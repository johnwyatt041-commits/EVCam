package com.kooo.evcam.configplaza;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.camera.RecordingSoundPlayer;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 12. 银河 E5 离线配置广场纯 JVM 单元测试：
 * 覆盖内置 E5 预设校验、非 E5/越界/未知字段/敏感字段拒绝、应用前原子备份与一键回滚。
 */
public class E5ConfigPlazaTest {

    private static final List<String> BUILTIN_FILES = Arrays.asList(
            "e5_balanced_daily.json",
            "e5_low_power_smooth.json",
            "e5_surround_reverse_enhanced.json"
    );

    @Test
    public void builtinE5PresetsAllPassStrictSchemaValidation() throws Exception {
        File dir = locatePresetAssetsDir();
        assertTrue("Preset assets directory should exist: " + dir.getAbsolutePath(), dir.isDirectory());

        for (String name : BUILTIN_FILES) {
            File file = new File(dir, name);
            assertTrue("Missing preset file: " + file.getAbsolutePath(), file.isFile());
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            E5ConfigSchema.ValidationResult result = E5ConfigSchema.parseAndValidate(raw);
            assertTrue("Preset " + name + " should pass validation: " + result.errorMessage, result.valid);
            assertNotNull(result.snapshot);
            assertEquals(E5ConfigSection.SCHEMA_VERSION, result.snapshot.schemaVersion);
            assertEquals(E5ConfigSection.VEHICLE_MODEL_GALAXY_E5, result.snapshot.vehicleModel);
            assertEquals(E5ConfigSection.values().length, result.snapshot.sections.size());
        }
    }

    @Test
    public void rejectsUnsupportedVehicleModelAndSchemaVersion() throws Exception {
        JsonObject base = loadBalancedPresetJson();

        JsonObject lynk08 = base.deepCopy();
        lynk08.addProperty("vehicleModel", "lynk_08");
        E5ConfigSchema.ValidationResult r1 = E5ConfigSchema.validateJsonObject(lynk08);
        assertFalse(r1.valid);
        assertTrue(r1.errorMessage != null && r1.errorMessage.contains("galaxy_e5"));

        JsonObject l7 = base.deepCopy();
        l7.addProperty("vehicleModel", "galaxy_l7");
        assertFalse(E5ConfigSchema.validateJsonObject(l7).valid);

        JsonObject badVersion = base.deepCopy();
        badVersion.addProperty("schemaVersion", 2);
        assertFalse(E5ConfigSchema.validateJsonObject(badVersion).valid);
    }

    @Test
    public void rejectsRotarySectionAndCredentialOrUnknownFields() throws Exception {
        JsonObject base = loadBalancedPresetJson();

        JsonObject withRotary = base.deepCopy();
        JsonObject rotarySec = new JsonObject();
        rotarySec.addProperty("version", 1);
        JsonObject rotaryVals = new JsonObject();
        rotaryVals.addProperty("enabled", true);
        rotarySec.add("values", rotaryVals);
        withRotary.getAsJsonObject("sections").add("rotary_controls", rotarySec);
        E5ConfigSchema.ValidationResult rRotary = E5ConfigSchema.validateJsonObject(withRotary);
        assertFalse(rRotary.valid);
        assertTrue(rRotary.errorMessage != null && rRotary.errorMessage.contains("rotary_controls"));

        JsonObject withSecret = base.deepCopy();
        withSecret.getAsJsonObject("sections")
                .getAsJsonObject("appearance")
                .getAsJsonObject("values")
                .addProperty("feishu_app_secret", "cli_leak_12345");
        E5ConfigSchema.ValidationResult rSecret = E5ConfigSchema.validateJsonObject(withSecret);
        assertFalse(rSecret.valid);
        assertTrue(rSecret.errorMessage != null && rSecret.errorMessage.contains("feishu_app_secret"));
    }

