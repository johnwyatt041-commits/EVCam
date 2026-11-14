package com.kooo.evcam.camera;

import android.media.MediaMuxer;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Run with MANAGE_EXTERNAL_STORAGE denied, unlike earlier broad-permission emulator checks. */
@RunWith(AndroidJUnit4.class)
public class ScopedVideoStorageTest {
    @Test public void publicDcimRequiresRecognizedVideoSuffixForPendingMuxer() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= 30);
        assertFalse("Regression requires scoped storage", Environment.isExternalStorageManager());
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "EVCam_StorageRegression");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        String stamp = "probe_" + System.nanoTime();
        File opaque = new File(dir, stamp + ".mp4.pending");
        File media = new File(dir, "." + stamp + ".pending.mp4");
        File published = new File(dir, stamp + ".mp4");
        try {
            boolean blocked = false;
            try {
                MediaMuxer old = new MediaMuxer(opaque.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                old.release();
            } catch (java.io.IOException denied) {
                blocked = true;
                Log.i("ScopedStorageProbe", "Old opaque pending path denied: " + denied);
            }
            assertTrue("Old pending suffix did not reproduce restriction", blocked);
            MediaMuxer fixed = new MediaMuxer(media.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            fixed.release();
            assertTrue(media.exists());
            assertTrue(media.isHidden());
            Log.i("ScopedStorageProbe", "Recognized hidden pending MP4 can be created: " + media);
            assertTrue("Completed MP4 must publish within the same DCIM directory", media.renameTo(published));
        } finally {
            opaque.delete();
            media.delete();
            published.delete();
            dir.delete();
        }
    }
}
