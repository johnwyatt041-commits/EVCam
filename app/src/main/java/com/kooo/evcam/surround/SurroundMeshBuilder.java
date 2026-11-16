package com.kooo.evcam.surround;

import androidx.annotation.NonNull;

import com.kooo.evcam.surround.SurroundCalibrationProfile.CameraCalibration;

/**
 * 纯 Java 360 鸟瞰与单向视图网格生成器（按 E5 标定档案在内存中构建三角形顶点与平滑羽毛混合权重）。
 * 彻底替代外部二进制 `.mesh` 资产依赖，并避免在渲染线程执行 `glReadPixels`。
 */
public final class SurroundMeshBuilder {

    /**
     * 鸟瞰网格每个顶点 10 个 float：
     * [ndcX, ndcY, primaryU, primaryV, secondaryU, secondaryV, primaryIdx, secondaryIdx, blendWeight, underCarWeight]
     * 相机索引约定：0=front, 1=back, 2=left, 3=right。
     */
    public static final int BIRD_FLOATS_PER_VERTEX = 10;

    /**
     * 单视角去畸变网格每个顶点 5 个 float：
     * [ndcX, ndcY, texU, texV, validAlpha]
     */
    public static final int PATCH_FLOATS_PER_VERTEX = 5;

    public static final int CAM_FRONT = 0;
    public static final int CAM_BACK = 1;
    public static final int CAM_LEFT = 2;
    public static final int CAM_RIGHT = 3;

    public static final class GridMeshData {
        @NonNull
        public final float[] vertices;
        @NonNull
        public final short[] indices;
        public final int floatsPerVertex;

        public GridMeshData(@NonNull float[] vertices, @NonNull short[] indices, int floatsPerVertex) {
            this.vertices = vertices;
            this.indices = indices;
            this.floatsPerVertex = floatsPerVertex;
        }
    }

    private SurroundMeshBuilder() {
    }

    /** 构建 360 全景鸟瞰地面融合网格（支持根据 E5 视野参数实时重生成）。 */
    @NonNull
    public static GridMeshData buildBirdGroundMesh(@NonNull SurroundCalibrationProfile profile,
                                                   int cols,
                                                   int rows) {
        int safeCols = Math.max(8, Math.min(80, cols));
        int safeRows = Math.max(8, Math.min(120, rows));

        int vertexCount = (safeCols + 1) * (safeRows + 1);
        float[] verts = new float[vertexCount * BIRD_FLOATS_PER_VERTEX];

        double halfW = profile.getHalfWorldWidthCm();
        double minY = profile.getWorldMinYCm();
        double maxY = profile.getWorldMaxYCm();
        double worldSpanY = maxY - minY;

        final float carMinX = SurroundCalibrationProfile.E5_CAR_MIN_X_CM;
        final float carMaxX = SurroundCalibrationProfile.E5_CAR_MAX_X_CM;
        final float carMinY = SurroundCalibrationProfile.E5_CAR_MIN_Y_CM;
        final float carMaxY = SurroundCalibrationProfile.E5_CAR_MAX_Y_CM;

        CameraCalibration frontCal = profile.getCamera("front");
        CameraCalibration backCal = profile.getCamera("back");
        CameraCalibration leftCal = profile.getCamera("left");
        CameraCalibration rightCal = profile.getCamera("right");

        int vIdx = 0;
        for (int r = 0; r <= safeRows; r++) {
            double vRatio = (double) r / (double) safeRows; // 0=底(-Y 后方), 1=顶(+Y 前方)
            double origY = minY + vRatio * worldSpanY;
            float ndcY = (float) (vRatio * 2.0 - 1.0);

            for (int c = 0; c <= safeCols; c++) {
                double uRatio = (double) c / (double) safeCols; // 0=左(-X), 1=右(+X)
                double origX = (uRatio - 0.5) * 2.0 * halfW;
                float ndcX = (float) (uRatio * 2.0 - 1.0);

                // 车底区域把采样点夹回车身边缘并计算羽化系数（不遮断外圈地面，无需 glReadPixels）
                double sampleX = origX;
                double sampleY = origY;
                float underCarWeight = 0.0f;
                boolean underCar = origX > carMinX && origX < carMaxX
                        && origY > carMinY && origY < carMaxY;
                if (underCar) {
                    double dLeft = origX - carMinX;
                    double dRight = carMaxX - origX;
                    double dRear = origY - carMinY;
                    double dFront = carMaxY - origY;
                    double minEdge = Math.min(Math.min(dLeft, dRight), Math.min(dRear, dFront));
                    if (minEdge == dLeft) sampleX = carMinX;
                    else if (minEdge == dRight) sampleX = carMaxX;
                    else if (minEdge == dRear) sampleY = carMinY;
                    else sampleY = carMaxY;
                    underCarWeight = smoothstep(0.0f, 24.0f, (float) minEdge);
                }

                // 前/后相机 vs 左/右相机象限混合权重（沿车身四角斜向 45° 融合带平滑过渡）
                CameraCalibration longCal = origY >= 0.0 ? frontCal : backCal;
                int longIdx = origY >= 0.0 ? CAM_FRONT : CAM_BACK;
                CameraCalibration sideCal = origX < 0.0 ? leftCal : rightCal;
                int sideIdx = origX < 0.0 ? CAM_LEFT : CAM_RIGHT;

                double[] longUv = longCal != null
                        ? longCal.projectWorldToUv(sampleX, sampleY, 0.0)
                        : new double[]{0.5, 0.5, 0.0};
                double[] sideUv = sideCal != null
                        ? sideCal.projectWorldToUv(sampleX, sampleY, 0.0)
                        : new double[]{0.5, 0.5, 0.0};

                double absX = Math.abs(origX);
                double absY = Math.abs(origY);
                double seamCarY = origY >= 0.0 ? 269.0 : 259.0;
                double seamSigned = (absY - seamCarY) - (absX - 106.0);
                float blendLongToSide = (float) clamp((seamSigned / 44.0) + 0.5, 0.0, 1.0);
                // primary = side, secondary = long; blendWeight = 1 表示完全使用 secondary (front/back)
                if (longUv[2] < 0.5) blendLongToSide = 0.0f;
                if (sideUv[2] < 0.5) blendLongToSide = 1.0f;

                int base = vIdx * BIRD_FLOATS_PER_VERTEX;
                verts[base] = ndcX;
                verts[base + 1] = ndcY;
                verts[base + 2] = (float) sideUv[0];
                verts[base + 3] = (float) sideUv[1];
                verts[base + 4] = (float) longUv[0];
                verts[base + 5] = (float) longUv[1];
                verts[base + 6] = (float) sideIdx;
                verts[base + 7] = (float) longIdx;
                verts[base + 8] = blendLongToSide;
                verts[base + 9] = underCarWeight;
                vIdx++;
            }
        }

        return new GridMeshData(verts, buildTriangleIndices(safeCols, safeRows), BIRD_FLOATS_PER_VERTEX);
    }

