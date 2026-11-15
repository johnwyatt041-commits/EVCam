package com.kooo.evcam.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.R;

/**
 * 原地重涂已挂载的 View 树，避免深浅色切换时调用 Activity.recreate() 中断录像或预览。
 */
final class UiThemeRepainter {

    private static final int[] BG_COLOR_TOKENS = {
            R.color.page_background,
            R.color.menu_background,
            R.color.card_background,
            R.color.button_background,
            R.color.button_accent,
            R.color.input_background,
            R.color.ui_border,
            R.color.ui_accent_soft,
            R.color.preview_background,
            R.color.preview_frame_background,
            R.color.preview_controls_background
    };

    private static final int[] TEXT_COLOR_TOKENS = {
            R.color.text_primary,
            R.color.text_secondary,
            R.color.text_hint,
            R.color.button_text,
            R.color.button_accent,
            R.color.ui_success,
            R.color.ui_warning,
            R.color.ui_danger,
            R.color.preview_placeholder_text
    };

    private UiThemeRepainter() {
    }

    static Context createNightContext(Context base, int targetNightMask) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | targetNightMask;
        return new ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_Cam);
    }

    static void repaintHierarchy(@Nullable View root, Context baseContext, boolean targetNight) {
        if (root == null || baseContext == null) return;
        Context lightCtx = createNightContext(baseContext, Configuration.UI_MODE_NIGHT_NO);
        Context darkCtx = createNightContext(baseContext, Configuration.UI_MODE_NIGHT_YES);
        Context targetCtx = targetNight ? darkCtx : lightCtx;

        ColorStateList switchThumb = null;
        ColorStateList switchTrack = null;
        try {
            SwitchMaterial templateSwitch = new SwitchMaterial(targetCtx);
            switchThumb = templateSwitch.getThumbTintList();
            switchTrack = templateSwitch.getTrackTintList();
        } catch (Throwable ignored) {
            // 某些非 UI 测试上下文下忽略模板构建
        }

        Palette palette = new Palette(lightCtx, darkCtx, targetCtx, switchThumb, switchTrack);
        repaintView(root, palette);
    }

    private static void repaintView(View view, Palette palette) {
        int id = view.getId();
        // 壳层骨架区域由 AppShellController.refreshTheme 精确控制，避免破坏选择器状态
        if (id != R.id.ui_nav_recording
                && id != R.id.ui_nav_playback
                && id != R.id.ui_nav_blind_spot
                && id != R.id.ui_nav_feishu
                && id != R.id.ui_nav_settings
                && id != R.id.ui_nav_exit
                && id != R.id.ui_rail
                && id != R.id.ui_shell_root
                && id != R.id.ui_top_bar
                && id != R.id.ui_rail_divider
                && id != R.id.ui_rail_footer_divider
                && id != R.id.ui_topbar_divider
                && id != R.id.ui_content) {
            repaintBackground(view, palette);
            repaintBackgroundTint(view, palette);
        }

        if (view instanceof EditablePreviewLayout) {
            ((EditablePreviewLayout) view).refreshThemeColors(palette.targetCtx);
        }

        if (view instanceof SwitchMaterial && palette.switchThumb != null) {
            SwitchMaterial sw = (SwitchMaterial) view;
            sw.setThumbTintList(palette.switchThumb);
            if (palette.switchTrack != null) {
                sw.setTrackTintList(palette.switchTrack);
            }
        }

        if (view instanceof TextView
                && id != R.id.ui_nav_recording
                && id != R.id.ui_nav_playback
                && id != R.id.ui_nav_blind_spot
                && id != R.id.ui_nav_feishu
                && id != R.id.ui_nav_settings
                && id != R.id.ui_nav_exit) {
            repaintTextView((TextView) view, palette);
        }

        if (view instanceof ImageView) {
            ImageView iv = (ImageView) view;
            ColorStateList tint = iv.getImageTintList();
            if (tint != null) {
                Integer mapped = palette.mapTextOrTintColor(tint.getDefaultColor());
                if (mapped != null) {
                    iv.setImageTintList(ColorStateList.valueOf(mapped));
                }
            }
        }

        if (view instanceof RecyclerView) {
            RecyclerView rv = (RecyclerView) view;
            rv.getRecycledViewPool().clear();
            RecyclerView.Adapter<?> adapter = rv.getAdapter();
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                repaintView(vg.getChildAt(i), palette);
            }
        }
    }

    private static void repaintBackground(View view, Palette palette) {
        Drawable bg = view.getBackground();
        if (bg == null) return;

        Object bgRole = view.getTag(R.id.ui_theme_bg_role);
        if (bgRole instanceof Integer) {
            view.setBackgroundColor(ContextCompat.getColor(palette.targetCtx, (Integer) bgRole));
            return;
        }

        if (bg instanceof ColorDrawable) {
            int current = ((ColorDrawable) bg).getColor();
            Integer mapped = palette.mapBackgroundColor(current);
            if (mapped != null) {
                view.setBackgroundColor(mapped);
            }
            return;
        }

        if (bg instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) bg;
            ColorStateList solid = gd.getColor();
            if (solid == null) return;
            int fillColor = solid.getDefaultColor();
            if (palette.matchesToken(fillColor, R.color.card_background)) {
                float cornerRadius = gd.getCornerRadius();
                int l = view.getPaddingLeft();
                int t = view.getPaddingTop();
                int r = view.getPaddingRight();
                int b = view.getPaddingBottom();
                if (cornerRadius >= 10f * view.getResources().getDisplayMetrics().density) {
                    view.setBackground(ContextCompat.getDrawable(palette.targetCtx, R.drawable.ui_card));
                } else {
                    gd.mutate();
                    gd.setColor(ContextCompat.getColor(palette.targetCtx, R.color.card_background));
                }
                view.setPadding(l, t, r, b);
            } else if (palette.matchesToken(fillColor, R.color.page_background)
                    && gd.getCornerRadius() > 0f) {
                int l = view.getPaddingLeft();
                int t = view.getPaddingTop();
                int r = view.getPaddingRight();
                int b = view.getPaddingBottom();
                view.setBackground(ContextCompat.getDrawable(palette.targetCtx, R.drawable.edit_text_background));
                view.setPadding(l, t, r, b);
            } else {
                Integer mapped = palette.mapBackgroundColor(fillColor);
                if (mapped != null) {
                    gd.mutate();
                    gd.setColor(mapped);
                }
            }
        }
    }

    private static void repaintBackgroundTint(View view, Palette palette) {
        ColorStateList tint = ViewCompat.getBackgroundTintList(view);
        if (tint == null) return;
        int current = tint.getDefaultColor();
        Integer mapped = palette.mapBackgroundColor(current);
        if (mapped != null) {
            ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(mapped));
        }
    }

    private static void repaintTextView(TextView tv, Palette palette) {
        Object textRole = tv.getTag(R.id.ui_theme_text_role);
        if ("on_accent".equals(textRole)) {
            tv.setTextColor(ContextCompat.getColor(palette.targetCtx, R.color.ui_on_accent));
            return;
        }
        ColorStateList textColors = tv.getTextColors();
        if (textColors != null) {
            int currentText = textColors.getDefaultColor();
            if (hasAccentBackground(tv, palette) && palette.matchesToken(currentText, R.color.ui_on_accent)) {
                tv.setTextColor(ContextCompat.getColor(palette.targetCtx, R.color.ui_on_accent));
            } else {
                Integer mappedText = palette.mapTextOrTintColor(currentText);
                if (mappedText != null) {
                    tv.setTextColor(mappedText);
                }
            }
        }

        ColorStateList hintColors = tv.getHintTextColors();
        if (hintColors != null) {
            Integer mappedHint = palette.mapTextOrTintColor(hintColors.getDefaultColor());
            if (mappedHint != null) {
                tv.setHintTextColor(mappedHint);
            }
        }

        ColorStateList drawableTint = TextViewCompat.getCompoundDrawableTintList(tv);
        if (drawableTint != null) {
            Integer mappedDrawable = palette.mapTextOrTintColor(drawableTint.getDefaultColor());
            if (mappedDrawable != null) {
                TextViewCompat.setCompoundDrawableTintList(tv, ColorStateList.valueOf(mappedDrawable));
            }
        }
    }

    private static boolean hasAccentBackground(View view, Palette palette) {
        ColorStateList tint = ViewCompat.getBackgroundTintList(view);
        if (tint != null && palette.matchesToken(tint.getDefaultColor(), R.color.button_accent)) {
            return true;
        }
        Drawable bg = view.getBackground();
        if (bg instanceof ColorDrawable && palette.matchesToken(((ColorDrawable) bg).getColor(), R.color.button_accent)) {
            return true;
        }
        if (bg instanceof GradientDrawable) {
            ColorStateList solid = ((GradientDrawable) bg).getColor();
            return solid != null && palette.matchesToken(solid.getDefaultColor(), R.color.button_accent);
        }
        return false;
    }

    private static final class Palette {
        final Context lightCtx;
        final Context darkCtx;
        final Context targetCtx;
        @Nullable final ColorStateList switchThumb;
        @Nullable final ColorStateList switchTrack;

        Palette(Context lightCtx, Context darkCtx, Context targetCtx,
                @Nullable ColorStateList switchThumb, @Nullable ColorStateList switchTrack) {
            this.lightCtx = lightCtx;
            this.darkCtx = darkCtx;
            this.targetCtx = targetCtx;
            this.switchThumb = switchThumb;
            this.switchTrack = switchTrack;
        }

        boolean matchesToken(@ColorInt int color, @ColorRes int resId) {
            int light = ContextCompat.getColor(lightCtx, resId);
            int dark = ContextCompat.getColor(darkCtx, resId);
            return color == light || color == dark;
        }

        @Nullable
        Integer mapBackgroundColor(@ColorInt int current) {
            if (current == Color.TRANSPARENT) return null;
            for (int resId : BG_COLOR_TOKENS) {
                if (matchesToken(current, resId)) {
                    return ContextCompat.getColor(targetCtx, resId);
                }
            }
            return null;
        }

        @Nullable
        Integer mapTextOrTintColor(@ColorInt int current) {
            if (current == Color.TRANSPARENT) return null;
            for (int resId : TEXT_COLOR_TOKENS) {
                if (matchesToken(current, resId)) {
                    return ContextCompat.getColor(targetCtx, resId);
                }
            }
            return null;
        }
    }
}
