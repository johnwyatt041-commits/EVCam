package com.kooo.evcam.vehicle;

import java.util.Objects;

/**
 * 不可变的 4 类核心车辆信号快照（档位、车速、4 车门、转向灯）。
 * 状态约定：数值字段 -1 表示未知，0 表示关/关闭，1 表示开/开启。
 */
public final class VehicleSignalSnapshot {

    /** 标准化车辆档位。 */
    public enum Gear {
        UNKNOWN,
        PARK,
        REVERSE,
        NEUTRAL,
        DRIVE,
        OTHER;

        /** 按 AOSP VehicleGear 标准值解析。 */
        public static Gear fromVhalValue(int rawValue) {
            switch (rawValue) {
                case 0x0000:
                    return UNKNOWN;
                case 0x0001:
                    return NEUTRAL;
                case 0x0002:
                    return REVERSE;
                case 0x0004:
                    return PARK;
                case 0x0008:
                    return DRIVE;
                default:
                    if ((rawValue & 0x0004) != 0) {
                        return PARK;
                    }
                    if ((rawValue & 0x0002) != 0) {
                        return REVERSE;
                    }
                    if ((rawValue & 0x0008) != 0) {
                        return DRIVE;
                    }
                    if ((rawValue & 0x0001) != 0) {
                        return NEUTRAL;
                    }
                    return OTHER;
            }
        }
    }

    public static final VehicleSignalSnapshot UNKNOWN =
            new VehicleSignalSnapshot(Gear.UNKNOWN, -1, -1, -1, -1, -1, -1, -1, 0L);

    public final Gear gear;
    public final int speedKmh;
    public final int doorFl;
    public final int doorFr;
    public final int doorRl;
    public final int doorRr;
    public final int leftTurn;
    public final int rightTurn;
    public final long updatedAtMs;

    public VehicleSignalSnapshot(
            Gear gear,
            int speedKmh,
            int doorFl,
            int doorFr,
            int doorRl,
            int doorRr,
            int leftTurn,
            int rightTurn,
            long updatedAtMs) {
        this.gear = gear != null ? gear : Gear.UNKNOWN;
        this.speedKmh = speedKmh;
        this.doorFl = doorFl;
        this.doorFr = doorFr;
        this.doorRl = doorRl;
        this.doorRr = doorRr;
        this.leftTurn = leftTurn;
        this.rightTurn = rightTurn;
        this.updatedAtMs = updatedAtMs;
    }

    /** 四个车门中是否任意一个明确处于开启状态。 */
    public boolean hasAnyDoorOpen() {
        return doorFl == 1 || doorFr == 1 || doorRl == 1 || doorRr == 1;
    }

    /** 当前是否明确处于 P 档（驻车）。 */
    public boolean isParkGear() {
        return gear == Gear.PARK;
    }

    /** 判断排除时间戳外的语义状态是否完全一致。 */
    public boolean hasSameSignals(VehicleSignalSnapshot other) {
        if (other == null) {
            return false;
        }
        return gear == other.gear
                && speedKmh == other.speedKmh
                && doorFl == other.doorFl
                && doorFr == other.doorFr
                && doorRl == other.doorRl
                && doorRr == other.doorRr
                && leftTurn == other.leftTurn
                && rightTurn == other.rightTurn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VehicleSignalSnapshot)) return false;
        VehicleSignalSnapshot that = (VehicleSignalSnapshot) o;
        return speedKmh == that.speedKmh
                && doorFl == that.doorFl
                && doorFr == that.doorFr
                && doorRl == that.doorRl
                && doorRr == that.doorRr
                && leftTurn == that.leftTurn
                && rightTurn == that.rightTurn
                && gear == that.gear;
    }

    @Override
    public int hashCode() {
        return Objects.hash(gear, speedKmh, doorFl, doorFr, doorRl, doorRr, leftTurn, rightTurn);
    }
}
