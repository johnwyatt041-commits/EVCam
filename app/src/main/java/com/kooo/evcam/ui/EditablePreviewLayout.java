package com.kooo.evcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.content.ContextCompat;
import com.kooo.evcam.R;

/** Repositions the same four TextureView parents. Editing never replaces their surfaces. */
public final class EditablePreviewLayout extends ViewGroup {
    private final PreviewLayoutModel model = new PreviewLayoutModel();
    private final SharedPreferences preferences;
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean editing;
    private boolean resizing;
    private int selected = -1;
    private float startX, startY, originalX, originalY, originalWidth, originalHeight;
    private float[] beforeEditing;
    private int[] previousOrder;

    public EditablePreviewLayout(Context context, AttributeSet attributes) {
        super(context, attributes);
        density = getResources().getDisplayMetrics().density;
        preferences = context.getSharedPreferences("e5_preview_layout_v1", Context.MODE_PRIVATE);
        if (preferences.getBoolean("saved", false)) {
            float[] values = new float[16];
            for (int i = 0; i < values.length; i++) values[i] = preferences.getFloat("pane_" + i, Float.NaN);
            model.restore(values);
        }
        border.setColor(ContextCompat.getColor(context, R.color.button_accent)); border.setStyle(Paint.Style.STROKE);
        handle.setColor(ContextCompat.getColor(context, R.color.button_accent));
        arrow.setColor(ContextCompat.getColor(context, R.color.ui_on_accent)); arrow.setStrokeWidth(2 * density);
        setWillNotDraw(false);
    }
    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        if (preferences.getBoolean("saved", false)) {
            int[] order = new int[4]; boolean[] seen = new boolean[4];
            for (int i = 0; i < 4; i++) {
                order[i] = preferences.getInt("order_" + i, i);
                if (order[i] < 0 || order[i] > 3 || seen[order[i]]) return;
                seen[order[i]] = true;
            }
            applyOrder(order);
        }
    }
    private void applyOrder(int[] order) {
        if (order == null) return;
        for (int index : order) for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (paneIndex(child) == index) { child.bringToFront(); break; }
        }
    }
    private int paneIndex(View view) {
        Object tag = view.getTag();
        if ("left".equals(tag)) return PreviewLayoutModel.LEFT;
        if ("front".equals(tag)) return PreviewLayoutModel.FRONT;
        if ("back".equals(tag)) return PreviewLayoutModel.BACK;
        return PreviewLayoutModel.RIGHT;
    }
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec), height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        int gap = Math.round(12 * density);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i); PreviewLayoutModel.Pane pane = model.pane(paneIndex(child));
            int w = Math.max(1, Math.round(pane.width * width) - gap);
            int h = Math.max(1, Math.round(pane.height * height) - gap);
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int gap = Math.round(6 * density);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i); PreviewLayoutModel.Pane pane = model.pane(paneIndex(child));
            int x = Math.round(pane.x * getWidth()) + gap, y = Math.round(pane.y * getHeight()) + gap;
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        }
    }
    public boolean isEditing() { return editing; }
    public void beginEditing() {
        if (editing) return;
        beforeEditing = model.snapshot();
        previousOrder = new int[getChildCount()];
        for (int i = 0; i < previousOrder.length; i++) previousOrder[i] = paneIndex(getChildAt(i));
        editing = true; selected = -1; invalidate();
    }
    public void applyPreset(int preset) { model.preset(preset); selected = -1; requestLayout(); invalidate(); }
    public void finishEditing(boolean save) {
        if (!editing) return;
        if (save) {
            float[] values = model.snapshot(); SharedPreferences.Editor editor = preferences.edit().putBoolean("saved", true);
            for (int i = 0; i < values.length; i++) editor.putFloat("pane_" + i, values[i]);
            for (int i = 0; i < getChildCount(); i++) editor.putInt("order_" + i, paneIndex(getChildAt(i)));
            editor.apply();
        } else { model.restore(beforeEditing); applyOrder(previousOrder); }
        editing = false; selected = -1; beforeEditing = null; previousOrder = null; requestLayout(); invalidate();
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return editing; }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!editing) return super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            selected = -1;
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View child = getChildAt(i);
                if (event.getX() >= child.getLeft() && event.getX() <= child.getRight()
                        && event.getY() >= child.getTop() && event.getY() <= child.getBottom()) {
                    selected = paneIndex(child);
                    resizing = event.getX() >= child.getRight() - 48 * density && event.getY() >= child.getBottom() - 48 * density;
                    PreviewLayoutModel.Pane pane = model.pane(selected);
                    originalX = pane.x; originalY = pane.y; originalWidth = pane.width; originalHeight = pane.height;
                    startX = event.getX(); startY = event.getY(); child.bringToFront(); break;
                }
            }
            invalidate(); return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && selected >= 0 && getWidth() > 0 && getHeight() > 0) {
            float dx = (event.getX() - startX) / getWidth(), dy = (event.getY() - startY) / getHeight();
            if (resizing) model.resize(selected, originalWidth + dx, originalHeight + dy);
            else model.move(selected, originalX + dx, originalY + dy);
            requestLayout(); invalidate(); return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!editing) return;
        float size = 32 * density;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i); border.setStrokeWidth((paneIndex(child) == selected ? 3 : 1.5f) * density);
            canvas.drawRect(child.getLeft() + 1, child.getTop() + 1, child.getRight() - 1, child.getBottom() - 1, border);
            float x = child.getRight() - size, y = child.getBottom() - size;
            canvas.drawRoundRect(x, y, child.getRight(), child.getBottom(), 6 * density, 6 * density, handle);
            canvas.drawLine(x + 10 * density, y + 10 * density, x + 23 * density, y + 23 * density, arrow);
            canvas.drawLine(x + 15 * density, y + 23 * density, x + 23 * density, y + 23 * density, arrow);
            canvas.drawLine(x + 23 * density, y + 15 * density, x + 23 * density, y + 23 * density, arrow);
        }
    }
}
