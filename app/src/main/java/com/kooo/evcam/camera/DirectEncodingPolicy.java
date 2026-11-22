package com.kooo.evcam.camera;

import java.util.HashSet;
import java.util.Set;

/**
 * 无水印无调色直连编码（跳过 GL）资格判定与单路异常回退守卫。
 * 纯 Java 实现，便于在 JVM 单元测试中覆盖全部分支与并发边界。
 */
public final class DirectEncodingPolicy {

    public enum PathMode {
        DIRECT("direct"),
        GL("gl");

        public final String logTag;

        PathMode(String logTag) {
            this.logTag = logTag;
        }
    }

    public enum FallbackReason {
        NONE("none"),
        SWITCH_DISABLED("switch_disabled"),
        FORCE_GL_REQUESTED("force_gl_requested"),
        RUNTIME_GL_FALLBACK("runtime_gl_fallback"),
        WATERMARK_ENABLED("watermark_enabled"),
        IMAGE_ADJUST_COLOR("image_adjust_color"),
        IMAGE_ADJUST_UV("image_adjust_uv"),
        IMAGE_ADJUST_QUALITY_SCALE("image_adjust_quality_scale"),
        RESOLUTION_MISMATCH("resolution_mismatch"),
        CAMERA_SIZE_UNSUPPORTED("camera_size_unsupported"),
        BLIND_SPOT_OPTIMIZE_MODE("blind_spot_optimize_mode"),
        FRAME_PACING_REQUIRED("frame_pacing_required");

        public final String code;

        FallbackReason(String code) {
            this.code = code;
        }
    }

    public static final class Decision {
        public final PathMode mode;
        public final FallbackReason reason;

        public Decision(PathMode mode, FallbackReason reason) {
            this.mode = mode;
            this.reason = reason;
        }

        public boolean isDirect() {
            return mode == PathMode.DIRECT;
        }
    }

    /**
     * 每路运行期 Direct -> GL 回退防重入守卫：
     * 同一录制周期内每路最多触发一次自动回退，防止重复重建循环。
     */
    public static final class FallbackGuard {
        private final Set<String> attemptedKeys = new HashSet<>();
        private final Set<String> inProgressKeys = new HashSet<>();

        public synchronized boolean tryBeginFallback(
                String cameraKey,
                boolean isDirectPath,
                boolean fatalWriteError) {
            if (cameraKey == null || cameraKey.isEmpty() || !isDirectPath || fatalWriteError) {
                return false;
            }
            if (attemptedKeys.contains(cameraKey) || inProgressKeys.contains(cameraKey)) {
                return false;
            }
            attemptedKeys.add(cameraKey);
            inProgressKeys.add(cameraKey);
            return true;
        }

        public synchronized void finishFallback(String cameraKey) {
            if (cameraKey != null) {
                inProgressKeys.remove(cameraKey);
            }
        }

        public synchronized boolean isFallbackAttempted(String cameraKey) {
            return cameraKey != null && attemptedKeys.contains(cameraKey);
        }

        public synchronized boolean isFallbackInProgress(String cameraKey) {
            return cameraKey != null && inProgressKeys.contains(cameraKey);
        }

        public synchronized void reset() {
            attemptedKeys.clear();
            inProgressKeys.clear();
        }
    }

    private DirectEncodingPolicy() {
    }

    public static Decision evaluate(
            boolean directSwitchEnabled,
            boolean forceGlRequested,
            boolean runtimeGlFallbackActive,
            boolean legacyTimestampWatermarkEnabled,
            boolean legacyVehicleStatusWatermarkEnabled,
            VehicleStatusWatermark.WatermarkOptions watermarkOptions,
            ImageAdjustmentConfig imageAdjustmentConfig,
            int sourceWidth,
            int sourceHeight,
            int encodeWidth,
            int encodeHeight,
            boolean cameraSupportsDirectSize,
            boolean blindSpotOptimizeMode,
            int targetFrameRate,
            int nativeSourceFrameRate) {
        if (runtimeGlFallbackActive) {
            return new Decision(PathMode.GL, FallbackReason.RUNTIME_GL_FALLBACK);
        }
        if (forceGlRequested) {
            return new Decision(PathMode.GL, FallbackReason.FORCE_GL_REQUESTED);
        }
        if (!directSwitchEnabled) {
            return new Decision(PathMode.GL, FallbackReason.SWITCH_DISABLED);
        }
        if (legacyTimestampWatermarkEnabled
                || legacyVehicleStatusWatermarkEnabled
                || (watermarkOptions != null && watermarkOptions.hasAnyEnabled())) {
            return new Decision(PathMode.GL, FallbackReason.WATERMARK_ENABLED);
        }
        if (imageAdjustmentConfig != null && !imageAdjustmentConfig.isBypassed()) {
            if (!imageAdjustmentConfig.isColorShaderIdentity()) {
                return new Decision(PathMode.GL, FallbackReason.IMAGE_ADJUST_COLOR);
            }
            if (!imageAdjustmentConfig.isUvTransformIdentity()) {
                return new Decision(PathMode.GL, FallbackReason.IMAGE_ADJUST_UV);
            }
            if (imageAdjustmentConfig.qualityPercent != ImageAdjustmentConfig.DEFAULT_QUALITY_PERCENT) {
                return new Decision(PathMode.GL, FallbackReason.IMAGE_ADJUST_QUALITY_SCALE);
            }
        }
        if (sourceWidth <= 0 || sourceHeight <= 0
                || encodeWidth <= 0 || encodeHeight <= 0
                || encodeWidth != sourceWidth || encodeHeight != sourceHeight) {
            return new Decision(PathMode.GL, FallbackReason.RESOLUTION_MISMATCH);
        }
        if (!cameraSupportsDirectSize) {
            return new Decision(PathMode.GL, FallbackReason.CAMERA_SIZE_UNSUPPORTED);
        }
        if (blindSpotOptimizeMode) {
            return new Decision(PathMode.GL, FallbackReason.BLIND_SPOT_OPTIMIZE_MODE);
        }
        if (targetFrameRate > 0 && nativeSourceFrameRate > 0 && targetFrameRate < nativeSourceFrameRate) {
            return new Decision(PathMode.GL, FallbackReason.FRAME_PACING_REQUIRED);
        }
        return new Decision(PathMode.DIRECT, FallbackReason.NONE);
    }
}
