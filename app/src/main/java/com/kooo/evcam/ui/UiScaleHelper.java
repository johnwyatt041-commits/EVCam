package com.kooo.evcam.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.DimenRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.R;
import com.kooo.evcam.blindspot.BlindSpotCropPreviewView;
import com.kooo.evcam.blindspot.BlindSpotVisualEditorView;

/**
 * 幂等的菜单与按钮缩放工具。
 * 首次处理按钮时在 {@link R.id#ui_scale_base_metrics} 中缓存原始像素基准，
 * 后续在“标准（自适应）”与“车机大号”间反复切换时严格按基准乘倍率重算，不叠加放大。
 */
public final class UiScaleHelper {

    private UiScaleHelper() {
    }

    private static final class BaseButtonMetrics {
        final float textSizePx;
        final int minHeightPx;
        final int minWidthPx;
        final int layoutHeightPx;
        final int padLeftPx;
        final int padTopPx;
        final int padRightPx;
        final int padBottomPx;

        BaseButtonMetrics(Button button) {
            this.textSizePx = button.getTextSize();
            this.minHeightPx = Math.max(button.getMinHeight(), button.getMinimumHeight());
            this.minWidthPx = Math.max(button.getMinWidth(), button.getMinimumWidth());
            ViewGroup.LayoutParams lp = button.getLayoutParams();
            this.layoutHeightPx = lp != null ? lp.height : ViewGroup.LayoutParams.WRAP_CONTENT;
            this.padLeftPx = button.getPaddingLeft();
            this.padTopPx = button.getPaddingTop();
            this.padRightPx = button.getPaddingRight();
            this.padBottomPx = button.getPaddingBottom();
        }
    }

    public static float normalizeScale(float scaleFactor) {
        if (Float.isNaN(scaleFactor) || Float.isInfinite(scaleFactor) || scaleFactor <= 0f) {
            return 1.0f;
        }
        return Math.abs(scaleFactor - 1.0f) < 0.01f ? 1.0f : scaleFactor;
    }

    public static int dimPx(@NonNull Context context, @DimenRes int dimenRes, float scaleFactor) {
        float s = normalizeScale(scaleFactor);
        return Math.max(0, Math.round(context.getResources().getDimensionPixelSize(dimenRes) * s));
    }

    public static float dimFloat(@NonNull Context context, @DimenRes int dimenRes, float scaleFactor) {
        float s = normalizeScale(scaleFactor);
        return context.getResources().getDimension(dimenRes) * s;
    }

    /**
     * 对 Fragment 或弹窗视图树中的普通 {@link Button}（排除开关/单复选框等 {@link CompoundButton}）
     * 以及明确隔离的壳层/预览/360/补盲自定义绘制区域以外的控件应用缩放。
     */
    public static void applyScaleToViewTree(@Nullable View root, float scaleFactor) {
        if (root == null) return;
        int id = root.getId();
        if (id == R.id.ui_rail
                || id == R.id.ui_top_bar
                || id == R.id.ui_preview_layout
                || id == R.id.ui_mode_a_surround_container
                || id == R.id.btn_take_photo
                || id == R.id.btn_start_record
                || id == R.id.ui_shortcut_corner_nav_group
                || root instanceof BlindSpotVisualEditorView
                || root instanceof BlindSpotCropPreviewView) {
            return;
        }

        float normalized = normalizeScale(scaleFactor);
        if (root instanceof Button && !(root instanceof CompoundButton)) {
            applyScaleToButton((Button) root, normalized);
            return;
        }

        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyScaleToViewTree(group.getChildAt(i), normalized);
            }
        }
    }

    public static void applyScaleToButton(@Nullable Button button, float scaleFactor) {
        if (button == null || button instanceof CompoundButton) return;
        float s = normalizeScale(scaleFactor);

        Object existingBase = button.getTag(R.id.ui_scale_base_metrics);
        BaseButtonMetrics base;
        if (existingBase instanceof BaseButtonMetrics) {
            base = (BaseButtonMetrics) existingBase;
        } else {
            base = new BaseButtonMetrics(button);
            button.setTag(R.id.ui_scale_base_metrics, base);
        }

        Object existingApplied = button.getTag(R.id.ui_scale_applied_factor);
        if (existingApplied instanceof Float && Math.abs(((Float) existingApplied) - s) < 0.001f) {
            return;
        }

        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, base.textSizePx * s);

        if (base.minHeightPx > 0) {
            int mh = Math.round(base.minHeightPx * s);
            button.setMinHeight(mh);
            button.setMinimumHeight(mh);
        }
        if (base.minWidthPx > 0) {
            int mw = Math.round(base.minWidthPx * s);
            button.setMinWidth(mw);
            button.setMinimumWidth(mw);
        }

        button.setPadding(
                Math.round(base.padLeftPx * s),
                Math.round(base.padTopPx * s),
                Math.round(base.padRightPx * s),
                Math.round(base.padBottomPx * s)
        );

        ViewGroup.LayoutParams lp = button.getLayoutParams();
        if (lp != null && base.layoutHeightPx > 0) {
            int targetH = Math.round(base.layoutHeightPx * s);
            if (lp.height != targetH) {
                lp.height = targetH;
                button.setLayoutParams(lp);
            }
        }

        button.setTag(R.id.ui_scale_applied_factor, s);
    }

    /** 将指定像素尺寸直接应用到壳层控件或 TextView 胶囊按钮。 */
    public static void applyTextViewMetrics(
            @Nullable TextView tv,
            float textSizePx,
            int heightPx,
            int minHeightPx,
            int minWidthPx,
            int padHPx,
            int padVPx) {
        if (tv == null) return;
        if (textSizePx > 0f) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        }
        if (minHeightPx > 0) {
            tv.setMinHeight(minHeightPx);
            tv.setMinimumHeight(minHeightPx);
        }
        if (minWidthPx > 0) {
            tv.setMinWidth(minWidthPx);
            tv.setMinimumWidth(minWidthPx);
        }
        tv.setPadding(padHPx, padVPx, padHPx, padVPx);
        ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp != null && heightPx > 0 && lp.height != heightPx) {
            lp.height = heightPx;
            tv.setLayoutParams(lp);
        }
    }
}
