package com.kooo.evcam.blindspot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * 补盲画面位置与大小可视化拖拽编辑器（固定 2560×1600 / 16:10 比例画布）。
 *
 * <p>参照图 4 与 Smartcam 3.0.56：支持切换编辑态（锁定防误触 / 进入编辑），
 * 单指拖动矩形移动位置、拖动右下角 ↘ 手柄等边界约束缩放尺寸。</p>
 */
public class BlindSpotVisualEditorView extends View {

    public interface OnPlacementChangeListener {
        void onPlacementChanged(@NonNull String slot, @NonNull BlindSpotPlacement.RectRatio rect, boolean fromUser);
        void onPlacementCommitted(@NonNull String slot, @NonNull BlindSpotPlacement.RectRatio rect);
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_RESIZE = 2;

    private static final int GRID_COLS = 8;
    private static final int GRID_ROWS = 5;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerAxisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint metricsTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF canvasRect = new RectF();
    private final RectF activeBoxRect = new RectF();
    private final RectF handleRect = new RectF();
    private final Path arrowPath = new Path();

    private String currentSlot = BlindSpotPlacement.SLOT_RIGHT;
    private BlindSpotPlacement.RectRatio currentRect =
            BlindSpotPlacement.defaultForSlot(BlindSpotPlacement.SLOT_RIGHT);
    private boolean editingEnabled = false;
    private int dragMode = MODE_NONE;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private float cornerRadiusPx;
    private float boxRadiusPx;
    private float handleSizePx;
    private float handleMarginPx;
    private float handleTouchSlopPx;
    private float maxCanvasHeightPx;

    @Nullable
    private OnPlacementChangeListener listener;

    public BlindSpotVisualEditorView(Context context) {
        this(context, null);
    }

