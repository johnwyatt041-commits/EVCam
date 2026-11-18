package com.kooo.evcam.configplaza;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.blindspot.MirrorTarget;
import com.kooo.evcam.surround.SurroundViewMode;
import com.kooo.evcam.ui.PreviewLayoutModel;
import com.kooo.evcam.ui.PreviewMode;
import com.kooo.evcam.ui.UiThemeController;
import com.kooo.evcam.vehicle.rule.VehicleTriggerRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将白名单分区映射到 EC 真实配置存储（AppConfig / UiThemeController / EditablePreviewLayout）。
 */
public final class AndroidE5ConfigTarget implements E5ConfigApplier.ConfigTarget {

    private static final String PREF_PREVIEW_LAYOUT = "e5_preview_layout_v1";
    private static final String[] POSITIONS = {"front", "back", "left", "right"};

    private final Context context;
    private final AppConfig appConfig;
    private final SharedPreferences layoutPrefs;

    public AndroidE5ConfigTarget(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(this.context);
        this.layoutPrefs = this.context.getSharedPreferences(PREF_PREVIEW_LAYOUT, Context.MODE_PRIVATE);
    }

    @NonNull
    @Override
    public String getCurrentCarModel() {
        return appConfig.getCarModel();
    }

    @NonNull
    @Override
    public Map<String, Object> readSectionValues(@NonNull E5ConfigSection section) {
        switch (section) {
            case APPEARANCE:
                return readAppearance();
            case CAMERA_MAPPING:
                return readCameraMapping();
            case RECORDING:
                return readRecording();
            case VEHICLE_RULES_SIGNALS:
                return readVehicleRulesSignals();
            case BLIND_SPOT_HUD:
                return readBlindSpotHud();
            case SURROUND_360:
                return readSurround360();
            default:
                return Collections.emptyMap();
        }
    }

    @Override
    public void writeSectionValues(@NonNull E5ConfigSection section, @NonNull Map<String, Object> values) {
        switch (section) {
            case APPEARANCE:
                writeAppearance(values);
                break;
            case CAMERA_MAPPING:
                writeCameraMapping(values);
                break;
            case RECORDING:
                writeRecording(values);
                break;
            case VEHICLE_RULES_SIGNALS:
                writeVehicleRulesSignals(values);
                break;
            case BLIND_SPOT_HUD:
                writeBlindSpotHud(values);
                break;
            case SURROUND_360:
                writeSurround360(values);
                break;
        }
    }