    /**
     * 构建前视或后视广角去畸变网格。
     *
     * @param rear         false=前视，true=后视
     * @param stretchFill  true=按视口全幅拉伸铺满，false=保持广角自然比例
     */
    @NonNull
    public static GridMeshData buildRoadPatchMesh(boolean rear,
                                                  float viewportAspect,
                                                  boolean stretchFill,
                                                  int cols,
                                                  int rows) {
        int safeCols = Math.max(6, Math.min(48, cols));
        int safeRows = Math.max(6, Math.min(36, rows));
        int vertexCount = (safeCols + 1) * (safeRows + 1);
        float[] verts = new float[vertexCount * PATCH_FLOATS_PER_VERTEX];

        float safeAspect = Math.max(0.4f, Math.min(4.0f, viewportAspect));
        double effectiveAspect = stretchFill ? safeAspect * 1.15 : Math.min(safeAspect, 1.65);
        double xi = rear ? 1.041836 : 1.007645;
        double cx = rear ? 640.00 : 630.53;
        double cy = rear ? 400.00 : 369.36;

        int vIdx = 0;
        for (int r = 0; r <= safeRows; r++) {
            double vRatio = (double) r / (double) safeRows; // 0=底, 1=顶
            float ndcY = (float) (vRatio * 2.0 - 1.0);
            for (int c = 0; c <= safeCols; c++) {
                double uRatio = (double) c / (double) safeCols; // 0=左, 1=右
                float ndcX = (float) (uRatio * 2.0 - 1.0);

                double sampleU = rear ? (1.0 - uRatio) : uRatio;
                double rayX = (sampleU - 0.5) * 2.5 * effectiveAspect;
                double rayY = (0.5 - vRatio) * 2.5;
                double rayZ = 1.0;

                double norm = Math.sqrt(rayX * rayX + rayY * rayY + rayZ * rayZ);
                double denom = Math.max(rayZ + xi * norm, 1e-4);
                double px = 592.60 * rayX / denom + cx;
                double py = 592.60 * rayY / denom + cy;
                float valid = (px >= 0.0 && px <= 1279.0 && py >= 0.0 && py <= 799.0) ? 1.0f : 0.0f;

                int base = vIdx * PATCH_FLOATS_PER_VERTEX;
                verts[base] = ndcX;
                verts[base + 1] = ndcY;
                verts[base + 2] = (float) clamp(px / 1280.0, 0.001, 0.999);
                verts[base + 3] = (float) clamp(1.0 - py / 800.0, 0.001, 0.999);
                verts[base + 4] = valid;
                vIdx++;
            }
        }
        return new GridMeshData(verts, buildTriangleIndices(safeCols, safeRows), PATCH_FLOATS_PER_VERTEX);
    }

