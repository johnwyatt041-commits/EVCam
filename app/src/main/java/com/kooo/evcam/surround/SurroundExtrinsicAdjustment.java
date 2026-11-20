package com.kooo.evcam.surround;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kooo.evcam.surround.SurroundCalibrationProfile.CameraCalibration;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 360 四路摄像头外参实时微调模型（纯 Java）：
 * - 按相机局部右手系（+x 右，+y 下，+z 光轴前）叠加俯仰 ΔPitch、偏航 ΔYaw、滚转 ΔRoll；
 * - 按世界坐标系（+X 右，+Y 前，+Z 上）叠加位置平移 ΔX / ΔY / ΔZ（cm）；
 * - 支持确定性签名比对、JSON 持久化序列化，并与原标定方案分槽独立保存。
 */
public final class SurroundExtrinsicAdjustment {

    public static final float MIN_PITCH_DEG = -20.0f;
    public static final float MAX_PITCH_DEG = 20.0f;
    public static final float MIN_YAW_DEG = -20.0f;
    public static final float MAX_YAW_DEG = 20.0f;
    public static final float MIN_ROLL_DEG = -15.0f;
    public static final float MAX_ROLL_DEG = 15.0f;
    public static final int MIN_POS_X_CM = -40;
    public static final int MAX_POS_X_CM = 40;
    public static final int MIN_POS_Y_CM = -60;
    public static final int MAX_POS_Y_CM = 60;
    public static final int MIN_POS_Z_CM = -40;
    public static final int MAX_POS_Z_CM = 40;

    public static final float MIN_SCALE = 0.70f;
    public static final float MAX_SCALE = 1.30f;
    public static final float DEFAULT_SCALE = 1.00f;
    public static final int MIN_CX_OFFSET_PX = -60;
    public static final int MAX_CX_OFFSET_PX = 60;
    public static final int MIN_CY_OFFSET_PX = -60;
    public static final int MAX_CY_OFFSET_PX = 60;
    public static final float MIN_K1_DELTA = -0.150f;
    public static final float MAX_K1_DELTA = 0.150f;

    public static final List<String> CAMERA_KEYS = Collections.unmodifiableList(
            Arrays.asList("front", "back", "left", "right"));

    private static final Gson GSON = new GsonBuilder().create();
    private static final double MIN_CAMERA_HEIGHT_CM = 10.0;

    public static final SurroundExtrinsicAdjustment IDENTITY =
            new SurroundExtrinsicAdjustment(Collections.emptyMap());

    /** 单路摄像头外参及内参微调量（不可变）。 */
    public static final class CameraOffset {
        public static final CameraOffset ZERO = new CameraOffset(0f, 0f, 0f, 0, 0, 0, DEFAULT_SCALE, 0, 0, 0f);

        public final float pitchDeg;
        public final float yawDeg;
        public final float rollDeg;
        public final int posXCm;
        public final int posYCm;
        public final int posZCm;
        public final float scale;
        public final int cxOffsetPx;
        public final int cyOffsetPx;
        public final float k1Delta;

        public CameraOffset(float pitchDeg,
                            float yawDeg,
                            float rollDeg,
                            int posXCm,
                            int posYCm,
                            int posZCm) {
            this(pitchDeg, yawDeg, rollDeg, posXCm, posYCm, posZCm, DEFAULT_SCALE, 0, 0, 0f);
        }

        public CameraOffset(float pitchDeg,
                            float yawDeg,
                            float rollDeg,
                            int posXCm,
                            int posYCm,
                            int posZCm,
                            float scale,
                            int cxOffsetPx,
                            int cyOffsetPx,
                            float k1Delta) {
            this.pitchDeg = normalizeAngle(pitchDeg, MIN_PITCH_DEG, MAX_PITCH_DEG);
            this.yawDeg = normalizeAngle(yawDeg, MIN_YAW_DEG, MAX_YAW_DEG);
            this.rollDeg = normalizeAngle(rollDeg, MIN_ROLL_DEG, MAX_ROLL_DEG);
            this.posXCm = clampInt(posXCm, MIN_POS_X_CM, MAX_POS_X_CM);
            this.posYCm = clampInt(posYCm, MIN_POS_Y_CM, MAX_POS_Y_CM);
            this.posZCm = clampInt(posZCm, MIN_POS_Z_CM, MAX_POS_Z_CM);
            this.scale = normalizeScale(scale);
            this.cxOffsetPx = clampInt(cxOffsetPx, MIN_CX_OFFSET_PX, MAX_CX_OFFSET_PX);
            this.cyOffsetPx = clampInt(cyOffsetPx, MIN_CY_OFFSET_PX, MAX_CY_OFFSET_PX);
            this.k1Delta = normalizeK1Delta(k1Delta);
        }

