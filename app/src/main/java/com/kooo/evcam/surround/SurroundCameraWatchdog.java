package com.kooo.evcam.surround;

/**
 * Pure-Java monotonic watchdog for Mode A 360 surround cameras.
 * Detects per-camera OES frame stalls without false-triggering during startup or active recording.
 */
public final class SurroundCameraWatchdog {

    public static final int CAMERA_COUNT = 4;
    public static final long STARTUP_GRACE_MS = 4_000L;
    public static final long OES_STALL_TIMEOUT_MS = 3_500L;
    public static final long CAPTURE_ALIVE_WINDOW_MS = 2_500L;
    public static final long BASE_COOLDOWN_MS = 4_000L;
    public static final long MAX_BACKOFF_COOLDOWN_MS = 15_000L;
    public static final int MAX_REBIND_ATTEMPTS_BEFORE_REOPEN = 2;

    public enum RecoveryAction {
        NONE,
        REBIND_AND_RECREATE_SESSION,
        FORCE_REOPEN
    }

    public static final class CameraState {
        public final boolean surfaceValid;
        public final boolean cameraConnected;
        public final boolean cameraOpeningOrReconnecting;
        public final boolean hasCaptureSession;
        public final boolean recordingActive;
        public final long lastOesFrameUptimeMs;
        public final long captureAgeMs;

        public CameraState(boolean surfaceValid,
                           boolean cameraConnected,
                           boolean cameraOpeningOrReconnecting,
                           boolean hasCaptureSession,
                           boolean recordingActive,
                           long lastOesFrameUptimeMs,
                           long captureAgeMs) {
            this.surfaceValid = surfaceValid;
            this.cameraConnected = cameraConnected;
            this.cameraOpeningOrReconnecting = cameraOpeningOrReconnecting;
            this.hasCaptureSession = hasCaptureSession;
            this.recordingActive = recordingActive;
            this.lastOesFrameUptimeMs = lastOesFrameUptimeMs;
            this.captureAgeMs = captureAgeMs;
        }
    }

    private static final class SlotTracker {
        long startedAtUptimeMs;
        long lastObservedOesUptimeMs;
        long lastRecoveryUptimeMs;
        int consecutiveRecoveries;

        void reset(long nowUptimeMs) {
            startedAtUptimeMs = nowUptimeMs;
            lastObservedOesUptimeMs = 0L;
            lastRecoveryUptimeMs = 0L;
            consecutiveRecoveries = 0;
        }
    }

    private final SlotTracker[] trackers = new SlotTracker[CAMERA_COUNT];

    public SurroundCameraWatchdog() {
        for (int i = 0; i < CAMERA_COUNT; i++) {
            trackers[i] = new SlotTracker();
        }
    }

    public void reset(long nowUptimeMs) {
        for (SlotTracker tracker : trackers) {
            tracker.reset(nowUptimeMs);
        }
    }

    public RecoveryAction evaluate(int slot, long nowUptimeMs, CameraState state) {
        if (slot < 0 || slot >= CAMERA_COUNT || state == null) {
            return RecoveryAction.NONE;
        }
        if (!state.surfaceValid || state.cameraOpeningOrReconnecting) {
            return RecoveryAction.NONE;
        }

        SlotTracker tracker = trackers[slot];
        if (tracker.startedAtUptimeMs <= 0L) {
            tracker.startedAtUptimeMs = nowUptimeMs;
        }

        if (state.lastOesFrameUptimeMs > 0L && state.lastOesFrameUptimeMs > tracker.lastObservedOesUptimeMs) {
            tracker.lastObservedOesUptimeMs = state.lastOesFrameUptimeMs;
            tracker.consecutiveRecoveries = 0;
        }

        boolean stalled;
        if (state.lastOesFrameUptimeMs <= 0L) {
            stalled = (nowUptimeMs - tracker.startedAtUptimeMs) >= STARTUP_GRACE_MS;
        } else {
            stalled = (nowUptimeMs - state.lastOesFrameUptimeMs) >= OES_STALL_TIMEOUT_MS;
        }
        if (!stalled) {
            return RecoveryAction.NONE;
        }

        int shift = Math.min(3, Math.max(0, tracker.consecutiveRecoveries));
        long cooldownMs = Math.min(MAX_BACKOFF_COOLDOWN_MS, BASE_COOLDOWN_MS << shift);
        if (tracker.lastRecoveryUptimeMs > 0L && (nowUptimeMs - tracker.lastRecoveryUptimeMs) < cooldownMs) {
            return RecoveryAction.NONE;
        }

        tracker.consecutiveRecoveries++;
        tracker.lastRecoveryUptimeMs = nowUptimeMs;

        if (state.recordingActive) {
            // Never interrupt active multi-camera recording with forceReopen from surround view.
            return RecoveryAction.REBIND_AND_RECREATE_SESSION;
        }

        if (!state.cameraConnected) {
            return RecoveryAction.FORCE_REOPEN;
        }

        boolean captureAlive = state.captureAgeMs >= 0L && state.captureAgeMs < CAPTURE_ALIVE_WINDOW_MS;
        if (captureAlive && state.hasCaptureSession) {
            if (tracker.consecutiveRecoveries > MAX_REBIND_ATTEMPTS_BEFORE_REOPEN) {
                return RecoveryAction.FORCE_REOPEN;
            }
            return RecoveryAction.REBIND_AND_RECREATE_SESSION;
        }

        if (tracker.consecutiveRecoveries >= MAX_REBIND_ATTEMPTS_BEFORE_REOPEN) {
            return RecoveryAction.FORCE_REOPEN;
        }
        return RecoveryAction.REBIND_AND_RECREATE_SESSION;
    }
}
