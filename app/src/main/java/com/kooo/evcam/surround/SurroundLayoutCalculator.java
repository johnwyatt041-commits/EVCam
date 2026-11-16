package com.kooo.evcam.surround;

import androidx.annotation.NonNull;

/**
 * 360 模式 A 纯 Java 归一化视口布局计算器（对标 SC Lmf1.Q / Lxv3）。
 * 左侧为 360 全景鸟瞰图，右侧按上下分屏排列道路主视角与双侧轮位/侧视分屏。
 */
public final class SurroundLayoutCalculator {

    /** UI 像素矩形与 OpenGL 左下角原点视口转换。 */
    public static final class ViewportBox {
        public final int left;
        public final int top;
        public final int width;
        public final int height;

        public ViewportBox(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
        }

        public int right() {
            return left + width;
        }

        public int bottom() {
            return top + height;
        }

        public boolean isEmpty() {
            return width <= 0 || height <= 0;
        }

        /** 转为 OpenGL glViewport(x, y, width, height)（原点在容器左下角）。 */
        public int glY(int containerHeight) {
            return Math.max(0, containerHeight - (top + height));
        }

        public float aspectRatio() {
            return height > 0 ? (float) width / (float) height : 1.0f;
        }
    }

    /** 模式 A 完整布局计算结果。 */
    public static final class SurroundLayoutSpec {
        public final int containerWidth;
        public final int containerHeight;
        public final ViewportBox birdBox;
        public final ViewportBox topDetailBox;
        public final ViewportBox bottomBandBox;
        public final ViewportBox bottomLeftBox;
        public final ViewportBox bottomCenterCardBox;
        public final ViewportBox bottomRightBox;
        public final boolean wheelsCollapsed;
        public final SurroundViewMode effectiveBottomMode;

        public SurroundLayoutSpec(int containerWidth,
                                  int containerHeight,
                                  @NonNull ViewportBox birdBox,
                                  @NonNull ViewportBox topDetailBox,
                                  @NonNull ViewportBox bottomBandBox,
                                  @NonNull ViewportBox bottomLeftBox,
                                  @NonNull ViewportBox bottomCenterCardBox,
                                  @NonNull ViewportBox bottomRightBox,
                                  boolean wheelsCollapsed,
                                  @NonNull SurroundViewMode effectiveBottomMode) {
            this.containerWidth = containerWidth;
            this.containerHeight = containerHeight;
            this.birdBox = birdBox;
            this.topDetailBox = topDetailBox;
            this.bottomBandBox = bottomBandBox;
            this.bottomLeftBox = bottomLeftBox;
            this.bottomCenterCardBox = bottomCenterCardBox;
            this.bottomRightBox = bottomRightBox;
            this.wheelsCollapsed = wheelsCollapsed;
            this.effectiveBottomMode = effectiveBottomMode;
        }
    }

    private SurroundLayoutCalculator() {
    }

    @NonNull
    public static SurroundLayoutSpec calculate(int containerWidth,
                                               int containerHeight,
                                               @NonNull SurroundViewMode effectiveBottomMode,
                                               boolean wheelsCollapsed,
                                               int groundWidthCm,
                                               int groundLengthCm,
                                               int groundFrontExtensionCm,
                                               int gapPx) {
        if (containerWidth <= 0 || containerHeight <= 0) {
            ViewportBox empty = new ViewportBox(0, 0, 0, 0);
            return new SurroundLayoutSpec(containerWidth, containerHeight,
                    empty, empty, empty, empty, empty, empty, wheelsCollapsed, effectiveBottomMode);
        }

        int gap = Math.max(4, gapPx);
        int outerPad = gap;
        int innerW = Math.max(120, containerWidth - outerPad * 2);
        int innerH = Math.max(120, containerHeight - outerPad * 2);

        // 根据实际地面覆盖物理长宽比推导左侧 360 鸟瞰区宽度，并约束在总宽度的 31% ~ 42% 之间
        int effectiveLengthCm = Math.max(420, groundLengthCm) + Math.max(0, groundFrontExtensionCm);
        float birdWorldAspect = (float) Math.max(160, groundWidthCm) / (float) effectiveLengthCm;
        int idealBirdWidth = Math.round(innerH * birdWorldAspect * 1.18f);
        int minBirdWidth = Math.round(innerW * 0.31f);
        int maxBirdWidth = Math.round(innerW * 0.42f);
        int birdWidth = Math.max(minBirdWidth, Math.min(maxBirdWidth, idealBirdWidth));

        ViewportBox birdBox = new ViewportBox(outerPad, outerPad, birdWidth, innerH);

        int rightLeft = birdBox.right() + gap;
        int rightWidth = Math.max(80, outerPad + innerW - rightLeft);

        if (wheelsCollapsed) {
            ViewportBox topBox = new ViewportBox(rightLeft, outerPad, rightWidth, innerH);
            ViewportBox collapsedBar = new ViewportBox(rightLeft, outerPad + innerH, rightWidth, 0);
            ViewportBox empty = new ViewportBox(0, 0, 0, 0);
            return new SurroundLayoutSpec(containerWidth, containerHeight,
                    birdBox, topBox, collapsedBar, empty, empty, empty, true, effectiveBottomMode);
        }

        // 右侧上下分割：上方道路视角占 56%，下方轮位/侧视区占 44%
        int topHeight = Math.round((innerH - gap) * 0.56f);
        int bottomTop = outerPad + topHeight + gap;
        int bottomHeight = Math.max(60, outerPad + innerH - bottomTop);

        ViewportBox topBox = new ViewportBox(rightLeft, outerPad, rightWidth, topHeight);
        ViewportBox bottomBand = new ViewportBox(rightLeft, bottomTop, rightWidth, bottomHeight);

        if (effectiveBottomMode == SurroundViewMode.SIDES) {
            int sideWidth = Math.max(30, (rightWidth - gap) / 2);
            ViewportBox leftSide = new ViewportBox(rightLeft, bottomTop, sideWidth, bottomHeight);
            ViewportBox rightSide = new ViewportBox(rightLeft + sideWidth + gap, bottomTop,
                    Math.max(30, rightWidth - sideWidth - gap), bottomHeight);
            ViewportBox centerEmpty = new ViewportBox(0, 0, 0, 0);
            return new SurroundLayoutSpec(containerWidth, containerHeight,
                    birdBox, topBox, bottomBand, leftSide, centerEmpty, rightSide, false, effectiveBottomMode);
        }

        // 前轮 / 后轮三列切分：左轮 | 中央切换控卡 | 右轮
        int centerCardW = Math.max(96, Math.min(Math.round(rightWidth * 0.24f), 196));
        int wheelWidth = Math.max(40, (rightWidth - centerCardW - gap * 2) / 2);
        ViewportBox leftWheel = new ViewportBox(rightLeft, bottomTop, wheelWidth, bottomHeight);
        int centerLeft = leftWheel.right() + gap;
        ViewportBox centerCard = new ViewportBox(centerLeft, bottomTop, centerCardW, bottomHeight);
        int rightWheelLeft = centerCard.right() + gap;
        int rightWheelWidth = Math.max(40, bottomBand.right() - rightWheelLeft);
        ViewportBox rightWheel = new ViewportBox(rightWheelLeft, bottomTop, rightWheelWidth, bottomHeight);

        return new SurroundLayoutSpec(containerWidth, containerHeight,
                birdBox, topBox, bottomBand, leftWheel, centerCard, rightWheel, false, effectiveBottomMode);
    }
}
