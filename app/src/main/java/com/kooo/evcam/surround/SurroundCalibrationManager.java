package com.kooo.evcam.surround;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.surround.SurroundCalibrationProfile.CameraCalibration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 银河 E5 360 标定档案工厂与本地 E5 标定 JSON 校验/导入器（完全离线，无领克 08、无云端依赖）。
 */
public final class SurroundCalibrationManager {

    private static final long MAX_CALIBRATION_FILE_BYTES = 2L * 1024L * 1024L;

    public static final class ImportResult {
        public final boolean success;
        @NonNull
        public final String message;
        @Nullable
        public final String importedFilePath;

        public ImportResult(boolean success, @NonNull String message, @Nullable String importedFilePath) {
            this.success = success;
            this.message = message;
            this.importedFilePath = importedFilePath;
        }
    }

    private SurroundCalibrationManager() {
    }

    /** 构造银河 E5 默认内置标定档案（参数与 E5 四路实测光学几何匹配）。 */
    @NonNull
    public static SurroundCalibrationProfile createBuiltInE5Profile(int groundWidthCm,
                                                                     int groundLengthCm,
                                                                     int groundFrontExtensionCm) {
        Map<String, CameraCalibration> map = new LinkedHashMap<>(4);
        map.put("front", new CameraCalibration(
                "front",
                0.06, 244.87, 63.89,
                new double[]{0.999899235, 0.014189049, -0.000436332},
                new double[]{0.005243726, -0.397738131, -0.917483996},
                new double[]{-0.013191772, 0.917389258, -0.397772456},
                592.60, 592.60, 630.53, 369.36, 1.007645, false, null
        ));
        map.put("back", new CameraCalibration(
                "back",
                0.06, -244.96, 74.85,
                new double[]{-0.999806090, 0.006037742, 0.018743738},
                new double[]{-0.011530504, 0.592107672, -0.805776366},
                new double[]{-0.015963381, -0.805836243, -0.591923238},
                592.60, 592.60, 640.00, 400.00, 1.041836, false, null
        ));
        map.put("left", new CameraCalibration(
                "left",
                -105.19, 82.50, 107.48,
                new double[]{-0.006753175, 0.999799874, -0.018830990},
                new double[]{0.576466059, -0.011495000, -0.817040237},
                new double[]{-0.817093188, -0.016373042, -0.576273065},
                592.60, 592.60, 630.53, 369.36, 1.014824, false, null
        ));
        map.put("right", new CameraCalibration(
                "right",
                105.42, 85.30, 108.59,
                new double[]{0.022861805, -0.999737930, 0.001186824},
                new double[]{-0.561813001, -0.013829363, -0.827148657},
                new double[]{0.826948299, 0.018243338, -0.561981931},
                592.60, 592.60, 630.53, 369.36, 1.009981, false, null
        ));
        return new SurroundCalibrationProfile(
                "银河 E5 内置校准",
                false,
                groundWidthCm,
                groundLengthCm,
                groundFrontExtensionCm,
                map
        );
    }

