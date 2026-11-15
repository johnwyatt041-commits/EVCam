package com.kooo.evcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.kooo.evcam.R;

/**
 * Repositions and clips the same four TextureView parents across Mode B / Mode C.
 * Hides non-active panes with View.GONE instead of detaching views so SurfaceTexture recreation stays clean.
 */
public final class EditablePreviewLayout extends ViewGroup {

    public interface OnStateChangeListener {
        void onEffectiveVisibilityChanged(boolean[] visibleMask);
    }

    private static final float CORNER_RADIUS_DP = 16f;
    private static final float GAP_DP = 12f;

    private final PreviewLayoutModel modeBModel = new PreviewLayoutModel(PreviewLayoutModel.PRESET_MODE_B_THREE_COL);
    private final PreviewLayoutModel modeCModel = new PreviewLayoutModel(PreviewLayoutModel.PRESET_MODE_C_FRONT);
    private final SharedPreferences preferences;
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float cornerRadiusPx;

    private final boolean[] modeBVisible = {true, true, true, true};
    private boolean modeCShowLeft = true;
    private boolean modeCShowRight = true;
    private PreviewMode currentMode = PreviewMode.MODE_B;
    private int modeCPrimaryIndex = PreviewLayoutModel.FRONT;
    private PreviewLayoutModel.EffectiveLayout effectiveLayout;

    private boolean editing;
    private boolean resizing;
    private int selected = -1;
    private float startX, startY, originalX, originalY, originalWidth, originalHeight;
    private float[] beforeEditing;
    private boolean[] beforeEditingModeBVisible;
    private boolean beforeEditingCLeft;
    private boolean beforeEditingCRight;
    private int beforeEditingCPrimary = PreviewLayoutModel.FRONT;
    private int[] previousOrder;
    @Nullable
    private OnStateChangeListener stateChangeListener;

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
        loadSavedState();

