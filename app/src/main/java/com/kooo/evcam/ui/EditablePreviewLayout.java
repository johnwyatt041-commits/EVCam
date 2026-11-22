package com.kooo.evcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

import java.util.EnumMap;
import java.util.Map;

/**
 * Repositions and clips the same four TextureView parents across Mode B / Mode C / Mode D.
 * Hides non-active panes with View.GONE instead of detaching views so SurfaceTexture recreation stays clean.
 */
public final class EditablePreviewLayout extends ViewGroup {

    public interface OnStateChangeListener {
        void onEffectiveVisibilityChanged(boolean[] visibleMask);
    }

    public interface OnTransformInvalidatedListener {
        void onPreviewTransformsInvalidated();
    }

    public interface OnEditorSelectionListener {
        void onSelectedPaneChanged(int selectedPaneIndex);
    }

    private static final class ModeStorageSpec {
        final String savedKey;
        final String legacySavedKey;
        final String panePrefix;
        final String legacyPanePrefix;
        final String orderPrefix;
        final String legacyOrderPrefix;
        final int defaultPreset;

        ModeStorageSpec(String savedKey, String legacySavedKey,
                        String panePrefix, String legacyPanePrefix,
                        String orderPrefix, String legacyOrderPrefix,
                        int defaultPreset) {
            this.savedKey = savedKey;
            this.legacySavedKey = legacySavedKey;
            this.panePrefix = panePrefix;
            this.legacyPanePrefix = legacyPanePrefix;
            this.orderPrefix = orderPrefix;
            this.legacyOrderPrefix = legacyOrderPrefix;
            this.defaultPreset = defaultPreset;
        }
    }

    private static final float CORNER_RADIUS_DP = 16f;
    private static final float GAP_DP = 12f;
    private static final String[] SLOT_KEYS = {"front", "back", "left", "right"};

    private static final EnumMap<PreviewMode, ModeStorageSpec> STORAGE_SPECS = new EnumMap<>(PreviewMode.class);

    static {
        STORAGE_SPECS.put(PreviewMode.MODE_B, new ModeStorageSpec(
                "b_saved", "saved", "b_pane_", "pane_", "b_order_", "order_",
                PreviewLayoutModel.PRESET_MODE_B_THREE_COL));
        STORAGE_SPECS.put(PreviewMode.MODE_C, new ModeStorageSpec(
                "c_saved", null, "c_pane_", null, "c_order_", null,
                PreviewLayoutModel.PRESET_MODE_C_FRONT));
        STORAGE_SPECS.put(PreviewMode.MODE_D, new ModeStorageSpec(
                "d_saved", null, "d_pane_", null, "d_order_", null,
                PreviewLayoutModel.PRESET_MODE_D_DUAL));
        STORAGE_SPECS.put(PreviewMode.MODE_E, new ModeStorageSpec(
                "e_saved", null, "e_pane_", null, "e_order_", null,
                PreviewLayoutModel.PRESET_MODE_E_SIDES));
    }

    private final EnumMap<PreviewMode, PreviewLayoutModel> modeModels = new EnumMap<>(PreviewMode.class);
    private final SharedPreferences preferences;
    private final AppConfig appConfig;
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float cornerRadiusPx;
    private final ScaleGestureDetector pinchDetector;

    private final boolean[] modeBVisible = {true, true, true, true};
    private boolean modeCShowLeft = true;
    private boolean modeCShowRight = true;
    private PreviewMode currentMode = PreviewMode.MODE_B;
    private int modeCPrimaryIndex = PreviewLayoutModel.FRONT;
    private int modeDLeftPrimaryIndex = PreviewLayoutModel.FRONT;
    private int modeDRightSideIndex = PreviewLayoutModel.LEFT;
    private PreviewLayoutModel.EffectiveLayout effectiveLayout;

    private boolean editing;
    private boolean cropEditMode;
    private boolean opacityPreviewMode;
    private boolean resizing;
    private int selected = -1;
    private float startX, startY, originalX, originalY, originalWidth, originalHeight;
    private float startCropPanX, startCropPanY;
    private float[] dragOriginSnapshot;
    private float[] beforeEditing;
    private boolean[] beforeEditingModeBVisible;
    private boolean beforeEditingCLeft;
    private boolean beforeEditingCRight;
    private int beforeEditingCPrimary = PreviewLayoutModel.FRONT;
    private int beforeEditingDPrimary = PreviewLayoutModel.FRONT;
    private int beforeEditingDRight = PreviewLayoutModel.LEFT;
    private int[] previousOrder;
    private boolean[] beforeCropFill;
    private float[] beforeCropZoom;
    private float[] beforeCropPanX;
    private float[] beforeCropPanY;

    private boolean inFrameControlsVisible = true;
    private int overlayBgAlphaPercent = AppConfig.DEFAULT_PREVIEW_OVERLAY_BG_ALPHA_PERCENT;
    private boolean mainPreviewEnabled = true;
    private boolean roundedClipEnabled = false;
    private static final int LIGHTWEIGHT_PANE_BG_COLOR = 0xFF0B0F17;
    private final boolean[] cameraAvailable = {true, true, true, true};
    private final TextView[] emptyCameraHints = new TextView[PreviewLayoutModel.PANE_COUNT];
    private TextView btnMainFeedSwitch;
    private String modeDSideLinkageMode = AppConfig.MODE_D_SIDE_LINKAGE_STEERING;
    private LinearLayout modeDSideControlsContainer;
    private TextView btnModeDSideLinkage;
    private TextView btnSideFeedSwitch;

    private final int[] lastPaneWidths = new int[PreviewLayoutModel.PANE_COUNT];
    private final int[] lastPaneHeights = new int[PreviewLayoutModel.PANE_COUNT];

