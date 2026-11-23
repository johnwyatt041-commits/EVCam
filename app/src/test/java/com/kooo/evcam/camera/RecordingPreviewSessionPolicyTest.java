package com.kooo.evcam.camera;

import com.kooo.evcam.camera.RecordingPreviewSessionPolicy.AbandonedSurfaceTarget;
import com.kooo.evcam.camera.RecordingPreviewSessionPolicy.PreviewAction;
import com.kooo.evcam.camera.RecordingPreviewSessionPolicy.SessionSnapshot;
import com.kooo.evcam.camera.RecordingPreviewSessionPolicy.SurroundClearAction;

import org.junit.Test;

import static org.junit.Assert.*;

public class RecordingPreviewSessionPolicyTest {

    private static SessionSnapshot snapshot(
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
        return new SessionSnapshot(
                cameraConnected,
                hasActiveSession,
                sessionBusy,
                hasActiveRecordSurface,
                recordSurfaceConfiguredInSession,
                singleOutputMode,
                mainPreviewEnabled,
                hasAvailableTexture,
                previewSurfaceMatchesSession,
                previewInRepeating,
                hasHealthySharedPreviewConfig,
                hasAnyOtherActiveOutput
        );
    }

    @Test
    public void recordingMainPreviewToggleUsesRepeatingTargetsWithoutSessionRebuild() {
        // Mode D hides an active preview pane during recording -> update repeating targets only.
        SessionSnapshot hideDuringRecording = snapshot(
                true, true, false, true, true, false,
                false, true, true, true, true, false);
        assertEquals(PreviewAction.UPDATE_REPEATING_TARGETS,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(hideDuringRecording));

        // Redundant hide when preview is already excluded from repeating -> no-op.
        SessionSnapshot alreadyPaused = snapshot(
                true, true, false, true, true, false,
                false, true, true, false, true, false);
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(alreadyPaused));

        // Mode D restores same TextureView -> update repeating targets to re-include previewSurface.
        SessionSnapshot resumeSameTexture = snapshot(
                true, true, false, true, true, false,
                true, true, true, false, true, false);
        assertEquals(PreviewAction.UPDATE_REPEATING_TARGETS,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(resumeSameTexture));

