package com.kooo.evcam.camera;

import android.graphics.Bitmap;
import android.opengl.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.vehicle.VehicleStatus;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Exercises the actual shader/texture path with synthetic states, without a vehicle connection. */
@RunWith(AndroidJUnit4.class)
public class VehicleStatusWatermarkGlTest {
    @Test public void drawsAndUpdatesSmallOverlayWithoutChangingTheRestOfTheVideo() throws Exception {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertTrue(EGL14.eglInitialize(display, new int[2], 0, new int[2], 0));
        EGLConfig[] configs = new EGLConfig[1];
        int[] attributes = {EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE};
        assertTrue(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, new int[1], 0));
        EGLContext context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE}, 0);
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH,1280,EGL14.EGL_HEIGHT,800,EGL14.EGL_NONE}, 0);
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
        AtomicReference<VehicleStatus> status = new AtomicReference<>(VehicleStatus.UNKNOWN);
        try (VehicleStatusWatermark overlay = new VehicleStatusWatermark(status::get)) {
            Bitmap unknown = render(overlay);
            assertEquals(0xff336699, unknown.getPixel(1000, 100));
            assertNotEquals(0xff336699, unknown.getPixel(15, 800 - 15));
            save(unknown, "status-watermark-unknown.png");
            status.set(new VehicleStatus(56, 1, 1, 0));
            Bitmap active = render(overlay);
            assertEquals(0xff336699, active.getPixel(1000, 100));
            assertFalse(unknown.sameAs(active));
            save(active, "status-watermark-active.png");
            Bitmap repeated = render(overlay);
            assertTrue(active.sameAs(repeated));
            unknown.recycle(); active.recycle(); repeated.recycle();
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    private static Bitmap render(VehicleStatusWatermark overlay) {
        GLES20.glViewport(0, 0, 1280, 800);
        GLES20.glClearColor(0.2f, 0.4f, 0.6f, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        overlay.draw(1280, 800);
        ByteBuffer pixels = ByteBuffer.allocateDirect(1280 * 800 * 4);
        GLES20.glReadPixels(0, 0, 1280, 800, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        int[] argb = new int[1280 * 800];
        for (int y = 0; y < 800; y++) for (int x = 0; x < 1280; x++) {
            int r = pixels.get() & 255, g = pixels.get() & 255, b = pixels.get() & 255, a = pixels.get() & 255;
            argb[(799 - y) * 1280 + x] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return Bitmap.createBitmap(argb, 1280, 800, Bitmap.Config.ARGB_8888);
    }

    private static void save(Bitmap image, String name) throws Exception {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
    }
}
