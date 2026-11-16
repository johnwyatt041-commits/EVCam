package com.kooo.evcam.configplaza;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.camera.RecordingSoundPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 银河 E5 配置快照导出、应用前原子备份、局部应用与一键回滚协调器（纯 Java，支持 JVM 单测）。
 */
public final class E5ConfigApplier {

    public interface ConfigTarget {
        @NonNull
        String getCurrentCarModel();

        default boolean isE5CarModel() {
            String m = getCurrentCarModel();
            return AppConfig.CAR_MODEL_GALAXY_E5.equals(m) || AppConfig.CAR_MODEL_E5_MULTI.equals(m);
        }

        @NonNull
        Map<String, Object> readSectionValues(@NonNull E5ConfigSection section);

        void writeSectionValues(@NonNull E5ConfigSection section, @NonNull Map<String, Object> values);
    }

    public interface BackupStore {
        boolean saveBackup(@NonNull BackupRecord record);

        @Nullable
        BackupRecord loadBackup();

        boolean hasBackup();
    }

    public static final class BackupRecord {
        private static final Gson PRETTY_GSON = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();

        @NonNull
        public final String backupCreatedAt;
        @NonNull
        public final String appliedSourceName;
        @NonNull
        public final List<String> appliedSectionIds;
        @NonNull
        public final String previousCarModel;
        @NonNull
        public final E5ConfigSnapshot snapshot;

        public BackupRecord(
                @NonNull String backupCreatedAt,
                @NonNull String appliedSourceName,
                @NonNull List<String> appliedSectionIds,
                @NonNull String previousCarModel,
                @NonNull E5ConfigSnapshot snapshot) {
            this.backupCreatedAt = backupCreatedAt;
            this.appliedSourceName = appliedSourceName;
            this.appliedSectionIds = Collections.unmodifiableList(new ArrayList<>(appliedSectionIds));
            this.previousCarModel = previousCarModel;
            this.snapshot = snapshot;
        }

        @NonNull
        public String toJsonString() {
            JsonObject root = new JsonObject();
            root.addProperty("backupCreatedAt", backupCreatedAt);
            root.addProperty("appliedSourceName", appliedSourceName);
            root.addProperty("previousCarModel", previousCarModel);
            JsonArray arr = new JsonArray();
            for (String id : appliedSectionIds) {
                arr.add(id);
            }
            root.add("appliedSectionIds", arr);
            root.add("snapshot", snapshot.toJsonObject());
            return PRETTY_GSON.toJson(root);
        }

