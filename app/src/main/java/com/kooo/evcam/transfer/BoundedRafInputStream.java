package com.kooo.evcam.transfer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded {@link RandomAccessFile} stream for HTTP Range responses.
 * Reports stream open, byte-read activity, and idempotent close back to the server watchdog.
 */
public final class BoundedRafInputStream extends InputStream {

    public interface StreamLifecycleObserver {
        void onStreamOpened();

        void onStreamActivity();

        void onStreamClosed(boolean completedFully, long bytesServed);
    }

    private final RandomAccessFile raf;
    private final long expectedBytes;
    @Nullable
    private final StreamLifecycleObserver observer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private long remaining;
    private long bytesServed = 0L;

    public BoundedRafInputStream(@NonNull File file,
                                 long startOffset,
                                 long length,
                                 @Nullable StreamLifecycleObserver observer) throws IOException {
        if (startOffset < 0L || length < 0L) {
            throw new IllegalArgumentException("Invalid slice: start=" + startOffset + ", length=" + length);
        }
        RandomAccessFile opened = new RandomAccessFile(file, "r");
        try {
            if (startOffset > 0L) {
                opened.seek(startOffset);
            }
        } catch (IOException | RuntimeException e) {
            try {
                opened.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        this.raf = opened;
        this.expectedBytes = length;
        this.remaining = length;
        this.observer = observer;
        if (this.observer != null) {
            this.observer.onStreamOpened();
        }
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0L) {
            return -1;
        }
        int b = raf.read();
        if (b >= 0) {
            remaining--;
            bytesServed++;
            if (observer != null) {
                observer.onStreamActivity();
            }
        }
        return b;
    }

    @Override
    public int read(@NonNull byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        if (remaining <= 0L) {
            return -1;
        }
        int toRead = (int) Math.min(len, remaining);
        int read = raf.read(b, off, toRead);
        if (read > 0) {
            remaining -= read;
            bytesServed += read;
            if (observer != null) {
                observer.onStreamActivity();
            }
        }
        return read;
    }

    @Override
    public int available() {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, remaining));
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            raf.close();
        } finally {
            if (observer != null) {
                observer.onStreamClosed(bytesServed >= expectedBytes, bytesServed);
            }
        }
    }
}
