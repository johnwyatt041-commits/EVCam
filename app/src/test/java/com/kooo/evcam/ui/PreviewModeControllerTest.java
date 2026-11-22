package com.kooo.evcam.ui;

import com.kooo.evcam.AppConfig;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

public class PreviewModeControllerTest {

    @Test
    public void modeDSideCameraSwitchesLeftAndRightWithDeadbandHysteresis() {
        // Negative steering angle -> left turn -> LEFT camera
        assertEquals(PreviewLayoutModel.LEFT,
                PreviewModeController.resolveModeDSideCamera(-5.0f, PreviewLayoutModel.RIGHT,
                        PreviewModeController.MODE_D_STEER_DEADBAND_DEG));
        // Positive steering angle -> right turn -> RIGHT camera
        assertEquals(PreviewLayoutModel.RIGHT,
                PreviewModeController.resolveModeDSideCamera(4.5f, PreviewLayoutModel.LEFT,
                        PreviewModeController.MODE_D_STEER_DEADBAND_DEG));
        // Inside deadband (-1.2, +1.2) -> preserves current side to avoid flicker
        assertEquals(PreviewLayoutModel.RIGHT,
                PreviewModeController.resolveModeDSideCamera(0.4f, PreviewLayoutModel.RIGHT,
                        PreviewModeController.MODE_D_STEER_DEADBAND_DEG));
        assertEquals(PreviewLayoutModel.LEFT,
                PreviewModeController.resolveModeDSideCamera(-0.8f, PreviewLayoutModel.LEFT,
                        PreviewModeController.MODE_D_STEER_DEADBAND_DEG));
        // NaN/Infinity safety falls back to valid side
        assertEquals(PreviewLayoutModel.RIGHT,
                PreviewModeController.resolveModeDSideCamera(Float.NaN, PreviewLayoutModel.RIGHT,
                        PreviewModeController.MODE_D_STEER_DEADBAND_DEG));
    }

    @Test
    public void modeDSteeringSubscriptionRunsOnlyInForegroundWithMainPreviewEnabled() {
        assertTrue(PreviewModeController.shouldSubscribeSteeringForModeD(PreviewMode.MODE_D, true, true));
        assertFalse(PreviewModeController.shouldSubscribeSteeringForModeD(PreviewMode.MODE_D, false, true));
        assertFalse(PreviewModeController.shouldSubscribeSteeringForModeD(PreviewMode.MODE_D, true, false));
        assertFalse(PreviewModeController.shouldSubscribeSteeringForModeD(PreviewMode.MODE_C, true, true));
        assertFalse(PreviewModeController.shouldSubscribeSteeringForModeD(PreviewMode.MODE_B, true, true));
        assertFalse(PreviewModeController.shouldSubscribeSteeringForModeD(PreviewMode.MODE_A, true, true));
    }

    @Test
    public void modeDSideLinkageModesSubscribeMutuallyExclusivelyAndTurnSignalResolvesSideWithoutFlicker() {
        assertEquals(AppConfig.MODE_D_SIDE_LINKAGE_STEERING, AppConfig.normalizeModeDSideLinkageMode(null));
        assertEquals(AppConfig.MODE_D_SIDE_LINKAGE_STEERING, AppConfig.normalizeModeDSideLinkageMode("other"));
        assertEquals(AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL,
                AppConfig.normalizeModeDSideLinkageMode(AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL));
        assertEquals(AppConfig.MODE_D_SIDE_LINKAGE_MANUAL,
                AppConfig.normalizeModeDSideLinkageMode(AppConfig.MODE_D_SIDE_LINKAGE_MANUAL));

        // 随方向盘：仅开方向盘订阅
        assertTrue(PreviewModeController.shouldSubscribeSteeringForModeD(
                PreviewMode.MODE_D, true, true, AppConfig.MODE_D_SIDE_LINKAGE_STEERING));
        assertFalse(PreviewModeController.shouldSubscribeTurnSignalForModeD(
                PreviewMode.MODE_D, true, true, AppConfig.MODE_D_SIDE_LINKAGE_STEERING));

        // 随转向灯：仅开转向灯订阅，不占方向盘连接
        assertFalse(PreviewModeController.shouldSubscribeSteeringForModeD(
                PreviewMode.MODE_D, true, true, AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL));
        assertTrue(PreviewModeController.shouldSubscribeTurnSignalForModeD(
                PreviewMode.MODE_D, true, true, AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL));
        assertFalse(PreviewModeController.shouldSubscribeTurnSignalForModeD(
                PreviewMode.MODE_D, false, true, AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL));
        assertFalse(PreviewModeController.shouldSubscribeTurnSignalForModeD(
                PreviewMode.MODE_C, true, true, AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL));

        // 手动切换：两者均不订阅
        assertFalse(PreviewModeController.shouldSubscribeSteeringForModeD(
                PreviewMode.MODE_D, true, true, AppConfig.MODE_D_SIDE_LINKAGE_MANUAL));
        assertFalse(PreviewModeController.shouldSubscribeTurnSignalForModeD(
                PreviewMode.MODE_D, true, true, AppConfig.MODE_D_SIDE_LINKAGE_MANUAL));

        // 转向灯决议：左灯->LEFT，右灯->RIGHT，熄灭/双闪/未知保持原侧
        assertEquals(PreviewLayoutModel.LEFT,
                PreviewModeController.resolveModeDSideCameraFromTurnSignal(1, 0, PreviewLayoutModel.RIGHT));
        assertEquals(PreviewLayoutModel.RIGHT,
                PreviewModeController.resolveModeDSideCameraFromTurnSignal(0, 1, PreviewLayoutModel.LEFT));
        assertEquals(PreviewLayoutModel.RIGHT,
                PreviewModeController.resolveModeDSideCameraFromTurnSignal(0, 0, PreviewLayoutModel.RIGHT));
        assertEquals(PreviewLayoutModel.LEFT,
                PreviewModeController.resolveModeDSideCameraFromTurnSignal(1, 1, PreviewLayoutModel.LEFT));
        assertEquals(PreviewLayoutModel.RIGHT,
                PreviewModeController.resolveModeDSideCameraFromTurnSignal(-1, -1, PreviewLayoutModel.RIGHT));
    }

