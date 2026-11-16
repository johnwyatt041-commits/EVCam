package com.kooo.evcam.configplaza;

import android.content.Context;
import android.content.res.AssetManager;
import android.hardware.camera2.CameraManager;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.AtomicFile;

import com.kooo.evcam.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 银河 E5 离线配置广场仓库：管理内置 E5 预设资产、本机离线快照存储及 SAF 文件读写。
 */
public final class ConfigPlazaRepository {
    private static final String TAG = "ConfigPlazaRepo";
    private static final String ASSET_PRESETS_DIR = "config-plaza/e5-presets";
    private static final List<String> BUILTIN_PRESET_ORDER = Arrays.asList(
            "e5_balanced_daily.json",
            "e5_low_power_smooth.json",
            "e5_surround_reverse_enhanced.json"
    );
    private static final int MAX_IMPORT_BYTES = 512 * 1024;

    private final Context context;
    private final File snapshotsDir;
    private final AndroidE5ConfigTarget target;
    private final AndroidE5ConfigBackupStore backupStore;
    private final E5ConfigApplier applier;

    public ConfigPlazaRepository(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.snapshotsDir = new File(this.context.getFilesDir(), "config-plaza/snapshots");
        this.target = new AndroidE5ConfigTarget(this.context);
        this.backupStore = new AndroidE5ConfigBackupStore(this.context);
        this.applier = new E5ConfigApplier(this.target, this.backupStore);
    }

    @NonNull
    public E5ConfigApplier getApplier() {
        return applier;
    }

    @NonNull
    public AndroidE5ConfigTarget getTarget() {
        return target;
    }

    @Nullable
    public E5ConfigApplier.BackupRecord loadLastBackup() {
        return backupStore.loadBackup();
    }

    @NonNull
    public List<E5ConfigSnapshot> loadBuiltinPresets() {
        List<E5ConfigSnapshot> list = new ArrayList<>();
        AssetManager assets = context.getAssets();
        for (String fileName : BUILTIN_PRESET_ORDER) {
            try (InputStream is = assets.open(ASSET_PRESETS_DIR + "/" + fileName)) {
                String json = readStreamUtf8(is, MAX_IMPORT_BYTES);
                E5ConfigSchema.ValidationResult res = E5ConfigSchema.parseAndValidate(json);
                if (res.valid && res.snapshot != null) {
                    list.add(res.snapshot.withMetadata(
                            res.snapshot.id,
                            res.snapshot.name,
                            res.snapshot.description,
                            res.snapshot.authorNote,
                            res.snapshot.createdAt,
                            E5ConfigSnapshot.SOURCE_BUILTIN
                    ));
                } else {
                    AppLog.w(TAG, "内置预设校验失败 " + fileName + ": " + res.errorMessage);
                }
            } catch (Exception e) {
                AppLog.e(TAG, "读取内置 E5 预设失败: " + fileName, e);
            }
        }
        return list;
    }

