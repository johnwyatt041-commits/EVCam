package com.kooo.evcam.surround;

import androidx.annotation.NonNull;

/**
 * 纯 Java 银河 E5 阿克曼转向几何动态引导线计算器（替代 SC 1.9MB 二进制 guidelines.db）。
 * 输出原车 360 风格（图 2）金黄色双层轨迹带：
 * - 左侧鸟瞰：前后轮迹双轨随动黄弧 + 倒车转向时前外侧车身外摆预警黄弧；
 * - 右上道路（前视/后视）：纯两侧双层金黄轨迹带（粗内侧轮迹 + 细外侧包络线），中间完全留空无横线。
 */
public final class SurroundGuidanceCalculator {

    // 银河 E5 实车轴距与轮距（cm）
    public static final float E5_WHEELBASE_CM = 275.0f;
    public static final float E5_TRACK_WIDTH_CM = 162.0f;
    public static final int FLOATS_PER_COLOR_VERTEX = 6; // [ndcX, ndcY, r, g, b, a]

    private static final float ROAD_VIEW_ASPECT_COMP = 1.85f;

    public static final class LineStripBatch {
        /** 若干连续线段带的三角带/线段顶点数据，每顶点 6 floats: [x, y, r, g, b, a]。 */
        @NonNull
        public final float[] triangleStripVertices;
        /** 每个独立的带状多边形在 triangleStripVertices 中占用的顶点数。 */
        @NonNull
        public final int[] stripVertexCounts;

        public LineStripBatch(@NonNull float[] triangleStripVertices, @NonNull int[] stripVertexCounts) {
            this.triangleStripVertices = triangleStripVertices;
            this.stripVertexCounts = stripVertexCounts;
        }
    }

    private SurroundGuidanceCalculator() {
    }

