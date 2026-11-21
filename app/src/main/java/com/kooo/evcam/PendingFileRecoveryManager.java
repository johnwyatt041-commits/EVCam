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
    private static final int MAX_TOP_LEVEL_MP4_BOXES = 256;

    public interface Mp4Validator {
        boolean isPlayableMp4(@NonNull File file);
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

    public static void scanStartupAsync(@NonNull Context context,
                                        @Nullable Consumer<ScanReport> mainThreadCallback) {
        final Context appCtx = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            List<File> dirs = new ArrayList<>();
            File internalVideoDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    StorageHelper.VIDEO_DIR_NAME);
            dirs.add(internalVideoDir);

            File sdRoot = StorageHelper.getExternalSdCardRoot(appCtx);
            if (sdRoot != null && sdRoot.exists()) {
                dirs.add(new File(new File(sdRoot, Environment.DIRECTORY_DCIM), StorageHelper.VIDEO_DIR_NAME));
            }
            File tempVideoDir = new File(appCtx.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
            dirs.add(tempVideoDir);

            ScanReport report = scanDirectories(
                    dirs,
                    ActiveRecordingRegistry.snapshot(),
                    System.currentTimeMillis(),
                    STARTUP_GRACE_WINDOW_MS,
                    PendingFileRecoveryManager::isPlayableAndroidMp4);

            if (!report.recoveredFiles.isEmpty()) {
                String tempDirPath = ActiveRecordingRegistry.normalizePath(tempVideoDir);
                boolean recoveredInTemp = false;
                for (File recovered : report.recoveredFiles) {
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
}
