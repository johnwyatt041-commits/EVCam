package com.kooo.evcam.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.kooo.evcam.R;

/**
 * 统一的胶囊选项按钮样式工具（兼容日/夜主题切换和 UiThemeRepainter）。
 */
public final class UiOptionPillHelper {

    private UiOptionPillHelper() {
    }

    public static void apply(@Nullable Button button, @NonNull Context context, boolean selected) {
        apply(button, context, selected, false);
    }

    public static void apply(
            @Nullable Button button,
            @NonNull Context context,
            boolean selected,
            boolean solidAccentWhenSelected) {
        if (button == null) return;
        int fillColor;
        int strokeColor;
        int textColor;
        if (selected && solidAccentWhenSelected) {
            fillColor = ContextCompat.getColor(context, R.color.button_accent);
            strokeColor = fillColor;
            textColor = ContextCompat.getColor(context, R.color.ui_on_accent);
        } else {
            fillColor = ContextCompat.getColor(
                    context, selected ? R.color.ui_accent_soft : R.color.input_background);
            strokeColor = ContextCompat.getColor(
                    context, selected ? R.color.ui_option_pill_selected_stroke : R.color.ui_border);
            textColor = ContextCompat.getColor(
                    context, selected ? R.color.ui_option_pill_selected_text : R.color.text_primary);
        }

        int strokeWidthPx = Math.max(1, Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 1f, context.getResources().getDisplayMetrics())));
        float radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 10f, context.getResources().getDisplayMetrics());

        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(radiusPx);
        pill.setColor(fillColor);
        pill.setStroke(strokeWidthPx, strokeColor);

        int pl = button.getPaddingLeft();
        int pt = button.getPaddingTop();
        int pr = button.getPaddingRight();
        int pb = button.getPaddingBottom();
        ViewCompat.setBackgroundTintList(button, null);
        button.setBackground(pill);
        button.setPadding(pl, pt, pr, pb);
        button.setTextColor(textColor);
        button.setTag(R.id.ui_theme_bg_role, null);
        button.setTag(R.id.ui_theme_text_role, null);
        button.setSelected(selected);
    }
}
