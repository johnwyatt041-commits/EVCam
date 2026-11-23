package com.kooo.evcam;

import com.kooo.evcam.camera.ActiveRecordingRegistry;
import com.kooo.evcam.playback.PlaybackTimelineBuilder;
import com.kooo.evcam.playback.VideoLockStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class PendingFileRecoveryAndCleanupTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static byte[] box(String type, byte[] payload) {
        int total = 8 + payload.length;
        byte[] out = new byte[total];
        out[0] = (byte) ((total >>> 24) & 0xFF);
        out[1] = (byte) ((total >>> 16) & 0xFF);
        out[2] = (byte) ((total >>> 8) & 0xFF);
        out[3] = (byte) (total & 0xFF);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(typeBytes, 0, out, 4, 4);
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    private static byte[] buildMinimalValidMp4(int mdatPayloadBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(box("ftyp", "isom\0\0\0\0isomiso2".getBytes(StandardCharsets.US_ASCII)));
        bos.write(box("mdat", new byte[mdatPayloadBytes]));
        bos.write(box("moov", new byte[64]));
        return bos.toByteArray();
    }

    @Test
    public void mp4AtomValidatorAcceptsCompleteMp4AndRejectsTruncatedWithoutMoov() throws Exception {
        File dir = temp.newFolder("mp4_validate");
        File validPending = new File(dir, "20261121_080000_front.mp4.pending");
        File truncatedPending = new File(dir, "20261121_080100_front.mp4.pending");

        Files.write(validPending.toPath(), buildMinimalValidMp4(1200));
        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        partial.write(box("ftyp", "isom\0\0\0\0".getBytes(StandardCharsets.US_ASCII)));
        partial.write(box("mdat", new byte[1200]));
        Files.write(truncatedPending.toPath(), partial.toByteArray());

        assertTrue(PendingFileRecoveryManager.hasValidMp4AtomStructure(validPending));
        assertFalse(PendingFileRecoveryManager.hasValidMp4AtomStructure(truncatedPending));
    }

    @Test
    public void startupScanRecoversValidOrphanAutoDeletesStubAndProtectsActiveOrGracePending() throws Exception {
        File dir = temp.newFolder("EVCam_Video");
        long nowMs = 10_000_000L;

        File recoverable = new File(dir, "20261121_080000_front.mp4.pending");
        File tinyStub = new File(dir, "20261121_080500_back.mp4.pending");
        File corruptOrphan = new File(dir, "20261121_081000_left.mp4.pending");
        File activeRecording = new File(dir, "20261121_090000_right.mp4.pending");
        File graceRecent = new File(dir, "20261121_090100_front.mp4.pending");

        Files.write(recoverable.toPath(), buildMinimalValidMp4(1400));
        Files.write(tinyStub.toPath(), new byte[128]);
        Files.write(corruptOrphan.toPath(), new byte[4096]);
        Files.write(activeRecording.toPath(), new byte[4096]);
        Files.write(graceRecent.toPath(), new byte[4096]);

        recoverable.setLastModified(nowMs - 300_000L);
        tinyStub.setLastModified(nowMs - 300_000L);
        corruptOrphan.setLastModified(nowMs - 300_000L);
        activeRecording.setLastModified(nowMs - 300_000L); // Old mtime, but explicitly registered active!
        graceRecent.setLastModified(nowMs - 10_000L);     // Within 60s grace window

        Set<String> activePaths = new HashSet<>();
        activePaths.add(ActiveRecordingRegistry.normalizePath(activeRecording));

        PendingFileRecoveryManager.ScanReport report = PendingFileRecoveryManager.scanDirectories(
                Collections.singletonList(dir),
                activePaths,
                nowMs,
                PendingFileRecoveryManager.STARTUP_GRACE_WINDOW_MS,
                PendingFileRecoveryManager::hasValidMp4AtomStructure);

        assertEquals(1, report.recoveredFiles.size());
        assertEquals("20261121_080000_front.mp4", report.recoveredFiles.get(0).getName());
        assertTrue(report.recoveredFiles.get(0).exists());
        assertFalse(recoverable.exists());

        assertEquals(1, report.autoDeletedStubs.size());
        assertFalse(tinyStub.exists());

        assertEquals(1, report.unrecoverableOrphans.size());
        assertEquals(corruptOrphan, report.unrecoverableOrphans.get(0));
        assertEquals(4096L, report.unrecoverableBytes);
        assertTrue(corruptOrphan.exists());

        // Active & recent pending files remain untouched.
        assertTrue(activeRecording.exists());
        assertTrue(graceRecent.exists());

        // User-confirmed cleanup safely deletes unrecoverable orphans while never touching active files.
        PendingFileRecoveryManager.DeleteResult deleted = PendingFileRecoveryManager.deleteOrphansSafely(
                java.util.Arrays.asList(corruptOrphan, activeRecording),
                activePaths);
        assertEquals(1, deleted.deletedCount);
        assertEquals(4096L, deleted.freedBytes);
        assertFalse(corruptOrphan.exists());
        assertTrue(activeRecording.exists());
    }

    @Test
    public void storageCleanupReclaimsOrphanPendingWhileProtectingActivePending() throws Exception {
        File dir = temp.newFolder("cleanup_pending");
        long nowMs = 20_000_000L;

        File orphanPending = new File(dir, "20261120_080000_front.mp4.pending");
        File activePending = new File(dir, "20261121_090000_front.mp4.pending");
        File recentPending = new File(dir, "20261121_090100_back.mp4.pending");
        Files.write(orphanPending.toPath(), new byte[2000]);
        Files.write(activePending.toPath(), new byte[2000]);
        Files.write(recentPending.toPath(), new byte[2000]);

        orphanPending.setLastModified(nowMs - 600_000L);
        activePending.setLastModified(nowMs - 600_000L); // Long running active segment (> grace), registered active
        recentPending.setLastModified(nowMs - 15_000L);  // Within 2 min grace

        Set<String> activePaths = Collections.singleton(ActiveRecordingRegistry.normalizePath(activePending));
        File[] files = dir.listFiles(File::isFile);
        assertNotNull(files);

        StorageCleanupManager.DeleteStats stats = StorageCleanupManager.deleteOldestUnprotectedFiles(
                files,
                6000L,
                4000L,
                "测试清理遗留pending",
                activePaths,
                nowMs,
                VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS);

        assertFalse(orphanPending.exists());
        assertTrue(activePending.exists());
        assertTrue(recentPending.exists());
        assertEquals(1, stats.deletedCount);
        assertEquals(2000L, stats.deletedSize);
    }

    @Test
    public void playbackTimelineIgnoresOrphanPendingWhenActiveRegistrySnapshotProvided() throws Exception {
        File dir = temp.newFolder("playback_pending");
        File orphanPending = new File(dir, "20261121_080000_front.mp4.pending");
        File activePending = new File(dir, "20261121_090000_front.mp4.pending");
        Files.write(orphanPending.toPath(), new byte[2048]);
        Files.write(activePending.toPath(), new byte[2048]);

        long todayRef = new com.kooo.evcam.playback.VideoGroup("20261121_120000").getRecordTime().getTime();

        // Only orphan exists, not in active set -> no false "正在录制" banner!
        PlaybackTimelineBuilder.VideoScanResult noActiveScan = PlaybackTimelineBuilder.scanVideoDirectory(
                dir,
                PlaybackTimelineBuilder.FILTER_ALL,
                todayRef,
                Collections.emptySet());
        assertNull(noActiveScan.activeRecording);

        // Active pending in set -> banner shows active recording timestamp.
        PlaybackTimelineBuilder.VideoScanResult activeScan = PlaybackTimelineBuilder.scanVideoDirectory(
                dir,
                PlaybackTimelineBuilder.FILTER_ALL,
                todayRef,
                Collections.singleton(ActiveRecordingRegistry.normalizePath(activePending)));
        assertNotNull(activeScan.activeRecording);
        assertEquals("20261121_090000", activeScan.activeRecording.timestampPrefix);
    }

    @Test
    public void manualCleanupScanFindsFragmentsProtectsActiveAndRefusesNormalMediaDeletion() throws Exception {
        File videoDir = temp.newFolder("EVCam_Video_Manual");
        long nowMs = 50_000_000L;
        long oldMtime = nowMs - VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS - 60_000L;
        long freshMtime = nowMs - 10_000L;

        // Recoverable playable orphan pending -> recovered losslessly during scan
        File recoverablePending = new File(videoDir, "20261122_070000_back.mp4.pending");
        Files.write(recoverablePending.toPath(), buildMinimalValidMp4(1400));
        recoverablePending.setLastModified(oldMtime);

        // Deletable fragments
        File corruptPending = new File(videoDir, "20261122_071000_left.mp4.pending");
        File tinyStubPending = new File(videoDir, "20261122_071500_right.mp4.pending");
        File stalePart = new File(videoDir, "20261122_072000_back.mp4.part");
        File staleProbe = new File(videoDir, ".evcam_write_probe_1.mp4.pending");
        File staleSpeedTmp = new File(videoDir, ".evcam_speed_test_1.tmp");
        File orphanLock = new File(videoDir, "20261120_050000_back.mp4.lock");

        Files.write(corruptPending.toPath(), new byte[4096]);
        Files.write(tinyStubPending.toPath(), new byte[128]);
        Files.write(stalePart.toPath(), new byte[2048]);
        Files.write(staleProbe.toPath(), new byte[64]);
        Files.write(staleSpeedTmp.toPath(), new byte[512]);
        Files.write(orphanLock.toPath(), new byte[1]);

        corruptPending.setLastModified(oldMtime);
        tinyStubPending.setLastModified(oldMtime);
        stalePart.setLastModified(oldMtime);
        staleProbe.setLastModified(oldMtime);
        staleSpeedTmp.setLastModified(oldMtime);
        orphanLock.setLastModified(oldMtime);

        // Must be strictly protected!
        File normalMp4 = new File(videoDir, "20261122_080000_back.mp4");
        File normalMp4Lock = new File(videoDir, "20261122_080000_back.mp4.lock");
        File normalJpg = new File(videoDir, "20261122_080000_back.jpg");
        File activePending = new File(videoDir, "20261123_090000_back.mp4.pending");
        File gracePending = new File(videoDir, "20261123_090100_left.mp4.pending");
        File activeTransferPart = new File(videoDir, "20261123_090200_right.mp4.part");
        File trashedMp4 = new File(videoDir, ".trashed-123-20261122_060000_back.mp4");

        Files.write(normalMp4.toPath(), new byte[8192]);
        Files.write(normalMp4Lock.toPath(), new byte[1]);
        Files.write(normalJpg.toPath(), new byte[1024]);
        Files.write(activePending.toPath(), new byte[4096]);
        Files.write(gracePending.toPath(), new byte[4096]);
        Files.write(activeTransferPart.toPath(), new byte[4096]);
        Files.write(trashedMp4.toPath(), new byte[2048]);

        normalMp4.setLastModified(oldMtime);
        normalMp4Lock.setLastModified(oldMtime);
        normalJpg.setLastModified(oldMtime);
        activePending.setLastModified(oldMtime); // old mtime, but actively registered
        gracePending.setLastModified(freshMtime); // recent grace window
        activeTransferPart.setLastModified(oldMtime); // actively transferring
        trashedMp4.setLastModified(oldMtime);

        Set<String> protectedPaths = new HashSet<>();
        protectedPaths.add(ActiveRecordingRegistry.normalizePath(activePending));
        protectedPaths.add(ActiveRecordingRegistry.normalizePath(activeTransferPart));

        PendingFileRecoveryManager.ManualScanReport report = PendingFileRecoveryManager.scanForManualCleanup(
                Collections.singletonList(videoDir),
                protectedPaths,
                nowMs,
                VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS,
                PendingFileRecoveryManager::hasValidMp4AtomStructure);

        // Scan must recover playable pending but NEVER delete tiny/corrupt fragments before confirmation!
        assertEquals(1, report.recoveredFiles.size());
        assertEquals("20261122_070000_back.mp4", report.recoveredFiles.get(0).getName());
        assertTrue(new File(videoDir, "20261122_070000_back.mp4").exists());
        assertTrue(tinyStubPending.exists());
        assertTrue(corruptPending.exists());

        assertEquals(2, report.pendingCount);
        assertEquals(4224L, report.pendingBytes);
        assertEquals(1, report.partCount);
        assertEquals(2048L, report.partBytes);
        assertEquals(2, report.tempProbeCount);
        assertEquals(576L, report.tempProbeBytes);
        assertEquals(1, report.orphanLockCount);
        assertEquals(1L, report.orphanLockBytes);
        assertEquals(6, report.getTotalDeletableCount());
        assertEquals(6849L, report.totalDeletableBytes);
        assertEquals(3, report.protectedActiveCount);

        // Even if normal media or protected items are maliciously injected into delete list, deleteFragmentsSafely refuses them!
        java.util.List<PendingFileRecoveryManager.FragmentItem> mixedWithProtected = new java.util.ArrayList<>(report.deletableFragments);
        mixedWithProtected.add(new PendingFileRecoveryManager.FragmentItem(
                normalMp4, PendingFileRecoveryManager.FragmentCategory.PENDING_VIDEO, normalMp4.length()));
        mixedWithProtected.add(new PendingFileRecoveryManager.FragmentItem(
                normalJpg, PendingFileRecoveryManager.FragmentCategory.TEMP_PROBE, normalJpg.length()));
        mixedWithProtected.add(new PendingFileRecoveryManager.FragmentItem(
                normalMp4Lock, PendingFileRecoveryManager.FragmentCategory.ORPHAN_LOCK, normalMp4Lock.length()));
        mixedWithProtected.add(new PendingFileRecoveryManager.FragmentItem(
                activePending, PendingFileRecoveryManager.FragmentCategory.PENDING_VIDEO, activePending.length()));
        mixedWithProtected.add(new PendingFileRecoveryManager.FragmentItem(
                gracePending, PendingFileRecoveryManager.FragmentCategory.PENDING_VIDEO, gracePending.length()));
        mixedWithProtected.add(new PendingFileRecoveryManager.FragmentItem(
                activeTransferPart, PendingFileRecoveryManager.FragmentCategory.TRANSFER_PART, activeTransferPart.length()));

        PendingFileRecoveryManager.ManualDeleteResult deleteResult = PendingFileRecoveryManager.deleteFragmentsSafely(
                mixedWithProtected,
                protectedPaths,
                nowMs,
                VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS);

        assertEquals(6, deleteResult.deletedCount);
        assertEquals(6, deleteResult.skippedCount);
        assertEquals(6849L, deleteResult.freedBytes);

        assertFalse(corruptPending.exists());
        assertFalse(tinyStubPending.exists());
        assertFalse(stalePart.exists());
        assertFalse(staleProbe.exists());
        assertFalse(staleSpeedTmp.exists());
        assertFalse(orphanLock.exists());

        assertTrue(normalMp4.exists());
        assertTrue(normalMp4Lock.exists());
        assertTrue(normalJpg.exists());
        assertTrue(activePending.exists());
        assertTrue(gracePending.exists());
        assertTrue(activeTransferPart.exists());
        assertTrue(trashedMp4.exists());
    }
}
