package com.kooo.evcam.surround;

import androidx.annotation.NonNull;

/**
 * 纯 Java 银河 E5 阿克曼转向几何动态引导线计算器（替代 SC 1.9MB 二进制 guidelines.db）。
 * 输入前轮等效力矩转角（-45°..+45°，右转为正）与是否倒车，输出可直接给 GLES20 LINE_STRIP/TRIANGLES 绘制的 NDC 顶点与 RGBA 颜色数组。
 */
public final class SurroundGuidanceCalculator {

    // 银河 E5 实车轴距与轮距（cm）
    public static final float E5_WHEELBASE_CM = 275.0f;
    public static final float E5_TRACK_WIDTH_CM = 162.0f;
    public static final int FLOATS_PER_COLOR_VERTEX = 6; // [ndcX, ndcY, r, g, b, a]

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
     * 生成左侧 360 鸟瞰图中的动态转向/倒车辅助轨迹线（车前或车尾延伸 180cm 弧线）。
     */
    @NonNull
    public static LineStripBatch buildBirdGuidanceStrips(float wheelAngleDeg,
                                                         boolean isReverse,
                                                         @NonNull SurroundCalibrationProfile profile,
                                                         int steps) {
        int safeSteps = Math.max(6, Math.min(36, steps));
        double halfWorldW = profile.getHalfWorldWidthCm();
        double minY = profile.getWorldMinYCm();
        double maxY = profile.getWorldMaxYCm();
        double spanY = maxY - minY;

        float baseY = isReverse ? SurroundCalibrationProfile.E5_CAR_MIN_Y_CM : SurroundCalibrationProfile.E5_CAR_MAX_Y_CM;
        float dirSign = isReverse ? -1.0f : 1.0f;
        float maxTravelCm = 175.0f;
        float halfTrack = E5_TRACK_WIDTH_CM * 0.5f;
        float halfStrokeCm = 3.6f;

        double clampedAngle = Math.max(-42.0, Math.min(42.0, wheelAngleDeg));
        double absRad = Math.toRadians(Math.abs(clampedAngle));

        float[][] leftPts = new float[safeSteps + 1][2];
        float[][] rightPts = new float[safeSteps + 1][2];
        for (int i = 0; i <= safeSteps; i++) {
            float arcCm = (float) i / (float) safeSteps * maxTravelCm;
            double lx;
            double rx;
            double ly;
            double ry;
            if (absRad < 0.008) {
                lx = -halfTrack;
                rx = halfTrack;
                ly = baseY + dirSign * arcCm;
                ry = ly;
            } else {
                // 绕后轴中心（近似车尾+48cm）旋转半径
                double rearAxleY = SurroundCalibrationProfile.E5_CAR_MIN_Y_CM + 55.0;
                double turnRadiusCenter = E5_WHEELBASE_CM / Math.tan(absRad);
                double signTurn = clampedAngle >= 0.0 ? 1.0 : -1.0; // 右转转弯圆心在 +X 侧
                double pivotX = signTurn * turnRadiusCenter;

                double rLeft = Math.abs(pivotX - (-halfTrack));
                double rRight = Math.abs(pivotX - halfTrack);
                double dThetaBase = (dirSign * arcCm) / Math.max(turnRadiusCenter, 60.0);
                // 相对于车尾/车头端面切向角偏移
                double angleFromPivot = (isReverse ? -dThetaBase : dThetaBase) * signTurn;

                double baseDeltaY = baseY - rearAxleY;
                double[] ptL = rotateAroundPivot(-halfTrack, baseY, pivotX, rearAxleY, angleFromPivot);
                double[] ptR = rotateAroundPivot(halfTrack, baseY, pivotX, rearAxleY, angleFromPivot);
                lx = ptL[0];
                ly = ptL[1];
                rx = ptR[0];
                ry = ptR[1];
            }
            leftPts[i][0] = (float) ((lx / halfW(halfWorldW)));
            leftPts[i][1] = (float) (((ly - minY) / spanY) * 2.0 - 1.0);
            rightPts[i][0] = (float) ((rx / halfW(halfWorldW)));
            rightPts[i][1] = (float) (((ry - minY) / spanY) * 2.0 - 1.0);
        }

        // 双轨加宽并转换为 NDC 宽度带
        float halfWidthNdc = (float) (halfStrokeCm / halfW(halfWorldW));
        float[] rColor = isReverse ? new float[]{0.98f, 0.72f, 0.20f, 0.92f} : new float[]{0.22f, 0.74f, 0.97f, 0.92f};
        return packTwoRailStrips(leftPts, rightPts, null, halfWidthNdc, rColor);
    }

