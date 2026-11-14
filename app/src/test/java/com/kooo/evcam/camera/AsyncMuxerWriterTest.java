package com.kooo.evcam.camera;

import android.media.MediaCodec;
import android.media.MediaFormat;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AsyncMuxerWriterTest {
    static class Sink implements AsyncMuxerWriter.Sink {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch unblock = new CountDownLatch(1);
        final List<Long> pts = new ArrayList<>();
        final List<Integer> bytes = new ArrayList<>();
        volatile boolean published;
        @Override public void start(MediaFormat format) {}
        @Override public void write(ByteBuffer data, MediaCodec.BufferInfo info) throws Exception {
            entered.countDown();
            if (!unblock.await(5, TimeUnit.SECONDS)) throw new TimeoutException();
            pts.add(info.presentationTimeUs);
            bytes.add(data.get(0) & 255);
        }
        @Override public void finish(boolean publish) { published = publish; }
    }

    static MediaCodec.BufferInfo metadata(int size, long pts) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.size = size; info.presentationTimeUs = pts;
        return info;
    }

    @Test public void stalledDiskDoesNotOwnCodecBuffersAndStopWaitsForTail() throws Exception {
        Sink sink = new Sink();
        AsyncMuxerWriter writer = new AsyncMuxerWriter(sink, e -> fail(e.toString()), "TestWriter");
        writer.start();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            ByteBuffer reusable = ByteBuffer.allocate(4);
            MediaCodec.BufferInfo info = metadata(4, 0);
            for (int i = 0; i < 12; i++) {
                reusable.put(0, (byte) i);
                info.presentationTimeUs = i * 80000L;
                writer.writeSampleData(0, reusable, info);
            }
            assertTrue(sink.entered.await(1, TimeUnit.SECONDS));
            reusable.put(0, (byte) 99);
            info.presentationTimeUs = 999;
            Future<?> stop = caller.submit(writer::stop);
            assertFalse(stop.isDone());
            sink.unblock.countDown();
            stop.get(2, TimeUnit.SECONDS);
            assertEquals(12, sink.pts.size());
            assertEquals(Long.valueOf(880000), sink.pts.get(11));
            assertEquals(Integer.valueOf(0), sink.bytes.get(0));
            assertEquals(Integer.valueOf(11), sink.bytes.get(11));
            assertTrue(sink.published);
        } finally {
            sink.unblock.countDown();
            writer.release();
            caller.shutdownNow();
        }
    }

    @Test public void backlogIsBoundedAndFailureIsNeverPublishedAsCompletedVideo() throws Exception {
        Sink sink = new Sink();
        AtomicInteger failures = new AtomicInteger();
        AsyncMuxerWriter writer = new AsyncMuxerWriter(sink, e -> failures.incrementAndGet(), "TestBound");
        writer.start();
        ByteBuffer frame = ByteBuffer.allocate(1024 * 1024);
        try {
            for (int i = 0; i < 4; i++) writer.writeSampleData(0, frame, metadata(frame.capacity(), i));
            try {
                writer.writeSampleData(0, frame, metadata(frame.capacity(), 4));
                fail("Unbounded admission");
            } catch (IllegalStateException expected) {}
            assertEquals(1, failures.get());
            assertTrue(writer.stats().contains("writerPeakBytes=4194304"));
        } finally {
            sink.unblock.countDown();
            try { writer.stop(); fail("Failure must reach the stop caller"); }
            catch (IllegalStateException expected) {}
        }
        assertFalse(sink.published);
    }
}
