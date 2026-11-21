package com.kooo.evcam.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Editor controls are inflated on entry and removed on exit. */
public final class PreviewEditorController {
    private enum SubMode {
        LAYOUT,
        CROP,
        OPACITY
    }

    private final AppCompatActivity activity;
    private final EditablePreviewLayout layout;
    private final ViewGroup host;
    private final Consumer<Boolean> changed;
    private final AppConfig appConfig;
    @Nullable
    private PreviewModeController previewModeController;
    @Nullable
    private View controls;
    private boolean collapsed = false;
    private boolean modeAEditing = false;
    private SubMode subMode = SubMode.LAYOUT;
    private int beforeEditingWidthPercent = AppConfig.DEFAULT_SURROUND_ROAD_GUIDANCE_WIDTH_PERCENT;
    private int beforeEditingAlphaPercent = AppConfig.DEFAULT_PREVIEW_OVERLAY_BG_ALPHA_PERCENT;

    public PreviewEditorController(AppCompatActivity activity, Consumer<Boolean> changed) {
        this.activity = activity;
        layout = activity.findViewById(R.id.ui_preview_layout);
        host = activity.findViewById(R.id.ui_preview_editor_host);
        this.changed = changed;
        this.appConfig = new AppConfig(activity);
    }

    public void setPreviewModeController(@Nullable PreviewModeController controller) {
        this.previewModeController = controller;
    }

    public boolean isAvailable() {
        return layout != null && host != null;
    }

    public boolean isEditing() {
        return modeAEditing || (isAvailable() && layout.isEditing());
    }

    public void begin() {
        if (!isAvailable() || isEditing()) return;
        PreviewMode currentMode = previewModeController != null
                ? previewModeController.getCurrentPreviewMode()
                : PreviewMode.fromKey(appConfig.getPreviewMode());
        collapsed = false;
        beforeEditingWidthPercent = appConfig.getSurroundRoadGuidanceWidthPercent();
        beforeEditingAlphaPercent = appConfig.getPreviewOverlayBgAlphaPercent();
        modeAEditing = (currentMode == PreviewMode.MODE_A);
        subMode = modeAEditing ? SubMode.OPACITY : SubMode.LAYOUT;

        if (!modeAEditing) {
            layout.beginEditing();
            layout.setOnEditorSelectionListener(idx -> refreshCropControls(activity));
        }

        controls = activity.getLayoutInflater().inflate(R.layout.ui_preview_editor, host, false);
        host.addView(controls);
        host.setVisibility(View.VISIBLE);

        controls.findViewById(R.id.ui_editor_tab_layout).setOnClickListener(v -> switchSubMode(SubMode.LAYOUT));
        controls.findViewById(R.id.ui_editor_tab_crop).setOnClickListener(v -> switchSubMode(SubMode.CROP));
        controls.findViewById(R.id.ui_editor_tab_opacity).setOnClickListener(v -> switchSubMode(SubMode.OPACITY));
        controls.findViewById(R.id.ui_editor_collapse).setOnClickListener(v -> toggleCollapse());

        bindVisibilityButton(controls.findViewById(R.id.ui_vis_front), PreviewLayoutModel.FRONT);
        bindVisibilityButton(controls.findViewById(R.id.ui_vis_back), PreviewLayoutModel.BACK);
        bindVisibilityButton(controls.findViewById(R.id.ui_vis_left), PreviewLayoutModel.LEFT);
        bindVisibilityButton(controls.findViewById(R.id.ui_vis_right), PreviewLayoutModel.RIGHT);

        controls.findViewById(R.id.ui_layout_presets).setOnClickListener(v -> showPresetsDialog());
        controls.findViewById(R.id.ui_layout_reset).setOnClickListener(v -> {
            layout.resetCurrentModeDefault();
            refreshVisibilityButtons(activity);
        });

        controls.findViewById(R.id.ui_crop_fill_toggle).setOnClickListener(v -> toggleSelectedFillMode());
        controls.findViewById(R.id.ui_crop_zoom_out).setOnClickListener(v -> stepSelectedZoom(-0.15f));
        controls.findViewById(R.id.ui_crop_zoom_in).setOnClickListener(v -> stepSelectedZoom(0.15f));
        controls.findViewById(R.id.ui_crop_reset).setOnClickListener(v -> {
            layout.resetSelectedSlotCrop();
            refreshCropControls(activity);
        });

        bindPercentSliderRow(
                R.id.ui_width_seekbar, R.id.ui_width_minus, R.id.ui_width_plus,
                R.id.ui_width_reset, R.id.ui_width_value,
                AppConfig.MIN_SURROUND_ROAD_GUIDANCE_WIDTH_PERCENT,
                AppConfig.MAX_SURROUND_ROAD_GUIDANCE_WIDTH_PERCENT,
                AppConfig.DEFAULT_SURROUND_ROAD_GUIDANCE_WIDTH_PERCENT,
                5,
                appConfig::getSurroundRoadGuidanceWidthPercent,
                this::applyRoadGuidanceWidthPercent);

        bindPercentSliderRow(
                R.id.ui_opacity_seekbar, R.id.ui_opacity_minus, R.id.ui_opacity_plus,
                R.id.ui_opacity_reset, R.id.ui_opacity_value,
                AppConfig.MIN_PREVIEW_OVERLAY_BG_ALPHA_PERCENT,
                AppConfig.MAX_PREVIEW_OVERLAY_BG_ALPHA_PERCENT,
                AppConfig.DEFAULT_PREVIEW_OVERLAY_BG_ALPHA_PERCENT,
                10,
                appConfig::getPreviewOverlayBgAlphaPercent,
                this::applyOverlayAlphaPercent);

        controls.findViewById(R.id.ui_layout_cancel).setOnClickListener(v -> finish(false));
        controls.findViewById(R.id.ui_layout_save).setOnClickListener(v -> finish(true));

        refreshAllEditorControls(activity);
        UiScaleHelper.applyScaleToViewTree(controls, UiScaleController.getSavedScaleFactor(activity));
        changed.accept(true);
    }