        public boolean isZero() {
            return Math.abs(pitchDeg) < 1e-4f
                    && Math.abs(yawDeg) < 1e-4f
                    && Math.abs(rollDeg) < 1e-4f
                    && posXCm == 0
                    && posYCm == 0
                    && posZCm == 0
                    && Math.abs(scale - DEFAULT_SCALE) < 1e-4f
                    && cxOffsetPx == 0
                    && cyOffsetPx == 0
                    && Math.abs(k1Delta) < 1e-5f;
        }

        @NonNull
        public CameraOffset withPitchDeg(float nextPitchDeg) {
            return new CameraOffset(nextPitchDeg, yawDeg, rollDeg, posXCm, posYCm, posZCm,
                    scale, cxOffsetPx, cyOffsetPx, k1Delta);
        }

        @NonNull
        public CameraOffset withYawDeg(float nextYawDeg) {
            return new CameraOffset(pitchDeg, nextYawDeg, rollDeg, posXCm, posYCm, posZCm,
                    scale, cxOffsetPx, cyOffsetPx, k1Delta);
        }

        @NonNull
        public CameraOffset withRollDeg(float nextRollDeg) {
            return new CameraOffset(pitchDeg, yawDeg, nextRollDeg, posXCm, posYCm, posZCm,
                    scale, cxOffsetPx, cyOffsetPx, k1Delta);
        }

        @NonNull
        public CameraOffset withPosXCm(int nextPosXCm) {
            return new CameraOffset(pitchDeg, yawDeg, rollDeg, nextPosXCm, posYCm, posZCm,
                    scale, cxOffsetPx, cyOffsetPx, k1Delta);
        }

        @NonNull
        public CameraOffset withPosYCm(int nextPosYCm) {
            return new CameraOffset(pitchDeg, yawDeg, rollDeg, posXCm, nextPosYCm, posZCm,
                    scale, cxOffsetPx, cyOffsetPx, k1Delta);
        }

        @NonNull
        public CameraOffset withPosZCm(int nextPosZCm) {
            return new CameraOffset(pitchDeg, yawDeg, rollDeg, posXCm, posYCm, nextPosZCm,
                    scale, cxOffsetPx, cyOffsetPx, k1Delta);
        }

        @NonNull
        public CameraOffset withScale(float nextScale) {
            return new CameraOffset(pitchDeg, yawDeg, rollDeg, posXCm, posYCm, posZCm,
                    nextScale, cxOffsetPx, cyOffsetPx, k1Delta);
        }

        @NonNull
        public CameraOffset withCxOffsetPx(int nextCxOffsetPx) {
            return new CameraOffset(pitchDeg, yawDeg, rollDeg, posXCm, posYCm, posZCm,
                    scale, nextCxOffsetPx, cyOffsetPx, k1Delta);
        }

        @NonNull
        public CameraOffset withCyOffsetPx(int nextCyOffsetPx) {
            return new CameraOffset(pitchDeg, yawDeg, rollDeg, posXCm, posYCm, posZCm,
                    scale, cxOffsetPx, nextCyOffsetPx, k1Delta);
        }

        @NonNull
        public CameraOffset withK1Delta(float nextK1Delta) {
            return new CameraOffset(pitchDeg, yawDeg, rollDeg, posXCm, posYCm, posZCm,
                    scale, cxOffsetPx, cyOffsetPx, nextK1Delta);
        }