    public BlindSpotVisualEditorView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BlindSpotVisualEditorView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        cornerRadiusPx = 14f * d;
        boxRadiusPx = 10f * d;
        handleSizePx = 28f * d;
        handleMarginPx = 6f * d;
        handleTouchSlopPx = 44f * d;
        maxCanvasHeightPx = 310f * d;

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(0xFF000000);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f * d);
        borderPaint.setColor(0xFF283244);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * d);
        gridPaint.setColor(0x1EFFFFFF);

        centerAxisPaint.setStyle(Paint.Style.STROKE);
        centerAxisPaint.setStrokeWidth(1.25f * d);
        centerAxisPaint.setColor(0x36FFFFFF);

        boxFillPaint.setStyle(Paint.Style.FILL);
        boxStrokePaint.setStyle(Paint.Style.STROKE);
        boxStrokePaint.setStrokeWidth(2f * d);

        titleTextPaint.setColor(Color.WHITE);
        titleTextPaint.setTextSize(15f * d);
        titleTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titleTextPaint.setTextAlign(Paint.Align.CENTER);

        metricsTextPaint.setColor(0xFFE2E8F0);
        metricsTextPaint.setTextSize(12f * d);
        metricsTextPaint.setTextAlign(Paint.Align.CENTER);

        handleBgPaint.setStyle(Paint.Style.FILL);
        handleBgPaint.setColor(0xFFF8FAFC);

        handleArrowPaint.setStyle(Paint.Style.STROKE);
        handleArrowPaint.setStrokeWidth(2.2f * d);
        handleArrowPaint.setStrokeCap(Paint.Cap.ROUND);
        handleArrowPaint.setStrokeJoin(Paint.Join.ROUND);
        handleArrowPaint.setColor(0xFF1E3A8A);

        updateBoxPaintColors();
    }

    public void setOnPlacementChangeListener(@Nullable OnPlacementChangeListener listener) {
        this.listener = listener;
    }

    public void setPlacement(@NonNull String slot, @NonNull BlindSpotPlacement.RectRatio rect) {
        this.currentSlot = BlindSpotPlacement.normalizeSlot(slot);
        this.currentRect = rect;
        invalidate();
    }

    @NonNull
    public String getCurrentSlot() {
        return currentSlot;
    }

    @NonNull
    public BlindSpotPlacement.RectRatio getCurrentRect() {
        return currentRect;
    }

    public void setEditingEnabled(boolean enabled) {
        if (this.editingEnabled != enabled) {
            this.editingEnabled = enabled;
            if (!enabled) {
                dragMode = MODE_NONE;
            }
            updateBoxPaintColors();
            invalidate();
        }
    }

    public boolean isEditingEnabled() {
        return editingEnabled;
    }

    private void updateBoxPaintColors() {
        if (editingEnabled) {
            boxFillPaint.setColor(0x523B82F6);
            boxStrokePaint.setColor(0xFF8CC2FF);
        } else {
            boxFillPaint.setColor(0x3A475569);
            boxStrokePaint.setColor(0xFF94A3B8);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availW = MeasureSpec.getSize(widthMeasureSpec);
        if (availW <= 0) {
            availW = Math.round(640f * getResources().getDisplayMetrics().density);
        }
        int availH = MeasureSpec.getSize(heightMeasureSpec);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);

        int measuredW = availW;
        int measuredH = Math.round(measuredW * ((float) BlindSpotPlacement.BASE_CANVAS_HEIGHT
                / BlindSpotPlacement.BASE_CANVAS_WIDTH));

        float effectiveMaxH = maxCanvasHeightPx;
        if (hMode == MeasureSpec.AT_MOST || hMode == MeasureSpec.EXACTLY) {
            if (availH > 0) {
                effectiveMaxH = Math.min(effectiveMaxH, availH);
            }
        }
        if (measuredH > effectiveMaxH && effectiveMaxH > 0) {
            measuredH = Math.round(effectiveMaxH);
            measuredW = Math.round(measuredH * ((float) BlindSpotPlacement.BASE_CANVAS_WIDTH
                    / BlindSpotPlacement.BASE_CANVAS_HEIGHT));
        }
        setMeasuredDimension(measuredW, measuredH);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        float inset = borderPaint.getStrokeWidth() * 0.5f;
        canvasRect.set(inset, inset, w - inset, h - inset);
        canvas.drawRoundRect(canvasRect, cornerRadiusPx, cornerRadiusPx, bgPaint);
        canvas.drawRoundRect(canvasRect, cornerRadiusPx, cornerRadiusPx, borderPaint);

        // 绘制 16:10 辅助网格线与中心十字线
        for (int c = 1; c < GRID_COLS; c++) {
            float x = w * c / GRID_COLS;
            Paint p = (c * 2 == GRID_COLS) ? centerAxisPaint : gridPaint;
            canvas.drawLine(x, inset, x, h - inset, p);
        }
        for (int r = 1; r < GRID_ROWS; r++) {
            float y = h * r / GRID_ROWS;
            canvas.drawLine(inset, y, w - inset, y, gridPaint);
        }
        canvas.drawLine(inset, h * 0.5f, w - inset, h * 0.5f, centerAxisPaint);

        // 计算当前激活矩形框位置
        float boxInset = boxStrokePaint.getStrokeWidth();
        float left = Math.max(boxInset, currentRect.x * w);
        float top = Math.max(boxInset, currentRect.y * h);
        float right = Math.min(w - boxInset, (currentRect.x + currentRect.width) * w);
        float bottom = Math.min(h - boxInset, (currentRect.y + currentRect.height) * h);
        activeBoxRect.set(left, top, right, bottom);

        canvas.drawRoundRect(activeBoxRect, boxRadiusPx, boxRadiusPx, boxFillPaint);
        canvas.drawRoundRect(activeBoxRect, boxRadiusPx, boxRadiusPx, boxStrokePaint);

        String slotLabel = BlindSpotPlacement.getSlotDisplayName(currentSlot);
        String line1 = editingEnabled ? (slotLabel + " · 拖动移动") : (slotLabel + " · 已锁定");
        String line2 = String.format(Locale.US, "X %d%%  Y %d%%  宽 %d%%  高 %d%%",
                currentRect.xPercent(),
                currentRect.yPercent(),
                currentRect.widthPercent(),
                currentRect.heightPercent());

        float textCenterX = activeBoxRect.centerX();
        float textCenterY = activeBoxRect.centerY();
        float d = getResources().getDisplayMetrics().density;
        canvas.drawText(line1, textCenterX, textCenterY - 4f * d, titleTextPaint);
        if (activeBoxRect.width() >= 120f * d) {
            canvas.drawText(line2, textCenterX, textCenterY + 18f * d, metricsTextPaint);
        }

        if (editingEnabled) {
            float hLeft = Math.max(activeBoxRect.left + handleMarginPx,
                    activeBoxRect.right - handleMarginPx - handleSizePx);
            float hTop = Math.max(activeBoxRect.top + handleMarginPx,
                    activeBoxRect.bottom - handleMarginPx - handleSizePx);
            handleRect.set(hLeft, hTop, hLeft + handleSizePx, hTop + handleSizePx);
            canvas.drawRoundRect(handleRect, 7f * d, 7f * d, handleBgPaint);

            // 绘制清晰 ↘ 缩放箭头图标
            float cx = handleRect.centerX();
            float cy = handleRect.centerY();
            float arm = handleSizePx * 0.22f;
            arrowPath.reset();
            arrowPath.moveTo(cx - arm, cy - arm);
            arrowPath.lineTo(cx + arm, cy + arm);
            arrowPath.moveTo(cx + arm, cy - arm * 0.1f);
            arrowPath.lineTo(cx + arm, cy + arm);
            arrowPath.lineTo(cx - arm * 0.1f, cy + arm);
            canvas.drawPath(arrowPath, handleArrowPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!editingEnabled) {
            return false;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float boxLeft = currentRect.x * w;
                float boxTop = currentRect.y * h;
                float boxRight = (currentRect.x + currentRect.width) * w;
                float boxBottom = (currentRect.y + currentRect.height) * h;

                float knobCx = boxRight - handleMarginPx - handleSizePx * 0.5f;
                float knobCy = boxBottom - handleMarginPx - handleSizePx * 0.5f;
                float halfSlop = handleTouchSlopPx * 0.5f;
                if (x >= knobCx - halfSlop && x <= boxRight + 16f
                        && y >= knobCy - halfSlop && y <= boxBottom + 16f) {
                    dragMode = MODE_RESIZE;
                } else if (x >= boxLeft - 12f && x <= boxRight + 12f
                        && y >= boxTop - 12f && y <= boxBottom + 12f) {
                    dragMode = MODE_MOVE;
                } else {
                    dragMode = MODE_NONE;
                    return false;
                }

                lastTouchX = x;
                lastTouchY = y;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (dragMode == MODE_NONE) return false;
                float dxRatio = (x - lastTouchX) / (float) w;
                float dyRatio = (y - lastTouchY) / (float) h;
                lastTouchX = x;
                lastTouchY = y;

                BlindSpotPlacement.RectRatio updated = (dragMode == MODE_RESIZE)
                        ? currentRect.resizedRightBottomBy(dxRatio, dyRatio)
                        : currentRect.movedBy(dxRatio, dyRatio);
                if (!updated.equals(currentRect)) {
                    currentRect = updated;
                    invalidate();
                    if (listener != null) {
                        listener.onPlacementChanged(currentSlot, currentRect, true);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragMode != MODE_NONE) {
                    dragMode = MODE_NONE;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    if (listener != null) {
                        listener.onPlacementCommitted(currentSlot, currentRect);
                    }
                    return true;
                }
                return false;

            default:
                return super.onTouchEvent(event);
        }
    }
}
