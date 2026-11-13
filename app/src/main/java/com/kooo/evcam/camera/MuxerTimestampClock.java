package com.kooo.evcam.camera;

/** Keeps real encoder timing, with a fresh zero point for each MP4 segment. */
final class MuxerTimestampClock {
    private long firstPtsUs = Long.MIN_VALUE;
    private long lastPtsUs = -1;
    private long correctedSamples;

    void reset() {
        firstPtsUs = Long.MIN_VALUE;
        lastPtsUs = -1;
        correctedSamples = 0;
    }

    long normalize(long encoderPtsUs) {
        if (firstPtsUs == Long.MIN_VALUE) firstPtsUs = encoderPtsUs;
        long ptsUs = encoderPtsUs - firstPtsUs;
        // Recording encoders are configured without B frames. Do not let a duplicate
        // or invalid trailing timestamp send the MP4 timeline backwards.
        if (ptsUs <= lastPtsUs) {
            ptsUs = lastPtsUs + 1;
            correctedSamples++;
        }
        lastPtsUs = ptsUs;
        return ptsUs;
    }

    long getCorrectedSamples() {
        return correctedSamples;
    }

    long getLastPtsUs() {
        return lastPtsUs;
    }
}
