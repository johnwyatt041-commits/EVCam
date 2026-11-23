package com.kooo.evcam.external;

import com.kooo.evcam.ui.PreviewMode;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExternalCommandRouterTest {

    @Test
    public void contractRecognizesAll13ActionsAndRejectsUnknown() {
        String[] allActions = {
                ExternalCommandContract.ACTION_TOGGLE_FOREGROUND,
                ExternalCommandContract.ACTION_MOVE_TO_FOREGROUND,
                ExternalCommandContract.ACTION_MOVE_TO_BACKGROUND,
                ExternalCommandContract.ACTION_TOGGLE_RECORDING,
                ExternalCommandContract.ACTION_START_RECORDING,
                ExternalCommandContract.ACTION_STOP_RECORDING,
                ExternalCommandContract.ACTION_TAKE_PHOTO,
                ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_A,
                ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_B,
                ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_C,
                ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_D,
                ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_E,
                ExternalCommandContract.ACTION_SWITCH_PREVIEW_MODE
        };
        assertEquals(13, allActions.length);
        for (String action : allActions) {
            assertTrue("Expected supported action: " + action,
                    ExternalCommandContract.isSupportedAction(action));
        }

        assertFalse(ExternalCommandContract.isSupportedAction(null));
        assertFalse(ExternalCommandContract.isSupportedAction(""));
        assertFalse(ExternalCommandContract.isSupportedAction("com.kooo.evcam.internal.TOGGLE_RECORDING"));
        assertFalse(ExternalCommandContract.isSupportedAction("com.kooo.evcam.action.UNKNOWN"));
    }

    @Test
    public void contractMapsFixedPreviewModeActions() {
        assertEquals(PreviewMode.MODE_A,
                ExternalCommandContract.resolveFixedPreviewMode(ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_A));
        assertEquals(PreviewMode.MODE_B,
                ExternalCommandContract.resolveFixedPreviewMode(ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_B));
        assertEquals(PreviewMode.MODE_C,
                ExternalCommandContract.resolveFixedPreviewMode(ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_C));
        assertEquals(PreviewMode.MODE_D,
                ExternalCommandContract.resolveFixedPreviewMode(ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_D));
        assertEquals(PreviewMode.MODE_E,
                ExternalCommandContract.resolveFixedPreviewMode(ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_E));

        assertNull(ExternalCommandContract.resolveFixedPreviewMode(ExternalCommandContract.ACTION_SWITCH_PREVIEW_MODE));
        assertNull(ExternalCommandContract.resolveFixedPreviewMode(ExternalCommandContract.ACTION_TOGGLE_FOREGROUND));
        assertNull(ExternalCommandContract.resolveFixedPreviewMode(null));
    }

    @Test
    public void contractParsesPreviewModeExtras() {
        assertEquals(PreviewMode.MODE_A, ExternalCommandContract.parsePreviewModeExtra("mode_a"));
        assertEquals(PreviewMode.MODE_B, ExternalCommandContract.parsePreviewModeExtra("MODE_B"));
        assertEquals(PreviewMode.MODE_C, ExternalCommandContract.parsePreviewModeExtra(" mode_c "));
        assertEquals(PreviewMode.MODE_D, ExternalCommandContract.parsePreviewModeExtra("d"));
        assertEquals(PreviewMode.MODE_E, ExternalCommandContract.parsePreviewModeExtra(" E "));

        assertEquals(PreviewMode.MODE_A, ExternalCommandContract.parsePreviewModeExtra("a"));
        assertEquals(PreviewMode.MODE_B, ExternalCommandContract.parsePreviewModeExtra("B"));
        assertEquals(PreviewMode.MODE_C, ExternalCommandContract.parsePreviewModeExtra("c"));
        assertEquals(PreviewMode.MODE_D, ExternalCommandContract.parsePreviewModeExtra("mode_d"));
        assertEquals(PreviewMode.MODE_E, ExternalCommandContract.parsePreviewModeExtra("mode_e"));

        assertNull(ExternalCommandContract.parsePreviewModeExtra(null));
        assertNull(ExternalCommandContract.parsePreviewModeExtra(""));
        assertNull(ExternalCommandContract.parsePreviewModeExtra("   "));
        assertNull(ExternalCommandContract.parsePreviewModeExtra("mode_f"));
    }

    @Test
    public void contractCyclesPreviewModesInOrder() {
        assertEquals(PreviewMode.MODE_A, ExternalCommandContract.nextPreviewMode(null));
        assertEquals(PreviewMode.MODE_B, ExternalCommandContract.nextPreviewMode(PreviewMode.MODE_A));
        assertEquals(PreviewMode.MODE_C, ExternalCommandContract.nextPreviewMode(PreviewMode.MODE_B));
        assertEquals(PreviewMode.MODE_D, ExternalCommandContract.nextPreviewMode(PreviewMode.MODE_C));
        assertEquals(PreviewMode.MODE_E, ExternalCommandContract.nextPreviewMode(PreviewMode.MODE_D));
        assertEquals(PreviewMode.MODE_A, ExternalCommandContract.nextPreviewMode(PreviewMode.MODE_E));
    }

    @Test
    public void foregroundDecisionsMatchAppState() {
        assertEquals(ExternalCommandRouter.ForegroundDecision.MOVE_TO_BACKGROUND,
                ExternalCommandRouter.decideForegroundAction(
                        ExternalCommandContract.ACTION_TOGGLE_FOREGROUND, true));
        assertEquals(ExternalCommandRouter.ForegroundDecision.LAUNCH_TO_FOREGROUND,
                ExternalCommandRouter.decideForegroundAction(
                        ExternalCommandContract.ACTION_TOGGLE_FOREGROUND, false));

        assertEquals(ExternalCommandRouter.ForegroundDecision.NO_OP,
                ExternalCommandRouter.decideForegroundAction(
                        ExternalCommandContract.ACTION_MOVE_TO_FOREGROUND, true));
        assertEquals(ExternalCommandRouter.ForegroundDecision.LAUNCH_TO_FOREGROUND,
                ExternalCommandRouter.decideForegroundAction(
                        ExternalCommandContract.ACTION_MOVE_TO_FOREGROUND, false));

        assertEquals(ExternalCommandRouter.ForegroundDecision.MOVE_TO_BACKGROUND,
                ExternalCommandRouter.decideForegroundAction(
                        ExternalCommandContract.ACTION_MOVE_TO_BACKGROUND, true));
        assertEquals(ExternalCommandRouter.ForegroundDecision.NO_OP,
                ExternalCommandRouter.decideForegroundAction(
                        ExternalCommandContract.ACTION_MOVE_TO_BACKGROUND, false));

        assertEquals(ExternalCommandRouter.ForegroundDecision.NO_OP,
                ExternalCommandRouter.decideForegroundAction(null, true));
    }

    @Test
    public void previewModeDecisionsCoverToggleBackgroundSemantics() {
        // 1. Background -> launch to foreground with target mode
        assertEquals(ExternalCommandRouter.PreviewModeDecision.LAUNCH_TO_FOREGROUND_WITH_MODE,
                ExternalCommandRouter.decidePreviewModeAction(
                        false, false, false, null, PreviewMode.MODE_E, true));

        // 2. Sub-activity foreground (MainActivity not foreground) -> launch MainActivity with target mode
        assertEquals(ExternalCommandRouter.PreviewModeDecision.LAUNCH_TO_FOREGROUND_WITH_MODE,
                ExternalCommandRouter.decidePreviewModeAction(
                        false, true, true, PreviewMode.MODE_E, PreviewMode.MODE_E, true));

        // 3. MainActivity foreground on Playback/Settings tab -> apply in place (switches to recording + mode)
        assertEquals(ExternalCommandRouter.PreviewModeDecision.APPLY_IN_PLACE,
                ExternalCommandRouter.decidePreviewModeAction(
                        true, false, true, PreviewMode.MODE_E, PreviewMode.MODE_E, true));

        // 4. MainActivity foreground on recording section, main preview disabled -> apply in place (enables preview)
        assertEquals(ExternalCommandRouter.PreviewModeDecision.APPLY_IN_PLACE,
                ExternalCommandRouter.decidePreviewModeAction(
                        true, true, false, PreviewMode.MODE_E, PreviewMode.MODE_E, true));

        // 5. MainActivity foreground on recording section, main preview enabled, different mode -> apply in place
        assertEquals(ExternalCommandRouter.PreviewModeDecision.APPLY_IN_PLACE,
                ExternalCommandRouter.decidePreviewModeAction(
                        true, true, true, PreviewMode.MODE_B, PreviewMode.MODE_E, true));

        // 6a. MainActivity foreground on recording section, main preview enabled, same mode, toggleBg=true -> background
        assertEquals(ExternalCommandRouter.PreviewModeDecision.MOVE_TO_BACKGROUND,
                ExternalCommandRouter.decidePreviewModeAction(
                        true, true, true, PreviewMode.MODE_E, PreviewMode.MODE_E, true));

        // 6b. Same state with toggleBg=false -> apply in place (no background toggle)
        assertEquals(ExternalCommandRouter.PreviewModeDecision.APPLY_IN_PLACE,
                ExternalCommandRouter.decidePreviewModeAction(
                        true, true, true, PreviewMode.MODE_E, PreviewMode.MODE_E, false));
    }

    @Test
    public void recordingDecisionsHandleShortCircuitInPlaceAndFallbacks() {
        // START_RECORDING
        assertEquals(ExternalCommandRouter.RecordingDecision.NO_OP,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_START_RECORDING, true, true, true, true));
        assertEquals(ExternalCommandRouter.RecordingDecision.START_IN_PLACE,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_START_RECORDING, true, true, false, false));
        assertEquals(ExternalCommandRouter.RecordingDecision.START_IN_PLACE,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_START_RECORDING, true, false, true, false));
        assertEquals(ExternalCommandRouter.RecordingDecision.LAUNCH_START_RECORDING,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_START_RECORDING, true, false, false, false));
        assertEquals(ExternalCommandRouter.RecordingDecision.LAUNCH_START_RECORDING,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_START_RECORDING, false, false, true, false));

        // STOP_RECORDING
        assertEquals(ExternalCommandRouter.RecordingDecision.NO_OP,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_STOP_RECORDING, true, true, true, false));
        assertEquals(ExternalCommandRouter.RecordingDecision.STOP_IN_PLACE,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_STOP_RECORDING, true, false, true, true));
        assertEquals(ExternalCommandRouter.RecordingDecision.STOP_VIA_CAMERA_HOLDER,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_STOP_RECORDING, false, false, true, true));

        // TOGGLE_RECORDING
        assertEquals(ExternalCommandRouter.RecordingDecision.STOP_IN_PLACE,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_TOGGLE_RECORDING, true, true, true, true));
        assertEquals(ExternalCommandRouter.RecordingDecision.STOP_VIA_CAMERA_HOLDER,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_TOGGLE_RECORDING, false, false, true, true));
        assertEquals(ExternalCommandRouter.RecordingDecision.START_IN_PLACE,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_TOGGLE_RECORDING, true, true, true, false));
        assertEquals(ExternalCommandRouter.RecordingDecision.LAUNCH_START_RECORDING,
                ExternalCommandRouter.decideRecordingAction(
                        ExternalCommandContract.ACTION_TOGGLE_RECORDING, false, false, false, false));
    }

    @Test
    public void photoDecisionsCoverForegroundSubActivityAndBackground() {
        assertEquals(ExternalCommandRouter.PhotoDecision.TAKE_PHOTO_IN_PLACE,
                ExternalCommandRouter.decidePhotoAction(true, true));
        assertEquals(ExternalCommandRouter.PhotoDecision.LAUNCH_TAKE_PHOTO_KEEP_FOREGROUND,
                ExternalCommandRouter.decidePhotoAction(false, true));
        assertEquals(ExternalCommandRouter.PhotoDecision.LAUNCH_TAKE_PHOTO_AND_RETURN_BACKGROUND,
                ExternalCommandRouter.decidePhotoAction(false, false));
    }

    @Test
    public void debounceDropsDuplicatesPerActionWindow() {
        AtomicLong now = new AtomicLong(10_000L);
        ExternalCommandRouter router = new ExternalCommandRouter(now::get);

        // UI / view actions use 400ms window
        assertFalse(router.shouldDropDuplicate(ExternalCommandContract.ACTION_TOGGLE_FOREGROUND));
        now.addAndGet(399L);
        assertTrue(router.shouldDropDuplicate(ExternalCommandContract.ACTION_TOGGLE_FOREGROUND));
        // Different action allowed immediately
        assertFalse(router.shouldDropDuplicate(ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_A));
        // Original action allowed once 400ms elapses
        now.addAndGet(1L);
        assertFalse(router.shouldDropDuplicate(ExternalCommandContract.ACTION_TOGGLE_FOREGROUND));

        // Recording / photo actions use 1000ms window
        assertFalse(router.shouldDropDuplicate(ExternalCommandContract.ACTION_TAKE_PHOTO));
        now.addAndGet(999L);
        assertTrue(router.shouldDropDuplicate(ExternalCommandContract.ACTION_TAKE_PHOTO));
        assertFalse(router.shouldDropDuplicate(ExternalCommandContract.ACTION_TOGGLE_RECORDING));
        now.addAndGet(1L);
        assertFalse(router.shouldDropDuplicate(ExternalCommandContract.ACTION_TAKE_PHOTO));
    }

    @Test
    public void startupOverrideGuardMatchesExternalCommandPresence() {
        assertFalse(ExternalCommandContract.shouldSkipStartupPageOverrides(null));
        assertFalse(ExternalCommandContract.shouldSkipStartupPageOverrides(""));
        assertTrue(ExternalCommandContract.shouldSkipStartupPageOverrides(
                ExternalCommandContract.CMD_OPEN_PREVIEW_MODE));
        assertTrue(ExternalCommandContract.shouldSkipStartupPageOverrides(
                ExternalCommandContract.CMD_TAKE_PHOTO));
    }
}