    private void switchSubMode(@NonNull SubMode target) {
        if (modeAEditing) return;
        this.subMode = target;
        layout.setCropEditMode(target == SubMode.CROP);
        if (previewModeController != null) {
            previewModeController.setOpacityPreviewMode(target == SubMode.OPACITY);
        }
        if (collapsed) {
            collapsed = false;
        }
        refreshAllEditorControls(activity);
    }

    private void applyRoadGuidanceWidthPercent(int percent) {
        int clamped = AppConfig.normalizeSurroundRoadGuidanceWidthPercent(percent);
        appConfig.setSurroundRoadGuidanceWidthPercent(clamped);
        if (previewModeController != null) {
            previewModeController.updateSurroundRoadGuidanceWidthLive(clamped / 100.0f);
        }
    }

    private void applyOverlayAlphaPercent(int percent) {
        int clamped = AppConfig.normalizePreviewOverlayBgAlphaPercent(percent);
        appConfig.setPreviewOverlayBgAlphaPercent(clamped);
        if (previewModeController != null) {
            previewModeController.updateOverlayBgAlphaPercentLive(clamped);
        }
    }

    private void bindPercentSliderRow(int seekBarId,
                                      int minusId,
                                      int plusId,
                                      int resetId,
                                      int valueTextId,
                                      int minPercent,
                                      int maxPercent,
                                      int defaultPercent,
                                      int stepPercent,
                                      @NonNull IntSupplier getter,
                                      @NonNull IntConsumer updater) {
        if (controls == null) return;
        SeekBar seekBar = controls.findViewById(seekBarId);
        TextView valueText = controls.findViewById(valueTextId);
        View minusBtn = controls.findViewById(minusId);
        View plusBtn = controls.findViewById(plusId);
        View resetBtn = controls.findViewById(resetId);
        int range = Math.max(1, maxPercent - minPercent);

        Runnable syncUi = () -> {
            int val = Math.max(minPercent, Math.min(maxPercent, getter.getAsInt()));
            if (seekBar != null) {
                seekBar.setMax(range);
                int targetProgress = val - minPercent;
                if (seekBar.getProgress() != targetProgress) {
                    seekBar.setProgress(targetProgress);
                }
            }
            if (valueText != null) {
                valueText.setText(String.format(Locale.US, "%d%%", val));
            }
        };
        syncUi.run();

        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    int val = minPercent + progress;
                    if (valueText != null) {
                        valueText.setText(String.format(Locale.US, "%d%%", val));
                    }
                    if (fromUser) {
                        updater.accept(val);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {}

                @Override
                public void onStopTrackingTouch(SeekBar bar) {}
            });
        }
        if (minusBtn != null) {
            minusBtn.setOnClickListener(v -> {
                updater.accept(getter.getAsInt() - stepPercent);
                syncUi.run();
            });
        }
        if (plusBtn != null) {
            plusBtn.setOnClickListener(v -> {
                updater.accept(getter.getAsInt() + stepPercent);
                syncUi.run();
            });
        }
        if (resetBtn != null) {
            resetBtn.setOnClickListener(v -> {
                updater.accept(defaultPercent);
                syncUi.run();
            });
        }
    }

    private void toggleCollapse() {
        collapsed = !collapsed;
        refreshAllEditorControls(activity);
    }

    private void toggleSelectedFillMode() {
        String camKey = EditablePreviewLayout.cameraKeyForIndex(layout.getSelectedPaneIndex());
        if (camKey == null) return;
        String modeKey = layout.getCurrentMode().key;
        boolean curFill = appConfig.isPreviewSlotFillContainer(modeKey, camKey);
        appConfig.setPreviewSlotFillContainer(modeKey, camKey, !curFill);
        triggerTransformRefresh();
        refreshCropControls(activity);
    }

    private void stepSelectedZoom(float delta) {
        String camKey = EditablePreviewLayout.cameraKeyForIndex(layout.getSelectedPaneIndex());
        if (camKey == null) return;
        String modeKey = layout.getCurrentMode().key;
        float nextZoom = appConfig.getPreviewSlotZoom(modeKey, camKey) + delta;
        appConfig.setPreviewSlotZoom(modeKey, camKey, nextZoom);
        triggerTransformRefresh();
        refreshCropControls(activity);
    }

    private void triggerTransformRefresh() {
        if (activity instanceof com.kooo.evcam.MainActivity) {
            ((com.kooo.evcam.MainActivity) activity).refreshMainPreviewTransforms();
        }
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
        PreviewMode mode = layout.getCurrentMode();
        if (mode == PreviewMode.MODE_C) {
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
        if (mode == PreviewMode.MODE_D) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("双视联动排版（模式 D）")
                    .setItems(new String[]{"恢复默认双屏等分（左 50% 前/后 + 右 50% 左/右转向）"},
                            (dialog, which) -> {
                                layout.resetCurrentModeDefault();
                                refreshVisibilityButtons(activity);
                            })
                    .show();
            return;
        }
        if (mode == PreviewMode.MODE_E) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("左右双摄排版（模式 E）")
                    .setItems(new String[]{"恢复默认左右等分（左 50% 左摄 + 右 50% 右摄）"},
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
        if (!save) {
            applyOverlayAlphaPercent(beforeEditingAlphaPercent);
            if (modeAEditing) {
                applyRoadGuidanceWidthPercent(beforeEditingWidthPercent);
            }
        }
        if (modeAEditing) {
            modeAEditing = false;
        } else {
            layout.setOnEditorSelectionListener(null);
            layout.finishEditing(save);
        }
        if (previewModeController != null) {
            previewModeController.setOpacityPreviewMode(false);
        }
        host.removeAllViews();
        controls = null;
        collapsed = false;
        host.setVisibility(View.GONE);
        changed.accept(false);
    }

    public void refreshTheme(@NonNull Context themedContext) {
        if (controls == null) return;
        refreshAllEditorControls(themedContext);
        Button saveBtn = controls.findViewById(R.id.ui_layout_save);
        if (saveBtn != null) {
            ViewCompat.setBackgroundTintList(saveBtn, ColorStateList.valueOf(
                    ContextCompat.getColor(themedContext, R.color.button_accent)));
            saveBtn.setTextColor(ContextCompat.getColor(themedContext, R.color.ui_on_accent));
            saveBtn.setTag(R.id.ui_theme_bg_role, "accent");
            saveBtn.setTag(R.id.ui_theme_text_role, "on_accent");
        }
    }

    private void refreshAllEditorControls(@NonNull Context ctx) {
        if (controls == null) return;

        View body = controls.findViewById(R.id.ui_editor_body);
        if (body != null) {
            body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        Button collapseBtn = controls.findViewById(R.id.ui_editor_collapse);
        if (collapseBtn != null) {
            collapseBtn.setText(collapsed ? "展开" : "收起");
            styleOptionButton(collapseBtn, ctx, false);
        }

        View modeABadge = controls.findViewById(R.id.ui_editor_mode_a_badge);
        Button tabLayout = controls.findViewById(R.id.ui_editor_tab_layout);
        Button tabCrop = controls.findViewById(R.id.ui_editor_tab_crop);
        Button tabOpacity = controls.findViewById(R.id.ui_editor_tab_opacity);
        if (modeABadge != null) modeABadge.setVisibility(modeAEditing ? View.VISIBLE : View.GONE);
        int tabVis = modeAEditing ? View.GONE : View.VISIBLE;
        if (tabLayout != null) {
            tabLayout.setVisibility(tabVis);
            styleOptionButton(tabLayout, ctx, subMode == SubMode.LAYOUT);
        }
        if (tabCrop != null) {
            tabCrop.setVisibility(tabVis);
            styleOptionButton(tabCrop, ctx, subMode == SubMode.CROP);
        }
        if (tabOpacity != null) {
            tabOpacity.setVisibility(tabVis);
            styleOptionButton(tabOpacity, ctx, subMode == SubMode.OPACITY);
        }

        TextView hint = controls.findViewById(R.id.ui_layout_editor_hint);
        if (hint != null) {
            if (modeAEditing) {
                hint.setText("实时调节 360 前/后视引导线宽度，以及全部视图通用的按键底色透明度。");
            } else if (subMode == SubMode.CROP) {
                hint.setText("点击选中画框，框内单指拖动平移、双指或 +/- 缩放。");
            } else if (subMode == SubMode.OPACITY) {
                hint.setText("统一调节 360 A / 分栏 B / 三路 C / 模式 D / 左右 E 画面内按键与标签底色透明度。");
            } else {
                hint.setText("拖动画面自动避让换位，拖动右下角调尺寸。");
            }
        }

        View layoutRow = controls.findViewById(R.id.ui_editor_layout_controls);
        View cropRow = controls.findViewById(R.id.ui_editor_crop_controls);
        View widthRow = controls.findViewById(R.id.ui_editor_guidance_width_row);
        View opacityRow = controls.findViewById(R.id.ui_editor_opacity_row);
        if (layoutRow != null) {
            layoutRow.setVisibility(!modeAEditing && subMode == SubMode.LAYOUT ? View.VISIBLE : View.GONE);
        }
        if (cropRow != null) {
            cropRow.setVisibility(!modeAEditing && subMode == SubMode.CROP ? View.VISIBLE : View.GONE);
        }
        if (widthRow != null) {
            widthRow.setVisibility(modeAEditing ? View.VISIBLE : View.GONE);
        }
        if (opacityRow != null) {
            opacityRow.setVisibility((modeAEditing || subMode == SubMode.OPACITY) ? View.VISIBLE : View.GONE);
        }

        refreshVisibilityButtons(ctx);
        refreshCropControls(ctx);
        styleOptionButton(controls.findViewById(R.id.ui_width_minus), ctx, false);
        styleOptionButton(controls.findViewById(R.id.ui_width_plus), ctx, false);
        styleOptionButton(controls.findViewById(R.id.ui_width_reset), ctx, false);
        styleOptionButton(controls.findViewById(R.id.ui_opacity_minus), ctx, false);
        styleOptionButton(controls.findViewById(R.id.ui_opacity_plus), ctx, false);
        styleOptionButton(controls.findViewById(R.id.ui_opacity_reset), ctx, false);
    }

    private void refreshCropControls(@NonNull Context ctx) {
        if (controls == null) return;
        int idx = layout.getSelectedPaneIndex();
        String camKey = EditablePreviewLayout.cameraKeyForIndex(idx);
        TextView targetLabel = controls.findViewById(R.id.ui_crop_target_label);
        Button fillBtn = controls.findViewById(R.id.ui_crop_fill_toggle);
        TextView zoomLabel = controls.findViewById(R.id.ui_crop_zoom_value);
        if (camKey == null) {
            if (targetLabel != null) targetLabel.setText("当前画框：请点击画面");
            return;
        }
        String modeKey = layout.getCurrentMode().key;
        boolean fill = appConfig.isPreviewSlotFillContainer(modeKey, camKey);
        float zoom = appConfig.getPreviewSlotZoom(modeKey, camKey);
        if (targetLabel != null) {
            targetLabel.setText("当前画框：" + EditablePreviewLayout.defaultCameraLabel(idx));
        }
        if (fillBtn != null) {
            fillBtn.setText(fill ? "显示：铺满裁剪" : "显示：完整居中");
            styleOptionButton(fillBtn, ctx, fill);
        }
        if (zoomLabel != null) {
            zoomLabel.setText(String.format(Locale.US, "%.2fx", zoom));
        }
        styleOptionButton(controls.findViewById(R.id.ui_crop_zoom_out), ctx, false);
        styleOptionButton(controls.findViewById(R.id.ui_crop_zoom_in), ctx, false);
        styleOptionButton(controls.findViewById(R.id.ui_crop_reset), ctx, false);
    }

    private void refreshVisibilityButtons(@NonNull Context ctx) {
        if (controls == null) return;
        PreviewMode mode = layout.getCurrentMode();
        boolean isC = mode == PreviewMode.MODE_C;
        boolean isD = mode == PreviewMode.MODE_D;
        boolean isE = mode == PreviewMode.MODE_E;
        Button btnFront = controls.findViewById(R.id.ui_vis_front);
        Button btnBack = controls.findViewById(R.id.ui_vis_back);
        if (btnFront != null) {
            btnFront.setVisibility(isE ? View.GONE : View.VISIBLE);
        }
        if (btnBack != null) {
            btnBack.setVisibility(isE ? View.GONE : View.VISIBLE);
        }
        updateVisButton(btnFront, ctx,
                (isC || isD) ? "主视：前" : "前", layout.isPaneVisibleInMode(PreviewLayoutModel.FRONT), !(isC || isD));
        updateVisButton(btnBack, ctx,
                (isC || isD) ? "主视：后" : "后", layout.isPaneVisibleInMode(PreviewLayoutModel.BACK), !(isC || isD));
        updateVisButton(controls.findViewById(R.id.ui_vis_left), ctx,
                isE ? "左侧" : (isD ? "侧视：左" : "左"),
                layout.isPaneVisibleInMode(PreviewLayoutModel.LEFT), !(isD || isE));
        updateVisButton(controls.findViewById(R.id.ui_vis_right), ctx,
                isE ? "右侧" : (isD ? "侧视：右" : "右"),
                layout.isPaneVisibleInMode(PreviewLayoutModel.RIGHT), !(isD || isE));
        styleOptionButton(controls.findViewById(R.id.ui_layout_presets), ctx, false);
        styleOptionButton(controls.findViewById(R.id.ui_layout_reset), ctx, false);
    }

    private void updateVisButton(@Nullable Button btn, @NonNull Context ctx, String label,
                                 boolean active, boolean toggleSuffix) {
        if (btn == null) return;
        if (!toggleSuffix) {
            btn.setText(active ? label + " ✓" : label);
        } else {
            btn.setText(label + (active ? " · 开" : " · 关"));
        }
        styleOptionButton(btn, ctx, active);
    }

    private void styleOptionButton(@Nullable Button btn, @NonNull Context ctx, boolean active) {
        if (btn == null) return;
        int bgColor = ContextCompat.getColor(ctx, active ? R.color.button_accent : R.color.button_background);
        int textColor = ContextCompat.getColor(ctx, active ? R.color.ui_on_accent : R.color.button_text);
        ViewCompat.setBackgroundTintList(btn, ColorStateList.valueOf(bgColor));
        btn.setTextColor(textColor);
        btn.setTag(R.id.ui_theme_bg_role, active ? "accent" : null);
        btn.setTag(R.id.ui_theme_text_role, active ? "on_accent" : null);
    }
}
