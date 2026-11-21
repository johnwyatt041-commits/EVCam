package com.kooo.evcam;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;

import com.kooo.evcam.camera.ActiveRecordingRegistry;
import com.kooo.evcam.playback.VideoLockStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 文件传输管理器
 * 负责将临时目录中的视频文件异步传输到目标存储（如U盘）
 * 
 * 工作原理：
 * 1. 录制时先写入内部存储的临时目录（高速）
 * 2. 分段完成后，将文件加入传输队列
 * 3. 后台线程负责将文件移动/复制到目标目录
 * 4. 传输完成后删除临时文件
 * 
 * 这样可以避免U盘慢速写入影响录制性能
 */
public class FileTransferManager {
    private static final String TAG = "FileTransferManager";
    
    // 临时目录名称（在内部存储的应用缓存目录下）
    public static final String TEMP_VIDEO_DIR = "temp_video";
    
    // 传输任务
    private static class TransferTask {
        final File sourceFile;      // 源文件（临时目录中）
        final File targetFile;      // 目标文件（最终存储位置）
        final TransferCallback callback;
        int retryCount;             // 重试次数
        
        TransferTask(File source, File target, TransferCallback callback) {
            this.sourceFile = source;
            this.targetFile = target;
            this.callback = callback;
            this.retryCount = 0;
        }
    }
    
    // 传输回调
    public interface TransferCallback {
        void onTransferComplete(File sourceFile, File targetFile);
        void onTransferFailed(File sourceFile, File targetFile, String error);
    }
    
    // 单例
    private static FileTransferManager instance;
    
    private final Context context;
    private final ConcurrentLinkedQueue<TransferTask> transferQueue;
    private HandlerThread transferThread;
    private Handler transferHandler;
    private final AtomicBoolean isRunning;
    private final AtomicBoolean isProcessing;
    
    // 配置
    private static final int MAX_RETRY_COUNT = 5;           // 最大重试次数（增加到5次）
    private static final long RETRY_DELAY_MS = 3000;        // 重试延迟（毫秒）- 减少到3秒加快重试
    private static final long STARTUP_CLEANUP_DELAY_MS = 60 * 1000;  // 启动后清理延迟：1分钟
    private static final long PENDING_ACTIVE_GRACE_MS = 2 * 60 * 1000; // 活跃 .pending 宽限窗口
    private static final long STALE_TINY_FILE_AGE_MS = 5 * 60 * 1000;  // 极小无效残片延迟清理时间：5分钟
    private static final long STALE_PART_FILE_AGE_MS = 60 * 60 * 1000; // 残留 .part 分片清理时间：1小时
    private static final long MIN_VALID_FILE_SIZE = 1024;  // Short, valid recordings can be smaller than 500 KB.

    private volatile String inFlightSourcePath = null;

    public static final class TempCleanupStats {
        public int deletedVerifiedCount = 0;
        public int deletedInvalidCount = 0;
        public int requeuedCount = 0;
        public int preservedUnsavedCount = 0;
        public long freedBytes = 0;
        public long preservedUnsavedBytes = 0;
    }

    public static final class EmergencyEvictionResult {
        public final int deletedCount;
        public final long deletedBytes;

        public EmergencyEvictionResult(int deletedCount, long deletedBytes) {
            this.deletedCount = deletedCount;
            this.deletedBytes = deletedBytes;
        }
    }
    
    // 统计
    private long totalTransferred = 0;      // 已传输文件数
    private long totalFailed = 0;           // 失败文件数
    private long totalBytesTransferred = 0; // 已传输字节数
    
