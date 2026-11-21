package com.kooo.evcam;

import android.graphics.Matrix;
import android.view.TextureView;

import com.kooo.evcam.blindspot.BlindSpotCorrectionMath;
import com.kooo.evcam.blindspot.BlindSpotPlacement;
import com.kooo.evcam.blindspot.CameraMirrorPolicy;
import com.kooo.evcam.blindspot.MirrorTarget;

/**
 * 补盲画面矫正工具（分方向旋转角度 + 四边裁切 + 物理/输出独立镜像）。
 *
 * <p>仅用于补盲窗口（主屏补盲悬浮窗 / 副屏 HUD），不影响主界面记录仪预览和录像，也不引入鱼眼畸变。</p>
 */
public final class BlindSpotCorrection {

    private static final float MIN_SCALE = 0.1f;
    private static final float MAX_SCALE = 3.0f;
    private static final float MIN_TRANSLATE = -1.0f;
    private static final float MAX_TRANSLATE = 1.0f;

    private BlindSpotCorrection() {
    }

    /**
     * 对补盲预览 TextureView 应用分方向旋转、四边裁切与镜像矩阵变换。
     */
    public static void apply(TextureView textureView, AppConfig appConfig, String cameraPos, int baseRotation) {
        apply(textureView, appConfig, MirrorTarget.BLIND_SPOT, cameraPos, baseRotation, false);
    }

    /**
     * 按指定输出目标解析独立的输出镜像配置后应用变换。
     */
    public static void apply(
            TextureView textureView,
            AppConfig appConfig,
            MirrorTarget target,
            String cameraPos,
            int baseRotation) {
        apply(textureView, appConfig, target, cameraPos, baseRotation, false);
    }

    /**
     * 兼容调用方 windowSwapped 参数；内部统一在等比空间内完成旋转与 Center-Crop。
     */
    public static void apply(TextureView textureView, AppConfig appConfig, String cameraPos, int baseRotation, boolean windowSwapped) {
        apply(textureView, appConfig, MirrorTarget.BLIND_SPOT, cameraPos, baseRotation, windowSwapped);
    }

    /**
     * 按屏幕维度与摄像头方位应用独立旋转、四边裁切、物理/输出镜像及屏幕硬件方向矩阵变换。
     * 所有旋转、裁切与输出镜像均在驾驶者正面视觉坐标系下生效，最后再叠加屏幕硬件方向旋转。
     */
    public static void applyForScreen(
            TextureView textureView,
            AppConfig appConfig,
            String screenKey,
            MirrorTarget target,
            String cameraPos,
            int displayOrientation) {
        if (textureView == null || appConfig == null) return;
        final String normScreen = BlindSpotPlacement.isMainScreenKey(screenKey)
                ? BlindSpotPlacement.SCREEN_MAIN
                : screenKey.trim().toLowerCase(java.util.Locale.ROOT);
        final MirrorTarget resolvedTarget = target != null
                ? target
                : (BlindSpotPlacement.isMainScreenKey(normScreen)
                        ? MirrorTarget.BLIND_SPOT
                        : MirrorTarget.SECONDARY_HUD);
        final String normCam = cameraPos != null ? BlindSpotPlacement.slotToCameraPos(cameraPos) : null;
        final CameraMirrorPolicy.ResolvedMirror mirror =
                CameraMirrorPolicy.resolve(appConfig, resolvedTarget, normCam);
        final int safeDisplayOrient = BlindSpotPlacement.normalizeScreenOrientation(displayOrientation);

        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) return;

            float centerX = viewWidth / 2f;
            float centerY = viewHeight / 2f;

            boolean swapVis = (safeDisplayOrient == 90 || safeDisplayOrient == 270);
            int visW = swapVis ? viewHeight : viewWidth;
            int visH = swapVis ? viewWidth : viewHeight;

            int previewW = 0;
            int previewH = 0;
            if (normCam != null) {
                com.kooo.evcam.camera.MultiCameraManager cm =
                        com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
                if (cm != null) {
                    com.kooo.evcam.camera.SingleCamera camera = cm.getCamera(normCam);
                    if (camera != null) {
                        android.util.Size previewSize = camera.getPreviewSize();
                        if (previewSize != null) {
                            previewW = previewSize.getWidth();
                            previewH = previewSize.getHeight();
                        }
                    }
                }
            }

