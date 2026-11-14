package com.kooo.evcam.camera;

import android.os.Handler;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RecordingStartupWatchdogTest {
    private final List<Runnable> queued=new ArrayList<>();
    private final AtomicInteger failures=new AtomicInteger();
    private final Handler handler=mock(Handler.class);
    private RecordingStartupWatchdog watchdog() {
        when(handler.postDelayed(any(Runnable.class),anyLong())).thenAnswer(call -> {
            queued.add(call.getArgument(0)); return true;
        });
        return new RecordingStartupWatchdog(handler,20000,() -> "source=0; encoded=0",message -> {
            assertTrue(message.contains("encoded=0")); failures.incrementAndGet();
        });
    }
    @Test public void missingFirstWriteEndsWaitingOnce() {
        RecordingStartupWatchdog watchdog=watchdog(); watchdog.start();
        queued.get(0).run(); queued.get(0).run(); assertEquals(1,failures.get());
    }
    @Test public void firstWriteOrStopCancelsEvenAnAlreadyDequeuedTimeout() {
        RecordingStartupWatchdog watchdog=watchdog(); watchdog.start(); watchdog.cancel();
        queued.get(0).run(); assertEquals(0,failures.get());
    }
    @Test public void staleAttemptCannotStopTheNextRecording() {
        RecordingStartupWatchdog watchdog=watchdog(); watchdog.start(); watchdog.start();
        queued.get(0).run(); assertEquals(0,failures.get());
        queued.get(1).run(); assertEquals(1,failures.get());
    }
    @Test public void diagnosticFailureCannotLeaveUiWaitingForever() {
        watchdog();
        RecordingStartupWatchdog watchdog=new RecordingStartupWatchdog(handler,20000,
                () -> { throw new IllegalStateException("snapshot"); },message -> failures.incrementAndGet());
        watchdog.start(); queued.get(0).run(); assertEquals(1,failures.get());
    }
}
