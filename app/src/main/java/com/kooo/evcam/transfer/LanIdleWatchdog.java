package com.kooo.evcam.transfer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks active LAN HTTP media streams and idle timeout for 60-second auto-shutdown.
 */
public final class LanIdleWatchdog {

    public static final long DEFAULT_IDLE_TIMEOUT_MS = 60_000L;
    public static final long POLL_INTERVAL_MS = 10_000L;

    public interface Clock {
        long elapsedRealtimeMs();
    }

    private final Clock clock;
    private final long idleTimeoutMs;
    private final AtomicInteger activeStreams = new AtomicInteger(0);
    private final AtomicLong lastActivityMs = new AtomicLong(0L);

    public LanIdleWatchdog() {
        this(android.os.SystemClock::elapsedRealtime, DEFAULT_IDLE_TIMEOUT_MS);
    }

    public LanIdleWatchdog(Clock clock, long idleTimeoutMs) {
        this.clock = clock;
        this.idleTimeoutMs = Math.max(1_000L, idleTimeoutMs);
        this.lastActivityMs.set(clock.elapsedRealtimeMs());
    }

    public void reset() {
        activeStreams.set(0);
        lastActivityMs.set(clock.elapsedRealtimeMs());
    }

    public void touchActivity() {
        lastActivityMs.set(clock.elapsedRealtimeMs());
    }

    public void onStreamOpened() {
        activeStreams.incrementAndGet();
        touchActivity();
    }

    public void onStreamClosed() {
        activeStreams.updateAndGet(current -> current > 0 ? current - 1 : 0);
        touchActivity();
    }

    public int getActiveStreamCount() {
        return activeStreams.get();
    }

    public boolean shouldAutoStop() {
        if (activeStreams.get() > 0) {
            return false;
        }
        return (clock.elapsedRealtimeMs() - lastActivityMs.get()) >= idleTimeoutMs;
    }

    public long getRemainingIdleMs() {
        if (activeStreams.get() > 0) {
            return idleTimeoutMs;
        }
        long elapsed = clock.elapsedRealtimeMs() - lastActivityMs.get();
        return Math.max(0L, idleTimeoutMs - elapsed);
    }

    public int getRemainingIdleSeconds() {
        return (int) ((getRemainingIdleMs() + 999L) / 1000L);
    }
}