    /**
     * 生成左侧 360 鸟瞰图中的动态转向/倒车辅助轨迹线（车前或车尾延伸 175cm 弧线，倒车大转角叠绘前外侧外摆弧）。
     */
    @NonNull
    public static LineStripBatch buildBirdGuidanceStrips(float wheelAngleDeg,
                                                         boolean isReverse,
                                                         @NonNull SurroundCalibrationProfile profile,
                                                         int steps) {
        int safeSteps = Math.max(6, Math.min(36, steps));
        double halfWorldW = halfW(profile.getHalfWorldWidthCm());
        double minY = profile.getWorldMinYCm();
        double spanY = Math.max(100.0, profile.getWorldMaxYCm() - minY);

        double baseY = isReverse
                ? SurroundCalibrationProfile.E5_CAR_MIN_Y_CM - 2.0
                : SurroundCalibrationProfile.E5_CAR_MAX_Y_CM + 2.0;
        double dirSign = isReverse ? -1.0 : 1.0;
        double maxTravelCm = 175.0;
        double halfTrack = E5_TRACK_WIDTH_CM * 0.5;

        double clampedAngle = Math.max(-42.0, Math.min(42.0, wheelAngleDeg));
        double absRad = Math.toRadians(Math.abs(clampedAngle));
        boolean straight = absRad < 0.008;

        double rearAxleY = SurroundCalibrationProfile.E5_CAR_MIN_Y_CM + 92.0;
        double turnRadiusCenter = straight ? 1e6 : E5_WHEELBASE_CM / Math.tan(absRad);
        double signTurn = clampedAngle >= 0.0 ? 1.0 : -1.0; // 右转为正 -> 圆心在 +X 侧
        double pivotX = signTurn * turnRadiusCenter;

        double[][] leftWorld = new double[safeSteps + 1][2];
        double[][] rightWorld = new double[safeSteps + 1][2];
        for (int i = 0; i <= safeSteps; i++) {
            double arcCm = (double) i / (double) safeSteps * maxTravelCm;
            if (straight) {
                leftWorld[i][0] = -halfTrack;
                leftWorld[i][1] = baseY + dirSign * arcCm;
                rightWorld[i][0] = halfTrack;
                rightWorld[i][1] = leftWorld[i][1];
            } else {
                // 统一阿克曼角速度符号：前进右转顺时针(-)向 +X/+Y；倒车右转逆时针(+)向 +X/-Y
                double angleFromPivot = -(dirSign * arcCm / Math.max(turnRadiusCenter, 60.0)) * signTurn;
                double[] ptL = rotateAroundPivot(-halfTrack, baseY, pivotX, rearAxleY, angleFromPivot);
                double[] ptR = rotateAroundPivot(halfTrack, baseY, pivotX, rearAxleY, angleFromPivot);
                leftWorld[i][0] = ptL[0];
                leftWorld[i][1] = ptL[1];
                rightWorld[i][0] = ptR[0];
                rightWorld[i][1] = ptR[1];
            }
        }

        boolean drawOuterSwing = !straight && isReverse && Math.abs(clampedAngle) >= 5.0;
        double[][] outerSwingWorld = null;
        if (drawOuterSwing) {
            outerSwingWorld = new double[safeSteps + 1][2];
            double swingTravelCm = Math.min(130.0, maxTravelCm * 0.78);
            double outerStartX = -signTurn * (SurroundCalibrationProfile.E5_CAR_MAX_X_CM + 4.0);
            double outerStartY = SurroundCalibrationProfile.E5_CAR_MAX_Y_CM - 28.0;
            for (int i = 0; i <= safeSteps; i++) {
                double arcCm = (double) i / (double) safeSteps * swingTravelCm;
                double angleFromPivot = -(dirSign * arcCm / Math.max(turnRadiusCenter, 60.0)) * signTurn;
                double[] pt = rotateAroundPivot(outerStartX, outerStartY, pivotX, rearAxleY, angleFromPivot);
                outerSwingWorld[i][0] = pt[0];
                outerSwingWorld[i][1] = pt[1];
            }
        }

        int stripVerts = (safeSteps + 1) * 2;
        int stripCount = drawOuterSwing ? 3 : 2;
        float[] out = new float[stripCount * stripVerts * FLOATS_PER_COLOR_VERTEX];
        int[] counts = new int[stripCount];

        float[] oemGold = {0.99f, 0.80f, 0.12f, 0.92f};
        float[] swingGold = {1.00f, 0.84f, 0.18f, 0.88f};

        int cursor = 0;
        cursor = writeWorldRibbon(leftWorld, 3.0, 2.0, oemGold, 0.54f, halfWorldW, minY, spanY, out, cursor);
        counts[0] = stripVerts;
        cursor = writeWorldRibbon(rightWorld, 3.0, 2.0, oemGold, 0.54f, halfWorldW, minY, spanY, out, cursor);
        counts[1] = stripVerts;
        if (drawOuterSwing) {
            writeWorldRibbon(outerSwingWorld, 2.2, 1.6, swingGold, 0.45f, halfWorldW, minY, spanY, out, cursor);
            counts[2] = stripVerts;
        }
        return new LineStripBatch(out, counts);
    }

    public static final float MIN_ROAD_GUIDANCE_WIDTH_SCALE = 0.50f;
    public static final float MAX_ROAD_GUIDANCE_WIDTH_SCALE = 1.50f;

    /**
     * 生成右上道路视图（前视/后视）中的动态转向引导线（原车风格纯两侧双层金黄轨带，中间完全留空无横线）。
     * Strip 0/2：左/右内侧粗金黄主轮迹带；Strip 1/3：左/右外侧细亮黄包络线。
     */
    @NonNull
    public static LineStripBatch buildRoadGuidanceStrips(float wheelAngleDeg,
                                                         boolean isReverse,
                                                         int steps) {
        return buildRoadGuidanceStrips(wheelAngleDeg, isReverse, 1.0f, steps);
    }

