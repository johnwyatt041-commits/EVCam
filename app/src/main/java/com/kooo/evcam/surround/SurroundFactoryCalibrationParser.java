package com.kooo.evcam.surround;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kooo.evcam.surround.SurroundCalibrationProfile.CameraCalibration;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 吉利/Flyme Auto 原厂 360 标定资源包（`avm_calib_res`）离线解析与转换器：
 * - 支持校验并解析 704 字节 1.3 版 `*.param`（4 路 OpenCV 鱼眼多项式内参、Rodrigues 旋转向量及安装位置）；
 * - 支持校验 `Table.db`（392 字节/条查找记录）并与 `.param` 自动匹配重投影精度，转换为 `bird.mesh`（32 字节/顶点）；
 * - 支持解析原厂 `table_cam_two_front.db` / `table_cam_two_rear.db`（72 字节/三角形）转换为 `front_wheels.mesh` / `rear_wheels.mesh`，缺少时按标定参数合成 `55×36` 双轮位网格；
 * - 支持读取 `config.ini` 的 `bottom_det` 车底中心偏移，居中后生成标准 1.3 `calibration.json`。
 */
public final class SurroundFactoryCalibrationParser {

    public static final int PARAM_FILE_BYTES = 704;
    public static final int TABLE_RECORD_BYTES = 392;
    public static final int WHEEL_TRIANGLE_BYTES = 72;
    public static final int MESH_VERTEX_BYTES = 32;

    private static final double[][] WHEEL_FRONT_ROTATION = {
            {1.0, 0.0, 0.0},
            {0.0, -0.731353702, 0.68199836},
            {0.0, -0.68199836, -0.731353702}
    };
    private static final double[][] WHEEL_REAR_LEFT_ROTATION = {
            {-0.994370426, 0.060126135, -0.08724853},
            {0.104512543, 0.692165231, -0.714131935},
            {0.017452407, -0.719230242, -0.69455257}
    };
    private static final double[][] WHEEL_REAR_RIGHT_ROTATION = {
            {-0.994370424, -0.060126134, 0.08724853},
            {-0.104512543, 0.692165231, -0.714131935},
            {-0.017452407, -0.719230242, -0.69455257}
    };

    private static final int[] QUAD_TO_TRI_SLOTS = {2, 0, 3, 3, 0, 1};
    // positionId/2 -> {0:right, 1:front, 2:left, 3:back} 映射到 param 顺序 {0:front, 1:back, 2:left, 3:right}
    private static final int[] TABLE_CAM_TO_PARAM_INDEX = {3, 0, 2, 1};
    private static final String[] PARAM_CAMERA_KEYS = {"front", "back", "left", "right"};

    public static final class NamedBytes {
        @NonNull public final String name;
        @NonNull public final byte[] bytes;

