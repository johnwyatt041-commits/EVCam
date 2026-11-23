package com.kooo.evcam.camera;

/**
 * Pure decision policy for isolating Camera2 CaptureSession rebuilds during active recording.
 * Evaluates whether a preview lifecycle change (Mode D pane toggle, foreground/background transition,
 * TextureView destruction, or 360 Mode A switch) can be satisfied by updating RepeatingRequest
 * targets or dynamic OutputConfiguration instead of tearing down the active recording session.
 */
public final class RecordingPreviewSessionPolicy {

    public enum PreviewAction {
        NO_OP,
        CLOSE_CAMERA,
        OPEN_CAMERA,
        UPDATE_REPEATING_TARGETS,
        DYNAMIC_ADD_PREVIEW_OR_RECREATE,
        RECREATE_SESSION
    }

    public enum SurroundClearAction {
        NO_OP,
        CLOSE_CAMERA,
        DYNAMIC_REMOVE_OR_UPDATE_REPEATING,
        UPDATE_REPEATING_TARGETS,
        RECREATE_SESSION
    }

    public enum AbandonedSurfaceTarget {
        NONE,
        PREVIEW,
        SECONDARY_DISPLAY,
        MAIN_FLOATING,
        FULLSCREEN_PREVIEW,
        SURROUND_PREVIEW,
        STREAM,
        RECORD
    }

    public static final class SessionSnapshot {
        public final boolean cameraConnected;
        public final boolean hasActiveSession;
        public final boolean sessionBusy;
        public final boolean hasActiveRecordSurface;
        public final boolean recordSurfaceConfiguredInSession;
        public final boolean singleOutputMode;
        public final boolean mainPreviewEnabled;
        public final boolean hasAvailableTexture;
        public final boolean previewSurfaceMatchesSession;
        public final boolean previewInRepeating;
        public final boolean hasHealthySharedPreviewConfig;
        public final boolean hasAnyOtherActiveOutput;

        public SessionSnapshot(
                boolean cameraConnected,
                boolean hasActiveSession,
                boolean sessionBusy,
                boolean hasActiveRecordSurface,
                boolean recordSurfaceConfiguredInSession,
                boolean singleOutputMode,
                boolean mainPreviewEnabled,
                boolean hasAvailableTexture,
                boolean previewSurfaceMatchesSession,
                boolean previewInRepeating,
                boolean hasHealthySharedPreviewConfig,
                boolean hasAnyOtherActiveOutput) {
            this.cameraConnected = cameraConnected;
            this.hasActiveSession = hasActiveSession;
            this.sessionBusy = sessionBusy;
            this.hasActiveRecordSurface = hasActiveRecordSurface;
            this.recordSurfaceConfiguredInSession = recordSurfaceConfiguredInSession;
            this.singleOutputMode = singleOutputMode;
            this.mainPreviewEnabled = mainPreviewEnabled;
            this.hasAvailableTexture = hasAvailableTexture;
            this.previewSurfaceMatchesSession = previewSurfaceMatchesSession;
            this.previewInRepeating = previewInRepeating;
            this.hasHealthySharedPreviewConfig = hasHealthySharedPreviewConfig;
            this.hasAnyOtherActiveOutput = hasAnyOtherActiveOutput;
        }
    }

    private RecordingPreviewSessionPolicy() {
    }

    public static boolean hasAnyActiveOutput(SessionSnapshot s) {
        if (s == null) {
            return false;
        }
        return s.hasActiveRecordSurface
                || s.hasAnyOtherActiveOutput
                || (s.mainPreviewEnabled && s.hasAvailableTexture);
    }

