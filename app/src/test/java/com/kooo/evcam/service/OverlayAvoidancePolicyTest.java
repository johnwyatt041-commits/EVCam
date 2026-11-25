package com.kooo.evcam.service;

import com.kooo.evcam.vehicle.VehicleSignalSnapshot;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot.Gear;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OverlayAvoidancePolicyTest {

    @Test
    public void resolveEffectiveAvmTargetDefaultsBlankAndAllToGeelyAvmActivity() {
        assertEquals(OverlayAvoidancePolicy.DEFAULT_AVM_ACTIVITY,
                OverlayAvoidancePolicy.resolveEffectiveAvmTarget(null));
        assertEquals(OverlayAvoidancePolicy.DEFAULT_AVM_ACTIVITY,
                OverlayAvoidancePolicy.resolveEffectiveAvmTarget(""));
        assertEquals(OverlayAvoidancePolicy.DEFAULT_AVM_ACTIVITY,
                OverlayAvoidancePolicy.resolveEffectiveAvmTarget("   "));
        assertEquals(OverlayAvoidancePolicy.DEFAULT_AVM_ACTIVITY,
                OverlayAvoidancePolicy.resolveEffectiveAvmTarget("all"));
        assertEquals(OverlayAvoidancePolicy.DEFAULT_AVM_ACTIVITY,
                OverlayAvoidancePolicy.resolveEffectiveAvmTarget("ALL"));
        assertEquals("com.custom.avm.MainActivity",
                OverlayAvoidancePolicy.resolveEffectiveAvmTarget("  com.custom.avm.MainActivity  "));
        assertEquals("com.custom.avm",
                OverlayAvoidancePolicy.resolveEffectiveAvmTarget("com.custom.avm"));
    }

    @Test
    public void matchesAvmTargetDetectsDefaultAndCustomTargetsWhileIgnoringOtherApps() {
        assertTrue(OverlayAvoidancePolicy.matchesAvmTarget(
                null,
                "com.geely.avm_app",
                "com.geely.avm_app.AvmRenderActivity"));
        assertTrue(OverlayAvoidancePolicy.matchesAvmTarget(
                "all",
                "com.geely.avm_app",
                "com.geely.avm_app.OtherActivity"));
        assertTrue(OverlayAvoidancePolicy.matchesAvmTarget(
                "com.geely.avm_app.AvmRenderActivity",
                "com.geely.avm_app",
                null));
        assertTrue(OverlayAvoidancePolicy.matchesAvmTarget(
                "com.vendor.surround.SurroundActivity",
                "com.vendor.surround",
                "com.vendor.surround.SurroundActivity"));

        assertFalse(OverlayAvoidancePolicy.matchesAvmTarget(
                null,
                "com.android.launcher",
                "com.android.launcher.Launcher"));
        assertFalse(OverlayAvoidancePolicy.matchesAvmTarget(
                null,
                "com.kooo.evcam",
                "com.kooo.evcam.MainActivity"));
        assertFalse(OverlayAvoidancePolicy.matchesAvmTarget(
                null,
                null,
                null));
    }

    @Test
    public void applyUsageEventTracksAvmForegroundAcrossSystemUiAndAppSwitches() {
        boolean state = false;

        state = OverlayAvoidancePolicy.applyUsageEvent(
                state,
                null,
                OverlayAvoidancePolicy.EVENT_MOVE_TO_FOREGROUND,
                "com.geely.avm_app",
                "com.geely.avm_app.AvmRenderActivity");
        assertTrue(state);

        // Transient SystemUI notifications/volume bars do not clear active AVM suppression.
        state = OverlayAvoidancePolicy.applyUsageEvent(
                state,
                null,
                OverlayAvoidancePolicy.EVENT_MOVE_TO_FOREGROUND,
                OverlayAvoidancePolicy.SYSTEM_UI_PACKAGE,
                "com.android.systemui.VolumeDialog");
        assertTrue(state);

        // Non-AVM background events do not toggle state off.
        state = OverlayAvoidancePolicy.applyUsageEvent(
                state,
                null,
                OverlayAvoidancePolicy.EVENT_MOVE_TO_BACKGROUND,
                "com.example.nav",
                "com.example.nav.MapActivity");
        assertTrue(state);

        // Switching to navigation/launcher clears AVM foreground state.
        state = OverlayAvoidancePolicy.applyUsageEvent(
                state,
                null,
                OverlayAvoidancePolicy.EVENT_MOVE_TO_FOREGROUND,
                "com.autonavi.amapauto",
                "com.autonavi.amapauto.MainMapActivity");
        assertFalse(state);

        // Explicit AVM background event clears AVM foreground state.
        state = OverlayAvoidancePolicy.applyUsageEvent(
                true,
                null,
                OverlayAvoidancePolicy.EVENT_MOVE_TO_BACKGROUND,
                "com.geely.avm_app",
                "com.geely.avm_app.AvmRenderActivity");
        assertFalse(state);
    }

    @Test
    public void reverseGearAndSuppressionAndExitPollingRespectAllGuards() {
        assertFalse(OverlayAvoidancePolicy.isReverseGear(null));
        assertFalse(OverlayAvoidancePolicy.isReverseGear(VehicleSignalSnapshot.UNKNOWN));
        assertFalse(OverlayAvoidancePolicy.isReverseGear(
                new VehicleSignalSnapshot(Gear.DRIVE, 10, 0, 0, 0, 0, 0, 0, 0L)));
        assertTrue(OverlayAvoidancePolicy.isReverseGear(
                new VehicleSignalSnapshot(Gear.REVERSE, 0, 0, 0, 0, 0, 0, 0, 0L)));

        // Any active condition suppresses main-screen overlay.
        assertFalse(OverlayAvoidancePolicy.shouldSuppressMainOverlay(false, false, false));
        assertTrue(OverlayAvoidancePolicy.shouldSuppressMainOverlay(true, false, false));
        assertTrue(OverlayAvoidancePolicy.shouldSuppressMainOverlay(false, true, false));
        assertTrue(OverlayAvoidancePolicy.shouldSuppressMainOverlay(false, false, true));
        assertTrue(OverlayAvoidancePolicy.shouldSuppressMainOverlay(true, true, true));

        // Exit polling runs only when background wanted, app not foreground, AVM active, and not in R gear.
        assertTrue(OverlayAvoidancePolicy.shouldPollAvmExit(true, false, true, false));
        assertFalse(OverlayAvoidancePolicy.shouldPollAvmExit(false, false, true, false));
        assertFalse(OverlayAvoidancePolicy.shouldPollAvmExit(true, true, true, false));
        assertFalse(OverlayAvoidancePolicy.shouldPollAvmExit(true, false, false, false));
        assertFalse(OverlayAvoidancePolicy.shouldPollAvmExit(true, false, true, true));

        // Continuous AVM watch runs whenever background wanted, app not foreground, and not in R gear.
        assertTrue(OverlayAvoidancePolicy.shouldWatchAvmForeground(true, false, false));
        assertFalse(OverlayAvoidancePolicy.shouldWatchAvmForeground(false, false, false));
        assertFalse(OverlayAvoidancePolicy.shouldWatchAvmForeground(true, true, false));
        assertFalse(OverlayAvoidancePolicy.shouldWatchAvmForeground(true, false, true));
    }

    @Test
    public void matchesOemGeelyEcarxFlymeAvmAndApaVariantsWhileRejectingSelfAndOrdinaryApps() {
        assertTrue(OverlayAvoidancePolicy.matchesAvmTarget(
                null, "com.ecarx.avm", "com.ecarx.avm.MainActivity"));
        assertTrue(OverlayAvoidancePolicy.matchesAvmTarget(
                null, "com.geely.apa_app", "com.geely.apa_app.ApaActivity"));
        assertTrue(OverlayAvoidancePolicy.matchesAvmTarget(
                null, "com.flyme.auto.panoramic", "com.flyme.auto.panoramic.PanoramicActivity"));
        assertTrue(OverlayAvoidancePolicy.matchesAvmTarget(
                "com.geely.avm_app.AvmRenderActivity",
                "com.lynkco.surround360",
                "com.lynkco.surround360.SurroundActivity"));
        assertTrue(OverlayAvoidancePolicy.matchesAvmTarget(
                null, "com.zeekr.apa", "com.zeekr.apa.ParkingCameraActivity"));
        assertTrue(OverlayAvoidancePolicy.matchesAvmTarget(
                null, OverlayAvoidancePolicy.SYSTEM_UI_PACKAGE, "com.android.systemui.avm.AvmDialog"));

        // Must NOT match normal SystemUI windows, EVCam itself, or ordinary OEM launcher/settings/map apps.
        assertFalse(OverlayAvoidancePolicy.matchesAvmTarget(
                null, OverlayAvoidancePolicy.SYSTEM_UI_PACKAGE, "com.android.systemui.VolumeDialog"));
        assertFalse(OverlayAvoidancePolicy.matchesAvmTarget(
                null, OverlayAvoidancePolicy.SELF_PACKAGE, "com.kooo.evcam.MainActivity"));
        assertFalse(OverlayAvoidancePolicy.matchesAvmTarget(
                "all", OverlayAvoidancePolicy.SELF_PACKAGE, "com.kooo.evcam.MainActivity"));
        assertFalse(OverlayAvoidancePolicy.matchesAvmTarget(
                null, "com.flyme.auto.launcher", "com.flyme.auto.launcher.Launcher"));
        assertFalse(OverlayAvoidancePolicy.matchesAvmTarget(
                null, "com.geely.hmi.settings", "com.geely.hmi.settings.SettingsActivity"));
        assertFalse(OverlayAvoidancePolicy.matchesAvmTarget(
                null, "com.autonavi.amapauto", "com.autonavi.amapauto.MainMapActivity"));
    }

    @Test
    public void avmForegroundDetectorTracksAccessibilityWindowEventsWithoutStaleSelfShortCircuit() {
        AvmForegroundDetector detector = new AvmForegroundDetector();
        String target = OverlayAvoidancePolicy.DEFAULT_AVM_ACTIVITY;

        // 1. Stale self package after backgrounding must not force false if AVM enters next.
        assertFalse(detector.onAccessibilityWindowEvent(target, OverlayAvoidancePolicy.SELF_PACKAGE, "com.kooo.evcam.MainActivity"));
        assertTrue(detector.onAccessibilityWindowEvent(target, "com.geely.avm_app", "com.geely.avm_app.AvmRenderActivity"));

        // 2. Transient SystemUI or stale self event does not drop active AVM state.
        assertTrue(detector.onAccessibilityWindowEvent(target, OverlayAvoidancePolicy.SYSTEM_UI_PACKAGE, "com.android.systemui.VolumeDialog"));
        assertTrue(detector.onAccessibilityWindowEvent(target, OverlayAvoidancePolicy.SELF_PACKAGE, "com.kooo.evcam.MainActivity"));

        // 3. Switching to Launcher / Map clears AVM foreground state.
        assertFalse(detector.onAccessibilityWindowEvent(target, "com.flyme.auto.launcher", "com.flyme.auto.launcher.Launcher"));

        // 4. Re-entering OEM AVM from background immediately triggers true.
        assertTrue(detector.onAccessibilityWindowEvent(target, "com.ecarx.panoramic", "com.ecarx.panoramic.AvmActivity"));
        detector.reset();
        assertFalse(detector.onAccessibilityWindowEvent(target, OverlayAvoidancePolicy.SYSTEM_UI_PACKAGE, "com.android.systemui.StatusBar"));
    }
}