    @Test
    public void rejectsOutOfRangeAndCrossFieldViolations() throws Exception {
        JsonObject base = loadBalancedPresetJson();

        // Out-of-range FPS
        JsonObject badFps = base.deepCopy();
        badFps.getAsJsonObject("sections")
                .getAsJsonObject("recording")
                .getAsJsonObject("values")
                .addProperty("target_recording_fps", 120);
        assertFalse(E5ConfigSchema.validateJsonObject(badFps).valid);

        // Duplicate camera IDs
        JsonObject dupCam = base.deepCopy();
        JsonObject camVals = dupCam.getAsJsonObject("sections")
                .getAsJsonObject("camera_mapping")
                .getAsJsonObject("values");
        camVals.addProperty("camera_front_id", "1");
        camVals.addProperty("camera_back_id", "1");
        assertFalse(E5ConfigSchema.validateJsonObject(dupCam).valid);

        // Zero enabled recording cameras
        JsonObject zeroRec = base.deepCopy();
        JsonObject zCamVals = zeroRec.getAsJsonObject("sections")
                .getAsJsonObject("camera_mapping")
                .getAsJsonObject("values");
        zCamVals.addProperty("recording_camera_front_enabled", false);
        zCamVals.addProperty("recording_camera_back_enabled", false);
        zCamVals.addProperty("recording_camera_left_enabled", false);
        zCamVals.addProperty("recording_camera_right_enabled", false);
        assertFalse(E5ConfigSchema.validateJsonObject(zeroRec).valid);

        // Invalid PreviewLayoutModel pane (< 0.18 min size)
        JsonObject badPane = base.deepCopy();
        JsonArray tinyPane = new JsonArray();
        for (int i = 0; i < 4; i++) {
            tinyPane.add(0.0f);
            tinyPane.add(0.0f);
            tinyPane.add(0.05f);
            tinyPane.add(0.5f);
        }
        badPane.getAsJsonObject("sections")
                .getAsJsonObject("camera_mapping")
                .getAsJsonObject("values")
                .add("mode_b_panes", tinyPane);
        assertFalse(E5ConfigSchema.validateJsonObject(badPane).valid);

        // Non-whitelisted vehicle rule signal
        JsonObject badRule = base.deepCopy();
        badRule.getAsJsonObject("sections")
                .getAsJsonObject("vehicle_rules_signals")
                .getAsJsonObject("values")
                .addProperty("vehicle_trigger_rules",
                        "[{\"primary\":{\"signal\":\"ENGINE_RPM\",\"comparison\":\"EQUALS\",\"expected\":1},\"action\":\"START_RECORDING\"}]");
        assertFalse(E5ConfigSchema.validateJsonObject(badRule).valid);
    }

    @Test
    public void exportNormalizesCustomSoundAndApplySupportsBackupRestoreRoundTrip() throws Exception {
        InMemoryConfigTarget target = InMemoryConfigTarget.createDefaultE5Target();
        target.put(E5ConfigSection.RECORDING, "recording_start_sound_mode", RecordingSoundPlayer.MODE_CUSTOM);
        target.put(E5ConfigSection.RECORDING, "target_recording_fps", 25);
        target.put(E5ConfigSection.APPEARANCE, "theme_mode", "follow_system");

        InMemoryBackupStore backupStore = new InMemoryBackupStore();
        E5ConfigApplier applier = new E5ConfigApplier(target, backupStore);

        // Export only RECORDING + APPEARANCE
        E5ConfigApplier.ExportResult exp = applier.exportCurrent(
                "local_test",
                "本地快照测试",
                "desc",
                "tester",
                "2026-11-17 00:00:00",
                EnumSet.of(E5ConfigSection.APPEARANCE, E5ConfigSection.RECORDING)
        );
        assertTrue(exp.errorMessage, exp.success);
        assertNotNull(exp.snapshot);
        assertEquals(2, exp.snapshot.sections.size());
        assertFalse(exp.warnings.isEmpty());
        assertEquals(
                RecordingSoundPlayer.MODE_SYSTEM,
                exp.snapshot.getSection(E5ConfigSection.RECORDING).values.get("recording_start_sound_mode")
        );

        // Load low_power preset and apply ONLY RECORDING + SURROUND_360 + APPEARANCE (leave BLIND_SPOT untouched)
        E5ConfigSnapshot preset = parseAsset("e5_low_power_smooth.json");
        target.put(E5ConfigSection.BLIND_SPOT_HUD, "blind_spot_global_enabled", true);

        E5ConfigApplier.ApplyResult applyRes = applier.applySnapshot(
                preset,
                EnumSet.of(E5ConfigSection.APPEARANCE, E5ConfigSection.RECORDING, E5ConfigSection.SURROUND_360),
                "2026-11-17 00:01:00"
        );
        assertTrue(applyRes.errorMessage, applyRes.success);
        assertTrue(applyRes.backupSaved);
        assertTrue(applyRes.themeChanged);
        assertTrue(applyRes.surroundChanged);
        assertFalse(applyRes.cameraMappingChanged);
        assertEquals(20, target.readSectionValues(E5ConfigSection.RECORDING).get("target_recording_fps"));
        assertEquals("dark", target.readSectionValues(E5ConfigSection.APPEARANCE).get("theme_mode"));
        // Unselected section preserved
        assertEquals(Boolean.TRUE, target.readSectionValues(E5ConfigSection.BLIND_SPOT_HUD).get("blind_spot_global_enabled"));

        // Verify BackupRecord JSON persistence round-trip
        E5ConfigApplier.BackupRecord savedBackup = backupStore.loadBackup();
        assertNotNull(savedBackup);
        E5ConfigApplier.BackupRecord parsedBackup =
                E5ConfigApplier.BackupRecord.fromJsonString(savedBackup.toJsonString());
        assertNotNull(parsedBackup);
        backupStore.saveBackup(parsedBackup);

        // Restore last backup -> original values return
        E5ConfigApplier.ApplyResult restoreRes = applier.restoreLastBackup();
        assertTrue(restoreRes.errorMessage, restoreRes.success);
        assertFalse(restoreRes.backupSaved);
        assertEquals(25, target.readSectionValues(E5ConfigSection.RECORDING).get("target_recording_fps"));
        assertEquals("follow_system", target.readSectionValues(E5ConfigSection.APPEARANCE).get("theme_mode"));
        assertNull(restoreRes.errorMessage);
    }

