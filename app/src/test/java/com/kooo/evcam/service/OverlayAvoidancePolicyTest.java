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
    }
}