    /**
     * 构建四轮/左右侧视的去畸变视角网格（对标 SC Lew3 "wheel" 光学射线旋转参数）。
     * wheelKind: 0=左前轮, 1=右前轮, 2=右后轮, 3=左后轮。
     */
    @NonNull
    public static GridMeshData buildWheelPatchMesh(int wheelKind, int cols, int rows) {
        int safeCols = Math.max(6, Math.min(36, cols));
        int safeRows = Math.max(6, Math.min(36, rows));
        int vertexCount = (safeCols + 1) * (safeRows + 1);
        float[] verts = new float[vertexCount * PATCH_FLOATS_PER_VERTEX];

        final double[] r0;
        final double[] r1;
        final double[] r2;
        final double fcX;
        final double fcY;
        final double xi;
        if (wheelKind == 0) { // 左前轮
            r0 = new double[]{-0.006753175, -0.718364635, 0.695633988};
            r1 = new double[]{0.576466059, 0.565627013, 0.589705830};
            r2 = new double[]{-0.817093188, 0.404991770, 0.410293051};
            fcX = 0.907;
            fcY = 0.53;
            xi = 1.014824;
        } else if (wheelKind == 1) { // 右前轮
            r0 = new double[]{0.022861805, 0.730352624, -0.682687617};
            r1 = new double[]{-0.561813001, 0.574228184, 0.595506629};
            r2 = new double[]{0.826948299, 0.369928422, 0.423449492};
            fcX = 0.093;
            fcY = 0.53;
            xi = 1.009981;
        } else if (wheelKind == 2) { // 右后轮
            r0 = new double[]{0.110878139, 0.705164486, -0.700320702};
            r1 = new double[]{-0.588681490, 0.614344283, 0.525390527};
            r2 = new double[]{0.800724761, 0.354011510, 0.483234631};
            fcX = 0.136;
            fcY = 0.50;
            xi = 1.014824;
        } else { // 左后轮 (wheelKind == 3)
            r0 = new double[]{0.081731338, -0.694212027, 0.715115130};
            r1 = new double[]{0.574531309, 0.619117768, 0.535356857};
            r2 = new double[]{-0.814391652, 0.367100600, 0.449447868};
            fcX = 0.873;
            fcY = 0.50;
            xi = 1.009981;
        }
        double inward = wheelKind >= 2 ? 0.040 : 0.032;

        int vIdx = 0;
        for (int r = 0; r <= safeRows; r++) {
            double vRatio = (double) r / (double) safeRows; // 0=底, 1=顶
            float ndcY = (float) (vRatio * 2.0 - 1.0);
            for (int c = 0; c <= safeCols; c++) {
                double uRatio = (double) c / (double) safeCols; // 0=左, 1=右
                float ndcX = (float) (uRatio * 2.0 - 1.0);

                double viewU = uRatio;
                double viewV = 1.0 - vRatio; // 纹理顶朝下坐标系
                if (wheelKind < 2) {
                    viewU += (wheelKind == 0 ? inward : -inward);
                } else {
                    viewU = 1.0 - viewU + (wheelKind == 2 ? -inward : inward);
                    viewV += 0.12;
                }

                double ox = (viewU - fcX) * 1.361204719;
                double oy = ((1.0 - viewV) - fcY) * 1.956585857;
                double oz = 1.0;
                double oLen = Math.sqrt(ox * ox + oy * oy + oz * oz);
                ox /= oLen;
                oy /= oLen;
                oz /= oLen;

                double camX = r0[0] * ox + r0[1] * oy + r0[2] * oz;
                double camY = r1[0] * ox + r1[1] * oy + r1[2] * oz;
                double camZ = r2[0] * ox + r2[1] * oy + r2[2] * oz;
                double cLen = Math.sqrt(camX * camX + camY * camY + camZ * camZ);
                double denom = Math.max(camZ + xi * cLen, 1e-4);

                double px = 592.60 * camX / denom + 630.53;
                double py = 592.60 * camY / denom + 369.36;
                float valid = (px >= 0.0 && px <= 1279.0 && py >= 0.0 && py <= 799.0) ? 1.0f : 0.0f;

                int base = vIdx * PATCH_FLOATS_PER_VERTEX;
                verts[base] = ndcX;
                verts[base + 1] = ndcY;
                verts[base + 2] = (float) clamp(px / 1280.0, 0.001, 0.999);
                verts[base + 3] = (float) clamp(1.0 - py / 800.0, 0.001, 0.999);
                verts[base + 4] = valid;
                vIdx++;
            }
        }
        return new GridMeshData(verts, buildTriangleIndices(safeCols, safeRows), PATCH_FLOATS_PER_VERTEX);
    }

    @NonNull
    private static short[] buildTriangleIndices(int cols, int rows) {
        short[] indices = new short[cols * rows * 6];
        int idx = 0;
        int stride = cols + 1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                short bl = (short) (r * stride + c);
                short br = (short) (bl + 1);
                short tl = (short) (bl + stride);
                short tr = (short) (tl + 1);
                indices[idx++] = bl;
                indices[idx++] = br;
                indices[idx++] = tr;
                indices[idx++] = bl;
                indices[idx++] = tr;
                indices[idx++] = tl;
            }
        }
        return indices;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
