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

    public static final List<String> CAMERA_KEYS = Collections.unmodifiableList(
            Arrays.asList("front", "back", "left", "right"));

    private static final Gson GSON = new GsonBuilder().create();
    private static final double MIN_CAMERA_HEIGHT_CM = 10.0;

    public static final SurroundExtrinsicAdjustment IDENTITY =
            new SurroundExtrinsicAdjustment(Collections.emptyMap());

    /** 单路摄像头外参微调量（不可变）。 */
    public static final class CameraOffset {
        public static final CameraOffset ZERO = new CameraOffset(0f, 0f, 0f, 0, 0, 0);

        public final float pitchDeg;
        public final float yawDeg;
        public final float rollDeg;
        public final int posXCm;
        public final int posYCm;
        public final int posZCm;

        public CameraOffset(float pitchDeg,
                            float yawDeg,
                            float rollDeg,
                            int posXCm,
                            int posYCm,
                            int posZCm) {
            this.pitchDeg = normalizeAngle(pitchDeg, MIN_PITCH_DEG, MAX_PITCH_DEG);
            this.yawDeg = normalizeAngle(yawDeg, MIN_YAW_DEG, MAX_YAW_DEG);
            this.rollDeg = normalizeAngle(rollDeg, MIN_ROLL_DEG, MAX_ROLL_DEG);
            this.posXCm = clampInt(posXCm, MIN_POS_X_CM, MAX_POS_X_CM);
            this.posYCm = clampInt(posYCm, MIN_POS_Y_CM, MAX_POS_Y_CM);
            this.posZCm = clampInt(posZCm, MIN_POS_Z_CM, MAX_POS_Z_CM);
        }

        public boolean isZero() {
            return Math.abs(pitchDeg) < 1e-4f
                    && Math.abs(yawDeg) < 1e-4f
                    && Math.abs(rollDeg) < 1e-4f
                    && posXCm == 0
                    && posYCm == 0
                    && posZCm == 0;
        }

        @NonNull
        public CameraOffset withPitchDeg(float nextPitchDeg) {
            return new CameraOffset(nextPitchDeg, yawDeg, rollDeg, posXCm, posYCm, posZCm);
        }

        @NonNull
        public CameraOffset withYawDeg(float nextYawDeg) {
            return new CameraOffset(pitchDeg, nextYawDeg, rollDeg, posXCm, posYCm, posZCm);
        }

        @NonNull
        public CameraOffset withRollDeg(float nextRollDeg) {
            return new CameraOffset(pitchDeg, yawDeg, nextRollDeg, posXCm, posYCm, posZCm);
        }

        @NonNull
        public CameraOffset withPosXCm(int nextPosXCm) {
            return new CameraOffset(pitchDeg, yawDeg, rollDeg, nextPosXCm, posYCm, posZCm);
        }

        @NonNull
        public CameraOffset withPosYCm(int nextPosYCm) {
            return new CameraOffset(pitchDeg, yawDeg, rollDeg, posXCm, nextPosYCm, posZCm);
        }

        @NonNull
        public CameraOffset withPosZCm(int nextPosZCm) {
            return new CameraOffset(pitchDeg, yawDeg, rollDeg, posXCm, posYCm, nextPosZCm);
        }

        @NonNull
        public String toCompactPart() {
            if (isZero()) {
                return "0";
            }
            return String.format(Locale.US, "%.1f,%.1f,%.1f,%d,%d,%d",
                    pitchDeg, yawDeg, rollDeg, posXCm, posYCm, posZCm);
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
                    && posZCm == that.posZCm;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pitchDeg, yawDeg, rollDeg, posXCm, posYCm, posZCm);
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
        root.addProperty("version", 1);
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
                float pitch = optFloat(item, "pitch_deg");
                float yaw = optFloat(item, "yaw_deg");
                float roll = optFloat(item, "roll_deg");
                int dx = optInt(item, "pos_x_cm");
                int dy = optInt(item, "pos_y_cm");
                int dz = optInt(item, "pos_z_cm");
                CameraOffset off = new CameraOffset(pitch, yaw, roll, dx, dy, dz);
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
     * 将单路位置偏移与局部旋转叠加到基准相机参数上（右手系 `q = R * (world - pos)`）：
     * - `+pitchDeg`：物理镜头向上仰/更平，减少对地俯角（地面采样点在原图下移，推走误入地面的远景）
     * - `+yawDeg`：物理镜头向右偏航
     * - `+rollDeg`：物理镜头顺时针滚转
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

        return new CameraCalibration(
                base.position,
                nextX,
                nextY,
                nextZ,
                rNew[0],
                rNew[1],
                rNew[2],
                base.fx,
                base.fy,
                base.cx,
                base.cy,
                base.xi,
                base.usePolynomialDistortion,
                base.distortion
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

    private static int clampInt(int raw, int minVal, int maxVal) {
        return Math.max(minVal, Math.min(maxVal, raw));
    }

    private static float optFloat(@NonNull JsonObject obj, @NonNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return 0f;
        try {
            float v = el.getAsFloat();
            return Float.isFinite(v) ? v : 0f;
        } catch (RuntimeException e) {
            return 0f;
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