            int channelRotation = normCam != null
                    ? appConfig.getBlindSpotScreenCameraRotation(normScreen, normCam)
                    : 0;
            BlindSpotCorrectionMath.EdgeCrop edgeCrop = normCam != null
                    ? appConfig.getBlindSpotScreenCameraEdgeCrop(normScreen, normCam)
                    : new BlindSpotCorrectionMath.EdgeCrop(0f, 0f, 0f, 0f);

            int bufferW = previewW > 0 ? previewW : visW;
            int bufferH = previewH > 0 ? previewH : visH;

            Matrix matrix = new Matrix();

            // 1. 从 TextureView 默认拉伸空间还原到 1:1 传感器像素比例空间
            matrix.postScale((float) bufferW / viewWidth, (float) bufferH / viewHeight, centerX, centerY);

            // 2. 物理传感器镜像（在旋转之前，与摄像头实际安装朝向一致）
            if (mirror.physicalH || mirror.physicalV) {
                matrix.postScale(
                        mirror.physicalH ? -1f : 1f,
                        mirror.physicalV ? -1f : 1f,
                        centerX,
                        centerY);
            }

            // 3. 在 1:1 像素空间中执行该屏幕+通道的独立旋转（0°–359°）
            if (channelRotation != 0) {
                matrix.postRotate(channelRotation, centerX, centerY);
            }

            // 4. 等比铺满驾驶者视觉视口（visW × visH，保证任意角度无黑边黑角）
            float coverScale = BlindSpotCorrectionMath.computeIsotropicCoverScale(
                    bufferW, bufferH, visW, visH, channelRotation);
            matrix.postScale(coverScale, coverScale, centerX, centerY);

            // 5. 按目标输出通道的独立输出镜像翻转（驾驶者视觉坐标系内执行）
            if (mirror.outputH || mirror.outputV) {
                matrix.postScale(
                        mirror.outputH ? -1f : 1f,
                        mirror.outputV ? -1f : 1f,
                        centerX,
                        centerY);
            }

            // 6. 视觉坐标系内的四边裁切（不影响主预览/录像，不引入鱼眼畸变）
            boolean legacyFallback = normCam != null
                    && appConfig.isBlindSpotCorrectionEnabled()
                    && !appConfig.hasBlindSpotScreenCameraEdgeCrop(normScreen, normCam);
            if (legacyFallback) {
                float legacyScaleX = clamp(appConfig.getBlindSpotCorrectionScaleX(normCam), MIN_SCALE, MAX_SCALE);
                float legacyScaleY = clamp(appConfig.getBlindSpotCorrectionScaleY(normCam), MIN_SCALE, MAX_SCALE);
                float legacyTransX = clamp(appConfig.getBlindSpotCorrectionTranslateX(normCam), MIN_TRANSLATE, MAX_TRANSLATE);
                float legacyTransY = clamp(appConfig.getBlindSpotCorrectionTranslateY(normCam), MIN_TRANSLATE, MAX_TRANSLATE);
                if (Math.abs(legacyScaleX - 1.0f) > 1e-3f || Math.abs(legacyScaleY - 1.0f) > 1e-3f) {
                    matrix.postScale(legacyScaleX, legacyScaleY, centerX, centerY);
                }
                if (Math.abs(legacyTransX) > 1e-3f || Math.abs(legacyTransY) > 1e-3f) {
                    matrix.postTranslate(legacyTransX * visW, legacyTransY * visH);
                }
            }
            if (!edgeCrop.isZero()) {
                matrix.postTranslate(
                        edgeCrop.preZoomShiftXNorm() * visW,
                        edgeCrop.preZoomShiftYNorm() * visH);
                float cropZoom = edgeCrop.uniformZoom();
                matrix.postScale(cropZoom, cropZoom, centerX, centerY);
            }

            // 7. 最后叠加目标屏幕硬件方向旋转（0/90/180/270），避免容器旋转引发裁剪与坐标反向
            if (safeDisplayOrient != 0) {
                matrix.postRotate(safeDisplayOrient, centerX, centerY);
            }

