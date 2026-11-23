package com.kooo.evcam.playback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 纯 Java 缩略图加载守卫：
 * <ul>
 *     <li>从单组真实已录制视频中选取唯一代表视角（绝不显示未录视角）。</li>
 *     <li>对损坏/解码失败文件记录有界负缓存（首次冷却 TTL、二次会话内永久跳过，文件大小或修改时间变更自动失效）。</li>
 *     <li>限制并发视频首帧解码数量、支持惯性快滑暂停/恢复队列及短时连败退避冷却，防止 Glide 线程被坏文件卡死。</li>
 * </ul>
 */
public final class PlaybackThumbnailGuard {

    public static final int DEFAULT_MAX_CONCURRENT_VIDEO_LOADS = 2;
    public static final long DEFAULT_NEGATIVE_TTL_MS = 10L * 60L * 1000L;
    public static final int PERMANENT_FAILURE_THRESHOLD = 2;
    public static final int DEFAULT_BURST_FAILURE_THRESHOLD = 3;
    public static final long DEFAULT_BURST_WINDOW_MS = 5_000L;
    public static final long DEFAULT_BURST_COOLDOWN_MS = 2_000L;
    public static final long DEFAULT_STALE_IN_FLIGHT_MS = 10_000L;
    public static final int MAX_CACHE_ENTRIES = 512;
    public static final long MIN_PLAYABLE_THUMB_BYTES = 1024L;

    public interface LoadTask {
        void startLoad(long token);
    }

    public interface CooldownScheduler {
        void scheduleDrain(long delayMs);
    }

    private static final class NegativeEntry {
        int failureCount;
        long expiresAtMs;
        boolean sessionPermanent;
    }

    private static final class PendingEntry {
        final long token;
        final String fileKey;
        final LoadTask task;

        PendingEntry(long token, @NonNull String fileKey, @NonNull LoadTask task) {
            this.token = token;
            this.fileKey = fileKey;
            this.task = task;
        }
    }

    /**
     * 线程安全的有界 LRU 负缓存，跨回放页重入复用而不致内存泄漏。
     */
    public static final class NegativeCache {
        private final int maxEntries;
        private final LinkedHashMap<String, NegativeEntry> map;

