package com.kooo.evcam.playback;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class PlaybackTimelineAndLockTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void videoGroupShareableFilesIncludesCombinedAndExcludesPending() throws Exception {
        File dir = temp.newFolder("clips");
        File front = new File(dir, "20261116_101500_front.mp4");
        File combined = new File(dir, "20261116_101500_combined.mp4");
        Files.write(front.toPath(), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(combined.toPath(), "b".getBytes(StandardCharsets.UTF_8));

        VideoGroup combinedOnly = new VideoGroup("20261116_101500");
        combinedOnly.addFile(combined);
        List<File> shareable = combinedOnly.getShareableFiles();
        assertEquals(1, shareable.size());
        assertEquals(combined, shareable.get(0));

        VideoGroup both = new VideoGroup("20261116_101500");
        both.addFile(front);
        both.addFile(combined);
        assertEquals(2, both.getShareableFiles().size());
    }

    @Test
    public void timelineScannerGroupsByDateAndHourFiltersLockedAndSpotsActivePending() throws Exception {
        File dir = temp.newFolder("EVCam_Video");
        File f1 = new File(dir, "20261116_091500_combined.mp4");
        File f2 = new File(dir, "20261116_092500_combined.mp4");
        File f3 = new File(dir, "20261116_141000_combined.mp4");
        File lockF2 = new File(dir, "20261116_092500_combined.mp4.lock");
        File pending = new File(dir, "20261116_150000_combined.mp4.pending");

        Files.write(f1.toPath(), new byte[100]);
        Files.write(f2.toPath(), new byte[100]);
        Files.write(f3.toPath(), new byte[100]);
        Files.write(lockF2.toPath(), new byte[0]);
        Files.write(pending.toPath(), new byte[100]);

        long todayRef = new VideoGroup("20261116_120000").getRecordTime().getTime();
        PlaybackTimelineBuilder.VideoScanResult allScan = PlaybackTimelineBuilder.scanVideoDirectory(
                dir, PlaybackTimelineBuilder.FILTER_ALL, todayRef);
        assertNotNull(allScan.activeRecording);
        assertEquals("20261116_150000", allScan.activeRecording.timestampPrefix);
        assertEquals(1, allScan.dateSections.size());
        allScan.dateSections.get(0).setExpanded(true);

        List<Object> flattened = PlaybackTimelineBuilder.flattenVideoSections(
                allScan.dateSections, allScan.activeRecording);
        // ActiveBanner + DateSection + HourHeader(14:00) + f3 + HourHeader(09:00) + f2 + f1 = 7
        assertEquals(7, flattened.size());
        assertTrue(flattened.get(0) instanceof PlaybackTimelineBuilder.ActiveRecordingBanner);
        assertTrue(flattened.get(2) instanceof PlaybackTimelineBuilder.HourHeader);
        assertEquals("14:00  ·  1 段", ((PlaybackTimelineBuilder.HourHeader) flattened.get(2)).getDisplayText());
        assertEquals("09:00  ·  2 段", ((PlaybackTimelineBuilder.HourHeader) flattened.get(4)).getDisplayText());

        PlaybackTimelineBuilder.VideoScanResult lockedScan = PlaybackTimelineBuilder.scanVideoDirectory(
                dir, PlaybackTimelineBuilder.FILTER_LOCKED, todayRef);
        assertEquals(1, lockedScan.dateSections.size());
        assertEquals(1, lockedScan.dateSections.get(0).getItemCount());
        assertEquals("20261116_092500", lockedScan.dateSections.get(0).getItems().get(0).getTimestampPrefix());
        assertTrue(lockedScan.dateSections.get(0).getItems().get(0).isLocked());
    }

    @Test
    public void cleanupPreservesLockedMediaAndSidecarsWhileRemovingOrphanLocks() throws Exception {
        File dir = temp.newFolder("cleanup");
        File oldestLocked = new File(dir, "20261115_080000_front.mp4");
        File oldestLockSidecar = new File(dir, "20261115_080000_front.mp4.lock");
        File orphanLock = new File(dir, "20261114_080000_gone.mp4.lock");
        File unlockedOld = new File(dir, "20261115_090000_front.mp4");
        File activePending = new File(dir, "20261116_100000_front.mp4.pending");

        Files.write(oldestLocked.toPath(), new byte[1000]);
        Files.write(oldestLockSidecar.toPath(), new byte[1]);
        Files.write(orphanLock.toPath(), new byte[10]);
        Files.write(unlockedOld.toPath(), new byte[1000]);
        Files.write(activePending.toPath(), new byte[1000]);

        oldestLocked.setLastModified(1000L);
        oldestLockSidecar.setLastModified(1001L);
        orphanLock.setLastModified(1500L);
        unlockedOld.setLastModified(2000L);
        activePending.setLastModified(3000L);

        File[] files = dir.listFiles();
        assertNotNull(files);
        Set<String> names = VideoLockStore.collectFileNames(files);
        assertTrue(VideoLockStore.shouldPreserveDuringCleanup(oldestLocked, names));
        assertTrue(VideoLockStore.shouldPreserveDuringCleanup(oldestLockSidecar, names));
        assertTrue(VideoLockStore.shouldPreserveDuringCleanup(activePending, names));
        assertFalse(VideoLockStore.shouldPreserveDuringCleanup(orphanLock, names));
        assertFalse(VideoLockStore.shouldPreserveDuringCleanup(unlockedOld, names));

        // Simulate StorageCleanupManager loop via VideoLockStore rules:
        // oldestLocked must remain, orphanLock & unlockedOld deleted
        long total = 3011L;
        long deleted = 0L;
        java.util.List<File> sorted = new java.util.ArrayList<>(java.util.Arrays.asList(files));
        sorted.sort(java.util.Comparator.comparingLong(File::lastModified));
        for (File f : sorted) {
            if (total - deleted <= 2000L) break;
            if (VideoLockStore.shouldPreserveDuringCleanup(f, names)) continue;
            long len = f.length();
            assertTrue(f.delete());
            deleted += len;
        }
        assertTrue(oldestLocked.exists());
        assertTrue(oldestLockSidecar.exists());
        assertTrue(activePending.exists());
        assertFalse(orphanLock.exists());
        assertFalse(unlockedOld.exists());
    }
}