        // Already repeating with same preview surface -> no-op.
        SessionSnapshot alreadyShowing = snapshot(
                true, true, false, true, true, false,
                true, true, true, true, true, false);
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(alreadyShowing));

        // Main preview enabled onResume before TextureView.isAvailable() -> wait for surface without rebuild.
        SessionSnapshot resumeBeforeTextureAvailable = snapshot(
                true, true, false, true, true, false,
                true, false, false, false, false, false);
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(resumeBeforeTextureAvailable));
    }

    @Test
    public void recordingNewTexturePrefersDynamicAddOnlyWhenSharedConfigIsHealthy() {
        // Background recording with healthy blind-spot/stream shared config -> dynamic add preview.
        SessionSnapshot healthyShared = snapshot(
                true, true, false, true, true, false,
                true, true, false, false, true, true);
        assertEquals(PreviewAction.DYNAMIC_ADD_PREVIEW_OR_RECREATE,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(healthyShared));

        // Old primary preview died in background -> shared config unhealthy -> clean single rebuild.
        SessionSnapshot deadPrimary = snapshot(
                true, true, false, true, true, false,
                true, true, false, false, false, false);
        assertEquals(PreviewAction.RECREATE_SESSION,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(deadPrimary));
    }

    @Test
    public void recordingBusyStartupAndSingleOutputPathsRespectBoundaries() {
        // Busy session with preview disabled: onConfigured drops target, no extra recreate.
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        true, false, true, true, true, false,
                        false, true, false, true, true, false)));
        // Busy session with brand-new texture needing configuration -> schedule pending recreate.
        assertEquals(PreviewAction.RECREATE_SESSION,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        true, false, true, true, true, false,
                        true, true, false, false, false, false)));

        // Single-output mode ignores preview toggles while recording.
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        true, true, false, true, true, true,
                        true, true, false, false, false, false)));

        // Startup window (setRecordSurface called, session not yet configured with recordSurface):
        // do not interfere with pending recreate or run recording repeating against old session.
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        true, true, false, true, false, false,
                        false, true, true, true, true, false)));
        assertEquals(PreviewAction.RECREATE_SESSION,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        true, false, false, true, false, false,
                        false, true, false, false, false, false)));

        // Startup window while in-flight configuration (sessionBusy=true):
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        true, false, true, true, false, false,
                        false, true, false, false, false, false)));
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        true, false, true, true, false, false,
                        true, true, true, false, false, false)));
        assertEquals(PreviewAction.RECREATE_SESSION,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        true, false, true, true, false, false,
                        true, true, false, false, false, false)));
    }

    @Test
    public void nonRecordingStatesPreserveCloseOpenAndAuxiliaryOutputs() {
        assertEquals(PreviewAction.CLOSE_CAMERA,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        true, true, false, false, false, false,
                        false, true, true, true, true, false)));

        assertEquals(PreviewAction.OPEN_CAMERA,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        false, false, false, false, false, false,
                        true, true, false, false, false, false)));

        // Non-recording with blind spot alive and preview already excluded -> no-op.
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        true, true, false, false, false, false,
                        false, true, false, false, true, true)));

        // Non-recording with blind spot alive while preview was repeating -> recreate to drop preview.
        assertEquals(PreviewAction.RECREATE_SESSION,
                RecordingPreviewSessionPolicy.decideOnMainPreviewChanged(snapshot(
                        true, true, false, false, false, false,
                        false, true, true, true, true, true)));
    }

    @Test
    public void previewTextureDestroyedDecisionsCoverRecordingAndIdle() {
        // Unused camera closes when TextureView is destroyed without recording or aux surfaces.
        assertEquals(PreviewAction.CLOSE_CAMERA,
                RecordingPreviewSessionPolicy.decideOnPreviewTextureDestroyed(snapshot(
                        true, true, false, false, false, false,
                        true, true, true, true, true, false)));

        // Recording active with preview repeating -> immediately drop preview target without closing session.
        assertEquals(PreviewAction.UPDATE_REPEATING_TARGETS,
                RecordingPreviewSessionPolicy.decideOnPreviewTextureDestroyed(snapshot(
                        true, true, false, true, true, false,
                        true, true, true, true, true, false)));

        // Recording active while preview was already paused -> no repeating update needed.
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnPreviewTextureDestroyed(snapshot(
                        true, true, false, true, true, false,
                        false, true, true, false, true, false)));

        // Recording session currently configuring -> onConfigured handles detached preview target.
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnPreviewTextureDestroyed(snapshot(
                        true, false, true, true, true, false,
                        true, true, false, true, true, false)));

        // Non-recording with auxiliary blind-spot/HUD -> recreate to detach destroyed preview.
        assertEquals(PreviewAction.RECREATE_SESSION,
                RecordingPreviewSessionPolicy.decideOnPreviewTextureDestroyed(snapshot(
                        true, true, false, false, false, false,
                        true, true, true, true, true, true)));
    }

    @Test
    public void surroundModeATransitionsIsolateRecordingSession() {
        // Entering Mode A during recording from Mode B/D: dynamically add surround even when disabling main preview.
        SessionSnapshot recordingWithSharedPreview = snapshot(
                true, true, false, true, true, false,
                false, true, true, true, true, true);
        assertEquals(PreviewAction.DYNAMIC_ADD_PREVIEW_OR_RECREATE,
                RecordingPreviewSessionPolicy.decideOnSurroundSurfaceAdded(
                        recordingWithSharedPreview, false, true));

        // Exiting Mode A during recording when surround was primary (index 0): update repeating without stop/close.
        SessionSnapshot recordingSurroundCleared = snapshot(
                true, true, false, true, true, false,
                false, false, false, false, true, false);
        assertEquals(SurroundClearAction.UPDATE_REPEATING_TARGETS,
                RecordingPreviewSessionPolicy.decideOnSurroundSurfaceCleared(recordingSurroundCleared, true));

        // Exiting Mode A during recording when surround was shared child: dynamic remove or repeating update.
        assertEquals(SurroundClearAction.DYNAMIC_REMOVE_OR_UPDATE_REPEATING,
                RecordingPreviewSessionPolicy.decideOnSurroundSurfaceCleared(recordingSurroundCleared, false));

        // Exiting Mode A without recording or other outputs -> close camera.
        SessionSnapshot idleSurroundCleared = snapshot(
                true, true, false, false, false, false,
                false, false, false, false, true, false);
        assertEquals(SurroundClearAction.CLOSE_CAMERA,
                RecordingPreviewSessionPolicy.decideOnSurroundSurfaceCleared(idleSurroundCleared, true));

        // B1: Duplicate surround bind for same in-flight surface while session is closing/configuring is NO_OP,
        // unless disabling main preview atomically or binding a distinct new request.
        SessionSnapshot busyRecording = snapshot(
                true, false, true, true, true, false,
                false, true, false, false, true, false);
        assertEquals(PreviewAction.NO_OP,
                RecordingPreviewSessionPolicy.decideOnSurroundSurfaceAdded(busyRecording, false, true, false));
        assertEquals(PreviewAction.RECREATE_SESSION,
                RecordingPreviewSessionPolicy.decideOnSurroundSurfaceAdded(busyRecording, false, true, true));
        assertEquals(PreviewAction.RECREATE_SESSION,
                RecordingPreviewSessionPolicy.decideOnSurroundSurfaceAdded(busyRecording, false, false, false));
    }

    @Test
    public void abandonedSurfaceSelectionProtectsHealthyRecordSurface() {
        // Explicitly invalid preview is cleared before anything else.
        assertEquals(AbandonedSurfaceTarget.PREVIEW,
                RecordingPreviewSessionPolicy.chooseAbandonedSurfaceToClear(
                        true, false, true, true, true, true, true, true, true, true, true, true, true, true));

        // When all surfaces claim valid, healthy recordSurface is still protected by clearing non-record surfaces first.
        assertEquals(AbandonedSurfaceTarget.PREVIEW,
                RecordingPreviewSessionPolicy.chooseAbandonedSurfaceToClear(
                        true, true, false, false, false, false, false, false, false, false, false, false, true, true));
        assertEquals(AbandonedSurfaceTarget.FULLSCREEN_PREVIEW,
                RecordingPreviewSessionPolicy.chooseAbandonedSurfaceToClear(
                        false, false, false, false, false, false, true, true, false, false, true, true, true, true));

        // Explicitly invalid recordSurface is cleared even if non-record surfaces are valid.
        assertEquals(AbandonedSurfaceTarget.RECORD,
                RecordingPreviewSessionPolicy.chooseAbandonedSurfaceToClear(
                        true, true, false, false, false, false, false, false, false, false, false, false, true, false));

        // Only recordSurface present -> falls back to RECORD.
        assertEquals(AbandonedSurfaceTarget.RECORD,
                RecordingPreviewSessionPolicy.chooseAbandonedSurfaceToClear(
                        false, false, false, false, false, false, false, false, false, false, false, false, true, true));
    }
}
