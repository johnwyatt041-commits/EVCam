package com.kooo.evcam.camera;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class RecordingBitrateTest {
    @Test public void initialHevcRateDoesNotDependOnPreviousCodecMime() throws Exception {
        CodecVideoRecorder recorder = new CodecVideoRecorder("right", 1280, 800);
        Method rate = CodecVideoRecorder.class.getDeclaredMethod("calculateOptimalBitrate", String.class);
        rate.setAccessible(true);
        int avc = (int) rate.invoke(recorder, "video/avc");
        int hevc = (int) rate.invoke(recorder, "video/hevc");
        assertTrue(hevc >= 1000000);
        assertTrue(hevc < avc);
        assertEquals(hevc, (int) rate.invoke(recorder, "video/hevc"));
    }

    @Test public void explicitUserBitrateTakesEffectForBothAvcAndHevc() {
        CodecVideoRecorder low = new CodecVideoRecorder("front", 1280, 800);
        low.setBitRate(1_500_000);
        CodecVideoRecorder mid = new CodecVideoRecorder("front", 1280, 800);
        mid.setBitRate(3_000_000);
        CodecVideoRecorder high = new CodecVideoRecorder("front", 1280, 800);
        high.setBitRate(4_500_000);

        assertEquals(1_500_000, low.resolveTargetBitrate("video/avc"));
        assertEquals(3_000_000, mid.resolveTargetBitrate("video/avc"));
        assertEquals(4_500_000, high.resolveTargetBitrate("video/avc"));

        int hevcLow = low.resolveTargetBitrate("video/hevc");
        int hevcMid = mid.resolveTargetBitrate("video/hevc");
        int hevcHigh = high.resolveTargetBitrate("video/hevc");
        assertTrue("HEVC bitrate must scale down from AVC", hevcLow < 1_500_000);
        assertTrue("HEVC low < mid < high", hevcLow < hevcMid && hevcMid < hevcHigh);
        assertEquals(800_000, hevcLow);
        assertEquals(1_700_000, hevcMid);
        assertEquals(2_500_000, hevcHigh);
    }

    @Test public void resolveTargetBitrateClampsBoundsAndFallsBackWhenUnset() {
        CodecVideoRecorder recorder = new CodecVideoRecorder("back", 1280, 800);
        recorder.setBitRate(20_000_000);
        assertEquals(12_000_000, recorder.resolveTargetBitrate("video/avc"));
        assertEquals(8_000_000, recorder.resolveTargetBitrate("video/hevc"));

        recorder.setBitRate(100_000);
        assertEquals(500_000, recorder.resolveTargetBitrate("video/avc"));
        assertEquals(500_000, recorder.resolveTargetBitrate("video/hevc"));

        CodecVideoRecorder unset = new CodecVideoRecorder("left", 1280, 800);
        assertTrue(unset.resolveTargetBitrate("video/hevc") >= 1_000_000);
        assertTrue(unset.resolveTargetBitrate("video/avc") > unset.resolveTargetBitrate("video/hevc"));
    }
}
