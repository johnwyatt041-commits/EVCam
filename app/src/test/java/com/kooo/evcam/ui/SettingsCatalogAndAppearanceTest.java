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
        return new AppConfig(context);
    }

    @Test
    public void catalogShowsOnlySevenImplementedEntriesWithoutShellsOrExcludedCategories() {
        List<SettingsCatalog.Entry> entries = SettingsCatalog.getImplementedEntries();
        assertEquals(7, entries.size());

        List<String> actualTitles = new ArrayList<>();
        List<String> actualDisplayTitles = new ArrayList<>();
        for (SettingsCatalog.Entry entry : entries) {
            actualTitles.add(entry.title);
            actualDisplayTitles.add(entry.displayTitle);
            assertNotEquals(0, entry.iconResId);
            assertFalse(entry.title.contains("屏幕尺寸"));
            assertFalse(entry.title.contains("外设"));
            assertFalse(entry.title.startsWith("4."));
            assertFalse(entry.title.startsWith("7."));
            assertFalse(entry.title.startsWith("8."));
            assertFalse(entry.title.startsWith("9."));
            assertFalse(entry.title.contains("投票"));
        }

        assertEquals(Arrays.asList(
                "1. 外观与交互",
                "3. 记录仪",
                "5. 补盲",
                "11. 飞书远程",
                "13. 系统与权限",
                "14. 软件更新",
                "15. Debug 日志"
        ), actualTitles);

        assertEquals(Arrays.asList(
                "外观与交互",
                "记录仪",
                "补盲",
                "飞书远程",
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
        assertEquals(AppConfig.PREVIEW_MODE_B, AppConfig.normalizePreviewMode("mode_a"));
        assertEquals(AppConfig.PREVIEW_MODE_C, AppConfig.normalizePreviewMode(AppConfig.PREVIEW_MODE_C));

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
        assertEquals("三路 C（档位联动大屏）", AppearanceSettingsFragment.formatPreviewModeLabel(AppConfig.PREVIEW_MODE_C));
        assertEquals("仅沉浸全屏显示", AppearanceSettingsFragment.formatCornerNavLabel(AppConfig.CORNER_NAV_AUTO));
        assertEquals("始终显示", AppearanceSettingsFragment.formatCornerNavLabel(AppConfig.CORNER_NAV_ALWAYS));
        assertEquals("隐藏", AppearanceSettingsFragment.formatCornerNavLabel(AppConfig.CORNER_NAV_HIDDEN));
    }
}