    /**
     * 生成右上道路视图（前视/后视）中的动态转向引导线，支持按 widthScale（0.50 ~ 1.50）调整左右间距。
     */
    @NonNull
    public static LineStripBatch buildRoadGuidanceStrips(float wheelAngleDeg,
                                                         boolean isReverse,
                                                         float widthScale,
                                                         int steps) {
        int safeSteps = Math.max(8, Math.min(36, steps));
        float safeWidthScale = Float.isFinite(widthScale)
                ? Math.max(MIN_ROAD_GUIDANCE_WIDTH_SCALE, Math.min(MAX_ROAD_GUIDANCE_WIDTH_SCALE, widthScale))
                : 1.0f;
        double clampedAngle = Math.max(-42.0, Math.min(42.0, wheelAngleDeg));
        double turnFactor = Math.sin(Math.toRadians(clampedAngle)); // [-0.669, +0.669]

        float[][] leftInnerPts = new float[safeSteps + 1][2];
        float[][] leftOuterPts = new float[safeSteps + 1][2];
        float[][] rightInnerPts = new float[safeSteps + 1][2];
        float[][] rightOuterPts = new float[safeSteps + 1][2];

        float baseHalfLane = 0.60f * safeWidthScale;
        float outerScale = 0.85f + 0.15f * safeWidthScale;

        for (int i = 0; i <= safeSteps; i++) {
            float t = (float) i / (float) safeSteps; // 0=近车端底部, 1=远端
            float ndcY = -0.86f + t * 0.96f;
            float halfLane = baseHalfLane * (1.0f - 0.64f * t);
            float curveShift = (float) (turnFactor * (0.20 * t + 0.80 * t * t) * 0.76);
            float outerGap = 0.088f * outerScale * (1.0f - 0.66f * t);

            float lx = -halfLane + curveShift;
            float rx = halfLane + curveShift;

            leftInnerPts[i][0] = lx;
            leftInnerPts[i][1] = ndcY;
            leftOuterPts[i][0] = lx - outerGap;
            leftOuterPts[i][1] = ndcY;

            rightInnerPts[i][0] = rx;
            rightInnerPts[i][1] = ndcY;
            rightOuterPts[i][0] = rx + outerGap;
            rightOuterPts[i][1] = ndcY;
        }

        float[] innerColor = {0.98f, 0.77f, 0.08f, 0.86f};
        float[] outerColor = {1.00f, 0.88f, 0.28f, 0.94f};

        int stripVerts = (safeSteps + 1) * 2;
        float[] out = new float[4 * stripVerts * FLOATS_PER_COLOR_VERTEX];
        int[] counts = {stripVerts, stripVerts, stripVerts, stripVerts};

        int cursor = 0;
        cursor = writeNdcTaperedRibbon(leftInnerPts, 0.035f, 0.011f, innerColor, 0.50f, out, cursor);
        cursor = writeNdcTaperedRibbon(leftOuterPts, 0.0085f, 0.0038f, outerColor, 0.58f, out, cursor);
        cursor = writeNdcTaperedRibbon(rightInnerPts, 0.035f, 0.011f, innerColor, 0.50f, out, cursor);
        writeNdcTaperedRibbon(rightOuterPts, 0.0085f, 0.0038f, outerColor, 0.58f, out, cursor);
        return new LineStripBatch(out, counts);
    }

