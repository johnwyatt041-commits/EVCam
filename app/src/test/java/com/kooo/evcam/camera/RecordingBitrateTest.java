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
}
