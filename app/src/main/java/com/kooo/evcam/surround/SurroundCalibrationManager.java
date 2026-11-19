package com.kooo.evcam.surround;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.surround.SurroundCalibrationProfile.CameraCalibration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 银河 E5 360 标定档案工厂与本地 E5 标定 JSON/ZIP 校验导入器（完全离线，无领克 08、无云端依赖）。
 * 默认内置标定采用星舰7（avm_calib_res）四路 OpenCV 鱼眼多项式实测标定参数与网格。
 */
public final class SurroundCalibrationManager {

    private static final long MAX_CALIBRATION_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_CALIBRATION_ZIP_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_MESH_FILE_BYTES = 4L * 1024L * 1024L;

    public static final String CUSTOM_CALIBRATION_FILE_NAME = "custom_e5_calibration.json";
    public static final String CUSTOM_BIRD_MESH_FILE_NAME = "custom_bird.mesh";
    public static final String CUSTOM_FRONT_WHEELS_MESH_FILE_NAME = "custom_front_wheels.mesh";
    public static final String CUSTOM_REAR_WHEELS_MESH_FILE_NAME = "custom_rear_wheels.mesh";

    private static final double[] BUILTIN_DISTORTION = {
            0.129996832562922,
            -0.041928332298147,
            0.014005468157064,
            -0.002599688315656
    };

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

    public static final class ExportResult {
        public final boolean success;
        @NonNull
        public final String message;
        @Nullable
        public final String exportedPath;

        public ExportResult(boolean success, @NonNull String message, @Nullable String exportedPath) {
            this.success = success;
            this.message = message;
            this.exportedPath = exportedPath;
        }
    }

    private SurroundCalibrationManager() {
    }

    /** 构造默认内置标定档案（采用星舰7 avm_calib_res 四路 OpenCV 鱼眼多项式标定参数，以车体中心为原点）。 */
    @NonNull
    public static SurroundCalibrationProfile createBuiltInE5Profile(int groundWidthCm,
                                                                     int groundLengthCm,
                                                                     int groundFrontExtensionCm) {
        double yShiftCm = -SurroundCalibrationProfile.DEFAULT_BIRD_CENTER_OFFSET_Y_CM; // +143.0 cm
        Map<String, CameraCalibration> map = new LinkedHashMap<>(4);
        map.put("front", new CameraCalibration(
                "front",
                -0.004356377853284801 * 100.0,
                0.8067030902515822 * 100.0 + yShiftCm,
                0.6552500377332486 * 100.0,
                new double[]{0.9999225522745963, 0.01244366610333551, -0.00021125000828116445},
                new double[]{0.005040253643994054, -0.42041741779638486, -0.9073168083181442},
                new double[]{-0.011379160595646087, 0.9072454739414955, -0.42044757665707844},
                318.0, 318.0, 640.0, 400.0, 0.0, true, BUILTIN_DISTORTION
        ));
        map.put("back", new CameraCalibration(
                "back",
                0.06420808076605605 * 100.0,
                -3.6549453245001606 * 100.0 + yShiftCm,
                0.9919933182890428 * 100.0,
                new double[]{-0.9999512310686646, -0.007414019843584837, 0.006524399897390958},
                new double[]{-0.009211257147569272, 0.461871397531451, -0.8868990725466488},
                new double[]{0.0035620536244558255, -0.8869159173518668, -0.46191716500025526},
                318.0, 318.0, 640.0, 400.0, 0.0, true, BUILTIN_DISTORTION
        ));
        map.put("left", new CameraCalibration(
                "left",
                -1.0325600262845192 * 100.0,
                -0.9257069587489 * 100.0 + yShiftCm,
                1.0831671161062215 * 100.0,
                new double[]{-0.004761879637051547, 0.9997887846367982, -0.01999276411095563},
                new double[]{0.7454485925128783, -0.009777334192937692, -0.6664914100396557},
                new double[]{-0.6665461127505666, -0.018077329740691594, -0.7452445838290643},
                318.0, 318.0, 640.0, 400.0, 0.0, true, BUILTIN_DISTORTION
        ));
        map.put("right", new CameraCalibration(
                "right",
                1.015017647542593 * 100.0,
                -0.9552939005459508 * 100.0 + yShiftCm,
                1.0816068864978858 * 100.0,
                new double[]{-0.006856468853798758, -0.9999648660297112, -0.004822399925212606},
                new double[]{-0.7019393136006203, 0.008247575011013697, -0.7121890040770974},
                new double[]{0.7122037551549044, -0.0014980696310612496, -0.701971200926814},
                318.0, 318.0, 640.0, 400.0, 0.0, true, BUILTIN_DISTORTION
        ));
        return new SurroundCalibrationProfile(
                "银河 E5 内置校准",
                false,
                true,
                groundWidthCm,
                groundLengthCm,
                groundFrontExtensionCm,
                map
        );
    }