    /**
     * 生成 4.3 标定辅助用的标准 250 cm 车位与 100 cm 等距世界坐标参考网格，以及 E5 实车外廓框（[-95,+95] × [-231,+231] cm）。
     */
    @NonNull
    public static LineStripBatch buildCalibrationGridBatch(@NonNull SurroundCalibrationProfile profile) {
        double halfWorldW = halfW(profile.getHalfWorldWidthCm());
        double minY = profile.getWorldMinYCm();
        double maxY = profile.getWorldMaxYCm();
        double spanY = Math.max(100.0, maxY - minY);

        java.util.List<double[][]> segments = new java.util.ArrayList<>();
        java.util.List<Double> halfWidths = new java.util.ArrayList<>();
        java.util.List<float[]> colors = new java.util.ArrayList<>();

        float[] slotLineColor = {0.28f, 0.88f, 1.00f, 0.72f};
        float[] fineGridColor = {0.75f, 0.92f, 1.00f, 0.34f};
        float[] carOutlineColor = {0.35f, 0.95f, 0.55f, 0.65f};

        // 1. 标准 250 cm 车位两侧竖线 (X = ±125 cm) 与前后保险杠/1 米横线 (Y = ±231, ±331 cm)
        double[] slotXs = {-125.0, 125.0};
        for (double x : slotXs) {
            if (Math.abs(x) <= halfWorldW + 5.0) {
                segments.add(new double[][]{{x, minY}, {x, maxY}});
                halfWidths.add(1.6);
                colors.add(slotLineColor);
            }
        }
        double[] slotYs = {-331.0, -231.0, 231.0, 331.0};
        for (double y : slotYs) {
            if (y >= minY && y <= maxY) {
                segments.add(new double[][]{{-halfWorldW, y}, {halfWorldW, y}});
                halfWidths.add(1.6);
                colors.add(slotLineColor);
            }
        }

        // 2. 100 cm 等距细标尺网格 (X = 0, ±100, ±200; Y = 0, ±100, ±200, ±300, ±400, ±500)
        double[] gridXs = {-200.0, -100.0, 0.0, 100.0, 200.0};
        for (double x : gridXs) {
            if (Math.abs(x) <= halfWorldW + 5.0) {
                segments.add(new double[][]{{x, minY}, {x, maxY}});
                halfWidths.add(0.9);
                colors.add(fineGridColor);
            }
        }
        double[] gridYs = {-500.0, -400.0, -300.0, -200.0, -100.0, 0.0, 100.0, 200.0, 300.0, 400.0, 500.0};
        for (double y : gridYs) {
            if (y >= minY && y <= maxY) {
                segments.add(new double[][]{{-halfWorldW, y}, {halfWorldW, y}});
                halfWidths.add(0.9);
                colors.add(fineGridColor);
            }
        }

        // 3. 银河 E5 实车外廓参考框 [-95, +95] × [-231, +231] cm（4 条边）
        double cxMin = SurroundCalibrationProfile.E5_CAR_MIN_X_CM;
        double cxMax = SurroundCalibrationProfile.E5_CAR_MAX_X_CM;
        double cyMin = SurroundCalibrationProfile.E5_CAR_MIN_Y_CM;
        double cyMax = SurroundCalibrationProfile.E5_CAR_MAX_Y_CM;
        double[][][] carEdges = {
                {{cxMin, cyMin}, {cxMin, cyMax}},
                {{cxMax, cyMin}, {cxMax, cyMax}},
                {{cxMin, cyMax}, {cxMax, cyMax}},
                {{cxMin, cyMin}, {cxMax, cyMin}}
        };
        for (double[][] edge : carEdges) {
            segments.add(edge);
            halfWidths.add(1.4);
            colors.add(carOutlineColor);
        }

        int stripCount = segments.size();
        int stripVerts = 4; // 2 points per segment -> 4 vertices in triangle strip
        float[] out = new float[stripCount * stripVerts * FLOATS_PER_COLOR_VERTEX];
        int[] counts = new int[stripCount];
        int cursor = 0;
        for (int i = 0; i < stripCount; i++) {
            double hw = halfWidths.get(i);
            cursor = writeWorldRibbon(segments.get(i), hw, hw, colors.get(i), 1.0f,
                    halfWorldW, minY, spanY, out, cursor);
            counts[i] = stripVerts;
        }
        return new LineStripBatch(out, counts);
    }

