package com.kooo.evcam;

import android.content.Context;
import android.media.MediaExtractor;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.camera.ActiveRecordingRegistry;
import com.kooo.evcam.playback.VideoLockStore;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 遗留 {@code .mp4.pending} 文件识别、安全恢复与清理工具：
 * <ul>
 *     <li>通过 {@link ActiveRecordingRegistry} 与修改时间宽限窗口，严格保护正在录制的实时文件。</li>
 *     <li>对非活跃的遗留 {@code .pending} 文件：极小空壳自动清理；箱结构/音视频轨道完整的尝试重命名为 {@code .mp4} 恢复；无法恢复的汇总供启动提示与后续容量回收。</li>
 * </ul>
 */
public final class PendingFileRecoveryManager {
    private static final String TAG = "PendingFileRecovery";

    public static final long MIN_VALID_SIZE_BYTES = 1024L;
    public static final long STARTUP_GRACE_WINDOW_MS = 60_000L;
    public static final String PART_SUFFIX = ".part";
    public static final String PROBE_PREFIX = ".evcam_write_probe_";
    public static final String SPEED_TEST_PREFIX = ".evcam_speed_test_";
    public static final String SPEED_TEST_SUFFIX = ".tmp";
    private static final int MAX_TOP_LEVEL_MP4_BOXES = 256;

    public interface Mp4Validator {
        boolean isPlayableMp4(@NonNull File file);
    }

    public enum FragmentCategory {
        PENDING_VIDEO,
        TRANSFER_PART,
        TEMP_PROBE,
        ORPHAN_LOCK
    }

    public static final class FragmentItem {
        @NonNull public final File file;
        @NonNull public final FragmentCategory category;
        public final long sizeBytes;

        public FragmentItem(@NonNull File file, @NonNull FragmentCategory category, long sizeBytes) {
            this.file = file;
            this.category = category;
            this.sizeBytes = Math.max(0L, sizeBytes);
        }
    }

    public static final class ManualScanReport {
        @NonNull public final List<File> recoveredFiles;
        @NonNull public final List<FragmentItem> deletableFragments;
        public final int pendingCount;
        public final long pendingBytes;
        public final int partCount;
        public final long partBytes;
        public final int tempProbeCount;
        public final long tempProbeBytes;
        public final int orphanLockCount;
        public final long orphanLockBytes;
        public final long totalDeletableBytes;
        public final int protectedActiveCount;

        public ManualScanReport(@NonNull List<File> recoveredFiles,
                                @NonNull List<FragmentItem> deletableFragments,
                                int pendingCount,
                                long pendingBytes,
                                int partCount,
                                long partBytes,
                                int tempProbeCount,
                                long tempProbeBytes,
                                int orphanLockCount,
                                long orphanLockBytes,
                                long totalDeletableBytes,
                                int protectedActiveCount) {
            this.recoveredFiles = Collections.unmodifiableList(new ArrayList<>(recoveredFiles));
            this.deletableFragments = Collections.unmodifiableList(new ArrayList<>(deletableFragments));
            this.pendingCount = pendingCount;
            this.pendingBytes = Math.max(0L, pendingBytes);
            this.partCount = partCount;
            this.partBytes = Math.max(0L, partBytes);
            this.tempProbeCount = tempProbeCount;
            this.tempProbeBytes = Math.max(0L, tempProbeBytes);
            this.orphanLockCount = orphanLockCount;
            this.orphanLockBytes = Math.max(0L, orphanLockBytes);
            this.totalDeletableBytes = Math.max(0L, totalDeletableBytes);
            this.protectedActiveCount = Math.max(0, protectedActiveCount);
        }

        public int getTotalDeletableCount() {
            return deletableFragments.size();
        }

        public boolean hasDeletableFragments() {
            return !deletableFragments.isEmpty();
        }

        public boolean hasRecoveredFiles() {
            return !recoveredFiles.isEmpty();
        }
    }

