package com.kooo.evcam.camera;

/** Limits encoding by capture time, never by when a delayed callback is delivered. */
final class FrameTimestampClock {
    static final long SKIP_FRAME = -1;
    private long intervalNs;
    private long firstSourceNs = Long.MIN_VALUE;
    private long lastSourceNs = Long.MIN_VALUE;
    private long lastSlot = -1;

    FrameTimestampClock(int frameRate) {
        setFrameRate(frameRate);
    }

    void setFrameRate(int frameRate) {
        if (frameRate <= 0) throw new IllegalArgumentException("frameRate must be positive");
        intervalNs = 1_000_000_000L / frameRate;
        if (lastSourceNs != Long.MIN_VALUE) {
            lastSlot = slot(lastSourceNs - firstSourceNs);
        }
    }

    long nextPresentationTimeNs(long sourceTimestampNs) {
        if (lastSourceNs != Long.MIN_VALUE && sourceTimestampNs <= lastSourceNs) {
            return SKIP_FRAME;
        }
        if (firstSourceNs == Long.MIN_VALUE) firstSourceNs = sourceTimestampNs;
        lastSourceNs = sourceTimestampNs;
        long elapsedNs = sourceTimestampNs - firstSourceNs;
        long slot = slot(elapsedNs);
        if (slot <= lastSlot) return SKIP_FRAME;
        lastSlot = slot;
        return elapsedNs;
    }

    private long slot(long elapsedNs) {
        // Round to the nearest slot so small sensor jitter does not halve the frame rate.
        return (elapsedNs + intervalNs / 2) / intervalNs;
    }
}
