package com.kooo.evcam.vehicle;

/**
 * Reads only selected global properties from the existing E5 VHAL stream.
 * Envelope: repeated field 1 -> field 1 VehiclePropValue (verified against vhal_decoder).
 * Value fields follow AOSP VehicleHalProto.proto: prop=1, area=4, sint32=5, float=7,
 * status=10. No dependency on third-party APK code or native libraries.
 */
public final class VehicleStatusDecoder {
    public static final int SPEED = 0x11600207;
    public static final int TURN = 0x11400408;
    // Standard pedal percentage, NOT parking brake, regeneration or inferred deceleration.
    public static final int BRAKE_PEDAL = 0x11600310;
    private VehicleStatus status = VehicleStatus.UNKNOWN;
    private long speedTime;
    private long brakeTime;
    private long speedTimestamp;
    private long brakeTimestamp;
    private long turnTimestamp;

    public synchronized void reset() {
        status = VehicleStatus.UNKNOWN;
        speedTime = brakeTime = speedTimestamp = brakeTimestamp = turnTimestamp = 0;
    }

    public synchronized VehicleStatus snapshot(long nowMs) {
        // Pedal percentage and speed are continuous. Turn is an on-change signal.
        int speed = nowMs - speedTime > 3000 ? -1 : status.speedKmh;
        int brake = nowMs - brakeTime > 3000 ? -1 : status.brake;
        if (speed != status.speedKmh || brake != status.brake) {
            status = new VehicleStatus(speed, brake, status.left, status.right);
        }
        return status;
    }

    public synchronized void accept(byte[] bytes, long nowMs) {
        VhalProtoBatchReader.parseBatch(bytes,
                (prop, area, availability, timestamp, integer, number) ->
                        applyProperty(prop, area, availability, timestamp, integer, number, nowMs));
    }

    private void applyProperty(
            int prop, int area, int availability, long timestamp, Integer integer, Float number, long nowMs) {
        if (area != 0) return;
        int speed = status.speedKmh, brake = status.brake, left = status.left, right = status.right;
        if (prop == SPEED) {
            if (timestamp > 0 && timestamp < speedTimestamp) return;
            speedTimestamp = timestamp;
            speed = availability == 0 && number != null && Float.isFinite(number)
                    && Math.abs(number) <= 120 ? Math.round(Math.abs(number) * 3.6f) : -1;
            speedTime = nowMs;
        } else if (prop == BRAKE_PEDAL) {
            if (timestamp > 0 && timestamp < brakeTimestamp) return;
            brakeTimestamp = timestamp;
            brakeTime = nowMs;
            brake = availability == 0 && number != null && Float.isFinite(number)
                    && number >= 0 && number <= 100 ? (number > 0 ? 1 : 0) : -1;
        } else if (prop == TURN) {
            if (timestamp > 0 && timestamp < turnTimestamp) return;
            turnTimestamp = timestamp;
            // Legacy TURN_SIGNAL_STATE only defines NONE=0, RIGHT=1, LEFT=2.
            // Do not guess a vendor-specific hazard value.
            boolean known = availability == 0 && integer != null && integer >= 0 && integer <= 2;
            left = known ? (integer == 2 ? 1 : 0) : -1;
            right = known ? (integer == 1 ? 1 : 0) : -1;
        } else return;
        if (speed != status.speedKmh || brake != status.brake || left != status.left || right != status.right) {
            status = new VehicleStatus(speed, brake, left, right);
        }
    }
}
