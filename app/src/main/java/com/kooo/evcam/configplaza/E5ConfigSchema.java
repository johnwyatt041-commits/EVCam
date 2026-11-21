package com.kooo.evcam.configplaza;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.camera.ImageAdjustmentConfig;
import com.kooo.evcam.camera.RecordingSoundPlayer;
import com.kooo.evcam.camera.VehicleStatusWatermark;
import com.kooo.evcam.surround.SurroundViewMode;
import com.kooo.evcam.ui.PreviewLayoutModel;
import com.kooo.evcam.ui.PreviewPresentation;
import com.kooo.evcam.ui.UiThemeController;
import com.kooo.evcam.vehicle.GearShiftPopupCoordinator;
import com.kooo.evcam.vehicle.rule.ConditionLogic;
import com.kooo.evcam.vehicle.rule.RuleAction;
import com.kooo.evcam.vehicle.rule.RuleComparison;
import com.kooo.evcam.vehicle.rule.VehicleRuleSignal;
import com.kooo.evcam.vehicle.rule.VehicleTriggerRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 银河 E5 离线配置白名单 Schema 与防错配校验器（对照 SC Lab0/Lya0/Lza0 思想重写为 E5 专属本地校验）。
 * 严格拒绝：非 1 版本、非银河 E5 车型、未知分区（如 rotary_controls）、未知/敏感字段、越界数值与畸形布局/规则。
 */
public final class E5ConfigSchema {

    public enum FieldType {
        BOOLEAN,
        INT,
        FLOAT,
        STRING,
        RULES_JSON,
        FLOAT_ARRAY_16,
        BOOL_ARRAY_4
    }

    public static final class FieldSpec {
        @NonNull
        public final String key;
        @NonNull
        public final FieldType type;
        @NonNull
        public final Object defaultValue;
        @Nullable
        final Validator validator;

        FieldSpec(@NonNull String key, @NonNull FieldType type, @NonNull Object defaultValue, @Nullable Validator validator) {
            this.key = key;
            this.type = type;
            this.defaultValue = defaultValue;
            this.validator = validator;
        }
    }

    public static final class ValidationResult {
        public final boolean valid;
        @Nullable
        public final String errorMessage;
        @NonNull
        public final List<String> warnings;
        @Nullable
        public final E5ConfigSnapshot snapshot;

        private ValidationResult(boolean valid,
                                 @Nullable String errorMessage,
                                 @NonNull List<String> warnings,
                                 @Nullable E5ConfigSnapshot snapshot) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.snapshot = snapshot;
        }

        @NonNull
        public static ValidationResult ok(@NonNull E5ConfigSnapshot snapshot, @NonNull List<String> warnings) {
            return new ValidationResult(true, null, warnings, snapshot);
        }

