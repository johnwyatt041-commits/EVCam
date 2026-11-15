package com.kooo.evcam.ui;

import android.os.Build;
import android.view.WindowManager;

import org.junit.Test;

import static org.junit.Assert.*;

public class OverlayWindowParamsFactoryTest {

    @Test
    public void mainOverlayUsesAccessibilityTypeOnlyWhenEnabledAndActive() {
        OverlayWindowParamsFactory.WindowSpec activeSpec =
                OverlayWindowParamsFactory.resolveMainOverlaySpec(
                        true, true, Build.VERSION_CODES.O, true);
        assertEquals(OverlayWindowParamsFactory.TYPE_ACCESSIBILITY_OVERLAY, activeSpec.windowType);
        assertTrue(activeSpec.usingAccessibilityOverlay);

        OverlayWindowParamsFactory.WindowSpec disabledPrefSpec =
                OverlayWindowParamsFactory.resolveMainOverlaySpec(
                        false, true, Build.VERSION_CODES.O, true);
        assertEquals(OverlayWindowParamsFactory.TYPE_APPLICATION_OVERLAY, disabledPrefSpec.windowType);
        assertFalse(disabledPrefSpec.usingAccessibilityOverlay);

        OverlayWindowParamsFactory.WindowSpec inactiveAccSpec =
                OverlayWindowParamsFactory.resolveMainOverlaySpec(
                        true, false, Build.VERSION_CODES.O, true);
        assertEquals(OverlayWindowParamsFactory.TYPE_APPLICATION_OVERLAY, inactiveAccSpec.windowType);
        assertFalse(inactiveAccSpec.usingAccessibilityOverlay);

        OverlayWindowParamsFactory.WindowSpec legacySpec =
                OverlayWindowParamsFactory.resolveMainOverlaySpec(
                        true, true, Build.VERSION_CODES.N_MR1, true);
        assertEquals(OverlayWindowParamsFactory.TYPE_PHONE_LEGACY, legacySpec.windowType);
        assertFalse(legacySpec.usingAccessibilityOverlay);
    }

    @Test
    public void mainOverlayFlagsNeverStealFocusOrTouchOutsideWindow() {
        OverlayWindowParamsFactory.WindowSpec touchable =
                OverlayWindowParamsFactory.resolveMainOverlaySpec(
                        true, true, Build.VERSION_CODES.TIRAMISU, true);
        assertTrue((touchable.flags & OverlayWindowParamsFactory.FLAG_NOT_FOCUSABLE) != 0);
        assertTrue((touchable.flags & OverlayWindowParamsFactory.FLAG_NOT_TOUCH_MODAL) != 0);
        assertTrue((touchable.flags & OverlayWindowParamsFactory.FLAG_ALT_FOCUSABLE_IM) != 0);
        assertTrue((touchable.flags & OverlayWindowParamsFactory.FLAG_LAYOUT_IN_SCREEN) != 0);
        assertTrue((touchable.flags & OverlayWindowParamsFactory.FLAG_HARDWARE_ACCELERATED) != 0);
        assertEquals(0, touchable.flags & OverlayWindowParamsFactory.FLAG_NOT_TOUCHABLE);

        OverlayWindowParamsFactory.WindowSpec passThrough =
                OverlayWindowParamsFactory.resolveMainOverlaySpec(
                        false, false, Build.VERSION_CODES.TIRAMISU, false);
        assertTrue((passThrough.flags & OverlayWindowParamsFactory.FLAG_NOT_TOUCHABLE) != 0);
        assertTrue((passThrough.flags & OverlayWindowParamsFactory.FLAG_NOT_FOCUSABLE) != 0);
        assertTrue((passThrough.flags & OverlayWindowParamsFactory.FLAG_NOT_TOUCH_MODAL) != 0);
    }

    @Test
    public void secondaryDisplayOverlayNeverUpgradesToAccessibilityType() {
        OverlayWindowParamsFactory.WindowSpec secondaryO =
                OverlayWindowParamsFactory.resolveSecondaryDisplayOverlaySpec(Build.VERSION_CODES.O);
        assertEquals(OverlayWindowParamsFactory.TYPE_APPLICATION_OVERLAY, secondaryO.windowType);
        assertFalse(secondaryO.usingAccessibilityOverlay);
        assertTrue((secondaryO.flags & OverlayWindowParamsFactory.FLAG_NOT_FOCUSABLE) != 0);
        assertTrue((secondaryO.flags & OverlayWindowParamsFactory.FLAG_NOT_TOUCH_MODAL) != 0);
        assertTrue((secondaryO.flags & OverlayWindowParamsFactory.FLAG_ALT_FOCUSABLE_IM) != 0);

        OverlayWindowParamsFactory.WindowSpec secondaryLegacy =
                OverlayWindowParamsFactory.resolveSecondaryDisplayOverlaySpec(Build.VERSION_CODES.N);
        assertEquals(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT, secondaryLegacy.windowType);
        assertFalse(secondaryLegacy.usingAccessibilityOverlay);
    }

    @Test
    public void fallbackOverlayTypeMatchesApiLevel() {
        assertEquals(OverlayWindowParamsFactory.TYPE_APPLICATION_OVERLAY,
                OverlayWindowParamsFactory.fallbackOverlayType(Build.VERSION_CODES.O));
        assertEquals(OverlayWindowParamsFactory.TYPE_PHONE_LEGACY,
                OverlayWindowParamsFactory.fallbackOverlayType(Build.VERSION_CODES.N_MR1));
    }
}
