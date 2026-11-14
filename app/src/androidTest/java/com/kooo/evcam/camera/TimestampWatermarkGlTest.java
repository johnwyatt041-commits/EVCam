package com.kooo.evcam.camera;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaCodecInfo;
import android.opengl.GLES20;
import android.view.Surface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Checks real GLES storage/update/reinitialization, beyond the JVM timestamp cache test. */
@RunWith(AndroidJUnit4.class)
public class TimestampWatermarkGlTest {
    @Test public void textureUpdatesAcrossSecondsAndReinitializesWithoutGlErrors() throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType("video/avc");
        Surface output = null;
        EglSurfaceEncoder encoder = new EglSurfaceEncoder("timestamp-regression", 640, 480);
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 640, 480);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 500000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            output = codec.createInputSurface();
            encoder.initialize(output);
            encoder.setWatermarkEnabled(true);
            Method update = EglSurfaceEncoder.class.getDeclaredMethod("updateWatermarkBitmap");
            update.setAccessible(true);
            Field time = EglSurfaceEncoder.class.getDeclaredField("lastWatermarkTime");
            time.setAccessible(true);
            Field bitmap = EglSurfaceEncoder.class.getDeclaredField("watermarkBitmap");
            bitmap.setAccessible(true);
            Bitmap reused = (Bitmap) bitmap.get(encoder);
            String before = (String) time.get(encoder);
            boolean hasText = false;
            for (int y = 0; y < reused.getHeight(); y++) for (int x = 0; x < reused.getWidth(); x++) {
                if ((reused.getPixel(x, y) >>> 24) != 0) hasText = true;
            }
            assertTrue("Timestamp bitmap is empty", hasText);
            Thread.sleep(1100);
            update.invoke(encoder);
            assertNotEquals(before, time.get(encoder));
            assertSame(reused, bitmap.get(encoder));
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            encoder.release();
            encoder.initialize(output);
            encoder.setWatermarkEnabled(true);
            assertFalse(((Bitmap) bitmap.get(encoder)).isRecycled());
            assertFalse(((String) time.get(encoder)).isEmpty());
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        } finally {
            encoder.release();
            if (output != null) output.release();
            codec.release();
        }
    }
}