        @Nullable
        public static BackupRecord fromJsonString(@Nullable String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            try {
                JsonElement el = JsonParser.parseString(raw);
                if (!el.isJsonObject()) {
                    return null;
                }
                JsonObject obj = el.getAsJsonObject();
                if (!obj.has("snapshot") || !obj.get("snapshot").isJsonObject()) {
                    return null;
                }
                E5ConfigSchema.ValidationResult res =
                        E5ConfigSchema.validateJsonObject(obj.getAsJsonObject("snapshot"));
                if (!res.valid || res.snapshot == null) {
                    return null;
                }
                String createdAt = obj.has("backupCreatedAt") && obj.get("backupCreatedAt").isJsonPrimitive()
                        ? obj.get("backupCreatedAt").getAsString() : "";
                String sourceName = obj.has("appliedSourceName") && obj.get("appliedSourceName").isJsonPrimitive()
                        ? obj.get("appliedSourceName").getAsString() : "";
                String prevModel = obj.has("previousCarModel") && obj.get("previousCarModel").isJsonPrimitive()
                        ? obj.get("previousCarModel").getAsString() : "";
                List<String> secIds = new ArrayList<>();
                if (obj.has("appliedSectionIds") && obj.get("appliedSectionIds").isJsonArray()) {
                    for (JsonElement secEl : obj.getAsJsonArray("appliedSectionIds")) {
                        if (secEl != null && secEl.isJsonPrimitive()) {
                            secIds.add(secEl.getAsString());
                        }
                    }
                }
                return new BackupRecord(createdAt, sourceName, secIds, prevModel, res.snapshot);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    public static final class ExportResult {
        public final boolean success;
        @Nullable
        public final String errorMessage;
        @NonNull
        public final List<String> warnings;
        @Nullable
        public final E5ConfigSnapshot snapshot;

        private ExportResult(boolean success,
                             @Nullable String errorMessage,
                             @NonNull List<String> warnings,
                             @Nullable E5ConfigSnapshot snapshot) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.snapshot = snapshot;
        }

        public static ExportResult ok(@NonNull E5ConfigSnapshot snapshot, @NonNull List<String> warnings) {
            return new ExportResult(true, null, warnings, snapshot);
        }

        public static ExportResult fail(@NonNull String errorMessage) {
            return new ExportResult(false, errorMessage, Collections.emptyList(), null);
        }
    }

    public static final class ApplyResult {
        public final boolean success;
        @Nullable
        public final String errorMessage;
        @NonNull
        public final List<String> warnings;
        @NonNull
        public final Set<E5ConfigSection> appliedSections;
        public final boolean cameraMappingChanged;
        public final boolean themeChanged;
        public final boolean surroundChanged;
        public final boolean backupSaved;

        private ApplyResult(boolean success,
                            @Nullable String errorMessage,
                            @NonNull List<String> warnings,
                            @NonNull Set<E5ConfigSection> appliedSections,
                            boolean cameraMappingChanged,
                            boolean themeChanged,
                            boolean surroundChanged,
                            boolean backupSaved) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.appliedSections = Collections.unmodifiableSet(new LinkedHashSet<>(appliedSections));
            this.cameraMappingChanged = cameraMappingChanged;
            this.themeChanged = themeChanged;
            this.surroundChanged = surroundChanged;
            this.backupSaved = backupSaved;
        }

        public static ApplyResult ok(@NonNull Set<E5ConfigSection> appliedSections,
                                     @NonNull List<String> warnings,
                                     boolean cameraMappingChanged,
                                     boolean themeChanged,
                                     boolean surroundChanged,
                                     boolean backupSaved) {
            return new ApplyResult(true, null, warnings, appliedSections,
                    cameraMappingChanged, themeChanged, surroundChanged, backupSaved);
        }

        public static ApplyResult fail(@NonNull String errorMessage) {
            return new ApplyResult(false, errorMessage, Collections.emptyList(),
                    Collections.emptySet(), false, false, false, false);
        }
    }

    private static final List<E5ConfigSection> APPLY_ORDER = Arrays.asList(
            E5ConfigSection.CAMERA_MAPPING,
            E5ConfigSection.APPEARANCE,
            E5ConfigSection.RECORDING,
            E5ConfigSection.VEHICLE_RULES_SIGNALS,
            E5ConfigSection.BLIND_SPOT_HUD,
            E5ConfigSection.SURROUND_360
    );

    private final ConfigTarget target;
    private final BackupStore backupStore;

    public E5ConfigApplier(@NonNull ConfigTarget target, @NonNull BackupStore backupStore) {
        this.target = target;
        this.backupStore = backupStore;
    }

