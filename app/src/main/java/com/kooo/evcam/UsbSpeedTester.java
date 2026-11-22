package com.kooo.evcam;

import android.content.Context;
import android.os.Environment;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.camera.ImageAdjustmentConfig;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.RecordingExperiment;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * U盘写入速度检测与录制需求评估工具。
 * 通过强制落盘同步（FileChannel.force(true)）跳过系统页缓存，测量 U 盘真实持续顺序写入速率，
 * 并结合当前启用的录制路数、编码分辨率、帧率与码率等级判定是否满足直接写入或中转写入要求。
 */
public final class UsbSpeedTester {
    private static final String TAG = "UsbSpeedTester";

    static final double BYTES_PER_MB = 1024.0 * 1024.0;
    static final long DEFAULT_TARGET_TEST_BYTES = 64L * 1024L * 1024L;
    static final long DEFAULT_MIN_TEST_BYTES = 16L * 1024L * 1024L;
    static final long DEFAULT_CHUNK_BYTES = 2L * 1024L * 1024L;
    static final long DEFAULT_FREE_HEADROOM_BYTES = 32L * 1024L * 1024L;
    static final int WRITE_BUFFER_BYTES = 256 * 1024;
    static final long SOFT_TIME_CAP_MS = 10_000L;
    static final long SOFT_TIME_MIN_BYTES = 8L * 1024L * 1024L;
    static final long HARD_TIME_CAP_MS = 15_000L;

    static final double MIN_RELAY_FLOOR_MBPS = 4.0;
    static final double MIN_DIRECT_FLOOR_MBPS = 10.0;
    private static final int MAX_ENCODE_SIZE = 4096;
    private static final String TEMP_FILE_PREFIX = ".evcam_speed_test_";
    private static final String TEMP_FILE_SUFFIX = ".tmp";
    private static final String[] CAMERA_ORDER = {"front", "back", "left", "right"};
    private static final AtomicLong TEST_SEQ = new AtomicLong();

    private UsbSpeedTester() {
    }

    public enum Status {
        MEETS_DIRECT,
        RELAY_ONLY,
        INSUFFICIENT,
        UNAVAILABLE,
        CANCELLED,
        ERROR
    }

    public static final class RecordingRequirement {
        public final int streamCount;
        public final long totalBitrateBps;
        public final double nominalMBps;
        public final double minRelayMBps;
        public final double recommendedDirectMBps;
        public final boolean compositeMode;
        public final int fps;
        public final String resolutionLabel;
        public final String bitrateLevelLabel;
        public final String summary;

        public RecordingRequirement(
                int streamCount,
                long totalBitrateBps,
                double nominalMBps,
                double minRelayMBps,
                double recommendedDirectMBps,
                boolean compositeMode,
                int fps,
                @NonNull String resolutionLabel,
                @NonNull String bitrateLevelLabel,
                @NonNull String summary) {
            this.streamCount = streamCount;
            this.totalBitrateBps = totalBitrateBps;
            this.nominalMBps = nominalMBps;
            this.minRelayMBps = minRelayMBps;
            this.recommendedDirectMBps = recommendedDirectMBps;
            this.compositeMode = compositeMode;
            this.fps = fps;
            this.resolutionLabel = resolutionLabel;
            this.bitrateLevelLabel = bitrateLevelLabel;
            this.summary = summary;
        }
    }

    public static final class SpeedTestResult {
        public final Status status;
        public final double avgWriteMBps;
        public final double minChunkMBps;
        public final double maxChunkMBps;
        public final long bytesWritten;
        public final long elapsedMs;
        public final String targetPath;
        public final RecordingRequirement requirement;
        public final String summary;
        public final String detail;

        public SpeedTestResult(
                @NonNull Status status,
                double avgWriteMBps,
                double minChunkMBps,
                double maxChunkMBps,
                long bytesWritten,
                long elapsedMs,
                @Nullable String targetPath,
                @NonNull RecordingRequirement requirement,
                @NonNull String summary,
                @NonNull String detail) {
            this.status = status;
            this.avgWriteMBps = avgWriteMBps;
            this.minChunkMBps = minChunkMBps;
            this.maxChunkMBps = maxChunkMBps;
            this.bytesWritten = bytesWritten;
            this.elapsedMs = elapsedMs;
            this.targetPath = targetPath == null ? "" : targetPath;
            this.requirement = requirement;
            this.summary = summary;
            this.detail = detail;
        }
    }

