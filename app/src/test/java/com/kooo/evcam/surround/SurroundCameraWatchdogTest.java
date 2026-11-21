package com.kooo.evcam.surround;

import org.junit.Test;

import static org.junit.Assert.*;

public class SurroundCameraWatchdogTest {

    @Test
    public void respectsStartupGracePeriodBeforeReportingStall() {
        SurroundCameraWatchdog watchdog = new SurroundCameraWatchdog();
        long start = 10_000L;
        watchdog.reset(start);

        SurroundCameraWatchdog.CameraState waiting = new SurroundCameraWatchdog.CameraState(
                true, true, false, true, false, 0L, -1L);

        assertEquals(SurroundCameraWatchdog.RecoveryAction.NONE,
                watchdog.evaluate(0, start + 3_999L, waiting));
        assertEquals(SurroundCameraWatchdog.RecoveryAction.REBIND_AND_RECREATE_SESSION,
                watchdog.evaluate(0, start + 4_000L, waiting));
    }

    @Test
    public void stalledOesWithLiveCaptureTriggersRebindInsteadOfForceReopenFirst() {
        SurroundCameraWatchdog watchdog = new SurroundCameraWatchdog();
        long t0 = 50_000L;
        watchdog.reset(t0);

        SurroundCameraWatchdog.CameraState liveCaptureStalledOes = new SurroundCameraWatchdog.CameraState(
                true, true, false, true, false, t0 + 1_000L, 120L);

        assertEquals(SurroundCameraWatchdog.RecoveryAction.NONE,
                watchdog.evaluate(1, t0 + 4_499L, liveCaptureStalledOes));
        assertEquals(SurroundCameraWatchdog.RecoveryAction.REBIND_AND_RECREATE_SESSION,
                watchdog.evaluate(1, t0 + 4_500L, liveCaptureStalledOes));

        // Cooldown suppresses immediate repeat
        assertEquals(SurroundCameraWatchdog.RecoveryAction.NONE,
                watchdog.evaluate(1, t0 + 6_000L, liveCaptureStalledOes));

        // Second attempt after BASE_COOLDOWN_MS*2 (8s) still rebinds while capture is alive
        assertEquals(SurroundCameraWatchdog.RecoveryAction.REBIND_AND_RECREATE_SESSION,
                watchdog.evaluate(1, t0 + 12_600L, liveCaptureStalledOes));
    }

    @Test
    public void stalledOesWithDeadCaptureEscalatesToForceReopenAfterRebind() {
        SurroundCameraWatchdog watchdog = new SurroundCameraWatchdog();
        long t0 = 100_000L;
        watchdog.reset(t0);

        SurroundCameraWatchdog.CameraState deadCapture = new SurroundCameraWatchdog.CameraState(
                true, true, false, true, false, t0 + 1_000L, 10_000L);

        // Attempt 1: rebind & recreate session
        assertEquals(SurroundCameraWatchdog.RecoveryAction.REBIND_AND_RECREATE_SESSION,
                watchdog.evaluate(2, t0 + 4_500L, deadCapture));

        // After backoff cooldown (BASE_COOLDOWN_MS << 1 = 8000ms), escalates to FORCE_REOPEN
        assertEquals(SurroundCameraWatchdog.RecoveryAction.FORCE_REOPEN,
                watchdog.evaluate(2, t0 + 12_500L, deadCapture));
    }

    @Test
    public void activeRecordingNeverTriggersForceReopenEvenWhenCaptureStale() {
        SurroundCameraWatchdog watchdog = new SurroundCameraWatchdog();
        long t0 = 200_000L;
        watchdog.reset(t0);

        SurroundCameraWatchdog.CameraState recordingState = new SurroundCameraWatchdog.CameraState(
                true, false, false, false, true, t0 + 500L, 12_000L);

        assertEquals(SurroundCameraWatchdog.RecoveryAction.REBIND_AND_RECREATE_SESSION,
                watchdog.evaluate(3, t0 + 4_000L, recordingState));
        assertEquals(SurroundCameraWatchdog.RecoveryAction.REBIND_AND_RECREATE_SESSION,
                watchdog.evaluate(3, t0 + 20_000L, recordingState));
        assertEquals(SurroundCameraWatchdog.RecoveryAction.REBIND_AND_RECREATE_SESSION,
                watchdog.evaluate(3, t0 + 50_000L, recordingState));
    }

    @Test
    public void freshOesFrameResetsRecoveryCounterAndStopsRecovery() {
        SurroundCameraWatchdog watchdog = new SurroundCameraWatchdog();
        long t0 = 300_000L;
        watchdog.reset(t0);

        SurroundCameraWatchdog.CameraState deadCapture = new SurroundCameraWatchdog.CameraState(
                true, true, false, false, false, t0 + 500L, 9_000L);

        assertEquals(SurroundCameraWatchdog.RecoveryAction.REBIND_AND_RECREATE_SESSION,
                watchdog.evaluate(0, t0 + 4_000L, deadCapture));

        // New OES frame arrives
        SurroundCameraWatchdog.CameraState recovered = new SurroundCameraWatchdog.CameraState(
                true, true, false, true, false, t0 + 5_000L, 50L);
        assertEquals(SurroundCameraWatchdog.RecoveryAction.NONE,
                watchdog.evaluate(0, t0 + 5_500L, recovered));

        // Later stall starts from attempt 1 again (rebind first, not immediate forceReopen)
        assertEquals(SurroundCameraWatchdog.RecoveryAction.REBIND_AND_RECREATE_SESSION,
                watchdog.evaluate(0, t0 + 9_000L, deadCapture));
    }
}
