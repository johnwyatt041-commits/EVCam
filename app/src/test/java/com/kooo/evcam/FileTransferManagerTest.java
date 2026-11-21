package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import com.kooo.evcam.camera.ActiveRecordingRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FileTransferManagerTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void queuedTaskWakesWorkerAndTransfersSmallRecordingWithoutPolling() throws Exception {
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        Constructor<FileTransferManager> constructor = FileTransferManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        FileTransferManager manager = constructor.newInstance(context);
        Field running = FileTransferManager.class.getDeclaredField("isRunning");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(manager)).set(true);
        Handler worker = mock(Handler.class);
        Field handler = FileTransferManager.class.getDeclaredField("transferHandler");
        handler.setAccessible(true);
        handler.set(manager, worker);
        File source = files.newFile("short.mp4");
        byte[] content = new byte[8192];
        for (int i = 0; i < content.length; i++) content[i] = (byte) i;
        Files.write(source.toPath(), content);
        File target = new File(files.getRoot(), "copied.mp4");
        FileTransferManager.TransferCallback callback = mock(FileTransferManager.TransferCallback.class);

        manager.addTransferTask(source, target, callback);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(worker).post(task.capture());
        task.getValue().run();

        assertArrayEquals(content, Files.readAllBytes(target.toPath()));
        assertFalse(source.exists());
        verify(callback).onTransferComplete(source, target);
        verify(worker, never()).postDelayed(any(Runnable.class), anyLong());
        assertEquals(0, manager.getQueueSize());
    }

    @Test public void expiredTempFileAlreadyVerifiedOnUsbIsDeleted() throws Exception {
        File tempDir = files.newFolder("temp_video_verified");
        File usbDir = files.newFolder("usb_video_verified");
        File tempFile = new File(tempDir, "20261121_080000_front.mp4");
        File usbFile = new File(usbDir, "20261121_080000_front.mp4");
        byte[] data = new byte[4096];
        Files.write(tempFile.toPath(), data);
        Files.write(usbFile.toPath(), data);
        long nowMs = 10_000_000L;
        tempFile.setLastModified(nowMs - 2L * 3600L * 1000L); // >1h old

        List<File> requeued = new ArrayList<>();
        FileTransferManager.TempCleanupStats stats = FileTransferManager.inspectAndCleanTempDir(
                tempDir, usbDir, Collections.emptySet(), nowMs, 1024L, requeued::add);

        assertFalse(tempFile.exists());
        assertTrue(usbFile.exists());
        assertEquals(1, stats.deletedVerifiedCount);
        assertEquals(4096L, stats.freedBytes);
        assertTrue(requeued.isEmpty());
    }

    @Test public void expiredTempFileUnsavedWhenUsbUnavailableIsPreservedAndNeverDeleted() throws Exception {
        File tempDir = files.newFolder("temp_video_unsaved");
        File tempFile = new File(tempDir, "20261121_090000_back.mp4");
        Files.write(tempFile.toPath(), new byte[8192]);
        long nowMs = 20_000_000L;
        tempFile.setLastModified(nowMs - 5L * 3600L * 1000L); // 5h old

        List<File> requeued = new ArrayList<>();
        FileTransferManager.TempCleanupStats stats = FileTransferManager.inspectAndCleanTempDir(
                tempDir, null, Collections.emptySet(), nowMs, 1024L, requeued::add);

        assertTrue(tempFile.exists());
        assertEquals(0, stats.deletedVerifiedCount);
        assertEquals(0, stats.deletedInvalidCount);
        assertEquals(1, stats.preservedUnsavedCount);
        assertEquals(8192L, stats.preservedUnsavedBytes);
        assertTrue(requeued.isEmpty());
    }

    @Test public void expiredTempFileWithSizeMismatchOnUsbIsPreservedAndRequeued() throws Exception {
        File tempDir = files.newFolder("temp_video_mismatch");
        File usbDir = files.newFolder("usb_video_mismatch");
        File tempFile = new File(tempDir, "20261121_100000_left.mp4");
        File corruptUsbFile = new File(usbDir, "20261121_100000_left.mp4");
        Files.write(tempFile.toPath(), new byte[8192]);
        Files.write(corruptUsbFile.toPath(), new byte[2048]); // Truncated copy on USB
        long nowMs = 20_000_000L;
        tempFile.setLastModified(nowMs - 3L * 3600L * 1000L);

        List<File> requeued = new ArrayList<>();
        FileTransferManager.TempCleanupStats stats = FileTransferManager.inspectAndCleanTempDir(
                tempDir, usbDir, Collections.emptySet(), nowMs, 1024L, requeued::add);

        assertTrue(tempFile.exists());
        assertEquals(0, stats.deletedVerifiedCount);
        assertEquals(1, stats.requeuedCount);
        assertEquals(Collections.singletonList(tempFile), requeued);
    }

    @Test public void activePendingAndQueuedFilesAreProtectedDuringTempInspection() throws Exception {
        File tempDir = files.newFolder("temp_video_protected");
        File usbDir = files.newFolder("usb_video_protected");
        File queuedFile = new File(tempDir, "20261121_110000_front.mp4");
        File usbCopy = new File(usbDir, "20261121_110000_front.mp4");
        File activePending = new File(tempDir, "20261121_110500_front.mp4.pending");
        Files.write(queuedFile.toPath(), new byte[4096]);
        Files.write(usbCopy.toPath(), new byte[4096]);
        Files.write(activePending.toPath(), new byte[512]);
        long nowMs = 30_000_000L;
        queuedFile.setLastModified(nowMs - 2L * 3600L * 1000L);
        activePending.setLastModified(nowMs - 10_000L);

        Set<String> protectedPaths = new HashSet<>();
        protectedPaths.add(ActiveRecordingRegistry.normalizePath(queuedFile));
        protectedPaths.add(ActiveRecordingRegistry.normalizePath(activePending));

        FileTransferManager.TempCleanupStats stats = FileTransferManager.inspectAndCleanTempDir(
                tempDir, usbDir, protectedPaths, nowMs, 1024L, null);

        assertTrue(queuedFile.exists());
        assertTrue(activePending.exists());
        assertEquals(0, stats.deletedVerifiedCount);
        assertEquals(0, stats.deletedInvalidCount);
    }

    @Test public void emergencyEvictionDeletesOldestUnsavedFilesAndProtectsQueuedOrActiveFiles() throws Exception {
        File cacheDir = files.newFolder("cache_root");
        File tempDir = new File(cacheDir, FileTransferManager.TEMP_VIDEO_DIR);
        assertTrue(tempDir.mkdirs());

        Context context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getCacheDir()).thenReturn(cacheDir);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.getString(eq("storage_location"), any())).thenReturn("sd_card");

        Constructor<FileTransferManager> constructor = FileTransferManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        FileTransferManager manager = constructor.newInstance(context);

        File oldest = new File(tempDir, "20261121_070000_front.mp4");
        File middle = new File(tempDir, "20261121_080000_front.mp4");
        File protectedInFlight = new File(tempDir, "20261121_090000_front.mp4");
        Files.write(oldest.toPath(), new byte[4096]);
        Files.write(middle.toPath(), new byte[4096]);
        Files.write(protectedInFlight.toPath(), new byte[4096]);
        oldest.setLastModified(1000L);
        middle.setLastModified(2000L);
        protectedInFlight.setLastModified(500L); // Oldest mtime, but active in-flight

        Field inFlight = FileTransferManager.class.getDeclaredField("inFlightSourcePath");
        inFlight.setAccessible(true);
        inFlight.set(manager, ActiveRecordingRegistry.normalizePath(protectedInFlight));

        FileTransferManager.EmergencyEvictionResult result = manager.evictOldestUnsavedForEmergencySpace(3000L);

        assertFalse(oldest.exists());
        assertTrue(middle.exists());
        assertTrue(protectedInFlight.exists());
        assertEquals(1, result.deletedCount);
        assertEquals(4096L, result.deletedBytes);
    }
}