    private static int writeWorldRibbon(double[][] worldPts,
                                        double halfWidthStartCm,
                                        double halfWidthEndCm,
                                        float[] baseColor,
                                        float endAlphaRatio,
                                        double halfWorldW,
                                        double minY,
                                        double spanY,
                                        float[] out,
                                        int cursor) {
        int n = worldPts.length;
        for (int i = 0; i < n; i++) {
            int prev = Math.max(0, i - 1);
            int next = Math.min(n - 1, i + 1);
            double tx = worldPts[next][0] - worldPts[prev][0];
            double ty = worldPts[next][1] - worldPts[prev][1];
            double len = Math.hypot(tx, ty);
            double nx = len > 1e-6 ? (-ty / len) : -1.0;
            double ny = len > 1e-6 ? (tx / len) : 0.0;

            double t = n > 1 ? (double) i / (double) (n - 1) : 0.0;
            double hw = halfWidthStartCm + (halfWidthEndCm - halfWidthStartCm) * t;

            double wx0 = worldPts[i][0] - nx * hw;
            double wy0 = worldPts[i][1] - ny * hw;
            double wx1 = worldPts[i][0] + nx * hw;
            double wy1 = worldPts[i][1] + ny * hw;

            float alphaScale = 1.0f - (float) t * (1.0f - endAlphaRatio);
            float[] c = {baseColor[0], baseColor[1], baseColor[2], baseColor[3] * alphaScale};

            cursor = writeVertex(out, cursor,
                    (float) (wx0 / halfWorldW), (float) (((wy0 - minY) / spanY) * 2.0 - 1.0), c);
            cursor = writeVertex(out, cursor,
                    (float) (wx1 / halfWorldW), (float) (((wy1 - minY) / spanY) * 2.0 - 1.0), c);
        }
        return cursor;
    }

    private static int writeNdcTaperedRibbon(float[][] pts,
                                             float halfWidthNear,
                                             float halfWidthFar,
                                             float[] baseColor,
                                             float endAlphaRatio,
                                             float[] out,
                                             int cursor) {
        int n = pts.length;
        for (int i = 0; i < n; i++) {
            int prev = Math.max(0, i - 1);
            int next = Math.min(n - 1, i + 1);
            float tvx = (pts[next][0] - pts[prev][0]) * ROAD_VIEW_ASPECT_COMP;
            float tvy = pts[next][1] - pts[prev][1];
            float len = (float) Math.hypot(tvx, tvy);
            float nvx = len > 1e-5f ? (-tvy / len) : -1.0f;
            float nvy = len > 1e-5f ? (tvx / len) : 0.0f;

            float t = n > 1 ? (float) i / (float) (n - 1) : 0f;
            float hw = halfWidthNear + (halfWidthFar - halfWidthNear) * t;
            float offX = nvx * hw;
            float offY = nvy * hw * ROAD_VIEW_ASPECT_COMP;

            float alphaScale = 1.0f - t * (1.0f - endAlphaRatio);
            float[] c = {baseColor[0], baseColor[1], baseColor[2], baseColor[3] * alphaScale};

            cursor = writeVertex(out, cursor, pts[i][0] - offX, pts[i][1] - offY, c);
            cursor = writeVertex(out, cursor, pts[i][0] + offX, pts[i][1] + offY, c);
        }
        return cursor;
    }

    private static int writeVertex(float[] out, int vertexOffset, float x, float y, float[] rgba) {
        int base = vertexOffset * FLOATS_PER_COLOR_VERTEX;
        out[base] = x;
        out[base + 1] = y;
        out[base + 2] = rgba[0];
        out[base + 3] = rgba[1];
        out[base + 4] = rgba[2];
        out[base + 5] = rgba[3];
        return vertexOffset + 1;
    }

    private static double[] rotateAroundPivot(double x, double y, double px, double py, double angleRad) {
        double dx = x - px;
        double dy = y - py;
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        return new double[]{
                px + dx * cos - dy * sin,
                py + dx * sin + dy * cos
        };
    }

    private static double halfW(double halfWorldW) {
        return Math.max(50.0, halfWorldW);
    }
}