        @NonNull
        public String toCompactPart() {
            if (isZero()) {
                return "0";
            }
            return String.format(Locale.US, "%.1f,%.1f,%.1f,%d,%d,%d,%.2f,%d,%d,%.3f",
                    pitchDeg, yawDeg, rollDeg, posXCm, posYCm, posZCm,
                    scale, cxOffsetPx, cyOffsetPx, k1Delta);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CameraOffset)) return false;
            CameraOffset that = (CameraOffset) o;
            return Float.compare(that.pitchDeg, pitchDeg) == 0
                    && Float.compare(that.yawDeg, yawDeg) == 0
                    && Float.compare(that.rollDeg, rollDeg) == 0
                    && posXCm == that.posXCm
                    && posYCm == that.posYCm
                    && posZCm == that.posZCm
                    && Float.compare(that.scale, scale) == 0
                    && cxOffsetPx == that.cxOffsetPx
                    && cyOffsetPx == that.cyOffsetPx
                    && Float.compare(that.k1Delta, k1Delta) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pitchDeg, yawDeg, rollDeg, posXCm, posYCm, posZCm,
                    scale, cxOffsetPx, cyOffsetPx, k1Delta);
        }

        @NonNull
        @Override
        public String toString() {
            return "CameraOffset{" + toCompactPart() + "}";
        }
    }

    @NonNull
    private final Map<String, CameraOffset> cameras;

    private SurroundExtrinsicAdjustment(@NonNull Map<String, CameraOffset> source) {
        Map<String, CameraOffset> normalized = new LinkedHashMap<>(4);
        for (String key : CAMERA_KEYS) {
            CameraOffset off = source.get(key);
            normalized.put(key, (off != null && !off.isZero()) ? off : CameraOffset.ZERO);
        }
        this.cameras = Collections.unmodifiableMap(normalized);
    }

    @NonNull
    public static String normalizeCameraKey(@Nullable String key) {
        if (key == null) return "front";
        String lower = key.trim().toLowerCase(Locale.US);
        if ("rear".equals(lower) || "back".equals(lower)) return "back";
        if ("left".equals(lower)) return "left";
        if ("right".equals(lower)) return "right";
        return "front";
    }

    @NonNull
    public CameraOffset getCamera(@Nullable String key) {
        CameraOffset off = cameras.get(normalizeCameraKey(key));
        return off != null ? off : CameraOffset.ZERO;
    }

    public boolean isIdentity() {
        for (String key : CAMERA_KEYS) {
            if (!getCamera(key).isZero()) {
                return false;
            }
        }
        return true;
    }

    public int countNonZeroCameras() {
        int count = 0;
        for (String key : CAMERA_KEYS) {
            if (!getCamera(key).isZero()) {
                count++;
            }
        }
        return count;
    }

    @NonNull
    public SurroundExtrinsicAdjustment withCamera(@Nullable String key, @Nullable CameraOffset offset) {
        String canonical = normalizeCameraKey(key);
        Map<String, CameraOffset> next = new LinkedHashMap<>(cameras);
        next.put(canonical, (offset != null && !offset.isZero()) ? offset : CameraOffset.ZERO);
        SurroundExtrinsicAdjustment candidate = new SurroundExtrinsicAdjustment(next);
        return candidate.isIdentity() ? IDENTITY : candidate;
    }

    @NonNull
    public SurroundExtrinsicAdjustment resetCamera(@Nullable String key) {
        return withCamera(key, CameraOffset.ZERO);
    }

    @NonNull
    public String toCompactSignature() {
        if (isIdentity()) {
            return "identity";
        }
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < CAMERA_KEYS.size(); i++) {
            if (i > 0) sb.append('|');
            String key = CAMERA_KEYS.get(i);
            sb.append(key).append(':').append(getCamera(key).toCompactPart());
        }
        return sb.toString();
    }

    @NonNull
    public String toJson() {
        if (isIdentity()) {
            return "";
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        JsonObject camsObj = new JsonObject();
        for (String key : CAMERA_KEYS) {
            CameraOffset off = getCamera(key);
            if (off.isZero()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("pitch_deg", off.pitchDeg);
            item.addProperty("yaw_deg", off.yawDeg);
            item.addProperty("roll_deg", off.rollDeg);
            item.addProperty("pos_x_cm", off.posXCm);
            item.addProperty("pos_y_cm", off.posYCm);
            item.addProperty("pos_z_cm", off.posZCm);
            item.addProperty("scale", off.scale);
            item.addProperty("cx_px", off.cxOffsetPx);
            item.addProperty("cy_px", off.cyOffsetPx);
            item.addProperty("k1_delta", off.k1Delta);
            camsObj.add(key, item);
        }
        root.add("cameras", camsObj);
        return GSON.toJson(root);
    }

    @NonNull
    public static SurroundExtrinsicAdjustment fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return IDENTITY;
        }
        try {
            JsonElement rootEl = JsonParser.parseString(json);
            if (rootEl == null || !rootEl.isJsonObject()) {
                return IDENTITY;
            }
            JsonObject root = rootEl.getAsJsonObject();
            JsonElement camsEl = root.get("cameras");
            if (camsEl == null || !camsEl.isJsonObject()) {
                return IDENTITY;
            }
            JsonObject camsObj = camsEl.getAsJsonObject();
            Map<String, CameraOffset> map = new LinkedHashMap<>(4);
            for (String key : CAMERA_KEYS) {
                JsonElement camEl = camsObj.get(key);
                if ((camEl == null || !camEl.isJsonObject()) && "back".equals(key)) {
                    camEl = camsObj.get("rear");
                }
                if (camEl == null || !camEl.isJsonObject()) {
                    continue;
                }
                JsonObject item = camEl.getAsJsonObject();
                float pitch = optFloat(item, "pitch_deg", 0f);
                float yaw = optFloat(item, "yaw_deg", 0f);
                float roll = optFloat(item, "roll_deg", 0f);
                int dx = optInt(item, "pos_x_cm");
                int dy = optInt(item, "pos_y_cm");
                int dz = optInt(item, "pos_z_cm");
                float scale = optFloat(item, "scale", DEFAULT_SCALE);
                int dcx = optInt(item, "cx_px");
                int dcy = optInt(item, "cy_px");
                float dk1 = optFloat(item, "k1_delta", 0f);
                CameraOffset off = new CameraOffset(pitch, yaw, roll, dx, dy, dz, scale, dcx, dcy, dk1);
                if (!off.isZero()) {
                    map.put(key, off);
                }
            }
            SurroundExtrinsicAdjustment parsed = new SurroundExtrinsicAdjustment(map);
            return parsed.isIdentity() ? IDENTITY : parsed;
        } catch (RuntimeException e) {
            return IDENTITY;
        }
    }

    /**
     * 将单路位置偏移、局部旋转及内参缩放/光心/畸变微调叠加到基准相机参数上（右手系 `q = R * (world - pos)`）：
     * - `+pitchDeg`：物理镜头向上仰/更平，减少对地俯角（地面采样点在原图下移，推走误入地面的远景）
     * - `+yawDeg`：物理镜头向右偏航
     * - `+rollDeg`：物理镜头顺时针滚转
     * - `scale > 1.0`：按 `fx/scale` 缩小虚拟焦距，使鸟瞰/单视角画面中地面景物放大撑宽；`< 1.0` 则缩小收紧
     */
    @NonNull
    public static CameraCalibration applyToCamera(@NonNull CameraCalibration base,
                                                  @Nullable CameraOffset offset) {
        if (offset == null || offset.isZero()) {
            return base;
        }
        double nextX = base.posXCm + offset.posXCm;
        double nextY = base.posYCm + offset.posYCm;
        double nextZ = Math.max(MIN_CAMERA_HEIGHT_CM, base.posZCm + offset.posZCm);

        double p = Math.toRadians(-offset.pitchDeg);
        double y = Math.toRadians(-offset.yawDeg);
        double r = Math.toRadians(-offset.rollDeg);

        double cp = Math.cos(p), sp = Math.sin(p);
        double cy = Math.cos(y), sy = Math.sin(y);
        double cr = Math.cos(r), sr = Math.sin(r);

        double[][] rx = {
                {1.0, 0.0, 0.0},
                {0.0, cp, -sp},
                {0.0, sp, cp}
        };
        double[][] ry = {
                {cy, 0.0, sy},
                {0.0, 1.0, 0.0},
                {-sy, 0.0, cy}
        };
        double[][] rz = {
                {cr, -sr, 0.0},
                {sr, cr, 0.0},
                {0.0, 0.0, 1.0}
        };

        double[][] d = matMul3(rz, matMul3(rx, ry));
        double[][] rBase = {base.r0, base.r1, base.r2};
        double[][] rNew = matMul3(d, rBase);

        double safeScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, offset.scale));
        double nextFx = base.fx / safeScale;
        double nextFy = base.fy / safeScale;
        double nextCx = Math.max(0.0, Math.min(SurroundCalibrationProfile.IMAGE_WIDTH, base.cx + offset.cxOffsetPx));
        double nextCy = Math.max(0.0, Math.min(SurroundCalibrationProfile.IMAGE_HEIGHT, base.cy + offset.cyOffsetPx));
        double[] nextDist = new double[Math.max(4, base.distortion.length)];
        System.arraycopy(base.distortion, 0, nextDist, 0, base.distortion.length);
        nextDist[0] += offset.k1Delta;

        return new CameraCalibration(
                base.position,
                nextX,
                nextY,
                nextZ,
                rNew[0],
                rNew[1],
                rNew[2],
                nextFx,
                nextFy,
                nextCx,
                nextCy,
                base.xi,
                base.usePolynomialDistortion,
                nextDist
        );
    }

    @NonNull
    public SurroundCalibrationProfile applyToProfile(@NonNull SurroundCalibrationProfile base) {
        if (isIdentity()) {
            return base;
        }
        Map<String, CameraCalibration> adjusted = new LinkedHashMap<>(4);
        for (Map.Entry<String, CameraCalibration> entry : base.cameras.entrySet()) {
            String key = entry.getKey();
            CameraCalibration cam = entry.getValue();
            if (cam == null) continue;
            adjusted.put(key, applyToCamera(cam, getCamera(key)));
        }
        return new SurroundCalibrationProfile(
                base.profileName,
                base.isCustomCalibration,
                base.useBuiltInMeshes,
                true,
                base.birdCenterOffsetYCm,
                base.groundWidthCm,
                base.groundLengthCm,
                base.groundFrontExtensionCm,
                adjusted
        );
    }

    private static float normalizeAngle(float raw, float minDeg, float maxDeg) {
        if (!Float.isFinite(raw)) {
            return 0f;
        }
        float clamped = Math.max(minDeg, Math.min(maxDeg, raw));
        float rounded = Math.round(clamped * 2.0f) * 0.5f;
        return Math.abs(rounded) < 1e-4f ? 0f : rounded;
    }

    private static float normalizeScale(float raw) {
        if (!Float.isFinite(raw) || raw <= 0f) {
            return DEFAULT_SCALE;
        }
        float clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, raw));
        float rounded = Math.round(clamped * 100.0f) / 100.0f;
        return Math.abs(rounded - DEFAULT_SCALE) < 1e-4f ? DEFAULT_SCALE : rounded;
    }

    private static float normalizeK1Delta(float raw) {
        if (!Float.isFinite(raw)) {
            return 0f;
        }
        float clamped = Math.max(MIN_K1_DELTA, Math.min(MAX_K1_DELTA, raw));
        float rounded = Math.round(clamped * 200.0f) / 200.0f;
        return Math.abs(rounded) < 1e-5f ? 0f : rounded;
    }

    private static int clampInt(int raw, int minVal, int maxVal) {
        return Math.max(minVal, Math.min(maxVal, raw));
    }

    private static float optFloat(@NonNull JsonObject obj, @NonNull String key, float fallback) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return fallback;
        try {
            float v = el.getAsFloat();
            return Float.isFinite(v) ? v : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int optInt(@NonNull JsonObject obj, @NonNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return 0;
        try {
            return (int) Math.round(el.getAsDouble());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double[][] matMul3(@NonNull double[][] a, @NonNull double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SurroundExtrinsicAdjustment)) return false;
        SurroundExtrinsicAdjustment that = (SurroundExtrinsicAdjustment) o;
        return cameras.equals(that.cameras);
    }

    @Override
    public int hashCode() {
        return cameras.hashCode();
    }
}