            textureView.setTransform(matrix);
        });
    }

    public static void apply(
            TextureView textureView,
            AppConfig appConfig,
            MirrorTarget target,
            String cameraPos,
            int baseRotation,
            boolean windowSwapped) {
        if (textureView == null || appConfig == null) return;
        final MirrorTarget resolvedTarget = target != null ? target : MirrorTarget.BLIND_SPOT;
        final CameraMirrorPolicy.ResolvedMirror mirror =
                CameraMirrorPolicy.resolve(appConfig, resolvedTarget, cameraPos);

        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) return;

            float centerX = viewWidth / 2f;
            float centerY = viewHeight / 2f;

            Matrix matrix = new Matrix();

            int previewW = 0;
            int previewH = 0;
            if (cameraPos != null) {
                com.kooo.evcam.camera.MultiCameraManager cm =
                        com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
                if (cm != null) {
                    com.kooo.evcam.camera.SingleCamera camera = cm.getCamera(cameraPos);
                    if (camera != null) {
                        android.util.Size previewSize = camera.getPreviewSize();
                        if (previewSize != null) {
                            previewW = previewSize.getWidth();
                            previewH = previewSize.getHeight();
                        }
                    }
                }
            }

            boolean correctionEnabled = appConfig.isBlindSpotCorrectionEnabled() && cameraPos != null;
            int correctionRotation = correctionEnabled
                    ? appConfig.getBlindSpotCorrectionRotation(cameraPos)
                    : 0;
            int totalRotation = BlindSpotCorrectionMath.normalizeAngle(baseRotation + correctionRotation);

            int bufferW = previewW > 0 ? previewW : viewWidth;
            int bufferH = previewH > 0 ? previewH : viewHeight;

            // 1. 从 TextureView 默认拉伸空间还原到 1:1 传感器像素比例空间
            matrix.postScale((float) bufferW / viewWidth, (float) bufferH / viewHeight, centerX, centerY);

            // 2. 物理传感器镜像（在旋转之前，与摄像头实际安装朝向一致）
            if (mirror.physicalH || mirror.physicalV) {
                matrix.postScale(
                        mirror.physicalH ? -1f : 1f,
                        mirror.physicalV ? -1f : 1f,
                        centerX,
                        centerY);
            }

            // 3. 在 1:1 像素空间中执行旋转（无拉伸切变）
            if (totalRotation != 0) {
                matrix.postRotate(totalRotation, centerX, centerY);
            }

            // 4. 等比铺满目标窗口（Center-Crop，保证任意旋转角度均无黑边和黑角）
            float coverScale = BlindSpotCorrectionMath.computeIsotropicCoverScale(
                    bufferW, bufferH, viewWidth, viewHeight, totalRotation);
            matrix.postScale(coverScale, coverScale, centerX, centerY);

            // 5. 按目标输出通道的独立输出镜像翻转（显示端视觉上执行）
            if (mirror.outputH || mirror.outputV) {
                matrix.postScale(
                        mirror.outputH ? -1f : 1f,
                        mirror.outputV ? -1f : 1f,
                        centerX,
                        centerY);
            }

            // 6. 补盲独立分路四边裁切（不影响主预览/录像，不引入鱼眼畸变）
            if (correctionEnabled) {
                float legacyScaleX = clamp(appConfig.getBlindSpotCorrectionScaleX(cameraPos), MIN_SCALE, MAX_SCALE);
                float legacyScaleY = clamp(appConfig.getBlindSpotCorrectionScaleY(cameraPos), MIN_SCALE, MAX_SCALE);
                float legacyTransX = clamp(appConfig.getBlindSpotCorrectionTranslateX(cameraPos), MIN_TRANSLATE, MAX_TRANSLATE);
                float legacyTransY = clamp(appConfig.getBlindSpotCorrectionTranslateY(cameraPos), MIN_TRANSLATE, MAX_TRANSLATE);
                if (Math.abs(legacyScaleX - 1.0f) > 1e-3f || Math.abs(legacyScaleY - 1.0f) > 1e-3f) {
                    matrix.postScale(legacyScaleX, legacyScaleY, centerX, centerY);
                }
                if (Math.abs(legacyTransX) > 1e-3f || Math.abs(legacyTransY) > 1e-3f) {
                    matrix.postTranslate(legacyTransX * viewWidth, legacyTransY * viewHeight);
                }

                BlindSpotCorrectionMath.EdgeCrop edgeCrop = appConfig.getBlindSpotCorrectionEdgeCrop(cameraPos);
                if (!edgeCrop.isZero()) {
                    matrix.postTranslate(
                            edgeCrop.preZoomShiftXNorm() * viewWidth,
                            edgeCrop.preZoomShiftYNorm() * viewHeight);
                    float cropZoom = edgeCrop.uniformZoom();
                    matrix.postScale(cropZoom, cropZoom, centerX, centerY);
                }
            }

            textureView.setTransform(matrix);
        });
    }

    /** 判断旋转角度是否更接近竖屏（90° 或 270°）。 */
    public static boolean isCloserToPortrait(int rotation) {
        int r = BlindSpotCorrectionMath.normalizeAngle(rotation);
        return (r >= 45 && r < 135) || (r >= 225 && r < 315);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