    public interface ProgressListener {
        void onProgress(int percent, double currentMBps, long bytesWritten, long targetBytes);
    }

    public static final class TestHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    /**
     * 基于当前应用配置与运行中的相机分辨率估算写入速率需求。
     */
    @NonNull
    public static RecordingRequirement estimateRequirement(
            @NonNull AppConfig config,
            @Nullable MultiCameraManager cameraManager) {
        Set<String> enabledSet = config.getEnabledRecordingCameras();
        List<String> keys = new ArrayList<>();
        for (String cam : CAMERA_ORDER) {
            if (enabledSet.contains(cam)) {
                keys.add(cam);
            }
        }
        if (keys.isEmpty()) {
            keys.add("front");
        }

        int[] fallbackRes = resolveFallbackResolution(config.getTargetResolution());
        Map<String, int[]> cameraSizes = new LinkedHashMap<>();
        for (String key : keys) {
            Size liveSize = cameraManager != null ? cameraManager.getCameraPreviewSize(key) : null;
            if (liveSize != null && liveSize.getWidth() > 0 && liveSize.getHeight() > 0) {
                cameraSizes.put(key, new int[]{liveSize.getWidth(), liveSize.getHeight()});
            } else {
                cameraSizes.put(key, new int[]{fallbackRes[0], fallbackRes[1]});
            }
        }

        int fps = config.getActualFrameRate(25);
        boolean composite = RecordingExperiment.compositeDaily(keys);
        String bitrateLevelLabel = AppConfig.getBitrateLevelDisplayName(config.getBitrateLevel());

        if (composite) {
            int maxW = 0;
            int maxH = 0;
            for (int[] size : cameraSizes.values()) {
                maxW = Math.max(maxW, size[0]);
                maxH = Math.max(maxH, size[1]);
            }
            boolean adjustEnabled = config.isVideoImageAdjustEnabled();
            int qualityPercent = config.getVideoImageQualityPercent();
            int scaledW = ImageAdjustmentConfig.scaleDimensionEven(maxW, qualityPercent, adjustEnabled);
            int scaledH = ImageAdjustmentConfig.scaleDimensionEven(maxH, qualityPercent, adjustEnabled);
            String carModel = config.getCarModel();
            boolean e5 = AppConfig.CAR_MODEL_GALAXY_E5.equals(carModel)
                    || AppConfig.CAR_MODEL_E5_MULTI.equals(carModel);
            int cellW = Math.max(16, (scaledW / 16) * 16);
            int compH = Math.max(16, (scaledH / 2) * 2);
            int compW = e5 ? cellW : cellW * keys.size();
            long bitrate = config.getActualBitrate(compW, compH, fps);
            return buildRequirement(
                    1,
                    bitrate,
                    true,
                    fps,
                    compW + "×" + compH,
                    bitrateLevelLabel
            );
        }

        long totalBitrate = 0L;
        int firstW = fallbackRes[0];
        int firstH = fallbackRes[1];
        boolean firstRecorded = false;
        boolean sameResolution = true;

        for (String key : keys) {
            int[] base = cameraSizes.get(key);
            ImageAdjustmentConfig adj = config.getImageAdjustmentConfig(key);
            int encodeW = clampEncodeDim(adj.scaleWidth(base[0]));
            int encodeH = clampEncodeDim(adj.scaleHeight(base[1]));
            if (encodeW > MAX_ENCODE_SIZE || encodeH > MAX_ENCODE_SIZE) {
                float scale = Math.min((float) MAX_ENCODE_SIZE / encodeW, (float) MAX_ENCODE_SIZE / encodeH);
                encodeW = clampEncodeDim((int) (encodeW * scale));
                encodeH = clampEncodeDim((int) (encodeH * scale));
            }
            if (!firstRecorded) {
                firstW = encodeW;
                firstH = encodeH;
                firstRecorded = true;
            } else if (encodeW != firstW || encodeH != firstH) {
                sameResolution = false;
            }
            totalBitrate += config.getActualBitrate(encodeW, encodeH, fps);
        }

        String resLabel = sameResolution ? (firstW + "×" + firstH) : "多分辨率";
        return buildRequirement(
                keys.size(),
                totalBitrate,
                false,
                fps,
                resLabel,
                bitrateLevelLabel
        );
    }

