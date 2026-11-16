package com.kooo.evcam.transfer;

import androidx.annotation.NonNull;

import com.kooo.evcam.AppLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import fi.iki.elonen.NanoHTTPD;

/**
 * Bounded {@link NanoHTTPD.AsyncRunner} to avoid unbounded per-socket thread creation when mobile
 * browsers fetch thumbnails and media metadata concurrently.
 */
public final class BoundedAsyncRunner implements NanoHTTPD.AsyncRunner {

    private static final String TAG = "BoundedAsyncRunner";
    private static final int CORE_THREADS = 4;
    private static final int MAX_THREADS = 12;
    private static final int QUEUE_CAPACITY = 48;

    private final List<NanoHTTPD.ClientHandler> running = Collections.synchronizedList(new ArrayList<>());
    private final ThreadPoolExecutor executor;

    public BoundedAsyncRunner() {
        AtomicInteger threadIndex = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "lan-http-" + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
                CORE_THREADS,
                MAX_THREADS,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                factory);
        this.executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public void closeAll() {
        List<NanoHTTPD.ClientHandler> snapshot;
        synchronized (running) {
            snapshot = new ArrayList<>(running);
        }
        for (NanoHTTPD.ClientHandler handler : snapshot) {
            try {
                handler.close();
            } catch (Exception ignored) {
            }
        }
        executor.shutdownNow();
    }

    @Override
    public void closed(NanoHTTPD.ClientHandler clientHandler) {
        running.remove(clientHandler);
    }

    @Override
    public void exec(@NonNull NanoHTTPD.ClientHandler clientHandler) {
        if (executor.isShutdown()) {
            safeClose(clientHandler);
            return;
        }
        running.add(clientHandler);
        try {
            executor.execute(clientHandler);
        } catch (RejectedExecutionException e) {
            running.remove(clientHandler);
            safeClose(clientHandler);
            AppLog.w(TAG, "LAN HTTP worker pool saturated; rejected incoming socket");
        }
    }

    public int getActiveHandlerCount() {
        return running.size();
    }

    private static void safeClose(NanoHTTPD.ClientHandler clientHandler) {
        try {
            clientHandler.close();
        } catch (Exception ignored) {
        }
    }
}