    public static PreviewAction decideOnMainPreviewChanged(SessionSnapshot s) {
        if (s == null) {
            return PreviewAction.NO_OP;
        }
        if (!hasAnyActiveOutput(s)) {
            return PreviewAction.CLOSE_CAMERA;
        }
        if (!s.cameraConnected) {
            return (s.mainPreviewEnabled && s.hasAvailableTexture)
                    ? PreviewAction.OPEN_CAMERA
                    : PreviewAction.NO_OP;
        }
        if (s.singleOutputMode && s.hasActiveRecordSurface) {
            return PreviewAction.NO_OP;
        }
        if (s.hasActiveRecordSurface && !s.recordSurfaceConfiguredInSession) {
            // Startup window: setRecordSurface() was called, waiting for or executing initial recording recreateSession().
            if (s.sessionBusy) {
                if (s.mainPreviewEnabled && s.hasAvailableTexture && !s.previewSurfaceMatchesSession) {
                    return PreviewAction.RECREATE_SESSION;
                }
                return PreviewAction.NO_OP;
            }
            return s.hasActiveSession
                    ? PreviewAction.NO_OP
                    : PreviewAction.RECREATE_SESSION;
        }
        if (s.recordSurfaceConfiguredInSession) {
            if (s.sessionBusy) {
                if (!s.mainPreviewEnabled || !s.hasAvailableTexture || s.previewSurfaceMatchesSession) {
                    return PreviewAction.NO_OP;
                }
                return PreviewAction.RECREATE_SESSION;
            }
            if (!s.hasActiveSession) {
                return PreviewAction.RECREATE_SESSION;
            }
            if (!s.mainPreviewEnabled) {
                return s.previewInRepeating
                        ? PreviewAction.UPDATE_REPEATING_TARGETS
                        : PreviewAction.NO_OP;
            }
            if (!s.hasAvailableTexture) {
                return PreviewAction.NO_OP;
            }
            if (s.previewSurfaceMatchesSession) {
                return s.previewInRepeating
                        ? PreviewAction.NO_OP
                        : PreviewAction.UPDATE_REPEATING_TARGETS;
            }
            return s.hasHealthySharedPreviewConfig
                    ? PreviewAction.DYNAMIC_ADD_PREVIEW_OR_RECREATE
                    : PreviewAction.RECREATE_SESSION;
        }

        // Non-recording steady state
        if (s.mainPreviewEnabled) {
            if (!s.hasAvailableTexture) {
                return s.hasActiveSession ? PreviewAction.NO_OP : PreviewAction.RECREATE_SESSION;
            }
            if (s.hasActiveSession && s.previewSurfaceMatchesSession && s.previewInRepeating) {
                return PreviewAction.NO_OP;
            }
            return PreviewAction.RECREATE_SESSION;
        }
        if (s.previewInRepeating) {
            return PreviewAction.RECREATE_SESSION;
        }
        if (!s.hasActiveSession && s.hasAnyOtherActiveOutput) {
            return PreviewAction.RECREATE_SESSION;
        }
        return PreviewAction.NO_OP;
    }

    public static PreviewAction decideOnPreviewTextureDestroyed(SessionSnapshot s) {
        if (s == null) {
            return PreviewAction.NO_OP;
        }
        if (!s.hasActiveRecordSurface && !s.hasAnyOtherActiveOutput) {
            return PreviewAction.CLOSE_CAMERA;
        }
        if (s.singleOutputMode && s.hasActiveRecordSurface) {
            return PreviewAction.NO_OP;
        }
        if (s.recordSurfaceConfiguredInSession) {
            if (s.sessionBusy) {
                return PreviewAction.NO_OP;
            }
            if (s.hasActiveSession && s.previewInRepeating) {
                return PreviewAction.UPDATE_REPEATING_TARGETS;
            }
            return PreviewAction.NO_OP;
        }
        if (s.hasActiveRecordSurface) {
            return (s.hasActiveSession && s.previewInRepeating)
                    ? PreviewAction.RECREATE_SESSION
                    : PreviewAction.NO_OP;
        }
        if (s.hasAnyOtherActiveOutput) {
            return (s.hasActiveSession && s.previewInRepeating)
                    ? PreviewAction.RECREATE_SESSION
                    : PreviewAction.NO_OP;
        }
        return PreviewAction.NO_OP;
    }

    public static PreviewAction decideOnSurroundSurfaceAdded(
            SessionSnapshot s,
            boolean alreadyConfigured,
            boolean mainPreviewDisabledAtomically) {
        return decideOnSurroundSurfaceAdded(s, alreadyConfigured, false, mainPreviewDisabledAtomically);
    }

