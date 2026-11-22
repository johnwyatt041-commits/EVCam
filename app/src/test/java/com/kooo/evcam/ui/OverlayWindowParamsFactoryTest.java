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

    @Test
    public void lightweightMainOverlayPrefersApplicationOverlayWithoutFullscreenOrHwAccelFlags() {
        // 有悬浮窗权限时，即使开启并激活无障碍，纯 2D 状态按钮也强制使用 2038 且不带全屏与硬件加速标志
        OverlayWindowParamsFactory.WindowSpec permittedSpec =
                OverlayWindowParamsFactory.resolveLightweightMainOverlaySpec(
                        true, true, true, Build.VERSION_CODES.TIRAMISU, true);
        assertEquals(OverlayWindowParamsFactory.TYPE_APPLICATION_OVERLAY, permittedSpec.windowType);
        assertFalse(permittedSpec.usingAccessibilityOverlay);
        assertTrue((permittedSpec.flags & OverlayWindowParamsFactory.FLAG_NOT_FOCUSABLE) != 0);
        assertTrue((permittedSpec.flags & OverlayWindowParamsFactory.FLAG_NOT_TOUCH_MODAL) != 0);
        assertTrue((permittedSpec.flags & OverlayWindowParamsFactory.FLAG_ALT_FOCUSABLE_IM) != 0);
        assertEquals(0, permittedSpec.flags & OverlayWindowParamsFactory.FLAG_LAYOUT_IN_SCREEN);
        assertEquals(0, permittedSpec.flags & OverlayWindowParamsFactory.FLAG_HARDWARE_ACCELERATED);
        assertEquals(0, permittedSpec.flags & OverlayWindowParamsFactory.FLAG_NOT_TOUCHABLE);

        // 无悬浮窗权限且无障碍激活时才回退 2032，仍保持轻量标志
        OverlayWindowParamsFactory.WindowSpec accFallbackSpec =
                OverlayWindowParamsFactory.resolveLightweightMainOverlaySpec(
                        false, true, true, Build.VERSION_CODES.O, false);
        assertEquals(OverlayWindowParamsFactory.TYPE_ACCESSIBILITY_OVERLAY, accFallbackSpec.windowType);
        assertTrue(accFallbackSpec.usingAccessibilityOverlay);
        assertTrue((accFallbackSpec.flags & OverlayWindowParamsFactory.FLAG_NOT_TOUCHABLE) != 0);
        assertEquals(0, accFallbackSpec.flags & OverlayWindowParamsFactory.FLAG_LAYOUT_IN_SCREEN);
        assertEquals(0, accFallbackSpec.flags & OverlayWindowParamsFactory.FLAG_HARDWARE_ACCELERATED);

        // 旧 API 回退 TYPE_PHONE_LEGACY
        OverlayWindowParamsFactory.WindowSpec legacySpec =
                OverlayWindowParamsFactory.resolveLightweightMainOverlaySpec(
                        true, true, true, Build.VERSION_CODES.N_MR1, true);
        assertEquals(OverlayWindowParamsFactory.TYPE_PHONE_LEGACY, legacySpec.windowType);
        assertFalse(legacySpec.usingAccessibilityOverlay);
    }
}
