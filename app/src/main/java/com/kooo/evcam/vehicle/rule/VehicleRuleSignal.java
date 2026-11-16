package com.kooo.evcam.vehicle.rule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.vehicle.VehicleSignalCategory;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;

/**
 * 规则引擎可用信号枚举：严格收窄到 4 类白名单核心信号（档位 / 车速 / 四车门 / 转向灯）。
 */
public enum VehicleRuleSignal {
    GEAR("当前档位", VehicleSignalCategory.GEAR, "1=N挡, 2=R挡, 4=P挡, 8=D挡"),
    SPEED("车速 (km/h)", VehicleSignalCategory.SPEED, "整数 km/h（>= 0）"),
    DOOR_FL("左前门 (主驾)", VehicleSignalCategory.DOORS, "0=关, 1=开"),
    DOOR_FR("右前门 (副驾)", VehicleSignalCategory.DOORS, "0=关, 1=开"),
    DOOR_RL("左后门", VehicleSignalCategory.DOORS, "0=关, 1=开"),
    DOOR_RR("右后门", VehicleSignalCategory.DOORS, "0=关, 1=开"),
    ANY_DOOR("任意车门", VehicleSignalCategory.DOORS, "0=全关, 1=任一打开"),
    TURN_SIGNAL("转向灯状态", VehicleSignalCategory.TURN_SIGNALS, "0=熄灭, 1=左转, 2=右转, 3=双闪");

    public static final int GEAR_UNKNOWN = 0;
    public static final int GEAR_NEUTRAL = 1;
    public static final int GEAR_REVERSE = 2;
    public static final int GEAR_PARK = 4;
    public static final int GEAR_DRIVE = 8;
    public static final int GEAR_OTHER = 16;

    public static final int TURN_OFF = 0;
    public static final int TURN_LEFT = 1;
    public static final int TURN_RIGHT = 2;
    public static final int TURN_HAZARD = 3;

    public final String label;
    public final VehicleSignalCategory category;
    public final String valueHint;

    VehicleRuleSignal(@NonNull String label,
                      @NonNull VehicleSignalCategory category,
                      @NonNull String valueHint) {
        this.label = label;
        this.category = category;
        this.valueHint = valueHint;
    }

    /**
     * 从统一 4 类快照中提取可比较的整数值；信号未知/失效时返回 null（不触发规则）。
     */
    @Nullable
    public Integer extractValue(@Nullable VehicleSignalSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        switch (this) {
            case GEAR:
                return gearToCode(snapshot.gear);
            case SPEED:
                return snapshot.speedKmh >= 0 ? snapshot.speedKmh : null;
            case DOOR_FL:
                return snapshot.doorFl >= 0 ? snapshot.doorFl : null;
            case DOOR_FR:
                return snapshot.doorFr >= 0 ? snapshot.doorFr : null;
            case DOOR_RL:
                return snapshot.doorRl >= 0 ? snapshot.doorRl : null;
            case DOOR_RR:
                return snapshot.doorRr >= 0 ? snapshot.doorRr : null;
            case ANY_DOOR:
                if (snapshot.doorFl < 0 && snapshot.doorFr < 0
                        && snapshot.doorRl < 0 && snapshot.doorRr < 0) {
                    return null;
                }
                return snapshot.hasAnyDoorOpen() ? 1 : 0;
            case TURN_SIGNAL:
                if (snapshot.leftTurn < 0 && snapshot.rightTurn < 0) {
                    return null;
                }
                boolean left = snapshot.leftTurn == 1;
                boolean right = snapshot.rightTurn == 1;
                if (left && right) {
                    return TURN_HAZARD;
                }
                if (left) {
                    return TURN_LEFT;
                }
                if (right) {
                    return TURN_RIGHT;
                }
                return TURN_OFF;
            default:
                return null;
        }
    }

    @Nullable
    public static Integer gearToCode(@Nullable VehicleSignalSnapshot.Gear gear) {
        if (gear == null) {
            return null;
        }
        switch (gear) {
            case NEUTRAL:
                return GEAR_NEUTRAL;
            case REVERSE:
                return GEAR_REVERSE;
            case PARK:
                return GEAR_PARK;
            case DRIVE:
                return GEAR_DRIVE;
            case OTHER:
                return GEAR_OTHER;
            case UNKNOWN:
            default:
                return null;
        }
    }

    @NonNull
    public String formatValue(int value) {
        switch (this) {
            case GEAR:
                switch (value) {
                    case GEAR_NEUTRAL:
                        return "N挡(1)";
                    case GEAR_REVERSE:
                        return "R挡(2)";
                    case GEAR_PARK:
                        return "P挡(4)";
                    case GEAR_DRIVE:
                        return "D挡(8)";
                    default:
                        return "挡位(" + value + ")";
                }
            case SPEED:
                return value + " km/h";
            case DOOR_FL:
            case DOOR_FR:
            case DOOR_RL:
            case DOOR_RR:
                return value == 1 ? "开(1)" : (value == 0 ? "关(0)" : String.valueOf(value));
            case ANY_DOOR:
                return value == 1 ? "任一打开(1)" : (value == 0 ? "全部关闭(0)" : String.valueOf(value));
            case TURN_SIGNAL:
                switch (value) {
                    case TURN_OFF:
                        return "熄灭(0)";
                    case TURN_LEFT:
                        return "左转(1)";
                    case TURN_RIGHT:
                        return "右转(2)";
                    case TURN_HAZARD:
                        return "双闪(3)";
                    default:
                        return String.valueOf(value);
                }
            default:
                return String.valueOf(value);
        }
    }

    @NonNull
    public static VehicleRuleSignal fromName(@Nullable String name) {
        if (name != null) {
            for (VehicleRuleSignal value : values()) {
                if (value.name().equalsIgnoreCase(name.trim())) {
                    return value;
                }
            }
        }
        return GEAR;
    }
}
