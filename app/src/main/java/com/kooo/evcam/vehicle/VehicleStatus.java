package com.kooo.evcam.vehicle;

/** Immutable display snapshot. Unknown is distinct from an inactive signal. */
public final class VehicleStatus {
    public static final VehicleStatus UNKNOWN = new VehicleStatus(-1, -1, -1, -1);
    public final int speedKmh;
    public final int brake;
    public final int left;
    public final int right;

    public VehicleStatus(int speedKmh, int brake, int left, int right) {
        this.speedKmh = speedKmh;
        this.brake = brake;
        this.left = left;
        this.right = right;
    }

    public String displayText() {
        return "车速 " + (speedKmh < 0 ? "--" : speedKmh) + " km/h   刹车 " + label(brake)
                + "   左 " + label(left) + "   右 " + label(right);
    }

    private static String label(int value) {
        return value < 0 ? "未知" : value == 0 ? "关" : "开";
    }
}
