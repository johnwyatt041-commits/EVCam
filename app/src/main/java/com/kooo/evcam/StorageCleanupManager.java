package com.kooo.evcam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.kooo.evcam.camera.ActiveRecordingRegistry;
import com.kooo.evcam.playback.VideoLockStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 存储清理管理器
 * 自动删除超过限制的旧视频和图片文件，并结合分段完成事件与写入速率动态预留空间
 * 
 * 功能：
 * - 冷启动30秒后执行首次检测
 * - 每隔1小时执行定期检测
 * - 每次录像分段切换完成后按 O(1) 快速门控 + 写入速率预留触发增量检测
 * - 启用 U 盘中转写入时同步检查并保护内部缓存空间
 * - 支持分别设置视频和图片的存储限制（GB）
 * - 删除时额外删除20%，避免频繁删除
 */
public class StorageCleanupManager {
    private static final String TAG = "StorageCleanupManager";
    private static volatile StorageCleanupManager activeInstance;
    
    // 定时任务延迟
    private static final long INITIAL_DELAY_MS = 30 * 1000;  // 冷启动后30秒
    private static final long PERIODIC_INTERVAL_MS = 60 * 60 * 1000;  // 每1小时
    private static final long SEGMENT_CHECK_DEBOUNCE_MS = 2_000L;
    private static final long MIN_SEGMENT_CHECK_INTERVAL_MS = 15_000L;
    
    // 额外删除比例（20%）
    private static final double EXTRA_DELETE_RATIO = 0.20;
    
    // GB 转 字节
    private static final long GB_TO_BYTES = 1024L * 1024L * 1024L;
    private static final long FALLBACK_WRITE_RATE_BPS = 3L * StorageSpaceGuard.MB;
    
    // 内部存储低空间阈值（3GB，当动态预留未超过时作为下限）
    private static final long LOW_SPACE_THRESHOLD_BYTES = 3L * GB_TO_BYTES;
    
    // 低空间强制清理比例（删除20%的已用空间，保留80%）
    private static final double LOW_SPACE_CLEANUP_RATIO = 0.20;
    
    private final Context context;
    private final AppConfig appConfig;
    private ScheduledExecutorService scheduler;
    private Handler mainHandler;
    private volatile boolean isRunning = false;

    private final StorageSpaceGuard spaceGuard = new StorageSpaceGuard();
    private final ConcurrentLinkedQueue<SegmentNotice> pendingSegments = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean segmentCheckScheduled = new AtomicBoolean(false);
    private final AtomicLong lastSegmentCheckMs = new AtomicLong(0L);
    private volatile long lastSegmentDurationMs = 60_000L;

    // 仅在单线程 scheduler 中读写，用于避免每次分段都扫描整个视频目录
    private String cachedVideoDirPath = null;
    private long cachedVideoDirBytes = -1L;
    private long lastVideoFullScanMs = 0L;
    private long estimatedAddedVideoBytes = 0L;

    private static final class SegmentNotice {
        final String filePath;
        final long segmentDurationMs;
        final long completedAtMs;

        SegmentNotice(String filePath, long segmentDurationMs, long completedAtMs) {
            this.filePath = filePath;
            this.segmentDurationMs = segmentDurationMs;
            this.completedAtMs = completedAtMs;
        }
    }
    
