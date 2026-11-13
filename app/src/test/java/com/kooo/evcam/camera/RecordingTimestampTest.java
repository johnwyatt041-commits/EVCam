package com.kooo.evcam.camera;

import org.junit.Test;

import static org.junit.Assert.*;

public class RecordingTimestampTest {
    @Test public void delayedCallbackBurstsDoNotDiscardDistinctCapturedFrames() {
        FrameTimestampClock clock = new FrameTimestampClock(25);
        for (int i = 0; i < 750; i++) {
            long jitterNs = i == 0 ? 0 : (i % 2 == 0 ? 500_000 : -500_000);
            long sourceNs = 1_000_000_000L + i * 40_000_000L + jitterNs;
            // These calls can be delivered together; wall-clock spacing is irrelevant.
            assertEquals(sourceNs - 1_000_000_000L, clock.nextPresentationTimeNs(sourceNs));
        }
    }

    @Test public void fasterSourceIsLimitedWithoutChangingAcceptedFrameTimes() {
        FrameTimestampClock clock = new FrameTimestampClock(25);
        int accepted = 0;
        for (int i = 0; i < 300; i++) {
            long sourceNs = 5_000_000_000L + Math.round(i * 1_000_000_000.0 / 30);
            long ptsNs = clock.nextPresentationTimeNs(sourceNs);
            if (ptsNs != FrameTimestampClock.SKIP_FRAME) {
                accepted++;
                assertEquals(sourceNs - 5_000_000_000L, ptsNs);
            }
        }
        assertTrue(accepted >= 249 && accepted <= 251);
    }

    @Test public void duplicateOrRegressingCaptureIsSkippedBeforeEncoding() {
        FrameTimestampClock clock = new FrameTimestampClock(25);
        assertEquals(0, clock.nextPresentationTimeNs(1_000_000_000L));
        assertEquals(FrameTimestampClock.SKIP_FRAME, clock.nextPresentationTimeNs(1_000_000_000L));
        assertEquals(FrameTimestampClock.SKIP_FRAME, clock.nextPresentationTimeNs(900_000_000L));
        assertEquals(40_000_000L, clock.nextPresentationTimeNs(1_040_000_000L));
    }

    @Test public void changingLimitDoesNotResetCaptureTimeline() {
        FrameTimestampClock clock = new FrameTimestampClock(25);
        clock.nextPresentationTimeNs(1_000_000_000L);
        clock.nextPresentationTimeNs(1_040_000_000L);
        clock.setFrameRate(15);
        long pts = clock.nextPresentationTimeNs(1_120_000_000L);
        assertEquals(120_000_000L, pts);
    }

    @Test public void lowFrameRateDoesNotCompressThirtySecondsIntoFourteenSeconds() {
        MuxerTimestampClock clock = new MuxerTimestampClock();
        long last = -1;
        for (int i = 0; i < 359; i++) {
            last = clock.normalize(7_000_000L + Math.round(i * 30_340_956.0 / 358));
        }
        assertEquals(30_340_956L, last);
    }

    @Test public void realGapsArePreservedAcrossRegularAndFinalSamples() {
        MuxerTimestampClock clock = new MuxerTimestampClock();
        assertEquals(0, clock.normalize(2_000_000));
        assertEquals(40_000, clock.normalize(2_040_000));
        assertEquals(240_000, clock.normalize(2_240_000));
        assertEquals(280_000, clock.normalize(2_280_000));
    }

    @Test public void malformedTrailingTimestampCannotJumpBackSixteenSeconds() {
        MuxerTimestampClock clock = new MuxerTimestampClock();
        clock.normalize(0);
        assertEquals(30_340_956, clock.normalize(30_340_956));
        assertEquals(30_340_957, clock.normalize(14_283_511));
        assertEquals(1, clock.getCorrectedSamples());
    }

    @Test public void nextSegmentHasIndependentZeroPoint() {
        MuxerTimestampClock clock = new MuxerTimestampClock();
        clock.normalize(0);
        clock.normalize(60_000_000);
        clock.reset();
        assertEquals(0, clock.normalize(60_050_000));
        assertEquals(80_000, clock.normalize(60_130_000));
    }
}
