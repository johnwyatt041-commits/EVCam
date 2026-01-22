package com.test.cam;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

/**
 * 自动适配宽高比的 TextureView
 * 根据设置的宽高比自动调整视图尺寸，避免画面拉伸
 */
public class AutoFitTextureView extends TextureView {

    private int ratioWidth = 0;
    private int ratioHeight = 0;

    public AutoFitTextureView(Context context) {
        this(context, null);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * 设置此视图的宽高比
     *
     * @param width  相对宽度
     * @param height 相对高度
     */
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        ratioWidth = width;
        ratioHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (ratioWidth == 0 || ratioHeight == 0) {
            // 如果没有设置宽高比，使用默认测量
            setMeasuredDimension(width, height);
        } else {
            // 根据宽高比调整尺寸
            // 计算两种可能的尺寸：基于宽度和基于高度
            int newWidth, newHeight;

            // 方案1：基于容器宽度计算高度
            newWidth = width;
            newHeight = width * ratioHeight / ratioWidth;

            // 如果计算出的高度超过容器高度，则基于容器高度计算宽度
            if (newHeight > height) {
                newHeight = height;
                newWidth = height * ratioWidth / ratioHeight;
            }

            setMeasuredDimension(newWidth, newHeight);
        }
    }
}
