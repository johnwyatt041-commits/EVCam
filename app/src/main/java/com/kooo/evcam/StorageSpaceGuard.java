package com.kooo.evcam;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 存储空间预警与写入速率预留计算器（纯 Java）：
 * <ul>
 *     <li>基于最近分段完成样本估算实际总写入速率（字节/秒）。</li>
 *     <li>按写入速率 × 预留时间窗口（至少 5 分钟、最多 15 分钟）结合存储类型下限计算动态安全余量。</li>
 *     <li>提供 O(1) 快速门控判断，仅在低空间、接近配额上限或目录缓存过期时才触发全目录 {@code listFiles()} 扫描。</li>
 * </ul>
 */
public final class StorageSpaceGuard {

    public static final long GB = 1024L * 1024L * 1024L;
    public static final long MB = 1024L * 1024L;

    public static final long INTERNAL_MIN_RESERVE_BYTES = 3L * GB;
    public static final long USB_MIN_RESERVE_BYTES = 1L * GB;
    public static final long RELAY_CACHE_MIN_RESERVE_BYTES = 2L * GB;
    public static final long RELAY_CRITICAL_MIN_RESERVE_BYTES = 512L * MB;

    public static final long MIN_RESERVE_HORIZON_SEC = 300L;
    public static final long MAX_RESERVE_HORIZON_SEC = 900L;
    public static final long MIN_WRITE_RATE_BPS = 256L * 1024L;
    public static final long MAX_WRITE_RATE_BPS = 100L * MB;

    public static final long DEFAULT_FULL_SCAN_CACHE_MAX_AGE_MS = 15L * 60L * 1000L;
    public static final int ROLLING_WINDOW_MAX_SAMPLES = 16;
    public static final long ROLLING_WINDOW_MAX_AGE_MS = 5L * 60L * 1000L;

    private static final class SegmentSample {
        final long completedAtMs;
        final long bytes;
        final long segmentDurationMs;

        SegmentSample(long completedAtMs, long bytes, long segmentDurationMs) {
            this.completedAtMs = completedAtMs;
            this.bytes = bytes;
            this.segmentDurationMs = segmentDurationMs;
        }
    }

    private final Deque<SegmentSample> samples = new ArrayDeque<>();

    public synchronized void recordSegment(long nowMs, long bytes, long segmentDurationMs) {
        if (bytes <= 0L || segmentDurationMs <= 0L) {
            return;
        }
        samples.addLast(new SegmentSample(nowMs, bytes, segmentDurationMs));
        pruneLocked(nowMs);
    }

    public synchronized long estimateBytesPerSecond(long nowMs, long fallbackBytesPerSecond) {
        pruneLocked(nowMs);
        if (samples.isEmpty()) {
            return clampRate(fallbackBytesPerSecond);
        }
        long totalBytes = 0L;
        long totalSegDurationMs = 0L;
        long oldestTs = Long.MAX_VALUE;
        long newestTs = Long.MIN_VALUE;
        int count = 0;
        for (SegmentSample sample : samples) {
            totalBytes += sample.bytes;
            totalSegDurationMs += sample.segmentDurationMs;
            if (sample.completedAtMs < oldestTs) {
                oldestTs = sample.completedAtMs;
            }
            if (sample.completedAtMs > newestTs) {
                newestTs = sample.completedAtMs;
            }
            count++;
        }
        long avgSegMs = Math.max(1000L, totalSegDurationMs / Math.max(1, count));
        long spanMs = Math.max(0L, newestTs - oldestTs);
        long effectiveDurationMs = Math.max(avgSegMs, spanMs + avgSegMs);
        long estimated = (totalBytes * 1000L) / Math.max(1000L, effectiveDurationMs);
        return clampRate(estimated);
    }

    public synchronized int getSampleCount() {
        return samples.size();
    }

    private void pruneLocked(long nowMs) {
        while (samples.size() > ROLLING_WINDOW_MAX_SAMPLES) {
            samples.removeFirst();
        }
        while (!samples.isEmpty()) {
            SegmentSample head = samples.peekFirst();
            if (head == null) {
                break;
            }
            if (nowMs - head.completedAtMs > ROLLING_WINDOW_MAX_AGE_MS) {
                samples.removeFirst();
            } else {
                break;
            }
        }
    }

    private static long clampRate(long rateBytesPerSec) {
        return Math.max(MIN_WRITE_RATE_BPS, Math.min(MAX_WRITE_RATE_BPS, rateBytesPerSec));
    }

    public static long computeReserveHorizonSeconds(long segmentDurationMs) {
        long segSec = Math.max(60L, segmentDurationMs / 1000L);
        return Math.max(MIN_RESERVE_HORIZON_SEC, Math.min(MAX_RESERVE_HORIZON_SEC, segSec * 3L));
    }

    public static long computeTargetReserveBytes(long bytesPerSecond,
                                                 long segmentDurationMs,
                                                 boolean isInternalStorage) {
        long safeRate = clampRate(bytesPerSecond);
        long rateReserve = safeRate * computeReserveHorizonSeconds(segmentDurationMs);
        long floor = isInternalStorage ? INTERNAL_MIN_RESERVE_BYTES : USB_MIN_RESERVE_BYTES;
        return Math.max(floor, rateReserve);
    }

    public static long computeRelayCacheReserveBytes(long bytesPerSecond, long segmentDurationMs) {
        long safeRate = clampRate(bytesPerSecond);
        long rateReserve = safeRate * computeReserveHorizonSeconds(segmentDurationMs);
        return Math.max(RELAY_CACHE_MIN_RESERVE_BYTES, rateReserve);
    }

    public static long computeRelayCriticalReserveBytes(long bytesPerSecond, long segmentDurationMs) {
        long safeRate = clampRate(bytesPerSecond);
        long segSec = Math.max(60L, segmentDurationMs / 1000L);
        long horizon = Math.max(120L, Math.min(360L, segSec * 2L));
        return Math.max(RELAY_CRITICAL_MIN_RESERVE_BYTES, safeRate * horizon);
    }

    public static boolean shouldRunFullVideoScan(long availableSpaceBytes,
                                                 long dynamicReserveBytes,
                                                 long videoLimitBytes,
                                                 long cachedVideoDirBytes,
                                                 long estimatedAddedBytes,
                                                 long elapsedSinceLastFullScanMs,
                                                 long maxCacheAgeMs) {
        if (availableSpaceBytes >= 0L && availableSpaceBytes < dynamicReserveBytes) {
            return true;
        }
        if (videoLimitBytes > 0L) {
            if (cachedVideoDirBytes < 0L || elapsedSinceLastFullScanMs >= maxCacheAgeMs) {
                return true;
            }
            if (cachedVideoDirBytes + Math.max(0L, estimatedAddedBytes) >= videoLimitBytes) {
                return true;
            }
        }
        return false;
    }
}
