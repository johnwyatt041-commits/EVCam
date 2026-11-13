package com.kooo.evcam.vehicle;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import static org.junit.Assert.*;

public class VehicleStatusDecoderTest {
    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int b = (int) (value & 127);
            value >>>= 7;
            out.write(value == 0 ? b : b | 128);
        } while (value != 0);
        return out.toByteArray();
    }
    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }
    private static byte[] field(int tag, long value) { return concat(varint(tag), varint(value)); }
    private static byte[] msg(int tag, byte[] bytes) { return concat(field(tag, bytes.length), bytes); }
    private static byte[] floatValue(float value, boolean packed) {
        int bits = Float.floatToIntBits(value);
        byte[] bytes = {(byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24)};
        return packed ? msg(58, bytes) : concat(varint(61), bytes);
    }
    private static byte[] property(int id, byte[] value, int availability, long timestamp) {
        byte[] prop = concat(field(8, id), field(24, timestamp), field(80, availability), value);
        return msg(10, msg(10, prop));
    }
    private static byte[] turn(int raw) { return property(VehicleStatusDecoder.TURN, field(40, raw * 2L), 0, 1); }

    @Test public void noDataMeansUnknownNotOff() {
        VehicleStatus s = new VehicleStatusDecoder().snapshot(0);
        assertEquals(-1, s.speedKmh);
        assertEquals(-1, s.brake);
        assertEquals(-1, s.left);
        assertTrue(s.displayText().contains("未知"));
    }

    @Test public void readsPackedSpeedAndPedalWithoutInferringBrakeFromSpeed() {
        VehicleStatusDecoder d = new VehicleStatusDecoder();
        d.accept(property(VehicleStatusDecoder.SPEED, floatValue(-10, true), 0, 1), 1000);
        assertEquals(36, d.snapshot(1000).speedKmh);
        assertEquals(-1, d.snapshot(1000).brake);
        d.accept(property(VehicleStatusDecoder.BRAKE_PEDAL, floatValue(25, false), 0, 1), 1000);
        assertEquals(1, d.snapshot(1000).brake);
        d.accept(property(VehicleStatusDecoder.BRAKE_PEDAL, floatValue(0, true), 0, 2), 1100);
        assertEquals(0, d.snapshot(1100).brake);
    }

    @Test public void legacyTurnEnumsDoNotSwapLeftAndRight() {
        VehicleStatusDecoder d = new VehicleStatusDecoder();
        d.accept(turn(2), 1000);
        assertEquals(1, d.snapshot(1000).left);
        assertEquals(0, d.snapshot(1000).right);
        d.accept(turn(1), 1000);
        assertEquals(0, d.snapshot(1000).left);
        assertEquals(1, d.snapshot(1000).right);
        d.accept(turn(0), 1000);
        assertEquals(0, d.snapshot(1000).left);
        assertEquals(0, d.snapshot(1000).right);
    }

    @Test public void unknownVendorTurnEnumIsNotRenderedAsOff() {
        VehicleStatusDecoder d = new VehicleStatusDecoder();
        d.accept(turn(3), 1000);
        assertEquals(-1, d.snapshot(1000).left);
        assertEquals(-1, d.snapshot(1000).right);
    }

    @Test public void repeatedValuesReuseDisplaySnapshot() {
        VehicleStatusDecoder d = new VehicleStatusDecoder();
        d.accept(turn(2), 1000);
        VehicleStatus s = d.snapshot(1000);
        d.accept(turn(2), 1100);
        assertSame(s, d.snapshot(1100));
    }

    @Test public void continuousSignalsExpireAndDisconnectClearsEverything() {
        VehicleStatusDecoder d = new VehicleStatusDecoder();
        d.accept(concat(turn(2), property(VehicleStatusDecoder.SPEED, floatValue(10, true), 0, 1),
                property(VehicleStatusDecoder.BRAKE_PEDAL, floatValue(5, true), 0, 1)), 1000);
        VehicleStatus s = d.snapshot(4001);
        assertEquals(-1, s.speedKmh);
        assertEquals(-1, s.brake);
        assertEquals(1, s.left);
        d.reset();
        assertSame(VehicleStatus.UNKNOWN, d.snapshot(4001));
    }

    @Test public void unavailableNonfiniteAndOutOfRangeAreUnknown() {
        VehicleStatusDecoder d = new VehicleStatusDecoder();
        d.accept(property(VehicleStatusDecoder.BRAKE_PEDAL, floatValue(1, true), 1, 1), 1000);
        assertEquals(-1, d.snapshot(1000).brake);
        d.accept(property(VehicleStatusDecoder.SPEED, floatValue(Float.NaN, true), 0, 1), 1000);
        assertEquals(-1, d.snapshot(1000).speedKmh);
        d.accept(property(VehicleStatusDecoder.BRAKE_PEDAL, floatValue(101, true), 0, 2), 1000);
        assertEquals(-1, d.snapshot(1000).brake);
    }

    @Test public void parkingBrakeIsNeverMistakenForPedalBrake() {
        VehicleStatusDecoder d = new VehicleStatusDecoder();
        d.accept(property(0x11200402, field(40, 2), 0, 1), 1000);
        assertEquals(-1, d.snapshot(1000).brake);
    }

    @Test public void olderSamplesCannotOverwriteNewerPedalState() {
        VehicleStatusDecoder d = new VehicleStatusDecoder();
        d.accept(property(VehicleStatusDecoder.BRAKE_PEDAL, floatValue(1, true), 0, 20), 1000);
        d.accept(property(VehicleStatusDecoder.BRAKE_PEDAL, floatValue(0, true), 0, 10), 1001);
        assertEquals(1, d.snapshot(1001).brake);
    }

    @Test public void truncatedOrOversizedInputsDoNotInventAState() {
        byte[] valid = turn(2);
        for (int length = 0; length < valid.length; length++) {
            VehicleStatusDecoder d = new VehicleStatusDecoder();
            d.accept(Arrays.copyOf(valid, length), 1000);
            assertSame(VehicleStatus.UNKNOWN, d.snapshot(1000));
        }
        VehicleStatusDecoder d = new VehicleStatusDecoder();
        d.accept(new byte[2 * 1024 * 1024 + 1], 1000);
        d.accept(new byte[]{10, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 127}, 1000);
        assertSame(VehicleStatus.UNKNOWN, d.snapshot(1000));
    }
}