        @NonNull
        public static ValidationResult fail(@NonNull String errorMessage) {
            return new ValidationResult(false, errorMessage, Collections.emptyList(), null);
        }
    }

    private interface Validator {
        @Nullable
        String validate(@NonNull Object value);
    }

    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("^(\\d{3,4})x(\\d{3,4})$");
    private static final String[] CAMERA_POSITIONS = {"front", "back", "left", "right"};
    private static final String[] MIRROR_TARGET_KEYS = {"main", "blind_spot", "secondary", "stream"};

    private static final Map<E5ConfigSection, Map<String, FieldSpec>> SECTION_FIELDS = buildSchema();

    private E5ConfigSchema() {
    }

    @NonNull
    public static Map<String, FieldSpec> getFields(@NonNull E5ConfigSection section) {
        Map<String, FieldSpec> map = SECTION_FIELDS.get(section);
        return map != null ? Collections.unmodifiableMap(map) : Collections.emptyMap();
    }

    @NonNull
    public static ValidationResult parseAndValidate(@Nullable String rawJson) {
        JsonElement rootEl = E5ConfigSnapshot.parseJsonElement(rawJson);
        if (rootEl == null || !rootEl.isJsonObject()) {
            return ValidationResult.fail("配置包不是有效的 JSON 对象");
        }
        return validateJsonObject(rootEl.getAsJsonObject());
    }

    @NonNull
    public static ValidationResult validateJsonObject(@NonNull JsonObject root) {
        if (!root.has("schemaVersion") || !isWholeInt(root.get("schemaVersion"))) {
            return ValidationResult.fail("缺少或非法的 schemaVersion");
        }
        int schemaVersion = root.get("schemaVersion").getAsInt();
        if (schemaVersion != E5ConfigSection.SCHEMA_VERSION) {
            return ValidationResult.fail("配置包版本不受支持（仅支持 schemaVersion=1，收到 " + schemaVersion + "）");
        }

        String vehicleModel = readOptionalString(root, "vehicleModel", "");
        if (!E5ConfigSection.VEHICLE_MODEL_GALAXY_E5.equals(vehicleModel)) {
            String actual = vehicleModel.isEmpty() ? "未注明车型" : vehicleModel;
            return ValidationResult.fail("仅支持银河 E5（galaxy_e5）配置，已拒绝：" + actual);
        }

        if (!root.has("sections") || !root.get("sections").isJsonObject()) {
            return ValidationResult.fail("配置包缺少有效的 sections 分区对象");
        }
        JsonObject sectionsObj = root.getAsJsonObject("sections");
        if (sectionsObj.size() == 0) {
            return ValidationResult.fail("请至少包含一个配置分区");
        }

        Map<E5ConfigSection, E5ConfigSnapshot.SectionData> parsedSections = new EnumMap<>(E5ConfigSection.class);
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : sectionsObj.entrySet()) {
            String sectionId = entry.getKey();
            E5ConfigSection section = E5ConfigSection.fromId(sectionId);
            if (section == null) {
                return ValidationResult.fail("包含不受支持的分区：" + sectionId);
            }
            if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
                return ValidationResult.fail("分区 " + section.getLabel() + " 格式错误");
            }
            JsonObject secObj = entry.getValue().getAsJsonObject();
            if (!secObj.has("version") || !isWholeInt(secObj.get("version"))
                    || secObj.get("version").getAsInt() != section.getVersion()) {
                return ValidationResult.fail("分区 " + section.getLabel() + " 版本不受支持");
            }
            if (!secObj.has("values") || !secObj.get("values").isJsonObject()) {
                return ValidationResult.fail("分区 " + section.getLabel() + " 缺少 values 字段");
            }
            JsonObject valuesObj = secObj.getAsJsonObject("values");
            if (valuesObj.size() == 0) {
                return ValidationResult.fail("分区 " + section.getLabel() + " 的 values 不能为空");
            }

            ValidationResult secRes = validateSectionValues(section, valuesObj, warnings);
            if (!secRes.valid || secRes.snapshot == null) {
                return ValidationResult.fail(secRes.errorMessage != null ? secRes.errorMessage : "分区校验失败");
            }
            E5ConfigSnapshot.SectionData data = secRes.snapshot.getSection(section);
            if (data != null) {
                parsedSections.put(section, data);
            }
        }

        String id = sanitizeMeta(readOptionalString(root, "id", ""), "snapshot_imported", 64);
        String name = sanitizeMeta(readOptionalString(root, "name", ""), "导入的银河 E5 配置", 48);
        String description = sanitizeMeta(readOptionalString(root, "description", ""), "", 160);
        String authorNote = sanitizeMeta(readOptionalString(root, "authorNote", ""), "", 64);
        String createdAt = sanitizeMeta(readOptionalString(root, "createdAt", ""), "", 40);
        String sourceType = E5ConfigSnapshot.normalizeSourceType(readOptionalString(root, "sourceType", E5ConfigSnapshot.SOURCE_IMPORTED));

        E5ConfigSnapshot snapshot = new E5ConfigSnapshot(
                E5ConfigSection.SCHEMA_VERSION,
                E5ConfigSection.VEHICLE_MODEL_GALAXY_E5,
                id,
                name,
                description,
                authorNote,
                createdAt,
                sourceType,
                parsedSections
        );
        return ValidationResult.ok(snapshot, warnings);
    }

    @NonNull
    public static ValidationResult validateSectionMap(@NonNull E5ConfigSection section,
                                                      @NonNull Map<String, Object> rawValues) {
        JsonObject valuesObj = new JsonObject();
        for (Map.Entry<String, Object> entry : rawValues.entrySet()) {
            putObjectToJson(valuesObj, entry.getKey(), entry.getValue());
        }
        List<String> warnings = new ArrayList<>();
        return validateSectionValues(section, valuesObj, warnings);
    }

    private static ValidationResult validateSectionValues(@NonNull E5ConfigSection section,
                                                          @NonNull JsonObject valuesObj,
                                                          @NonNull List<String> warnings) {
        Map<String, FieldSpec> whitelist = getFields(section);
        for (String key : valuesObj.keySet()) {
            if (!whitelist.containsKey(key)) {
                return ValidationResult.fail("分区「" + section.getLabel() + "」包含未知或受限字段：" + key);
            }
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (FieldSpec spec : whitelist.values()) {
            if (!valuesObj.has(spec.key) || valuesObj.get(spec.key).isJsonNull()) {
                normalized.put(spec.key, copyDefault(spec.defaultValue));
                continue;
            }
            JsonElement raw = valuesObj.get(spec.key);
            Object parsed = parseTypedField(spec, raw);
            if (parsed == null) {
                return ValidationResult.fail("字段「" + spec.key + "」类型或数值格式非法");
            }
            if (spec.validator != null) {
                String err = spec.validator.validate(parsed);
                if (err != null) {
                    return ValidationResult.fail("字段「" + spec.key + "」" + err);
                }
            }
            normalized.put(spec.key, parsed);
        }

        if (section == E5ConfigSection.RECORDING) {
            boolean hasNewIndependent = valuesObj.has("watermark_gear_enabled")
                    || valuesObj.has("watermark_turn_signal_enabled");
            if (!hasNewIndependent && valuesObj.has("vehicle_status_watermark")) {
                boolean legacyMaster = Boolean.TRUE.equals(normalized.get("vehicle_status_watermark"));
                boolean legacySpeed = valuesObj.has("watermark_speed_enabled")
                        ? Boolean.TRUE.equals(normalized.get("watermark_speed_enabled"))
                        : true;
                boolean legacyGearTurn = valuesObj.has("watermark_gear_turn_enabled")
                        ? Boolean.TRUE.equals(normalized.get("watermark_gear_turn_enabled"))
                        : true;
                normalized.put("watermark_speed_enabled", legacyMaster && legacySpeed);
                normalized.put("watermark_gear_enabled", legacyMaster && legacyGearTurn);
                normalized.put("watermark_turn_signal_enabled", legacyMaster && legacyGearTurn);
                normalized.put("watermark_gear_turn_enabled", legacyMaster && legacyGearTurn);
            } else if (hasNewIndependent) {
                boolean s = Boolean.TRUE.equals(normalized.get("watermark_speed_enabled"));
                boolean g = Boolean.TRUE.equals(normalized.get("watermark_gear_enabled"));
                boolean t = Boolean.TRUE.equals(normalized.get("watermark_turn_signal_enabled"));
                if (!valuesObj.has("watermark_gear_turn_enabled")) {
                    normalized.put("watermark_gear_turn_enabled", g || t);
                }
                if (!valuesObj.has("vehicle_status_watermark")) {
                    normalized.put("vehicle_status_watermark", s || g || t);
                }
            }
        }

        String crossErr = validateSectionInvariants(section, normalized, warnings);
        if (crossErr != null) {
            return ValidationResult.fail(crossErr);
        }

        Map<E5ConfigSection, E5ConfigSnapshot.SectionData> singleMap = new EnumMap<>(E5ConfigSection.class);
        singleMap.put(section, new E5ConfigSnapshot.SectionData(section.getVersion(), normalized));
        E5ConfigSnapshot dummy = new E5ConfigSnapshot(
                E5ConfigSection.SCHEMA_VERSION,
                E5ConfigSection.VEHICLE_MODEL_GALAXY_E5,
                "section",
                section.getLabel(),
                "",
                "",
                "",
                E5ConfigSnapshot.SOURCE_LOCAL,
                singleMap
        );
        return ValidationResult.ok(dummy, warnings);
    }

    @Nullable
    private static Object parseTypedField(@NonNull FieldSpec spec, @NonNull JsonElement raw) {
        switch (spec.type) {
            case BOOLEAN:
                if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean()) {
                    return raw.getAsBoolean();
                }
                return null;
            case INT:
                if (isWholeInt(raw)) {
                    return raw.getAsInt();
                }
                return null;
            case FLOAT:
                if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isNumber()) {
                    double d = raw.getAsDouble();
                    if (!Double.isFinite(d)) {
                        return null;
                    }
                    return (float) d;
                }
                return null;
            case STRING:
                if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
                    return raw.getAsString();
                }
                return null;
            case RULES_JSON:
                if (raw.isJsonArray()) {
                    return raw.getAsJsonArray().toString();
                }
                if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
                    return raw.getAsString();
                }
                return null;
            case FLOAT_ARRAY_16: {
                if (!raw.isJsonArray()) {
                    return null;
                }
                JsonArray arr = raw.getAsJsonArray();
                if (arr.size() != 16) {
                    return null;
                }
                List<Float> list = new ArrayList<>(16);
                for (JsonElement item : arr) {
                    if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
                        return null;
                    }
                    double d = item.getAsDouble();
                    if (!Double.isFinite(d)) {
                        return null;
                    }
                    list.add((float) d);
                }
                return Collections.unmodifiableList(list);
            }
            case BOOL_ARRAY_4: {
                if (!raw.isJsonArray()) {
                    return null;
                }
                JsonArray arr = raw.getAsJsonArray();
                if (arr.size() != 4) {
                    return null;
                }
                List<Boolean> list = new ArrayList<>(4);
                for (JsonElement item : arr) {
                    if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean()) {
                        return null;
                    }
                    list.add(item.getAsBoolean());
                }
                return Collections.unmodifiableList(list);
            }
            default:
                return null;
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static String validateSectionInvariants(@NonNull E5ConfigSection section,
                                                    @NonNull Map<String, Object> values,
                                                    @NonNull List<String> warnings) {
        if (section == E5ConfigSection.CAMERA_MAPPING) {
            Set<String> seenIds = new HashSet<>();
            for (String pos : CAMERA_POSITIONS) {
                String id = String.valueOf(values.get("camera_" + pos + "_id")).trim();
                if (id.isEmpty()) {
                    return "摄像头位置 " + pos + " 的 cameraId 不能为空";
                }
                if (!seenIds.add(id)) {
                    return "四路摄像头 ID 存在重复映射（重复 ID：" + id + "）";
                }
                values.put("camera_" + pos + "_id", id);
            }
            boolean anyRecording = false;
            for (String pos : CAMERA_POSITIONS) {
                if (Boolean.TRUE.equals(values.get("recording_camera_" + pos + "_enabled"))) {
                    anyRecording = true;
                    break;
                }
            }
            if (!anyRecording) {
                return "请至少启用一路日常录制摄像头";
            }
            boolean anyVisible = false;
            for (String pos : CAMERA_POSITIONS) {
                if (Boolean.TRUE.equals(values.get("camera_" + pos + "_visible"))) {
                    anyVisible = true;
                    break;
                }
            }
            if (!anyVisible) {
                return "请至少保留一路主预览画面显示";
            }
            List<Boolean> modeBVis = (List<Boolean>) values.get("mode_b_visible");
            if (modeBVis == null || !modeBVis.contains(Boolean.TRUE)) {
                return "模式 B 至少需要保留一个可见预览窗格";
            }
        } else if (section == E5ConfigSection.SURROUND_360) {
            SurroundViewMode top = SurroundViewMode.fromKey(String.valueOf(values.get("surround_top_view")));
            SurroundViewMode bottom = SurroundViewMode.fromKey(String.valueOf(values.get("surround_bottom_view")));
            SurroundViewMode[] pair = SurroundViewMode.normalizeDetailViews(top, bottom);
            if (!pair[0].name().equals(top.name()) || !pair[1].name().equals(bottom.name())) {
                values.put("surround_top_view", pair[0].name());
                values.put("surround_bottom_view", pair[1].name());
                warnings.add("360 上下视角已自动规整为合法组合：" + pair[0].label + " + " + pair[1].label);
            }
        }
        return null;
    }

    private static Map<E5ConfigSection, Map<String, FieldSpec>> buildSchema() {
        Map<E5ConfigSection, Map<String, FieldSpec>> root = new EnumMap<>(E5ConfigSection.class);

        // 1. appearance
        Map<String, FieldSpec> appearance = new LinkedHashMap<>();
        reg(appearance, "theme_mode", FieldType.STRING, UiThemeController.ThemeMode.FOLLOW_SYSTEM.key,
                enumSet("follow_system", "dark", "light"));
        reg(appearance, "ui_scale_mode", FieldType.STRING, AppConfig.UI_SCALE_STANDARD,
                enumSet(AppConfig.UI_SCALE_STANDARD, AppConfig.UI_SCALE_LARGE));
        reg(appearance, "default_start_page", FieldType.STRING, AppConfig.START_PAGE_RECORDING,
                enumSet(AppConfig.START_PAGE_RECORDING, AppConfig.START_PAGE_PLAYBACK, AppConfig.START_PAGE_SETTINGS));
        reg(appearance, "preview_mode", FieldType.STRING, AppConfig.PREVIEW_MODE_B,
                enumSet(AppConfig.PREVIEW_MODE_A, AppConfig.PREVIEW_MODE_B, AppConfig.PREVIEW_MODE_C, AppConfig.PREVIEW_MODE_D, AppConfig.PREVIEW_MODE_E));
        reg(appearance, "preview_presentation", FieldType.STRING, PreviewPresentation.SIDEBAR.getKey(),
                enumSet(PreviewPresentation.SIDEBAR.getKey(), PreviewPresentation.FULLSCREEN.getKey(), PreviewPresentation.WINDOW.getKey()));
        reg(appearance, "corner_nav_mode", FieldType.STRING, AppConfig.CORNER_NAV_AUTO,
                enumSet(AppConfig.CORNER_NAV_AUTO, AppConfig.CORNER_NAV_ALWAYS, AppConfig.CORNER_NAV_HIDDEN));
        reg(appearance, "view_shortcuts_enabled", FieldType.BOOLEAN, true, null);
        reg(appearance, "accessibility_overlay_enabled", FieldType.BOOLEAN, true, null);
        reg(appearance, "confirm_door_exit", FieldType.BOOLEAN, false, null);
        reg(appearance, "door_exit_delay_seconds", FieldType.INT, AppConfig.DEFAULT_DOOR_EXIT_DELAY_SECONDS,
                intSet(3, 5, 10, 15, 30));
        reg(appearance, "auto_start_on_boot", FieldType.BOOLEAN, true, null);
        root.put(E5ConfigSection.APPEARANCE, appearance);

        // 2. camera_mapping
        Map<String, FieldSpec> camera = new LinkedHashMap<>();
        reg(camera, "car_model", FieldType.STRING, AppConfig.CAR_MODEL_GALAXY_E5,
                enumSet(AppConfig.CAR_MODEL_GALAXY_E5, AppConfig.CAR_MODEL_E5_MULTI));
        reg(camera, "camera_front_id", FieldType.STRING, "2", nonEmptyString(16));
        reg(camera, "camera_back_id", FieldType.STRING, "1", nonEmptyString(16));
        reg(camera, "camera_left_id", FieldType.STRING, "3", nonEmptyString(16));
        reg(camera, "camera_right_id", FieldType.STRING, "0", nonEmptyString(16));
        reg(camera, "recording_camera_front_enabled", FieldType.BOOLEAN, false, null);
        reg(camera, "recording_camera_back_enabled", FieldType.BOOLEAN, true, null);
        reg(camera, "recording_camera_left_enabled", FieldType.BOOLEAN, true, null);
        reg(camera, "recording_camera_right_enabled", FieldType.BOOLEAN, true, null);
        for (String pos : CAMERA_POSITIONS) {
            reg(camera, "camera_" + pos + "_visible", FieldType.BOOLEAN, true, null);
        }
        reg(camera, "mode_b_panes", FieldType.FLOAT_ARRAY_16,
                defaultPanesList(PreviewLayoutModel.PRESET_MODE_B_THREE_COL), paneRect16Validator());
        reg(camera, "mode_b_visible", FieldType.BOOL_ARRAY_4,
                Arrays.asList(Boolean.TRUE, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE), null);
        reg(camera, "mode_c_panes", FieldType.FLOAT_ARRAY_16,
                defaultPanesList(PreviewLayoutModel.PRESET_MODE_C_FRONT), paneRect16Validator());
        reg(camera, "mode_c_show_left", FieldType.BOOLEAN, true, null);
        reg(camera, "mode_c_show_right", FieldType.BOOLEAN, true, null);
        reg(camera, "mode_d_panes", FieldType.FLOAT_ARRAY_16,
                defaultPanesList(PreviewLayoutModel.PRESET_MODE_D_DUAL), paneRect16Validator());
        reg(camera, "mode_e_panes", FieldType.FLOAT_ARRAY_16,
                defaultPanesList(PreviewLayoutModel.PRESET_MODE_E_SIDES), paneRect16Validator());
        root.put(E5ConfigSection.CAMERA_MAPPING, camera);

        // 3. recording
        Map<String, FieldSpec> recording = new LinkedHashMap<>();
        reg(recording, "recording_mode", FieldType.STRING, AppConfig.RECORDING_MODE_AUTO,
                enumSet(AppConfig.RECORDING_MODE_AUTO, AppConfig.RECORDING_MODE_CODEC, AppConfig.RECORDING_MODE_MEDIA_RECORDER));
        reg(recording, "force_h264_encoding", FieldType.BOOLEAN, false, null);
        reg(recording, "target_resolution", FieldType.STRING, AppConfig.RESOLUTION_DEFAULT, resolutionValidator());
        reg(recording, "bitrate_level", FieldType.STRING, AppConfig.BITRATE_MEDIUM,
                enumSet(AppConfig.BITRATE_LOW, AppConfig.BITRATE_MEDIUM, AppConfig.BITRATE_HIGH));
        reg(recording, "framerate_level", FieldType.STRING, AppConfig.FRAMERATE_STANDARD,
                enumSet(AppConfig.FRAMERATE_STANDARD, AppConfig.FRAMERATE_LOW));
        reg(recording, "target_recording_fps", FieldType.INT, 0, intSet(0, 15, 20, 25, 30));
        reg(recording, "preview_fps_limit", FieldType.INT, 0, intSet(0, 15, 25));
        reg(recording, "segment_duration_minutes", FieldType.INT, AppConfig.SEGMENT_DURATION_1_MIN, intSet(1, 3, 5));
        reg(recording, "auto_start_recording", FieldType.BOOLEAN, false, null);
        reg(recording, "screen_off_recording", FieldType.BOOLEAN, false, null);
        reg(recording, "recording_stats_enabled", FieldType.BOOLEAN, true, null);
        reg(recording, "keep_camera_connected", FieldType.BOOLEAN, false, null);
        reg(recording, "camera_recovery_page_enabled", FieldType.BOOLEAN, false, null);
        reg(recording, "rd_shift_auto_popup_enabled", FieldType.BOOLEAN, false, null);
        reg(recording, "rd_shift_popup_seconds", FieldType.INT, GearShiftPopupCoordinator.DEFAULT_POPUP_SECONDS,
                intSet(3, 5, 10, 15, 30));
        reg(recording, "storage_location", FieldType.STRING, AppConfig.STORAGE_INTERNAL,
                enumSet(AppConfig.STORAGE_INTERNAL, AppConfig.STORAGE_EXTERNAL_SD));
        reg(recording, "relay_write_enabled", FieldType.BOOLEAN, true, null);
        reg(recording, "video_storage_limit_gb", FieldType.INT, 10, intRange(0, 500));
        reg(recording, "photo_storage_limit_gb", FieldType.INT, 10, intRange(0, 500));
        reg(recording, "video_image_adjust_enabled", FieldType.BOOLEAN, false, null);
        reg(recording, "video_image_quality_percent", FieldType.INT, ImageAdjustmentConfig.DEFAULT_QUALITY_PERCENT,
                intRange(ImageAdjustmentConfig.MIN_QUALITY_PERCENT, ImageAdjustmentConfig.MAX_QUALITY_PERCENT));
        reg(recording, "video_image_brightness_percent", FieldType.INT, ImageAdjustmentConfig.DEFAULT_BRIGHTNESS_PERCENT,
                intRange(ImageAdjustmentConfig.MIN_BRIGHTNESS_PERCENT, ImageAdjustmentConfig.MAX_BRIGHTNESS_PERCENT));
        reg(recording, "video_image_contrast_percent", FieldType.INT, ImageAdjustmentConfig.DEFAULT_CONTRAST_PERCENT,
                intRange(ImageAdjustmentConfig.MIN_CONTRAST_PERCENT, ImageAdjustmentConfig.MAX_CONTRAST_PERCENT));
        reg(recording, "video_image_saturation_percent", FieldType.INT, ImageAdjustmentConfig.DEFAULT_SATURATION_PERCENT,
                intRange(ImageAdjustmentConfig.MIN_SATURATION_PERCENT, ImageAdjustmentConfig.MAX_SATURATION_PERCENT));
        for (String pos : CAMERA_POSITIONS) {
            reg(recording, "video_image_crop_percent_" + pos, FieldType.INT, ImageAdjustmentConfig.DEFAULT_CROP_PERCENT,
                    intRange(ImageAdjustmentConfig.MIN_CROP_PERCENT, ImageAdjustmentConfig.MAX_CROP_PERCENT));
            reg(recording, "phys_mirror_h_" + pos, FieldType.BOOLEAN, false, null);
            reg(recording, "phys_mirror_v_" + pos, FieldType.BOOLEAN, false, null);
        }
        reg(recording, "timestamp_watermark_enabled", FieldType.BOOLEAN, false, null);
        reg(recording, "vehicle_status_watermark", FieldType.BOOLEAN, false, null);
        reg(recording, "watermark_speed_enabled", FieldType.BOOLEAN, false, null);
        reg(recording, "watermark_gear_turn_enabled", FieldType.BOOLEAN, false, null);
        reg(recording, "watermark_gear_enabled", FieldType.BOOLEAN, false, null);
        reg(recording, "watermark_turn_signal_enabled", FieldType.BOOLEAN, false, null);
        reg(recording, "watermark_custom_text_enabled", FieldType.BOOLEAN, false, null);
        reg(recording, "watermark_custom_text", FieldType.STRING, "", maxLength(VehicleStatusWatermark.MAX_CUSTOM_TEXT_LENGTH));
        reg(recording, "video_watermark_position", FieldType.STRING, VehicleStatusWatermark.Position.BOTTOM_RIGHT.name(),
                enumSet("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"));
        reg(recording, "video_watermark_style", FieldType.STRING, VehicleStatusWatermark.Style.CARD.name(),
                enumSet("CARD", "TEXT"));
        reg(recording, "recording_start_sound_mode", FieldType.STRING, RecordingSoundPlayer.MODE_NONE,
                enumSet(RecordingSoundPlayer.MODE_NONE, RecordingSoundPlayer.MODE_SYSTEM));
        reg(recording, "recording_stop_sound_mode", FieldType.STRING, RecordingSoundPlayer.MODE_NONE,
                enumSet(RecordingSoundPlayer.MODE_NONE, RecordingSoundPlayer.MODE_SYSTEM));
        reg(recording, "recording_floating_enabled", FieldType.BOOLEAN, true, null);
        reg(recording, "recording_floating_button_size", FieldType.INT, 64, intRange(24, 160));
        reg(recording, "recording_floating_time_text_size", FieldType.INT, 14, intRange(10, 32));
        reg(recording, "floating_window_enabled", FieldType.BOOLEAN, false, null);
        reg(recording, "floating_window_size", FieldType.INT, AppConfig.FLOATING_SIZE_MEDIUM, intRange(32, 160));
        reg(recording, "floating_window_alpha", FieldType.INT, 100, intRange(0, 100));
        root.put(E5ConfigSection.RECORDING, recording);

        // 4. vehicle_rules_signals
        Map<String, FieldSpec> rules = new LinkedHashMap<>();
        reg(rules, "vehicle_trigger_rules", FieldType.RULES_JSON,
                VehicleTriggerRule.toJsonArray(VehicleTriggerRule.createDefaultRules()), rulesJsonValidator());
        reg(rules, "turn_signal_trigger_mode", FieldType.STRING, AppConfig.TRIGGER_MODE_VHAL_GRPC,
                enumSet(AppConfig.TRIGGER_MODE_VHAL_GRPC, AppConfig.TRIGGER_MODE_CAR_SIGNAL_MANAGER, AppConfig.TRIGGER_MODE_LOGCAT));
        reg(rules, "t06_vhal_prop_gear_selection", FieldType.INT, 0, intRange(0, Integer.MAX_VALUE));
        reg(rules, "t06_vhal_prop_current_gear", FieldType.INT, 0, intRange(0, Integer.MAX_VALUE));
        reg(rules, "t06_vhal_prop_speed", FieldType.INT, 0, intRange(0, Integer.MAX_VALUE));
        reg(rules, "t06_vhal_prop_door_pos", FieldType.INT, 0, intRange(0, Integer.MAX_VALUE));
        reg(rules, "t06_vhal_prop_turn_signal", FieldType.INT, 0, intRange(0, Integer.MAX_VALUE));
        root.put(E5ConfigSection.VEHICLE_RULES_SIGNALS, rules);

        // 5. blind_spot_hud
        Map<String, FieldSpec> blindSpot = new LinkedHashMap<>();
        reg(blindSpot, "blind_spot_global_enabled", FieldType.BOOLEAN, false, null);
        reg(blindSpot, "main_blind_spot_enabled", FieldType.BOOLEAN, true, null);
        reg(blindSpot, "turn_signal_linkage_enabled", FieldType.BOOLEAN, false, null);
        reg(blindSpot, "turn_signal_timeout", FieldType.INT, 10, intRange(0, 30));
        reg(blindSpot, "turn_signal_reuse_main_floating", FieldType.BOOLEAN, true, null);
        reg(blindSpot, "main_floating_enabled", FieldType.BOOLEAN, false, null);
        reg(blindSpot, "main_floating_camera", FieldType.STRING, "front", enumSet(CAMERA_POSITIONS));
        reg(blindSpot, "main_floating_x", FieldType.INT, 100, intRange(-2560, 2560));
        reg(blindSpot, "main_floating_y", FieldType.INT, 100, intRange(-1600, 1600));
        reg(blindSpot, "main_floating_width", FieldType.INT, 480, intRange(160, 2560));
        reg(blindSpot, "main_floating_height", FieldType.INT, 320, intRange(120, 1600));
        reg(blindSpot, "main_floating_aspect_ratio_locked", FieldType.BOOLEAN, false, null);
        reg(blindSpot, "main_floating_long_press_drag", FieldType.BOOLEAN, false, null);
        reg(blindSpot, "turn_signal_floating_x", FieldType.INT, 200, intRange(-2560, 2560));
        reg(blindSpot, "turn_signal_floating_y", FieldType.INT, 200, intRange(-1600, 1600));
        reg(blindSpot, "turn_signal_floating_width", FieldType.INT, 640, intRange(160, 2560));
        reg(blindSpot, "turn_signal_floating_height", FieldType.INT, 360, intRange(120, 1600));
        reg(blindSpot, "turn_signal_floating_rotation", FieldType.INT, 0, intSet(0, 90, 180, 270));
        reg(blindSpot, "t04_standalone_window_camera", FieldType.STRING, "front", enumSet(CAMERA_POSITIONS));
        reg(blindSpot, "t04_standalone_window_x", FieldType.INT, AppConfig.DEFAULT_STANDALONE_WINDOW_X, intRange(-2560, 2560));
        reg(blindSpot, "t04_standalone_window_y", FieldType.INT, AppConfig.DEFAULT_STANDALONE_WINDOW_Y, intRange(-1600, 1600));
        reg(blindSpot, "t04_standalone_window_width", FieldType.INT, AppConfig.DEFAULT_STANDALONE_WINDOW_WIDTH, intRange(160, 2560));
        reg(blindSpot, "t04_standalone_window_height", FieldType.INT, AppConfig.DEFAULT_STANDALONE_WINDOW_HEIGHT, intRange(120, 1600));
        reg(blindSpot, "t04_blind_spot_suppress_on_360", FieldType.BOOLEAN, true, null);
        reg(blindSpot, "avm_avoidance_enabled", FieldType.BOOLEAN, false, null);
        reg(blindSpot, "avm_avoidance_activity", FieldType.STRING, "com.geely.avm_app.AvmRenderActivity", maxLength(128));
        reg(blindSpot, "blind_spot_use_same_side_position", FieldType.BOOLEAN, false, null);
        reg(blindSpot, "blind_spot_correction_enabled", FieldType.BOOLEAN, false, null);
        for (String pos : CAMERA_POSITIONS) {
            reg(blindSpot, "blind_spot_correction_" + pos + "_scale_x", FieldType.FLOAT, 1.0f, floatRange(0.1f, 3.0f));
            reg(blindSpot, "blind_spot_correction_" + pos + "_scale_y", FieldType.FLOAT, 1.0f, floatRange(0.1f, 3.0f));
            reg(blindSpot, "blind_spot_correction_" + pos + "_translate_x", FieldType.FLOAT, 0.0f, floatRange(-1.0f, 1.0f));
            reg(blindSpot, "blind_spot_correction_" + pos + "_translate_y", FieldType.FLOAT, 0.0f, floatRange(-1.0f, 1.0f));
            reg(blindSpot, "blind_spot_correction_" + pos + "_rotation", FieldType.INT, 0, intRange(0, 359));
            reg(blindSpot, "blind_spot_correction_" + pos + "_crop_left", FieldType.FLOAT, 0.0f, floatRange(0.0f, 0.45f));
            reg(blindSpot, "blind_spot_correction_" + pos + "_crop_top", FieldType.FLOAT, 0.0f, floatRange(0.0f, 0.45f));
            reg(blindSpot, "blind_spot_correction_" + pos + "_crop_right", FieldType.FLOAT, 0.0f, floatRange(0.0f, 0.45f));
            reg(blindSpot, "blind_spot_correction_" + pos + "_crop_bottom", FieldType.FLOAT, 0.0f, floatRange(0.0f, 0.45f));
        }
        for (String target : MIRROR_TARGET_KEYS) {
            for (String pos : CAMERA_POSITIONS) {
                boolean defaultH = "main".equals(target) && "back".equals(pos);
                reg(blindSpot, "out_mirror_" + target + "_" + pos + "_h", FieldType.BOOLEAN, defaultH, null);
                reg(blindSpot, "out_mirror_" + target + "_" + pos + "_v", FieldType.BOOLEAN, false, null);
            }
        }
        reg(blindSpot, "secondary_display_enabled", FieldType.BOOLEAN, false, null);
        reg(blindSpot, "secondary_display_camera", FieldType.STRING, "front", enumSet(CAMERA_POSITIONS));
        reg(blindSpot, "secondary_display_id", FieldType.INT, 0, intRange(0, 32));
        reg(blindSpot, "secondary_display_x", FieldType.INT, 0, intRange(-2560, 2560));
        reg(blindSpot, "secondary_display_y", FieldType.INT, 139, intRange(-1600, 1600));
        reg(blindSpot, "secondary_display_width", FieldType.INT, 318, intRange(64, 2560));
        reg(blindSpot, "secondary_display_height", FieldType.INT, 236, intRange(64, 1600));
        reg(blindSpot, "secondary_display_rotation", FieldType.INT, 0, intSet(0, 90, 180, 270));
        reg(blindSpot, "secondary_display_border", FieldType.BOOLEAN, false, null);
        reg(blindSpot, "secondary_display_orientation", FieldType.INT, 180, intSet(0, 90, 180, 270));
        reg(blindSpot, "secondary_display_alpha", FieldType.INT, 100, intRange(0, 100));
        reg(blindSpot, "mjpeg_stream_enabled", FieldType.BOOLEAN, false, null);
        reg(blindSpot, "mjpeg_stream_camera", FieldType.STRING, "left", enumSet(CAMERA_POSITIONS));
        reg(blindSpot, "mjpeg_stream_port", FieldType.INT, 29543, intRange(1024, 65535));
        reg(blindSpot, "mjpeg_stream_width", FieldType.INT, 820, intRange(80, 1920));
        reg(blindSpot, "mjpeg_stream_height", FieldType.INT, 320, intRange(80, 1920));
        reg(blindSpot, "mjpeg_stream_quality", FieldType.INT, 30, intRange(1, 95));
        reg(blindSpot, "mjpeg_stream_pan_x", FieldType.FLOAT, 0.5f, floatRange(0f, 1f));
        reg(blindSpot, "mjpeg_stream_pan_y", FieldType.FLOAT, 0.5f, floatRange(0f, 1f));
        reg(blindSpot, "mjpeg_stream_cover_scale", FieldType.FLOAT, 1.0f, floatRange(1.0f, 4.0f));
        reg(blindSpot, "mjpeg_stream_linkage_mode", FieldType.BOOLEAN, false, null);
        reg(blindSpot, "mjpeg_stream_protocol", FieldType.STRING, "HTTP", enumSet("HTTP", "TCP", "UDP", "RTP"));
        reg(blindSpot, "mjpeg_stream_mode", FieldType.STRING, "SERVER", enumSet("SERVER", "CLIENT"));
        reg(blindSpot, "mjpeg_stream_client_host", FieldType.STRING, "", maxLength(128));
        reg(blindSpot, "mjpeg_stream_client_port", FieldType.INT, 8080, intRange(1, 65535));
        reg(blindSpot, "mjpeg_stream_auto_discover", FieldType.BOOLEAN, true, null);
        root.put(E5ConfigSection.BLIND_SPOT_HUD, blindSpot);

        // 6. surround_360
        Map<String, FieldSpec> surround = new LinkedHashMap<>();
        reg(surround, "surround_top_view", FieldType.STRING, SurroundViewMode.DRIVE.name(),
                enumSet("DRIVE", "FRONT", "REAR", "FRONT_WHEELS", "REAR_WHEELS", "SIDES"));
        reg(surround, "surround_bottom_view", FieldType.STRING, SurroundViewMode.FRONT_WHEELS.name(),
                enumSet("FRONT_WHEELS", "REAR_WHEELS", "SIDES"));
        reg(surround, "surround_wheels_collapsed", FieldType.BOOLEAN, false, null);
        reg(surround, "surround_stretch_road_views", FieldType.BOOLEAN, false, null);
        reg(surround, "surround_overlay_buttons_visible", FieldType.BOOLEAN, true, null);
        reg(surround, "reverse_preview_enabled", FieldType.BOOLEAN, false, null);
        reg(surround, "reverse_preview_delay_ms", FieldType.INT, AppConfig.DEFAULT_REVERSE_PREVIEW_DELAY_MS,
                reverseDelayValidator());
        reg(surround, "preview_speed_close_enabled", FieldType.BOOLEAN, false, null);
        reg(surround, "preview_speed_threshold_kmh", FieldType.INT, AppConfig.DEFAULT_SPEED_CLOSE_KMH,
                intRange(AppConfig.MIN_SPEED_CLOSE_KMH, AppConfig.MAX_SPEED_CLOSE_KMH));
        reg(surround, "park_preview_close_enabled", FieldType.BOOLEAN, false, null);
        reg(surround, "park_preview_close_delay_seconds", FieldType.INT, AppConfig.DEFAULT_PARK_CLOSE_SECONDS,
                intRange(AppConfig.MIN_PARK_CLOSE_SECONDS, AppConfig.MAX_PARK_CLOSE_SECONDS));
        reg(surround, "surround_ground_width_cm", FieldType.INT, AppConfig.DEFAULT_SURROUND_GROUND_WIDTH_CM,
                intRange(AppConfig.MIN_SURROUND_GROUND_WIDTH_CM, AppConfig.MAX_SURROUND_GROUND_WIDTH_CM));
        reg(surround, "surround_ground_length_cm", FieldType.INT, AppConfig.DEFAULT_SURROUND_GROUND_LENGTH_CM,
                intRange(AppConfig.MIN_SURROUND_GROUND_LENGTH_CM, AppConfig.MAX_SURROUND_GROUND_LENGTH_CM));
        reg(surround, "surround_ground_front_ext_cm", FieldType.INT, AppConfig.DEFAULT_SURROUND_GROUND_FRONT_EXT_CM,
                intRange(AppConfig.MIN_SURROUND_GROUND_FRONT_EXT_CM, AppConfig.MAX_SURROUND_GROUND_FRONT_EXT_CM));
        reg(surround, "preview_guidance_enabled", FieldType.BOOLEAN, true, null);
        root.put(E5ConfigSection.SURROUND_360, surround);

        return Collections.unmodifiableMap(root);
    }

    private static void reg(@NonNull Map<String, FieldSpec> map,
                            @NonNull String key,
                            @NonNull FieldType type,
                            @NonNull Object defaultValue,
                            @Nullable Validator validator) {
        map.put(key, new FieldSpec(key, type, defaultValue, validator));
    }

    private static Validator enumSet(@NonNull String... allowed) {
        final Set<String> set = new HashSet<>(Arrays.asList(allowed));
        return v -> set.contains(String.valueOf(v)) ? null : "不在允许枚举范围内（" + v + "）";
    }

    private static Validator intSet(int... allowed) {
        final Set<Integer> set = new HashSet<>();
        for (int a : allowed) {
            set.add(a);
        }
        return v -> (v instanceof Integer && set.contains(v)) ? null : "不在允许离散档位内（" + v + "）";
    }

    private static Validator intRange(int min, int max) {
        return v -> {
            if (!(v instanceof Integer)) {
                return "必须是整数";
            }
            int iv = (Integer) v;
            return (iv >= min && iv <= max) ? null : "超出允许范围 [" + min + ", " + max + "]";
        };
    }

    private static Validator floatRange(float min, float max) {
        return v -> {
            if (!(v instanceof Float)) {
                return "必须是浮点数";
            }
            float fv = (Float) v;
            return (Float.isFinite(fv) && fv >= min && fv <= max) ? null
                    : String.format(Locale.US, "超出允许范围 [%.2f, %.2f]", min, max);
        };
    }

    private static Validator nonEmptyString(int maxLen) {
        return v -> {
            if (!(v instanceof String)) {
                return "必须是字符串";
            }
            String trimmed = ((String) v).trim();
            if (trimmed.isEmpty() || trimmed.length() > maxLen) {
                return "不能为空且长度不超过 " + maxLen;
            }
            return null;
        };
    }

    private static Validator maxLength(int maxLen) {
        return v -> (v instanceof String && ((String) v).length() <= maxLen) ? null
                : "字符串长度不能超过 " + maxLen;
    }

    private static Validator resolutionValidator() {
        return v -> {
            if (!(v instanceof String)) {
                return "必须是分辨率字符串";
            }
            String s = ((String) v).trim();
            if (AppConfig.RESOLUTION_DEFAULT.equals(s)) {
                return null;
            }
            Matcher m = RESOLUTION_PATTERN.matcher(s);
            if (!m.matches()) {
                return "必须为 default 或 WxH 格式";
            }
            int w = Integer.parseInt(m.group(1));
            int h = Integer.parseInt(m.group(2));
            if (w < 160 || w > 4096 || h < 120 || h > 4096) {
                return "分辨率宽高超出合法范围";
            }
            return null;
        };
    }

    private static Validator reverseDelayValidator() {
        return v -> {
            if (!(v instanceof Integer)) {
                return "必须是整数";
            }
            int ms = (Integer) v;
            if (ms < AppConfig.MIN_REVERSE_PREVIEW_DELAY_MS
                    || ms > AppConfig.MAX_REVERSE_PREVIEW_DELAY_MS
                    || ms % AppConfig.STEP_REVERSE_PREVIEW_DELAY_MS != 0) {
                return "必须在 0..5000ms 且为 50ms 整数倍";
            }
            return null;
        };
    }

    private static Validator paneRect16Validator() {
        return v -> {
            if (!(v instanceof List<?>)) {
                return "必须是 16 浮点数组";
            }
            List<?> rawList = (List<?>) v;
            if (rawList.size() != 16) {
                return "长度必须为 16";
            }
            float[] arr = new float[16];
            for (int i = 0; i < 16; i++) {
                Object o = rawList.get(i);
                if (!(o instanceof Float)) {
                    return "包含非浮点元素";
                }
                arr[i] = (Float) o;
            }
            PreviewLayoutModel probe = new PreviewLayoutModel();
            return probe.restore(arr) ? null : "包含越界或小于最小尺寸(0.18)的窗格矩形";
        };
    }

    private static Validator rulesJsonValidator() {
        return v -> {
            if (!(v instanceof String)) {
                return "规则配置格式非法";
            }
            try {
                JsonElement el = JsonParser.parseString((String) v);
                if (!el.isJsonArray()) {
                    return "必须是规则 JSON 数组";
                }
                JsonArray arr = el.getAsJsonArray();
                if (arr.size() > 32) {
                    return "规则数量超出上限（最多 32 条）";
                }
                for (JsonElement item : arr) {
                    if (item == null || !item.isJsonObject()) {
                        return "包含非对象规则项";
                    }
                    String itemErr = validateRuleObject(item.getAsJsonObject());
                    if (itemErr != null) {
                        return itemErr;
                    }
                }
                return null;
            } catch (Exception e) {
                return "规则 JSON 解析失败";
            }
        };
    }

    @Nullable
    private static String validateRuleObject(@NonNull JsonObject obj) {
        if (!obj.has("primary") || !obj.get("primary").isJsonObject()) {
            return "规则缺少 primary 条件";
        }
        String primaryErr = validateConditionObject(obj.getAsJsonObject("primary"));
        if (primaryErr != null) {
            return primaryErr;
        }
        if (obj.has("extras") && !obj.get("extras").isJsonNull()) {
            if (!obj.get("extras").isJsonArray()) {
                return "规则 extras 必须是数组";
            }
            for (JsonElement extra : obj.getAsJsonArray("extras")) {
                if (extra == null || !extra.isJsonObject()) {
                    return "规则 extras 项格式错误";
                }
                String extraErr = validateConditionObject(extra.getAsJsonObject());
                if (extraErr != null) {
                    return extraErr;
                }
            }
        }
        if (obj.has("logic") && !obj.get("logic").isJsonNull()) {
            String logic = obj.get("logic").getAsString();
            if (!isEnumName(ConditionLogic.values(), logic)) {
                return "不支持的条件逻辑：" + logic;
            }
        }
        if (obj.has("action") && !obj.get("action").isJsonNull()) {
            String action = obj.get("action").getAsString();
            if (!isEnumName(RuleAction.values(), action)) {
                return "不支持的规则动作：" + action;
            }
        }
        if (obj.has("delaySeconds") && !obj.get("delaySeconds").isJsonNull()) {
            if (!isWholeInt(obj.get("delaySeconds"))) {
                return "规则延时必须是整数";
            }
            int d = obj.get("delaySeconds").getAsInt();
            if (d < 0 || d > 3600) {
                return "规则延时超出范围 [0, 3600]";
            }
        }
        return null;
    }

    @Nullable
    private static String validateConditionObject(@NonNull JsonObject cond) {
        if (!cond.has("signal") || !cond.get("signal").isJsonPrimitive()) {
            return "条件缺少 signal";
        }
        String sig = cond.get("signal").getAsString();
        if (!isEnumName(VehicleRuleSignal.values(), sig)) {
            return "条件信号不在 4 类白名单内：" + sig;
        }
        if (cond.has("comparison") && !cond.get("comparison").isJsonNull()) {
            String cmp = cond.get("comparison").getAsString();
            if (!isEnumName(RuleComparison.values(), cmp)) {
                return "不支持的比较运算符：" + cmp;
            }
        }
        if (cond.has("expected") && !cond.get("expected").isJsonNull() && !isWholeInt(cond.get("expected"))) {
            return "条件期望值必须是整数";
        }
        return null;
    }

    private static <E extends Enum<E>> boolean isEnumName(@NonNull E[] values, @Nullable String raw) {
        if (raw == null) {
            return false;
        }
        for (E e : values) {
            if (e.name().equalsIgnoreCase(raw.trim())) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static List<Float> defaultPanesList(int preset) {
        PreviewLayoutModel model = new PreviewLayoutModel(preset);
        float[] snap = model.snapshot();
        List<Float> list = new ArrayList<>(snap.length);
        for (float v : snap) {
            list.add(v);
        }
        return Collections.unmodifiableList(list);
    }

    @NonNull
    @SuppressWarnings("unchecked")
    private static Object copyDefault(@NonNull Object val) {
        if (val instanceof List<?>) {
            return Collections.unmodifiableList(new ArrayList<>((List<Object>) val));
        }
        return val;
    }

    private static boolean isWholeInt(@Nullable JsonElement el) {
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            double d = el.getAsDouble();
            return Double.isFinite(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE && Math.rint(d) == d;
        } catch (Exception ignored) {
            return false;
        }
    }

    @NonNull
    private static String readOptionalString(@NonNull JsonObject obj, @NonNull String key, @NonNull String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : fallback;
    }

    @NonNull
    private static String sanitizeMeta(@NonNull String raw, @NonNull String fallback, int maxLen) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) : trimmed;
    }

    @SuppressWarnings("unchecked")
    private static void putObjectToJson(@NonNull JsonObject target, @NonNull String key, @Nullable Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Boolean) {
            target.addProperty(key, (Boolean) value);
        } else if (value instanceof Integer) {
            target.addProperty(key, (Integer) value);
        } else if (value instanceof Long) {
            target.addProperty(key, ((Long) value).intValue());
        } else if (value instanceof Float) {
            target.addProperty(key, (Float) value);
        } else if (value instanceof Double) {
            target.addProperty(key, ((Double) value).floatValue());
        } else if (value instanceof String) {
            target.addProperty(key, (String) value);
        } else if (value instanceof List<?>) {
            JsonArray arr = new JsonArray();
            for (Object item : (List<?>) value) {
                if (item instanceof Boolean) {
                    arr.add(new JsonPrimitive((Boolean) item));
                } else if (item instanceof Number) {
                    arr.add(new JsonPrimitive((Number) item));
                } else if (item != null) {
                    arr.add(new JsonPrimitive(item.toString()));
                }
            }
            target.add(key, arr);
        }
    }
}