        border.setColor(ContextCompat.getColor(context, R.color.button_accent));
        border.setStyle(Paint.Style.STROKE);
        handle.setColor(ContextCompat.getColor(context, R.color.button_accent));
        arrow.setColor(ContextCompat.getColor(context, R.color.ui_on_accent));
        arrow.setStrokeWidth(2 * density);
        setWillNotDraw(false);
        recomputeEffectiveLayout(false);
    }

    private void loadSavedState() {
        // Load Mode B (prefer new b_* keys; migrate legacy T02 saved keys transparently).
        if (preferences.getBoolean("b_saved", false)) {
            restoreModel(modeBModel, "b_pane_");
        } else if (preferences.getBoolean("saved", false)) {
            restoreModel(modeBModel, "pane_");
        }
        for (int i = 0; i < PreviewLayoutModel.PANE_COUNT; i++) {
            modeBVisible[i] = preferences.getBoolean("b_vis_" + i, true);
        }
        PreviewLayoutModel.normalizeVisibleAtLeastOne(modeBVisible, PreviewLayoutModel.FRONT);

        if (preferences.getBoolean("c_saved", false)) {
            restoreModel(modeCModel, "c_pane_");
        }
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

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        for (int i = 0; i < getChildCount(); i++) {
            configurePaneOutline(getChildAt(i));
        }
        restoreOrderForMode(currentMode);
        recomputeEffectiveLayout(false);
    }

    private void configurePaneOutline(View child) {
        if (child == null) return;
        child.setOutlineProvider(paneOutlineProvider);
        child.setClipToOutline(true);
    }

    private void restoreOrderForMode(PreviewMode mode) {
        String orderPrefix = mode == PreviewMode.MODE_C ? "c_order_" : "b_order_";
        boolean hasNewOrder = preferences.contains(orderPrefix + "0");
        String effectivePrefix = hasNewOrder ? orderPrefix
                : (mode == PreviewMode.MODE_B && preferences.getBoolean("saved", false) ? "order_" : null);
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

    public PreviewMode getCurrentMode() {
        return currentMode;
    }

    public int getModeCPrimaryIndex() {
        return modeCPrimaryIndex;
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
        if (currentMode == PreviewMode.MODE_C) {
            if (index == PreviewLayoutModel.LEFT) return modeCShowLeft;
            if (index == PreviewLayoutModel.RIGHT) return modeCShowRight;
            return index == modeCPrimaryIndex;
        }
        return index >= 0 && index < PreviewLayoutModel.PANE_COUNT && modeBVisible[index];
    }

    /** Switch active preview mode and/or Mode C primary feed (front/back), recomputing layout and visibility. */
    public void setPreviewState(@NonNull PreviewMode mode, int modeCPrimary) {
        PreviewMode normalizedMode = (mode == PreviewMode.MODE_C) ? PreviewMode.MODE_C : PreviewMode.MODE_B;
        int normalizedPrimary = (modeCPrimary == PreviewLayoutModel.BACK)
                ? PreviewLayoutModel.BACK : PreviewLayoutModel.FRONT;
        if (editing && normalizedMode != currentMode) {
            finishEditing(false);
        }
        boolean modeChanged = currentMode != normalizedMode;
        boolean primaryChanged = modeCPrimaryIndex != normalizedPrimary;
        currentMode = normalizedMode;
        modeCPrimaryIndex = normalizedPrimary;
        if (modeChanged) {
            restoreOrderForMode(currentMode);
        }
        if (modeChanged || primaryChanged || effectiveLayout == null) {
            recomputeEffectiveLayout(true);
        }
    }

    /** Toggles camera pane visibility in the current mode; Mode B guarantees at least one visible pane. */
    public boolean togglePaneVisibility(int index) {
        if (index < 0 || index >= PreviewLayoutModel.PANE_COUNT) return false;
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
        return currentMode == PreviewMode.MODE_C ? modeCModel : modeBModel;
    }

    private void recomputeEffectiveLayout(boolean notify) {
        if (currentMode == PreviewMode.MODE_C) {
            effectiveLayout = modeCModel.computeEffectiveModeC(
                    modeCPrimaryIndex, modeCShowLeft, modeCShowRight);
        } else {
            effectiveLayout = modeBModel.computeEffectiveModeB(modeBVisible);
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int idx = paneIndex(child);
            int desiredVisibility = effectiveLayout.visible[idx] ? View.VISIBLE : View.GONE;
            if (child.getVisibility() != desiredVisibility) {
                child.setVisibility(desiredVisibility);
            }
        }
        requestLayout();
        invalidate();
        if (notify && stateChangeListener != null) {
            stateChangeListener.onEffectiveVisibilityChanged(getEffectiveVisibilityMask());
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
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            int idx = paneIndex(child);
            PreviewLayoutModel.Pane pane = (effectiveLayout != null)
                    ? effectiveLayout.panes[idx] : activeModel().pane(idx);
            int x = Math.round(pane.x * getWidth()) + halfGap;
            int y = Math.round(pane.y * getHeight()) + halfGap;
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
            child.invalidateOutline();
        }
    }

    public boolean isEditing() {
        return editing;
    }

    public void beginEditing() {
        if (editing) return;
        PreviewLayoutModel model = activeModel();
        beforeEditing = model.snapshot();
        beforeEditingModeBVisible = modeBVisible.clone();
        beforeEditingCLeft = modeCShowLeft;
        beforeEditingCRight = modeCShowRight;
        beforeEditingCPrimary = modeCPrimaryIndex;
        previousOrder = new int[getChildCount()];
        for (int i = 0; i < previousOrder.length; i++) {
            previousOrder[i] = paneIndex(getChildAt(i));
        }
        editing = true;
        selected = -1;
        invalidate();
    }

    public void applyPreset(int preset) {
        activeModel().preset(preset);
        if (currentMode == PreviewMode.MODE_C) {
            modeCShowLeft = true;
            modeCShowRight = true;
        }
        selected = -1;
        recomputeEffectiveLayout(true);
    }

    public void resetCurrentModeDefault() {
        if (currentMode == PreviewMode.MODE_C) {
            modeCModel.preset(PreviewLayoutModel.PRESET_MODE_C_FRONT);
            modeCShowLeft = true;
            modeCShowRight = true;
        } else {
            modeBModel.preset(PreviewLayoutModel.PRESET_MODE_B_THREE_COL);
            for (int i = 0; i < PreviewLayoutModel.PANE_COUNT; i++) {
                modeBVisible[i] = true;
            }
        }
        selected = -1;
        if (!editing) {
            persistCurrentMode(false);
        }
        recomputeEffectiveLayout(true);
    }

    public void finishEditing(boolean save) {
        if (!editing) return;
        if (save) {
            if (currentMode == PreviewMode.MODE_C) {
                syncModeCPrimaryPair();
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
            applyOrder(previousOrder);
        }
        editing = false;
        selected = -1;
        beforeEditing = null;
        beforeEditingModeBVisible = null;
        previousOrder = null;
        recomputeEffectiveLayout(!save);
    }

    private void syncModeCPrimaryPair() {
        int active = (modeCPrimaryIndex == PreviewLayoutModel.BACK)
                ? PreviewLayoutModel.BACK : PreviewLayoutModel.FRONT;
        int inactive = (active == PreviewLayoutModel.BACK)
                ? PreviewLayoutModel.FRONT : PreviewLayoutModel.BACK;
        PreviewLayoutModel.Pane src = modeCModel.pane(active);
        modeCModel.move(inactive, 0f, 0f);
        modeCModel.resize(inactive, src.width, src.height);
        modeCModel.move(inactive, src.x, src.y);
    }

    private void persistCurrentMode(boolean includeOrder) {
        PreviewLayoutModel model = activeModel();
        float[] values = model.snapshot();
        boolean isC = currentMode == PreviewMode.MODE_C;
        String savedKey = isC ? "c_saved" : "b_saved";
        String panePrefix = isC ? "c_pane_" : "b_pane_";
        String orderPrefix = isC ? "c_order_" : "b_order_";

        SharedPreferences.Editor editor = preferences.edit().putBoolean(savedKey, true);
        for (int i = 0; i < values.length; i++) {
            editor.putFloat(panePrefix + i, values[i]);
        }
        if (includeOrder) {
            for (int i = 0; i < getChildCount(); i++) {
                editor.putInt(orderPrefix + i, paneIndex(getChildAt(i)));
            }
        }
        if (isC) {
            editor.putBoolean("c_vis_left", modeCShowLeft);
            editor.putBoolean("c_vis_right", modeCShowRight);
        } else {
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
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            selected = -1;
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                if (event.getX() >= child.getLeft() && event.getX() <= child.getRight()
                        && event.getY() >= child.getTop() && event.getY() <= child.getBottom()) {
                    selected = paneIndex(child);
                    resizing = event.getX() >= child.getRight() - 48 * density
                            && event.getY() >= child.getBottom() - 48 * density;
                    PreviewLayoutModel.Pane pane = (effectiveLayout != null)
                            ? effectiveLayout.panes[selected]
                            : activeModel().pane(selected);
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
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                && selected >= 0 && getWidth() > 0 && getHeight() > 0) {
            float dx = (event.getX() - startX) / getWidth();
            float dy = (event.getY() - startY) / getHeight();
            PreviewLayoutModel model = activeModel();
            if (resizing) {
                model.move(selected, 0f, 0f);
                model.resize(selected, originalWidth + dx, originalHeight + dy);
                model.move(selected, originalX, originalY);
            } else {
                model.move(selected, 0f, 0f);
                model.resize(selected, originalWidth, originalHeight);
                model.move(selected, originalX + dx, originalY + dy);
            }
            if (effectiveLayout != null) {
                PreviewLayoutModel.Pane src = model.pane(selected);
                effectiveLayout.panes[selected].x = src.x;
                effectiveLayout.panes[selected].y = src.y;
                effectiveLayout.panes[selected].width = src.width;
                effectiveLayout.panes[selected].height = src.height;
            }
            requestLayout();
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!editing) return;
        float size = 32 * density;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            border.setStrokeWidth((paneIndex(child) == selected ? 3 : 1.5f) * density);
            canvas.drawRoundRect(
                    child.getLeft() + 1,
                    child.getTop() + 1,
                    child.getRight() - 1,
                    child.getBottom() - 1,
                    cornerRadiusPx,
                    cornerRadiusPx,
                    border);
            float x = child.getRight() - size;
            float y = child.getBottom() - size;
            canvas.drawRoundRect(x, y, child.getRight(), child.getBottom(), 6 * density, 6 * density, handle);
            canvas.drawLine(x + 10 * density, y + 10 * density, x + 23 * density, y + 23 * density, arrow);
            canvas.drawLine(x + 15 * density, y + 23 * density, x + 23 * density, y + 23 * density, arrow);
            canvas.drawLine(x + 23 * density, y + 15 * density, x + 23 * density, y + 23 * density, arrow);
        }
    }
}