    /**
     * 根据总编码码率与流参数计算写入速率要求（纯 Java，便于单元测试）。
     */
    @NonNull
    public static RecordingRequirement buildRequirement(
            int streamCount,
            long totalBitrateBps,
            boolean compositeMode,
            int fps,
            @NonNull String resolutionLabel,
            @NonNull String bitrateLevelLabel) {
        int safeStreams = Math.max(1, streamCount);
        long safeBitrate = Math.max(500_000L, totalBitrateBps);
        double nominalMBps = (safeBitrate / 8.0) / BYTES_PER_MB;
        double minRelayMBps = Math.max(MIN_RELAY_FLOOR_MBPS, nominalMBps * 2.0);
        double recommendedDirectMBps = Math.max(MIN_DIRECT_FLOOR_MBPS, nominalMBps * 3.0);

        String modeTag = compositeMode ? "合成单流" : (safeStreams + "路独立");
        String summary = String.format(
                Locale.US,
                "%s · %s @ %dfps · %s码率（总码率 %.1f Mbps ≈ %.2f MB/s）",
                modeTag,
                resolutionLabel,
                fps,
                bitrateLevelLabel,
                safeBitrate / 1_000_000.0,
                nominalMBps
        );
        return new RecordingRequirement(
                safeStreams,
                safeBitrate,
                nominalMBps,
                minRelayMBps,
                recommendedDirectMBps,
                compositeMode,
                fps,
                resolutionLabel,
                bitrateLevelLabel,
                summary
        );
    }

    /**
     * 对已检测的 U 盘执行真实落盘写入测速。
     */
    @NonNull
    public static SpeedTestResult runUsbSpeedTest(
            @NonNull Context context,
            @NonNull AppConfig config,
            @Nullable MultiCameraManager cameraManager,
            @Nullable ProgressListener progressListener,
            @NonNull TestHandle handle) {
        RecordingRequirement requirement = estimateRequirement(config, cameraManager);
        boolean recordingActive = cameraManager != null && cameraManager.isRecording();

        // 先清缓存重新检测，确保能识别刚插入或更换的 U 盘
        StorageHelper.clearCache();
        File sdRoot = StorageHelper.getExternalSdCardRoot(context);
        if (sdRoot == null || !sdRoot.exists() || !sdRoot.isDirectory()) {
            return new SpeedTestResult(
                    Status.UNAVAILABLE,
                    0, 0, 0, 0, 0,
                    "",
                    requirement,
                    "未检测到可用 U 盘",
                    "✗ 未检测到可用 U 盘\n请确认 U 盘已插牢并等待系统挂载完成，可点击上方「检测存储设备」核对挂载路径。"
            );
        }

        File dcimDir = new File(sdRoot, Environment.DIRECTORY_DCIM);
        if (!dcimDir.exists() && !dcimDir.mkdirs() && !dcimDir.exists()) {
            boolean missingAllFiles = RecordingStorageAccess.isMissingAllFilesAccessOnAndroid11Plus(context);
            String detail = missingAllFiles
                    ? "✗ 无法在 U 盘创建 DCIM 目录：缺少「所有文件访问权限」，请前往「13. 系统与权限」授权后重试。"
                    : "✗ 无法在 U 盘创建测试目录：" + dcimDir.getAbsolutePath() + "\n请检查 U 盘是否只读或未授予所有文件访问权限。";
            return new SpeedTestResult(
                    Status.ERROR,
                    0, 0, 0, 0, 0,
                    dcimDir.getAbsolutePath(),
                    requirement,
                    "U 盘不可写",
                    detail
            );
        }

        long availableBytes = StorageHelper.getAvailableSpace(dcimDir);
        if (availableBytes < 0) {
            availableBytes = dcimDir.getUsableSpace();
        }
        boolean missingAllFiles = RecordingStorageAccess.isMissingAllFilesAccessOnAndroid11Plus(context);
        SpeedTestResult result = runDirectorySpeedTest(
                dcimDir,
                availableBytes,
                requirement,
                config.isRelayWriteEnabled(),
                recordingActive,
                DEFAULT_TARGET_TEST_BYTES,
                DEFAULT_MIN_TEST_BYTES,
                DEFAULT_CHUNK_BYTES,
                DEFAULT_FREE_HEADROOM_BYTES,
                progressListener,
                handle
        );
        if (result.status == Status.ERROR && missingAllFiles && !RecordingStorageAccess.isReadOnlyMessage(result.detail)
                && !RecordingStorageAccess.isOutOfSpaceMessage(result.detail)) {
            return new SpeedTestResult(
                    Status.ERROR,
                    0, 0, 0, 0, 0,
                    dcimDir.getAbsolutePath(),
                    requirement,
                    "缺少文件访问权限",
                    "✗ 无法写入 U 盘：缺少「所有文件访问权限」，请前往「13. 系统与权限」授权后重试。\n目标路径：" + dcimDir.getAbsolutePath()
            );
        }
        return result;
    }