    public static PreviewAction decideOnSurroundSurfaceAdded(
            SessionSnapshot s,
            boolean alreadyConfigured,
            boolean alreadyInFlightForSameSurface,
            boolean mainPreviewDisabledAtomically) {
        if (s == null) {
            return PreviewAction.NO_OP;
        }
        if (!s.cameraConnected) {
            return PreviewAction.OPEN_CAMERA;
        }
        if (s.sessionBusy) {
            if (alreadyInFlightForSameSurface && !mainPreviewDisabledAtomically) {
                return PreviewAction.NO_OP;
            }
            return PreviewAction.RECREATE_SESSION;
        }
        if (!s.hasActiveSession) {
            return PreviewAction.RECREATE_SESSION;
        }
        if (alreadyConfigured) {
            return (mainPreviewDisabledAtomically && s.previewInRepeating)
                    ? PreviewAction.UPDATE_REPEATING_TARGETS
                    : PreviewAction.NO_OP;
        }
        if (s.hasHealthySharedPreviewConfig
                && (s.recordSurfaceConfiguredInSession || !mainPreviewDisabledAtomically)) {
            return PreviewAction.DYNAMIC_ADD_PREVIEW_OR_RECREATE;
        }
        return PreviewAction.RECREATE_SESSION;
    }

    public static SurroundClearAction decideOnSurroundSurfaceCleared(
            SessionSnapshot s,
            boolean oldSurfaceWasPrimary) {
        if (s == null) {
            return SurroundClearAction.NO_OP;
        }
        if (!hasAnyActiveOutput(s)) {
            return SurroundClearAction.CLOSE_CAMERA;
        }
        if (s.recordSurfaceConfiguredInSession) {
            if (s.sessionBusy) {
                return SurroundClearAction.NO_OP;
            }
            if (!s.hasActiveSession) {
                return SurroundClearAction.RECREATE_SESSION;
            }
            if (oldSurfaceWasPrimary) {
                return SurroundClearAction.UPDATE_REPEATING_TARGETS;
            }
            return s.hasHealthySharedPreviewConfig
                    ? SurroundClearAction.DYNAMIC_REMOVE_OR_UPDATE_REPEATING
                    : SurroundClearAction.UPDATE_REPEATING_TARGETS;
        }
        if (!oldSurfaceWasPrimary && s.hasActiveSession && !s.sessionBusy && s.hasHealthySharedPreviewConfig) {
            return SurroundClearAction.DYNAMIC_REMOVE_OR_UPDATE_REPEATING;
        }
        return SurroundClearAction.RECREATE_SESSION;
    }

    public static AbandonedSurfaceTarget chooseAbandonedSurfaceToClear(
            boolean hasPreview, boolean previewValid,
            boolean hasSecondary, boolean secondaryValid,
            boolean hasMainFloating, boolean mainFloatingValid,
            boolean hasFullscreen, boolean fullscreenValid,
            boolean hasSurround, boolean surroundValid,
            boolean hasStream, boolean streamValid,
            boolean hasRecord, boolean recordValid) {
        // 1. Prefer clearing any explicitly invalid non-record surface first.
        if (hasPreview && !previewValid) return AbandonedSurfaceTarget.PREVIEW;
        if (hasSecondary && !secondaryValid) return AbandonedSurfaceTarget.SECONDARY_DISPLAY;
        if (hasMainFloating && !mainFloatingValid) return AbandonedSurfaceTarget.MAIN_FLOATING;
        if (hasFullscreen && !fullscreenValid) return AbandonedSurfaceTarget.FULLSCREEN_PREVIEW;
        if (hasSurround && !surroundValid) return AbandonedSurfaceTarget.SURROUND_PREVIEW;
        if (hasStream && !streamValid) return AbandonedSurfaceTarget.STREAM;

        // 2. If recordSurface itself is invalid while all non-record surfaces are valid, clear record.
        if (hasRecord && !recordValid) return AbandonedSurfaceTarget.RECORD;

        // 3. Otherwise keep healthy recordSurface protected (P002) and clear non-record surfaces first.
        if (hasPreview) return AbandonedSurfaceTarget.PREVIEW;
        if (hasSecondary) return AbandonedSurfaceTarget.SECONDARY_DISPLAY;
        if (hasMainFloating) return AbandonedSurfaceTarget.MAIN_FLOATING;
        if (hasFullscreen) return AbandonedSurfaceTarget.FULLSCREEN_PREVIEW;
        if (hasSurround) return AbandonedSurfaceTarget.SURROUND_PREVIEW;
        if (hasStream) return AbandonedSurfaceTarget.STREAM;

        // 4. Only if no non-record surface remains, fall back to recordSurface.
        if (hasRecord) return AbandonedSurfaceTarget.RECORD;
        return AbandonedSurfaceTarget.NONE;
    }
}