    /** 根据当前 AppConfig 解析基础标定档案（未叠加实时外参微调；自定义损坏或不存在时自动回退内置）。 */
    @NonNull
    public static SurroundCalibrationProfile resolveActiveBaseProfile(@NonNull AppConfig config) {
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

    /** 根据当前 AppConfig 加载有效校准档案，并按实际生效方案槽位叠加四路外参实时微调。 */
    @NonNull
    public static SurroundCalibrationProfile resolveActiveProfile(@NonNull AppConfig config) {
        SurroundCalibrationProfile base = resolveActiveBaseProfile(config);
        String effectiveSlot = base.isCustomCalibration
                ? AppConfig.SURROUND_CALIBRATION_E5_CUSTOM
                : AppConfig.SURROUND_CALIBRATION_E5_BUILTIN;
        return config.getSurroundExtrinsicAdjustmentForSlot(effectiveSlot).applyToProfile(base);
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

        // 若标定坐标未以车体中心为 Y 原点（例如原始坐标前后相机中点约 -142.4cm），自动平移对齐车体中心；
        // 若 JSON 已显式声明 vehicle_centered=true（例如用户微调后导出的档案），则严格保留原始外参坐标不再二次平移。
        boolean explicitVehicleCentered = optBoolean(root, "vehicle_centered", false);
        CameraCalibration frontCal = parsed.get("front");
        CameraCalibration backCal = parsed.get("back");
        if (!explicitVehicleCentered && frontCal != null && backCal != null) {
            double midYCm = (frontCal.posYCm + backCal.posYCm) * 0.5;
            if (Math.abs(midYCm) > 25.0) {
                Map<String, CameraCalibration> recentered = new LinkedHashMap<>(4);
                for (Map.Entry<String, CameraCalibration> entry : parsed.entrySet()) {
                    CameraCalibration c = entry.getValue();
                    recentered.put(entry.getKey(), new CameraCalibration(
                            c.position,
                            c.posXCm, c.posYCm - midYCm, c.posZCm,
                            c.r0, c.r1, c.r2,
                            c.fx, c.fy, c.cx, c.cy,
                            c.xi, c.usePolynomialDistortion, c.distortion));
                }
                parsed = recentered;
            }
        }

        String name = optString(root, "profile_name", "本地导入 E5 校准");
        return new SurroundCalibrationProfile(name, true, false,
                groundWidthCm, groundLengthCm, groundFrontExtensionCm, parsed);
    }

    /** 将生效中的 360 标定档案（含已应用的外参微调）序列化为标准 1.3 `calibration.json` 文本。 */
    @NonNull
    public static String exportCalibrationJson(@NonNull SurroundCalibrationProfile profile) {
        JsonObject root = new JsonObject();
        String exportName = profile.hasExtrinsicAdjustments
                ? profile.profileName + "（已微调）"
                : profile.profileName;
        root.addProperty("profile_name", exportName);
        root.addProperty("provenance", profile.hasExtrinsicAdjustments
                ? "EVCam 4.3 tuned surround calibration"
                : "EVCam exported surround calibration");
        JsonArray fileVer = new JsonArray();
        fileVer.add(1);
        fileVer.add(3);
        root.add("file_version", fileVer);
        JsonArray imageSize = new JsonArray();
        imageSize.add(SurroundCalibrationProfile.IMAGE_WIDTH);
        imageSize.add(SurroundCalibrationProfile.IMAGE_HEIGHT);
        root.add("image_size", imageSize);
        root.addProperty("lens_model", "opencv_fisheye_theta_polynomial");
        root.addProperty("vehicle_centered", true);

        JsonObject camerasObj = new JsonObject();
        String[][] exportKeys = {
                {"front", "front"},
                {"back", "rear"},
                {"left", "left"},
                {"right", "right"}
        };
        for (String[] pair : exportKeys) {
            CameraCalibration cam = profile.getCamera(pair[0]);
            if (cam == null) continue;
            JsonObject camObj = new JsonObject();
            camObj.addProperty("fx", cam.fx);
            camObj.addProperty("fy", cam.fy);
            camObj.addProperty("cx", cam.cx);
            camObj.addProperty("cy", cam.cy);

            JsonArray distArr = new JsonArray();
            for (int i = 0; i < 4; i++) {
                double d = (i < cam.distortion.length && Double.isFinite(cam.distortion[i])) ? cam.distortion[i] : 0.0;
                distArr.add(d);
            }
            camObj.add("distortion", distArr);

            JsonArray posArr = new JsonArray();
            posArr.add(cam.posXCm / 100.0);
            posArr.add(cam.posYCm / 100.0);
            posArr.add(cam.posZCm / 100.0);
            camObj.add("position", posArr);

            JsonArray rotArr = new JsonArray();
            rotArr.add(toJsonRow(cam.r0));
            rotArr.add(toJsonRow(cam.r1));
            rotArr.add(toJsonRow(cam.r2));
            camObj.add("world_to_camera_rotation", rotArr);

            camerasObj.add(pair[1], camObj);
        }
        root.add("cameras", camerasObj);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(root) + "\n";
    }

    @NonNull
    public static ExportResult exportCalibrationToFile(@NonNull File destFile,
                                                       @NonNull SurroundCalibrationProfile profile) {
        try {
            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return new ExportResult(false, "无法创建导出目录：" + parent.getAbsolutePath(), null);
            }
            byte[] bytes = exportCalibrationJson(profile).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(destFile)) {
                fos.write(bytes);
                fos.flush();
            }
            return new ExportResult(true, "已导出校准文件：" + destFile.getAbsolutePath(),
                    destFile.getAbsolutePath());
        } catch (IOException e) {
            return new ExportResult(false, "导出校准文件失败：" + e.getMessage(), null);
        }
    }

    @NonNull
    public static ExportResult exportCalibrationToUri(@NonNull Context context,
                                                      @NonNull Uri destUri,
                                                      @NonNull SurroundCalibrationProfile profile) {
        ContentResolver resolver = context.getContentResolver();
        String displayName = queryDisplayName(resolver, destUri);
        try (OutputStream out = resolver.openOutputStream(destUri, "wt")) {
            if (out == null) {
                return new ExportResult(false, "无法写入所选导出位置", null);
            }
            out.write(exportCalibrationJson(profile).getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new ExportResult(true, "已导出校准文件：" + displayName, destUri.toString());
        } catch (IOException | SecurityException e) {
            return new ExportResult(false, "导出校准文件失败：" + e.getMessage(), null);
        }
    }

    /** 扫描目录下可用于导入的 E5 `calibration.json` 或标定 `.zip` 候选文件（含一层子目录）。 */
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
                File nestedJson = new File(child, "calibration.json");
                if (nestedJson.isFile() && isCandidateCalibrationFile(nestedJson)) {
                    out.add(nestedJson);
                }
                File[] grandChildren = child.listFiles();
                if (grandChildren != null) {
                    for (File gc : grandChildren) {
                        if (gc.isFile() && gc.getName().toLowerCase(Locale.US).endsWith(".zip")
                                && isCandidateCalibrationFile(gc)) {
                            out.add(gc);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static boolean isCandidateCalibrationFile(@NonNull File f) {
        String name = f.getName().toLowerCase(Locale.US);
        if (name.endsWith(".zip")) {
            return (name.contains("calib") || name.contains("avm") || name.contains("星舰"))
                    && f.length() > 100 && f.length() <= MAX_CALIBRATION_ZIP_BYTES;
        }
        return ("calibration.json".equals(name) || (name.endsWith(".json") && name.contains("calib")))
                && f.length() > 100 && f.length() <= MAX_CALIBRATION_FILE_BYTES;
    }

    /** 验证并复制本地 E5 `calibration.json` 或标定 `.zip` 至应用私有目录。 */
    @NonNull
    public static ImportResult importCalibrationFile(@NonNull File sourceFile, @NonNull File internalDestDir) {
        if (!sourceFile.isFile() || !sourceFile.canRead()) {
            return new ImportResult(false, "所选标定文件不存在或不可读", null);
        }
        long maxAllowed = sourceFile.getName().toLowerCase(Locale.US).endsWith(".zip")
                ? MAX_CALIBRATION_ZIP_BYTES
                : MAX_CALIBRATION_FILE_BYTES;
        if (sourceFile.length() <= 0 || sourceFile.length() > maxAllowed) {
            return new ImportResult(false, "标定文件大小超限", null);
        }
        try (FileInputStream fis = new FileInputStream(sourceFile)) {
            byte[] bytes = readBoundedBytes(fis, maxAllowed);
            if (bytes == null) {
                return new ImportResult(false, "标定文件大小超限", null);
            }
            return importCalibrationBytes(bytes, sourceFile.getName(), internalDestDir);
        } catch (IOException e) {
            return new ImportResult(false, "读取/保存标定文件失败：" + e.getMessage(), null);
        }
    }

    /** 通过系统文件选择器 (SAF Uri) 验证并导入本地 E5 `calibration.json` 或标定 `.zip`。 */
    @NonNull
    public static ImportResult importCalibrationUri(@NonNull Context context,
                                                    @NonNull Uri sourceUri,
                                                    @NonNull File internalDestDir) {
        ContentResolver resolver = context.getContentResolver();
        String displayName = queryDisplayName(resolver, sourceUri);
        try (InputStream in = resolver.openInputStream(sourceUri)) {
            if (in == null) {
                return new ImportResult(false, "无法打开所选标定文件", null);
            }
            byte[] bytes = readBoundedBytes(in, MAX_CALIBRATION_ZIP_BYTES);
            if (bytes == null) {
                return new ImportResult(false, "标定文件大小超限（需 <= 8MB）", null);
            }
            return importCalibrationBytes(bytes, displayName, internalDestDir);
        } catch (IOException e) {
            return new ImportResult(false, "读取/保存标定文件失败：" + e.getMessage(), null);
        }
    }

    @NonNull
    private static ImportResult importCalibrationBytes(@NonNull byte[] bytes,
                                                       @NonNull String displayName,
                                                       @NonNull File internalDestDir) {
        if (isZipHeader(bytes)) {
            return importCalibrationZipBytes(bytes, displayName, internalDestDir);
        }
        if (bytes.length > MAX_CALIBRATION_FILE_BYTES) {
            return new ImportResult(false, "标定 JSON 文件大小超限（需 <= 2MB）", null);
        }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        return importCalibrationRaw(raw, displayName, internalDestDir, null, null, null);
    }

    @NonNull
    private static ImportResult importCalibrationZipBytes(@NonNull byte[] zipBytes,
                                                          @NonNull String displayName,
                                                          @NonNull File internalDestDir) {
        String calibRaw = null;
        byte[] birdMesh = null;
        byte[] frontWheelsMesh = null;
        byte[] rearWheelsMesh = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (entryName.contains("..")) {
                    continue;
                }
                String baseName = new File(entryName).getName().toLowerCase(Locale.US);
                if ("calibration.json".equals(baseName)) {
                    byte[] jsonBytes = readBoundedBytes(zis, MAX_CALIBRATION_FILE_BYTES);
                    if (jsonBytes != null) {
                        calibRaw = new String(jsonBytes, StandardCharsets.UTF_8);
                    }
                } else if ("bird.mesh".equals(baseName)) {
                    birdMesh = readBoundedBytes(zis, MAX_MESH_FILE_BYTES);
                } else if ("front_wheels.mesh".equals(baseName)) {
                    frontWheelsMesh = readBoundedBytes(zis, MAX_MESH_FILE_BYTES);
                } else if ("rear_wheels.mesh".equals(baseName)) {
                    rearWheelsMesh = readBoundedBytes(zis, MAX_MESH_FILE_BYTES);
                }
            }
        } catch (IOException e) {
            return new ImportResult(false, "解析标定压缩包失败：" + e.getMessage(), null);
        }
        if (calibRaw == null) {
            return new ImportResult(false, "压缩包内未找到 calibration.json", null);
        }
        return importCalibrationRaw(calibRaw, displayName, internalDestDir, birdMesh, frontWheelsMesh, rearWheelsMesh);
    }

    @NonNull
    private static ImportResult importCalibrationRaw(@NonNull String raw,
                                                     @NonNull String displayName,
                                                     @NonNull File internalDestDir,
                                                     @Nullable byte[] birdMesh,
                                                     @Nullable byte[] frontWheelsMesh,
                                                     @Nullable byte[] rearWheelsMesh) {
        try {
            parseCalibrationJson(raw, 206, 530, 0);
            if (!internalDestDir.exists() && !internalDestDir.mkdirs()) {
                return new ImportResult(false, "无法创建本地标定目录", null);
            }
            File destFile = new File(internalDestDir, CUSTOM_CALIBRATION_FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(destFile)) {
                fos.write(raw.getBytes(StandardCharsets.UTF_8));
            }
            writeOrDeleteMeshFile(new File(internalDestDir, CUSTOM_BIRD_MESH_FILE_NAME), birdMesh);
            writeOrDeleteMeshFile(new File(internalDestDir, CUSTOM_FRONT_WHEELS_MESH_FILE_NAME), frontWheelsMesh);
            writeOrDeleteMeshFile(new File(internalDestDir, CUSTOM_REAR_WHEELS_MESH_FILE_NAME), rearWheelsMesh);
            return new ImportResult(true, "已导入本地 E5 校准：" + displayName, destFile.getAbsolutePath());
        } catch (IllegalArgumentException e) {
            return new ImportResult(false, "E5 标定 JSON 校验失败：" + e.getMessage(), null);
        } catch (IOException e) {
            return new ImportResult(false, "读取/保存标定文件失败：" + e.getMessage(), null);
        }
    }

    private static void writeOrDeleteMeshFile(@NonNull File dest, @Nullable byte[] data) throws IOException {
        if (data != null && data.length >= 32 && data.length % 32 == 0) {
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(data);
            }
        } else if (dest.exists()) {
            // 防止旧压缩包遗留网格与新导入 JSON 混用
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
        }
    }

    private static boolean isZipHeader(@NonNull byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 'P' && bytes[1] == 'K'
                && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7)
                && (bytes[3] == 4 || bytes[3] == 6 || bytes[3] == 8);
    }

    @Nullable
    private static byte[] readBoundedBytes(@NonNull InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0L;
        int n;
        while ((n = in.read(chunk)) > 0) {
            total += n;
            if (total > maxBytes) {
                return null;
            }
            buffer.write(chunk, 0, n);
        }
        return total > 0L ? buffer.toByteArray() : null;
    }

    @NonNull
    private static String queryDisplayName(@NonNull ContentResolver resolver, @NonNull Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String segment = uri.getLastPathSegment();
        return (segment != null && !segment.trim().isEmpty()) ? segment.trim() : "calibration.json";
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

    private static boolean optBoolean(@NonNull JsonObject obj, @NonNull String key, boolean fallback) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        JsonPrimitive prim = el.getAsJsonPrimitive();
        if (prim.isBoolean()) return prim.getAsBoolean();
        return fallback;
    }

    @NonNull
    private static JsonArray toJsonRow(@NonNull double[] row) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < 3; i++) {
            double v = (i < row.length && Double.isFinite(row[i])) ? row[i] : 0.0;
            arr.add(v);
        }
        return arr;
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