        public NegativeCache(int maxEntries) {
            this.maxEntries = Math.max(16, maxEntries);
            this.map = new LinkedHashMap<String, NegativeEntry>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, NegativeEntry> eldest) {
                    return size() > NegativeCache.this.maxEntries;
                }
            };
        }

        public synchronized boolean isBlocked(@NonNull String fileKey, long nowMs) {
            NegativeEntry entry = map.get(fileKey);
            if (entry == null) {
                return false;
            }
            if (entry.sessionPermanent) {
                return true;
            }
            return nowMs < entry.expiresAtMs;
        }

        public synchronized void recordFailure(@NonNull String fileKey, long nowMs, long ttlMs) {
            NegativeEntry entry = map.get(fileKey);
            if (entry == null) {
                entry = new NegativeEntry();
                map.put(fileKey, entry);
            }
            entry.failureCount++;
            if (entry.failureCount >= PERMANENT_FAILURE_THRESHOLD) {
                entry.sessionPermanent = true;
                entry.expiresAtMs = Long.MAX_VALUE;
            } else {
                entry.expiresAtMs = nowMs + Math.max(0L, ttlMs);
            }
        }

        public synchronized void clearKey(@NonNull String fileKey) {
            map.remove(fileKey);
        }

        public synchronized int size() {
            return map.size();
        }
    }

    private static final NegativeCache SHARED_NEGATIVE_CACHE = new NegativeCache(MAX_CACHE_ENTRIES);

    private final NegativeCache negativeCache;
    private final LinkedHashMap<String, Boolean> positiveKeys;
    private final Map<Long, Long> inFlightStartMs = new HashMap<>();
    private final LinkedHashMap<Long, PendingEntry> pendingQueue = new LinkedHashMap<>();
    private final Deque<Long> recentFailureTimestamps = new ArrayDeque<>();
    private final AtomicLong nextToken = new AtomicLong(0L);

    private final int maxConcurrentLoads;
    private final long negativeTtlMs;
    private final int burstFailureThreshold;
    private final long burstWindowMs;
    private final long burstCooldownMs;
    private final long staleInFlightMs;
    @Nullable
    private final CooldownScheduler cooldownScheduler;

    private boolean scrollPaused = false;
    private long burstCooldownUntilMs = 0L;
    private boolean isDraining = false;
    private boolean drainRequested = false;

    @NonNull
    public static PlaybackThumbnailGuard createShared(@Nullable CooldownScheduler cooldownScheduler) {
        return new PlaybackThumbnailGuard(
                SHARED_NEGATIVE_CACHE,
                DEFAULT_MAX_CONCURRENT_VIDEO_LOADS,
                DEFAULT_NEGATIVE_TTL_MS,
                DEFAULT_BURST_FAILURE_THRESHOLD,
                DEFAULT_BURST_WINDOW_MS,
                DEFAULT_BURST_COOLDOWN_MS,
                DEFAULT_STALE_IN_FLIGHT_MS,
                cooldownScheduler);
    }

    @VisibleForTesting
    PlaybackThumbnailGuard(@NonNull NegativeCache negativeCache,
                           int maxConcurrentLoads,
                           long negativeTtlMs,
                           int burstFailureThreshold,
                           long burstWindowMs,
                           long burstCooldownMs,
                           long staleInFlightMs,
                           @Nullable CooldownScheduler cooldownScheduler) {
        this.negativeCache = negativeCache;
        this.maxConcurrentLoads = Math.max(1, maxConcurrentLoads);
        this.negativeTtlMs = Math.max(0L, negativeTtlMs);
        this.burstFailureThreshold = Math.max(1, burstFailureThreshold);
        this.burstWindowMs = Math.max(1L, burstWindowMs);
        this.burstCooldownMs = Math.max(0L, burstCooldownMs);
        this.staleInFlightMs = Math.max(1L, staleInFlightMs);
        this.cooldownScheduler = cooldownScheduler;
        this.positiveKeys = new LinkedHashMap<String, Boolean>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_CACHE_ENTRIES;
            }
        };
    }

    @NonNull
    public static String buildFileKey(@NonNull File file) {
        return buildFileKey(file.getAbsolutePath(), file.length(), file.lastModified());
    }

    @NonNull
    public static String buildFileKey(@NonNull String path, long length, long lastModified) {
        return path + "|" + length + "|" + lastModified;
    }

    /**
     * 仅从本组实际已录制的视角中选择 1 个代表缩略图；跳过过小坏文件或处于负缓存冷却期的文件。
     */
    @Nullable
    public VideoGroup.RepresentativeMedia selectRepresentativeMedia(@Nullable VideoGroup group, long nowMs) {
        if (group == null) {
            return null;
        }
        for (VideoGroup.RepresentativeMedia candidate : group.getRecordedVideosInPriorityOrder()) {
            long len = candidate.file.length();
            long mtime = candidate.file.lastModified();
            String key = buildFileKey(candidate.file.getAbsolutePath(), len, mtime);
            if (!candidate.file.isFile() || len < MIN_PLAYABLE_THUMB_BYTES) {
                negativeCache.recordFailure(key, nowMs, negativeTtlMs);
                continue;
            }
            if (negativeCache.isBlocked(key, nowMs)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * 解析缩略图角标文字：优先显示实际选中的代表视角，若全部已录视角均损坏不可解码，
     * 仍只回退到已录制的首个真实视角标签，绝不展示未录制视角名称。
     */
    @NonNull
    public static String resolveDisplayCameraLabel(@Nullable VideoGroup group,
                                                   @Nullable VideoGroup.RepresentativeMedia chosen) {
        if (chosen != null) {
            return VideoGroup.getPositionDisplayLabel(chosen.position);
        }
        if (group == null) {
            return "";
        }
        java.util.List<VideoGroup.RepresentativeMedia> recorded = group.getRecordedVideosInPriorityOrder();
        if (recorded.isEmpty()) {
            return "";
        }
        return VideoGroup.getPositionDisplayLabel(recorded.get(0).position);
    }

    public boolean isNegativeCached(@NonNull String fileKey, long nowMs) {
        return negativeCache.isBlocked(fileKey, nowMs);
    }

    public synchronized long submit(@NonNull String fileKey, long nowMs, @NonNull LoadTask task) {
        if (negativeCache.isBlocked(fileKey, nowMs)) {
            return 0L;
        }
        long token = nextToken.incrementAndGet();
        pruneStaleInFlightLocked(nowMs);

        boolean knownPositive = positiveKeys.containsKey(fileKey);
        if (knownPositive || (!scrollPaused && canStartSlowLoadLocked(nowMs))) {
            inFlightStartMs.put(token, nowMs);
            startTaskOutsideLockSafe(token, nowMs, task);
        } else {
            pendingQueue.put(token, new PendingEntry(token, fileKey, task));
            if (!scrollPaused && inBurstCooldownLocked(nowMs)) {
                scheduleCooldownDrainLocked(nowMs);
            }
        }
        return token;
    }

    public synchronized void onLoadSucceeded(long token, @NonNull String fileKey, long nowMs) {
        pendingQueue.remove(token);
        inFlightStartMs.remove(token);
        negativeCache.clearKey(fileKey);
        positiveKeys.put(fileKey, Boolean.TRUE);
        drainPendingLocked(nowMs);
    }

    public synchronized void onLoadFailed(long token, @NonNull String fileKey, long nowMs) {
        pendingQueue.remove(token);
        inFlightStartMs.remove(token);
        positiveKeys.remove(fileKey);
        negativeCache.recordFailure(fileKey, nowMs, negativeTtlMs);
        recordBurstFailureLocked(nowMs);
        drainPendingLocked(nowMs);
    }

    public synchronized void cancel(long token, long nowMs) {
        if (token <= 0L) {
            return;
        }
        pendingQueue.remove(token);
        if (inFlightStartMs.remove(token) != null) {
            drainPendingLocked(nowMs);
        }
    }

    public synchronized void setScrollPaused(boolean paused, long nowMs) {
        if (this.scrollPaused == paused) {
            return;
        }
        this.scrollPaused = paused;
        if (!paused) {
            drainPendingLocked(nowMs);
        }
    }

    public synchronized void drainPending(long nowMs) {
        drainPendingLocked(nowMs);
    }

    public synchronized void clearPendingAndInFlight() {
        pendingQueue.clear();
        inFlightStartMs.clear();
        scrollPaused = false;
        drainRequested = false;
    }

    public synchronized int getInFlightCount() {
        return inFlightStartMs.size();
    }

    public synchronized int getPendingCount() {
        return pendingQueue.size();
    }

    public synchronized boolean isInBurstCooldown(long nowMs) {
        return inBurstCooldownLocked(nowMs);
    }

    private void startTaskOutsideLockSafe(long token, long nowMs, @NonNull LoadTask task) {
        boolean prevDraining = isDraining;
        isDraining = true;
        try {
            task.startLoad(token);
        } finally {
            isDraining = prevDraining;
        }
        if (!isDraining && drainRequested) {
            drainRequested = false;
            drainPendingLocked(nowMs);
        }
    }

    private void drainPendingLocked(long nowMs) {
        if (isDraining) {
            drainRequested = true;
            return;
        }
        isDraining = true;
        try {
            do {
                drainRequested = false;
                pruneStaleInFlightLocked(nowMs);
                Iterator<Map.Entry<Long, PendingEntry>> it = pendingQueue.entrySet().iterator();
                while (!scrollPaused && it.hasNext() && canStartSlowLoadLocked(nowMs)) {
                    PendingEntry next = it.next().getValue();
                    it.remove();
                    if (negativeCache.isBlocked(next.fileKey, nowMs)) {
                        continue;
                    }
                    inFlightStartMs.put(next.token, nowMs);
                    next.task.startLoad(next.token);
                }
                if (!scrollPaused && !pendingQueue.isEmpty() && inBurstCooldownLocked(nowMs)) {
                    scheduleCooldownDrainLocked(nowMs);
                }
            } while (drainRequested && !scrollPaused && !pendingQueue.isEmpty() && canStartSlowLoadLocked(nowMs));
        } finally {
            isDraining = false;
        }
    }

    private boolean canStartSlowLoadLocked(long nowMs) {
        return !inBurstCooldownLocked(nowMs) && inFlightStartMs.size() < maxConcurrentLoads;
    }

    private boolean inBurstCooldownLocked(long nowMs) {
        return nowMs < burstCooldownUntilMs;
    }

    private void recordBurstFailureLocked(long nowMs) {
        recentFailureTimestamps.addLast(nowMs);
        long cutoff = nowMs - burstWindowMs;
        while (!recentFailureTimestamps.isEmpty() && recentFailureTimestamps.peekFirst() < cutoff) {
            recentFailureTimestamps.removeFirst();
        }
        if (recentFailureTimestamps.size() >= burstFailureThreshold) {
            burstCooldownUntilMs = Math.max(burstCooldownUntilMs, nowMs + burstCooldownMs);
        }
    }

    private void scheduleCooldownDrainLocked(long nowMs) {
        if (cooldownScheduler == null) {
            return;
        }
        long remaining = Math.max(1L, burstCooldownUntilMs - nowMs);
        cooldownScheduler.scheduleDrain(remaining);
    }

    private void pruneStaleInFlightLocked(long nowMs) {
        if (inFlightStartMs.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Long, Long>> it = inFlightStartMs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> entry = it.next();
            if (nowMs - entry.getValue() >= staleInFlightMs) {
                it.remove();
            }
        }
    }
}
