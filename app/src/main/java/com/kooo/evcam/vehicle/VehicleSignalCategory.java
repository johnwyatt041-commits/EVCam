package com.kooo.evcam.vehicle;

import androidx.annotation.Nullable;

/**
 * T01 收窄后的核心车辆信号契约：仅保留 4 类核心信号，不引入 Smartcam Ler3 的 48 路全量信号。
 */
public enum VehicleSignalCategory {
    /** 档位（GEAR_SELECTION / CURRENT_GEAR） */
    GEAR,
    /** 车速（PERF_VEHICLE_SPEED） */
    SPEED,
    /** 四车门状态（DOOR_POS / NATIVE DOOR_OPEN & DOOR_CLOSE） */
    DOORS,
    /** 转向灯（TURN_SIGNAL_STATE / CarSignalManager / NATIVE TURN） */
    TURN_SIGNALS;

    public static final int PROP_GEAR_SELECTION = 0x11400400;
    public static final int PROP_CURRENT_GEAR = 0x11400401;
    public static final int PROP_PERF_VEHICLE_SPEED = 0x11600207;
    public static final int PROP_TURN_SIGNAL_STATE = 0x11400408;
    public static final int PROP_DOOR_POS = 0x16200b00;

    // 与 VhalNative 保持二进制值一致，但避免在纯 JVM 测试中触发 native 库加载
    public static final int NATIVE_EVT_TURN_SIGNAL = 1;
    public static final int NATIVE_EVT_DOOR_OPEN = 2;
    public static final int NATIVE_EVT_DOOR_CLOSE = 3;
    public static final int NATIVE_EVT_SPEED = 4;

    public static final int NATIVE_DIR_NONE = 0;
    public static final int NATIVE_DIR_LEFT = 1;
    public static final int NATIVE_DIR_RIGHT = 2;

    public static final int NATIVE_DOOR_FL = 1;
    public static final int NATIVE_DOOR_FR = 2;
    public static final int NATIVE_DOOR_RL = 3;
    public static final int NATIVE_DOOR_RR = 4;

    // AOSP VehicleAreaDoor 位掩码
    public static final int AREA_DOOR_ROW_1_LEFT = 0x00000001;
    public static final int AREA_DOOR_ROW_1_RIGHT = 0x00000004;
    public static final int AREA_DOOR_ROW_2_LEFT = 0x00000010;
    public static final int AREA_DOOR_ROW_2_RIGHT = 0x00000040;

    /**
     * 将 AOSP/E5 VHAL 属性 ID 映射至 4 类允许列表；不在 4 类范围内返回 null（直接丢弃）。
     */
    @Nullable
    public static VehicleSignalCategory classifyPropertyId(int propertyId) {
        switch (propertyId) {
            case PROP_GEAR_SELECTION:
            case PROP_CURRENT_GEAR:
                return GEAR;
            case PROP_PERF_VEHICLE_SPEED:
                return SPEED;
            case PROP_DOOR_POS:
                return DOORS;
            case PROP_TURN_SIGNAL_STATE:
                return TURN_SIGNALS;
            default:
                return null;
        }
    }

    /** 判断某 VHAL 属性是否在收窄后的 4 类信号白名单内。 */
    public static boolean isAcceptedPropertyId(int propertyId) {
        return classifyPropertyId(propertyId) != null;
    }

    /** 将 native 事件类型归类至 4 类允许列表；无关事件（如自定义键等）返回 null。 */
    @Nullable
    public static VehicleSignalCategory classifyNativeEvent(int nativeEventType) {
        switch (nativeEventType) {
            case NATIVE_EVT_SPEED:
                return SPEED;
            case NATIVE_EVT_DOOR_OPEN:
            case NATIVE_EVT_DOOR_CLOSE:
                return DOORS;
            case NATIVE_EVT_TURN_SIGNAL:
                return TURN_SIGNALS;
            default:
                return null;
        }
    }
}