    @NonNull
    public ExportResult exportCurrent(
            @NonNull String id,
            @NonNull String name,
            @NonNull String description,
            @NonNull String authorNote,
            @NonNull String createdAt,
            @NonNull Set<E5ConfigSection> selectedSections) {
        if (selectedSections.isEmpty()) {
            return ExportResult.fail("请至少选择一个导出分区");
        }
        if (selectedSections.contains(E5ConfigSection.CAMERA_MAPPING) && !target.isE5CarModel()) {
            return ExportResult.fail("当前车型为「" + target.getCurrentCarModel()
                    + "」，配置广场仅支持导出银河 E5（galaxy_e5）配置");
        }

        Map<E5ConfigSection, E5ConfigSnapshot.SectionData> sections = new EnumMap<>(E5ConfigSection.class);
        List<String> warnings = new ArrayList<>();
        for (E5ConfigSection section : APPLY_ORDER) {
            if (!selectedSections.contains(section)) {
                continue;
            }
            Map<String, Object> raw = new LinkedHashMap<>(target.readSectionValues(section));
            normalizePortableValues(section, raw, warnings);
            E5ConfigSchema.ValidationResult secRes = E5ConfigSchema.validateSectionMap(section, raw);
            if (!secRes.valid || secRes.snapshot == null) {
                return ExportResult.fail(secRes.errorMessage != null ? secRes.errorMessage : "导出校验失败");
            }
            warnings.addAll(secRes.warnings);
            E5ConfigSnapshot.SectionData secData = secRes.snapshot.getSection(section);
            if (secData != null) {
                sections.put(section, secData);
            }
        }

        E5ConfigSnapshot candidate = new E5ConfigSnapshot(
                E5ConfigSection.SCHEMA_VERSION,
                E5ConfigSection.VEHICLE_MODEL_GALAXY_E5,
                id.trim().isEmpty() ? "e5_snapshot" : id.trim(),
                name.trim().isEmpty() ? "银河 E5 配置快照" : name.trim(),
                description.trim(),
                authorNote.trim(),
                createdAt,
                E5ConfigSnapshot.SOURCE_LOCAL,
                sections
        );
        E5ConfigSchema.ValidationResult finalRes = E5ConfigSchema.validateJsonObject(candidate.toJsonObject());
        if (!finalRes.valid || finalRes.snapshot == null) {
            return ExportResult.fail(finalRes.errorMessage != null ? finalRes.errorMessage : "导出快照校验失败");
        }
        return ExportResult.ok(finalRes.snapshot, warnings);
    }

    @NonNull
    public ApplyResult applySnapshot(
            @NonNull E5ConfigSnapshot rawSnapshot,
            @NonNull Set<E5ConfigSection> selectedSections,
            @NonNull String backupTimestamp) {
        if (selectedSections.isEmpty()) {
            return ApplyResult.fail("请至少选择一个要应用的分区");
        }

        E5ConfigSchema.ValidationResult validation =
                E5ConfigSchema.validateJsonObject(rawSnapshot.toJsonObject());
        if (!validation.valid || validation.snapshot == null) {
            return ApplyResult.fail(validation.errorMessage != null ? validation.errorMessage : "配置校验未通过");
        }
        E5ConfigSnapshot snapshot = validation.snapshot;
        for (E5ConfigSection sec : selectedSections) {
            if (snapshot.getSection(sec) == null) {
                return ApplyResult.fail("配置包不包含分区：" + sec.getLabel());
            }
        }

        // 先对即将改动的分区及当前 E5 基线做原子备份
        BackupRecord backup = buildBackupRecord(snapshot.name, selectedSections, backupTimestamp);
        if (backup == null || !backupStore.saveBackup(backup)) {
            return ApplyResult.fail("写入应用前备份失败，已中止本次覆盖以保护现有配置");
        }

        return writeSectionsInternal(snapshot, selectedSections, validation.warnings, true);
    }

    @NonNull
    public ApplyResult restoreLastBackup() {
        BackupRecord backup = backupStore.loadBackup();
        if (backup == null) {
            return ApplyResult.fail("未找到可恢复的配置备份");
        }
        Set<E5ConfigSection> toRestore = new LinkedHashSet<>();
        for (String id : backup.appliedSectionIds) {
            E5ConfigSection sec = E5ConfigSection.fromId(id);
            if (sec != null && backup.snapshot.getSection(sec) != null) {
                toRestore.add(sec);
            }
        }
        if (toRestore.isEmpty()) {
            toRestore.addAll(backup.snapshot.getSectionSet());
        }
        return writeSectionsInternal(backup.snapshot, toRestore, Collections.emptyList(), false);
    }