    /**
     * 生成右上道路视图中的动态转向/倒车辅助引导线（包含 0.5m / 1.0m / 2.0m 距离横标与双轨随动引导带）。
     */
    @NonNull
    public static LineStripBatch buildRoadGuidanceStrips(float wheelAngleDeg,
                                                         boolean isReverse,
                                                         int steps) {
        int safeSteps = Math.max(8, Math.min(36, steps));
        double clampedAngle = Math.max(-42.0, Math.min(42.0, wheelAngleDeg));
        double turnFactor = Math.sin(Math.toRadians(clampedAngle)); // [-0.67, 0.67]

        float[][] leftPts = new float[safeSteps + 1][2];
        float[][] rightPts = new float[safeSteps + 1][2];
        for (int i = 0; i <= safeSteps; i++) {
            float t = (float) i / (float) safeSteps; // 0=贴近保险杠(底部), 1=远处
            float ndcY = -0.92f + t * 1.12f; // [-0.92, +0.20]
            float halfLane = 0.64f * (1.0f - 0.52f * t);
            // 倒车时方向盘右打，车尾轨迹向右侧弯（在后视镜像画面中与屏幕右向同向）
            float curveShift = (float) (turnFactor * (t * t) * 0.68f);
            leftPts[i][0] = -halfLane + curveShift;
            leftPts[i][1] = ndcY;
            rightPts[i][0] = halfLane + curveShift;
            rightPts[i][1] = ndcY;
        }

        // 三段横距标尺（近距 0.5m 红线、中距 1.0m 琥珀线、远距 2.0m 青蓝线）
        float[] tickTs = {0.16f, 0.45f, 0.80f};
        float[][][] crossTicks = new float[tickTs.length][2][2];
        for (int k = 0; k < tickTs.length; k++) {
            int idx = Math.min(safeSteps, Math.max(0, Math.round(tickTs[k] * safeSteps)));
            float lx = leftPts[idx][0];
            float rx = rightPts[idx][0];
            float y = leftPts[idx][1];
            float span = (rx - lx) * 0.22f;
            // 用左内延线代表分段横向安全提示
            crossTicks[k][0][0] = lx;
            crossTicks[k][0][1] = y;
            crossTicks[k][1][0] = rx;
            crossTicks[k][1][1] = y;
            if (k == 0) {
                // 保持跨度完整
                crossTicks[k][1][0] = rx;
            }
        }

        float[] primaryColor = isReverse
                ? new float[]{0.98f, 0.75f, 0.14f, 0.90f}
                : new float[]{0.22f, 0.74f, 0.97f, 0.90f};
        return packTwoRailStrips(leftPts, rightPts, crossTicks, 0.015f, primaryColor);
    }

    @NonNull
    private static LineStripBatch packTwoRailStrips(float[][] leftPts,
                                                    float[][] rightPts,
                                                    float[][][] crossTicks,
                                                    float halfStrokeNdc,
                                                    float[] color) {
        int railStripVerts = leftPts.length * 2;
        int tickCount = crossTicks != null ? crossTicks.length : 0;
        int totalStrips = 2 + tickCount;
        int totalVerts = railStripVerts * 2 + tickCount * 4;
        float[] out = new float[totalVerts * FLOATS_PER_COLOR_VERTEX];
        int[] counts = new int[totalStrips];

        int cursor = 0;
        cursor = writeRailRibbon(leftPts, halfStrokeNdc, color, out, cursor);
        counts[0] = railStripVerts;
        cursor = writeRailRibbon(rightPts, halfStrokeNdc, color, out, cursor);
        counts[1] = railStripVerts;

        if (crossTicks != null) {
            float[][] tickColors = {
                    {0.94f, 0.27f, 0.27f, 0.90f}, // 0.5m 红色预警线
                    {0.96f, 0.72f, 0.16f, 0.85f}, // 1.0m 琥珀线
                    {0.20f, 0.83f, 0.60f, 0.80f}  // 2.0m 绿色参考线
            };
            for (int t = 0; t < crossTicks.length; t++) {
                float[] tc = tickColors[Math.min(t, tickColors.length - 1)];
                float x0 = crossTicks[t][0][0];
                float y0 = crossTicks[t][0][1];
                float x1 = crossTicks[t][1][0];
                float y1 = crossTicks[t][1][1];
                cursor = writeVertex(out, cursor, x0, y0 - halfStrokeNdc * 0.7f, tc);
                cursor = writeVertex(out, cursor, x0, y0 + halfStrokeNdc * 0.7f, tc);
                cursor = writeVertex(out, cursor, x1, y1 - halfStrokeNdc * 0.7f, tc);
                cursor = writeVertex(out, cursor, x1, y1 + halfStrokeNdc * 0.7f, tc);
                counts[2 + t] = 4;
            }
        }
        return new LineStripBatch(out, counts);
    }

    private static int writeRailRibbon(float[][] pts, float halfStrokeNdc, float[] color, float[] out, int cursor) {
        for (int i = 0; i < pts.length; i++) {
            float x = pts[i][0];
            float y = pts[i][1];
            float fade = 1.0f - (float) i / (float) Math.max(1, pts.length) * 0.35f;
            float[] c = new float[]{color[0], color[1], color[2], color[3] * fade};
            cursor = writeVertex(out, cursor, x - halfStrokeNdc, y, c);
            cursor = writeVertex(out, cursor, x + halfStrokeNdc, y, c);
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
