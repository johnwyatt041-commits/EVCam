package com.kooo.evcam.camera;

import android.os.Handler;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A queued/stale timeout must never stop a later recording request. */
final class RecordingStartupWatchdog {
    private final Handler handler;
    private final long timeoutMs;
    private final Supplier<String> diagnostics;
    private final Consumer<String> failure;
    private long generation;
    private Runnable pending;
    RecordingStartupWatchdog(Handler handler,long timeoutMs,Supplier<String> diagnostics,Consumer<String> failure) {
        this.handler=handler; this.timeoutMs=timeoutMs; this.diagnostics=diagnostics; this.failure=failure;
    }
    synchronized void start() {
        cancel();
        final long attempt=generation;
        pending=() -> {
            synchronized (RecordingStartupWatchdog.this) {
                if (attempt!=generation) return;
                generation++; pending=null;
            }
            String detail;
            try { detail=diagnostics.get(); }
            catch (RuntimeException error) { detail="Startup diagnostics unavailable: "+error.getMessage(); }
            failure.accept(detail);
        };
        handler.postDelayed(pending,timeoutMs);
    }
    synchronized void cancel() {
        generation++;
        if (pending!=null) handler.removeCallbacks(pending);
        pending=null;
    }
}
