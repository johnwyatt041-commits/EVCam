package com.kooo.evcam.feishu;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class FeishuPacketCacheTest {
    @Test public void assemblesOutOfOrderWithoutRetainingCompletedMessages() {
        FeishuPacketCache cache = new FeishuPacketCache();
        assertNull(cache.add("a", 2, 1, new byte[]{3, 4}));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, cache.add("a", 2, 0, new byte[]{1, 2}));
        assertEquals(0, cache.retainedBytes());
    }

    @Test public void duplicatePacketDoesNotCountAsAnotherPart() {
        FeishuPacketCache cache = new FeishuPacketCache();
        cache.add("a", 2, 0, new byte[]{1});
        assertNull(cache.add("a", 2, 0, new byte[]{2}));
        assertEquals(1, cache.retainedBytes());
        assertArrayEquals(new byte[]{2, 3}, cache.add("a", 2, 1, new byte[]{3}));
    }

    @Test public void expiredIncompleteMessageIsFreed() {
        AtomicLong time = new AtomicLong();
        FeishuPacketCache cache = new FeishuPacketCache(2, 16, 100, time::get);
        cache.add("a", 2, 0, new byte[8]);
        time.set(100);
        cache.prune();
        assertEquals(0, cache.retainedBytes());
    }

    @Test public void incompleteMessagesCannotExceedByteBudget() {
        FeishuPacketCache cache = new FeishuPacketCache(2, 10, 100, () -> 0);
        cache.add("a", 2, 0, new byte[8]);
        cache.add("b", 2, 0, new byte[8]);
        assertEquals(8, cache.retainedBytes());
        assertArrayEquals(new byte[9], cache.add("b", 2, 1, new byte[1]));
    }

    @Test public void invalidSequenceOrPartCountDoesNotAllocateStorage() {
        FeishuPacketCache cache = new FeishuPacketCache();
        assertNull(cache.add("a", Integer.MAX_VALUE, 0, new byte[1]));
        assertNull(cache.add("a", 2, -1, new byte[1]));
        assertNull(cache.add("a", 2, 2, new byte[1]));
        assertEquals(0, cache.retainedBytes());
    }
}