    public static final class ManualDeleteResult {
        public final int deletedCount;
        public final int skippedCount;
        public final long freedBytes;

        public ManualDeleteResult(int deletedCount, int skippedCount, long freedBytes) {
            this.deletedCount = deletedCount;
            this.skippedCount = skippedCount;
            this.freedBytes = Math.max(0L, freedBytes);
        }
    }

    public static final class ScanReport {
        @NonNull public final List<File> recoveredFiles;
        @NonNull public final List<File> autoDeletedStubs;
        @NonNull public final List<File> unrecoverableOrphans;
        public final long unrecoverableBytes;

        public ScanReport(@NonNull List<File> recoveredFiles,
                          @NonNull List<File> autoDeletedStubs,
                          @NonNull List<File> unrecoverableOrphans,
                          long unrecoverableBytes) {
            this.recoveredFiles = Collections.unmodifiableList(new ArrayList<>(recoveredFiles));
            this.autoDeletedStubs = Collections.unmodifiableList(new ArrayList<>(autoDeletedStubs));
            this.unrecoverableOrphans = Collections.unmodifiableList(new ArrayList<>(unrecoverableOrphans));
            this.unrecoverableBytes = Math.max(0L, unrecoverableBytes);
        }

        public boolean hasUserVisibleFindings() {
            return !recoveredFiles.isEmpty() || !unrecoverableOrphans.isEmpty();
        }
    }

    public static final class DeleteResult {
        public final int deletedCount;
        public final long freedBytes;

        public DeleteResult(int deletedCount, long freedBytes) {
            this.deletedCount = deletedCount;
            this.freedBytes = freedBytes;
        }
    }

    private PendingFileRecoveryManager() {
    }

