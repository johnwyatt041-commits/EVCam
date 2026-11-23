package com.kooo.evcam.playback;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class PlaybackThumbnailGuardTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static PlaybackThumbnailGuard newGuard(AtomicLong scheduledDelayMs) {
        return new PlaybackThumbnailGuard(
                new PlaybackThumbnailGuard.NegativeCache(64),
                PlaybackThumbnailGuard.DEFAULT_MAX_CONCURRENT_VIDEO_LOADS,
                PlaybackThumbnailGuard.DEFAULT_NEGATIVE_TTL_MS,
                PlaybackThumbnailGuard.DEFAULT_BURST_FAILURE_THRESHOLD,
                PlaybackThumbnailGuard.DEFAULT_BURST_WINDOW_MS,
                PlaybackThumbnailGuard.DEFAULT_BURST_COOLDOWN_MS,
                PlaybackThumbnailGuard.DEFAULT_STALE_IN_FLIGHT_MS,
                delayMs -> {
                    if (scheduledDelayMs != null) {
                        scheduledDelayMs.set(delayMs);
                    }
                });
    }

    @Test
    public void selectsSingleRecordedRepresentativeWithoutUnrecordedFrontAndFallsBackOnBadFiles() throws Exception {
        File dir = temp.newFolder("e5_three_channel");
        File back = new File(dir, "20261123_090000_back.mp4");
        File left = new File(dir, "20261123_090000_left.mp4");
        File right = new File(dir, "20261123_090000_right.mp4");

        // E5 daily 3-channel recording: NO front camera recorded!
        Files.write(back.toPath(), new byte[2048]);
        Files.write(left.toPath(), new byte[2048]);
        Files.write(right.toPath(), new byte[2048]);

        VideoGroup group = new VideoGroup("20261123_090000");
        group.addFile(back);
        group.addFile(left);
        group.addFile(right);

        List<VideoGroup.RepresentativeMedia> recorded = group.getRecordedVideosInPriorityOrder();
        assertEquals(3, recorded.size());
        for (VideoGroup.RepresentativeMedia item : recorded) {
            assertNotEquals(VideoGroup.POSITION_FRONT, item.position);
        }

        PlaybackThumbnailGuard guard = newGuard(null);
        long now = 1_000_000L;
        VideoGroup.RepresentativeMedia rep1 = guard.selectRepresentativeMedia(group, now);
        assertNotNull(rep1);
        assertEquals(VideoGroup.POSITION_BACK, rep1.position);
        assertEquals("后", PlaybackThumbnailGuard.resolveDisplayCameraLabel(group, rep1));

        // Mark back as decode failure -> next call picks actually-recorded left, never unrecorded front.
        long token = guard.submit(PlaybackThumbnailGuard.buildFileKey(back), now, t -> {});
        assertTrue(token > 0L);
        guard.onLoadFailed(token, PlaybackThumbnailGuard.buildFileKey(back), now);

        VideoGroup.RepresentativeMedia rep2 = guard.selectRepresentativeMedia(group, now + 10L);
        assertNotNull(rep2);
        assertEquals(VideoGroup.POSITION_LEFT, rep2.position);
        assertEquals("左", PlaybackThumbnailGuard.resolveDisplayCameraLabel(group, rep2));

        // Fail left and right -> no eligible thumbnail left, label still stays a recorded camera ("后"), never "前".
        long tokenL = guard.submit(PlaybackThumbnailGuard.buildFileKey(left), now + 20L, t -> {});
        guard.onLoadFailed(tokenL, PlaybackThumbnailGuard.buildFileKey(left), now + 20L);
        long tokenR = guard.submit(PlaybackThumbnailGuard.buildFileKey(right), now + 30L, t -> {});
        guard.onLoadFailed(tokenR, PlaybackThumbnailGuard.buildFileKey(right), now + 30L);

        VideoGroup.RepresentativeMedia repNone = guard.selectRepresentativeMedia(group, now + 40L);
        assertNull(repNone);
        String fallbackLabel = PlaybackThumbnailGuard.resolveDisplayCameraLabel(group, null);
        assertEquals("后", fallbackLabel);
        assertNotEquals("前", fallbackLabel);
    }

    @Test
    public void tinyFileImmediatelyNegativeCachedAndKeyInvalidatesOnSizeOrMtimeChange() throws Exception {
        File dir = temp.newFolder("bad_thumb");
        File tinyBack = new File(dir, "20261123_091000_back.mp4");
        File healthyRight = new File(dir, "20261123_091000_right.mp4");
        Files.write(tinyBack.toPath(), new byte[128]);
        Files.write(healthyRight.toPath(), new byte[2048]);

        VideoGroup group = new VideoGroup("20261123_091000");
        group.addFile(tinyBack);
        group.addFile(healthyRight);

        PlaybackThumbnailGuard guard = newGuard(null);
        long now = 10_000L;
        VideoGroup.RepresentativeMedia rep = guard.selectRepresentativeMedia(group, now);
        assertNotNull(rep);
        assertEquals(VideoGroup.POSITION_RIGHT, rep.position);
        assertTrue(guard.isNegativeCached(PlaybackThumbnailGuard.buildFileKey(tinyBack), now));

        // Negative cache blocks retry on first failure for TTL, permanently after 2nd failure,
        // and auto-invalidates when length or mtime changes.
        File corrupt = new File(dir, "20261123_091500_left.mp4");
        Files.write(corrupt.toPath(), new byte[4096]);
        corrupt.setLastModified(50_000L);
        String keyV1 = PlaybackThumbnailGuard.buildFileKey(corrupt);

        long t1 = guard.submit(keyV1, now, t -> {});
        guard.onLoadFailed(t1, keyV1, now);
        assertTrue(guard.isNegativeCached(keyV1, now + 1000L));
        assertEquals(0L, guard.submit(keyV1, now + 1000L, t -> fail("must skip negative-cached file")));

        // After TTL expires, 2nd failure makes it session-permanent.
        long afterTtl = now + PlaybackThumbnailGuard.DEFAULT_NEGATIVE_TTL_MS + 100L;
        assertFalse(guard.isNegativeCached(keyV1, afterTtl));
        long t2 = guard.submit(keyV1, afterTtl, t -> {});
        assertTrue(t2 > 0L);
        guard.onLoadFailed(t2, keyV1, afterTtl);
        assertTrue(guard.isNegativeCached(keyV1, afterTtl + PlaybackThumbnailGuard.DEFAULT_NEGATIVE_TTL_MS * 100L));

        // If file content is replaced/repaired (different length/mtime), key changes and load is allowed again.
        Files.write(corrupt.toPath(), new byte[8192]);
        corrupt.setLastModified(90_000L);
        String keyV2 = PlaybackThumbnailGuard.buildFileKey(corrupt);
        assertNotEquals(keyV1, keyV2);
        assertFalse(guard.isNegativeCached(keyV2, afterTtl));
    }

    @Test
    public void throttlesConcurrentSlowLoadsQueuesDuringScrollAndDrainsAfterBurstCooldown() {
        AtomicLong scheduledDelay = new AtomicLong(-1L);
        PlaybackThumbnailGuard guard = newGuard(scheduledDelay);
        List<String> started = new ArrayList<>();
        List<Long> tokens = new ArrayList<>();

        long now = 1_000_000L;
        tokens.add(guard.submit("k1", now, t -> started.add("k1")));
        tokens.add(guard.submit("k2", now, t -> started.add("k2")));
        tokens.add(guard.submit("k3", now, t -> started.add("k3")));
        tokens.add(guard.submit("k4", now, t -> started.add("k4")));

        assertEquals(2, guard.getInFlightCount());
        assertEquals(2, guard.getPendingCount());
        assertEquals(java.util.Arrays.asList("k1", "k2"), started);

        // Cancel k4 before it starts -> removed from pending queue cleanly.
        guard.cancel(tokens.get(3), now);
        assertEquals(1, guard.getPendingCount());

        // Pause during fast fling scroll -> finishing k1 must not drain k3 until scroll stops.
        guard.setScrollPaused(true, now);
        guard.onLoadSucceeded(tokens.get(0), "k1", now + 10L);
        assertEquals(1, guard.getInFlightCount());
        assertEquals(1, guard.getPendingCount());

        // Unpause -> k3 starts immediately; also k1 is now known-positive so it bypasses slow queue.
        guard.setScrollPaused(false, now + 20L);
        assertEquals(java.util.Arrays.asList("k1", "k2", "k3"), started);
        assertEquals(2, guard.getInFlightCount());

        // Known-positive fast path runs even while scrollPaused.
        guard.setScrollPaused(true, now + 25L);
        long fastToken = guard.submit("k1", now + 25L, t -> started.add("k1-cached"));
        assertTrue(fastToken > 0L);
        assertEquals("k1-cached", started.get(started.size() - 1));
        guard.onLoadSucceeded(fastToken, "k1", now + 25L);
        guard.setScrollPaused(false, now + 30L);

        // Synchronous re-entrant completion inside startLoad does not corrupt slots.
        AtomicInteger syncRuns = new AtomicInteger();
        long t2 = tokens.get(1);
        long t3 = tokens.get(2);
        guard.onLoadSucceeded(t2, "k2", now + 30L);
        guard.onLoadSucceeded(t3, "k3", now + 30L);
        assertEquals(0, guard.getInFlightCount());

        guard.submit("syncA", now + 40L, tok -> {
            syncRuns.incrementAndGet();
            guard.onLoadSucceeded(tok, "syncA", now + 40L);
        });
        assertEquals(1, syncRuns.get());
        assertEquals(0, guard.getInFlightCount());

        // Trigger burst failures (3 failures within 5s) -> enters 2s cooldown and schedules drain.
        long f1 = guard.submit("bad1", now + 100L, t -> {});
        long f2 = guard.submit("bad2", now + 110L, t -> {});
        guard.onLoadFailed(f1, "bad1", now + 120L);
        guard.onLoadFailed(f2, "bad2", now + 130L);
        long f3 = guard.submit("bad3", now + 140L, t -> {});
        guard.onLoadFailed(f3, "bad3", now + 150L);

        assertTrue(guard.isInBurstCooldown(now + 160L));
        List<String> afterCooldown = new ArrayList<>();
        guard.submit("queuedDuringCooldown", now + 160L, t -> afterCooldown.add("queuedDuringCooldown"));
        assertTrue(afterCooldown.isEmpty());
        assertTrue(scheduledDelay.get() > 0L);

        // After cooldown elapses, drainPending starts queued item.
        guard.drainPending(now + 150L + PlaybackThumbnailGuard.DEFAULT_BURST_COOLDOWN_MS);
        assertEquals(java.util.Collections.singletonList("queuedDuringCooldown"), afterCooldown);

        // Stale in-flight pruning frees leaked slot after 10s.
        guard.submit("hung", now + 5000L, t -> {});
        assertEquals(2, guard.getInFlightCount());
        long unblocked = guard.submit("afterHung", now + 5000L + PlaybackThumbnailGuard.DEFAULT_STALE_IN_FLIGHT_MS + 1L,
                t -> afterCooldown.add("afterHung"));
        assertTrue(unblocked > 0L);
        assertEquals(2, afterCooldown.size());
    }
}
