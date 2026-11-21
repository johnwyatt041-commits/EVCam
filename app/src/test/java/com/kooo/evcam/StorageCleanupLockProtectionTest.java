package com.kooo.evcam;

import com.kooo.evcam.playback.VideoLockStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;

import static org.junit.Assert.*;

public class StorageCleanupLockProtectionTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void deleteOldestUnprotectedFilesSkipsLockedAndPendingAndDeletesUnlockedAndOrphans() throws Exception {
        File dir = temp.newFolder("EVCam_Video");
        File lockedVideo = new File(dir, "20261115_080000_combined.mp4");
        File lockedSidecar = new File(dir, "20261115_080000_combined.mp4.lock");
        File orphanLock = new File(dir, "20261114_070000_deleted.mp4.lock");
        File unlockedOld = new File(dir, "20261115_090000_combined.mp4");
        File unlockedNew = new File(dir, "20261116_090000_combined.mp4");
        File activePending = new File(dir, "20261116_100000_combined.mp4.pending");

        Files.write(lockedVideo.toPath(), new byte[1000]);
        Files.write(lockedSidecar.toPath(), new byte[1]);
        Files.write(orphanLock.toPath(), new byte[10]);
        Files.write(unlockedOld.toPath(), new byte[1000]);
        Files.write(unlockedNew.toPath(), new byte[1000]);
        Files.write(activePending.toPath(), new byte[1000]);

        lockedVideo.setLastModified(1000L);
        lockedSidecar.setLastModified(1001L);
        orphanLock.setLastModified(1500L);
        unlockedOld.setLastModified(2000L);
        unlockedNew.setLastModified(3000L);
        activePending.setLastModified(4000L);

        File[] files = dir.listFiles(File::isFile);
        assertNotNull(files);
        long total = 4011L;
        long target = 2500L;

        StorageCleanupManager.DeleteStats stats = StorageCleanupManager.deleteOldestUnprotectedFiles(
                files, total, target, "测试清理");

        assertTrue(lockedVideo.exists());
        assertTrue(lockedSidecar.exists());
        assertTrue(activePending.exists());
        assertFalse(orphanLock.exists());
        assertFalse(unlockedOld.exists());
        assertFalse(unlockedNew.exists());
        assertEquals(3, stats.deletedCount);
        assertEquals(2010L, stats.deletedSize);
        assertEquals(2, stats.skippedLockedCount);

        Set<String> remaining = VideoLockStore.collectFileNames(dir.listFiles(File::isFile));
        assertTrue(remaining.contains(lockedVideo.getName()));
        assertTrue(remaining.contains(lockedSidecar.getName()));
        assertTrue(remaining.contains(activePending.getName()));
    }

    @Test
    public void deleteOldestUnprotectedFilesReclaimsOrphanPendingWhileProtectingRegisteredAndRecentPending() throws Exception {
        File dir = temp.newFolder("EVCam_Video_Pending");
        File orphanPending = new File(dir, "20261110_070000_front.mp4.pending");
        File activePending = new File(dir, "20261116_100000_back.mp4.pending");
        File recentUnregisteredPending = new File(dir, "20261116_100100_left.mp4.pending");

        Files.write(orphanPending.toPath(), new byte[1000]);
        Files.write(activePending.toPath(), new byte[1000]);
        Files.write(recentUnregisteredPending.toPath(), new byte[1000]);

        long nowMs = 1_000_000_000L;
        orphanPending.setLastModified(nowMs - VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS - 10_000L);
        activePending.setLastModified(nowMs - VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS - 60_000L);
        recentUnregisteredPending.setLastModified(nowMs - 5_000L);

        Set<String> activePaths = java.util.Collections.singleton(
                com.kooo.evcam.camera.ActiveRecordingRegistry.normalizePath(activePending));
        File[] files = dir.listFiles(File::isFile);
        assertNotNull(files);

        StorageCleanupManager.DeleteStats stats = StorageCleanupManager.deleteOldestUnprotectedFiles(
                files,
                3000L,
                1500L,
                "清理遗留pending",
                activePaths,
                nowMs,
                VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS
        );

        assertFalse("orphan pending outside active registry & grace window should be reclaimed", orphanPending.exists());
        assertTrue("registered active pending must remain protected even if old", activePending.exists());
        assertTrue("fresh pending within grace window must remain protected", recentUnregisteredPending.exists());
        assertEquals(1, stats.deletedCount);
        assertEquals(1000L, stats.deletedSize);
        assertEquals(2, stats.skippedLockedCount);
    }
}
