package com.kooo.evcam.camera;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Copies compressed samples, releases codec buffers promptly, and owns all disk I/O. */
final class AsyncMuxerWriter {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int MAX_SAMPLES = 64;
    interface Sink {
        void start(MediaFormat format) throws Exception;
        void write(ByteBuffer data, MediaCodec.BufferInfo info) throws Exception;
        void finish(boolean publish) throws Exception;
    }
    private final ExecutorService worker;
    private final Sink sink;
    private final Consumer<Throwable> onFailure;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();
    private int allocatedBytes;
    private int pendingBytes;
    private int pendingSamples;
    private int peakBytes;
    private int peakSamples;
    private volatile long writtenSamples;
    private volatile long maxWriteUs;
    private volatile long totalWriteUs;
    private volatile boolean finished;
    private MediaFormat format;
    private Future<?> completion;

    AsyncMuxerWriter(String path, Consumer<Throwable> onFailure) {
        this(new FileSink(path), onFailure, "MuxerWriter-" + new File(path).getName());
    }

    AsyncMuxerWriter(Sink sink, Consumer<Throwable> onFailure, String name) {
        this.sink = sink;
        this.onFailure = onFailure;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_SAMPLES + 4), task -> new Thread(task, name));
    }

    int addTrack(MediaFormat value) { format = value; return 0; }
    void start() { worker.execute(() -> {
        try { sink.start(format); } catch (Throwable e) { fail(e); }
    }); }

    synchronized void writeSampleData(int ignoredTrack, ByteBuffer source, MediaCodec.BufferInfo info) {
        Throwable error = failure.get();
        if (error != null) throw new IllegalStateException("Video writer failed", error);
        ByteBuffer copy = reserve(info.size);
        if (copy == null) {
            IllegalStateException overflow = new IllegalStateException("Video writer backlog exceeded bounded capacity");
            fail(overflow);
            throw overflow;
        }
        try {
            ByteBuffer input = source.duplicate();
            input.position(info.offset).limit(info.offset + info.size);
            copy.put(input).flip();
            MediaCodec.BufferInfo metadata = new MediaCodec.BufferInfo();
            metadata.offset = 0;
            metadata.size = info.size;
            metadata.flags = info.flags;
            metadata.presentationTimeUs = info.presentationTimeUs;
            worker.execute(() -> {
                try {
                    if (failure.get() == null) {
                        long start = System.nanoTime();
                        sink.write(copy, metadata);
                        long us = (System.nanoTime() - start) / 1000;
                        writtenSamples++;
                        totalWriteUs += us;
                        maxWriteUs = Math.max(maxWriteUs, us);
                    }
                } catch (Throwable e) { fail(e); }
                finally { recycle(copy); }
            });
        } catch (RuntimeException e) {
            recycle(copy);
            fail(e);
            throw e;
        }
    }

    private synchronized ByteBuffer reserve(int size) {
        if (completion != null || size <= 0 || size > MAX_BYTES || pendingSamples >= MAX_SAMPLES) return null;
        int capacity = ((size + 65535) / 65536) * 65536;
        ByteBuffer selected = null;
        for (Iterator<ByteBuffer> it = pool.iterator(); it.hasNext();) {
            ByteBuffer candidate = it.next();
            if (candidate.capacity() >= capacity && candidate.capacity() <= capacity * 2) {
                selected = candidate;
                it.remove();
                break;
            }
        }
        if (selected == null) {
            while (allocatedBytes + capacity > MAX_BYTES && !pool.isEmpty()) {
                allocatedBytes -= pool.removeFirst().capacity();
            }
            if (allocatedBytes + capacity > MAX_BYTES) return null;
            selected = ByteBuffer.allocateDirect(capacity);
            allocatedBytes += capacity;
        }
        selected.clear();
        pendingSamples++;
        pendingBytes += selected.capacity();
        peakBytes = Math.max(peakBytes, pendingBytes);
        peakSamples = Math.max(peakSamples, pendingSamples);
        return selected;
    }

    private synchronized void recycle(ByteBuffer buffer) {
        pendingSamples--;
        pendingBytes -= buffer.capacity();
        if (finished) allocatedBytes -= buffer.capacity();
        else pool.addLast(buffer);
    }

    private void fail(Throwable e) {
        if (failure.compareAndSet(null, e) && onFailure != null) onFailure.accept(e);
    }

    void abort(Throwable error) { fail(error); }

    void stop() {
        Future<?> future;
        synchronized (this) {
            if (completion == null) {
                completion = worker.submit(() -> {
                    try { sink.finish(failure.get() == null); }
                    catch (Throwable e) { fail(e); }
                    finally {
                        synchronized (AsyncMuxerWriter.this) {
                            finished = true;
                            pool.clear();
                            allocatedBytes = 0;
                        }
                    }
                });
                worker.shutdown();
            }
            future = completion;
        }
        try {
            // A stop completion must mean that accepted samples and the MP4 trailer are on disk.
            // Caller is the recorder's stopping thread, never the UI or a frame callback.
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted finalizing video", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unable to finalize video", e.getCause());
        }
        if (failure.get() != null) throw new IllegalStateException("Video remains pending after writer failure", failure.get());
    }

    void release() { stop(); }
    long getWrittenSamples() { return writtenSamples; }
    synchronized String stats() {
        return "writerSamples=" + writtenSamples + ", writerAvgUs="
                + (writtenSamples == 0 ? 0 : totalWriteUs / writtenSamples)
                + ", writerMaxUs=" + maxWriteUs + ", writerPeakBytes=" + peakBytes
                + ", writerPeakSamples=" + peakSamples;
    }

    private static final class FileSink implements Sink {
        final File target;
        final File pending;
        MediaMuxer muxer;
        int track;
        boolean started;
        FileSink(String path) { target = new File(path); pending = new File(path + ".pending"); }
        @Override public void start(MediaFormat format) throws IOException {
            if (target.exists() || pending.exists()) throw new IOException("Refusing to overwrite video: " + target);
            muxer = new MediaMuxer(pending.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            track = muxer.addTrack(format);
            muxer.start();
            started = true;
        }
        @Override public void write(ByteBuffer data, MediaCodec.BufferInfo info) {
            if (!started) throw new IllegalStateException("Muxer has not started");
            muxer.writeSampleData(track, data, info);
        }
        @Override public void finish(boolean publish) throws IOException {
            try { if (started) muxer.stop(); }
            finally { if (muxer != null) muxer.release(); }
            if (publish && started && !pending.renameTo(target)) throw new IOException("Cannot publish completed video: " + target);
        }
    }
}