    @NonNull
    public List<E5ConfigSnapshot> loadLocalSnapshots() {
        if (!snapshotsDir.exists() || !snapshotsDir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = snapshotsDir.listFiles((dir, name) -> name != null && name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<E5ConfigSnapshot> result = new ArrayList<>();
        for (File file : files) {
            AtomicFile atomic = new AtomicFile(file);
            try {
                String json = new String(atomic.readFully(), StandardCharsets.UTF_8);
                E5ConfigSchema.ValidationResult res = E5ConfigSchema.parseAndValidate(json);
                if (res.valid && res.snapshot != null) {
                    result.add(res.snapshot);
                }
            } catch (Exception e) {
                AppLog.w(TAG, "跳过损坏的本地配置快照 " + file.getName() + ": " + e.getMessage());
            }
        }
        result.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return result;
    }

    @NonNull
    public E5ConfigApplier.ExportResult saveCurrentAsSnapshot(
            @NonNull String name,
            @NonNull String description,
            @NonNull Set<E5ConfigSection> sections) {
        String id = "local_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String now = nowIso();
        E5ConfigApplier.ExportResult export = applier.exportCurrent(
                id,
                name,
                description,
                "银河 E5 本机快照",
                now,
                sections
        );
        if (!export.success || export.snapshot == null) {
            return export;
        }
        if (!writeLocalSnapshotFile(export.snapshot)) {
            return E5ConfigApplier.ExportResult.fail("保存本地快照文件失败");
        }
        return export;
    }

    public boolean saveSnapshotCopy(@NonNull E5ConfigSnapshot source, @NonNull String newName, @NonNull String sourceType) {
        String id = "local_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        E5ConfigSnapshot copy = source.withMetadata(
                id,
                newName.trim().isEmpty() ? source.name : newName.trim(),
                source.description,
                source.authorNote,
                nowIso(),
                sourceType
        );
        E5ConfigSchema.ValidationResult valid = E5ConfigSchema.validateJsonObject(copy.toJsonObject());
        if (!valid.valid || valid.snapshot == null) {
            return false;
        }
        return writeLocalSnapshotFile(valid.snapshot);
    }

    public boolean deleteLocalSnapshot(@NonNull String snapshotId) {
        File file = resolveSnapshotFile(snapshotId);
        return file != null && file.exists() && file.delete();
    }

    @NonNull
    public E5ConfigSchema.ValidationResult parseFromUri(@NonNull Uri uri) {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                return E5ConfigSchema.ValidationResult.fail("无法读取所选文件");
            }
            String raw = readStreamUtf8(is, MAX_IMPORT_BYTES);
            return E5ConfigSchema.parseAndValidate(raw);
        } catch (IllegalArgumentException tooLarge) {
            return E5ConfigSchema.ValidationResult.fail(tooLarge.getMessage() != null
                    ? tooLarge.getMessage() : "配置文件过大");
        } catch (Exception e) {
            AppLog.e(TAG, "读取外部 JSON 配置失败", e);
            return E5ConfigSchema.ValidationResult.fail("读取文件失败：" + e.getMessage());
        }
    }

    public boolean writeSnapshotToUri(@NonNull Uri uri, @NonNull E5ConfigSnapshot snapshot) {
        try (OutputStream os = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) {
                return false;
            }
            os.write(snapshot.toJsonString(true).getBytes(StandardCharsets.UTF_8));
            os.flush();
            return true;
        } catch (Exception e) {
            AppLog.e(TAG, "导出配置到文件失败", e);
            return false;
        }
    }

    @NonNull
    public List<String> checkCameraCompatibility(@NonNull E5ConfigSnapshot snapshot) {
        E5ConfigSnapshot.SectionData camData = snapshot.getSection(E5ConfigSection.CAMERA_MAPPING);
        if (camData == null) {
            return Collections.emptyList();
        }
        Set<String> availableIds = queryLocalCameraIds();
        if (availableIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> warnings = new ArrayList<>();
        Map<String, Object> v = camData.values;
        for (String[] pair : new String[][]{
                {"front", "前视"}, {"back", "后视"}, {"left", "左视"}, {"right", "右视"}
        }) {
            Object rawId = v.get("camera_" + pair[0] + "_id");
            if (rawId != null) {
                String id = String.valueOf(rawId).trim();
                if (!id.isEmpty() && !availableIds.contains(id)) {
                    warnings.add(pair[1] + "摄像头 ID「" + id + "」当前未在本机 Camera2 列表检测到");
                }
            }
        }
        return warnings;
    }

    @NonNull
    public static String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private boolean writeLocalSnapshotFile(@NonNull E5ConfigSnapshot snapshot) {
        if (!snapshotsDir.exists() && !snapshotsDir.mkdirs()) {
            return false;
        }
        File file = resolveSnapshotFile(snapshot.id);
        if (file == null) {
            return false;
        }
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream fos = null;
        try {
            fos = atomic.startWrite();
            fos.write(snapshot.toJsonString(true).getBytes(StandardCharsets.UTF_8));
            fos.flush();
            atomic.finishWrite(fos);
            return true;
        } catch (Exception e) {
            AppLog.e(TAG, "写入本地快照失败", e);
            if (fos != null) {
                atomic.failWrite(fos);
            }
            return false;
        }
    }

    @Nullable
    private File resolveSnapshotFile(@NonNull String id) {
        String safe = id.replaceAll("[^a-zA-Z0-9_-]", "");
        if (safe.isEmpty()) {
            return null;
        }
        return new File(snapshotsDir, safe + ".json");
    }

    @NonNull
    private Set<String> queryLocalCameraIds() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return Collections.emptySet();
            }
            String[] ids = manager.getCameraIdList();
            if (ids == null || ids.length == 0) {
                return Collections.emptySet();
            }
            return new HashSet<>(Arrays.asList(ids));
        } catch (Exception ignored) {
            return Collections.emptySet();
        }
    }

    @NonNull
    private static String readStreamUtf8(@NonNull InputStream is, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IllegalArgumentException("配置文件超过 512KB 上限，已拒绝导入");
            }
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
