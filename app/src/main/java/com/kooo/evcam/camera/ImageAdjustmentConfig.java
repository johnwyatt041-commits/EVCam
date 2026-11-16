package com.kooo.evcam.camera;

/**
 * 3.2 画面调节参数配置（含独立总开关直通旁路）。
 * 关闭总开关（enabled=false）或全部参数保持默认值时，编码器自动走零开销直通路径：
 * 0 中间 FBO、0 色彩数学运算、0 UV 裁剪变换。
 */
public final class ImageAdjustmentConfig {

    public static final int DEFAULT_QUALITY_PERCENT = 100;
    public static final int MIN_QUALITY_PERCENT = 50;
    public static final int MAX_QUALITY_PERCENT = 100;

    public static final int DEFAULT_BRIGHTNESS_PERCENT = 100;
    public static final int MIN_BRIGHTNESS_PERCENT = 50;
    public static final int MAX_BRIGHTNESS_PERCENT = 150;

    public static final int DEFAULT_CONTRAST_PERCENT = 100;
    public static final int MIN_CONTRAST_PERCENT = 50;
    public static final int MAX_CONTRAST_PERCENT = 150;

    public static final int DEFAULT_SATURATION_PERCENT = 100;
    public static final int MIN_SATURATION_PERCENT = 0;
    public static final int MAX_SATURATION_PERCENT = 200;

    public static final int DEFAULT_CROP_PERCENT = 0;
    public static final int MIN_CROP_PERCENT = 0;
    public static final int MAX_CROP_PERCENT = 20;

    public final boolean enabled;
    public final int qualityPercent;
    public final int brightnessPercent;
    public final int contrastPercent;
    public final int saturationPercent;
    public final int cropPercent;
    public final boolean mirrorHorizontal;

    public ImageAdjustmentConfig(
            boolean enabled,
            int qualityPercent,
            int brightnessPercent,
            int contrastPercent,
            int saturationPercent,
            int cropPercent,
            boolean mirrorHorizontal) {
        this.enabled = enabled;
        this.qualityPercent = clamp(qualityPercent, MIN_QUALITY_PERCENT, MAX_QUALITY_PERCENT);
        this.brightnessPercent = clamp(brightnessPercent, MIN_BRIGHTNESS_PERCENT, MAX_BRIGHTNESS_PERCENT);
        this.contrastPercent = clamp(contrastPercent, MIN_CONTRAST_PERCENT, MAX_CONTRAST_PERCENT);
        this.saturationPercent = clamp(saturationPercent, MIN_SATURATION_PERCENT, MAX_SATURATION_PERCENT);
        this.cropPercent = clamp(cropPercent, MIN_CROP_PERCENT, MAX_CROP_PERCENT);
        this.mirrorHorizontal = mirrorHorizontal;
    }

    /** 返回总开关关闭的默认配置（全直通旁路）。 */
    public static ImageAdjustmentConfig disabled() {
        return new ImageAdjustmentConfig(
                false,
                DEFAULT_QUALITY_PERCENT,
                DEFAULT_BRIGHTNESS_PERCENT,
                DEFAULT_CONTRAST_PERCENT,
                DEFAULT_SATURATION_PERCENT,
                DEFAULT_CROP_PERCENT,
                false
        );
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 总开关是否处于直通旁路态（注意：物理镜像仍可通过独立的物理镜像开关控制）。 */
    public boolean isBypassed() {
        return !enabled;
    }

    /** 色彩着色器是否为单位恒等变换，是则直接绑定极简直通 Fragment Shader。 */
    public boolean isColorShaderIdentity() {
        return !enabled || (brightnessPercent == DEFAULT_BRIGHTNESS_PERCENT
                && contrastPercent == DEFAULT_CONTRAST_PERCENT
                && saturationPercent == DEFAULT_SATURATION_PERCENT);
    }

    /** UV 顶点坐标是否为单位恒等变换（无边缘裁剪且无水平镜像）。 */
    public boolean isUvTransformIdentity() {
        return !enabled || (cropPercent == DEFAULT_CROP_PERCENT && !mirrorHorizontal);
    }

    /** 是否满足完全零开销直通条件（无需调色 Shader，也无需自定义 UV）。 */
    public boolean isFullPassthrough() {
        return isColorShaderIdentity() && isUvTransformIdentity();
    }

    public float getBrightnessFactor() {
        return enabled ? brightnessPercent / 100.0f : 1.0f;
    }

    public float getContrastFactor() {
        return enabled ? contrastPercent / 100.0f : 1.0f;
    }

    public float getSaturationFactor() {
        return enabled ? saturationPercent / 100.0f : 1.0f;
    }

    public float getCropRatio() {
        return enabled ? cropPercent / 100.0f : 0.0f;
    }

    /**
     * 按画质比例缩放编码宽高，并向下偶数对齐（不低于 16），确保兼容 MediaCodec。
     */
    public static int scaleDimensionEven(int baseDim, int qualityPercent, boolean enabled) {
        int safeBase = Math.max(16, (baseDim / 2) * 2);
        if (!enabled) {
            return safeBase;
        }
        int clampedQuality = clamp(qualityPercent, MIN_QUALITY_PERCENT, MAX_QUALITY_PERCENT);
        if (clampedQuality >= MAX_QUALITY_PERCENT) {
            return safeBase;
        }
        int scaled = Math.round(safeBase * (clampedQuality / 100.0f));
        int even = (scaled / 2) * 2;
        return Math.max(16, even);
    }

    public int scaleWidth(int baseWidth) {
        return scaleDimensionEven(baseWidth, qualityPercent, enabled);
    }

    public int scaleHeight(int baseHeight) {
        return scaleDimensionEven(baseHeight, qualityPercent, enabled);
    }

    /**
     * 构建 OpenGL 全屏四边形纹理坐标（TRIANGLE_STRIP 顺序：左下、右下、左上、右上）。
     * 应用边缘裁剪（对称内缩 cropRatio * 0.5）和水平镜像（交换左右 U）。
     */
    public float[] buildTexCoords() {
        float cropHalf = getCropRatio() * 0.5f;
        float uLeft = cropHalf;
        float uRight = 1.0f - cropHalf;
        float vBottom = cropHalf;
        float vTop = 1.0f - cropHalf;
        if (enabled && mirrorHorizontal) {
            float tmp = uLeft;
            uLeft = uRight;
            uRight = tmp;
        }
        return new float[] {
                uLeft, vBottom,
                uRight, vBottom,
                uLeft, vTop,
                uRight, vTop
        };
    }
}
