package com.kooo.evcam.ui;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot.Gear;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

public class PreviewModeControllerTest {

    @Test
    public void modeCPrimaryResolvesReverseToBackAndOtherGearsToFront() {
        assertEquals(PreviewLayoutModel.BACK,
                PreviewModeController.resolveDefaultPrimaryForGear(Gear.REVERSE));
        assertEquals(PreviewLayoutModel.FRONT,
                PreviewModeController.resolveDefaultPrimaryForGear(Gear.DRIVE));
        assertEquals(PreviewLayoutModel.FRONT,
                PreviewModeController.resolveDefaultPrimaryForGear(Gear.PARK));
        assertEquals(PreviewLayoutModel.FRONT,
                PreviewModeController.resolveDefaultPrimaryForGear(Gear.NEUTRAL));
        assertEquals(PreviewLayoutModel.FRONT,
                PreviewModeController.resolveDefaultPrimaryForGear(Gear.UNKNOWN));
        assertEquals(PreviewLayoutModel.FRONT,
                PreviewModeController.resolveDefaultPrimaryForGear(null));
    }

    @Test
    public void manualSwapOverrideAppliesOnlyUntilGearChanges() {
        // User manually peeks at rear while in DRIVE.
        assertEquals(PreviewLayoutModel.BACK,
                PreviewModeController.resolveEffectiveModeCPrimary(
                        Gear.DRIVE, Gear.DRIVE, PreviewLayoutModel.BACK));
        // Shifting into REVERSE follows REVERSE rule.
        assertEquals(PreviewLayoutModel.BACK,
                PreviewModeController.resolveEffectiveModeCPrimary(
                        Gear.REVERSE, null, null));
        // Even if stale override from PARK lingered, shifting to DRIVE resets to FRONT.
        assertEquals(PreviewLayoutModel.FRONT,
                PreviewModeController.resolveEffectiveModeCPrimary(
                        Gear.DRIVE, Gear.PARK, PreviewLayoutModel.BACK));
        // User manually peeks at front while in REVERSE.
        assertEquals(PreviewLayoutModel.FRONT,
                PreviewModeController.resolveEffectiveModeCPrimary(
                        Gear.REVERSE, Gear.REVERSE, PreviewLayoutModel.FRONT));
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
}
