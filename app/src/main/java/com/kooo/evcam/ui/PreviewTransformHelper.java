package com.kooo.evcam.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.view.TextureView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.blindspot.CameraMirrorPolicy;
import com.kooo.evcam.blindspot.MirrorTarget;

/**
 * 统一的 TextureView 双镜像 + 旋转变换辅助类。
 *
 * 构建顺序：
 * 1) 旋转前在 Sensor/Buffer 空间应用物理镜像（pre-rotation）；
 * 2) 绕视图中心应用挂载旋转角度（0/90/180/270 或自定义角度）及可选缩放平移；
 * 3) 旋转后在屏幕显示空间应用输出镜像（post-rotation）。
 */
public final class PreviewTransformHelper {

    private PreviewTransformHelper() {
    }

    /**
     * 纯 Matrix 计算入口，便于无 Android 视图依赖的 JVM 单元测试验证旋转与双镜像合成顺序。
     */
    public static Matrix buildMatrix(
            int viewWidth,
            int viewHeight,
            float rotationDegrees,
            float scaleX,
            float scaleY,
            float translateX,
            float translateY,
            CameraMirrorPolicy.ResolvedMirror mirror) {
        Matrix matrix = new Matrix();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return matrix;
        }
        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;

        if (mirror != null && (mirror.physicalH || mirror.physicalV)) {
            matrix.postScale(
                    mirror.physicalH ? -1f : 1f,
                    mirror.physicalV ? -1f : 1f,
                    centerX,
                    centerY
            );
        }

        if (rotationDegrees != 0f) {
            matrix.postRotate(rotationDegrees, centerX, centerY);
        }

        if (scaleX != 1f || scaleY != 1f) {
            matrix.postScale(scaleX, scaleY, centerX, centerY);
        }

        if (translateX != 0f || translateY != 0f) {
            matrix.postTranslate(translateX, translateY);
        }

        if (mirror != null && (mirror.outputH || mirror.outputV)) {
            matrix.postScale(
                    mirror.outputH ? -1f : 1f,
                    mirror.outputV ? -1f : 1f,
                    centerX,
                    centerY
            );
        }

        return matrix;
    }

    public static void applyMainPreviewTransform(
            TextureView textureView,
            Context context,
            String cameraPosition,
            int rotationDegrees) {
        if (textureView == null || context == null) {
            return;
        }
        AppConfig appConfig = new AppConfig(context);
        applyTargetTransform(textureView, appConfig, MirrorTarget.MAIN_PREVIEW, cameraPosition, rotationDegrees);
    }

    public static void applyTargetTransform(
            TextureView textureView,
            AppConfig appConfig,
            MirrorTarget target,
            String cameraPosition,
            int rotationDegrees) {
        if (textureView == null || appConfig == null) {
            return;
        }
        final CameraMirrorPolicy.ResolvedMirror mirror =
                CameraMirrorPolicy.resolve(appConfig, target, cameraPosition);
        if (target == MirrorTarget.MAIN_PREVIEW) {
            textureView.setTag(com.kooo.evcam.R.id.tag_main_preview_matrix_managed, Boolean.TRUE);
        }
        Runnable task = () -> {
            int width = textureView.getWidth();
            int height = textureView.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            Matrix matrix = buildMatrix(width, height, rotationDegrees, 1f, 1f, 0f, 0f, mirror);
            textureView.setTransform(matrix);
        };
        if (textureView.getWidth() > 0 && textureView.getHeight() > 0) {
            task.run();
        } else {
            textureView.post(task);
        }
    }
}
