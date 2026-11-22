package com.kooo.evcam.camera;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.AppConfig;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DirectEncodingPolicyTest {

    private static DirectEncodingPolicy.Decision evalDefault(
            VehicleStatusWatermark.WatermarkOptions watermarkOpts,
            ImageAdjustmentConfig adjustCfg) {
        return DirectEncodingPolicy.evaluate(
                true,
                false,
                false,
                false,
                false,
                watermarkOpts,
                adjustCfg,
                1280,
                800,
                1280,
                800,
                true,
                false,
                25,
                25);
    }

    @Test
    public void eligibleWhenNoWatermarkAndImageAdjustDisabledOrIdentity() {
        DirectEncodingPolicy.Decision d1 = evalDefault(
                null, ImageAdjustmentConfig.disabled());
        assertTrue(d1.isDirect());
        assertEquals(DirectEncodingPolicy.PathMode.DIRECT, d1.mode);
        assertEquals(DirectEncodingPolicy.FallbackReason.NONE, d1.reason);

        DirectEncodingPolicy.Decision d2 = evalDefault(
                VehicleStatusWatermark.WatermarkOptions.disabled(),
                new ImageAdjustmentConfig(true, 100, 100, 100, 100, 0, false));
        assertTrue(d2.isDirect());
        assertEquals(DirectEncodingPolicy.FallbackReason.NONE, d2.reason);

        // Master switch disabled ignores stored non-default slider/mirror values.
        DirectEncodingPolicy.Decision d3 = evalDefault(
                VehicleStatusWatermark.WatermarkOptions.disabled(),
                new ImageAdjustmentConfig(false, 75, 130, 80, 150, 12, true));
        assertTrue(d3.isDirect());
        assertEquals(DirectEncodingPolicy.FallbackReason.NONE, d3.reason);
    }

    @Test
    public void blankCustomTextCountsAsNoWatermarkWhileNonEmptyCustomTextForcesGl() {
        VehicleStatusWatermark.WatermarkOptions blankCustom = new VehicleStatusWatermark.WatermarkOptions(
                false, true, "   ", false, false, false,
                VehicleStatusWatermark.Position.BOTTOM_RIGHT,
                VehicleStatusWatermark.Style.CARD);
        assertTrue(evalDefault(blankCustom, ImageAdjustmentConfig.disabled()).isDirect());

        VehicleStatusWatermark.WatermarkOptions activeCustom = new VehicleStatusWatermark.WatermarkOptions(
                false, true, "E5", false, false, false,
                VehicleStatusWatermark.Position.BOTTOM_RIGHT,
                VehicleStatusWatermark.Style.CARD);
        DirectEncodingPolicy.Decision decision = evalDefault(activeCustom, ImageAdjustmentConfig.disabled());
        assertFalse(decision.isDirect());
        assertEquals(DirectEncodingPolicy.FallbackReason.WATERMARK_ENABLED, decision.reason);
    }

    @Test
    public void anyEnabledWatermarkModuleForcesGl() {
        VehicleStatusWatermark.WatermarkOptions dateOnly = new VehicleStatusWatermark.WatermarkOptions(
                true, false, "", false, false, false,
                VehicleStatusWatermark.Position.BOTTOM_RIGHT, VehicleStatusWatermark.Style.CARD);
        assertEquals(DirectEncodingPolicy.FallbackReason.WATERMARK_ENABLED,
                evalDefault(dateOnly, ImageAdjustmentConfig.disabled()).reason);

        VehicleStatusWatermark.WatermarkOptions speedOnly = new VehicleStatusWatermark.WatermarkOptions(
                false, false, "", true, false, false,
                VehicleStatusWatermark.Position.BOTTOM_RIGHT, VehicleStatusWatermark.Style.CARD);
        assertEquals(DirectEncodingPolicy.FallbackReason.WATERMARK_ENABLED,
                evalDefault(speedOnly, ImageAdjustmentConfig.disabled()).reason);

        VehicleStatusWatermark.WatermarkOptions gearOnly = new VehicleStatusWatermark.WatermarkOptions(
                false, false, "", false, true, false,
                VehicleStatusWatermark.Position.BOTTOM_RIGHT, VehicleStatusWatermark.Style.CARD);
        assertEquals(DirectEncodingPolicy.FallbackReason.WATERMARK_ENABLED,
                evalDefault(gearOnly, ImageAdjustmentConfig.disabled()).reason);

        VehicleStatusWatermark.WatermarkOptions turnOnly = new VehicleStatusWatermark.WatermarkOptions(
                false, false, "", false, false, true,
                VehicleStatusWatermark.Position.BOTTOM_RIGHT, VehicleStatusWatermark.Style.CARD);
        assertEquals(DirectEncodingPolicy.FallbackReason.WATERMARK_ENABLED,
                evalDefault(turnOnly, ImageAdjustmentConfig.disabled()).reason);

        DirectEncodingPolicy.Decision legacyTs = DirectEncodingPolicy.evaluate(
                true, false, false, true, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 800, true, false, 25, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.WATERMARK_ENABLED, legacyTs.reason);

        DirectEncodingPolicy.Decision legacyVehicle = DirectEncodingPolicy.evaluate(
                true, false, false, false, true, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 800, true, false, 25, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.WATERMARK_ENABLED, legacyVehicle.reason);
    }

    @Test
    public void nonIdentityImageAdjustmentsForceGlWithSpecificReason() {
        ImageAdjustmentConfig brightness = new ImageAdjustmentConfig(true, 100, 110, 100, 100, 0, false);
        assertEquals(DirectEncodingPolicy.FallbackReason.IMAGE_ADJUST_COLOR,
                evalDefault(null, brightness).reason);

        ImageAdjustmentConfig contrast = new ImageAdjustmentConfig(true, 100, 100, 90, 100, 0, false);
        assertEquals(DirectEncodingPolicy.FallbackReason.IMAGE_ADJUST_COLOR,
                evalDefault(null, contrast).reason);

        ImageAdjustmentConfig saturation = new ImageAdjustmentConfig(true, 100, 100, 100, 130, 0, false);
        assertEquals(DirectEncodingPolicy.FallbackReason.IMAGE_ADJUST_COLOR,
                evalDefault(null, saturation).reason);

        ImageAdjustmentConfig crop = new ImageAdjustmentConfig(true, 100, 100, 100, 100, 5, false);
        assertEquals(DirectEncodingPolicy.FallbackReason.IMAGE_ADJUST_UV,
                evalDefault(null, crop).reason);

        ImageAdjustmentConfig mirror = new ImageAdjustmentConfig(true, 100, 100, 100, 100, 0, true);
        assertEquals(DirectEncodingPolicy.FallbackReason.IMAGE_ADJUST_UV,
                evalDefault(null, mirror).reason);

        ImageAdjustmentConfig qualityScale = new ImageAdjustmentConfig(true, 75, 100, 100, 100, 0, false);
        assertEquals(DirectEncodingPolicy.FallbackReason.IMAGE_ADJUST_QUALITY_SCALE,
                evalDefault(null, qualityScale).reason);
    }

    @Test
    public void resolutionMismatchAndUnsupportedCameraSizeForceGl() {
        DirectEncodingPolicy.Decision mismatchW = DirectEncodingPolicy.evaluate(
                true, false, false, false, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 960, 800, true, false, 25, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.RESOLUTION_MISMATCH, mismatchW.reason);

        DirectEncodingPolicy.Decision mismatchH = DirectEncodingPolicy.evaluate(
                true, false, false, false, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 600, true, false, 25, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.RESOLUTION_MISMATCH, mismatchH.reason);

        DirectEncodingPolicy.Decision invalidDims = DirectEncodingPolicy.evaluate(
                true, false, false, false, false, null, ImageAdjustmentConfig.disabled(),
                0, 800, 0, 800, true, false, 25, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.RESOLUTION_MISMATCH, invalidDims.reason);

        DirectEncodingPolicy.Decision unsupported = DirectEncodingPolicy.evaluate(
                true, false, false, false, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 800, false, false, 25, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.CAMERA_SIZE_UNSUPPORTED, unsupported.reason);
    }

    @Test
    public void framePacingAndBlindSpotOptimizeModeForceGlWhenNeeded() {
        DirectEncodingPolicy.Decision blindSpot = DirectEncodingPolicy.evaluate(
                true, false, false, false, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 800, true, true, 25, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.BLIND_SPOT_OPTIMIZE_MODE, blindSpot.reason);

        DirectEncodingPolicy.Decision lowFps20 = DirectEncodingPolicy.evaluate(
                true, false, false, false, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 800, true, false, 20, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.FRAME_PACING_REQUIRED, lowFps20.reason);

        DirectEncodingPolicy.Decision lowFps15 = DirectEncodingPolicy.evaluate(
                true, false, false, false, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 800, true, false, 15, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.FRAME_PACING_REQUIRED, lowFps15.reason);

        DirectEncodingPolicy.Decision fps25 = DirectEncodingPolicy.evaluate(
                true, false, false, false, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 800, true, false, 25, 25);
        assertTrue(fps25.isDirect());

        DirectEncodingPolicy.Decision fps30 = DirectEncodingPolicy.evaluate(
                true, false, false, false, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 800, true, false, 30, 25);
        assertTrue(fps30.isDirect());
    }

    @Test
    public void rollbackSwitchesTakePrecedenceOverEligibility() {
        DirectEncodingPolicy.Decision runtime = DirectEncodingPolicy.evaluate(
                true, true, true, false, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 800, true, false, 25, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.RUNTIME_GL_FALLBACK, runtime.reason);

        DirectEncodingPolicy.Decision forceGl = DirectEncodingPolicy.evaluate(
                false, true, false, false, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 800, true, false, 25, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.FORCE_GL_REQUESTED, forceGl.reason);

        DirectEncodingPolicy.Decision switchOff = DirectEncodingPolicy.evaluate(
                false, false, false, false, false, null, ImageAdjustmentConfig.disabled(),
                1280, 800, 1280, 800, true, false, 25, 25);
        assertEquals(DirectEncodingPolicy.FallbackReason.SWITCH_DISABLED, switchOff.reason);
    }

    @Test
    public void fallbackGuardAllowsAtMostOncePerCameraAndResetsCleanly() {
        DirectEncodingPolicy.FallbackGuard guard = new DirectEncodingPolicy.FallbackGuard();

        assertFalse(guard.tryBeginFallback(null, true, false));
        assertFalse(guard.tryBeginFallback("", true, false));
        assertFalse(guard.tryBeginFallback("front", false, false));
        assertFalse(guard.tryBeginFallback("front", true, true));

        assertTrue(guard.tryBeginFallback("front", true, false));
        assertTrue(guard.isFallbackAttempted("front"));
        assertTrue(guard.isFallbackInProgress("front"));
        assertFalse(guard.tryBeginFallback("front", true, false));

        // Independent camera keys remain eligible.
        assertTrue(guard.tryBeginFallback("back", true, false));
        guard.finishFallback("front");
        assertFalse(guard.isFallbackInProgress("front"));
        assertTrue(guard.isFallbackAttempted("front"));
        assertFalse(guard.tryBeginFallback("front", true, false));

        guard.reset();
        assertFalse(guard.isFallbackAttempted("front"));
        assertFalse(guard.isFallbackInProgress("back"));
        assertTrue(guard.tryBeginFallback("front", true, false));
    }

    @Test
    public void appConfigDirectCodecInputDefaultsTrueAndPersistsToggle() {
        Map<String, Object> store = new HashMap<>();
        Context context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(editor);
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            Object v = store.get(inv.getArgument(0));
            return v instanceof Boolean ? (Boolean) v : inv.getArgument(1);
        });
        when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });

        AppConfig appConfig = new AppConfig(context);
        assertTrue(appConfig.isDirectCodecInputEnabled());

        appConfig.setDirectCodecInputEnabled(false);
        assertFalse(appConfig.isDirectCodecInputEnabled());

        appConfig.setDirectCodecInputEnabled(true);
        assertTrue(appConfig.isDirectCodecInputEnabled());
    }
}
