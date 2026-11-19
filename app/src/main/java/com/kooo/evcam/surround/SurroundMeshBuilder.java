package com.kooo.evcam.surround;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.surround.SurroundCalibrationProfile.CameraCalibration;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * 纯 Java 360 鸟瞰与单向视图网格生成器及 `.mesh` 二进制加载器：
 * - 支持直接解析默认标定套件（`surround/default/*.mesh`）的 `bird.mesh`、`front_wheels.mesh`、`rear_wheels.mesh`；
 * - 同时保留按 E5 标定档案在内存中动态生成网格的回退能力（支持 OpenCV 多项式鱼眼与统一球面模型）。
 */
public final class SurroundMeshBuilder {

    public static final String BUILTIN_MESH_ASSET_DIR = "surround/default";
    private static final int MESH_VERTEX_STRIDE_BYTES = 32; // 8 floats: x, y, u, v, extraU, extraV, blend, texId
    private static final int MAX_INDEXED_VERTICES = 32767;

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

    /**
     * 解析 `bird.mesh`（32 字节/顶点三角列表，单位 m）为去重后的世界坐标 cm 网格。
     * 渲染阶段只需通过顶点着色器 `uViewTransform` 缩放/平移即可零重建响应地面尺寸滑块调节。
     */
    @Nullable
    public static GridMeshData parseBirdMeshBinary(@Nullable byte[] rawBytes, double birdOffsetYCm) {
        if (rawBytes == null || rawBytes.length < MESH_VERTEX_STRIDE_BYTES * 3
                || (rawBytes.length % (MESH_VERTEX_STRIDE_BYTES * 3)) != 0) {
            return null;
        }
        int totalVerts = rawBytes.length / MESH_VERTEX_STRIDE_BYTES;
        ByteBuffer buf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN);

        Map<RawMeshVertexKey, Short> indexMap = new HashMap<>(Math.min(20000, totalVerts / 3));
        float[] tempVerts = new float[Math.min(MAX_INDEXED_VERTICES, totalVerts) * BIRD_FLOATS_PER_VERTEX];
        short[] indices = new short[totalVerts];
        int uniqueCount = 0;

        for (int i = 0; i < totalVerts; i++) {
            int baseByte = i * MESH_VERTEX_STRIDE_BYTES;
            int b0 = buf.getInt(baseByte);
            int b1 = buf.getInt(baseByte + 4);
            int b2 = buf.getInt(baseByte + 8);
            int b3 = buf.getInt(baseByte + 12);
            int b4 = buf.getInt(baseByte + 16);
            int b5 = buf.getInt(baseByte + 20);
            int b6 = buf.getInt(baseByte + 24);
            int b7 = buf.getInt(baseByte + 28);

            RawMeshVertexKey key = new RawMeshVertexKey(b0, b1, b2, b3, b4, b5, b6, b7);
            Short existing = indexMap.get(key);
            if (existing != null) {
                indices[i] = existing;
                continue;
            }
            if (uniqueCount >= MAX_INDEXED_VERTICES) {
                return null;
            }

            float vxM = Float.intBitsToFloat(b0);
            float vyM = Float.intBitsToFloat(b1);
            float u0 = Float.intBitsToFloat(b2);
            float v0 = Float.intBitsToFloat(b3);
            float u1 = Float.intBitsToFloat(b4);
            float v1 = Float.intBitsToFloat(b5);
            float blend = Float.intBitsToFloat(b6);
            float texId = Float.intBitsToFloat(b7);

            float wxCm = vxM * 100.0f;
            float wyCm = (float) (vyM * 100.0 - birdOffsetYCm);

            int id = Math.max(0, (int) Math.floor(texId));
            int camCode = (id / 2) % 4;
            boolean isBlend = (id % 2) != 0;
            int pIdx = mapMeshCamToEvcamIndex(camCode);
            int sIdx = isBlend ? mapMeshCamToEvcamIndex((camCode + 1) % 4) : pIdx;

            boolean pValid = (u0 != 0.0f || v0 != 0.0f);
            boolean sValid = (u1 != 0.0f || v1 != 0.0f);
            float effectiveBlend;
            if (!isBlend) {
                effectiveBlend = 0.0f;
            } else if (!pValid && sValid) {
                effectiveBlend = 1.0f;
            } else if (pValid && !sValid) {
                effectiveBlend = 0.0f;
            } else {
                effectiveBlend = (float) clamp(blend, 0.0, 1.0);
            }

            // 车体正下方的盲区顶点在 .mesh 中 UV 为 (0,0)，标记 underCarWeight=1.4 以渲染纯深色车底而非采样边角像素
            float underCarWeight = (!pValid && (!isBlend || !sValid)
                    && Math.abs(wxCm) < 150.0f && Math.abs(wyCm) < 300.0f) ? 1.4f : 0.0f;

            int outBase = uniqueCount * BIRD_FLOATS_PER_VERTEX;
            tempVerts[outBase] = wxCm;
            tempVerts[outBase + 1] = wyCm;
            tempVerts[outBase + 2] = (float) clamp(u0, 0.001, 0.999);
            tempVerts[outBase + 3] = (float) clamp(1.0f - v0, 0.001, 0.999);
            tempVerts[outBase + 4] = (float) clamp(u1, 0.001, 0.999);
            tempVerts[outBase + 5] = (float) clamp(1.0f - v1, 0.001, 0.999);
            tempVerts[outBase + 6] = (float) pIdx;
            tempVerts[outBase + 7] = (float) sIdx;
            tempVerts[outBase + 8] = effectiveBlend;
            tempVerts[outBase + 9] = underCarWeight;

            short idxShort = (short) uniqueCount;
            indexMap.put(key, idxShort);
            indices[i] = idxShort;
            uniqueCount++;
        }

