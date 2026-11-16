package com.kooo.evcam.transfer;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class LanIdleWatchdogTest {

    @Test
    public void timeoutRespectsActiveStreamsAndResetsOnClose() {
        AtomicLong nowMs = new AtomicLong(1_000L);
        LanIdleWatchdog watchdog = new LanIdleWatchdog(nowMs::get, 60_000L);

        assertFalse(watchdog.shouldAutoStop());
        assertEquals(60, watchdog.getRemainingIdleSeconds());

        nowMs.set(60_999L);
        assertFalse(watchdog.shouldAutoStop());
        assertEquals(1, watchdog.getRemainingIdleSeconds());

        nowMs.set(61_000L);
        assertTrue(watchdog.shouldAutoStop());
        assertEquals(0, watchdog.getRemainingIdleSeconds());

        // Active stream prevents timeout even far past 60s
        watchdog.onStreamOpened();
        assertEquals(1, watchdog.getActiveStreamCount());
        nowMs.set(500_000L);
        assertFalse(watchdog.shouldAutoStop());

        // Closing the stream grants a fresh 60s window
        watchdog.onStreamClosed();
        assertEquals(0, watchdog.getActiveStreamCount());
        assertFalse(watchdog.shouldAutoStop());

        nowMs.set(559_999L);
        assertFalse(watchdog.shouldAutoStop());

        nowMs.set(560_000L);
        assertTrue(watchdog.shouldAutoStop());
    }
}
