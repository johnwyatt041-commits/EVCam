package com.kooo.evcam.blindspot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * 分方向旋转与四边裁切的直观预览画布。
 * 展示原始传感画面、被裁掉的四边区域与保留输出的有效区域及旋转角度。
 */
public class BlindSpotCropPreviewView extends View {

    private final Paint outerBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outerBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keepFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint keepBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF canvasRect = new RectF();
    private final RectF keepRect = new RectF();

    private String channelLabel = "右";
    private int rotationDeg = 0;
    private BlindSpotCorrectionMath.EdgeCrop edgeCrop =
            new BlindSpotCorrectionMath.EdgeCrop(0f, 0f, 0f, 0f);
    private float cornerRadiusPx;
    private float maxHeightPx;

    public BlindSpotCropPreviewView(Context context) {
        this(context, null);
    }

    public BlindSpotCropPreviewView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BlindSpotCropPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float d = getResources().getDisplayMetrics().density;
        cornerRadiusPx = 12f * d;
        maxHeightPx = 165f * d;

        outerBgPaint.setStyle(Paint.Style.FILL);
        outerBgPaint.setColor(0xFF0F172A);

        outerBorderPaint.setStyle(Paint.Style.STROKE);
        outerBorderPaint.setStrokeWidth(1.5f * d);
        outerBorderPaint.setColor(0xFF334155);

        maskPaint.setStyle(Paint.Style.FILL);
        maskPaint.setColor(0x88EF4444);

        keepFillPaint.setStyle(Paint.Style.FILL);
        keepFillPaint.setColor(0x3838BDF8);

        keepBorderPaint.setStyle(Paint.Style.STROKE);
        keepBorderPaint.setStrokeWidth(2f * d);
        keepBorderPaint.setColor(0xFF38BDF8);

        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(14f * d);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titlePaint.setTextAlign(Paint.Align.CENTER);

        subPaint.setColor(0xFFBAE6FD);
        subPaint.setTextSize(12f * d);
        subPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setPreviewState(
            @NonNull String channelLabel,
            int rotationDeg,
            @NonNull BlindSpotCorrectionMath.EdgeCrop crop) {
        this.channelLabel = channelLabel;
        this.rotationDeg = BlindSpotCorrectionMath.normalizeAngle(rotationDeg);
        this.edgeCrop = crop;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availW = MeasureSpec.getSize(widthMeasureSpec);
        if (availW <= 0) {
            availW = Math.round(384f * getResources().getDisplayMetrics().density);
        }
        int measuredW = availW;
        int measuredH = Math.round(measuredW * 9f / 16f);
        if (measuredH > maxHeightPx) {
            measuredH = Math.round(maxHeightPx);
            measuredW = Math.round(measuredH * 16f / 9f);
        }
        setMeasuredDimension(measuredW, measuredH);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        float inset = outerBorderPaint.getStrokeWidth() * 0.5f;
        canvasRect.set(inset, inset, w - inset, h - inset);
        android.graphics.Path clipPath = new android.graphics.Path();
        clipPath.addRoundRect(canvasRect, cornerRadiusPx, cornerRadiusPx, android.graphics.Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawRoundRect(canvasRect, cornerRadiusPx, cornerRadiusPx, outerBgPaint);

        float leftX = w * edgeCrop.left;
        float topY = h * edgeCrop.top;
        float rightX = w * (1f - edgeCrop.right);
        float bottomY = h * (1f - edgeCrop.bottom);

        // 高亮显示四边被裁切掉的冗余区域
        if (topY > 1f) canvas.drawRect(0f, 0f, w, topY, maskPaint);
        if (bottomY < h - 1f) canvas.drawRect(0f, bottomY, w, h, maskPaint);
        if (leftX > 1f) canvas.drawRect(0f, topY, leftX, bottomY, maskPaint);
        if (rightX < w - 1f) canvas.drawRect(rightX, topY, w, bottomY, maskPaint);

        float keepInset = keepBorderPaint.getStrokeWidth() * 0.5f;
        keepRect.set(
                Math.max(keepInset, leftX),
                Math.max(keepInset, topY),
                Math.min(w - keepInset, rightX),
                Math.min(h - keepInset, bottomY));
        canvas.drawRect(keepRect, keepFillPaint);
        canvas.drawRect(keepRect, keepBorderPaint);
        canvas.restore();
        canvas.drawRoundRect(canvasRect, cornerRadiusPx, cornerRadiusPx, outerBorderPaint);

        float cx = keepRect.centerX();
        float cy = keepRect.centerY();
        float d = getResources().getDisplayMetrics().density;
        int visW = Math.round(edgeCrop.visibleWidth() * 100f);
        int visH = Math.round(edgeCrop.visibleHeight() * 100f);
        canvas.drawText(
                String.format(Locale.US, "%s路 · 旋转 %d°", channelLabel, rotationDeg),
                cx, cy - 4f * d, titlePaint);
        canvas.drawText(
                String.format(Locale.US, "保留区域 %d%% × %d%%", visW, visH),
                cx, cy + 16f * d, subPaint);
    }
}