    @Nullable
    private OnStateChangeListener stateChangeListener;
    @Nullable
    private OnTransformInvalidatedListener transformInvalidatedListener;
    @Nullable
    private OnEditorSelectionListener editorSelectionListener;
    @Nullable
    private Runnable manualPrimaryToggleListener;
    @Nullable
    private Runnable manualSideToggleListener;
    @Nullable
    private Runnable modeDSideLinkageCycleListener;

    private final ViewOutlineProvider paneOutlineProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            int w = view.getWidth();
            int h = view.getHeight();
            if (w > 0 && h > 0) {
                outline.setRoundRect(0, 0, w, h, cornerRadiusPx);
            }
        }
    };

    public EditablePreviewLayout(Context context, AttributeSet attributes) {
        super(context, attributes);
        density = getResources().getDisplayMetrics().density;
        cornerRadiusPx = CORNER_RADIUS_DP * density;
        preferences = context.getSharedPreferences("e5_preview_layout_v1", Context.MODE_PRIVATE);
        appConfig = new AppConfig(context);
        overlayBgAlphaPercent = appConfig.getPreviewOverlayBgAlphaPercent();
        roundedClipEnabled = appConfig.isPreviewRoundedClipEnabled();

        for (Map.Entry<PreviewMode, ModeStorageSpec> e : STORAGE_SPECS.entrySet()) {
            modeModels.put(e.getKey(), new PreviewLayoutModel(e.getValue().defaultPreset));
        }
        loadSavedState();

        border.setColor(ContextCompat.getColor(context, R.color.button_accent));
        border.setStyle(Paint.Style.STROKE);
        handle.setColor(ContextCompat.getColor(context, R.color.button_accent));
        arrow.setColor(ContextCompat.getColor(context, R.color.ui_on_accent));
        arrow.setStrokeWidth(2 * density);
        // 非编辑态不需要 EditablePreviewLayout 自身 draw pass，仅进入编辑态时开启绘制选框/手柄
        setWillNotDraw(true);

        pinchDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if (!editing || !cropEditMode || selected < 0) return false;
                String camKey = cameraKeyForIndex(selected);
                if (camKey == null) return false;
                float nextZoom = appConfig.getPreviewSlotZoom(currentMode.key, camKey) * detector.getScaleFactor();
                appConfig.setPreviewSlotZoom(currentMode.key, camKey, nextZoom);
                notifyTransformInvalidated();
                if (editorSelectionListener != null) {
                    editorSelectionListener.onSelectedPaneChanged(selected);
                }
                return true;
            }
        });

        recomputeEffectiveLayout(false);
    }

    private void loadSavedState() {
        for (Map.Entry<PreviewMode, ModeStorageSpec> entry : STORAGE_SPECS.entrySet()) {
            ModeStorageSpec spec = entry.getValue();
            PreviewLayoutModel model = modeModels.get(entry.getKey());
            if (model == null) continue;
            if (preferences.getBoolean(spec.savedKey, false)) {
                restoreModel(model, spec.panePrefix);
            } else if (spec.legacySavedKey != null && preferences.getBoolean(spec.legacySavedKey, false)) {
                restoreModel(model, spec.legacyPanePrefix);
            }
        }
        for (int i = 0; i < PreviewLayoutModel.PANE_COUNT; i++) {
            modeBVisible[i] = preferences.getBoolean("b_vis_" + i, true);
        }
        PreviewLayoutModel.normalizeVisibleAtLeastOne(modeBVisible, PreviewLayoutModel.FRONT);
        modeCShowLeft = preferences.getBoolean("c_vis_left", true);
        modeCShowRight = preferences.getBoolean("c_vis_right", true);
    }

    private void restoreModel(PreviewLayoutModel target, String prefix) {
        float[] values = new float[16];
        for (int i = 0; i < values.length; i++) {
            values[i] = preferences.getFloat(prefix + i, Float.NaN);
        }
        target.restore(values);
    }

    /** 主题原地切换后刷新编辑边框和手柄画笔颜色。 */
    public void refreshThemeColors(Context themedContext) {
        border.setColor(ContextCompat.getColor(themedContext, R.color.button_accent));
        handle.setColor(ContextCompat.getColor(themedContext, R.color.button_accent));
        arrow.setColor(ContextCompat.getColor(themedContext, R.color.ui_on_accent));
        invalidate();
    }

    public void setOnStateChangeListener(@Nullable OnStateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    public void setOnTransformInvalidatedListener(@Nullable OnTransformInvalidatedListener listener) {
        this.transformInvalidatedListener = listener;
    }

    public void setOnEditorSelectionListener(@Nullable OnEditorSelectionListener listener) {
        this.editorSelectionListener = listener;
    }

    public void setOnManualPrimaryToggleListener(@Nullable Runnable listener) {
        this.manualPrimaryToggleListener = listener;
    }

    public void setOnManualSideToggleListener(@Nullable Runnable listener) {
        this.manualSideToggleListener = listener;
    }

    public void setOnModeDSideLinkageCycleListener(@Nullable Runnable listener) {
        this.modeDSideLinkageCycleListener = listener;
    }

    public void setModeDSideLinkageMode(@Nullable String mode) {
        String normalized = AppConfig.normalizeModeDSideLinkageMode(mode);
        if (normalized.equals(this.modeDSideLinkageMode)) {
            syncModeDSideControls();
            return;
        }
        this.modeDSideLinkageMode = normalized;
        syncModeDSideControls();
    }

    public void setInFrameControlsVisible(boolean visible) {
        if (this.inFrameControlsVisible == visible) return;
        this.inFrameControlsVisible = visible;
        syncMainFeedSwitchPill();
        syncModeDSideControls();
    }

    public void setOverlayBgAlphaPercent(int alphaPercent) {
        int normalized = AppConfig.normalizePreviewOverlayBgAlphaPercent(alphaPercent);
        this.overlayBgAlphaPercent = normalized;
        applyOverlayBackgroundAlpha();
    }

    public void setOpacityPreviewMode(boolean opacityPreviewMode) {
        if (this.opacityPreviewMode == opacityPreviewMode) return;
        this.opacityPreviewMode = opacityPreviewMode;
        syncMainFeedSwitchPill();
        syncModeDSideControls();
        invalidate();
    }

    public void setCameraAvailable(@NonNull String cameraKey, boolean available) {
        int idx = PreviewModeController.cameraKeyToIndex(cameraKey);
        if (idx < 0 || idx >= PreviewLayoutModel.PANE_COUNT) return;
        cameraAvailable[idx] = available;
        syncCameraEmptyHint(idx);
    }

    public void setMainPreviewEnabled(boolean enabled) {
        if (this.mainPreviewEnabled == enabled) return;
        this.mainPreviewEnabled = enabled;
        for (int i = 0; i < PreviewLayoutModel.PANE_COUNT; i++) {
            syncCameraEmptyHint(i);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        for (int i = 0; i < getChildCount(); i++) {
            configurePaneOutline(getChildAt(i));
        }
        initInFrameOverlays();
        restoreOrderForMode(currentMode);
        recomputeEffectiveLayout(false);
        applyOverlayBackgroundAlpha();
    }

    public void setRoundedClipEnabled(boolean enabled) {
        if (this.roundedClipEnabled == enabled) return;
        this.roundedClipEnabled = enabled;
        for (int i = 0; i < getChildCount(); i++) {
            configurePaneOutline(getChildAt(i));
        }
        if (editing) {
            invalidate();
        }
    }

    public boolean isRoundedClipEnabled() {
        return roundedClipEnabled;
    }

    private void configurePaneOutline(View child) {
        if (child == null) return;
        if (roundedClipEnabled) {
            child.setBackgroundResource(R.drawable.ui_card);
            child.setOutlineProvider(paneOutlineProvider);
            child.setClipToOutline(true);
            child.invalidateOutline();
        } else {
            child.setClipToOutline(false);
            child.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            child.setBackgroundColor(LIGHTWEIGHT_PANE_BG_COLOR);
        }
    }

    private void initInFrameOverlays() {
        btnMainFeedSwitch = createOverlayPill("toggle_front_back_primary", () -> {
            if (manualPrimaryToggleListener != null) {
                manualPrimaryToggleListener.run();
            }
        });

        modeDSideControlsContainer = new LinearLayout(getContext());
        modeDSideControlsContainer.setOrientation(LinearLayout.HORIZONTAL);
        modeDSideControlsContainer.setGravity(Gravity.CENTER_VERTICAL);

        btnModeDSideLinkage = createOverlayPill("cycle_mode_d_side_linkage", () -> {
            if (modeDSideLinkageCycleListener != null) {
                modeDSideLinkageCycleListener.run();
            }
        });
        LinearLayout.LayoutParams linkageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        linkageLp.setMarginEnd(Math.round(8 * density));
        modeDSideControlsContainer.addView(btnModeDSideLinkage, linkageLp);

        btnSideFeedSwitch = createOverlayPill("toggle_left_right_side", () -> {
            if (manualSideToggleListener != null) {
                manualSideToggleListener.run();
            }
        });
        modeDSideControlsContainer.addView(btnSideFeedSwitch);

        for (int i = 0; i < PreviewLayoutModel.PANE_COUNT; i++) {
            syncCameraEmptyHint(i);
        }
    }

    private TextView createOverlayPill(@NonNull String contentDescription, @NonNull Runnable action) {
        TextView tv = new TextView(getContext());
        tv.setBackground(createOverlayPillDrawable(0xBF101722, 0x46FFFFFF, 18f));
        tv.setContentDescription(contentDescription);
        tv.setTextColor(0xFFFFFFFF);
        tv.setShadowLayer(2f * density, 0f, 1f * density, 0xA0000000);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.ui_pill_text_size));
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setMinHeight(getResources().getDimensionPixelSize(R.dimen.ui_corner_shortcut_item_height));
        int hp = getResources().getDimensionPixelSize(R.dimen.ui_pill_padding_h);
        int vp = Math.round(6 * density);
        tv.setPadding(hp, vp, hp, vp);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(v -> action.run());
        return tv;
    }

    private GradientDrawable createOverlayPillDrawable(int baseFillArgb, int baseStrokeArgb, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusDp * density);
        d.setColor(AppConfig.applyColorAlphaPercent(baseFillArgb, overlayBgAlphaPercent));
        if (baseStrokeArgb != 0) {
            d.setStroke(Math.max(1, Math.round(density)),
                    AppConfig.applyColorAlphaPercent(baseStrokeArgb, overlayBgAlphaPercent));
        }
        return d;
    }

    private void applyOverlayBackgroundAlpha() {
        GradientDrawable standardBg = createOverlayPillDrawable(0xBF101722, 0x46FFFFFF, 18f);
        if (btnMainFeedSwitch != null) {
            btnMainFeedSwitch.setBackground(standardBg);
        }
        if (btnSideFeedSwitch != null) {
            btnSideFeedSwitch.setBackground(createOverlayPillDrawable(0xBF101722, 0x46FFFFFF, 18f));
        }
        syncModeDSideLinkagePillBg();

        int[] labelIds = {
                com.kooo.evcam.R.id.label_front,
                com.kooo.evcam.R.id.label_back,
                com.kooo.evcam.R.id.label_left,
                com.kooo.evcam.R.id.label_right
        };
        for (int labelId : labelIds) {
            View label = findViewById(labelId);
            if (label instanceof TextView) {
                label.setBackground(createOverlayPillDrawable(0xA0000000, 0, 8f));
                ((TextView) label).setShadowLayer(2f * density, 0f, 1f * density, 0xA0000000);
            }
        }
    }

    private void syncModeDSideLinkagePillBg() {
        if (btnModeDSideLinkage == null) return;
        boolean autoActive = !AppConfig.MODE_D_SIDE_LINKAGE_MANUAL.equals(modeDSideLinkageMode);
        if (autoActive) {
            btnModeDSideLinkage.setBackground(createOverlayPillDrawable(0xF21D5BB8, 0x8C8AC4FF, 18f));
            btnModeDSideLinkage.setTextColor(overlayBgAlphaPercent < 20 ? 0xFF60A5FA : 0xFFFFFFFF);
        } else {
            btnModeDSideLinkage.setBackground(createOverlayPillDrawable(0xBF101722, 0x46FFFFFF, 18f));
            btnModeDSideLinkage.setTextColor(0xFFFFFFFF);
        }
    }

    private void syncCameraEmptyHint(int idx) {
        FrameLayout frame = findFrameByPaneIndex(idx);
        if (frame == null) return;
        TextView hint = emptyCameraHints[idx];
        if (hint == null) {
            hint = new TextView(getContext());
            hint.setTextColor(0xFF94A3B8);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            hint.setGravity(Gravity.CENTER);
            hint.setText(defaultCameraLabel(idx) + "未接入信号");
            hint.setPadding(Math.round(12 * density), Math.round(6 * density),
                    Math.round(12 * density), Math.round(6 * density));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            frame.addView(hint, lp);
            emptyCameraHints[idx] = hint;
        }
        hint.setVisibility((mainPreviewEnabled && !cameraAvailable[idx]) ? View.VISIBLE : View.GONE);
    }

    private void syncMainFeedSwitchPill() {
        if (btnMainFeedSwitch == null) return;
        boolean needPill = (currentMode == PreviewMode.MODE_C || currentMode == PreviewMode.MODE_D)
                && (!editing || opacityPreviewMode) && inFrameControlsVisible;
        int primaryIdx = currentMode == PreviewMode.MODE_D ? modeDLeftPrimaryIndex : modeCPrimaryIndex;
        FrameLayout targetFrame = needPill ? findFrameByPaneIndex(primaryIdx) : null;

        ViewParent parent = btnMainFeedSwitch.getParent();
        if (targetFrame == null || targetFrame.getVisibility() != View.VISIBLE) {
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(btnMainFeedSwitch);
            }
            return;
        }
        if (parent != targetFrame) {
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(btnMainFeedSwitch);
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            int margin = Math.round(14 * density);
            lp.setMargins(margin, margin, margin, margin);
            targetFrame.addView(btnMainFeedSwitch, lp);
        }
        btnMainFeedSwitch.setText(primaryIdx == PreviewLayoutModel.BACK ? "后视 ⇄ 前视" : "前视 ⇄ 后视");
        btnMainFeedSwitch.bringToFront();
    }

    private void syncModeDSideControls() {
        if (modeDSideControlsContainer == null) return;
        boolean needControls = (currentMode == PreviewMode.MODE_D)
                && (!editing || opacityPreviewMode) && inFrameControlsVisible;
        FrameLayout targetFrame = needControls ? findFrameByPaneIndex(modeDRightSideIndex) : null;

        ViewParent parent = modeDSideControlsContainer.getParent();
        if (targetFrame == null || targetFrame.getVisibility() != View.VISIBLE) {
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(modeDSideControlsContainer);
            }
            return;
        }
        if (parent != targetFrame) {
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(modeDSideControlsContainer);
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            int margin = Math.round(14 * density);
            lp.setMargins(margin, margin, margin, margin);
            targetFrame.addView(modeDSideControlsContainer, lp);
        }

        if (btnModeDSideLinkage != null) {
            String linkageLabel;
            if (AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL.equals(modeDSideLinkageMode)) {
                linkageLabel = "随转向灯";
            } else if (AppConfig.MODE_D_SIDE_LINKAGE_MANUAL.equals(modeDSideLinkageMode)) {
                linkageLabel = "手动切换";
            } else {
                linkageLabel = "随方向盘";
            }
            btnModeDSideLinkage.setText(linkageLabel);
            syncModeDSideLinkagePillBg();
        }
        if (btnSideFeedSwitch != null) {
            btnSideFeedSwitch.setText(
                    modeDRightSideIndex == PreviewLayoutModel.RIGHT ? "右侧 ⇄ 左侧" : "左侧 ⇄ 右侧");
        }
        modeDSideControlsContainer.bringToFront();
    }

    @Nullable
    private FrameLayout findFrameByPaneIndex(int targetIndex) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof FrameLayout && paneIndex(child) == targetIndex) {
                return (FrameLayout) child;
            }
        }
        return null;
    }

    private void restoreOrderForMode(PreviewMode mode) {
        ModeStorageSpec spec = STORAGE_SPECS.get(mode);
        if (spec == null) return;
        boolean hasNew = preferences.contains(spec.orderPrefix + "0");
        String effectivePrefix = hasNew ? spec.orderPrefix
                : (spec.legacySavedKey != null && preferences.getBoolean(spec.legacySavedKey, false)
                ? spec.legacyOrderPrefix : null);
        if (effectivePrefix == null) return;
        int[] order = new int[4];
        boolean[] seen = new boolean[4];
        for (int i = 0; i < 4; i++) {
            order[i] = preferences.getInt(effectivePrefix + i, i);
            if (order[i] < 0 || order[i] > 3 || seen[order[i]]) return;
            seen[order[i]] = true;
        }
        applyOrder(order);
    }

    private void applyOrder(int[] order) {
        if (order == null) return;
        for (int index : order) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (paneIndex(child) == index) {
                    child.bringToFront();
                    break;
                }
            }
        }
    }

    static int paneIndex(View view) {
        Object tag = view.getTag();
        if ("left".equals(tag)) return PreviewLayoutModel.LEFT;
        if ("front".equals(tag)) return PreviewLayoutModel.FRONT;
        if ("back".equals(tag)) return PreviewLayoutModel.BACK;
        return PreviewLayoutModel.RIGHT;
    }

    @Nullable
    public static String cameraKeyForIndex(int index) {
        if (index == PreviewLayoutModel.FRONT) return "front";
        if (index == PreviewLayoutModel.BACK) return "back";
        if (index == PreviewLayoutModel.LEFT) return "left";
        if (index == PreviewLayoutModel.RIGHT) return "right";
        return null;
    }

    @NonNull
    public static String defaultCameraLabel(int index) {
        if (index == PreviewLayoutModel.FRONT) return "前视";
        if (index == PreviewLayoutModel.BACK) return "后视";
        if (index == PreviewLayoutModel.LEFT) return "左侧";
        if (index == PreviewLayoutModel.RIGHT) return "右侧";
        return "未知";
    }

    public PreviewMode getCurrentMode() {
        return currentMode;
    }

    public int getModeCPrimaryIndex() {
        return modeCPrimaryIndex;
    }

    public int getModeDLeftPrimaryIndex() {
        return modeDLeftPrimaryIndex;
    }

    public int getModeDRightSideIndex() {
        return modeDRightSideIndex;
    }

    public boolean[] getEffectiveVisibilityMask() {
        boolean[] copy = new boolean[PreviewLayoutModel.PANE_COUNT];
        if (effectiveLayout != null) {
            System.arraycopy(effectiveLayout.visible, 0, copy, 0, PreviewLayoutModel.PANE_COUNT);
        } else {
            for (int i = 0; i < PreviewLayoutModel.PANE_COUNT; i++) copy[i] = true;
        }
        return copy;
    }

    public boolean isPaneVisibleInMode(int index) {
        if (currentMode == PreviewMode.MODE_E) {
            return index == PreviewLayoutModel.LEFT || index == PreviewLayoutModel.RIGHT;
        }
        if (currentMode == PreviewMode.MODE_D) {
            return index == modeDLeftPrimaryIndex || index == modeDRightSideIndex;
        }
        if (currentMode == PreviewMode.MODE_C) {
            if (index == PreviewLayoutModel.LEFT) return modeCShowLeft;
            if (index == PreviewLayoutModel.RIGHT) return modeCShowRight;
            return index == modeCPrimaryIndex;
        }
        return index >= 0 && index < PreviewLayoutModel.PANE_COUNT && modeBVisible[index];
    }

    public void setPreviewState(@NonNull PreviewMode mode, int primaryIndex) {
        setPreviewState(mode, primaryIndex, PreviewLayoutModel.LEFT);
    }

    /** Switch active preview mode and/or paired feeds (Mode C/D/E), recomputing layout and visibility. */
    public void setPreviewState(@NonNull PreviewMode mode, int primaryIndex, int modeDRightSide) {
        PreviewMode normalizedMode = STORAGE_SPECS.containsKey(mode) ? mode : PreviewMode.MODE_B;
        int normalizedPrimary = (primaryIndex == PreviewLayoutModel.BACK)
                ? PreviewLayoutModel.BACK : PreviewLayoutModel.FRONT;
        int normalizedSide = (modeDRightSide == PreviewLayoutModel.RIGHT)
                ? PreviewLayoutModel.RIGHT : PreviewLayoutModel.LEFT;

        if (editing && normalizedMode != currentMode) {
            finishEditing(false);
        }
        boolean modeChanged = currentMode != normalizedMode;
        boolean primaryChanged = (normalizedMode == PreviewMode.MODE_E)
                ? false
                : ((normalizedMode == PreviewMode.MODE_D)
                ? (modeDLeftPrimaryIndex != normalizedPrimary || modeDRightSideIndex != normalizedSide)
                : (modeCPrimaryIndex != normalizedPrimary));

        currentMode = normalizedMode;
        if (normalizedMode == PreviewMode.MODE_D) {
            modeDLeftPrimaryIndex = normalizedPrimary;
            modeDRightSideIndex = normalizedSide;
        } else if (normalizedMode == PreviewMode.MODE_C) {
            modeCPrimaryIndex = normalizedPrimary;
        } else if (normalizedMode == PreviewMode.MODE_B) {
            modeCPrimaryIndex = normalizedPrimary;
        }
        if (modeChanged) {
            restoreOrderForMode(currentMode);
        }
        if (modeChanged || primaryChanged || effectiveLayout == null) {
            recomputeEffectiveLayout(true);
            notifyTransformInvalidated();
        }
    }

    /** Toggles camera pane visibility in the current mode; Mode B guarantees at least one visible pane. */
    public boolean togglePaneVisibility(int index) {
        if (index < 0 || index >= PreviewLayoutModel.PANE_COUNT) return false;
        if (currentMode == PreviewMode.MODE_E) {
            if (index == PreviewLayoutModel.LEFT || index == PreviewLayoutModel.RIGHT) {
                selected = index;
                invalidate();
                if (editorSelectionListener != null) {
                    editorSelectionListener.onSelectedPaneChanged(selected);
                }
            }
            return true;
        }
        if (currentMode == PreviewMode.MODE_D) {
            if (index == PreviewLayoutModel.FRONT || index == PreviewLayoutModel.BACK) {
                modeDLeftPrimaryIndex = index;
            } else {
                modeDRightSideIndex = index;
            }
            recomputeEffectiveLayout(true);
            return true;
        }
        if (currentMode == PreviewMode.MODE_C) {
            if (index == PreviewLayoutModel.LEFT) {
                modeCShowLeft = !modeCShowLeft;
            } else if (index == PreviewLayoutModel.RIGHT) {
                modeCShowRight = !modeCShowRight;
            } else if (index == PreviewLayoutModel.FRONT || index == PreviewLayoutModel.BACK) {
                modeCPrimaryIndex = index;
            }
            if (!editing) saveModeCVisibility();
            recomputeEffectiveLayout(true);
            return true;
        }

        if (modeBVisible[index] && PreviewLayoutModel.countVisible(modeBVisible) <= 1) {
            return false;
        }
        modeBVisible[index] = !modeBVisible[index];
        if (!editing) saveModeBVisibility();
        recomputeEffectiveLayout(true);
        return true;
    }

    public boolean[] getModeBVisibleCopy() {
        return modeBVisible.clone();
    }

    public boolean isModeCShowLeft() {
        return modeCShowLeft;
    }

    public boolean isModeCShowRight() {
        return modeCShowRight;
    }

    private void saveModeBVisibility() {
        SharedPreferences.Editor editor = preferences.edit();
        for (int i = 0; i < PreviewLayoutModel.PANE_COUNT; i++) {
            editor.putBoolean("b_vis_" + i, modeBVisible[i]);
        }
        editor.apply();
    }

    private void saveModeCVisibility() {
        preferences.edit()
                .putBoolean("c_vis_left", modeCShowLeft)
                .putBoolean("c_vis_right", modeCShowRight)
                .apply();
    }

    private PreviewLayoutModel activeModel() {
        PreviewLayoutModel m = modeModels.get(currentMode);
        return m != null ? m : modeModels.get(PreviewMode.MODE_B);
    }

    private void recomputeEffectiveLayout(boolean notify) {
        if (currentMode == PreviewMode.MODE_E) {
            effectiveLayout = activeModel().computeEffectiveModeE();
        } else if (currentMode == PreviewMode.MODE_D) {
            effectiveLayout = activeModel().computeEffectiveModeD(modeDLeftPrimaryIndex, modeDRightSideIndex);
        } else if (currentMode == PreviewMode.MODE_C) {
            effectiveLayout = activeModel().computeEffectiveModeC(
                    modeCPrimaryIndex, modeCShowLeft, modeCShowRight);
        } else {
            effectiveLayout = activeModel().computeEffectiveModeB(modeBVisible);
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int idx = paneIndex(child);
            int desiredVisibility = effectiveLayout.visible[idx] ? View.VISIBLE : View.GONE;
            if (child.getVisibility() != desiredVisibility) {
                child.setVisibility(desiredVisibility);
            }
        }
        syncMainFeedSwitchPill();
        syncModeDSideControls();
        requestLayout();
        invalidate();
        if (notify && stateChangeListener != null) {
            stateChangeListener.onEffectiveVisibilityChanged(getEffectiveVisibilityMask());
        }
    }

    private void notifyTransformInvalidated() {
        if (transformInvalidatedListener != null) {
            post(() -> {
                if (transformInvalidatedListener != null) {
                    transformInvalidatedListener.onPreviewTransformsInvalidated();
                }
            });
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        int gap = Math.round(GAP_DP * density);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            int idx = paneIndex(child);
            PreviewLayoutModel.Pane pane = (effectiveLayout != null)
                    ? effectiveLayout.panes[idx] : activeModel().pane(idx);
            int w = Math.max(1, Math.round(pane.width * width) - gap);
            int h = Math.max(1, Math.round(pane.height * height) - gap);
            child.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int halfGap = Math.round((GAP_DP * 0.5f) * density);
        boolean boundsChanged = false;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            int idx = paneIndex(child);
            PreviewLayoutModel.Pane pane = (effectiveLayout != null)
                    ? effectiveLayout.panes[idx] : activeModel().pane(idx);
            int x = Math.round(pane.x * getWidth()) + halfGap;
            int y = Math.round(pane.y * getHeight()) + halfGap;
            int mw = child.getMeasuredWidth();
            int mh = child.getMeasuredHeight();
            child.layout(x, y, x + mw, y + mh);
            if (lastPaneWidths[idx] != mw || lastPaneHeights[idx] != mh) {
                lastPaneWidths[idx] = mw;
                lastPaneHeights[idx] = mh;
                boundsChanged = true;
                if (roundedClipEnabled) {
                    child.invalidateOutline();
                }
            }
        }
        if (boundsChanged && transformInvalidatedListener != null) {
            post(() -> {
                if (transformInvalidatedListener != null) {
                    transformInvalidatedListener.onPreviewTransformsInvalidated();
                }
            });
        }
    }

    public boolean isEditing() {
        return editing;
    }

    public boolean isCropEditMode() {
        return cropEditMode;
    }

    public void setCropEditMode(boolean cropMode) {
        this.cropEditMode = cropMode;
        if (cropMode && (selected < 0 || !isPaneVisibleInMode(selected))) {
            selected = findFirstVisiblePane();
        }
        invalidate();
        if (editorSelectionListener != null) {
            editorSelectionListener.onSelectedPaneChanged(selected);
        }
    }

    public int getSelectedPaneIndex() {
        return selected;
    }

    private int findFirstVisiblePane() {
        for (int idx = 0; idx < PreviewLayoutModel.PANE_COUNT; idx++) {
            if (isPaneVisibleInMode(idx)) return idx;
        }
        return -1;
    }

    public void beginEditing() {
        if (editing) return;
        PreviewLayoutModel model = activeModel();
        beforeEditing = model.snapshot();
        beforeEditingModeBVisible = modeBVisible.clone();
        beforeEditingCLeft = modeCShowLeft;
        beforeEditingCRight = modeCShowRight;
        beforeEditingCPrimary = modeCPrimaryIndex;
        beforeEditingDPrimary = modeDLeftPrimaryIndex;
        beforeEditingDRight = modeDRightSideIndex;
        previousOrder = new int[getChildCount()];
        for (int i = 0; i < previousOrder.length; i++) {
            previousOrder[i] = paneIndex(getChildAt(i));
        }

        beforeCropFill = new boolean[SLOT_KEYS.length];
        beforeCropZoom = new float[SLOT_KEYS.length];
        beforeCropPanX = new float[SLOT_KEYS.length];
        beforeCropPanY = new float[SLOT_KEYS.length];
        String modeKey = currentMode.key;
        for (int i = 0; i < SLOT_KEYS.length; i++) {
            String camKey = SLOT_KEYS[i];
            beforeCropFill[i] = appConfig.isPreviewSlotFillContainer(modeKey, camKey);
            beforeCropZoom[i] = appConfig.getPreviewSlotZoom(modeKey, camKey);
            beforeCropPanX[i] = appConfig.getPreviewSlotPanX(modeKey, camKey);
            beforeCropPanY[i] = appConfig.getPreviewSlotPanY(modeKey, camKey);
        }

        editing = true;
        setWillNotDraw(false);
        cropEditMode = false;
        opacityPreviewMode = false;
        selected = findFirstVisiblePane();
        syncMainFeedSwitchPill();
        syncModeDSideControls();
        invalidate();
    }

    public void applyPreset(int preset) {
        activeModel().preset(preset);
        if (currentMode == PreviewMode.MODE_C) {
            modeCShowLeft = true;
            modeCShowRight = true;
        }
        selected = findFirstVisiblePane();
        recomputeEffectiveLayout(true);
    }

    public void resetCurrentModeDefault() {
        ModeStorageSpec spec = STORAGE_SPECS.get(currentMode);
        if (spec != null) {
            activeModel().preset(spec.defaultPreset);
        }
        if (currentMode == PreviewMode.MODE_B) {
            for (int i = 0; i < PreviewLayoutModel.PANE_COUNT; i++) {
                modeBVisible[i] = true;
            }
        } else if (currentMode == PreviewMode.MODE_C) {
            modeCShowLeft = true;
            modeCShowRight = true;
        }
        selected = findFirstVisiblePane();
        if (!editing) {
            persistCurrentMode(false);
        }
        recomputeEffectiveLayout(true);
    }

    public void resetSelectedSlotCrop() {
        int target = (selected >= 0 && isPaneVisibleInMode(selected)) ? selected : findFirstVisiblePane();
        if (target < 0) return;
        String camKey = cameraKeyForIndex(target);
        if (camKey == null) return;
        appConfig.resetPreviewSlotTransform(currentMode.key, camKey);
        notifyTransformInvalidated();
    }

    public void finishEditing(boolean save) {
        if (!editing) return;
        if (save) {
            if (currentMode == PreviewMode.MODE_C) {
                int inactive = modeCPrimaryIndex == PreviewLayoutModel.BACK
                        ? PreviewLayoutModel.FRONT : PreviewLayoutModel.BACK;
                activeModel().syncPairedSlot(modeCPrimaryIndex, inactive);
            } else if (currentMode == PreviewMode.MODE_D) {
                int inactiveLeft = modeDLeftPrimaryIndex == PreviewLayoutModel.BACK
                        ? PreviewLayoutModel.FRONT : PreviewLayoutModel.BACK;
                int inactiveRight = modeDRightSideIndex == PreviewLayoutModel.RIGHT
                        ? PreviewLayoutModel.LEFT : PreviewLayoutModel.RIGHT;
                activeModel().syncPairedSlot(modeDLeftPrimaryIndex, inactiveLeft);
                activeModel().syncPairedSlot(modeDRightSideIndex, inactiveRight);
            } else if (currentMode == PreviewMode.MODE_E) {
                activeModel().syncPairedSlot(PreviewLayoutModel.LEFT, PreviewLayoutModel.FRONT);
                activeModel().syncPairedSlot(PreviewLayoutModel.RIGHT, PreviewLayoutModel.BACK);
            }
            persistCurrentMode(true);
        } else {
            activeModel().restore(beforeEditing);
            if (beforeEditingModeBVisible != null) {
                System.arraycopy(beforeEditingModeBVisible, 0, modeBVisible, 0, PreviewLayoutModel.PANE_COUNT);
            }
            modeCShowLeft = beforeEditingCLeft;
            modeCShowRight = beforeEditingCRight;
            modeCPrimaryIndex = beforeEditingCPrimary;
            modeDLeftPrimaryIndex = beforeEditingDPrimary;
            modeDRightSideIndex = beforeEditingDRight;
            applyOrder(previousOrder);

            if (beforeCropFill != null) {
                String modeKey = currentMode.key;
                for (int i = 0; i < SLOT_KEYS.length; i++) {
                    String camKey = SLOT_KEYS[i];
                    appConfig.setPreviewSlotFillContainer(modeKey, camKey, beforeCropFill[i]);
                    appConfig.setPreviewSlotZoom(modeKey, camKey, beforeCropZoom[i]);
                    appConfig.setPreviewSlotPan(modeKey, camKey, beforeCropPanX[i], beforeCropPanY[i]);
                }
            }
        }
        editing = false;
        setWillNotDraw(true);
        cropEditMode = false;
        opacityPreviewMode = false;
        dragOriginSnapshot = null;
        selected = -1;
        beforeEditing = null;
        beforeEditingModeBVisible = null;
        beforeCropFill = null;
        beforeCropZoom = null;
        beforeCropPanX = null;
        beforeCropPanY = null;
        previousOrder = null;
        recomputeEffectiveLayout(!save);
        notifyTransformInvalidated();
    }

    private void persistCurrentMode(boolean includeOrder) {
        ModeStorageSpec spec = STORAGE_SPECS.get(currentMode);
        if (spec == null) return;
        PreviewLayoutModel model = activeModel();
        float[] values = model.snapshot();

        SharedPreferences.Editor editor = preferences.edit().putBoolean(spec.savedKey, true);
        for (int i = 0; i < values.length; i++) {
            editor.putFloat(spec.panePrefix + i, values[i]);
        }
        if (includeOrder) {
            for (int i = 0; i < getChildCount(); i++) {
                editor.putInt(spec.orderPrefix + i, paneIndex(getChildAt(i)));
            }
        }
        if (currentMode == PreviewMode.MODE_C) {
            editor.putBoolean("c_vis_left", modeCShowLeft);
            editor.putBoolean("c_vis_right", modeCShowRight);
        } else if (currentMode == PreviewMode.MODE_B) {
            for (int i = 0; i < PreviewLayoutModel.PANE_COUNT; i++) {
                editor.putBoolean("b_vis_" + i, modeBVisible[i]);
            }
        }
        editor.apply();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return editing;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editing) return super.onTouchEvent(event);
        if (opacityPreviewMode) return true;
        if (cropEditMode) {
            return handleCropTouch(event);
        }
        return handleLayoutTouch(event);
    }

    private boolean handleCropTouch(MotionEvent event) {
        pinchDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            int hit = findHitPaneIndex(event.getX(), event.getY());
            if (hit >= 0) {
                selected = hit;
                String camKey = cameraKeyForIndex(selected);
                if (camKey != null) {
                    startCropPanX = appConfig.getPreviewSlotPanX(currentMode.key, camKey);
                    startCropPanY = appConfig.getPreviewSlotPanY(currentMode.key, camKey);
                }
                startX = event.getX();
                startY = event.getY();
                if (editorSelectionListener != null) {
                    editorSelectionListener.onSelectedPaneChanged(selected);
                }
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1
                && selected >= 0 && !pinchDetector.isInProgress()) {
            View child = findFrameByPaneIndex(selected);
            String camKey = cameraKeyForIndex(selected);
            if (child != null && child.getWidth() > 0 && child.getHeight() > 0 && camKey != null) {
                float dxFrac = (event.getX() - startX) / child.getWidth();
                float dyFrac = (event.getY() - startY) / child.getHeight();
                appConfig.setPreviewSlotPan(currentMode.key, camKey, startCropPanX + dxFrac, startCropPanY + dyFrac);
                notifyTransformInvalidated();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            performClick();
        }
        return true;
    }

    private boolean handleLayoutTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            selected = -1;
            dragOriginSnapshot = null;
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                if (event.getX() >= child.getLeft() && event.getX() <= child.getRight()
                        && event.getY() >= child.getTop() && event.getY() <= child.getBottom()) {
                    selected = paneIndex(child);
                    resizing = event.getX() >= child.getRight() - 48 * density
                            && event.getY() >= child.getBottom() - 48 * density;
                    PreviewLayoutModel model = activeModel();
                    dragOriginSnapshot = model.snapshot();
                    PreviewLayoutModel.Pane pane = (effectiveLayout != null)
                            ? effectiveLayout.panes[selected]
                            : model.pane(selected);
                    originalX = pane.x;
                    originalY = pane.y;
                    originalWidth = pane.width;
                    originalHeight = pane.height;
                    startX = event.getX();
                    startY = event.getY();
                    child.bringToFront();
                    break;
                }
            }
            if (editorSelectionListener != null) {
                editorSelectionListener.onSelectedPaneChanged(selected);
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && selected >= 0 && getWidth() > 0 && getHeight() > 0) {
            float dx = (event.getX() - startX) / getWidth();
            float dy = (event.getY() - startY) / getHeight();
            PreviewLayoutModel model = activeModel();
            boolean[] mask = getEffectiveVisibilityMask();
            if (resizing) {
                model.resizePaneWithCollision(selected, originalWidth + dx, originalHeight + dy, mask);
            } else {
                model.movePaneWithCollision(selected, originalX + dx, originalY + dy, mask, dragOriginSnapshot);
            }
            if (effectiveLayout != null) {
                for (int i = 0; i < PreviewLayoutModel.PANE_COUNT; i++) {
                    PreviewLayoutModel.Pane src = model.pane(i);
                    effectiveLayout.panes[i].x = src.x;
                    effectiveLayout.panes[i].y = src.y;
                    effectiveLayout.panes[i].width = src.width;
                    effectiveLayout.panes[i].height = src.height;
                }
            }
            requestLayout();
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragOriginSnapshot = null;
            if (action == MotionEvent.ACTION_UP) {
                performClick();
            }
        }
        return true;
    }

    private int findHitPaneIndex(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (x >= child.getLeft() && x <= child.getRight()
                    && y >= child.getTop() && y <= child.getBottom()) {
                return paneIndex(child);
            }
        }
        return -1;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!editing || opacityPreviewMode) return;
        float size = 32 * density;
        float frameRadius = roundedClipEnabled ? cornerRadiusPx : 0f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            border.setStrokeWidth((paneIndex(child) == selected ? 3 : 1.5f) * density);
            canvas.drawRoundRect(
                    child.getLeft() + 1,
                    child.getTop() + 1,
                    child.getRight() - 1,
                    child.getBottom() - 1,
                    frameRadius,
                    frameRadius,
                    border);
            if (!cropEditMode) {
                float x = child.getRight() - size;
                float y = child.getBottom() - size;
                canvas.drawRoundRect(x, y, child.getRight(), child.getBottom(), 6 * density, 6 * density, handle);
                canvas.drawLine(x + 10 * density, y + 10 * density, x + 23 * density, y + 23 * density, arrow);
                canvas.drawLine(x + 15 * density, y + 23 * density, x + 23 * density, y + 23 * density, arrow);
                canvas.drawLine(x + 23 * density, y + 15 * density, x + 23 * density, y + 23 * density, arrow);
            }
        }
    }
}