    private FileTransferManager(Context context) {
        this.context = context.getApplicationContext();
        this.transferQueue = new ConcurrentLinkedQueue<>();
        this.isRunning = new AtomicBoolean(false);
        this.isProcessing = new AtomicBoolean(false);
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized FileTransferManager getInstance(Context context) {
        if (instance == null) {
            instance = new FileTransferManager(context);
        }
        return instance;
    }
    
    /**
     * 启动传输服务
     */
    public synchronized void start() {
        if (isRunning.getAndSet(true)) {
            AppLog.d(TAG, "Transfer service already running");
            return;
        }
        
        transferThread = new HandlerThread("FileTransfer");
        transferThread.start();
        transferHandler = new Handler(transferThread.getLooper());
        
        // Resume any queued work once. Empty queues do not need a polling timer.
        transferHandler.post(this::processQueue);
        
        // 启动后1分钟检查并清理过期的临时文件
        transferHandler.postDelayed(this::cleanupExpiredTempFiles, STARTUP_CLEANUP_DELAY_MS);
        
        AppLog.d(TAG, "File transfer service started");
    }
    
    /**
     * 停止传输服务
     */
    public synchronized void stop() {
        if (!isRunning.getAndSet(false)) {
            return;
        }
        
        if (transferHandler != null) {
            transferHandler.removeCallbacksAndMessages(null);
            transferHandler = null;
        }
        
        if (transferThread != null) {
            transferThread.quitSafely();
            try {
                transferThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            transferThread = null;
        }
        
        AppLog.d(TAG, "File transfer service stopped. Stats: transferred=" + totalTransferred + 
                ", failed=" + totalFailed + ", bytes=" + formatSize(totalBytesTransferred));
    }
    
    /**
     * 添加传输任务
     * @param sourceFile 源文件（临时目录中）
     * @param targetFile 目标文件（最终位置）
     * @param callback 回调（可为null）
     */
    public void addTransferTask(File sourceFile, File targetFile, TransferCallback callback) {
        if (sourceFile == null || !sourceFile.exists()) {
            AppLog.w(TAG, "Source file does not exist: " + sourceFile);
            if (callback != null) {
                callback.onTransferFailed(sourceFile, targetFile, "Source file not found");
            }
            return;
        }
        if (callback == null && isSourceQueuedOrInFlight(sourceFile)) {
            AppLog.d(TAG, "Skipping duplicate transfer task for already queued/active file: " + sourceFile.getName());
            return;
        }
        
        TransferTask task = new TransferTask(sourceFile, targetFile, callback);
        start();
        transferQueue.offer(task);
        
        AppLog.d(TAG, "Added transfer task: " + sourceFile.getName() + " -> " + targetFile.getAbsolutePath());
        
        // 如果服务在运行，立即触发处理
        if (isRunning.get() && transferHandler != null) {
            transferHandler.post(this::processQueue);
        }
    }

    private boolean isSourceQueuedOrInFlight(File sourceFile) {
        String normalized = ActiveRecordingRegistry.normalizePath(sourceFile);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.equals(inFlightSourcePath)) {
            return true;
        }
        for (TransferTask queued : transferQueue) {
            if (queued != null && normalized.equals(ActiveRecordingRegistry.normalizePath(queued.sourceFile))) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 获取临时视频目录
     * @return 临时目录，如果创建失败返回null
     */
    public File getTempVideoDir() {
        File tempDir = new File(context.getCacheDir(), TEMP_VIDEO_DIR);
        if (!tempDir.exists()) {
            if (!tempDir.mkdirs()) {
                AppLog.e(TAG, "Failed to create temp video directory: " + tempDir.getAbsolutePath());
                return null;
            }
        }
        return tempDir;
    }
    
    /**
     * 获取临时目录中的文件数量
     */
    public int getPendingFileCount() {
        File tempDir = getTempVideoDir();
        if (tempDir == null || !tempDir.exists()) {
            return 0;
        }
        File[] files = tempDir.listFiles();
        return files != null ? files.length : 0;
    }
    
    /**
     * 获取临时目录占用的空间（字节）
     */
    public long getTempDirSize() {
        File tempDir = getTempVideoDir();
        if (tempDir == null || !tempDir.exists()) {
            return 0;
        }
        
        long size = 0;
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                size += file.length();
            }
        }
        return size;
    }
    
    /**
     * 清理临时目录（删除所有文件）
     * 注意：仅在确认不需要这些文件时调用
     */
    public void clearTempDir() {
        File tempDir = getTempVideoDir();
        if (tempDir == null || !tempDir.exists()) {
            return;
        }
        
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.delete()) {
                    AppLog.d(TAG, "Deleted temp file: " + file.getName());
                }
            }
        }
        
        AppLog.d(TAG, "Temp directory cleared");
    }
    
    /**
     * 获取队列中等待传输的任务数
     */
    public int getQueueSize() {
        return transferQueue.size();
    }
    
    /**
     * 获取传输统计信息
     */
    public String getStats() {
        return String.format("已传输: %d 个文件 (%s), 失败: %d, 队列: %d, 临时文件: %d",
                totalTransferred, formatSize(totalBytesTransferred), 
                totalFailed, getQueueSize(), getPendingFileCount());
    }
    
    // ===== 私有与清理校验方法 =====

    /**
     * 触发一次临时目录校验清理与未落盘文件补传（异步走队列线程或同步兜底）。
     */
    public void cleanupAndRetryTempFiles() {
        if (isRunning.get() && transferHandler != null) {
            transferHandler.post(this::runCleanupAndRetrySync);
        } else {
            runCleanupAndRetrySync();
        }
    }

    /**
     * 校验临时目录：仅删除已确认安全落盘到目标目录的文件（或超时极小无效残片），
     * 绝不因超过 1 小时而误删尚未成功保存到 U 盘的有效视频；目标目录可用时自动入队补传。
     */
    public TempCleanupStats runCleanupAndRetrySync() {
        return runCleanupAndRetrySync(true);
    }

    private TempCleanupStats runCleanupAndRetrySync(boolean allowRequeue) {
        File tempDir = getTempVideoDir();
        if (tempDir == null || !tempDir.exists()) {
            return new TempCleanupStats();
        }
        File targetDir = resolveVerifiedTargetVideoDir(context);
        Set<String> protectedPaths = collectProtectedPaths();
        TempCleanupStats stats = inspectAndCleanTempDir(
                tempDir,
                targetDir,
                protectedPaths,
                System.currentTimeMillis(),
                MIN_VALID_FILE_SIZE,
                allowRequeue ? (file -> {
                    if (targetDir != null) {
                        addTransferTask(file, new File(targetDir, file.getName()), null);
                    }
                }) : null
        );
        if (stats.deletedVerifiedCount > 0 || stats.deletedInvalidCount > 0
                || stats.requeuedCount > 0 || stats.preservedUnsavedCount > 0) {
            AppLog.i(TAG, "Temp cleanup/verify: deletedVerified=" + stats.deletedVerifiedCount
                    + ", deletedInvalid=" + stats.deletedInvalidCount
                    + ", requeued=" + stats.requeuedCount
                    + ", preservedUnsaved=" + stats.preservedUnsavedCount
                    + " (" + formatSize(stats.preservedUnsavedBytes) + ")"
                    + ", freed=" + formatSize(stats.freedBytes));
        }
        if (stats.preservedUnsavedCount > 0) {
            AppLog.w(TAG, "Preserved " + stats.preservedUnsavedCount
                    + " unsaved temp video(s) because target USB storage is unavailable or unverified");
        }
        return stats;
    }

    /**
     * 内部缓存进入危险低空间阈值时的紧急淘汰：先清理已验证落盘的临时文件，
     * 仅在仍不足时才按最旧时间淘汰尚未传走的历史临时视频（仍严格跳过录制中/传输中文件）。
     */
    public EmergencyEvictionResult evictOldestUnsavedForEmergencySpace(long bytesToFree) {
        runCleanupAndRetrySync(false);
        if (bytesToFree <= 0L) {
            return new EmergencyEvictionResult(0, 0L);
        }
        File tempDir = getTempVideoDir();
        if (tempDir == null || !tempDir.exists()) {
            return new EmergencyEvictionResult(0, 0L);
        }
        File[] files = tempDir.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return new EmergencyEvictionResult(0, 0L);
        }
        Set<String> protectedPaths = collectProtectedPaths();
        List<File> candidates = new ArrayList<>();
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            if (protectedPaths.contains(ActiveRecordingRegistry.normalizePath(file))) {
                continue;
            }
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                continue;
            }
            candidates.add(file);
        }
        candidates.sort(Comparator.comparingLong(File::lastModified));
        int deletedCount = 0;
        long deletedBytes = 0L;
        for (File candidate : candidates) {
            if (deletedBytes >= bytesToFree) {
                break;
            }
            long len = candidate.length();
            if (candidate.delete()) {
                deletedCount++;
                deletedBytes += len;
                AppLog.w(TAG, "Emergency cache eviction deleted unsaved temp video: "
                        + candidate.getName() + " (" + formatSize(len) + ")");
            }
        }
        return new EmergencyEvictionResult(deletedCount, deletedBytes);
    }

    private Set<String> collectProtectedPaths() {
        Set<String> protectedPaths = new HashSet<>(ActiveRecordingRegistry.snapshot());
        String activeFlight = inFlightSourcePath;
        if (activeFlight != null && !activeFlight.isEmpty()) {
            protectedPaths.add(activeFlight);
        }
        for (TransferTask task : transferQueue) {
            if (task != null && task.sourceFile != null) {
                String path = ActiveRecordingRegistry.normalizePath(task.sourceFile);
                if (!path.isEmpty()) {
                    protectedPaths.add(path);
                }
            }
        }
        return protectedPaths;
    }

    static File resolveVerifiedTargetVideoDir(Context context) {
        if (context == null) {
            return null;
        }
        AppConfig config = new AppConfig(context);
        if (config.isUsingExternalSdCard()) {
            File sdRoot = StorageHelper.getExternalSdCardRoot(context);
            if (sdRoot == null || !sdRoot.exists()) {
                return null;
            }
            File dcimDir = new File(sdRoot, Environment.DIRECTORY_DCIM);
            if (!dcimDir.exists() || !dcimDir.isDirectory() || !dcimDir.canWrite()) {
                return null;
            }
            File videoDir = new File(dcimDir, StorageHelper.VIDEO_DIR_NAME);
            if (!videoDir.exists()) {
                videoDir.mkdirs();
            }
            return (videoDir.isDirectory() && videoDir.canWrite()) ? videoDir : null;
        }
        File publicDcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        if (publicDcim == null || !publicDcim.exists()) {
            return null;
        }
        File internalVideoDir = StorageHelper.getVideoDir(context, false);
        return (internalVideoDir != null && internalVideoDir.isDirectory() && internalVideoDir.canWrite())
                ? internalVideoDir
                : null;
    }

    static boolean isVerifiedInTargetDir(File sourceFile, File targetDir) {
        if (sourceFile == null || !sourceFile.isFile() || sourceFile.length() < MIN_VALID_FILE_SIZE) {
            return false;
        }
        if (targetDir == null || !targetDir.isDirectory()) {
            return false;
        }
        File targetFile = new File(targetDir, sourceFile.getName());
        return targetFile.isFile() && targetFile.length() == sourceFile.length();
    }

    static TempCleanupStats inspectAndCleanTempDir(File tempDir,
                                                    File verifiedTargetDir,
                                                    Set<String> protectedPaths,
                                                    long nowMs,
                                                    long minValidSize,
                                                    Consumer<File> requeueCallback) {
        TempCleanupStats stats = new TempCleanupStats();
        if (tempDir == null || !tempDir.exists() || !tempDir.isDirectory()) {
            return stats;
        }
        File[] files = tempDir.listFiles();
        if (files == null || files.length == 0) {
            return stats;
        }
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String canonical = ActiveRecordingRegistry.normalizePath(file);
            if (protectedPaths != null && protectedPaths.contains(canonical)) {
                continue;
            }
            long len = file.length();
            long age = Math.max(0L, nowMs - file.lastModified());
            if (VideoLockStore.isPendingFile(file)) {
                if (VideoLockStore.isProtectedPendingFile(file, protectedPaths, nowMs, PENDING_ACTIVE_GRACE_MS)) {
                    continue;
                }
                if (len < minValidSize && age >= STALE_TINY_FILE_AGE_MS && file.delete()) {
                    stats.deletedInvalidCount++;
                    stats.freedBytes += len;
                }
                continue;
            }
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".mp4")) {
                if (lower.endsWith(".part") && age >= STALE_PART_FILE_AGE_MS && file.delete()) {
                    stats.deletedInvalidCount++;
                    stats.freedBytes += len;
                }
                continue;
            }
            if (len < minValidSize) {
                if (age >= STALE_TINY_FILE_AGE_MS && file.delete()) {
                    stats.deletedInvalidCount++;
                    stats.freedBytes += len;
                    AppLog.d(TAG, "Cleanup: deleted tiny invalid temp file: " + file.getName());
                }
                continue;
            }
            if (verifiedTargetDir != null && isVerifiedInTargetDir(file, verifiedTargetDir)) {
                if (file.delete()) {
                    stats.deletedVerifiedCount++;
                    stats.freedBytes += len;
                    AppLog.d(TAG, "Cleanup: deleted verified temp file (already on target): " + file.getName());
                }
                continue;
            }
            if (verifiedTargetDir != null && requeueCallback != null) {
                requeueCallback.accept(file);
                stats.requeuedCount++;
            } else {
                stats.preservedUnsavedCount++;
                stats.preservedUnsavedBytes += len;
            }
        }
        return stats;
    }

    /**
     * 清理临时文件（启动 1 分钟后调用）
     */
    private void cleanupExpiredTempFiles() {
        runCleanupAndRetrySync();
    }
    
    /**
     * 处理传输队列
     */
    private void processQueue() {
        if (!isRunning.get() || isProcessing.getAndSet(true)) {
            return;
        }
        
        try {
            TransferTask task;
            while ((task = transferQueue.poll()) != null) {
                if (!isRunning.get()) {
                    // 服务停止，将任务放回队列
                    transferQueue.offer(task);
                    break;
                }
                
                processTask(task);
            }
        } finally {
            isProcessing.set(false);
        }
    }
    
    /**
     * 处理单个传输任务
     */
    private void processTask(TransferTask task) {
        inFlightSourcePath = task != null ? ActiveRecordingRegistry.normalizePath(task.sourceFile) : null;
        try {
            processTaskInternal(task);
        } finally {
            inFlightSourcePath = null;
        }
    }

    private void processTaskInternal(TransferTask task) {
        if (!task.sourceFile.exists()) {
            AppLog.w(TAG, "Source file no longer exists: " + task.sourceFile.getName());
            if (task.callback != null) {
                task.callback.onTransferFailed(task.sourceFile, task.targetFile, "Source file not found");
            }
            totalFailed++;
            return;
        }

        // 验证源文件大小
        long sourceSize = task.sourceFile.length();
        if (sourceSize < MIN_VALID_FILE_SIZE) {
            AppLog.w(TAG, "Source file too small: " + task.sourceFile.getName() + " (" + sourceSize + " bytes)");
            task.sourceFile.delete();
            if (task.callback != null) {
                task.callback.onTransferFailed(task.sourceFile, task.targetFile, "Source file too small");
            }
            totalFailed++;
            return;
        }

        // 确保目标目录存在
        File targetDir = task.targetFile.getParentFile();
        if (targetDir != null && !targetDir.exists()) {
            AppLog.d(TAG, "Creating target directory: " + targetDir.getAbsolutePath());
            if (!targetDir.mkdirs()) {
                AppLog.e(TAG, "Failed to create target directory: " + targetDir.getAbsolutePath());
                handleTransferFailure(task, "Cannot create target directory");
                return;
            }
        }

        // 检查目标目录是否可写
        if (targetDir != null && !targetDir.canWrite()) {
            AppLog.e(TAG, "Target directory is not writable: " + targetDir.getAbsolutePath());
            handleTransferFailure(task, "Target directory not writable");
            return;
        }

        // 尝试复制文件（直接复制，不尝试移动，因为U盘可能是不同的文件系统）
        AppLog.d(TAG, "Copying file: " + task.sourceFile.getName() + " (" + formatSize(sourceSize) + ") to " + task.targetFile.getAbsolutePath());

        boolean copied = copyFile(task.sourceFile, task.targetFile);

        if (copied) {
            // 复制成功，删除源文件
            long targetSize = task.targetFile.length();

            // 验证目标文件大小
            if (targetSize != sourceSize) {
                AppLog.w(TAG, "File size mismatch: source=" + sourceSize + ", target=" + targetSize);
                task.targetFile.delete();
                handleTransferFailure(task, "File size mismatch after copy");
                return;
            }

            if (task.sourceFile.delete()) {
                AppLog.d(TAG, "File transferred successfully: " + task.sourceFile.getName() +
                        " -> " + task.targetFile.getAbsolutePath() + " (" + formatSize(targetSize) + ")");
            } else {
                AppLog.w(TAG, "File copied but failed to delete source: " + task.sourceFile.getName());
            }

            totalTransferred++;
            totalBytesTransferred += targetSize;

            if (task.callback != null) {
                task.callback.onTransferComplete(task.sourceFile, task.targetFile);
            }
        } else {
            // 复制失败
            // 清理可能不完整的目标文件
            if (task.targetFile.exists()) {
                task.targetFile.delete();
            }
            handleTransferFailure(task, "Copy failed");
        }
    }
    
    /**
     * 处理传输失败
     */
    private void handleTransferFailure(TransferTask task, String error) {
        task.retryCount++;
        
        if (task.retryCount < MAX_RETRY_COUNT) {
            // 重试
            AppLog.w(TAG, "Transfer failed, will retry (" + task.retryCount + "/" + MAX_RETRY_COUNT + "): " + 
                    task.sourceFile.getName() + " - " + error);
            
            // 延迟后重新加入队列
            if (transferHandler != null) {
                transferHandler.postDelayed(() -> {
                    transferQueue.offer(task);
                    processQueue();
                }, RETRY_DELAY_MS);
            }
        } else {
            // 超过重试次数，放弃
            AppLog.e(TAG, "Transfer failed after " + MAX_RETRY_COUNT + " retries: " + 
                    task.sourceFile.getName() + " - " + error);
            
            totalFailed++;
            
            if (task.callback != null) {
                task.callback.onTransferFailed(task.sourceFile, task.targetFile, error);
            }
        }
    }
    
    /**
     * 复制文件（优先写入 .part 临时目标文件并落盘 sync 后原子重命名，避免中断在 U 盘留下半截 .mp4）
     */
    private boolean copyFile(File source, File target) {
        long size = source.length();
        if (size < MIN_VALID_FILE_SIZE) {
            AppLog.w(TAG, "Source file too small: " + source.getName() + " (" + size + " bytes)");
            return false;
        }
        File parent = target.getParentFile();
        File partFile = parent != null ? new File(parent, target.getName() + ".part") : null;
        if (partFile != null) {
            if (partFile.exists()) {
                partFile.delete();
            }
            if (copyFileDirect(source, partFile, size)) {
                if (target.exists() && !target.delete()) {
                    AppLog.w(TAG, "Failed to delete existing target file before rename: " + target.getName());
                }
                if (partFile.renameTo(target) && target.exists() && target.length() == size) {
                    AppLog.d(TAG, "File copied successfully (.part atomic): " + source.getName() + " (" + formatSize(size) + ")");
                    return true;
                }
                if (partFile.exists()) {
                    partFile.delete();
                }
                AppLog.w(TAG, "Atomic .part rename failed on target FS, falling back to direct copy: " + target.getName());
            } else {
                if (partFile.exists()) {
                    partFile.delete();
                }
                return false;
            }
        }
        if (target.exists() && !target.delete()) {
            AppLog.w(TAG, "Failed to delete existing target file: " + target.getName());
        }
        return copyFileDirect(source, target, size);
    }

    private boolean copyFileDirect(File source, File destination, long expectedSize) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(source);
            fos = new FileOutputStream(destination);

            byte[] buffer = new byte[64 * 1024];
            long transferred = 0;
            int read;

            while ((read = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                transferred += read;
                if (transferred % (1024 * 1024) == 0) {
                    fos.flush();
                }
            }

            fos.flush();
            fos.getFD().sync();

            fis.close();
            fis = null;
            fos.close();
            fos = null;

            long destSize = destination.length();
            if (destSize != expectedSize) {
                AppLog.w(TAG, "File size mismatch after copy: source=" + expectedSize + ", dest=" + destSize);
                destination.delete();
                return false;
            }
            return true;
        } catch (IOException e) {
            AppLog.e(TAG, "Error copying file: " + source.getName(), e);
            if (destination.exists()) {
                destination.delete();
            }
            return false;
        } finally {
            try {
                if (fis != null) fis.close();
                if (fos != null) fos.close();
            } catch (IOException ignored) {
            }
        }
    }
    
    /**
     * 格式化文件大小
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