    @Test
    public void modeAOffPreviewShowsDisabledGuideInsteadOfFallingBackToModeB() {
        assertTrue(PreviewModeController.shouldShowModeAPreviewDisabledGuide(PreviewMode.MODE_A, false));
        assertFalse(PreviewModeController.shouldShowModeAPreviewDisabledGuide(PreviewMode.MODE_A, true));
        assertFalse(PreviewModeController.shouldShowModeAPreviewDisabledGuide(PreviewMode.MODE_B, false));
        assertFalse(PreviewModeController.shouldShowModeAPreviewDisabledGuide(PreviewMode.MODE_D, false));
    }

    @Test
    public void cornerNavAndViewShortcutsRespectPolicyFullscreenAndEditing() {
        // Editing always hides overlays cleanly.
        assertFalse(PreviewModeController.shouldShowCornerNav(AppConfig.CORNER_NAV_ALWAYS, true, true));
        assertFalse(PreviewModeController.shouldShowViewShortcuts(true, true));

        // Auto corner nav shows only in fullscreen.
        assertFalse(PreviewModeController.shouldShowCornerNav(AppConfig.CORNER_NAV_AUTO, false, false));
        assertTrue(PreviewModeController.shouldShowCornerNav(AppConfig.CORNER_NAV_AUTO, true, false));

        // Always and Hidden policies.
        assertTrue(PreviewModeController.shouldShowCornerNav(AppConfig.CORNER_NAV_ALWAYS, false, false));
        assertTrue(PreviewModeController.shouldShowCornerNav(AppConfig.CORNER_NAV_ALWAYS, true, false));
        assertFalse(PreviewModeController.shouldShowCornerNav(AppConfig.CORNER_NAV_HIDDEN, false, false));
        assertFalse(PreviewModeController.shouldShowCornerNav(AppConfig.CORNER_NAV_HIDDEN, true, false));

        // View switch shortcuts toggle.
        assertTrue(PreviewModeController.shouldShowViewShortcuts(true, false));
        assertFalse(PreviewModeController.shouldShowViewShortcuts(false, false));
    }

    @Test
    public void cameraVisibilityMapAndHiddenKeyExtractionMatchPaneIndices() {
        boolean[] modeCFront = {true, true, false, true}; // LEFT, FRONT, !BACK, RIGHT
        Map<String, Boolean> map = PreviewModeController.toCameraVisibilityMap(modeCFront);
        assertEquals(Boolean.TRUE, map.get("left"));
        assertEquals(Boolean.TRUE, map.get("front"));
        assertEquals(Boolean.FALSE, map.get("back"));
        assertEquals(Boolean.TRUE, map.get("right"));
        assertEquals(Collections.singletonList("back"),
                PreviewModeController.hiddenKeysFromMask(modeCFront));

        boolean[] onlyFront = {false, true, false, false};
        assertEquals(Arrays.asList("back", "left", "right"),
                PreviewModeController.hiddenKeysFromMask(onlyFront));
    }