    @Nullable
    private BackupRecord buildBackupRecord(@NonNull String sourceName,
                                           @NonNull Set<E5ConfigSection> selectedSections,
                                           @NonNull String backupTimestamp) {
        String prevCarModel = target.getCurrentCarModel();
        Set<E5ConfigSection> backupSections = new LinkedHashSet<>(E5ConfigSection.allSections());
        if (!target.isE5CarModel()) {
            backupSections.remove(E5ConfigSection.CAMERA_MAPPING);
        }
        if (backupSections.isEmpty()) {
            return null;
        }
        ExportResult cur = exportCurrent(
                "backup_before_apply",
                "应用前自动备份",
                "应用「" + sourceName + "」前自动保存",
                "",
                backupTimestamp,
                backupSections
        );
        if (!cur.success || cur.snapshot == null) {
            return null;
        }
        List<String> appliedIds = new ArrayList<>();
        for (E5ConfigSection sec : APPLY_ORDER) {
            if (selectedSections.contains(sec) && backupSections.contains(sec)) {
                appliedIds.add(sec.getId());
            }
        }
        return new BackupRecord(backupTimestamp, sourceName, appliedIds, prevCarModel, cur.snapshot);
    }

    @NonNull
    private ApplyResult writeSectionsInternal(@NonNull E5ConfigSnapshot snapshot,
                                              @NonNull Set<E5ConfigSection> sectionsToApply,
                                              @NonNull List<String> baseWarnings,
                                              boolean backupSaved) {
        Map<String, Object> beforeCamera = target.readSectionValues(E5ConfigSection.CAMERA_MAPPING);
        Map<String, Object> beforeAppearance = target.readSectionValues(E5ConfigSection.APPEARANCE);

        Set<E5ConfigSection> applied = new LinkedHashSet<>();
        for (E5ConfigSection sec : APPLY_ORDER) {
            if (!sectionsToApply.contains(sec)) {
                continue;
            }
            E5ConfigSnapshot.SectionData secData = snapshot.getSection(sec);
            if (secData != null) {
                target.writeSectionValues(sec, secData.values);
                applied.add(sec);
            }
        }

        boolean cameraChanged = false;
        if (applied.contains(E5ConfigSection.CAMERA_MAPPING)) {
            E5ConfigSnapshot.SectionData camData = snapshot.getSection(E5ConfigSection.CAMERA_MAPPING);
            if (camData != null) {
                cameraChanged = hasAnyKeyChanged(beforeCamera, camData.values,
                        "car_model", "camera_front_id", "camera_back_id", "camera_left_id", "camera_right_id",
                        "camera_front_visible", "camera_back_visible", "camera_left_visible", "camera_right_visible");
            }
        }

        boolean themeChanged = false;
        if (applied.contains(E5ConfigSection.APPEARANCE)) {
            E5ConfigSnapshot.SectionData appData = snapshot.getSection(E5ConfigSection.APPEARANCE);
            if (appData != null) {
                themeChanged = hasAnyKeyChanged(beforeAppearance, appData.values, "theme_mode");
            }
        }

        boolean surroundChanged = applied.contains(E5ConfigSection.SURROUND_360);
        if (!surroundChanged && applied.contains(E5ConfigSection.APPEARANCE)) {
            E5ConfigSnapshot.SectionData appData = snapshot.getSection(E5ConfigSection.APPEARANCE);
            if (appData != null && hasAnyKeyChanged(beforeAppearance, appData.values, "preview_mode")) {
                surroundChanged = true;
            }
        }

        return ApplyResult.ok(applied, baseWarnings, cameraChanged, themeChanged, surroundChanged, backupSaved);
    }

    private static boolean hasAnyKeyChanged(@NonNull Map<String, Object> before,
                                            @NonNull Map<String, Object> after,
                                            @NonNull String... keys) {
        for (String key : keys) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                return true;
            }
        }
        return false;
    }

    private static void normalizePortableValues(@NonNull E5ConfigSection section,
                                              @NonNull Map<String, Object> raw,
                                              @NonNull List<String> warnings) {
        if (section != E5ConfigSection.RECORDING) {
            return;
        }
        for (String key : new String[]{"recording_start_sound_mode", "recording_stop_sound_mode"}) {
            Object val = raw.get(key);
            if (RecordingSoundPlayer.MODE_CUSTOM.equals(val)) {
                raw.put(key, RecordingSoundPlayer.MODE_SYSTEM);
                warnings.add("本地自定义提示音文件不随配置包导出，已自动切换为系统提示音");
            }
        }
    }
}