    public StorageCleanupManager(Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 录像分段切换完成时调用：合并多路并发通知，并在后台低优先级线程执行 O(1) 门控空间检查。
     */
    public static void notifySegmentCompleted(@Nullable String completedFilePath, long segmentDurationMs) {
        StorageCleanupManager instance = activeInstance;
        if (instance != null) {
            instance.onSegmentCompleted(completedFilePath, segmentDurationMs);
        }
    }

    /**
     * 写入触发空间不足错误时立即异步检查并清理空间。
     */
    public static void triggerEmergencyCleanupIfActive() {
        StorageCleanupManager instance = activeInstance;
        if (instance != null) {
            instance.triggerImmediateEmergencyCheck();
        }
    }
    
    /**
     * 启动存储清理任务
     * 冷启动30秒后执行首次检测，之后每隔1小时执行一次，同时响应分段切换完成事件
     * 注意：即使清理功能未启用，也会启动以检测低空间情况
     */
    public void start() {
        if (isRunning) {
            AppLog.d(TAG, "存储清理任务已在运行");
            return;
        }
        
        isRunning = true;
        activeInstance = this;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "StorageCleanup");
            t.setDaemon(true);
            return t;
        });
        
        // 30秒后执行首次检测
        scheduler.schedule(this::performCleanup, INITIAL_DELAY_MS, TimeUnit.MILLISECONDS);
        
        // 每1小时执行一次定期检测
        scheduler.scheduleAtFixedRate(
            this::performCleanup,
            INITIAL_DELAY_MS + PERIODIC_INTERVAL_MS,  // 首次定期检测在首次检测后1小时
            PERIODIC_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        
        AppLog.d(TAG, "存储清理任务已启动：30秒后首次检测，之后每1小时检测一次，并启用分段空间守卫");
        AppLog.d(TAG, "视频限制: " + appConfig.getVideoStorageLimitGb() + " GB, 图片限制: " + appConfig.getPhotoStorageLimitGb() + " GB");
    }
    
    /**
     * 停止存储清理任务
     */
    public void stop() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        isRunning = false;
        segmentCheckScheduled.set(false);
        pendingSegments.clear();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            scheduler = null;
        }
        AppLog.d(TAG, "存储清理任务已停止");
    }

    public void onSegmentCompleted(@Nullable String completedFilePath, long segmentDurationMs) {
        ScheduledExecutorService exec = scheduler;
        if (!isRunning || exec == null || exec.isShutdown()) {
            return;
        }
        long normalizedSegMs = segmentDurationMs > 0L ? segmentDurationMs : lastSegmentDurationMs;
        if (segmentDurationMs > 0L) {
            lastSegmentDurationMs = segmentDurationMs;
        }
        pendingSegments.offer(new SegmentNotice(completedFilePath, normalizedSegMs, System.currentTimeMillis()));
        scheduleCoalescedSegmentCheck(exec);
    }

    private void scheduleCoalescedSegmentCheck(@Nullable ScheduledExecutorService exec) {
        if (!isRunning || exec == null || exec.isShutdown()) {
            return;
        }
        if (!segmentCheckScheduled.compareAndSet(false, true)) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0L, now - lastSegmentCheckMs.get());
        long delayMs = Math.max(SEGMENT_CHECK_DEBOUNCE_MS, MIN_SEGMENT_CHECK_INTERVAL_MS - elapsed);
        try {
            exec.schedule(this::performSegmentSpaceCheck, delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            segmentCheckScheduled.set(false);
        }
    }

    public void triggerImmediateEmergencyCheck() {
        ScheduledExecutorService exec = scheduler;
        if (!isRunning || exec == null || exec.isShutdown()) {
            return;
        }
        try {
            exec.execute(this::performEmergencyCleanup);
        } catch (RuntimeException ignored) {
        }
    }

    private void performSegmentSpaceCheck() {
        long now = System.currentTimeMillis();
        try {
            lastSegmentCheckMs.set(now);
            SegmentNotice notice;
            while ((notice = pendingSegments.poll()) != null) {
                if (notice.segmentDurationMs > 0L) {
                    lastSegmentDurationMs = notice.segmentDurationMs;
                }
                if (notice.filePath != null && !notice.filePath.isEmpty()) {
                    File completed = new File(notice.filePath);
                    long len = completed.isFile() ? completed.length() : 0L;
                    if (len > 0L) {
                        spaceGuard.recordSegment(notice.completedAtMs, len, notice.segmentDurationMs);
                        estimatedAddedVideoBytes += len;
                    }
                }
            }

            long segDurationMs = lastSegmentDurationMs;
            long bps = spaceGuard.estimateBytesPerSecond(now, FALLBACK_WRITE_RATE_BPS);

            if (appConfig.shouldUseRelayWrite()) {
                performRelayCacheCleanupIfNeeded(bps, segDurationMs);
            }

            File videoDir = StorageHelper.getVideoDir(context);
            String videoPath = ActiveRecordingRegistry.normalizePath(videoDir);
            if (cachedVideoDirPath == null || !cachedVideoDirPath.equals(videoPath)) {
                cachedVideoDirPath = videoPath;
                cachedVideoDirBytes = -1L;
                lastVideoFullScanMs = 0L;
                estimatedAddedVideoBytes = 0L;
            }

            boolean isInternal = !appConfig.isUsingExternalSdCard() || StorageHelper.isSdCardFallback(context);
            long avail = StorageHelper.getAvailableSpace(videoDir);
            long dynamicReserve = StorageSpaceGuard.computeTargetReserveBytes(bps, segDurationMs, isInternal);
            int videoLimitGb = appConfig.getVideoStorageLimitGb();
            long videoLimitBytes = videoLimitGb > 0 ? videoLimitGb * GB_TO_BYTES : 0L;
            long cacheAge = cachedVideoDirBytes >= 0L ? Math.max(0L, now - lastVideoFullScanMs) : Long.MAX_VALUE;

            boolean needFullScan = StorageSpaceGuard.shouldRunFullVideoScan(
                    avail,
                    dynamicReserve,
                    videoLimitBytes,
                    cachedVideoDirBytes,
                    estimatedAddedVideoBytes,
                    cacheAge,
                    StorageSpaceGuard.DEFAULT_FULL_SCAN_CACHE_MAX_AGE_MS
            );

            if (!needFullScan) {
                return;
            }

            performActiveStorageLowSpaceCleanup(bps, segDurationMs, now);

            if (videoLimitBytes > 0) {
                CleanupResult videoResult = cleanupDirectory(videoDir, videoLimitBytes, "视频");
                updateVideoDirCache(videoDir, videoResult, now);
                if (videoResult.deletedCount > 0) {
                    showCleanupNotification(videoResult, "视频");
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "分段完成后存储检测异常", e);
        } finally {
            segmentCheckScheduled.set(false);
            if (!pendingSegments.isEmpty()) {
                scheduleCoalescedSegmentCheck(scheduler);
            }
        }
    }

    private void performEmergencyCleanup() {
        long now = System.currentTimeMillis();
        long segDurationMs = lastSegmentDurationMs;
        long bps = spaceGuard.estimateBytesPerSecond(now, FALLBACK_WRITE_RATE_BPS);
        AppLog.w(TAG, "收到紧急空间清理请求，当前估算写入速率=" + StorageHelper.formatSize(bps) + "/s");
        performLowSpaceCleanupIfNeeded(bps, segDurationMs, now);
        int videoLimitGb = appConfig.getVideoStorageLimitGb();
        if (videoLimitGb > 0) {
            File videoDir = StorageHelper.getVideoDir(context);
            CleanupResult videoResult = cleanupDirectory(videoDir, videoLimitGb * GB_TO_BYTES, "视频");
            updateVideoDirCache(videoDir, videoResult, now);
            if (videoResult.deletedCount > 0) {
                showCleanupNotification(videoResult, "视频");
            }
        }
    }

    private void updateVideoDirCache(File videoDir, CleanupResult result, long nowMs) {
        if (result == null || !result.scanned) {
            return;
        }
        cachedVideoDirPath = ActiveRecordingRegistry.normalizePath(videoDir);
        cachedVideoDirBytes = Math.max(0L, result.finalSize);
        lastVideoFullScanMs = nowMs;
        estimatedAddedVideoBytes = 0L;
    }
    
    /**
     * 执行清理任务
     */
    private void performCleanup() {
        AppLog.d(TAG, "开始执行存储清理检测...");
        long now = System.currentTimeMillis();
        long segDurationMs = lastSegmentDurationMs;
        long bps = spaceGuard.estimateBytesPerSecond(now, FALLBACK_WRITE_RATE_BPS);
        
        // 首先检测内部/U盘及中转缓存低空间情况（按写入速率预留余量强制清理）
        performLowSpaceCleanupIfNeeded(bps, segDurationMs, now);
        
        int videoLimitGb = appConfig.getVideoStorageLimitGb();
        int photoLimitGb = appConfig.getPhotoStorageLimitGb();
        
        // 检测并清理视频
        if (videoLimitGb > 0) {
            File videoDir = StorageHelper.getVideoDir(context);
            CleanupResult videoResult = cleanupDirectory(
                videoDir,
                videoLimitGb * GB_TO_BYTES,
                "视频"
            );
            updateVideoDirCache(videoDir, videoResult, now);
            if (videoResult.deletedCount > 0) {
                showCleanupNotification(videoResult, "视频");
            }
        }
        
        // 检测并清理图片
        if (photoLimitGb > 0) {
            CleanupResult photoResult = cleanupDirectory(
                StorageHelper.getPhotoDir(context),
                photoLimitGb * GB_TO_BYTES,
                "图片"
            );
            if (photoResult.deletedCount > 0) {
                showCleanupNotification(photoResult, "图片");
            }
        }
        
        AppLog.d(TAG, "存储清理检测完成");
    }
    
    /**
     * 低空间时强制清理：同时保护 U 盘中转使用的内部缓存与当前主录像目标存储。
     */
    private void performLowSpaceCleanupIfNeeded(long bps, long segDurationMs, long nowMs) {
        if (appConfig.shouldUseRelayWrite()) {
            performRelayCacheCleanupIfNeeded(bps, segDurationMs);
        }
        performActiveStorageLowSpaceCleanup(bps, segDurationMs, nowMs);
    }

    private void performActiveStorageLowSpaceCleanup(long bps, long segDurationMs, long nowMs) {
        boolean usingInternal = !appConfig.isUsingExternalSdCard() || StorageHelper.isSdCardFallback(context);
        File videoDir = StorageHelper.getVideoDir(context);
        long availableSpace = StorageHelper.getAvailableSpace(videoDir);
        long dynamicReserve = Math.max(
                usingInternal ? LOW_SPACE_THRESHOLD_BYTES : StorageSpaceGuard.USB_MIN_RESERVE_BYTES,
                StorageSpaceGuard.computeTargetReserveBytes(bps, segDurationMs, usingInternal)
        );
        String storageLabel = usingInternal ? "内部存储" : "U盘";

        AppLog.d(TAG, storageLabel + "可用空间: " + StorageHelper.formatSize(availableSpace)
                + ", 动态预留阈值: " + StorageHelper.formatSize(dynamicReserve)
                + " (速率=" + StorageHelper.formatSize(bps) + "/s)");

        if (availableSpace < 0 || availableSpace >= dynamicReserve) {
            return;
        }

        long extraHeadroom = Math.max(256L * StorageSpaceGuard.MB, (long) (dynamicReserve * 0.25));
        long needToFree = (dynamicReserve - availableSpace) + extraHeadroom;
        AppLog.w(TAG, storageLabel + "可用空间低于动态预留阈值，目标释放 " + StorageHelper.formatSize(needToFree) + "...");

        CleanupResult videoResult = cleanupToFreeBytes(videoDir, needToFree, storageLabel + "视频");
        updateVideoDirCache(videoDir, videoResult, nowMs);
        if (videoResult.deletedCount > 0) {
            showLowSpaceCleanupNotification(storageLabel, videoResult, "视频");
        }

        long afterVideoAvail = StorageHelper.getAvailableSpace(videoDir);
        if (afterVideoAvail >= 0 && afterVideoAvail < dynamicReserve) {
            File photoDir = StorageHelper.getPhotoDir(context);
            CleanupResult photoResult = cleanupByPercentage(photoDir, LOW_SPACE_CLEANUP_RATIO, "图片");
            if (photoResult.deletedCount > 0) {
                showLowSpaceCleanupNotification(storageLabel, photoResult, "图片");
            }
        }
    }

    private void performRelayCacheCleanupIfNeeded(long bps, long segDurationMs) {
        File cacheDir = context.getCacheDir();
        long availableInternal = StorageHelper.getAvailableSpace(cacheDir);
        long relayReserve = StorageSpaceGuard.computeRelayCacheReserveBytes(bps, segDurationMs);
        long criticalReserve = StorageSpaceGuard.computeRelayCriticalReserveBytes(bps, segDurationMs);

        if (availableInternal < 0 || availableInternal >= relayReserve) {
            return;
        }

        AppLog.w(TAG, "U盘中转内部缓存可用空间较低 (" + StorageHelper.formatSize(availableInternal)
                + " < 预留 " + StorageHelper.formatSize(relayReserve) + ")，先核验清理已落盘中转文件...");

        // 第一步：只删除已在 U 盘核验落盘的临时文件，并重新入队未落盘文件
        FileTransferManager transferManager = FileTransferManager.getInstance(context);
        transferManager.runCleanupAndRetrySync();

        availableInternal = StorageHelper.getAvailableSpace(cacheDir);
        if (availableInternal >= relayReserve) {
            return;
        }

        // 第二步：清理内部普通历史视频与图片
        long deficit = (relayReserve - availableInternal) + 256L * StorageSpaceGuard.MB;
        File internalVideoDir = StorageHelper.getVideoDir(context, false);
        CleanupResult internalVideoResult = cleanupToFreeBytes(internalVideoDir, deficit, "内部历史视频");
        if (internalVideoResult.deletedCount > 0) {
            showLowSpaceCleanupNotification("内部缓存", internalVideoResult, "视频");
        }

        availableInternal = StorageHelper.getAvailableSpace(cacheDir);
        if (availableInternal >= 0 && availableInternal < criticalReserve) {
            // 第三步：极端危险空间下淘汰最早未传输临时视频，防止彻底卡死或写爆内部存储
            long emergencyNeed = (criticalReserve - availableInternal) + 256L * StorageSpaceGuard.MB;
            FileTransferManager.EmergencyEvictionResult evicted =
                    transferManager.evictOldestUnsavedForEmergencySpace(emergencyNeed);
            if (evicted.deletedCount > 0) {
                mainHandler.post(() -> {
                    String msg = "内部中转空间严重不足，已紧急删除 " + evicted.deletedCount
                            + " 个未拷贝到U盘的旧缓存文件（" + StorageHelper.formatSize(evicted.deletedBytes) + "）";
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                });
            }
        }
    }

    private CleanupResult cleanupToFreeBytes(File directory, long bytesToFree, String typeName) {
        CleanupResult result = new CleanupResult();
        if (bytesToFree <= 0 || directory == null || !directory.exists() || !directory.isDirectory()) {
            return result;
        }
        File[] files = directory.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            result.scanned = true;
            result.finalSize = 0L;
            return result;
        }

        long totalSize = 0L;
        for (File file : files) {
            totalSize += file.length();
        }
        result.scanned = true;
        result.originalSize = totalSize;
        if (totalSize == 0L) {
            return result;
        }

        long targetSize = Math.max(0L, totalSize - bytesToFree);
        DeleteStats stats = deleteOldestUnprotectedFiles(
                files,
                totalSize,
                targetSize,
                "低空间清理" + typeName,
                ActiveRecordingRegistry.snapshot(),
                System.currentTimeMillis(),
                VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS
        );
        if (stats.skippedLockedCount > 0) {
            AppLog.d(TAG, typeName + "低空间清理保留了 " + stats.skippedLockedCount + " 个已锁定/活跃录制中文件");
        }
        result.deletedCount = stats.deletedCount;
        result.deletedSize = stats.deletedSize;
        result.finalSize = Math.max(0L, totalSize - stats.deletedSize);
        return result;
    }
    
    /**
     * 按比例清理目录（删除指定比例的已用空间）
     * @param directory 目标目录
     * @param deleteRatio 删除比例（0.0-1.0）
     * @param typeName 类型名称
     * @return 清理结果
     */
    private CleanupResult cleanupByPercentage(File directory, double deleteRatio, String typeName) {
        CleanupResult result = new CleanupResult();
        
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return result;
        }
        
        File[] files = directory.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            result.scanned = true;
            return result;
        }
        
        // 计算当前总大小
        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }
        
        result.scanned = true;
        result.originalSize = totalSize;
        
        if (totalSize == 0) {
            return result;
        }
        
        // 计算需要删除的大小（总大小的指定比例）
        long needToDelete = (long) (totalSize * deleteRatio);
        long targetSize = totalSize - needToDelete;
        
        AppLog.d(TAG, typeName + "强制清理：当前占用 " + StorageHelper.formatSize(totalSize) + 
                "，将删除 " + StorageHelper.formatSize(needToDelete) + " (20%)");
        
        // 排序并删除最旧的未锁定/非活跃录制文件直到达到目标大小
        DeleteStats stats = deleteOldestUnprotectedFiles(
                files,
                totalSize,
                targetSize,
                "强制删除旧文件",
                ActiveRecordingRegistry.snapshot(),
                System.currentTimeMillis(),
                VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS
        );
        int protectedSkipped = stats.skippedLockedCount;
        if (protectedSkipped > 0) {
            AppLog.d(TAG, typeName + "强制清理保留了 " + protectedSkipped + " 个已锁定/录制中文件");
        }
        
        result.deletedSize = stats.deletedSize;
        result.deletedCount = stats.deletedCount;
        result.finalSize = totalSize - stats.deletedSize;
        
        AppLog.d(TAG, typeName + "强制清理完成：删除 " + stats.deletedCount + " 个文件，释放 " + StorageHelper.formatSize(stats.deletedSize));
        
        return result;
    }
    
    /**
     * 显示低空间强制清理通知
     */
    private void showLowSpaceCleanupNotification(String storageLabel, CleanupResult result, String typeName) {
        mainHandler.post(() -> {
            String message = storageLabel + "空间不足，已清理" + typeName + " "
                    + result.deletedCount + "个文件（" + StorageHelper.formatSize(result.deletedSize) + "）";
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        });
    }
    
    /**
     * 清理指定目录
     * @param directory 目标目录
     * @param limitBytes 限制大小（字节）
     * @param typeName 类型名称（用于日志）
     * @return 清理结果
     */
    private CleanupResult cleanupDirectory(File directory, long limitBytes, String typeName) {
        CleanupResult result = new CleanupResult();
        
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            AppLog.w(TAG, typeName + "目录不存在: " + (directory != null ? directory.getAbsolutePath() : "null"));
            return result;
        }
        
        // 获取目录中所有文件（不筛选格式）
        File[] files = directory.listFiles(File::isFile);
        
        if (files == null || files.length == 0) {
            AppLog.d(TAG, typeName + "目录为空");
            result.scanned = true;
            return result;
        }
        
        // 计算当前总大小
        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }
        
        result.scanned = true;
        result.originalSize = totalSize;
        
        AppLog.d(TAG, typeName + "当前占用: " + StorageHelper.formatSize(totalSize) + 
                " / 限制: " + StorageHelper.formatSize(limitBytes));
        
        // 如果未超过限制，无需清理
        if (totalSize <= limitBytes) {
            AppLog.d(TAG, typeName + "未超过限制，无需清理");
            result.finalSize = totalSize;
            return result;
        }
        
        // 计算目标大小（限制的80%，即额外删除20%）
        long targetSize = (long) (limitBytes * (1 - EXTRA_DELETE_RATIO));
        long needToDelete = totalSize - targetSize;
        
        AppLog.d(TAG, typeName + "超过限制，需要删除: " + StorageHelper.formatSize(needToDelete) + 
                "，目标大小: " + StorageHelper.formatSize(targetSize));
        
        DeleteStats stats = deleteOldestUnprotectedFiles(
                files,
                totalSize,
                targetSize,
                "已删除" + typeName,
                ActiveRecordingRegistry.snapshot(),
                System.currentTimeMillis(),
                VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS
        );
        if (stats.skippedLockedCount > 0) {
            AppLog.d(TAG, typeName + "容量清理保留了 " + stats.skippedLockedCount + " 个已锁定/录制中文件");
        }
        
        result.deletedCount = stats.deletedCount;
        result.deletedSize = stats.deletedSize;
        result.finalSize = totalSize - stats.deletedSize;
        
        AppLog.d(TAG, typeName + "清理完成：删除 " + stats.deletedCount + " 个文件，释放 " + 
                StorageHelper.formatSize(stats.deletedSize) + "，剩余 " + StorageHelper.formatSize(result.finalSize));
        
        return result;
    }

    static DeleteStats deleteOldestUnprotectedFiles(File[] files,
                                                    long totalSize,
                                                    long targetSize,
                                                    String deleteActionLabel) {
        return deleteOldestUnprotectedFiles(files, totalSize, targetSize, deleteActionLabel, null, 0L, 0L);
    }

    static DeleteStats deleteOldestUnprotectedFiles(File[] files,
                                                    long totalSize,
                                                    long targetSize,
                                                    String deleteActionLabel,
                                                    @Nullable Set<String> activePendingPaths,
                                                    long nowMs,
                                                    long pendingGraceWindowMs) {
        DeleteStats stats = new DeleteStats();
        Set<String> directoryFileNames = VideoLockStore.collectFileNames(files);
        List<File> sortedFiles = new ArrayList<>(Arrays.asList(files));
        sortedFiles.sort(Comparator.comparingLong(File::lastModified));

        for (File file : sortedFiles) {
            if (totalSize - stats.deletedSize <= targetSize) {
                break;
            }
            if (VideoLockStore.shouldPreserveDuringCleanup(
                    file, directoryFileNames, activePendingPaths, nowMs, pendingGraceWindowMs)) {
                stats.skippedLockedCount++;
                continue;
            }
            long fileSize = file.length();
            String fileName = file.getName();
            if (file.delete()) {
                stats.deletedSize += fileSize;
                stats.deletedCount++;
                AppLog.d(TAG, deleteActionLabel + ": " + fileName + " (" + StorageHelper.formatSize(fileSize) + ")");
            } else {
                AppLog.w(TAG, deleteActionLabel + "失败: " + fileName);
            }
        }
        return stats;
    }

    static final class DeleteStats {
        long deletedSize = 0;
        int deletedCount = 0;
        int skippedLockedCount = 0;
    }
    
    /**
     * 显示清理通知
     */
    private void showCleanupNotification(CleanupResult result, String typeName) {
        mainHandler.post(() -> {
            String message = "已清理" + typeName + "：删除 " + result.deletedCount + " 个文件，释放 " + 
                    StorageHelper.formatSize(result.deletedSize);
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            AppLog.d(TAG, "清理通知: " + message);
        });
    }
    
    /**
     * 手动触发清理（用于测试或用户手动清理）
     */
    public void manualCleanup() {
        ScheduledExecutorService exec = scheduler;
        if (exec != null && !exec.isShutdown()) {
            try {
                exec.execute(this::performCleanup);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        new Thread(this::performCleanup, "ManualStorageCleanup").start();
    }
    
    /**
     * 获取当前视频占用大小
     * @return 占用大小（字节）
     */
    public long getVideoUsedSize() {
        return getDirectorySize(StorageHelper.getVideoDir(context));
    }
    
    /**
     * 获取当前图片占用大小
     * @return 占用大小（字节）
     */
    public long getPhotoUsedSize() {
        return getDirectorySize(StorageHelper.getPhotoDir(context));
    }
    
    /**
     * 获取目录中所有文件的总大小
     */
    private long getDirectorySize(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return 0;
        }
        
        File[] files = directory.listFiles(File::isFile);
        
        if (files == null) {
            return 0;
        }
        
        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }
        return totalSize;
    }
    
    /**
     * 清理结果
     */
    private static class CleanupResult {
        boolean scanned = false;
        long originalSize = 0;  // 清理前大小
        long deletedSize = 0;   // 删除的大小
        long finalSize = 0;     // 清理后大小
        int deletedCount = 0;   // 删除的文件数
    }
}
