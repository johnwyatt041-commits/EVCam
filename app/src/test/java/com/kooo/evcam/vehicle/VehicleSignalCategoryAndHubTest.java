package com.kooo.evcam.vehicle;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 T01 收窄后的 4 类核心车辆信号白名单（档位 / 车速 / 4 车门 / 转向灯）与 Hub 行为。
 */
public class VehicleSignalCategoryAndHubTest {

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
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static byte[] field(int tag, long value) {
        return concat(varint(tag), varint(value));
    }

    private static byte[] msg(int tag, byte[] bytes) {
        return concat(field(tag, bytes.length), bytes);
    }

    private static byte[] intProp(int id, int areaId, int rawValue, long timestamp) {
        byte[] prop = concat(
                field(8, id),
                field(24, timestamp),
                areaId != 0 ? field(32, areaId) : new byte[0],
                field(80, 0),
                field(40, ((long) rawValue << 1) ^ (rawValue >> 31))
        );
        return msg(10, msg(10, prop));
    }

    private static byte[] floatProp(int id, float value, long timestamp) {
        int bits = Float.floatToIntBits(value);
        byte[] bytes = {(byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24)};
        byte[] prop = concat(field(8, id), field(24, timestamp), field(80, 0), msg(58, bytes));
        return msg(10, msg(10, prop));
    }

    @Test
    public void categoryAllowlistAcceptsOnlyFourCoreCategories() {
        assertEquals(VehicleSignalCategory.GEAR, VehicleSignalCategory.classifyPropertyId(VehicleSignalCategory.PROP_GEAR_SELECTION));
        assertEquals(VehicleSignalCategory.GEAR, VehicleSignalCategory.classifyPropertyId(VehicleSignalCategory.PROP_CURRENT_GEAR));
        assertEquals(VehicleSignalCategory.SPEED, VehicleSignalCategory.classifyPropertyId(VehicleSignalCategory.PROP_PERF_VEHICLE_SPEED));
        assertEquals(VehicleSignalCategory.DOORS, VehicleSignalCategory.classifyPropertyId(VehicleSignalCategory.PROP_DOOR_POS));
        assertEquals(VehicleSignalCategory.TURN_SIGNALS, VehicleSignalCategory.classifyPropertyId(VehicleSignalCategory.PROP_TURN_SIGNAL_STATE));

        // 明确排除制动踏板、手刹、发动机转速、胎压、HVAC、EV 电量、ADAS 与自定义按键
        assertNull(VehicleSignalCategory.classifyPropertyId(0x11600305)); // BRAKE_PEDAL_POSITION
        assertNull(VehicleSignalCategory.classifyPropertyId(0x11200402)); // PARKING_BRAKE_ON
        assertNull(VehicleSignalCategory.classifyPropertyId(0x11600301)); // ENGINE_RPM
        assertNull(VehicleSignalCategory.classifyPropertyId(0x15400503)); // HVAC_TEMPERATURE_SET
        assertNull(VehicleSignalCategory.classifyPropertyId(0x11600309)); // EV_BATTERY_LEVEL
        assertNull(VehicleSignalCategory.classifyPropertyId(0x1760030c)); // TIRE_PRESSURE
        assertFalse(VehicleSignalCategory.isAcceptedPropertyId(0x11200403));

        // native 事件同样忽略自定义键(5)等非 4 类事件
        assertEquals(VehicleSignalCategory.TURN_SIGNALS, VehicleSignalCategory.classifyNativeEvent(VehicleSignalCategory.NATIVE_EVT_TURN_SIGNAL));
        assertEquals(VehicleSignalCategory.DOORS, VehicleSignalCategory.classifyNativeEvent(VehicleSignalCategory.NATIVE_EVT_DOOR_OPEN));
        assertEquals(VehicleSignalCategory.DOORS, VehicleSignalCategory.classifyNativeEvent(VehicleSignalCategory.NATIVE_EVT_DOOR_CLOSE));
        assertEquals(VehicleSignalCategory.SPEED, VehicleSignalCategory.classifyNativeEvent(VehicleSignalCategory.NATIVE_EVT_SPEED));
        assertNull(VehicleSignalCategory.classifyNativeEvent(5)); // EVT_CUSTOM_KEY
    }

    @Test
    public void hubDecodesFourCoreSignalsIgnoresNonCorePropertiesAndResetsCleanly() {
        VehicleSignalHub hub = new VehicleSignalHub();
        List<VehicleSignalSnapshot> received = new ArrayList<>();
        hub.addListener(received::add);

        // 非 4 类冗余属性直接丢弃，不产生状态快照或通知
        hub.onRawBatch(concat(
                floatProp(0x11600305, 80f, 100L), // brake pedal
                floatProp(0x11600309, 65f, 101L)  // EV battery
        ), 1000L);
        assertEquals(0, received.size());
        assertEquals(VehicleSignalSnapshot.UNKNOWN, hub.getSnapshot(1000L));

        // 解码 P 档 + 36 km/h + 左前门打开 + 左转向灯
        hub.onRawBatch(concat(
                intProp(VehicleSignalCategory.PROP_GEAR_SELECTION, 0, 0x0004, 200L),
                floatProp(VehicleSignalCategory.PROP_PERF_VEHICLE_SPEED, 10f, 200L),
                intProp(VehicleSignalCategory.PROP_DOOR_POS, VehicleSignalCategory.AREA_DOOR_ROW_1_LEFT, 1, 200L),
                intProp(VehicleSignalCategory.PROP_TURN_SIGNAL_STATE, 0, 2, 200L)
        ), 2000L);

        VehicleSignalSnapshot s = hub.getSnapshot(2000L);
        assertTrue(s.isParkGear());
        assertEquals(36, s.speedKmh);
        assertEquals(1, s.doorFl);
        assertEquals(-1, s.doorFr);
        assertTrue(s.hasAnyDoorOpen());
        assertEquals(1, s.leftTurn);
        assertEquals(0, s.rightTurn);
        assertEquals(1, received.size());

        // 较旧时间戳不能覆盖较新档位
        hub.onRawBatch(intProp(VehicleSignalCategory.PROP_GEAR_SELECTION, 0, 0x0008, 150L), 2100L);
        assertEquals(VehicleSignalSnapshot.Gear.PARK, hub.getSnapshot(2100L).gear);

        // Native 关左前门 + 开右后门 + 忽略自定义键(5)
        hub.onNativeEvents(new int[]{
                3,
                VehicleSignalCategory.NATIVE_EVT_DOOR_CLOSE, VehicleSignalCategory.NATIVE_DOOR_FL, 0,
                VehicleSignalCategory.NATIVE_EVT_DOOR_OPEN, VehicleSignalCategory.NATIVE_DOOR_RR, 0,
                5, 101, 1
        }, 2200L);
        s = hub.getSnapshot(2200L);
        assertEquals(0, s.doorFl);
        assertEquals(1, s.doorRr);
        assertTrue(s.hasAnyDoorOpen());

        // 外部 CarSignalManager 同步双闪，以及车速 3 秒超时退回未知
        hub.onTurnSignalState(1, 1);
        s = hub.getSnapshot(6000L);
        assertEquals(1, s.leftTurn);
        assertEquals(1, s.rightTurn);
        assertEquals(-1, s.speedKmh);

        // 断连或彻底退出时重置为 UNKNOWN 且幂等
        int countBeforeReset = received.size();
        hub.reset();
        assertEquals(VehicleSignalSnapshot.UNKNOWN, hub.getSnapshot(6001L));
        assertEquals(countBeforeReset + 1, received.size());
        hub.reset();
        assertEquals(countBeforeReset + 1, received.size());
    }
}