    /**
     * 在指定目录执行带 fsync 落盘同步的写入测速（支持在 JVM 单元测试中直接对临时目录调用）。
     */
    @NonNull
    static SpeedTestResult runDirectorySpeedTest(
            @NonNull File targetDir,
            long availableBytes,
            @NonNull RecordingRequirement requirement,
            boolean relayWriteEnabled,
            boolean recordingActive,
            long targetTestBytes,
            long minTestBytes,
            long chunkBytes,
            long freeHeadroomBytes,
            @Nullable ProgressListener progressListener,
            @NonNull TestHandle handle) {
        String targetPath = targetDir.getAbsolutePath();
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            return new SpeedTestResult(
                    Status.ERROR, 0, 0, 0, 0, 0, targetPath, requirement,
                    "测试目录不存在", "✗ 测试目录不存在：" + targetPath
            );
        }

        cleanupStaleTempFiles(targetDir);

        long safeChunkBytes = Math.max(WRITE_BUFFER_BYTES, chunkBytes);
        long effectiveAvail = availableBytes >= 0 ? availableBytes : targetDir.getUsableSpace();
        if (effectiveAvail >= 0 && effectiveAvail < minTestBytes + freeHeadroomBytes) {
            long minRequired = minTestBytes + freeHeadroomBytes;
            return new SpeedTestResult(
                    Status.ERROR, 0, 0, 0, 0, 0, targetPath, requirement,
                    "U 盘可用空间不足",
                    String.format(Locale.US, "✗ U 盘可用空间不足（当前可用 %s，安全测速至少需要 %s）",
                            StorageHelper.formatSize(effectiveAvail),
                            StorageHelper.formatSize(minRequired))
            );
        }

        long plannedBytes = targetTestBytes;
        if (effectiveAvail >= 0) {
            plannedBytes = Math.min(plannedBytes, effectiveAvail - freeHeadroomBytes);
        }
        plannedBytes = Math.max(minTestBytes, (plannedBytes / safeChunkBytes) * safeChunkBytes);

        File tempFile = new File(targetDir, TEMP_FILE_PREFIX + System.currentTimeMillis() + "_" + TEST_SEQ.incrementAndGet() + TEMP_FILE_SUFFIX);
        byte[] pattern = buildNonZeroPattern(WRITE_BUFFER_BYTES);

        long totalBytesWritten = 0L;
        long totalStartNs = System.nanoTime();
        double minChunkMBps = Double.MAX_VALUE;
        double maxChunkMBps = 0.0;

        try {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw");
                 FileChannel channel = raf.getChannel()) {
                int totalChunks = (int) Math.max(1L, plannedBytes / safeChunkBytes);
                for (int chunkIdx = 0; chunkIdx < totalChunks; chunkIdx++) {
                    if (handle.isCancelled()) {
                        return new SpeedTestResult(
                                Status.CANCELLED, 0, 0, 0, totalBytesWritten,
                                elapsedMsSince(totalStartNs), targetPath, requirement,
                                "测速已取消", "测速已取消"
                        );
                    }

                    long chunkStartNs = System.nanoTime();
                    long remainingInChunk = safeChunkBytes;
                    while (remainingInChunk > 0L) {
                        if (handle.isCancelled()) {
                            return new SpeedTestResult(
                                    Status.CANCELLED, 0, 0, 0, totalBytesWritten,
                                    elapsedMsSince(totalStartNs), targetPath, requirement,
                                    "测速已取消", "测速已取消"
                            );
                        }
                        int toWrite = (int) Math.min(pattern.length, remainingInChunk);
                        ByteBuffer buf = ByteBuffer.wrap(pattern, 0, toWrite);
                        while (buf.hasRemaining()) {
                            channel.write(buf);
                        }
                        remainingInChunk -= toWrite;
                    }
                    channel.force(true);

                    long nowNs = System.nanoTime();
                    long chunkNs = Math.max(1L, nowNs - chunkStartNs);
                    double chunkMBps = (safeChunkBytes / BYTES_PER_MB) / (chunkNs / 1_000_000_000.0);
                    if (chunkMBps < minChunkMBps) minChunkMBps = chunkMBps;
                    if (chunkMBps > maxChunkMBps) maxChunkMBps = chunkMBps;
                    totalBytesWritten += safeChunkBytes;

                    long elapsedNs = Math.max(1L, nowNs - totalStartNs);
                    double runningMBps = (totalBytesWritten / BYTES_PER_MB) / (elapsedNs / 1_000_000_000.0);
                    int percent = (int) Math.min(99L, (totalBytesWritten * 100L) / plannedBytes);
                    if (progressListener != null) {
                        progressListener.onProgress(percent, runningMBps, totalBytesWritten, plannedBytes);
                    }

                    long elapsedMs = elapsedNs / 1_000_000L;
                    if ((elapsedMs >= SOFT_TIME_CAP_MS && totalBytesWritten >= SOFT_TIME_MIN_BYTES)
                            || elapsedMs >= HARD_TIME_CAP_MS) {
                        AppLog.i(TAG, "USB speed test reached time cap: elapsedMs=" + elapsedMs
                                + ", written=" + totalBytesWritten);
                        break;
                    }
                }
            }

