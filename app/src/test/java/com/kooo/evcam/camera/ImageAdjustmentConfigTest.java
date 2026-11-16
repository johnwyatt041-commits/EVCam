package com.kooo.evcam.camera;

import org.junit.Test;

import static org.junit.Assert.*;

public class ImageAdjustmentConfigTest {

    @Test
    public void disabledMasterSwitchForcesZeroOverheadFullPassthrough() {
        ImageAdjustmentConfig cfg = new ImageAdjustmentConfig(
                false, 75, 130, 125, 160, 15, true);

        assertTrue(cfg.isBypassed());
        assertTrue(cfg.isColorShaderIdentity());
        assertTrue(cfg.isUvTransformIdentity());
        assertTrue(cfg.isFullPassthrough());

        assertEquals(1.0f, cfg.getBrightnessFactor(), 0.0001f);
        assertEquals(1.0f, cfg.getContrastFactor(), 0.0001f);
        assertEquals(1.0f, cfg.getSaturationFactor(), 0.0001f);
        assertEquals(0.0f, cfg.getCropRatio(), 0.0001f);
        assertEquals(1280, cfg.scaleWidth(1280));
        assertEquals(800, cfg.scaleHeight(800));
        assertEquals(1280, ImageAdjustmentConfig.scaleDimensionEven(1280, 75, false));
    }

    @Test
    public void enabledWithDefaultParamsStillEnablesPassthrough() {
        ImageAdjustmentConfig cfg = new ImageAdjustmentConfig(
                true, 100, 100, 100, 100, 0, false);

        assertFalse(cfg.isBypassed());
        assertTrue(cfg.isColorShaderIdentity());
        assertTrue(cfg.isUvTransformIdentity());
        assertTrue(cfg.isFullPassthrough());
        assertArrayEquals(
                new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f},
                cfg.buildTexCoords(),
                0.0001f);
    }

    @Test
    public void enabledQualityScalingIsEvenAlignedAndClamped() {
        assertEquals(1024, ImageAdjustmentConfig.scaleDimensionEven(1280, 80, true));
        assertEquals(640, ImageAdjustmentConfig.scaleDimensionEven(800, 80, true));
        // 1280 * 75% = 960, 800 * 75% = 600
        assertEquals(960, ImageAdjustmentConfig.scaleDimensionEven(1280, 75, true));
        assertEquals(600, ImageAdjustmentConfig.scaleDimensionEven(800, 75, true));
        // Odd base / quality < 50% clamps to >=50% and stays even >= 16
        assertEquals(640, ImageAdjustmentConfig.scaleDimensionEven(1281, 10, true));
        assertEquals(16, ImageAdjustmentConfig.scaleDimensionEven(17, 50, true));
    }

    @Test
    public void enabledCropAndMirrorProduceExpectedUvCoordinatesAndClamping() {
        ImageAdjustmentConfig cfg = new ImageAdjustmentConfig(
                true, 120, 200, 20, 300, 50, true);

        assertEquals(100, cfg.qualityPercent);
        assertEquals(150, cfg.brightnessPercent);
        assertEquals(50, cfg.contrastPercent);
        assertEquals(200, cfg.saturationPercent);
        assertEquals(20, cfg.cropPercent);

        assertFalse(cfg.isColorShaderIdentity());
        assertFalse(cfg.isUvTransformIdentity());
        assertFalse(cfg.isFullPassthrough());

        // crop 20% => cropHalf = 0.10 => [0.10, 0.90]; mirrorHorizontal swaps U => [0.90, 0.10]
        assertArrayEquals(
                new float[]{
                        0.90f, 0.10f,
                        0.10f, 0.10f,
                        0.90f, 0.90f,
                        0.10f, 0.90f
                },
                cfg.buildTexCoords(),
                0.0001f);
    }
}