    /** 根据当前 AppConfig 加载有效校准档案（自定义 E5 JSON 损坏或不存在时自动回退银河 E5 内置校准）。 */
    @NonNull
    public static SurroundCalibrationProfile resolveActiveProfile(@NonNull AppConfig config) {
        int width = config.getSurroundGroundWidthCm();
        int length = config.getSurroundGroundLengthCm();
        int ext = config.getSurroundGroundFrontExtensionCm();
        if (AppConfig.SURROUND_CALIBRATION_E5_CUSTOM.equals(config.getSurroundCalibrationSource())) {
            String path = config.getSurroundCustomCalibrationPath();
            if (!path.isEmpty()) {
                File f = new File(path);
                if (f.isFile()) {
                    try {
                        String raw = readUtf8File(f);
                        return parseCalibrationJson(raw, width, length, ext);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return createBuiltInE5Profile(width, length, ext);
    }

    /**
     * 解析 E5 1.3 标准 `calibration.json` 文本并校验四路内外参。
     * JSON 中外参 `position` 单位为 m（若数值绝对值大于 20 则视为 cm）。
     */
    @NonNull
    public static SurroundCalibrationProfile parseCalibrationJson(@NonNull String jsonText,
                                                                  int groundWidthCm,
                                                                  int groundLengthCm,
                                                                  int groundFrontExtensionCm) {
        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(jsonText);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("JSON 格式无效: " + e.getMessage(), e);
        }
        if (rootElement == null || !rootElement.isJsonObject()) {
            throw new IllegalArgumentException("标定文件根节点必须为 JSON 对象");
        }
        JsonObject root = rootElement.getAsJsonObject();
        JsonObject camerasObj = optObject(root, "cameras");
        if (camerasObj == null) {
            throw new IllegalArgumentException("缺少 cameras 字段");
        }
        Map<String, CameraCalibration> parsed = new LinkedHashMap<>(4);
        String[][] mapping = {
                {"front", "front"},
                {"back", "rear"},
                {"left", "left"},
                {"right", "right"}
        };
        for (String[] pair : mapping) {
            String targetKey = pair[0];
            JsonObject camJson = optObject(camerasObj, targetKey);
            if (camJson == null) {
                camJson = optObject(camerasObj, pair[1]);
            }
            if (camJson == null) {
                throw new IllegalArgumentException("缺少相机标定项: " + targetKey);
            }
            double fx = optDouble(camJson, "fx", 318.0);
            double fy = optDouble(camJson, "fy", fx);
            double cx = optDouble(camJson, "cx", 640.0);
            double cy = optDouble(camJson, "cy", 400.0);
            if (!Double.isFinite(fx) || fx <= 0 || !Double.isFinite(fy) || fy <= 0) {
                throw new IllegalArgumentException("非法焦距: " + targetKey);
            }

            JsonArray posArr = optArray(camJson, "position");
            if (posArr == null || posArr.size() < 3) {
                throw new IllegalArgumentException("缺少 position: " + targetKey);
            }
            double px = readFiniteDouble(posArr, 0);
            double py = readFiniteDouble(posArr, 1);
            double pz = readFiniteDouble(posArr, 2);
            double scaleToCm = (Math.abs(px) <= 20.0 && Math.abs(py) <= 20.0 && Math.abs(pz) <= 20.0) ? 100.0 : 1.0;

            JsonArray rotArr = optArray(camJson, "world_to_camera_rotation");
            if (rotArr == null || rotArr.size() < 3) {
                throw new IllegalArgumentException("缺少 world_to_camera_rotation: " + targetKey);
            }
            double[] r0 = readVec3(rotArr, 0);
            double[] r1 = readVec3(rotArr, 1);
            double[] r2 = readVec3(rotArr, 2);

            JsonArray distArr = optArray(camJson, "distortion");
            double[] dist = new double[4];
            if (distArr != null) {
                for (int i = 0; i < Math.min(4, distArr.size()); i++) {
                    dist[i] = readFiniteDouble(distArr, i);
                }
            }
            parsed.put(targetKey, new CameraCalibration(
                    targetKey,
                    px * scaleToCm, py * scaleToCm, pz * scaleToCm,
                    r0, r1, r2, fx, fy, cx, cy, 0.0, true, dist));
        }

        String name = optString(root, "profile_name", "本地导入 E5 校准");
        return new SurroundCalibrationProfile(name, true, groundWidthCm, groundLengthCm, groundFrontExtensionCm, parsed);
    }

    /** 扫描目录下可用于导入的 E5 `calibration.json` 候选文件（含一层子目录）。 */
    @NonNull
    public static List<File> findCalibrationCandidates(@Nullable File searchRoot) {
        List<File> out = new ArrayList<>();
        if (searchRoot == null || !searchRoot.isDirectory()) {
            return out;
        }
        File[] children = searchRoot.listFiles();
        if (children == null) return out;
        for (File child : children) {
            if (child.isFile() && isCandidateCalibrationFile(child)) {
                out.add(child);
            } else if (child.isDirectory()) {
                File nested = new File(child, "calibration.json");
                if (nested.isFile()) {
                    out.add(nested);
                }
            }
        }
        return out;
    }

    private static boolean isCandidateCalibrationFile(@NonNull File f) {
        String name = f.getName().toLowerCase(Locale.US);
        return ("calibration.json".equals(name) || (name.endsWith(".json") && name.contains("calib")))
                && f.length() > 100 && f.length() <= MAX_CALIBRATION_FILE_BYTES;
    }

    /** 验证并复制本地 E5 `calibration.json` 至应用私有目录。 */
    @NonNull
    public static ImportResult importCalibrationFile(@NonNull File sourceFile, @NonNull File internalDestDir) {
        if (!sourceFile.isFile() || !sourceFile.canRead()) {
            return new ImportResult(false, "所选标定文件不存在或不可读", null);
        }
        if (sourceFile.length() <= 0 || sourceFile.length() > MAX_CALIBRATION_FILE_BYTES) {
            return new ImportResult(false, "标定文件大小超限（需 <= 2MB）", null);
        }
        try {
            String raw = readUtf8File(sourceFile);
            parseCalibrationJson(raw, 206, 530, 0);
            if (!internalDestDir.exists() && !internalDestDir.mkdirs()) {
                return new ImportResult(false, "无法创建本地标定目录", null);
            }
            File destFile = new File(internalDestDir, "custom_e5_calibration.json");
            try (FileOutputStream fos = new FileOutputStream(destFile)) {
                fos.write(raw.getBytes(StandardCharsets.UTF_8));
            }
            return new ImportResult(true, "已导入本地 E5 校准：" + sourceFile.getName(), destFile.getAbsolutePath());
        } catch (IllegalArgumentException e) {
            return new ImportResult(false, "E5 标定 JSON 校验失败：" + e.getMessage(), null);
        } catch (IOException e) {
            return new ImportResult(false, "读取/保存标定文件失败：" + e.getMessage(), null);
        }
    }

    @Nullable
    private static JsonObject optObject(@NonNull JsonObject obj, @NonNull String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }

    @Nullable
    private static JsonArray optArray(@NonNull JsonObject obj, @NonNull String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonArray()) ? el.getAsJsonArray() : null;
    }

    private static double optDouble(@NonNull JsonObject obj, @NonNull String key, double fallback) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return fallback;
        try {
            double v = el.getAsDouble();
            return Double.isFinite(v) ? v : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @NonNull
    private static String optString(@NonNull JsonObject obj, @NonNull String key, @NonNull String fallback) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        String value = el.getAsString();
        return value.trim().isEmpty() ? fallback : value.trim();
    }

    private static double readFiniteDouble(@NonNull JsonArray arr, int index) {
        JsonElement el = arr.get(index);
        if (el == null || !el.isJsonPrimitive()) {
            throw new IllegalArgumentException("数值元素无效: index=" + index);
        }
        double value = el.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("非有限浮点数: index=" + index);
        }
        return value;
    }

    private static double[] readVec3(@NonNull JsonArray matrix, int rowIndex) {
        JsonElement rowEl = matrix.get(rowIndex);
        if (rowEl == null || !rowEl.isJsonArray()) {
            throw new IllegalArgumentException("旋转矩阵行格式无效: row=" + rowIndex);
        }
        JsonArray arr = rowEl.getAsJsonArray();
        if (arr.size() < 3) {
            throw new IllegalArgumentException("旋转矩阵行长度不足 3");
        }
        return new double[]{readFiniteDouble(arr, 0), readFiniteDouble(arr, 1), readFiniteDouble(arr, 2)};
    }

    @NonNull
    private static String readUtf8File(@NonNull File f) throws IOException {
        if (f.length() > MAX_CALIBRATION_FILE_BYTES) {
            throw new IOException("File too large");
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int offset = 0;
            while (offset < buf.length) {
                int n = fis.read(buf, offset, buf.length - offset);
                if (n < 0) break;
                offset += n;
            }
            return new String(buf, 0, offset, StandardCharsets.UTF_8);
        }
    }
}