            if (handle.isCancelled()) {
                return new SpeedTestResult(
                        Status.CANCELLED, 0, 0, 0, totalBytesWritten,
                        elapsedMsSince(totalStartNs), targetPath, requirement,
                        "测速已取消", "测速已取消"
                );
            }

            long totalElapsedNs = Math.max(1L, System.nanoTime() - totalStartNs);
            long totalElapsedMs = Math.max(1L, totalElapsedNs / 1_000_000L);
            double avgWriteMBps = (totalBytesWritten / BYTES_PER_MB) / (totalElapsedNs / 1_000_000_000.0);
            if (minChunkMBps == Double.MAX_VALUE) {
                minChunkMBps = avgWriteMBps;
            }
            if (progressListener != null) {
                progressListener.onProgress(100, avgWriteMBps, totalBytesWritten, totalBytesWritten);
            }

            return evaluateWriteResult(
                    avgWriteMBps,
                    minChunkMBps,
                    maxChunkMBps,
                    totalBytesWritten,
                    totalElapsedMs,
                    targetPath,
                    requirement,
                    relayWriteEnabled,
                    recordingActive
            );
        } catch (IOException | SecurityException e) {
            String detail = RecordingStorageAccess.describeThrowable(e);
            String userMsg;
            if (RecordingStorageAccess.isReadOnlyError(e)) {
                userMsg = "✗ U 盘处于只读状态，无法写入测试文件。\n" + detail;
            } else if (RecordingStorageAccess.isOutOfSpaceError(e)) {
                userMsg = "✗ U 盘空间不足，测速中断。\n" + detail;
            } else if (RecordingStorageAccess.isPermissionDeniedError(e)) {
                userMsg = "✗ 没有权限写入 U 盘，请检查「所有文件访问权限」。\n" + detail;
            } else {
                userMsg = "✗ 测速写入异常：" + (detail.isEmpty() ? e.getClass().getSimpleName() : detail);
            }
            return new SpeedTestResult(
                    Status.ERROR, 0, 0, 0, totalBytesWritten,
                    elapsedMsSince(totalStartNs), targetPath, requirement,
                    "测速失败", userMsg
            );
        } finally {
            if (tempFile.exists() && !tempFile.delete()) {
                AppLog.w(TAG, "Failed to delete USB speed test temp file: " + tempFile.getAbsolutePath());
            }
        }
    }

    /**
     * 结合实测持续写速、分块最低速率及当前录制需求输出结论（纯 Java，便于单元测试）。
     */
    @NonNull
    public static SpeedTestResult evaluateWriteResult(
            double avgWriteMBps,
            double minChunkMBps,
            double maxChunkMBps,
            long bytesWritten,
            long elapsedMs,
            @Nullable String targetPath,
            @NonNull RecordingRequirement requirement,
            boolean relayWriteEnabled,
            boolean recordingActive) {
        boolean severeDrop = minChunkMBps > 0 && minChunkMBps < (requirement.minRelayMBps * 0.5);
        Status status;
        String summary;
        String advice;

        if (avgWriteMBps >= requirement.recommendedDirectMBps && !severeDrop) {
            status = Status.MEETS_DIRECT;
            summary = String.format(Locale.US, "测速达标：%.1f MB/s（支持直写/中转）", avgWriteMBps);
            advice = "结论：✓ 符合录制要求，可直接写入 U 盘或配合「中转写入」使用。";
        } else if (avgWriteMBps >= requirement.minRelayMBps) {
            status = Status.RELAY_ONLY;
            if (avgWriteMBps >= requirement.recommendedDirectMBps && severeDrop) {
                summary = String.format(Locale.US, "波动较大：均速 %.1f MB/s，最低 %.1f MB/s（建议中转）", avgWriteMBps, minChunkMBps);
                advice = relayWriteEnabled
                        ? "结论：⚠ 平均写速较高但存在掉速波动，建议保持「中转写入」开启以避免直写卡顿。"
                        : "结论：⚠ 平均写速较高但存在掉速波动，强烈建议开启「中转写入模式」避免直写丢帧。";
            } else {
                summary = String.format(Locale.US, "仅建议中转：%.1f MB/s（直写需 ≥ %.1f MB/s）",
                        avgWriteMBps, requirement.recommendedDirectMBps);
                advice = relayWriteEnabled
                        ? "结论：⚠ 满足「中转写入」最低要求，但低于多路直写安全线；当前已开启中转写入，可正常使用。"
                        : "结论：⚠ 满足「中转写入」最低要求，但直接写盘可能卡顿/丢帧；建议开启上方「中转写入模式」。";
            }
        } else {
            status = Status.INSUFFICIENT;
            summary = String.format(Locale.US, "速度不达标：%.1f MB/s（最低需 %.1f MB/s）",
                    avgWriteMBps, requirement.minRelayMBps);
            advice = "结论：✗ 持续写入速度过慢，可能导致录像丢帧、分段失败或中转积压；建议更换高速 USB 3.0 / U3 U盘，或降低分辨率/码率、改用内部存储。";
        }

        double writtenMb = bytesWritten / BYTES_PER_MB;
        double elapsedSec = Math.max(0.01, elapsedMs / 1000.0);
        StringBuilder detail = new StringBuilder();
        detail.append(advice).append("\n");
        detail.append(String.format(
                Locale.US,
                "实测落盘写速：平均 %.1f MB/s（最低块 %.1f / 峰值 %.1f MB/s）\n",
                avgWriteMBps,
                minChunkMBps,
                Math.max(avgWriteMBps, maxChunkMBps)
        ));
        detail.append(String.format(
                Locale.US,
                "测试样本：%.0f MB，耗时 %.1f 秒（已强制 fsync 落盘）\n",
                writtenMb,
                elapsedSec
        ));
        detail.append(String.format(
                Locale.US,
                "当前录制要求：直写建议 ≥ %.1f MB/s，中转最低 ≥ %.1f MB/s\n配置参考：%s",
                requirement.recommendedDirectMBps,
                requirement.minRelayMBps,
                requirement.summary
        ));
        if (targetPath != null && !targetPath.isEmpty()) {
            detail.append("\n测试位置：").append(targetPath);
        }
        if (recordingActive) {
            detail.append("\n提示：测速期间检测到后台正在录像，实际空闲写入速度可能略高于本结果。");
        }

        return new SpeedTestResult(
                status,
                avgWriteMBps,
                minChunkMBps,
                maxChunkMBps,
                bytesWritten,
                elapsedMs,
                targetPath,
                requirement,
                summary,
                detail.toString()
        );
    }

    private static int[] resolveFallbackResolution(@Nullable String targetResolution) {
        int[] parsed = AppConfig.parseResolution(targetResolution);
        if (parsed != null && parsed.length == 2 && parsed[0] > 0 && parsed[1] > 0) {
            return parsed;
        }
        return new int[]{1280, 800};
    }

    private static int clampEncodeDim(int dim) {
        int even = (dim / 2) * 2;
        return Math.max(16, Math.min(MAX_ENCODE_SIZE, even));
    }

    private static long elapsedMsSince(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    private static byte[] buildNonZeroPattern(int size) {
        byte[] data = new byte[size];
        int state = 0x5A3C96E1;
        for (int i = 0; i < size; i++) {
            state = (state * 1664525) + 1013904223;
            data[i] = (byte) (state >>> 24);
        }
        return data;
    }

    private static void cleanupStaleTempFiles(@NonNull File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file != null && file.isFile()) {
                String name = file.getName();
                if (name.startsWith(TEMP_FILE_PREFIX) && name.endsWith(TEMP_FILE_SUFFIX)) {
                    if (!file.delete()) {
                        AppLog.w(TAG, "Failed to cleanup stale USB speed test file: " + file.getAbsolutePath());
                    }
                }
            }
        }
    }
}