        float[] finalVerts = new float[uniqueCount * BIRD_FLOATS_PER_VERTEX];
        System.arraycopy(tempVerts, 0, finalVerts, 0, finalVerts.length);
        // 车底盲区边界三角形一侧为有效地面 UV、另一侧原为 (0,0)，用同三角形有效邻点 UV 覆盖盲区顶点，避免跨图拉扯条纹
        for (int t = 0; t + 2 < indices.length; t += 3) {
            int i0 = (indices[t] & 0xFFFF) * BIRD_FLOATS_PER_VERTEX;
            int i1 = (indices[t + 1] & 0xFFFF) * BIRD_FLOATS_PER_VERTEX;
            int i2 = (indices[t + 2] & 0xFFFF) * BIRD_FLOATS_PER_VERTEX;
            int validBase = finalVerts[i0 + 9] < 0.5f ? i0
                    : (finalVerts[i1 + 9] < 0.5f ? i1
                    : (finalVerts[i2 + 9] < 0.5f ? i2 : -1));
            if (validBase < 0) {
                continue;
            }
            int[] triBases = {i0, i1, i2};
            for (int vb : triBases) {
                if (finalVerts[vb + 9] >= 1.0f) {
                    System.arraycopy(finalVerts, validBase + 2, finalVerts, vb + 2, 7);
                }
            }
        }
        return new GridMeshData(finalVerts, indices, BIRD_FLOATS_PER_VERTEX);
    }

    /**
     * 解析 `front_wheels.mesh` / `rear_wheels.mesh` 为左右双轮位网格数组 `[leftMesh, rightMesh]`。
     * 原网格左侧占 `x ∈ [-1, 0]`（`texId ≈ 4.1`），右侧占 `x ∈ [0, 1]`（`texId ≈ 0.1`），拆分后均重映射至完整视口 NDC `[-1, 1]`。
     */
    @Nullable
    public static GridMeshData[] parseWheelMeshPair(@Nullable byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length < MESH_VERTEX_STRIDE_BYTES * 3
                || (rawBytes.length % (MESH_VERTEX_STRIDE_BYTES * 3)) != 0) {
            return null;
        }
        int totalVerts = rawBytes.length / MESH_VERTEX_STRIDE_BYTES;
        int totalTris = totalVerts / 3;
        ByteBuffer buf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN);

        WheelSideAccumulator leftAcc = new WheelSideAccumulator(true, totalVerts);
        WheelSideAccumulator rightAcc = new WheelSideAccumulator(false, totalVerts);

        for (int t = 0; t < totalTris; t++) {
            int triBase = t * 3 * MESH_VERTEX_STRIDE_BYTES;
            float firstTexId = Float.intBitsToFloat(buf.getInt(triBase + 28));
            WheelSideAccumulator target = firstTexId > 2.5f ? leftAcc : rightAcc;
            for (int v = 0; v < 3; v++) {
                int vBase = triBase + v * MESH_VERTEX_STRIDE_BYTES;
                if (!target.addVertex(
                        buf.getInt(vBase),
                        buf.getInt(vBase + 4),
                        buf.getInt(vBase + 8),
                        buf.getInt(vBase + 12))) {
                    return null;
                }
            }
        }
        if (leftAcc.indexCount == 0 || rightAcc.indexCount == 0) {
            return null;
        }
        return new GridMeshData[]{leftAcc.build(), rightAcc.build()};
    }

    private static int mapMeshCamToEvcamIndex(int meshCamCode) {
        switch (meshCamCode) {
            case 0:
                return CAM_RIGHT;
            case 1:
                return CAM_FRONT;
            case 2:
                return CAM_LEFT;
            case 3:
            default:
                return CAM_BACK;
        }
    }

    private static final class RawMeshVertexKey {
        final int a, b, c, d, e, f, g, h;
        final int hash;

        RawMeshVertexKey(int a, int b, int c, int d, int e, int f, int g, int h) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.e = e;
            this.f = f;
            this.g = g;
            this.h = h;
            int res = a;
            res = 31 * res + b;
            res = 31 * res + c;
            res = 31 * res + d;
            res = 31 * res + e;
            res = 31 * res + f;
            res = 31 * res + g;
            res = 31 * res + h;
            this.hash = res;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RawMeshVertexKey)) return false;
            RawMeshVertexKey o = (RawMeshVertexKey) obj;
            return a == o.a && b == o.b && c == o.c && d == o.d
                    && e == o.e && f == o.f && g == o.g && h == o.h;
        }
    }

    private static final class WheelSideAccumulator {
        final boolean isLeft;
        final Map<Long, Short> indexMap = new HashMap<>(4096);
        final float[] verts;
        final short[] indices;
        int uniqueCount = 0;
        int indexCount = 0;

        WheelSideAccumulator(boolean isLeft, int maxVerts) {
            this.isLeft = isLeft;
            this.verts = new float[Math.min(MAX_INDEXED_VERTICES, Math.max(128, maxVerts)) * PATCH_FLOATS_PER_VERTEX];
            this.indices = new short[Math.max(128, maxVerts + 128)];
        }

        boolean addVertex(int bx, int by, int bu, int bv) {
            long key = (((long) bx) << 32) ^ (by & 0xFFFFFFFFL);
            // Also include UV bits in secondary check? Positions are unique on grid, pack exact key using RawMeshVertexKey if needed;
            // using RawMeshVertexKey avoids any collision when vertices overlap at boundaries.
            Short existing = indexMap.get(key);
            float vx = Float.intBitsToFloat(bx);
            float vy = Float.intBitsToFloat(by);
            float u = Float.intBitsToFloat(bu);
            float v = Float.intBitsToFloat(bv);
            if (existing != null) {
                int base = (existing & 0xFFFF) * PATCH_FLOATS_PER_VERTEX;
                float expectedNdcX = isLeft ? (vx * 2.0f + 1.0f) : (vx * 2.0f - 1.0f);
                if (Math.abs(verts[base] - expectedNdcX) < 1e-6f
                        && Math.abs(verts[base + 1] - vy) < 1e-6f
                        && Math.abs(verts[base + 2] - (float) clamp(u, 0.001, 0.999)) < 1e-5f) {
                    if (indexCount >= indices.length) return false;
                    indices[indexCount++] = existing;
                    return true;
                }
            }
            if (uniqueCount >= MAX_INDEXED_VERTICES || indexCount >= indices.length) {
                return false;
            }
            float ndcX = isLeft ? (vx * 2.0f + 1.0f) : (vx * 2.0f - 1.0f);
            float valid = (u >= -0.02f && u <= 1.02f && v >= -0.02f && v <= 1.02f) ? 1.0f : 0.0f;
            int outBase = uniqueCount * PATCH_FLOATS_PER_VERTEX;
            verts[outBase] = ndcX;
            verts[outBase + 1] = vy;
            verts[outBase + 2] = (float) clamp(u, 0.001, 0.999);
            verts[outBase + 3] = (float) clamp(1.0f - v, 0.001, 0.999);
            verts[outBase + 4] = valid;
            short idxShort = (short) uniqueCount;
            if (!indexMap.containsKey(key)) {
                indexMap.put(key, idxShort);
            }
            indices[indexCount++] = idxShort;
            uniqueCount++;
            return true;
        }

        GridMeshData build() {
            float[] finalVerts = new float[uniqueCount * PATCH_FLOATS_PER_VERTEX];
            System.arraycopy(verts, 0, finalVerts, 0, finalVerts.length);
            short[] finalIndices = new short[indexCount];
            System.arraycopy(indices, 0, finalIndices, 0, indexCount);
            return new GridMeshData(finalVerts, finalIndices, PATCH_FLOATS_PER_VERTEX);
        }
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
     * 构建前视或后视广角去畸变网格（默认使用内置标定档案参数）。
     */
    @NonNull
    public static GridMeshData buildRoadPatchMesh(boolean rear,
                                                  float viewportAspect,
                                                  boolean stretchFill,
                                                  int cols,
                                                  int rows) {
        return buildRoadPatchMesh(
                SurroundCalibrationManager.createBuiltInE5Profile(206, 530, 0),
                rear, viewportAspect, stretchFill, cols, rows);
    }

    /**
     * 基于标定档案构建前视或后视广角去畸变网格（兼容 OpenCV 多项式鱼眼与统一球面模型）。
     *
     * @param rear         false=前视，true=后视
     * @param stretchFill  true=按视口全幅拉伸铺满，false=保持广角自然比例
     */
    @NonNull
    public static GridMeshData buildRoadPatchMesh(@Nullable SurroundCalibrationProfile profile,
                                                  boolean rear,
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

        CameraCalibration cam = profile != null ? profile.getCamera(rear ? "back" : "front") : null;
        boolean usePoly = cam != null ? cam.usePolynomialDistortion : true;
        double fx = cam != null ? cam.fx : 318.0;
        double fy = cam != null ? cam.fy : 318.0;
        double cx = cam != null ? cam.cx : 640.0;
        double cy = cam != null ? cam.cy : 400.0;
        double xi = cam != null ? cam.xi : (rear ? 1.041836 : 1.007645);
        double[] dist = cam != null ? cam.distortion : new double[]{
                0.129996832562922, -0.041928332298147, 0.014005468157064, -0.002599688315656
        };

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

                double[] pxPy = projectVirtualRayToPixel(rayX, rayY, rayZ, fx, fy, cx, cy, xi, usePoly, dist);
                double px = pxPy[0];
                double py = pxPy[1];
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
     * 构建四轮/左右侧视的去畸变视角网格（无外置 `.mesh` 时的纯 Java 回退路径）。
     * wheelKind: 0=左前轮, 1=右前轮, 2=右后轮, 3=左后轮。
     */
    @NonNull
    public static GridMeshData buildWheelPatchMesh(int wheelKind, int cols, int rows) {
        return buildWheelPatchMesh(null, wheelKind, cols, rows);
    }

    @NonNull
    public static GridMeshData buildWheelPatchMesh(@Nullable SurroundCalibrationProfile profile,
                                                   int wheelKind,
                                                   int cols,
                                                   int rows) {
        int safeCols = Math.max(6, Math.min(36, cols));
        int safeRows = Math.max(6, Math.min(36, rows));
        int vertexCount = (safeCols + 1) * (safeRows + 1);
        float[] verts = new float[vertexCount * PATCH_FLOATS_PER_VERTEX];

        final double[] r0;
        final double[] r1;
        final double[] r2;
        final double fcX;
        final double fcY;
        final double fallbackXi;
        if (wheelKind == 0) { // 左前轮
            r0 = new double[]{-0.006753175, -0.718364635, 0.695633988};
            r1 = new double[]{0.576466059, 0.565627013, 0.589705830};
            r2 = new double[]{-0.817093188, 0.404991770, 0.410293051};
            fcX = 0.907;
            fcY = 0.53;
            fallbackXi = 1.014824;
        } else if (wheelKind == 1) { // 右前轮
            r0 = new double[]{0.022861805, 0.730352624, -0.682687617};
            r1 = new double[]{-0.561813001, 0.574228184, 0.595506629};
            r2 = new double[]{0.826948299, 0.369928422, 0.423449492};
            fcX = 0.093;
            fcY = 0.53;
            fallbackXi = 1.009981;
        } else if (wheelKind == 2) { // 右后轮
            r0 = new double[]{0.110878139, 0.705164486, -0.700320702};
            r1 = new double[]{-0.588681490, 0.614344283, 0.525390527};
            r2 = new double[]{0.800724761, 0.354011510, 0.483234631};
            fcX = 0.136;
            fcY = 0.50;
            fallbackXi = 1.014824;
        } else { // 左后轮 (wheelKind == 3)
            r0 = new double[]{0.081731338, -0.694212027, 0.715115130};
            r1 = new double[]{0.574531309, 0.619117768, 0.535356857};
            r2 = new double[]{-0.814391652, 0.367100600, 0.449447868};
            fcX = 0.873;
            fcY = 0.50;
            fallbackXi = 1.009981;
        }
        String camKey = (wheelKind == 1 || wheelKind == 2) ? "right" : "left";
        CameraCalibration cam = profile != null ? profile.getCamera(camKey) : null;
        boolean usePoly = cam != null && cam.usePolynomialDistortion;
        double fx = cam != null ? cam.fx : 592.60;
        double fy = cam != null ? cam.fy : 592.60;
        double cx = cam != null ? cam.cx : 630.53;
        double cy = cam != null ? cam.cy : 369.36;
        double xi = cam != null ? cam.xi : fallbackXi;
        double[] dist = cam != null ? cam.distortion : new double[]{0d, 0d, 0d, 0d};
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

                double[] pxPy = projectVirtualRayToPixel(camX, camY, camZ, fx, fy, cx, cy, xi, usePoly, dist);
                double px = pxPy[0];
                double py = pxPy[1];
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
    private static double[] projectVirtualRayToPixel(double camX,
                                                     double camY,
                                                     double camZ,
                                                     double fx,
                                                     double fy,
                                                     double cx,
                                                     double cy,
                                                     double xi,
                                                     boolean usePolynomialDistortion,
                                                     @NonNull double[] distortion) {
        if (usePolynomialDistortion) {
            double rad = Math.hypot(camX, camY);
            if (rad < 1e-9 && camZ <= 0.0) {
                return new double[]{-1.0, -1.0};
            }
            double theta = Math.atan2(rad, camZ);
            if (theta > Math.PI * 0.58) {
                return new double[]{-1.0, -1.0};
            }
            double t2 = theta * theta;
            double k1 = distortion.length > 0 ? distortion[0] : 0d;
            double k2 = distortion.length > 1 ? distortion[1] : 0d;
            double k3 = distortion.length > 2 ? distortion[2] : 0d;
            double k4 = distortion.length > 3 ? distortion[3] : 0d;
            double thetaD = theta * (1.0 + t2 * (k1 + t2 * (k2 + t2 * (k3 + t2 * k4))));
            double scale = rad > 1e-8 ? (thetaD / rad) : 1.0;
            return new double[]{fx * camX * scale + cx, fy * camY * scale + cy};
        }
        double cLen = Math.sqrt(camX * camX + camY * camY + camZ * camZ);
        double denom = camZ + xi * cLen;
        if (denom <= 1e-4) {
            return new double[]{-1.0, -1.0};
        }
        return new double[]{fx * camX / denom + cx, fy * camY / denom + cy};
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