        public NamedBytes(@NonNull String name, @NonNull byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    public static final class FactoryBundleInput {
        @NonNull public final String bundleName;
        @NonNull public final byte[] tableDbBytes;
        @NonNull public final List<NamedBytes> paramFiles;
        @Nullable public final byte[] frontWheelDbBytes;
        @Nullable public final byte[] rearWheelDbBytes;
        @Nullable public final byte[] configIniBytes;

        public FactoryBundleInput(@NonNull String bundleName,
                                  @NonNull byte[] tableDbBytes,
                                  @NonNull List<NamedBytes> paramFiles,
                                  @Nullable byte[] frontWheelDbBytes,
                                  @Nullable byte[] rearWheelDbBytes,
                                  @Nullable byte[] configIniBytes) {
            this.bundleName = bundleName;
            this.tableDbBytes = tableDbBytes;
            this.paramFiles = paramFiles;
            this.frontWheelDbBytes = frontWheelDbBytes;
            this.rearWheelDbBytes = rearWheelDbBytes;
            this.configIniBytes = configIniBytes;
        }
    }

    public static final class ParsedParamFile {
        @NonNull public final String sourceName;
        @NonNull public final Map<String, CameraCalibration> rawCameras;
        @NonNull public final double[][] rvecs;
        @NonNull public final double[][] posMeters;

        public ParsedParamFile(@NonNull String sourceName,
                               @NonNull Map<String, CameraCalibration> rawCameras,
                               @NonNull double[][] rvecs,
                               @NonNull double[][] posMeters) {
            this.sourceName = sourceName;
            this.rawCameras = rawCameras;
            this.rvecs = rvecs;
            this.posMeters = posMeters;
        }
    }

    public static final class ConvertedFactoryBundle {
        @NonNull public final String calibrationJson;
        @NonNull public final byte[] birdMesh;
        @NonNull public final byte[] frontWheelsMesh;
        @NonNull public final byte[] rearWheelsMesh;
        public final double birdOffsetYCm;
        public final double maxUvError;
        public final boolean usedNativeWheelTables;
        @NonNull public final String summaryMessage;

        public ConvertedFactoryBundle(@NonNull String calibrationJson,
                                      @NonNull byte[] birdMesh,
                                      @NonNull byte[] frontWheelsMesh,
                                      @NonNull byte[] rearWheelsMesh,
                                      double birdOffsetYCm,
                                      double maxUvError,
                                      boolean usedNativeWheelTables,
                                      @NonNull String summaryMessage) {
            this.calibrationJson = calibrationJson;
            this.birdMesh = birdMesh;
            this.frontWheelsMesh = frontWheelsMesh;
            this.rearWheelsMesh = rearWheelsMesh;
            this.birdOffsetYCm = birdOffsetYCm;
            this.maxUvError = maxUvError;
            this.usedNativeWheelTables = usedNativeWheelTables;
            this.summaryMessage = summaryMessage;
        }
    }

    private SurroundFactoryCalibrationParser() {
    }

    /** 将完整的原厂 360 标定包校验并转换为 EVCam 可用的 `calibration.json` 与 `.mesh` 资产。 */
    @NonNull
    public static ConvertedFactoryBundle convertFactoryBundle(@NonNull FactoryBundleInput input) {
        if (input.paramFiles.isEmpty()) {
            throw new IllegalArgumentException("缺少 .param 标定参数文件，请与 Table.db 放在同一文件夹。");
        }
        if (input.tableDbBytes.length < TABLE_RECORD_BYTES) {
            throw new IllegalArgumentException("Table.db 映射表格式不受支持：缺少完整记录。");
        }

        // 1. 解析并按二进制内容去重 .param 文件
        Map<String, ParsedParamFile> uniqueParams = new LinkedHashMap<>();
        List<String> parseErrors = new ArrayList<>();
        for (NamedBytes nb : input.paramFiles) {
            try {
                ParsedParamFile parsed = parseParam704(nb.bytes, nb.name);
                String digest = sha256Hex(nb.bytes);
                if (!uniqueParams.containsKey(digest)) {
                    uniqueParams.put(digest, parsed);
                }
            } catch (IllegalArgumentException e) {
                parseErrors.add(nb.name + "：" + e.getMessage());
            }
        }
        if (uniqueParams.isEmpty()) {
            String reason = parseErrors.isEmpty() ? "未找到有效的 704 字节 1.3 版 .param 文件" : parseErrors.get(0);
            throw new IllegalArgumentException(reason);
        }

        // 2. 校验 Table.db 并匹配唯一有效 .param
        List<ParsedParamFile> paramList = new ArrayList<>(uniqueParams.values());
        ParamMatchResult match = validateTableDbAndMatchParam(input.tableDbBytes, paramList);

        // 3. 解析/回退车身偏移 bottom_det（cm）与前后轮位网格
        double birdOffsetYCm = parseBirdOffsetYCm(input.configIniBytes, match.param);
        byte[] birdMesh = convertTableDbToBirdMesh(input.tableDbBytes);

        byte[] frontWheelsMesh = null;
        byte[] rearWheelsMesh = null;
        boolean usedNativeWheels = false;
        if (input.frontWheelDbBytes != null && input.rearWheelDbBytes != null
                && input.frontWheelDbBytes.length >= WHEEL_TRIANGLE_BYTES
                && input.rearWheelDbBytes.length >= WHEEL_TRIANGLE_BYTES) {
            frontWheelsMesh = convertWheelTableToMesh(input.frontWheelDbBytes);
            rearWheelsMesh = convertWheelTableToMesh(input.rearWheelDbBytes);
            usedNativeWheels = true;
        } else {
            frontWheelsMesh = generateWheelMeshFromParam(match.param, false);
            rearWheelsMesh = generateWheelMeshFromParam(match.param, true);
        }

        String calibrationJson = buildCenteredCalibrationJson(input.bundleName, match.param, birdOffsetYCm);
        String summary = String.format(Locale.US,
                "已转换原厂标定 %s（%s，%s，UV最大误差 %.2e）",
                input.bundleName,
                match.param.sourceName,
                usedNativeWheels ? "原厂轮位表" : "合成轮位网格",
                match.maxUvError);
        return new ConvertedFactoryBundle(
                calibrationJson,
                birdMesh,
                frontWheelsMesh,
                rearWheelsMesh,
                birdOffsetYCm,
                match.maxUvError,
                usedNativeWheels,
                summary);
    }

    /** 解析并校验 704 字节 1.3 版原厂 `.param` 二进制文件。 */
    @NonNull
    public static ParsedParamFile parseParam704(@NonNull byte[] bytes, @NonNull String sourceName) {
        if (bytes.length != PARAM_FILE_BYTES) {
            throw new IllegalArgumentException("暂不支持此标定格式，需要 704 字节的 1.3 版参数文件（实际 " + bytes.length + " B）。");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int verMajor = buf.getInt(0);
        int verMinor = buf.getInt(4);
        int fileLen = buf.getInt(8);
        if (verMajor != 1 || verMinor != 3 || fileLen != PARAM_FILE_BYTES) {
            throw new IllegalArgumentException("暂不支持此标定版本（仅支持 1.3 版 704 字节参数）。");
        }
        if (buf.getInt(12) != -1 || buf.getInt(16) != 1) {
            throw new IllegalArgumentException("参数未包含四路有效标定。");
        }

        Map<String, CameraCalibration> cameras = new LinkedHashMap<>(4);
        double[][] rvecs = new double[4][3];
        double[][] posMeters = new double[4][3];

        for (int i = 0; i < 4; i++) {
            double[] intr = new double[8];
            int intrBase = 32 + i * 96;
            for (int j = 0; j < 8; j++) {
                double v = buf.getDouble(intrBase + j * 8);
                if (!Double.isFinite(v) || Math.abs(v) > 10000.0) {
                    throw new IllegalArgumentException("镜头内参超出支持范围：" + PARAM_CAMERA_KEYS[i]);
                }
                intr[j] = v;
            }
            double fx = intr[0];
            double fy = intr[1];
            double cx = intr[2];
            double cy = intr[3];
            if (fx < 1.0 || fx > 3000.0 || fy < 1.0 || fy > 3000.0
                    || cx < 0.0 || cx > SurroundCalibrationProfile.IMAGE_WIDTH
                    || cy < 0.0 || cy > SurroundCalibrationProfile.IMAGE_HEIGHT) {
                throw new IllegalArgumentException("镜头焦距或光心超出支持范围：" + PARAM_CAMERA_KEYS[i]);
            }

            int extSlotOffset = i * 36;
            double[] rvec = readCvMatVec3(buf, 416 + extSlotOffset, "旋转向量");
            double[] posM = readCvMatVec3(buf, 560 + extSlotOffset, "位置向量");
            double[][] rot = rodriguesToMatrix(rvec);
            double[] dist = {intr[4], intr[5], intr[6], intr[7]};

            rvecs[i] = rvec;
            posMeters[i] = posM;
            cameras.put(PARAM_CAMERA_KEYS[i], new CameraCalibration(
                    PARAM_CAMERA_KEYS[i],
                    posM[0] * 100.0,
                    posM[1] * 100.0,
                    posM[2] * 100.0,
                    rot[0],
                    rot[1],
                    rot[2],
                    fx,
                    fy,
                    cx,
                    cy,
                    0.0,
                    true,
                    dist));
        }

        return new ParsedParamFile(sourceName, cameras, rvecs, posMeters);
    }

    private static final class ParamMatchResult {
        @NonNull final ParsedParamFile param;
        final double maxUvError;

        ParamMatchResult(@NonNull ParsedParamFile param, double maxUvError) {
            this.param = param;
            this.maxUvError = maxUvError;
        }
    }

    @NonNull
    private static ParamMatchResult validateTableDbAndMatchParam(@NonNull byte[] tableDb,
                                                                 @NonNull List<ParsedParamFile> params) {
        int recordCount = tableDb.length / TABLE_RECORD_BYTES;
        if (recordCount <= 0) {
            throw new IllegalArgumentException("Table.db 映射表格式不受支持：缺少完整记录。");
        }
        ByteBuffer buf = ByteBuffer.wrap(tableDb).order(ByteOrder.LITTLE_ENDIAN);
        int[] camSampleCounts = new int[4];
        double[] maxErrors = new double[params.size()];
        boolean[] errorOverflow = new boolean[params.size()];

        for (int rec = 0; rec < recordCount; rec++) {
            int off = rec * TABLE_RECORD_BYTES;
            int positionId = buf.getInt(off);
            if (positionId < 0 || positionId >= 8) {
                throw new IllegalArgumentException("映射表镜头编号不受支持: positionId=" + positionId);
            }
            for (int slot = 0; slot < 8; slot++) {
                int slotOff = off + 8 + slot * 48;
                double[] vals = new double[6];
                for (int k = 0; k < 6; k++) {
                    double v = buf.getDouble(slotOff + k * 8);
                    if (!Double.isFinite(v) || Math.abs(v) > 10000.0) {
                        throw new IllegalArgumentException("映射表包含异常数值。");
                    }
                    vals[k] = v;
                }
                if ((positionId % 2) == 0 && slot < 4
                        && vals[4] > 0.0 && vals[4] < 1.0
                        && vals[5] > 0.0 && vals[5] < 1.0) {
                    int camIdx = TABLE_CAM_TO_PARAM_INDEX[positionId / 2];
                    camSampleCounts[camIdx]++;
                    String camKey = PARAM_CAMERA_KEYS[camIdx];
                    for (int p = 0; p < params.size(); p++) {
                        if (errorOverflow[p]) continue;
                        CameraCalibration cam = params.get(p).rawCameras.get(camKey);
                        if (cam == null) continue;
                        double[] uv = projectRawNormalizedUvAtan(cam, vals[0] * 100.0, vals[1] * 100.0, vals[2] * 100.0);
                        double err = Math.hypot(uv[0] - vals[4], uv[1] - vals[5]);
                        if (!Double.isFinite(err)) {
                            errorOverflow[p] = true;
                        } else if (err > maxErrors[p]) {
                            maxErrors[p] = err;
                        }
                    }
                }
            }
        }

        for (int i = 0; i < 4; i++) {
            if (camSampleCounts[i] < 16) {
                throw new IllegalArgumentException("映射表缺少完整的四路镜头数据（" + PARAM_CAMERA_KEYS[i] + " 样本不足）。");
            }
        }

        List<Integer> exactMatches = new ArrayList<>();
        int bestIdx = 0;
        double bestErr = Double.POSITIVE_INFINITY;
        for (int p = 0; p < params.size(); p++) {
            double err = errorOverflow[p] ? Double.POSITIVE_INFINITY : maxErrors[p];
            if (err < bestErr) {
                bestErr = err;
                bestIdx = p;
            }
            if (err <= 1e-4) {
                exactMatches.add(p);
            }
        }

        if (exactMatches.size() > 1) {
            throw new IllegalArgumentException("发现多套与 Table.db 匹配的 .param 参数，请仅保留一套后重新导入。");
        }
        if (exactMatches.size() == 1) {
            int idx = exactMatches.get(0);
            return new ParamMatchResult(params.get(idx), maxErrors[idx]);
        }
        if (params.size() == 1 && Double.isFinite(bestErr) && bestErr <= 0.25) {
            return new ParamMatchResult(params.get(0), bestErr);
        }
        throw new IllegalArgumentException(String.format(Locale.US,
                "参数与 Table.db 映射表不匹配（最大 UV 偏差 %.4f），请确认同一套标定文件。", bestErr));
    }

    /** 将 `Table.db`（392B 记录）转换为 EVCam/Smartcam 兼容的 `bird.mesh`（32B/顶点）。 */
    @NonNull
    public static byte[] convertTableDbToBirdMesh(@NonNull byte[] tableDb) {
        int recordCount = tableDb.length / TABLE_RECORD_BYTES;
        if (recordCount <= 0) {
            throw new IllegalArgumentException("Table.db 缺少有效记录");
        }
        ByteBuffer src = ByteBuffer.wrap(tableDb).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer out = ByteBuffer.allocate(recordCount * 6 * MESH_VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        for (int rec = 0; rec < recordCount; rec++) {
            int off = rec * TABLE_RECORD_BYTES;
            int positionId = src.getInt(off);
            float texId = positionId + 0.1f;
            for (int s = 0; s < QUAD_TO_TRI_SLOTS.length; s++) {
                int vOff = off + 8 + QUAD_TO_TRI_SLOTS[s] * 48;
                double wx = src.getDouble(vOff);
                double wy = src.getDouble(vOff + 8);
                double u0 = src.getDouble(vOff + 32);
                double v0 = src.getDouble(vOff + 40);
                double blend = src.getDouble(vOff + 216);
                double u1 = src.getDouble(vOff + 224);
                double v1 = src.getDouble(vOff + 232);

                out.putFloat((float) wx);
                out.putFloat((float) wy);
                out.putFloat((float) u0);
                out.putFloat((float) v0);
                out.putFloat((float) u1);
                out.putFloat((float) v1);
                out.putFloat((float) blend);
                out.putFloat(texId);
            }
        }
        return out.array();
    }

    /** 将原厂 `table_cam_two_front.db` / `table_cam_two_rear.db`（72B/三角形）转换为 `*_wheels.mesh`。 */
    @NonNull
    public static byte[] convertWheelTableToMesh(@NonNull byte[] wheelDb) {
        int triCount = wheelDb.length / WHEEL_TRIANGLE_BYTES;
        if (triCount <= 0) {
            throw new IllegalArgumentException("原厂轮子映射格式不受支持：缺少完整三角形。");
        }
        ByteBuffer src = ByteBuffer.wrap(wheelDb).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream leftBos = new ByteArrayOutputStream();
        ByteArrayOutputStream rightBos = new ByteArrayOutputStream();
        double[] sideAreas = new double[2];

        for (int t = 0; t < triCount; t++) {
            int triOff = t * WHEEL_TRIANGLE_BYTES;
            float[][] tri = new float[3][6];
            for (int v = 0; v < 3; v++) {
                int vOff = triOff + v * 24;
                for (int k = 0; k < 6; k++) {
                    float val = src.getFloat(vOff + k * 4);
                    if (!Float.isFinite(val)) {
                        throw new IllegalArgumentException("原厂轮子映射包含异常数值。");
                    }
                    tri[v][k] = val;
                }
                if (tri[v][0] < -1.0f || tri[v][0] > 1.0f
                        || tri[v][1] < -1.0f || tri[v][1] > 1.0f
                        || Math.abs(tri[v][2]) >= 1e-5f
                        || Math.abs(tri[v][3]) > 10000.0f
                        || Math.abs(tri[v][4]) > 10000.0f) {
                    throw new IllegalArgumentException("原厂轮子映射顶点超出支持范围。");
                }
            }
            int camInt = (int) tri[0][5];
            for (int v = 0; v < 3; v++) {
                if (Math.abs(tri[v][5] - (camInt + 0.1f)) >= 1e-4f) {
                    throw new IllegalArgumentException("原厂轮子映射镜头编号不一致。");
                }
            }
            if (camInt == 3) {
                for (int v = 0; v < 3; v++) {
                    if (Math.abs(tri[v][0]) > 0.015f || tri[v][3] != 0.0f || tri[v][4] != 0.0f) {
                        throw new IllegalArgumentException("暂不支持此原厂分隔区域轮子映射。");
                    }
                }
                continue;
            }
            if (camInt < 0 || camInt > 1) {
                throw new IllegalArgumentException("原厂轮子映射左右分区不受支持: cam=" + camInt);
            }
            for (int v = 0; v < 3; v++) {
                if ((camInt == 0 && tri[v][0] > 0.0f) || (camInt == 1 && tri[v][0] < 0.0f)) {
                    throw new IllegalArgumentException("原厂轮子映射左右分区坐标异常。");
                }
            }
            double area = Math.abs(
                    (tri[2][1] - tri[0][1]) * (tri[1][0] - tri[0][0])
                            - (tri[2][0] - tri[0][0]) * (tri[1][1] - tri[0][1])) * 0.5;
            if (area <= 1e-12) {
                throw new IllegalArgumentException("原厂轮子映射包含退化三角形。");
            }
            sideAreas[camInt] += area;

            ByteBuffer vertBuf = ByteBuffer.allocate(3 * MESH_VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            float outTexId = camInt == 0 ? 4.1f : 0.1f;
            for (int v = 0; v < 3; v++) {
                vertBuf.putFloat(tri[v][0]);
                vertBuf.putFloat(tri[v][1]);
                vertBuf.putFloat(tri[v][3]);
                vertBuf.putFloat(tri[v][4]);
                vertBuf.putFloat(tri[v][3]);
                vertBuf.putFloat(tri[v][4]);
                vertBuf.putFloat(0.0f);
                vertBuf.putFloat(outTexId);
            }
            byte[] triBytes = vertBuf.array();
            if (camInt == 0) {
                leftBos.write(triBytes, 0, triBytes.length);
            } else {
                rightBos.write(triBytes, 0, triBytes.length);
            }
        }

        double minExpectedArea = (wheelDb.length % WHEEL_TRIANGLE_BYTES) == 0 ? 1.998 : 1.995;
        for (int s = 0; s < 2; s++) {
            if (sideAreas[s] < minExpectedArea || sideAreas[s] > 2.002) {
                throw new IllegalArgumentException("原厂轮子映射未完整覆盖左右视口。");
            }
        }
        byte[] left = leftBos.toByteArray();
        byte[] right = rightBos.toByteArray();
        byte[] combined = new byte[left.length + right.length];
        System.arraycopy(left, 0, combined, 0, left.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return combined;
    }

    /** 无原厂轮子表时，按 1.3 版左右镜头标定参数合成标准 `55×36` 双轮位 `.mesh`（760,320 B）。 */
    @NonNull
    public static byte[] generateWheelMeshFromParam(@NonNull ParsedParamFile param, boolean rear) {
        final int cols = 55;
        final int rows = 36;
        final String[] keys = {"left", "right"};
        ByteArrayOutputStream bos = new ByteArrayOutputStream(cols * rows * 2 * 6 * MESH_VERTEX_BYTES);
        DataOutputStream dos = new DataOutputStream(bos);
        try {
            for (int side = 0; side < 2; side++) {
                CameraCalibration cam = param.rawCameras.get(keys[side]);
                if (cam == null) {
                    throw new IllegalArgumentException("缺少侧视镜头参数: " + keys[side]);
                }
                double[][] virtRot = rear
                        ? (side == 0 ? WHEEL_REAR_LEFT_ROTATION : WHEEL_REAR_RIGHT_ROTATION)
                        : WHEEL_FRONT_ROTATION;
                double fcX = rear ? (side == 0 ? 0.23 : 0.77) : (side == 0 ? 0.78 : 0.22);
                double fcY = rear ? 0.50 : 0.53;
                double inward = rear ? (side == 0 ? -0.04 : 0.04) : (side == 0 ? 0.03 : -0.03);

                double[][][] uvGrid = new double[rows + 1][cols + 1][2];
                for (int r = 0; r <= rows; r++) {
                    for (int c = 0; c <= cols; c++) {
                        double u = (double) c / cols;
                        double v = (double) r / rows;
                        double viewU = rear ? ((1.0 - u) + inward) : (u + inward);
                        double viewV = rear ? (v + 0.12) : v;
                        double[] outRay = {
                                (viewU - fcX) * 1.361204719,
                                ((1.0 - viewV) - fcY) * 1.956585857,
                                1.0
                        };
                        double[] tmp = mulMat3Vec3(virtRot, outRay);
                        double[] camRay = {
                                dot3(cam.r0, tmp),
                                dot3(cam.r1, tmp),
                                dot3(cam.r2, tmp)
                        };
                        uvGrid[r][c] = projectCamRayNormalizedUvAtan2(cam, camRay);
                    }
                }

                float texId = side == 0 ? 4.1f : 0.1f;
                int[] triC = {0, 1, 0, 0, 1, 1};
                int[] triR = {0, 0, 1, 1, 0, 1};
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        for (int k = 0; k < 6; k++) {
                            int vc = c + triC[k];
                            int vr = r + triR[k];
                            float ndcX = (float) ((double) vc / cols - 1.0 + side);
                            float ndcY = (float) ((double) vr * 2.0 / rows - 1.0);
                            float texU = (float) uvGrid[vr][vc][0];
                            float texV = (float) uvGrid[vr][vc][1];
                            writeLeFloat(dos, ndcX);
                            writeLeFloat(dos, ndcY);
                            writeLeFloat(dos, texU);
                            writeLeFloat(dos, texV);
                            writeLeFloat(dos, texU);
                            writeLeFloat(dos, texV);
                            writeLeFloat(dos, 0.0f);
                            writeLeFloat(dos, texId);
                        }
                    }
                }
            }
            dos.flush();
        } catch (IOException e) {
            throw new IllegalStateException("合成轮位网格失败: " + e.getMessage(), e);
        }
        return bos.toByteArray();
    }

    /** 解析 `config.ini` 中的 `bottom_det`（单位 m → cm），缺失时由前后摄中点或 `-143.0` 推导。 */
    public static double parseBirdOffsetYCm(@Nullable byte[] configIniBytes, @NonNull ParsedParamFile param) {
        if (configIniBytes != null && configIniBytes.length > 0) {
            String text = new String(configIniBytes, StandardCharsets.UTF_8);
            String[] lines = text.split("\\r?\\n");
            for (String rawLine : lines) {
                int eq = rawLine.indexOf('=');
                if (eq <= 0) continue;
                String key = rawLine.substring(0, eq).trim();
                if (!"bottom_det".equalsIgnoreCase(key)) continue;
                String valPart = rawLine.substring(eq + 1);
                int commentIdx = valPart.indexOf('#');
                if (commentIdx >= 0) {
                    valPart = valPart.substring(0, commentIdx);
                }
                try {
                    double valM = Double.parseDouble(valPart.trim());
                    if (Double.isFinite(valM) && valM >= -10.0 && valM <= 10.0) {
                        return valM * 100.0;
                    }
                } catch (NumberFormatException ignored) {
                }
                throw new IllegalArgumentException("config.ini 中车身位置参数 bottom_det 无效。");
            }
        }
        CameraCalibration f = param.rawCameras.get("front");
        CameraCalibration b = param.rawCameras.get("back");
        if (f != null && b != null) {
            double midYCm = (f.posYCm + b.posYCm) * 0.5;
            if (Math.abs(midYCm) > 25.0) {
                return midYCm;
            }
        }
        return SurroundCalibrationProfile.DEFAULT_BIRD_CENTER_OFFSET_Y_CM;
    }

    @NonNull
    private static String buildCenteredCalibrationJson(@NonNull String bundleName,
                                                       @NonNull ParsedParamFile param,
                                                       double birdOffsetYCm) {
        JsonObject root = new JsonObject();
        root.addProperty("profile_name", bundleName);
        root.addProperty("provenance", "Imported OEM 1.3 avm_calib_res (" + param.sourceName + ")");
        JsonArray ver = new JsonArray();
        ver.add(1);
        ver.add(3);
        root.add("file_version", ver);
        JsonArray size = new JsonArray();
        size.add(SurroundCalibrationProfile.IMAGE_WIDTH);
        size.add(SurroundCalibrationProfile.IMAGE_HEIGHT);
        root.add("image_size", size);
        root.addProperty("lens_model", "opencv_fisheye_theta_polynomial");
        root.addProperty("vehicle_centered", true);
        root.addProperty("bird_offset_y_cm", birdOffsetYCm);

        JsonObject camerasObj = new JsonObject();
        String[][] keyPairs = {
                {"front", "front"},
                {"back", "rear"},
                {"left", "left"},
                {"right", "right"}
        };
        for (int i = 0; i < keyPairs.length; i++) {
            CameraCalibration cam = param.rawCameras.get(keyPairs[i][0]);
            if (cam == null) continue;
            JsonObject camObj = new JsonObject();
            camObj.addProperty("fx", cam.fx);
            camObj.addProperty("fy", cam.fy);
            camObj.addProperty("cx", cam.cx);
            camObj.addProperty("cy", cam.cy);

            JsonArray dist = new JsonArray();
            for (int k = 0; k < 4; k++) {
                dist.add(k < cam.distortion.length ? cam.distortion[k] : 0.0);
            }
            camObj.add("distortion", dist);

            JsonArray pos = new JsonArray();
            pos.add(cam.posXCm / 100.0);
            pos.add((cam.posYCm - birdOffsetYCm) / 100.0);
            pos.add(cam.posZCm / 100.0);
            camObj.add("position", pos);

            JsonArray rvec = new JsonArray();
            for (int k = 0; k < 3; k++) {
                rvec.add(param.rvecs[i][k]);
            }
            camObj.add("rotation_vector", rvec);

            JsonArray rot = new JsonArray();
            rot.add(toJsonArray(cam.r0));
            rot.add(toJsonArray(cam.r1));
            rot.add(toJsonArray(cam.r2));
            camObj.add("world_to_camera_rotation", rot);

            camerasObj.add(keyPairs[i][1], camObj);
        }
        root.add("cameras", camerasObj);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(root) + "\n";
    }

    @NonNull
    private static double[] readCvMatVec3(@NonNull ByteBuffer buf, int offset, @NonNull String label) {
        int rows = buf.getInt(offset);
        int cols = buf.getInt(offset + 4);
        int cvType = buf.getInt(offset + 8);
        if (rows != 3 || cols != 1 || cvType != 6) {
            throw new IllegalArgumentException("标定" + label + "格式不受支持。");
        }
        double[] vec = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = buf.getDouble(offset + 12 + i * 8);
            if (!Double.isFinite(v) || Math.abs(v) > 100.0) {
                throw new IllegalArgumentException("标定" + label + "数值异常。");
            }
            vec[i] = v;
        }
        return vec;
    }

    /** 标准 OpenCV Rodrigues 旋转向量转 3×3 正交旋转矩阵。 */
    @NonNull
    public static double[][] rodriguesToMatrix(@NonNull double[] rvec) {
        double theta = Math.sqrt(rvec[0] * rvec[0] + rvec[1] * rvec[1] + rvec[2] * rvec[2]);
        if (theta < 1e-12) {
            return new double[][]{
                    {1.0, 0.0, 0.0},
                    {0.0, 1.0, 0.0},
                    {0.0, 0.0, 1.0}
            };
        }
        double ux = rvec[0] / theta;
        double uy = rvec[1] / theta;
        double uz = rvec[2] / theta;
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        double omc = 1.0 - c;
        return new double[][]{
                {ux * ux * omc + c, ux * uy * omc - uz * s, ux * uz * omc + uy * s},
                {ux * uy * omc + uz * s, uy * uy * omc + c, uy * uz * omc - ux * s},
                {ux * uz * omc - uy * s, uy * uz * omc + ux * s, uz * uz * omc + c}
        };
    }

    private static double[] projectRawNormalizedUvAtan(@NonNull CameraCalibration cam,
                                                       double wxCm,
                                                       double wyCm,
                                                       double wzCm) {
        double dx = wxCm - cam.posXCm;
        double dy = wyCm - cam.posYCm;
        double dz = wzCm - cam.posZCm;
        double cx = cam.r0[0] * dx + cam.r0[1] * dy + cam.r0[2] * dz;
        double cy = cam.r1[0] * dx + cam.r1[1] * dy + cam.r1[2] * dz;
        double cz = cam.r2[0] * dx + cam.r2[1] * dy + cam.r2[2] * dz;
        double r = Math.hypot(cx, cy);
        double theta = Math.atan(r / cz);
        return applyFishEyePoly(cam, cx, cy, r, theta);
    }

    private static double[] projectCamRayNormalizedUvAtan2(@NonNull CameraCalibration cam,
                                                           @NonNull double[] camRay) {
        double r = Math.hypot(camRay[0], camRay[1]);
        double theta = Math.atan2(r, camRay[2]);
        return applyFishEyePoly(cam, camRay[0], camRay[1], r, theta);
    }

    private static double[] applyFishEyePoly(@NonNull CameraCalibration cam,
                                             double cx,
                                             double cy,
                                             double r,
                                             double theta) {
        double t2 = theta * theta;
        double k1 = cam.distortion.length > 0 ? cam.distortion[0] : 0.0;
        double k2 = cam.distortion.length > 1 ? cam.distortion[1] : 0.0;
        double k3 = cam.distortion.length > 2 ? cam.distortion[2] : 0.0;
        double k4 = cam.distortion.length > 3 ? cam.distortion[3] : 0.0;
        double thetaD = theta * (1.0 + t2 * (k1 + t2 * (k2 + t2 * (k3 + t2 * k4))));
        double safeR = Math.max(r, 1e-15);
        double u = (cam.fx * cx * thetaD / safeR + cam.cx) / SurroundCalibrationProfile.IMAGE_WIDTH;
        double v = (cam.fy * cy * thetaD / safeR + cam.cy) / SurroundCalibrationProfile.IMAGE_HEIGHT;
        return new double[]{u, v};
    }

    private static double[] mulMat3Vec3(@NonNull double[][] m, @NonNull double[] v) {
        return new double[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]
        };
    }

    private static double dot3(@NonNull double[] a, @NonNull double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static void writeLeFloat(@NonNull DataOutputStream dos, float val) throws IOException {
        dos.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(val)));
    }

    @NonNull
    private static JsonArray toJsonArray(@NonNull double[] arr) {
        JsonArray out = new JsonArray();
        for (double v : arr) {
            out.add(v);
        }
        return out;
    }

    @NonNull
    private static String sha256Hex(@NonNull byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