    private Map<String, Object> readAppearance() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("theme_mode", UiThemeController.savedThemeMode(context).key);
        m.put("ui_scale_mode", appConfig.getUiScaleMode());
        m.put("default_start_page", appConfig.getDefaultStartPage());
        m.put("preview_mode", appConfig.getPreviewMode());
        m.put("preview_presentation", appConfig.getPreviewPresentationKey());
        m.put("corner_nav_mode", appConfig.getPreviewCornerNavMode());
        m.put("view_shortcuts_enabled", appConfig.isPreviewViewShortcutsEnabled());
        m.put("accessibility_overlay_enabled", appConfig.isAccessibilityOverlayEnabled());
        m.put("confirm_door_exit", appConfig.isConfirmDoorExitEnabled());
        m.put("door_exit_delay_seconds", appConfig.getDoorExitDelaySeconds());
        m.put("auto_start_on_boot", appConfig.isAutoStartOnBoot());
        return m;
    }

    private void writeAppearance(@NonNull Map<String, Object> v) {
        UiThemeController.ThemeMode mode = UiThemeController.ThemeMode.fromKey(str(v, "theme_mode", "follow_system"));
        if (mode == null) {
            mode = UiThemeController.ThemeMode.FOLLOW_SYSTEM;
        }
        UiThemeController.saveThemeMode(context, mode);
        appConfig.setUiScaleMode(str(v, "ui_scale_mode", AppConfig.UI_SCALE_STANDARD));
        appConfig.setDefaultStartPage(str(v, "default_start_page", AppConfig.START_PAGE_RECORDING));
        appConfig.setPreviewMode(str(v, "preview_mode", AppConfig.PREVIEW_MODE_B));
        appConfig.setPreviewPresentationKey(str(v, "preview_presentation", "sidebar"));
        appConfig.setPreviewCornerNavMode(str(v, "corner_nav_mode", AppConfig.CORNER_NAV_AUTO));
        appConfig.setPreviewViewShortcutsEnabled(bool(v, "view_shortcuts_enabled", true));
        appConfig.setAccessibilityOverlayEnabled(bool(v, "accessibility_overlay_enabled", true));
        appConfig.setConfirmDoorExitEnabled(bool(v, "confirm_door_exit", false));
        appConfig.setDoorExitDelaySeconds(numInt(v, "door_exit_delay_seconds", AppConfig.DEFAULT_DOOR_EXIT_DELAY_SECONDS));
        appConfig.setAutoStartOnBoot(bool(v, "auto_start_on_boot", true));
    }

    private Map<String, Object> readCameraMapping() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("car_model", appConfig.getCarModel());
        for (String pos : POSITIONS) {
            m.put("camera_" + pos + "_id", appConfig.getCameraId(pos));
            m.put("recording_camera_" + pos + "_enabled", appConfig.isRecordingCameraEnabled(pos));
            m.put("camera_" + pos + "_visible", appConfig.isCameraVisible(pos));
        }
        m.put("mode_b_panes", readPaneList(PreviewMode.MODE_B));
        m.put("mode_b_visible", readModeBVisibleList());
        m.put("mode_c_panes", readPaneList(PreviewMode.MODE_C));
        m.put("mode_c_show_left", layoutPrefs.getBoolean("c_vis_left", true));
        m.put("mode_c_show_right", layoutPrefs.getBoolean("c_vis_right", true));
        m.put("mode_d_panes", readPaneList(PreviewMode.MODE_D));
        return m;
    }

    @SuppressWarnings("unchecked")
    private void writeCameraMapping(@NonNull Map<String, Object> v) {
        appConfig.setCarModel(str(v, "car_model", AppConfig.CAR_MODEL_GALAXY_E5));
        for (String pos : POSITIONS) {
            appConfig.setCameraId(pos, str(v, "camera_" + pos + "_id", appConfig.getCameraId(pos)));
            appConfig.setRecordingCameraEnabled(pos, bool(v, "recording_camera_" + pos + "_enabled", !"front".equals(pos)));
            appConfig.setCameraVisible(pos, bool(v, "camera_" + pos + "_visible", true));
        }

        SharedPreferences.Editor editor = layoutPrefs.edit();
        List<Float> bPanes = (List<Float>) v.get("mode_b_panes");
        if (bPanes != null && bPanes.size() == 16) {
            editor.putBoolean("b_saved", true);
            for (int i = 0; i < 16; i++) {
                editor.putFloat("b_pane_" + i, bPanes.get(i));
            }
        }
        List<Boolean> bVis = (List<Boolean>) v.get("mode_b_visible");
        if (bVis != null && bVis.size() == 4) {
            for (int i = 0; i < 4; i++) {
                editor.putBoolean("b_vis_" + i, Boolean.TRUE.equals(bVis.get(i)));
            }
        }
        List<Float> cPanes = (List<Float>) v.get("mode_c_panes");
        if (cPanes != null && cPanes.size() == 16) {
            editor.putBoolean("c_saved", true);
            for (int i = 0; i < 16; i++) {
                editor.putFloat("c_pane_" + i, cPanes.get(i));
            }
        }
        editor.putBoolean("c_vis_left", bool(v, "mode_c_show_left", true));
        editor.putBoolean("c_vis_right", bool(v, "mode_c_show_right", true));
        List<Float> dPanes = (List<Float>) v.get("mode_d_panes");
        if (dPanes != null && dPanes.size() == 16) {
            editor.putBoolean("d_saved", true);
            for (int i = 0; i < 16; i++) {
                editor.putFloat("d_pane_" + i, dPanes.get(i));
            }
        }
        editor.apply();
    }

    private List<Float> readPaneList(PreviewMode mode) {
        int defaultPreset;
        if (mode == PreviewMode.MODE_D) {
            defaultPreset = PreviewLayoutModel.PRESET_MODE_D_DUAL;
        } else if (mode == PreviewMode.MODE_C) {
            defaultPreset = PreviewLayoutModel.PRESET_MODE_C_FRONT;
        } else {
            defaultPreset = PreviewLayoutModel.PRESET_MODE_B_THREE_COL;
        }
        PreviewLayoutModel model = new PreviewLayoutModel(defaultPreset);
        String prefix = null;
        if (mode == PreviewMode.MODE_B) {
            if (layoutPrefs.getBoolean("b_saved", false)) {
                prefix = "b_pane_";
            } else if (layoutPrefs.getBoolean("saved", false)) {
                prefix = "pane_";
            }
        } else if (mode == PreviewMode.MODE_C) {
            if (layoutPrefs.getBoolean("c_saved", false)) {
                prefix = "c_pane_";
            }
        } else if (mode == PreviewMode.MODE_D && layoutPrefs.getBoolean("d_saved", false)) {
            prefix = "d_pane_";
        }
        if (prefix != null) {
            float[] vals = new float[16];
            for (int i = 0; i < 16; i++) {
                vals[i] = layoutPrefs.getFloat(prefix + i, Float.NaN);
            }
            model.restore(vals);
        }
        float[] snap = model.snapshot();
        List<Float> list = new ArrayList<>(16);
        for (float f : snap) {
            list.add(f);
        }
        return list;
    }

    private List<Boolean> readModeBVisibleList() {
        List<Boolean> list = new ArrayList<>(4);
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            boolean vis = layoutPrefs.getBoolean("b_vis_" + i, true);
            list.add(vis);
            if (vis) any = true;
        }
        if (!any) {
            list.set(PreviewLayoutModel.FRONT, true);
        }
        return list;
    }

    private Map<String, Object> readRecording() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recording_mode", appConfig.getRecordingMode());
        m.put("force_h264_encoding", appConfig.isForceH264Encoding());
        m.put("target_resolution", appConfig.getTargetResolution());
        m.put("bitrate_level", appConfig.getBitrateLevel());
        m.put("framerate_level", appConfig.getFramerateLevel());
        m.put("target_recording_fps", appConfig.getTargetRecordingFps());
        m.put("preview_fps_limit", appConfig.getPreviewFpsLimit());
        m.put("segment_duration_minutes", appConfig.getSegmentDurationMinutes());
        m.put("auto_start_recording", appConfig.isAutoStartRecording());
        m.put("screen_off_recording", appConfig.isScreenOffRecordingEnabled());
        m.put("recording_stats_enabled", appConfig.isRecordingStatsEnabled());
        m.put("keep_camera_connected", appConfig.isKeepCameraConnectedEnabled());
        m.put("camera_recovery_page_enabled", appConfig.isCameraRecoveryEnabled());
        m.put("rd_shift_auto_popup_enabled", appConfig.isRdShiftAutoPopupEnabled());
        m.put("rd_shift_popup_seconds", appConfig.getRdShiftPopupSeconds());
        m.put("storage_location", appConfig.getStorageLocation());
        m.put("relay_write_enabled", appConfig.isRelayWriteEnabled());
        m.put("video_storage_limit_gb", appConfig.getVideoStorageLimitGb());
        m.put("photo_storage_limit_gb", appConfig.getPhotoStorageLimitGb());
        m.put("video_image_adjust_enabled", appConfig.isVideoImageAdjustEnabled());
        m.put("video_image_quality_percent", appConfig.getVideoImageQualityPercent());
        m.put("video_image_brightness_percent", appConfig.getVideoImageBrightnessPercent());
        m.put("video_image_contrast_percent", appConfig.getVideoImageContrastPercent());
        m.put("video_image_saturation_percent", appConfig.getVideoImageSaturationPercent());
        for (String pos : POSITIONS) {
            m.put("video_image_crop_percent_" + pos, appConfig.getVideoImageCropPercent(pos));
            m.put("phys_mirror_h_" + pos, appConfig.isPhysicalMirrorH(pos));
            m.put("phys_mirror_v_" + pos, appConfig.isPhysicalMirrorV(pos));
        }
        m.put("timestamp_watermark_enabled", appConfig.isTimestampWatermarkEnabled());
        m.put("vehicle_status_watermark", appConfig.isVehicleStatusWatermarkEnabled());
        m.put("watermark_speed_enabled", appConfig.isWatermarkSpeedEnabled());
        m.put("watermark_gear_turn_enabled", appConfig.isWatermarkGearTurnEnabled());
        m.put("watermark_gear_enabled", appConfig.isWatermarkGearEnabled());
        m.put("watermark_turn_signal_enabled", appConfig.isWatermarkTurnSignalEnabled());
        m.put("watermark_custom_text_enabled", appConfig.isWatermarkCustomTextEnabled());
        m.put("watermark_custom_text", appConfig.getWatermarkCustomText());
        m.put("video_watermark_position", appConfig.getWatermarkPosition());
        m.put("video_watermark_style", appConfig.getWatermarkStyle());
        m.put("recording_start_sound_mode", appConfig.getRecordStartSoundMode());
        m.put("recording_stop_sound_mode", appConfig.getRecordStopSoundMode());
        m.put("recording_floating_enabled", appConfig.isRecordingFloatingEnabled());
        m.put("recording_floating_button_size", appConfig.getRecordingFloatingButtonSizeDp());
        m.put("recording_floating_time_text_size", appConfig.getRecordingFloatingTimeTextSizeSp());
        m.put("floating_window_enabled", appConfig.isFloatingWindowEnabled());
        m.put("floating_window_size", appConfig.getFloatingWindowSize());
        m.put("floating_window_alpha", appConfig.getFloatingWindowAlpha());
        return m;
    }

    private void writeRecording(@NonNull Map<String, Object> v) {
        appConfig.setRecordingMode(str(v, "recording_mode", AppConfig.RECORDING_MODE_AUTO));
        appConfig.setForceH264Encoding(bool(v, "force_h264_encoding", false));
        appConfig.setTargetResolution(str(v, "target_resolution", AppConfig.RESOLUTION_DEFAULT));
        appConfig.setBitrateLevel(str(v, "bitrate_level", AppConfig.BITRATE_MEDIUM));
        appConfig.setFramerateLevel(str(v, "framerate_level", AppConfig.FRAMERATE_STANDARD));
        appConfig.setTargetRecordingFps(numInt(v, "target_recording_fps", 0));
        appConfig.setPreviewFpsLimit(numInt(v, "preview_fps_limit", 0));
        appConfig.setSegmentDurationMinutes(numInt(v, "segment_duration_minutes", AppConfig.SEGMENT_DURATION_1_MIN));
        appConfig.setAutoStartRecording(bool(v, "auto_start_recording", false));
        appConfig.setScreenOffRecordingEnabled(bool(v, "screen_off_recording", false));
        appConfig.setRecordingStatsEnabled(bool(v, "recording_stats_enabled", true));
        appConfig.setKeepCameraConnectedEnabled(bool(v, "keep_camera_connected", false));
        appConfig.setCameraRecoveryEnabled(bool(v, "camera_recovery_page_enabled", false));
        appConfig.setRdShiftAutoPopupEnabled(bool(v, "rd_shift_auto_popup_enabled", false));
        appConfig.setRdShiftPopupSeconds(numInt(v, "rd_shift_popup_seconds", 5));
        appConfig.setStorageLocation(str(v, "storage_location", AppConfig.STORAGE_INTERNAL));
        appConfig.setRelayWriteEnabled(bool(v, "relay_write_enabled", true));
        appConfig.setVideoStorageLimitGb(numInt(v, "video_storage_limit_gb", 10));
        appConfig.setPhotoStorageLimitGb(numInt(v, "photo_storage_limit_gb", 10));
        appConfig.setVideoImageAdjustEnabled(bool(v, "video_image_adjust_enabled", false));
        appConfig.setVideoImageQualityPercent(numInt(v, "video_image_quality_percent", 100));
        appConfig.setVideoImageBrightnessPercent(numInt(v, "video_image_brightness_percent", 100));
        appConfig.setVideoImageContrastPercent(numInt(v, "video_image_contrast_percent", 100));
        appConfig.setVideoImageSaturationPercent(numInt(v, "video_image_saturation_percent", 100));
        for (String pos : POSITIONS) {
            appConfig.setVideoImageCropPercent(pos, numInt(v, "video_image_crop_percent_" + pos, 0));
            appConfig.setPhysicalMirrorH(pos, bool(v, "phys_mirror_h_" + pos, false));
            appConfig.setPhysicalMirrorV(pos, bool(v, "phys_mirror_v_" + pos, false));
        }
        appConfig.setTimestampWatermarkEnabled(bool(v, "timestamp_watermark_enabled", false));
        boolean hasNewIndependentWatermark = v.containsKey("watermark_gear_enabled")
                || v.containsKey("watermark_turn_signal_enabled");
        boolean legacyMasterWatermark = bool(v, "vehicle_status_watermark", false);
        boolean legacyGearTurnWatermark = bool(v, "watermark_gear_turn_enabled", false);
        boolean speedWatermark = hasNewIndependentWatermark
                ? bool(v, "watermark_speed_enabled", false)
                : (legacyMasterWatermark && bool(v, "watermark_speed_enabled", true));
        boolean gearWatermark = hasNewIndependentWatermark
                ? bool(v, "watermark_gear_enabled", false)
                : (legacyMasterWatermark && legacyGearTurnWatermark);
        boolean turnWatermark = hasNewIndependentWatermark
                ? bool(v, "watermark_turn_signal_enabled", false)
                : (legacyMasterWatermark && legacyGearTurnWatermark);
        appConfig.setWatermarkSpeedEnabled(speedWatermark);
        appConfig.setWatermarkGearEnabled(gearWatermark);
        appConfig.setWatermarkTurnSignalEnabled(turnWatermark);
        appConfig.setWatermarkCustomTextEnabled(bool(v, "watermark_custom_text_enabled", false));
        appConfig.setWatermarkCustomText(str(v, "watermark_custom_text", ""));
        appConfig.setWatermarkPosition(str(v, "video_watermark_position", "BOTTOM_RIGHT"));
        appConfig.setWatermarkStyle(str(v, "video_watermark_style", "CARD"));
        appConfig.setRecordStartSoundMode(str(v, "recording_start_sound_mode", "none"));
        appConfig.setRecordStopSoundMode(str(v, "recording_stop_sound_mode", "none"));
        appConfig.setRecordingFloatingEnabled(bool(v, "recording_floating_enabled", true));
        appConfig.setRecordingFloatingButtonSizeDp(numInt(v, "recording_floating_button_size", 64));
        appConfig.setRecordingFloatingTimeTextSizeSp(numInt(v, "recording_floating_time_text_size", 14));
        appConfig.setFloatingWindowEnabled(bool(v, "floating_window_enabled", false));
        appConfig.setFloatingWindowSize(numInt(v, "floating_window_size", AppConfig.FLOATING_SIZE_MEDIUM));
        appConfig.setFloatingWindowAlpha(numInt(v, "floating_window_alpha", 100));
    }

    private Map<String, Object> readVehicleRulesSignals() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vehicle_trigger_rules", VehicleTriggerRule.toJsonArray(appConfig.getVehicleTriggerRules()));
        m.put("turn_signal_trigger_mode", appConfig.getTurnSignalTriggerMode());
        m.put("t06_vhal_prop_gear_selection", appConfig.getCustomGearSelectionPropId());
        m.put("t06_vhal_prop_current_gear", appConfig.getCustomCurrentGearPropId());
        m.put("t06_vhal_prop_speed", appConfig.getCustomSpeedPropId());
        m.put("t06_vhal_prop_door_pos", appConfig.getCustomDoorPosPropId());
        m.put("t06_vhal_prop_turn_signal", appConfig.getCustomTurnSignalPropId());
        return m;
    }

    private void writeVehicleRulesSignals(@NonNull Map<String, Object> v) {
        String rulesJson = str(v, "vehicle_trigger_rules",
                VehicleTriggerRule.toJsonArray(VehicleTriggerRule.createDefaultRules()));
        appConfig.setVehicleTriggerRules(VehicleTriggerRule.fromJsonArray(rulesJson));
        appConfig.setTurnSignalTriggerMode(str(v, "turn_signal_trigger_mode", AppConfig.TRIGGER_MODE_VHAL_GRPC));
        appConfig.setCustomVhalPropertyIds(
                numInt(v, "t06_vhal_prop_gear_selection", 0),
                numInt(v, "t06_vhal_prop_current_gear", 0),
                numInt(v, "t06_vhal_prop_speed", 0),
                numInt(v, "t06_vhal_prop_door_pos", 0),
                numInt(v, "t06_vhal_prop_turn_signal", 0)
        );
    }

    private Map<String, Object> readBlindSpotHud() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blind_spot_global_enabled", appConfig.isBlindSpotGlobalEnabled());
        m.put("turn_signal_linkage_enabled", appConfig.isTurnSignalLinkageEnabled());
        m.put("turn_signal_timeout", appConfig.getTurnSignalTimeout());
        m.put("turn_signal_reuse_main_floating", appConfig.isTurnSignalReuseMainFloating());
        m.put("main_floating_enabled", appConfig.isMainFloatingEnabled());
        m.put("main_floating_camera", appConfig.getMainFloatingCamera());
        m.put("main_floating_x", appConfig.getMainFloatingX());
        m.put("main_floating_y", appConfig.getMainFloatingY());
        m.put("main_floating_width", appConfig.getMainFloatingWidth());
        m.put("main_floating_height", appConfig.getMainFloatingHeight());
        m.put("main_floating_aspect_ratio_locked", appConfig.isMainFloatingAspectRatioLocked());
        m.put("main_floating_long_press_drag", appConfig.isMainFloatingLongPressDragEnabled());
        m.put("turn_signal_floating_x", appConfig.getTurnSignalFloatingX());
        m.put("turn_signal_floating_y", appConfig.getTurnSignalFloatingY());
        m.put("turn_signal_floating_width", appConfig.getTurnSignalFloatingWidth());
        m.put("turn_signal_floating_height", appConfig.getTurnSignalFloatingHeight());
        m.put("turn_signal_floating_rotation", appConfig.getTurnSignalFloatingRotation());
        m.put("t04_standalone_window_camera", appConfig.getStandaloneWindowCamera());
        m.put("t04_standalone_window_x", appConfig.getStandaloneWindowX());
        m.put("t04_standalone_window_y", appConfig.getStandaloneWindowY());
        m.put("t04_standalone_window_width", appConfig.getStandaloneWindowWidth());
        m.put("t04_standalone_window_height", appConfig.getStandaloneWindowHeight());
        m.put("t04_blind_spot_suppress_on_360", appConfig.isBlindSpotSuppressOn360());
        m.put("avm_avoidance_enabled", appConfig.isAvmAvoidanceEnabled());
        m.put("avm_avoidance_activity", appConfig.getAvmAvoidanceActivity());
        m.put("blind_spot_use_same_side_position", appConfig.isBlindSpotUseSameSidePosition());
        m.put("blind_spot_correction_enabled", appConfig.isBlindSpotCorrectionEnabled());
        for (String pos : POSITIONS) {
            m.put("blind_spot_correction_" + pos + "_scale_x", appConfig.getBlindSpotCorrectionScaleX(pos));
            m.put("blind_spot_correction_" + pos + "_scale_y", appConfig.getBlindSpotCorrectionScaleY(pos));
            m.put("blind_spot_correction_" + pos + "_translate_x", appConfig.getBlindSpotCorrectionTranslateX(pos));
            m.put("blind_spot_correction_" + pos + "_translate_y", appConfig.getBlindSpotCorrectionTranslateY(pos));
            m.put("blind_spot_correction_" + pos + "_rotation", appConfig.getBlindSpotCorrectionRotation(pos));
            m.put("blind_spot_correction_" + pos + "_crop_left", appConfig.getBlindSpotCorrectionCropLeft(pos));
            m.put("blind_spot_correction_" + pos + "_crop_top", appConfig.getBlindSpotCorrectionCropTop(pos));
            m.put("blind_spot_correction_" + pos + "_crop_right", appConfig.getBlindSpotCorrectionCropRight(pos));
            m.put("blind_spot_correction_" + pos + "_crop_bottom", appConfig.getBlindSpotCorrectionCropBottom(pos));
        }
        for (MirrorTarget target : MirrorTarget.values()) {
            for (String pos : POSITIONS) {
                m.put("out_mirror_" + target.getKey() + "_" + pos + "_h", appConfig.isOutputMirrorH(target, pos));
                m.put("out_mirror_" + target.getKey() + "_" + pos + "_v", appConfig.isOutputMirrorV(target, pos));
            }
        }
        m.put("secondary_display_enabled", appConfig.isSecondaryDisplayEnabled());
        m.put("secondary_display_camera", appConfig.getSecondaryDisplayCamera());
        m.put("secondary_display_id", appConfig.getSecondaryDisplayId());
        m.put("secondary_display_x", appConfig.getSecondaryDisplayX());
        m.put("secondary_display_y", appConfig.getSecondaryDisplayY());
        m.put("secondary_display_width", appConfig.getSecondaryDisplayWidth());
        m.put("secondary_display_height", appConfig.getSecondaryDisplayHeight());
        m.put("secondary_display_rotation", appConfig.getSecondaryDisplayRotation());
        m.put("secondary_display_border", appConfig.isSecondaryDisplayBorderEnabled());
        m.put("secondary_display_orientation", appConfig.getSecondaryDisplayOrientation());
        m.put("secondary_display_alpha", appConfig.getSecondaryDisplayAlpha());
        m.put("mjpeg_stream_enabled", appConfig.isMjpegStreamEnabled());
        m.put("mjpeg_stream_camera", appConfig.getMjpegStreamCamera());
        m.put("mjpeg_stream_port", appConfig.getMjpegStreamPort());
        m.put("mjpeg_stream_width", appConfig.getMjpegStreamWidth());
        m.put("mjpeg_stream_height", appConfig.getMjpegStreamHeight());
        m.put("mjpeg_stream_quality", appConfig.getMjpegStreamQuality());
        m.put("mjpeg_stream_pan_x", appConfig.getMjpegStreamPanX());
        m.put("mjpeg_stream_pan_y", appConfig.getMjpegStreamPanY());
        m.put("mjpeg_stream_cover_scale", appConfig.getMjpegStreamCoverScale());
        m.put("mjpeg_stream_linkage_mode", appConfig.isMjpegStreamLinkageMode());
        m.put("mjpeg_stream_protocol", appConfig.getMjpegStreamProtocol());
        m.put("mjpeg_stream_mode", appConfig.getMjpegStreamMode());
        m.put("mjpeg_stream_client_host", appConfig.getMjpegStreamClientHost());
        m.put("mjpeg_stream_client_port", appConfig.getMjpegStreamClientPort());
        m.put("mjpeg_stream_auto_discover", appConfig.isMjpegStreamAutoDiscover());
        return m;
    }

    private void writeBlindSpotHud(@NonNull Map<String, Object> v) {
        appConfig.setBlindSpotGlobalEnabled(bool(v, "blind_spot_global_enabled", false));
        appConfig.setTurnSignalLinkageEnabled(bool(v, "turn_signal_linkage_enabled", false));
        appConfig.setTurnSignalTimeout(numInt(v, "turn_signal_timeout", 10));
        appConfig.setTurnSignalReuseMainFloating(bool(v, "turn_signal_reuse_main_floating", true));
        appConfig.setMainFloatingEnabled(bool(v, "main_floating_enabled", false));
        appConfig.setMainFloatingCamera(str(v, "main_floating_camera", "front"));
        appConfig.setMainFloatingBounds(
                numInt(v, "main_floating_x", 100),
                numInt(v, "main_floating_y", 100),
                numInt(v, "main_floating_width", 480),
                numInt(v, "main_floating_height", 320));
        appConfig.setMainFloatingAspectRatioLocked(bool(v, "main_floating_aspect_ratio_locked", false));
        appConfig.setMainFloatingLongPressDragEnabled(bool(v, "main_floating_long_press_drag", false));
        appConfig.setTurnSignalFloatingBounds(
                numInt(v, "turn_signal_floating_x", 200),
                numInt(v, "turn_signal_floating_y", 200),
                numInt(v, "turn_signal_floating_width", 640),
                numInt(v, "turn_signal_floating_height", 360));
        appConfig.setTurnSignalFloatingRotation(numInt(v, "turn_signal_floating_rotation", 0));
        appConfig.setStandaloneWindowCamera(str(v, "t04_standalone_window_camera", "front"));
        appConfig.setStandaloneWindowPosition(
                numInt(v, "t04_standalone_window_x", AppConfig.DEFAULT_STANDALONE_WINDOW_X),
                numInt(v, "t04_standalone_window_y", AppConfig.DEFAULT_STANDALONE_WINDOW_Y));
        appConfig.setStandaloneWindowSize(
                numInt(v, "t04_standalone_window_width", AppConfig.DEFAULT_STANDALONE_WINDOW_WIDTH),
                numInt(v, "t04_standalone_window_height", AppConfig.DEFAULT_STANDALONE_WINDOW_HEIGHT));
        appConfig.setBlindSpotSuppressOn360(bool(v, "t04_blind_spot_suppress_on_360", true));
        appConfig.setAvmAvoidanceEnabled(bool(v, "avm_avoidance_enabled", false));
        appConfig.setAvmAvoidanceActivity(str(v, "avm_avoidance_activity", "com.geely.avm_app.AvmRenderActivity"));
        appConfig.setBlindSpotUseSameSidePosition(bool(v, "blind_spot_use_same_side_position", false));
        appConfig.setBlindSpotCorrectionEnabled(bool(v, "blind_spot_correction_enabled", false));
        for (String pos : POSITIONS) {
            appConfig.setBlindSpotCorrectionScaleX(pos, numFloat(v, "blind_spot_correction_" + pos + "_scale_x", 1.0f));
            appConfig.setBlindSpotCorrectionScaleY(pos, numFloat(v, "blind_spot_correction_" + pos + "_scale_y", 1.0f));
            appConfig.setBlindSpotCorrectionTranslateX(pos, numFloat(v, "blind_spot_correction_" + pos + "_translate_x", 0.0f));
            appConfig.setBlindSpotCorrectionTranslateY(pos, numFloat(v, "blind_spot_correction_" + pos + "_translate_y", 0.0f));
            appConfig.setBlindSpotCorrectionRotation(pos, numInt(v, "blind_spot_correction_" + pos + "_rotation", 0));
            appConfig.setBlindSpotCorrectionCropLeft(pos, numFloat(v, "blind_spot_correction_" + pos + "_crop_left", 0.0f));
            appConfig.setBlindSpotCorrectionCropTop(pos, numFloat(v, "blind_spot_correction_" + pos + "_crop_top", 0.0f));
            appConfig.setBlindSpotCorrectionCropRight(pos, numFloat(v, "blind_spot_correction_" + pos + "_crop_right", 0.0f));
            appConfig.setBlindSpotCorrectionCropBottom(pos, numFloat(v, "blind_spot_correction_" + pos + "_crop_bottom", 0.0f));
        }
        for (MirrorTarget target : MirrorTarget.values()) {
            for (String pos : POSITIONS) {
                boolean defaultH = target == MirrorTarget.MAIN_PREVIEW && "back".equals(pos);
                appConfig.setOutputMirrorH(target, pos,
                        bool(v, "out_mirror_" + target.getKey() + "_" + pos + "_h", defaultH));
                appConfig.setOutputMirrorV(target, pos,
                        bool(v, "out_mirror_" + target.getKey() + "_" + pos + "_v", false));
            }
        }
        appConfig.setSecondaryDisplayEnabled(bool(v, "secondary_display_enabled", false));
        appConfig.setSecondaryDisplayCamera(str(v, "secondary_display_camera", "front"));
        appConfig.setSecondaryDisplayId(numInt(v, "secondary_display_id", 0));
        appConfig.setSecondaryDisplayBounds(
                numInt(v, "secondary_display_x", 0),
                numInt(v, "secondary_display_y", 139),
                numInt(v, "secondary_display_width", 318),
                numInt(v, "secondary_display_height", 236));
        appConfig.setSecondaryDisplayRotation(numInt(v, "secondary_display_rotation", 0));
        appConfig.setSecondaryDisplayBorderEnabled(bool(v, "secondary_display_border", false));
        appConfig.setSecondaryDisplayOrientation(numInt(v, "secondary_display_orientation", 180));
        appConfig.setSecondaryDisplayAlpha(numInt(v, "secondary_display_alpha", 100));
        appConfig.setMjpegStreamEnabled(bool(v, "mjpeg_stream_enabled", false));
        appConfig.setMjpegStreamCamera(str(v, "mjpeg_stream_camera", "left"));
        appConfig.setMjpegStreamPort(numInt(v, "mjpeg_stream_port", 29543));
        appConfig.setMjpegStreamWidth(numInt(v, "mjpeg_stream_width", 820));
        appConfig.setMjpegStreamHeight(numInt(v, "mjpeg_stream_height", 320));
        appConfig.setMjpegStreamQuality(numInt(v, "mjpeg_stream_quality", 30));
        appConfig.setMjpegStreamPanX(numFloat(v, "mjpeg_stream_pan_x", 0.5f));
        appConfig.setMjpegStreamPanY(numFloat(v, "mjpeg_stream_pan_y", 0.5f));
        appConfig.setMjpegStreamCoverScale(numFloat(v, "mjpeg_stream_cover_scale", 1.0f));
        appConfig.setMjpegStreamLinkageMode(bool(v, "mjpeg_stream_linkage_mode", false));
        appConfig.setMjpegStreamProtocol(str(v, "mjpeg_stream_protocol", "HTTP"));
        appConfig.setMjpegStreamMode(str(v, "mjpeg_stream_mode", "SERVER"));
        appConfig.setMjpegStreamClientHost(str(v, "mjpeg_stream_client_host", ""));
        appConfig.setMjpegStreamClientPort(numInt(v, "mjpeg_stream_client_port", 8080));
        appConfig.setMjpegStreamAutoDiscover(bool(v, "mjpeg_stream_auto_discover", true));
    }

    private Map<String, Object> readSurround360() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("surround_top_view", appConfig.getSurroundTopView().name());
        m.put("surround_bottom_view", appConfig.getSurroundBottomView().name());
        m.put("surround_wheels_collapsed", appConfig.isSurroundWheelsCollapsed());
        m.put("surround_stretch_road_views", appConfig.isSurroundStretchRoadViews());
        m.put("surround_overlay_buttons_visible", appConfig.isSurroundOverlayButtonsVisible());
        m.put("reverse_preview_enabled", appConfig.isReversePreviewEnabled());
        m.put("reverse_preview_delay_ms", appConfig.getReversePreviewDelayMs());
        m.put("preview_speed_close_enabled", appConfig.isPreviewSpeedCloseEnabled());
        m.put("preview_speed_threshold_kmh", appConfig.getPreviewSpeedThresholdKmh());
        m.put("park_preview_close_enabled", appConfig.isParkPreviewCloseEnabled());
        m.put("park_preview_close_delay_seconds", appConfig.getParkPreviewCloseDelaySeconds());
        m.put("surround_ground_width_cm", appConfig.getSurroundGroundWidthCm());
        m.put("surround_ground_length_cm", appConfig.getSurroundGroundLengthCm());
        m.put("surround_ground_front_ext_cm", appConfig.getSurroundGroundFrontExtensionCm());
        m.put("preview_guidance_enabled", appConfig.isPreviewGuidanceEnabled());
        return m;
    }

    private void writeSurround360(@NonNull Map<String, Object> v) {
        SurroundViewMode top = SurroundViewMode.fromKey(str(v, "surround_top_view", "DRIVE"));
        SurroundViewMode bottom = SurroundViewMode.fromKey(str(v, "surround_bottom_view", "FRONT_WHEELS"));
        appConfig.setSurroundDetailViews(top, bottom);
        appConfig.setSurroundWheelsCollapsed(bool(v, "surround_wheels_collapsed", false));
        appConfig.setSurroundStretchRoadViews(bool(v, "surround_stretch_road_views", false));
        appConfig.setSurroundOverlayButtonsVisible(bool(v, "surround_overlay_buttons_visible", true));
        appConfig.setReversePreviewEnabled(bool(v, "reverse_preview_enabled", false));
        appConfig.setReversePreviewDelayMs(numInt(v, "reverse_preview_delay_ms", AppConfig.DEFAULT_REVERSE_PREVIEW_DELAY_MS));
        appConfig.setPreviewSpeedCloseEnabled(bool(v, "preview_speed_close_enabled", false));
        appConfig.setPreviewSpeedThresholdKmh(numInt(v, "preview_speed_threshold_kmh", AppConfig.DEFAULT_SPEED_CLOSE_KMH));
        appConfig.setParkPreviewCloseEnabled(bool(v, "park_preview_close_enabled", false));
        appConfig.setParkPreviewCloseDelaySeconds(
                numInt(v, "park_preview_close_delay_seconds", AppConfig.DEFAULT_PARK_CLOSE_SECONDS));
        appConfig.setSurroundGroundWidthCm(numInt(v, "surround_ground_width_cm", AppConfig.DEFAULT_SURROUND_GROUND_WIDTH_CM));
        appConfig.setSurroundGroundLengthCm(numInt(v, "surround_ground_length_cm", AppConfig.DEFAULT_SURROUND_GROUND_LENGTH_CM));
        appConfig.setSurroundGroundFrontExtensionCm(
                numInt(v, "surround_ground_front_ext_cm", AppConfig.DEFAULT_SURROUND_GROUND_FRONT_EXT_CM));
        appConfig.setPreviewGuidanceEnabled(bool(v, "preview_guidance_enabled", true));
    }

    private static boolean bool(@NonNull Map<String, Object> m, @NonNull String key, boolean fallback) {
        Object o = m.get(key);
        return o instanceof Boolean ? (Boolean) o : fallback;
    }

    private static int numInt(@NonNull Map<String, Object> m, @NonNull String key, int fallback) {
        Object o = m.get(key);
        return o instanceof Number ? ((Number) o).intValue() : fallback;
    }

    private static float numFloat(@NonNull Map<String, Object> m, @NonNull String key, float fallback) {
        Object o = m.get(key);
        return o instanceof Number ? ((Number) o).floatValue() : fallback;
    }

    @NonNull
    private static String str(@NonNull Map<String, Object> m, @NonNull String key, @NonNull String fallback) {
        Object o = m.get(key);
        return o != null ? String.valueOf(o) : fallback;
    }
}
