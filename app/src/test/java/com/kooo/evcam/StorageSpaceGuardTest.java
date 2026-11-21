package com.kooo.evcam;

import org.junit.Test;

import static org.junit.Assert.*;

public class StorageSpaceGuardTest {

    @Test
    public void estimateBytesPerSecondTracksMultiCameraThroughputAndClampsBounds() {
        StorageSpaceGuard guard = new StorageSpaceGuard();
        long fallback = 3L * StorageSpaceGuard.MB;
        assertEquals(fallback, guard.estimateBytesPerSecond(1_000L, fallback));

        // 4 路共写入 60MB（每路 15MB，分段 60s），连续上报间隔极短但归属 60s 分段窗口
        long t0 = 100_000L;
        guard.recordSegment(t0, 15L * StorageSpaceGuard.MB, 60_000L);
        guard.recordSegment(t0 + 200L, 15L * StorageSpaceGuard.MB, 60_000L);
        guard.recordSegment(t0 + 400L, 15L * StorageSpaceGuard.MB, 60_000L);
        guard.recordSegment(t0 + 600L, 15L * StorageSpaceGuard.MB, 60_000L);

        long rate = guard.estimateBytesPerSecond(t0 + 700L, fallback);
        // 60MB / 60.6s ≈ 0.99 MB/s，远高于最小下限，显著高于 0
        assertTrue("rate should reflect ~1 MB/s, got " + rate,
                rate > 800L * 1024L && rate < 1200L * 1024L);

        // 超高码率测试上限截断 100 MB/s
        StorageSpaceGuard highGuard = new StorageSpaceGuard();
        highGuard.recordSegment(200_000L, 10_000L * StorageSpaceGuard.MB, 10_000L);
        assertEquals(StorageSpaceGuard.MAX_WRITE_RATE_BPS, highGuard.estimateBytesPerSecond(200_100L, fallback));
    }

    @Test
    public void dynamicReserveScalesWithWriteSpeedAndRespectsStorageFloors() {
        // 低码率时保持各自下限：内部 3GB / USB 1GB
        long lowRate = 1L * StorageSpaceGuard.MB;
        assertEquals(StorageSpaceGuard.INTERNAL_MIN_RESERVE_BYTES,
                StorageSpaceGuard.computeTargetReserveBytes(lowRate, 60_000L, true));
        assertEquals(StorageSpaceGuard.USB_MIN_RESERVE_BYTES,
                StorageSpaceGuard.computeTargetReserveBytes(lowRate, 60_000L, false));

        // 12 MB/s 连续 4 路高码率 + 5 分钟分段 -> horizon = 900s -> 10.8 GB > 3 GB 下限
        long highRate = 12L * StorageSpaceGuard.MB;
        long reserve = StorageSpaceGuard.computeTargetReserveBytes(highRate, 300_000L, true);
        assertEquals(12L * StorageSpaceGuard.MB * 900L, reserve);
        assertTrue(reserve > StorageSpaceGuard.INTERNAL_MIN_RESERVE_BYTES);

        // 中转缓存预留与紧急临界阈值
        assertEquals(StorageSpaceGuard.RELAY_CACHE_MIN_RESERVE_BYTES,
                StorageSpaceGuard.computeRelayCacheReserveBytes(lowRate, 60_000L));
        assertTrue(StorageSpaceGuard.computeRelayCriticalReserveBytes(highRate, 60_000L)
                >= StorageSpaceGuard.RELAY_CRITICAL_MIN_RESERVE_BYTES);
    }

    @Test
    public void fastPathGateSkipsFullScanWhenHealthyAndTriggersOnThresholdOrStaleCache() {
        long limit = 20L * StorageSpaceGuard.GB;
        long reserve = 3L * StorageSpaceGuard.GB;
        long maxAge = StorageSpaceGuard.DEFAULT_FULL_SCAN_CACHE_MAX_AGE_MS;

        // O(1) 快速跳过：可用空间充足、缓存新鲜、新增未触及配额上限
        assertFalse(StorageSpaceGuard.shouldRunFullVideoScan(
                10L * StorageSpaceGuard.GB,
                reserve,
                limit,
                12L * StorageSpaceGuard.GB,
                500L * StorageSpaceGuard.MB,
                60_000L,
                maxAge
        ));

        // 触发条件 1：可用空间低于动态预留
        assertTrue(StorageSpaceGuard.shouldRunFullVideoScan(
                2L * StorageSpaceGuard.GB,
                reserve,
                limit,
                12L * StorageSpaceGuard.GB,
                10L * StorageSpaceGuard.MB,
                1_000L,
                maxAge
        ));

        // 触发条件 2：缓存 + 新增预计达到配额上限
        assertTrue(StorageSpaceGuard.shouldRunFullVideoScan(
                10L * StorageSpaceGuard.GB,
                reserve,
                limit,
                19L * StorageSpaceGuard.GB,
                2L * StorageSpaceGuard.GB,
                10_000L,
                maxAge
        ));

        // 触发条件 3：目录缓存未知（冷启动首次）或过期
        assertTrue(StorageSpaceGuard.shouldRunFullVideoScan(
                10L * StorageSpaceGuard.GB,
                reserve,
                limit,
                -1L,
                0L,
                10_000L,
                maxAge
        ));
        assertTrue(StorageSpaceGuard.shouldRunFullVideoScan(
                10L * StorageSpaceGuard.GB,
                reserve,
                limit,
                10L * StorageSpaceGuard.GB,
                0L,
                maxAge + 1L,
                maxAge
        ));
    }
}
