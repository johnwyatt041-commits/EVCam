package com.kooo.evcam.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.playback.PlaybackHubFragment;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SettingsCatalogAndAppearanceTest {

    private AppConfig createAppConfig(Map<String, Object> backingStore) {
        Context context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);

        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(editor);
        when(prefs.contains(anyString())).thenAnswer(inv -> backingStore.containsKey(inv.getArgument(0)));
        when(prefs.getString(anyString(), nullable(String.class))).thenAnswer(inv -> {
            Object val = backingStore.get(inv.getArgument(0));
            return val instanceof String ? (String) val : inv.getArgument(1);
        });
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            Object val = backingStore.get(inv.getArgument(0));
            return val instanceof Boolean ? (Boolean) val : inv.getArgument(1);
        });
        when(prefs.getInt(anyString(), anyInt())).thenAnswer(inv -> {
            Object val = backingStore.get(inv.getArgument(0));
            return val instanceof Integer ? (Integer) val : inv.getArgument(1);
        });
        when(prefs.getFloat(anyString(), anyFloat())).thenAnswer(inv -> {
            Object val = backingStore.get(inv.getArgument(0));
            return val instanceof Float ? (Float) val : inv.getArgument(1);
        });
        when(editor.putString(anyString(), nullable(String.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            if (value == null) backingStore.remove(key);
            else backingStore.put(key, value);
            return editor;
        });
        when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            backingStore.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.putInt(anyString(), anyInt())).thenAnswer(inv -> {
            backingStore.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.putFloat(anyString(), anyFloat())).thenAnswer(inv -> {
            backingStore.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.remove(anyString())).thenAnswer(inv -> {
            backingStore.remove(inv.getArgument(0));
            return editor;
        });
        return new AppConfig(context);
    }

    @Test
    public void catalogShowsOnlyImplementedEntriesWithoutShellsOrExcludedCategories() {
        List<SettingsCatalog.Entry> entries = SettingsCatalog.getImplementedEntries();
        assertEquals(10, entries.size());

        List<String> actualTitles = new ArrayList<>();
        List<String> actualDisplayTitles = new ArrayList<>();
        for (SettingsCatalog.Entry entry : entries) {
            actualTitles.add(entry.title);
            actualDisplayTitles.add(entry.displayTitle);
            assertNotEquals(0, entry.iconResId);
            assertFalse(entry.title.contains("屏幕尺寸"));
            assertFalse(entry.title.contains("外设"));
            assertFalse(entry.title.startsWith("7."));
            assertFalse(entry.title.startsWith("8."));
            assertFalse(entry.title.startsWith("9."));
            assertFalse(entry.title.contains("投票"));
        }

        assertEquals(Arrays.asList(
                "1. 外观与交互",
                "3. 记录仪",
                "4. 360 画面",
                "5. 补盲",
                "10. 信号预设与匹配",
                "11. 飞书远程",
                "12. 配置广场",
                "13. 系统与权限",
                "14. 软件更新",
                "15. Debug 日志"
        ), actualTitles);

        assertEquals(Arrays.asList(
                "外观与交互",
                "记录仪",
                "360 画面",
                "补盲",
                "信号预设与匹配",
                "飞书远程",
                "配置广场",
                "系统与权限",
                "软件更新",
                "Debug 日志"
        ), actualDisplayTitles);
    }

    @Test
    public void defaultStartPageNormalizesAndPersists() {
        Map<String, Object> store = new HashMap<>();
        AppConfig config = createAppConfig(store);

        assertEquals(AppConfig.START_PAGE_RECORDING, config.getDefaultStartPage());
        assertEquals(AppConfig.START_PAGE_RECORDING, AppConfig.normalizeDefaultStartPage("unknown"));

        config.setDefaultStartPage(AppConfig.START_PAGE_PLAYBACK);
        assertEquals(AppConfig.START_PAGE_PLAYBACK, config.getDefaultStartPage());

        config.setDefaultStartPage(AppConfig.START_PAGE_SETTINGS);
        assertEquals(AppConfig.START_PAGE_SETTINGS, config.getDefaultStartPage());

        config.setDefaultStartPage("legacy_invalid");
        assertEquals(AppConfig.START_PAGE_RECORDING, config.getDefaultStartPage());
    }

    @Test
    public void confirmDoorExitDefaultsFalseAndPersists() {
        Map<String, Object> store = new HashMap<>();
        AppConfig config = createAppConfig(store);

        assertFalse(config.isConfirmDoorExitEnabled());
        config.setConfirmDoorExitEnabled(true);
        assertTrue(config.isConfirmDoorExitEnabled());
        assertEquals(Boolean.TRUE, store.get("confirm_door_exit"));

        config.setConfirmDoorExitEnabled(false);
        assertFalse(config.isConfirmDoorExitEnabled());
    }

    @Test
    public void doorExitDelaySecondsNormalizesAndPersists() {
        Map<String, Object> store = new HashMap<>();
        AppConfig config = createAppConfig(store);

        assertEquals(AppConfig.DEFAULT_DOOR_EXIT_DELAY_SECONDS, config.getDoorExitDelaySeconds());
        assertEquals(5, AppConfig.normalizeDoorExitDelaySeconds(99));
        assertEquals(5, AppConfig.normalizeDoorExitDelaySeconds(-1));

        for (int valid : new int[]{3, 5, 10, 15, 30}) {
            config.setDoorExitDelaySeconds(valid);
            assertEquals(valid, config.getDoorExitDelaySeconds());
        }
        config.setDoorExitDelaySeconds(42);
        assertEquals(5, config.getDoorExitDelaySeconds());
    }

    @Test
    public void startPageLabelsAndPlaybackHubTabsMatchContract() {
        assertEquals("记录仪", AppearanceSettingsFragment.formatStartPageLabel(AppConfig.START_PAGE_RECORDING));
        assertEquals("回看", AppearanceSettingsFragment.formatStartPageLabel(AppConfig.START_PAGE_PLAYBACK));
        assertEquals("设置", AppearanceSettingsFragment.formatStartPageLabel(AppConfig.START_PAGE_SETTINGS));
        assertEquals("记录仪", AppearanceSettingsFragment.formatStartPageLabel(null));
        assertNotEquals(PlaybackHubFragment.TAB_VIDEO, PlaybackHubFragment.TAB_PHOTO);
    }

    @Test
    public void t03PreviewModeCornerNavAndShortcutsNormalizeAndPersist() {
        Map<String, Object> store = new HashMap<>();
        AppConfig config = createAppConfig(store);

        assertEquals(AppConfig.PREVIEW_MODE_B, config.getPreviewMode());
        assertEquals(AppConfig.PREVIEW_MODE_A, AppConfig.normalizePreviewMode("mode_a"));
        assertEquals(AppConfig.PREVIEW_MODE_C, AppConfig.normalizePreviewMode(AppConfig.PREVIEW_MODE_C));

        config.setPreviewMode(AppConfig.PREVIEW_MODE_A);
        assertEquals(AppConfig.PREVIEW_MODE_A, config.getPreviewMode());
        config.setPreviewMode(AppConfig.PREVIEW_MODE_C);
        assertEquals(AppConfig.PREVIEW_MODE_C, config.getPreviewMode());
        config.setPreviewMode("unknown");
        assertEquals(AppConfig.PREVIEW_MODE_B, config.getPreviewMode());

        assertEquals(AppConfig.CORNER_NAV_AUTO, config.getPreviewCornerNavMode());
        config.setPreviewCornerNavMode(AppConfig.CORNER_NAV_ALWAYS);
        assertEquals(AppConfig.CORNER_NAV_ALWAYS, config.getPreviewCornerNavMode());
        config.setPreviewCornerNavMode(AppConfig.CORNER_NAV_HIDDEN);
        assertEquals(AppConfig.CORNER_NAV_HIDDEN, config.getPreviewCornerNavMode());
        assertEquals(AppConfig.CORNER_NAV_AUTO, AppConfig.normalizePreviewCornerNavMode("bad"));

        assertTrue(config.isPreviewViewShortcutsEnabled());
        config.setPreviewViewShortcutsEnabled(false);
        assertFalse(config.isPreviewViewShortcutsEnabled());

        assertEquals("分栏 B（三列分屏）", AppearanceSettingsFragment.formatPreviewModeLabel(AppConfig.PREVIEW_MODE_B));
        assertEquals("三路 C（双侧 + 手动前/后大屏）", AppearanceSettingsFragment.formatPreviewModeLabel(AppConfig.PREVIEW_MODE_C));
        assertEquals("模式 D（双视转向联动）", AppearanceSettingsFragment.formatPreviewModeLabel(AppConfig.PREVIEW_MODE_D));
        assertEquals(AppConfig.PREVIEW_MODE_D, AppConfig.normalizePreviewMode(AppConfig.PREVIEW_MODE_D));
        assertEquals(AppConfig.PREVIEW_MODE_D, PreviewMode.fromKey(AppConfig.PREVIEW_MODE_D).key);
        assertEquals("左右 E（左/右双摄并排）", AppearanceSettingsFragment.formatPreviewModeLabel(AppConfig.PREVIEW_MODE_E));
        assertEquals(AppConfig.PREVIEW_MODE_E, AppConfig.normalizePreviewMode(AppConfig.PREVIEW_MODE_E));
        assertEquals(AppConfig.PREVIEW_MODE_E, PreviewMode.fromKey(AppConfig.PREVIEW_MODE_E).key);
        config.setPreviewMode(AppConfig.PREVIEW_MODE_E);
        assertEquals(AppConfig.PREVIEW_MODE_E, config.getPreviewMode());
        assertEquals("仅沉浸全屏显示", AppearanceSettingsFragment.formatCornerNavLabel(AppConfig.CORNER_NAV_AUTO));
        assertEquals("始终显示", AppearanceSettingsFragment.formatCornerNavLabel(AppConfig.CORNER_NAV_ALWAYS));
        assertEquals("隐藏", AppearanceSettingsFragment.formatCornerNavLabel(AppConfig.CORNER_NAV_HIDDEN));
    }

    @Test
    public void t06AutomationAndSignalPresetConfigPersistAndNormalize() {
        Map<String, Object> store = new HashMap<>();
        AppConfig config = createAppConfig(store);

        assertFalse(config.isKeepCameraConnectedEnabled());
        config.setKeepCameraConnectedEnabled(true);
        assertTrue(config.isKeepCameraConnectedEnabled());

        assertFalse(config.isCameraRecoveryEnabled());
        config.setCameraRecoveryEnabled(true);
        assertTrue(config.isCameraRecoveryEnabled());

        assertFalse(config.isRdShiftAutoPopupEnabled());
        config.setRdShiftAutoPopupEnabled(true);
        assertTrue(config.isRdShiftAutoPopupEnabled());

        assertEquals(5, config.getRdShiftPopupSeconds());
        config.setRdShiftPopupSeconds(15);
        assertEquals(15, config.getRdShiftPopupSeconds());
        config.setRdShiftPopupSeconds(77);
        assertEquals(5, config.getRdShiftPopupSeconds());

        assertEquals(2, config.getVehicleTriggerRules().size());
        assertTrue(config.hasActiveVehicleTriggerRules());
        config.setVehicleTriggerRules(java.util.Collections.emptyList());
        assertTrue(config.getVehicleTriggerRules().isEmpty());
        assertFalse(config.hasActiveVehicleTriggerRules());
        config.resetVehicleTriggerRulesToDefault();
        assertEquals(2, config.getVehicleTriggerRules().size());

        config.setCustomVhalPropertyIds(0x21400400, 0x21400401, 0x21600207, 0x26200b00, 0x21400408);
        assertEquals(0x21400400, config.getCustomGearSelectionPropId());
        assertEquals(com.kooo.evcam.vehicle.VehicleSignalCategory.GEAR,
                com.kooo.evcam.vehicle.VehicleSignalCategory.classifyPropertyId(0x21400400));
        config.resetCustomVhalPropertyIds();
        assertEquals(com.kooo.evcam.vehicle.VehicleSignalCategory.PROP_GEAR_SELECTION,
                config.getCustomGearSelectionPropId());
    }

    @Test
    public void t07SurroundModeAAnd41To43SettingsNormalizeAndPersistWithoutLynk08() {
        Map<String, Object> store = new HashMap<>();
        AppConfig config = createAppConfig(store);

        assertEquals(com.kooo.evcam.surround.SurroundViewMode.DRIVE, config.getSurroundTopView());
        assertEquals(com.kooo.evcam.surround.SurroundViewMode.FRONT_WHEELS, config.getSurroundBottomView());
        assertTrue(config.isSurroundOverlayButtonsVisible());
        config.setSurroundOverlayButtonsVisible(false);
        assertFalse(config.isSurroundOverlayButtonsVisible());

        // R 档延时 0..5000ms 步长 50ms
        assertEquals(200, config.getReversePreviewDelayMs());
        config.setReversePreviewDelayMs(233);
        assertEquals(250, config.getReversePreviewDelayMs());
        config.setReversePreviewDelayMs(9999);
        assertEquals(5000, config.getReversePreviewDelayMs());

        // 车速返桌 15..50km/h；P 档返桌 1..60s
        assertEquals(20, config.getPreviewSpeedThresholdKmh());
        config.setPreviewSpeedThresholdKmh(5);
        assertEquals(15, config.getPreviewSpeedThresholdKmh());
        assertEquals(3, config.getParkPreviewCloseDelaySeconds());
        config.setParkPreviewCloseDelaySeconds(120);
        assertEquals(60, config.getParkPreviewCloseDelaySeconds());

        // 仅允许 E5 内置/本地校准，彻底剔除领克 08
        assertEquals(AppConfig.SURROUND_CALIBRATION_E5_BUILTIN, config.getSurroundCalibrationSource());
        assertEquals(AppConfig.SURROUND_CALIBRATION_E5_BUILTIN,
                AppConfig.normalizeSurroundCalibrationSource("LYNK_08"));
        assertEquals(206, config.getSurroundGroundWidthCm());
        config.setSurroundGroundWidthCm(300);
        assertEquals(260, config.getSurroundGroundWidthCm());
        config.resetSurroundGroundDimensions();
        assertEquals(206, config.getSurroundGroundWidthCm());
        assertEquals(530, config.getSurroundGroundLengthCm());
        assertEquals(0, config.getSurroundGroundFrontExtensionCm());

        assertTrue(config.isPreviewGuidanceEnabled());
        assertEquals("全景 A（360 + 分视角）",
                AppearanceSettingsFragment.formatPreviewModeLabel(AppConfig.PREVIEW_MODE_A));
    }

    @Test
    public void t08PerSlotFillZoomPanClampsPersistsAndResetsCleanly() {
        Map<String, Object> store = new HashMap<>();
        AppConfig config = createAppConfig(store);

        assertTrue(config.isPreviewSlotFillContainer(AppConfig.PREVIEW_MODE_C, "left"));
        assertEquals(1.0f, config.getPreviewSlotZoom(AppConfig.PREVIEW_MODE_C, "left"), 0.0001f);
        assertEquals(0.0f, config.getPreviewSlotPanX(AppConfig.PREVIEW_MODE_C, "left"), 0.0001f);
        assertEquals(0.0f, config.getPreviewSlotPanY(AppConfig.PREVIEW_MODE_C, "left"), 0.0001f);

        config.setPreviewSlotFillContainer(AppConfig.PREVIEW_MODE_C, "left", false);
        config.setPreviewSlotZoom(AppConfig.PREVIEW_MODE_C, "left", 99.0f);
        config.setPreviewSlotPan(AppConfig.PREVIEW_MODE_C, "left", -2.0f, 3.0f);

        assertFalse(config.isPreviewSlotFillContainer(AppConfig.PREVIEW_MODE_C, "left"));
        assertEquals(3.0f, config.getPreviewSlotZoom(AppConfig.PREVIEW_MODE_C, "left"), 0.0001f);
        assertEquals(-0.5f, config.getPreviewSlotPanX(AppConfig.PREVIEW_MODE_C, "left"), 0.0001f);
        assertEquals(0.5f, config.getPreviewSlotPanY(AppConfig.PREVIEW_MODE_C, "left"), 0.0001f);
        // Independent from other slots / modes
        assertTrue(config.isPreviewSlotFillContainer(AppConfig.PREVIEW_MODE_D, "left"));
        assertEquals(1.0f, config.getPreviewSlotZoom(AppConfig.PREVIEW_MODE_D, "left"), 0.0001f);
        config.setPreviewSlotFillContainer(AppConfig.PREVIEW_MODE_E, "left", false);
        config.setPreviewSlotZoom(AppConfig.PREVIEW_MODE_E, "left", 1.8f);
        config.setPreviewSlotPan(AppConfig.PREVIEW_MODE_E, "left", 0.25f, -0.25f);
        assertFalse(config.isPreviewSlotFillContainer(AppConfig.PREVIEW_MODE_E, "left"));
        assertEquals(1.8f, config.getPreviewSlotZoom(AppConfig.PREVIEW_MODE_E, "left"), 0.0001f);
        assertEquals(0.25f, config.getPreviewSlotPanX(AppConfig.PREVIEW_MODE_E, "left"), 0.0001f);
        assertEquals(-0.25f, config.getPreviewSlotPanY(AppConfig.PREVIEW_MODE_E, "left"), 0.0001f);
        assertTrue(config.isPreviewSlotFillContainer(AppConfig.PREVIEW_MODE_D, "left"));
        assertEquals(1.0f, config.getPreviewSlotZoom(AppConfig.PREVIEW_MODE_D, "left"), 0.0001f);

        config.resetPreviewSlotTransform(AppConfig.PREVIEW_MODE_C, "left");
        assertTrue(config.isPreviewSlotFillContainer(AppConfig.PREVIEW_MODE_C, "left"));
        assertEquals(1.0f, config.getPreviewSlotZoom(AppConfig.PREVIEW_MODE_C, "left"), 0.0001f);
        assertEquals(0.0f, config.getPreviewSlotPanX(AppConfig.PREVIEW_MODE_C, "left"), 0.0001f);
        assertEquals(0.0f, config.getPreviewSlotPanY(AppConfig.PREVIEW_MODE_C, "left"), 0.0001f);
    }

    @Test
    public void packEUiScaleModeNormalizesPersistsAndComputesMultiplier() {
        Map<String, Object> store = new HashMap<>();
        AppConfig config = createAppConfig(store);

        assertEquals(AppConfig.UI_SCALE_STANDARD, config.getUiScaleMode());
        assertEquals(1.0f, config.getUiScaleMultiplier(), 0.0001f);
        assertEquals(AppConfig.UI_SCALE_STANDARD, AppConfig.normalizeUiScaleMode(null));
        assertEquals(AppConfig.UI_SCALE_STANDARD, AppConfig.normalizeUiScaleMode("unexpected"));
        assertEquals("标准（自适应）", AppearanceSettingsFragment.formatUiScaleLabel("unexpected"));

        config.setUiScaleMode(AppConfig.UI_SCALE_LARGE);
        assertEquals(AppConfig.UI_SCALE_LARGE, config.getUiScaleMode());
        assertEquals(AppConfig.UI_SCALE_LARGE_MULTIPLIER, config.getUiScaleMultiplier(), 0.0001f);
        assertEquals("车机大号", AppearanceSettingsFragment.formatUiScaleLabel(AppConfig.UI_SCALE_LARGE));

        assertEquals(1.0f, UiScaleHelper.normalizeScale(Float.NaN), 0.0001f);
        assertEquals(1.0f, UiScaleHelper.normalizeScale(-1.0f), 0.0001f);
        assertEquals(1.0f, UiScaleHelper.normalizeScale(1.004f), 0.0001f);
        assertEquals(1.16f, UiScaleHelper.normalizeScale(1.16f), 0.0001f);

        config.setUiScaleMode("invalid_back_to_standard");
        assertEquals(AppConfig.UI_SCALE_STANDARD, config.getUiScaleMode());
        assertEquals(1.0f, config.getUiScaleMultiplier(), 0.0001f);
    }
}