    @Test
    public void modeAActivatesGlOnlyWhenHostEligibleAndMainPreviewEnabled() {
        assertEquals(PreviewMode.MODE_A, PreviewModeController.normalizeSupportedMode(PreviewMode.MODE_A));
        assertEquals(PreviewMode.MODE_D, PreviewModeController.normalizeSupportedMode(PreviewMode.MODE_D));
        assertEquals(PreviewMode.MODE_B, PreviewModeController.normalizeSupportedMode(null));

        assertTrue(PreviewModeController.shouldActivateSurroundGl(PreviewMode.MODE_A, true, true));
        assertFalse(PreviewModeController.shouldActivateSurroundGl(PreviewMode.MODE_B, true, true));
        assertFalse(PreviewModeController.shouldActivateSurroundGl(PreviewMode.MODE_C, true, true));
        assertFalse(PreviewModeController.shouldActivateSurroundGl(PreviewMode.MODE_D, true, true));
        assertFalse(PreviewModeController.shouldActivateSurroundGl(PreviewMode.MODE_A, false, true));
        assertFalse(PreviewModeController.shouldActivateSurroundGl(PreviewMode.MODE_A, true, false));

        assertTrue(PreviewModeController.shouldHideOrdinaryTextureViewsForModeA(PreviewMode.MODE_A, true));
        assertFalse(PreviewModeController.shouldHideOrdinaryTextureViewsForModeA(PreviewMode.MODE_A, false));
        assertFalse(PreviewModeController.shouldHideOrdinaryTextureViewsForModeA(PreviewMode.MODE_B, true));

        // Mode A visibility mask is deferred until GLThread finishes creating surround OES surfaces.
        assertTrue(PreviewModeController.shouldDeferCameraVisibilityMaskForModeA(true, false));
        assertFalse(PreviewModeController.shouldDeferCameraVisibilityMaskForModeA(true, true));
        assertFalse(PreviewModeController.shouldDeferCameraVisibilityMaskForModeA(false, false));

        // Background Settings/Playback pages defer camera visibility mask churn until returning to recording.
        assertTrue(PreviewModeController.shouldApplyVisibilityMaskImmediately(true));
        assertFalse(PreviewModeController.shouldApplyVisibilityMaskImmediately(false));

        // onResume only starts surround host eligibility when foreground AND on recording section.
        assertTrue(PreviewModeController.shouldStartSurroundHostEligible(false, true));
        assertFalse(PreviewModeController.shouldStartSurroundHostEligible(false, false));
        assertFalse(PreviewModeController.shouldStartSurroundHostEligible(true, true));
        assertFalse(PreviewModeController.shouldStartSurroundHostEligible(true, false));
    }

    @Test
    public void roadGuidanceWidthAndOverlayAlphaNormalizersAndColorScalingWorkCorrectly() {
        assertEquals(AppConfig.MIN_SURROUND_ROAD_GUIDANCE_WIDTH_PERCENT,
                AppConfig.normalizeSurroundRoadGuidanceWidthPercent(10));
        assertEquals(AppConfig.MAX_SURROUND_ROAD_GUIDANCE_WIDTH_PERCENT,
                AppConfig.normalizeSurroundRoadGuidanceWidthPercent(250));
        assertEquals(115, AppConfig.normalizeSurroundRoadGuidanceWidthPercent(115));

        assertEquals(AppConfig.MIN_PREVIEW_OVERLAY_BG_ALPHA_PERCENT,
                AppConfig.normalizePreviewOverlayBgAlphaPercent(-20));
        assertEquals(AppConfig.MAX_PREVIEW_OVERLAY_BG_ALPHA_PERCENT,
                AppConfig.normalizePreviewOverlayBgAlphaPercent(140));
        assertEquals(45, AppConfig.normalizePreviewOverlayBgAlphaPercent(45));

        assertEquals(0x00101620, AppConfig.applyColorAlphaPercent(0xFF101620, 0));
        assertEquals(0xFF101620, AppConfig.applyColorAlphaPercent(0xFF101620, 100));
        assertEquals(0xCC101620, AppConfig.applyColorAlphaPercent(0xFF101620, 80));
        assertEquals(0x99101722, AppConfig.applyColorAlphaPercent(0xBF101722, 80));
    }

    @Test
    public void modeEOnlyShowsSidesAndDoesNotActivateSurroundOrModeDSensors() {
        assertEquals(PreviewMode.MODE_E, PreviewModeController.normalizeSupportedMode(PreviewMode.MODE_E));
        assertFalse(PreviewModeController.shouldActivateSurroundGl(PreviewMode.MODE_E, true, true));
        assertFalse(PreviewModeController.shouldShowModeAPreviewDisabledGuide(PreviewMode.MODE_E, false));
        assertFalse(PreviewModeController.shouldHideOrdinaryTextureViewsForModeA(PreviewMode.MODE_E, true));
        assertFalse(PreviewModeController.shouldSubscribeSteeringForModeD(
                PreviewMode.MODE_E, true, true, AppConfig.MODE_D_SIDE_LINKAGE_STEERING));
        assertFalse(PreviewModeController.shouldSubscribeTurnSignalForModeD(
                PreviewMode.MODE_E, true, true, AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL));

        boolean[] modeEMask = {true, false, false, true}; // LEFT, !FRONT, !BACK, RIGHT
        Map<String, Boolean> map = PreviewModeController.toCameraVisibilityMap(modeEMask);
        assertEquals(Boolean.TRUE, map.get("left"));
        assertEquals(Boolean.FALSE, map.get("front"));
        assertEquals(Boolean.FALSE, map.get("back"));
        assertEquals(Boolean.TRUE, map.get("right"));
        assertEquals(Arrays.asList("front", "back"), PreviewModeController.hiddenKeysFromMask(modeEMask));
    }
}
