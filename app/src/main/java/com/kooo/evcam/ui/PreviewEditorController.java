package com.kooo.evcam.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;

import java.util.function.Consumer;

/** Editor controls are inflated on entry and removed on exit. */
public final class PreviewEditorController {
    private final AppCompatActivity activity;
    private final EditablePreviewLayout layout;
    private final ViewGroup host;
    private final Consumer<Boolean> changed;
    @Nullable
    private View controls;

    public PreviewEditorController(AppCompatActivity activity, Consumer<Boolean> changed) {
        this.activity = activity;
        layout = activity.findViewById(R.id.ui_preview_layout);
        host = activity.findViewById(R.id.ui_preview_editor_host);
        this.changed = changed;
    }

    public boolean isAvailable() {
        return layout != null && host != null;
    }

    public boolean isEditing() {
        return isAvailable() && layout.isEditing();
    }

    public void begin() {
        if (!isAvailable() || isEditing()) return;
        layout.beginEditing();
        controls = activity.getLayoutInflater().inflate(R.layout.ui_preview_editor, host, false);
        host.addView(controls);
        host.setVisibility(View.VISIBLE);

        bindVisibilityButton(controls.findViewById(R.id.ui_vis_front), PreviewLayoutModel.FRONT);
        bindVisibilityButton(controls.findViewById(R.id.ui_vis_back), PreviewLayoutModel.BACK);
        bindVisibilityButton(controls.findViewById(R.id.ui_vis_left), PreviewLayoutModel.LEFT);
        bindVisibilityButton(controls.findViewById(R.id.ui_vis_right), PreviewLayoutModel.RIGHT);

        controls.findViewById(R.id.ui_layout_presets).setOnClickListener(v -> showPresetsDialog());
        controls.findViewById(R.id.ui_layout_reset).setOnClickListener(v -> {
            layout.resetCurrentModeDefault();
            refreshVisibilityButtons(activity);
        });
        controls.findViewById(R.id.ui_layout_cancel).setOnClickListener(v -> finish(false));
        controls.findViewById(R.id.ui_layout_save).setOnClickListener(v -> finish(true));

        refreshVisibilityButtons(activity);
        changed.accept(true);
    }

    private void bindVisibilityButton(@Nullable Button btn, int paneIndex) {
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            if (!layout.togglePaneVisibility(paneIndex)) {
                Toast.makeText(activity, "至少保留 1 路预览画面", Toast.LENGTH_SHORT).show();
                return;
            }
            refreshVisibilityButtons(activity);
        });
    }

    private void showPresetsDialog() {
        if (layout.getCurrentMode() == PreviewMode.MODE_C) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("三路大屏排版（模式 C）")
                    .setItems(new String[]{"恢复默认三路大屏（64% 主屏 + 双侧 18%）"},
                            (dialog, which) -> {
                                layout.resetCurrentModeDefault();
                                refreshVisibilityButtons(activity);
                            })
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle("选择分栏排版（模式 B）")
                .setItems(new String[]{
                        "三列分栏 · 前后在左（默认）",
                        "两侧补盲 · 中间前后",
                        "四宫格",
                        "前方大画面"
                }, (dialog, which) -> {
                    int preset;
                    if (which == 0) preset = PreviewLayoutModel.PRESET_MODE_B_THREE_COL;
                    else if (which == 1) preset = PreviewLayoutModel.PRESET_THREE_COL_SIDE_BLIND;
                    else if (which == 2) preset = PreviewLayoutModel.PRESET_GRID_2X2;
                    else preset = PreviewLayoutModel.PRESET_FRONT_LARGE;
                    layout.applyPreset(preset);
                    refreshVisibilityButtons(activity);
                })
                .show();
    }

    public void finish(boolean save) {
        if (!isEditing()) return;
        layout.finishEditing(save);
        host.removeAllViews();
        controls = null;
        host.setVisibility(View.GONE);
        changed.accept(false);
    }

    public void refreshTheme(@NonNull Context themedContext) {
        if (controls == null) return;
        refreshVisibilityButtons(themedContext);
        Button saveBtn = controls.findViewById(R.id.ui_layout_save);
        if (saveBtn != null) {
            ViewCompat.setBackgroundTintList(saveBtn, ColorStateList.valueOf(
                    ContextCompat.getColor(themedContext, R.color.button_accent)));
            saveBtn.setTextColor(ContextCompat.getColor(themedContext, R.color.ui_on_accent));
            saveBtn.setTag(R.id.ui_theme_bg_role, "accent");
            saveBtn.setTag(R.id.ui_theme_text_role, "on_accent");
        }
    }

    private void refreshVisibilityButtons(@NonNull Context ctx) {
        if (controls == null) return;
        boolean isC = layout.getCurrentMode() == PreviewMode.MODE_C;
        updateVisButton(controls.findViewById(R.id.ui_vis_front), ctx,
                isC ? "主屏：前" : "前", layout.isPaneVisibleInMode(PreviewLayoutModel.FRONT));
        updateVisButton(controls.findViewById(R.id.ui_vis_back), ctx,
                isC ? "主屏：后" : "后", layout.isPaneVisibleInMode(PreviewLayoutModel.BACK));
        updateVisButton(controls.findViewById(R.id.ui_vis_left), ctx,
                "左", layout.isPaneVisibleInMode(PreviewLayoutModel.LEFT));
        updateVisButton(controls.findViewById(R.id.ui_vis_right), ctx,
                "右", layout.isPaneVisibleInMode(PreviewLayoutModel.RIGHT));
    }

    private void updateVisButton(@Nullable Button btn, @NonNull Context ctx, String label, boolean active) {
        if (btn == null) return;
        if (label.startsWith("主屏")) {
            btn.setText(active ? label + " ✓" : label);
        } else {
            btn.setText(label + (active ? " · 开" : " · 关"));
        }
        int bgColor = ContextCompat.getColor(ctx, active ? R.color.button_accent : R.color.button_background);
        int textColor = ContextCompat.getColor(ctx, active ? R.color.ui_on_accent : R.color.button_text);
        ViewCompat.setBackgroundTintList(btn, ColorStateList.valueOf(bgColor));
        btn.setTextColor(textColor);
        btn.setTag(R.id.ui_theme_bg_role, active ? "accent" : null);
        btn.setTag(R.id.ui_theme_text_role, active ? "on_accent" : null);
    }
}
