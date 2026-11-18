package com.kooo.evcam.blindspot;

import androidx.annotation.NonNull;

/**
 * 补盲画面分方向旋转与四边裁切的纯 Java 几何计算模块（不影响主预览和录像，也不引入鱼眼畸变）。
 */
public final class BlindSpotCorrectionMath {

    public static final float MAX_SINGLE_EDGE_CROP = 0.45f;
    public static final float MIN_VISIBLE_AXIS_RATIO = 0.20f;
    public static final int MAX_SINGLE_EDGE_PERCENT = 45;

    private BlindSpotCorrectionMath() {
    }

    /** 归一化并校验四边裁切比例（保证剩余可见宽/高均不低于 20%）。 */
    public static final class EdgeCrop {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        public EdgeCrop(float left, float top, float right, float bottom) {
            float safeLeft = clampEdge(left);
            float safeRight = clampEdge(right);
            float maxHorizTotal = 1.0f - MIN_VISIBLE_AXIS_RATIO;
            if (safeLeft + safeRight > maxHorizTotal) {
                float excess = (safeLeft + safeRight) - maxHorizTotal;
                float reduceLeft = excess * (safeLeft / Math.max(1e-5f, safeLeft + safeRight));
                float reduceRight = excess - reduceLeft;
                safeLeft = Math.max(0f, safeLeft - reduceLeft);
                safeRight = Math.max(0f, safeRight - reduceRight);
            }

            float safeTop = clampEdge(top);
            float safeBottom = clampEdge(bottom);
            float maxVertTotal = 1.0f - MIN_VISIBLE_AXIS_RATIO;
            if (safeTop + safeBottom > maxVertTotal) {
                float excess = (safeTop + safeBottom) - maxVertTotal;
                float reduceTop = excess * (safeTop / Math.max(1e-5f, safeTop + safeBottom));
                float reduceBottom = excess - reduceTop;
                safeTop = Math.max(0f, safeTop - reduceTop);
                safeBottom = Math.max(0f, safeBottom - reduceBottom);
            }

            this.left = safeLeft;
            this.top = safeTop;
            this.right = safeRight;
            this.bottom = safeBottom;
        }

        public static EdgeCrop fromPercents(int leftPct, int topPct, int rightPct, int bottomPct) {
            return new EdgeCrop(
                    leftPct / 100f,
                    topPct / 100f,
                    rightPct / 100f,
                    bottomPct / 100f
            );
        }

        public float visibleWidth() {
            return Math.max(MIN_VISIBLE_AXIS_RATIO, 1.0f - left - right);
        }

        public float visibleHeight() {
            return Math.max(MIN_VISIBLE_AXIS_RATIO, 1.0f - top - bottom);
        }

        /** 裁剪保留区域的水平中心点 U ∈ [0, 1]。 */
        public float centerU() {
            return 0.5f + (left - right) * 0.5f;
        }

        /** 裁剪保留区域的垂直中心点 V ∈ [0, 1]。 */
        public float centerV() {
            return 0.5f + (top - bottom) * 0.5f;
        }

        /**
         * 保持原始纵横比且不留黑边的等比放大倍率：
         * 使四边裁切以外的区域完全移出可视窗口外。
         */
        public float uniformZoom() {
            return Math.max(1.0f / visibleWidth(), 1.0f / visibleHeight());
        }

        /** 在进入等比放大前，需要将裁切中心平移到视图中心所需的视图归一化偏移量。 */
        public float preZoomShiftXNorm() {
            return 0.5f - centerU();
        }

        public float preZoomShiftYNorm() {
            return 0.5f - centerV();
        }

        public boolean isZero() {
            return left < 1e-4f && top < 1e-4f && right < 1e-4f && bottom < 1e-4f;
        }
    }

    /** 统一旋转角度归一化到 [0, 359]。 */
    public static int normalizeAngle(int degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    /**
     * 计算传感器画面 (bufferW × bufferH) 经任意角度 rotationDeg 旋转后，
     * 恰好完整铺满目标视口 (viewW × viewH) 且不出现黑边或黑角的最小等比缩放倍率（Center-Crop）。
     */
    public static float computeIsotropicCoverScale(
            int bufferWidth,
            int bufferHeight,
            int viewWidth,
            int viewHeight,
            int rotationDegrees) {
        if (bufferWidth <= 0 || bufferHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return 1.0f;
        }
        double rad = Math.toRadians(normalizeAngle(rotationDegrees));
        double absCos = Math.abs(Math.cos(rad));
        double absSin = Math.abs(Math.sin(rad));
        double requiredSourceW = viewWidth * absCos + viewHeight * absSin;
        double requiredSourceH = viewWidth * absSin + viewHeight * absCos;
        return (float) Math.max(requiredSourceW / bufferWidth, requiredSourceH / bufferHeight);
    }

    /**
     * 限制新编辑的单边裁切百分比，保证与对侧裁切叠加后仍保留 >= 20% 画面。
     */
    public static int clampNewEdgePercent(int requestedPercent, int oppositeEdgePercent) {
        int safeOpposite = Math.max(0, Math.min(MAX_SINGLE_EDGE_PERCENT, oppositeEdgePercent));
        int maxAllowedForPair = Math.round((1.0f - MIN_VISIBLE_AXIS_RATIO) * 100f) - safeOpposite;
        int upper = Math.min(MAX_SINGLE_EDGE_PERCENT, Math.max(0, maxAllowedForPair));
        return Math.max(0, Math.min(upper, requestedPercent));
    }

    private static float clampEdge(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(MAX_SINGLE_EDGE_CROP, value));
    }
}
