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
        if (bytes == null || bytes.length > 2 * 1024 * 1024) return;
        try {
            Reader batch = new Reader(bytes, 0, bytes.length);
            while (batch.more()) {
                int tag = batch.tag();
                if (tag == 10) {
                    Reader item = batch.message();
                    while (item.more()) {
                        int innerTag = item.tag();
                        if (innerTag == 10) property(item.message(), nowMs);
                        else item.skip(innerTag);
                    }
                } else batch.skip(tag);
            }
        } catch (IllegalArgumentException ignored) {
            // A truncated/unsupported message must never turn unknown into a false OFF.
        }
    }

    private void property(Reader r, long nowMs) {
        int prop = 0, area = 0, availability = 0;
        Integer integer = null;
        Float number = null;
        long timestamp = 0;
        while (r.more()) {
            int tag = r.tag();
            switch (tag) {
                case 8: prop = (int) r.varint(); break;
                case 24: timestamp = r.varint(); break;
                case 32: area = (int) r.varint(); break;
                case 80: availability = (int) r.varint(); break;
                case 40: {
                    long v = r.varint();
                    if (integer == null) integer = (int) ((v >>> 1) ^ -(v & 1));
                    break;
                }
                case 42: {
                    Reader packed = r.message();
                    while (packed.more()) {
                        long v = packed.varint();
                        if (integer == null) integer = (int) ((v >>> 1) ^ -(v & 1));
                    }
                    break;
                }
                case 61: {
                    float v = Float.intBitsToFloat(r.fixed32());
                    if (number == null) number = v;
                    break;
                }
                case 58: {
                    Reader packed = r.message();
                    while (packed.more()) {
                        float v = Float.intBitsToFloat(packed.fixed32());
                        if (number == null) number = v;
                    }
                    break;
                }
                default: r.skip(tag);
            }
        }
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

    private static final class Reader {
        final byte[] data;
        final int end;
        int pos;
        Reader(byte[] data, int pos, int end) { this.data = data; this.pos = pos; this.end = end; }
        boolean more() { return pos < end; }
        int tag() {
            long value = varint();
            if (value <= 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException();
            return (int) value;
        }
        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new IllegalArgumentException();
                int b = data[pos++] & 255;
                if (shift == 63 && (b & 254) != 0) throw new IllegalArgumentException();
                value |= (long) (b & 127) << shift;
                if ((b & 128) == 0) return value;
            }
            throw new IllegalArgumentException();
        }
        int fixed32() {
            require(4);
            int value = (data[pos] & 255) | (data[pos + 1] & 255) << 8
                    | (data[pos + 2] & 255) << 16 | (data[pos + 3] & 255) << 24;
            pos += 4;
            return value;
        }
        Reader message() {
            long size = varint();
            if (size < 0 || size > end - pos) throw new IllegalArgumentException();
            Reader child = new Reader(data, pos, pos + (int) size);
            pos = child.end;
            return child;
        }
        void require(int size) { if (size > end - pos) throw new IllegalArgumentException(); }
        void skip(int tag) {
            switch (tag & 7) {
                case 0: varint(); return;
                case 1: require(8); pos += 8; return;
                case 2: message(); return;
                case 5: require(4); pos += 4; return;
                default: throw new IllegalArgumentException();
            }
        }
    }
}