    /**
     * 纯 Java 校验顶层 ISO BMFF (MP4) Box 链是否完整闭合且同时包含 {@code ftyp} 与 {@code moov}。
     */
    public static boolean hasValidMp4AtomStructure(@Nullable File file) {
        if (file == null || !file.isFile() || file.length() < MIN_VALID_SIZE_BYTES) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            if (len < 16L) {
                return false;
            }
            long pos = 0L;
            boolean seenFtyp = false;
            boolean seenMoov = false;
            int boxCount = 0;
            byte[] header = new byte[8];
            while (pos + 8L <= len && boxCount < MAX_TOP_LEVEL_MP4_BOXES) {
                raf.seek(pos);
                raf.readFully(header);
                long boxSize = ((header[0] & 0xFFL) << 24)
                        | ((header[1] & 0xFFL) << 16)
                        | ((header[2] & 0xFFL) << 8)
                        | (header[3] & 0xFFL);
                String type = new String(header, 4, 4, StandardCharsets.US_ASCII);
                long headerSize = 8L;
                if (boxSize == 1L) {
                    if (pos + 16L > len) {
                        return false;
                    }
                    boxSize = raf.readLong();
                    headerSize = 16L;
                } else if (boxSize == 0L) {
                    boxSize = len - pos;
                }
                if (boxSize < headerSize || boxSize > (len - pos)) {
                    return false;
                }
                if ("ftyp".equals(type)) {
                    seenFtyp = true;
                } else if ("moov".equals(type)) {
                    seenMoov = true;
                }
                pos += boxSize;
                boxCount++;
            }
            return seenFtyp && seenMoov && pos == len;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isPlayableAndroidMp4(@NonNull File file) {
        if (!hasValidMp4AtomStructure(file)) {
            return false;
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            return extractor.getTrackCount() > 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }
    }

    @NonNull
    public static ScanReport scanDirectories(@NonNull List<File> directories,
                                             @NonNull Set<String> activePendingPaths,
                                             long nowMs,
                                             long graceWindowMs,
                                             @NonNull Mp4Validator validator) {
        List<File> recovered = new ArrayList<>();
        List<File> stubs = new ArrayList<>();
        List<File> unrecoverable = new ArrayList<>();
        long unrecoverableBytes = 0L;
        Set<String> visitedDirs = new HashSet<>();

        for (File dir : directories) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) {
                continue;
            }
            String dirKey = ActiveRecordingRegistry.normalizePath(dir);
            if (!visitedDirs.add(dirKey)) {
                continue;
            }
            File[] files = dir.listFiles(File::isFile);
            if (files == null || files.length == 0) {
                continue;
            }
            for (File file : files) {
                if (file == null || !file.isFile()) {
                    continue;
                }
                String name = file.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".mp4" + VideoLockStore.PENDING_SUFFIX)) {
                    continue;
                }
                if (VideoLockStore.isProtectedPendingFile(file, activePendingPaths, nowMs, graceWindowMs)) {
                    continue;
                }
                long len = file.length();
                if (len < MIN_VALID_SIZE_BYTES) {
                    if (file.delete()) {
                        stubs.add(file);
                        AppLog.d(TAG, "Auto-deleted empty/tiny orphan pending file: " + name);
                    }
                    continue;
                }
                String targetName = name.substring(0, name.length() - VideoLockStore.PENDING_SUFFIX.length());
                File targetFile = new File(file.getParentFile(), targetName);
                boolean playable = false;
                try {
                    playable = validator.isPlayableMp4(file);
                } catch (Throwable ignored) {
                    playable = false;
                }
                if (playable) {
                    if (!targetFile.exists()) {
                        if (file.renameTo(targetFile)) {
                            recovered.add(targetFile);
                            AppLog.i(TAG, "Recovered orphan pending video: " + targetFile.getName());
                        } else {
                            unrecoverable.add(file);
                            unrecoverableBytes += len;
                        }
                    } else if (targetFile.length() == len) {
                        if (file.delete()) {
                            stubs.add(file);
                        }
                    } else {
                        unrecoverable.add(file);
                        unrecoverableBytes += len;
                    }
                } else {
                    unrecoverable.add(file);
                    unrecoverableBytes += len;
                }
            }
        }
        return new ScanReport(recovered, stubs, unrecoverable, unrecoverableBytes);
    }

    @NonNull
    public static DeleteResult deleteOrphansSafely(@NonNull List<File> orphans,
                                                   @NonNull Set<String> activePendingPaths) {
        int deleted = 0;
        long freed = 0L;
        for (File file : orphans) {
            if (file == null || !file.isFile() || !VideoLockStore.isPendingFile(file)) {
                continue;
            }
            if (activePendingPaths.contains(ActiveRecordingRegistry.normalizePath(file))) {
                continue;
            }
            long len = file.length();
            if (file.delete()) {
                deleted++;
                freed += len;
                AppLog.i(TAG, "User-confirmed cleanup of orphan pending file: " + file.getName());
            }
        }
        return new DeleteResult(deleted, freed);
    }

    /**
     * 硬性安全守卫：正常录制/拍摄成品文件绝不允许被碎片清理流程删除。
     */
    public static boolean isNeverDeletableNormalMedia(@Nullable File file) {
        if (file == null) {
            return true;
        }
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png");
    }

    private static boolean isTempProbeOrSpeedTestFile(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return (name.startsWith(PROBE_PREFIX) && lower.endsWith(".mp4" + VideoLockStore.PENDING_SUFFIX))
                || (name.startsWith(SPEED_TEST_PREFIX) && lower.endsWith(SPEED_TEST_SUFFIX));
    }

    private static boolean isWithinGraceWindow(@NonNull File file, long nowMs, long graceWindowMs) {
        long modified = file.lastModified();
        return nowMs > 0L && modified > 0L && (nowMs - modified) < graceWindowMs;
    }

    private static boolean isProtectedFragmentFile(@NonNull File file,
                                                   @NonNull Set<String> protectedPaths,
                                                   long nowMs,
                                                   long graceWindowMs) {
        String canonical = ActiveRecordingRegistry.normalizePath(file);
        if (!canonical.isEmpty() && protectedPaths.contains(canonical)) {
            return true;
        }
        return isWithinGraceWindow(file, nowMs, graceWindowMs);
    }

    /**
     * 扫描指定目录中可无损恢复或可安全手动清理的不可恢复碎片。
     * 扫描过程绝不删除任何文件，且绝不纳入正常 {@code .mp4/.jpg/.jpeg/.png} 及正在录制/传输中的文件。
     */
    @NonNull
    public static ManualScanReport scanForManualCleanup(@NonNull List<File> directories,
                                                        @NonNull Set<String> protectedPaths,
                                                        long nowMs,
                                                        long graceWindowMs,
                                                        @NonNull Mp4Validator validator) {
        List<File> recovered = new ArrayList<>();
        List<FragmentItem> deletable = new ArrayList<>();
        int pendingCount = 0;
        long pendingBytes = 0L;
        int partCount = 0;
        long partBytes = 0L;
        int tempProbeCount = 0;
        long tempProbeBytes = 0L;
        int orphanLockCount = 0;
        long orphanLockBytes = 0L;
        long totalBytes = 0L;
        int protectedActiveCount = 0;
        Set<String> visitedDirs = new HashSet<>();

        for (File dir : directories) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) {
                continue;
            }
            String dirKey = ActiveRecordingRegistry.normalizePath(dir);
            if (!visitedDirs.add(dirKey)) {
                continue;
            }
            File[] files = dir.listFiles(File::isFile);
            if (files == null || files.length == 0) {
                continue;
            }
            Set<String> dirFileNames = VideoLockStore.collectFileNames(files);
            for (File file : files) {
                if (file == null || !file.isFile()) {
                    continue;
                }
                if (isNeverDeletableNormalMedia(file)) {
                    continue;
                }
                String name = file.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith(".trashed-") || lower.startsWith("trashed-")) {
                    continue;
                }

                long len = file.length();
                if (isTempProbeOrSpeedTestFile(name)) {
                    if (isProtectedFragmentFile(file, protectedPaths, nowMs, graceWindowMs)) {
                        protectedActiveCount++;
                        continue;
                    }
                    deletable.add(new FragmentItem(file, FragmentCategory.TEMP_PROBE, len));
                    tempProbeCount++;
                    tempProbeBytes += len;
                    totalBytes += len;
                    continue;
                }

                if (lower.endsWith(".mp4" + VideoLockStore.PENDING_SUFFIX)) {
                    if (VideoLockStore.isProtectedPendingFile(file, protectedPaths, nowMs, graceWindowMs)) {
                        protectedActiveCount++;
                        continue;
                    }
                    if (len >= MIN_VALID_SIZE_BYTES) {
                        String targetName = name.substring(0, name.length() - VideoLockStore.PENDING_SUFFIX.length());
                        File targetFile = new File(file.getParentFile(), targetName);
                        boolean playable = false;
                        try {
                            playable = validator.isPlayableMp4(file);
                        } catch (Throwable ignored) {
                            playable = false;
                        }
                        if (playable && !targetFile.exists() && file.renameTo(targetFile)) {
                            recovered.add(targetFile);
                            AppLog.i(TAG, "Recovered playable pending video during manual scan: " + targetFile.getName());
                            continue;
                        }
                    }
                    deletable.add(new FragmentItem(file, FragmentCategory.PENDING_VIDEO, len));
                    pendingCount++;
                    pendingBytes += len;
                    totalBytes += len;
                    continue;
                }

                if (lower.endsWith(".mp4" + PART_SUFFIX)) {
                    if (isProtectedFragmentFile(file, protectedPaths, nowMs, graceWindowMs)) {
                        protectedActiveCount++;
                        continue;
                    }
                    deletable.add(new FragmentItem(file, FragmentCategory.TRANSFER_PART, len));
                    partCount++;
                    partBytes += len;
                    totalBytes += len;
                    continue;
                }

                if (!name.startsWith(".")
                        && lower.endsWith(".mp4" + VideoLockStore.LOCK_SUFFIX)
                        && VideoLockStore.isOrphanLockFile(file, dirFileNames)) {
                    if (isProtectedFragmentFile(file, protectedPaths, nowMs, graceWindowMs)) {
                        protectedActiveCount++;
                        continue;
                    }
                    deletable.add(new FragmentItem(file, FragmentCategory.ORPHAN_LOCK, len));
                    orphanLockCount++;
                    orphanLockBytes += len;
                    totalBytes += len;
                }
            }
        }
        return new ManualScanReport(
                recovered,
                deletable,
                pendingCount,
                pendingBytes,
                partCount,
                partBytes,
                tempProbeCount,
                tempProbeBytes,
                orphanLockCount,
                orphanLockBytes,
                totalBytes,
                protectedActiveCount);
    }

    /**
     * 在执行删除前二次复核全部安全条件，确保绝不误删正常录像、非孤立锁文件或活跃/宽限期文件。
     */
    @NonNull
    public static ManualDeleteResult deleteFragmentsSafely(@NonNull List<FragmentItem> fragments,
                                                             @NonNull Set<String> protectedPaths,
                                                             long nowMs,
                                                             long graceWindowMs) {
        int deleted = 0;
        int skipped = 0;
        long freed = 0L;
        for (FragmentItem item : fragments) {
            if (item == null || item.file == null || !item.file.isFile()) {
                skipped++;
                continue;
            }
            File file = item.file;
            if (isNeverDeletableNormalMedia(file)) {
                skipped++;
                continue;
            }
            if (isProtectedFragmentFile(file, protectedPaths, nowMs, graceWindowMs)) {
                skipped++;
                continue;
            }
            if (!matchesCategoryForDeletion(file, item.category)) {
                skipped++;
                continue;
            }
            long len = file.length();
            if (file.delete()) {
                deleted++;
                freed += len;
                AppLog.i(TAG, "User-confirmed cleanup deleted fragment [" + item.category + "]: " + file.getName());
            } else {
                skipped++;
            }
        }
        return new ManualDeleteResult(deleted, skipped, freed);
    }

    private static boolean matchesCategoryForDeletion(@NonNull File file, @NonNull FragmentCategory category) {
        String name = file.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        switch (category) {
            case PENDING_VIDEO:
                return lower.endsWith(".mp4" + VideoLockStore.PENDING_SUFFIX);
            case TRANSFER_PART:
                return lower.endsWith(".mp4" + PART_SUFFIX);
            case TEMP_PROBE:
                return isTempProbeOrSpeedTestFile(name);
            case ORPHAN_LOCK:
                if (name.startsWith(".") || !lower.endsWith(".mp4" + VideoLockStore.LOCK_SUFFIX)) {
                    return false;
                }
                File parent = file.getParentFile();
                if (parent == null) {
                    return false;
                }
                String targetName = name.substring(0, name.length() - VideoLockStore.LOCK_SUFFIX.length());
                return !targetName.isEmpty()
                        && !new File(parent, targetName).exists()
                        && !new File(parent, targetName + VideoLockStore.PENDING_SUFFIX).exists();
            default:
                return false;
        }
    }

    @NonNull
    public static List<File> collectVideoScanDirectories(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();
        List<File> dirs = new ArrayList<>();
        File internalVideoDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                StorageHelper.VIDEO_DIR_NAME);
        dirs.add(internalVideoDir);

        File sdRoot = StorageHelper.getExternalSdCardRoot(appCtx);
        if (sdRoot != null && sdRoot.exists()) {
            dirs.add(new File(new File(sdRoot, Environment.DIRECTORY_DCIM), StorageHelper.VIDEO_DIR_NAME));
        }
        dirs.add(new File(appCtx.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR));
        return dirs;
    }

    private static void triggerTempRelayIfRecovered(@NonNull Context appCtx, @NonNull List<File> recoveredFiles) {
        if (recoveredFiles.isEmpty()) {
            return;
        }
        File tempVideoDir = new File(appCtx.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
        String tempDirPath = ActiveRecordingRegistry.normalizePath(tempVideoDir);
        boolean recoveredInTemp = false;
        for (File recovered : recoveredFiles) {
            File parent = recovered.getParentFile();
            if (parent != null && tempDirPath.equals(ActiveRecordingRegistry.normalizePath(parent))) {
                recoveredInTemp = true;
                break;
            }
        }
        if (recoveredInTemp && new AppConfig(appCtx).shouldUseRelayWrite()) {
            FileTransferManager.getInstance(appCtx).cleanupAndRetryTempFiles();
        }
    }

    public static void scanStartupAsync(@NonNull Context context,
                                        @Nullable Consumer<ScanReport> mainThreadCallback) {
        final Context appCtx = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            List<File> dirs = collectVideoScanDirectories(appCtx);

            ScanReport report = scanDirectories(
                    dirs,
                    ActiveRecordingRegistry.snapshot(),
                    System.currentTimeMillis(),
                    STARTUP_GRACE_WINDOW_MS,
                    PendingFileRecoveryManager::isPlayableAndroidMp4);

            triggerTempRelayIfRecovered(appCtx, report.recoveredFiles);

            if (!report.autoDeletedStubs.isEmpty() || report.hasUserVisibleFindings()) {
                AppLog.i(TAG, "Startup orphan pending scan: recovered=" + report.recoveredFiles.size()
                        + ", deletedStubs=" + report.autoDeletedStubs.size()
                        + ", unrecoverable=" + report.unrecoverableOrphans.size()
                        + " (" + StorageHelper.formatSize(report.unrecoverableBytes) + ")");
            }

            if (mainThreadCallback != null) {
                new Handler(Looper.getMainLooper()).post(() -> mainThreadCallback.accept(report));
            }
        }, "StartupPendingRecovery");
        worker.setDaemon(true);
        worker.start();
    }

    public static void scanForManualCleanupAsync(@NonNull Context context,
                                                 @Nullable Consumer<ManualScanReport> mainThreadCallback) {
        final Context appCtx = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            ManualScanReport report;
            try {
                List<File> dirs = collectVideoScanDirectories(appCtx);
                Set<String> protectedPaths = new HashSet<>(ActiveRecordingRegistry.snapshot());
                protectedPaths.addAll(FileTransferManager.getInstance(appCtx).collectActiveTransferPaths());

                report = scanForManualCleanup(
                        dirs,
                        protectedPaths,
                        System.currentTimeMillis(),
                        VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS,
                        PendingFileRecoveryManager::isPlayableAndroidMp4);

                triggerTempRelayIfRecovered(appCtx, report.recoveredFiles);

                AppLog.i(TAG, "Manual fragment scan: recovered=" + report.recoveredFiles.size()
                        + ", deletable=" + report.getTotalDeletableCount()
                        + " (" + StorageHelper.formatSize(report.totalDeletableBytes) + ")"
                        + ", protectedActive=" + report.protectedActiveCount);
            } catch (Throwable t) {
                AppLog.e(TAG, "Manual fragment scan failed", t);
                report = new ManualScanReport(
                        Collections.emptyList(),
                        Collections.emptyList(),
                        0, 0L, 0, 0L, 0, 0L, 0, 0L, 0L, 0);
            }

            if (mainThreadCallback != null) {
                final ManualScanReport finalReport = report;
                new Handler(Looper.getMainLooper()).post(() -> mainThreadCallback.accept(finalReport));
            }
        }, "ManualFragmentScan");
        worker.setDaemon(true);
        worker.start();
    }
}
