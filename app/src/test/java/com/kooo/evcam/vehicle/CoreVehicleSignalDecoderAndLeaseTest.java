package com.kooo.evcam.vehicle;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.*;

/**
 * 覆盖 1.4 倒车挡位 VHAL 解码与协调器按需租约判定：
 * 1. tag 40/42 sint32 zigzag 线协议下 P/R/D/N 准确无错位；
 * 2. CoreVehicleSignalDecoder 兼容非零 areaId 且隔离 CURRENT_GEAR / GEAR_SELECTION；
 * 3. Gear.fromVhalValue 对负数无效值安全返回 UNKNOWN；
 * 4. VehicleAutomationCoordinator 在未开补盲/切换 logcat 转向模式下仍按需启动 VHAL。
 */
public class CoreVehicleSignalDecoderAndLeaseTest {

    @After
    public void tearDown() {
        VehicleSignalCategory.resetCustomPropertyIds();
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
        return out.toByteArray();
    }

    private static byte[] fieldVarint(int fieldNumber, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(varint((long) fieldNumber << 3));
        out.writeBytes(varint(value));
        return out.toByteArray();
    }

    private static byte[] fieldBytes(int fieldNumber, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(varint(((long) fieldNumber << 3) | 2L));
        out.writeBytes(varint(payload.length));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static long zigzag32(int value) {
        return Integer.toUnsignedLong((value << 1) ^ (value >> 31));
    }

    private static byte[] sintIntProp(int prop, int areaId, int availability, int rawIntValue, long tsNs, boolean packed) {
        ByteArrayOutputStream propMsg = new ByteArrayOutputStream();
        propMsg.writeBytes(fieldVarint(1, Integer.toUnsignedLong(prop)));
        if (tsNs != 0) {
            propMsg.writeBytes(fieldVarint(3, tsNs));
        }
        if (areaId != 0) {
            propMsg.writeBytes(fieldVarint(4, Integer.toUnsignedLong(areaId)));
        }
        if (packed) {
            propMsg.writeBytes(fieldBytes(5, varint(zigzag32(rawIntValue))));
        } else {
            propMsg.writeBytes(fieldVarint(5, zigzag32(rawIntValue)));
        }
        if (availability != 0) {
            propMsg.writeBytes(fieldVarint(10, availability));
        }
        return fieldBytes(1, fieldBytes(1, propMsg.toByteArray()));
    }

    @Test
    public void sint32Tag40AndPackedTag42DecodeStandardGearsWithoutParkReverseSwap() {
        CoreVehicleSignalDecoder decoder = new CoreVehicleSignalDecoder();

        assertTrue(decoder.acceptRawBatch(
                sintIntProp(VehicleSignalCategory.PROP_CURRENT_GEAR, 0, 0, 0x0004, 100L, false), 1000L));
        assertEquals(VehicleSignalSnapshot.Gear.PARK, decoder.snapshot(1000L).gear);

        assertTrue(decoder.acceptRawBatch(
                sintIntProp(VehicleSignalCategory.PROP_CURRENT_GEAR, 0, 0, 0x0002, 200L, true), 1100L));
        assertEquals(VehicleSignalSnapshot.Gear.REVERSE, decoder.snapshot(1100L).gear);

        assertTrue(decoder.acceptRawBatch(
                sintIntProp(VehicleSignalCategory.PROP_CURRENT_GEAR, 0, 0, 0x0008, 300L, false), 1200L));
        assertEquals(VehicleSignalSnapshot.Gear.DRIVE, decoder.snapshot(1200L).gear);

        assertTrue(decoder.acceptRawBatch(
                sintIntProp(VehicleSignalCategory.PROP_CURRENT_GEAR, 0, 0, 0x0001, 400L, true), 1300L));
        assertEquals(VehicleSignalSnapshot.Gear.NEUTRAL, decoder.snapshot(1300L).gear);
    }

    @Test
    public void acceptsNonZeroAreaIdAndIsolatesCurrentGearFromUnimplementedSelection() {
        CoreVehicleSignalDecoder decoder = new CoreVehicleSignalDecoder();

        // E5 CURRENT_GEAR with non-zero areaId=1 must be accepted.
        assertTrue(decoder.acceptRawBatch(
                sintIntProp(VehicleSignalCategory.PROP_CURRENT_GEAR, 1, 0, 0x0002, 100L, false), 2000L));
        assertEquals(VehicleSignalSnapshot.Gear.REVERSE, decoder.snapshot(2000L).gear);

        // An unimplemented / UNKNOWN GEAR_SELECTION (0 or UNAVAILABLE) at newer timestamp
        // must not overwrite the valid CURRENT_GEAR=REVERSE.
        assertFalse(decoder.acceptRawBatch(
                sintIntProp(VehicleSignalCategory.PROP_GEAR_SELECTION, 0, 0, 0x0000, 9999L, false), 2100L));
        assertEquals(VehicleSignalSnapshot.Gear.REVERSE, decoder.snapshot(2100L).gear);

        assertFalse(decoder.acceptRawBatch(
                sintIntProp(VehicleSignalCategory.PROP_GEAR_SELECTION, 0, 1, 0x0004, 10000L, false), 2200L));
        assertEquals(VehicleSignalSnapshot.Gear.REVERSE, decoder.snapshot(2200L).gear);

        // When CURRENT_GEAR genuinely moves to DRIVE, it updates immediately even with areaId=1.
        assertTrue(decoder.acceptRawBatch(
                sintIntProp(VehicleSignalCategory.PROP_CURRENT_GEAR, 1, 0, 0x0008, 200L, false), 2300L));
        assertEquals(VehicleSignalSnapshot.Gear.DRIVE, decoder.snapshot(2300L).gear);
    }

    @Test
    public void negativeVhalGearSentinelReturnsUnknownNotPark() {
        assertEquals(VehicleSignalSnapshot.Gear.UNKNOWN, VehicleSignalSnapshot.Gear.fromVhalValue(-1));
        assertEquals(VehicleSignalSnapshot.Gear.UNKNOWN, VehicleSignalSnapshot.Gear.fromVhalValue(0));
        assertEquals(VehicleSignalSnapshot.Gear.NEUTRAL, VehicleSignalSnapshot.Gear.fromVhalValue(1));
        assertEquals(VehicleSignalSnapshot.Gear.REVERSE, VehicleSignalSnapshot.Gear.fromVhalValue(2));
        assertEquals(VehicleSignalSnapshot.Gear.PARK, VehicleSignalSnapshot.Gear.fromVhalValue(4));
        assertEquals(VehicleSignalSnapshot.Gear.DRIVE, VehicleSignalSnapshot.Gear.fromVhalValue(8));
    }

    @Test
    public void vhalLeaseStartsWithoutBlindSpotAndIsNotBlockedByLogcatTurnMode() {
        // E5 reverse preview enabled even if blind spot is disabled or set to logcat turn mode.
        assertTrue(VehicleAutomationCoordinator.shouldAcquireVhalLease(
                false, false, false, true, false));
        // E5 active rules or R/D shift popup require VHAL lease.
        assertTrue(VehicleAutomationCoordinator.shouldAcquireVhalLease(
                false, true, false, false, false));
        assertTrue(VehicleAutomationCoordinator.shouldAcquireVhalLease(
                false, false, true, false, false));
        // Mode A / Mode C foreground gear switching acquires VHAL lease on demand.
        assertTrue(VehicleAutomationCoordinator.shouldAcquireVhalLease(
                false, false, false, false, true));
        // When all features off (even on E5), no unnecessary VHAL lease.
        assertFalse(VehicleAutomationCoordinator.shouldAcquireVhalLease(
                false, false, false, false, false));
        // CarSignalManager platforms (L6/L7) never spin VHAL gRPC loop.
        assertFalse(VehicleAutomationCoordinator.shouldAcquireVhalLease(
                true, true, true, true, true));
    }
}