    @Test
    public void legacyRecordingPresetMigratesIndependentWatermarkSwitchesWithoutNewKeys() throws Exception {
        JsonObject base = loadBalancedPresetJson();
        JsonObject recVals = base.getAsJsonObject("sections")
                .getAsJsonObject("recording")
                .getAsJsonObject("values");
        recVals.remove("watermark_gear_enabled");
        recVals.remove("watermark_turn_signal_enabled");
        recVals.addProperty("vehicle_status_watermark", true);
        recVals.addProperty("watermark_speed_enabled", false);
        recVals.addProperty("watermark_gear_turn_enabled", true);

        E5ConfigSchema.ValidationResult res = E5ConfigSchema.validateJsonObject(base);
        assertTrue(res.errorMessage, res.valid && res.snapshot != null);
        Map<String, Object> parsed = res.snapshot.getSection(E5ConfigSection.RECORDING).values;
        assertEquals(Boolean.FALSE, parsed.get("watermark_speed_enabled"));
        assertEquals(Boolean.TRUE, parsed.get("watermark_gear_enabled"));
        assertEquals(Boolean.TRUE, parsed.get("watermark_turn_signal_enabled"));
    }

    @Test
    public void rejectsExportWhenCarModelIsNotGalaxyE5AndCameraMappingSelected() {
        InMemoryConfigTarget l7Target = InMemoryConfigTarget.createDefaultE5Target();
        l7Target.carModel = AppConfig.CAR_MODEL_L7;
        E5ConfigApplier applier = new E5ConfigApplier(l7Target, new InMemoryBackupStore());

        E5ConfigApplier.ExportResult res = applier.exportCurrent(
                "bad_l7",
                "L7",
                "",
                "",
                "2026-11-17 00:00:00",
                Collections.singleton(E5ConfigSection.CAMERA_MAPPING)
        );
        assertFalse(res.success);
    }

    private static File locatePresetAssetsDir() {
        File candidate1 = new File("src/main/assets/config-plaza/e5-presets");
        if (candidate1.isDirectory()) {
            return candidate1;
        }
        return new File("app/src/main/assets/config-plaza/e5-presets");
    }

    private static JsonObject loadBalancedPresetJson() throws Exception {
        File file = new File(locatePresetAssetsDir(), "e5_balanced_daily.json");
        String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    private static E5ConfigSnapshot parseAsset(String filename) throws Exception {
        File file = new File(locatePresetAssetsDir(), filename);
        String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        E5ConfigSchema.ValidationResult res = E5ConfigSchema.parseAndValidate(raw);
        assertTrue(res.errorMessage, res.valid && res.snapshot != null);
        return res.snapshot;
    }

    private static final class InMemoryBackupStore implements E5ConfigApplier.BackupStore {
        private E5ConfigApplier.BackupRecord stored;

        @Override
        public boolean saveBackup(E5ConfigApplier.BackupRecord record) {
            this.stored = record;
            return true;
        }

        @Override
        public E5ConfigApplier.BackupRecord loadBackup() {
            return stored;
        }

        @Override
        public boolean hasBackup() {
            return stored != null;
        }
    }

    private static final class InMemoryConfigTarget implements E5ConfigApplier.ConfigTarget {
        String carModel = AppConfig.CAR_MODEL_GALAXY_E5;
        final Map<E5ConfigSection, Map<String, Object>> store = new EnumMap<>(E5ConfigSection.class);

        static InMemoryConfigTarget createDefaultE5Target() {
            InMemoryConfigTarget t = new InMemoryConfigTarget();
            for (E5ConfigSection sec : E5ConfigSection.values()) {
                Map<String, Object> defaults = new LinkedHashMap<>();
                for (Map.Entry<String, E5ConfigSchema.FieldSpec> e : E5ConfigSchema.getFields(sec).entrySet()) {
                    defaults.put(e.getKey(), e.getValue().defaultValue);
                }
                t.store.put(sec, defaults);
            }
            return t;
        }

        void put(E5ConfigSection sec, String key, Object val) {
            store.get(sec).put(key, val);
        }

        @Override
        public String getCurrentCarModel() {
            return carModel;
        }

        @Override
        public Map<String, Object> readSectionValues(E5ConfigSection section) {
            return new LinkedHashMap<>(store.get(section));
        }

        @Override
        public void writeSectionValues(E5ConfigSection section, Map<String, Object> values) {
            store.put(section, new LinkedHashMap<>(values));
        }
    }
}
