package com.kooo.evcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.kooo.evcam.ui.UiThemeController.ThemeMode;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class UiThemeControllerTest {

    private static final class InMemoryPrefsHarness {
        final AppCompatActivity activity = mock(AppCompatActivity.class);
        final Context appContext = mock(Context.class);
        final AppCompatDelegate delegate = mock(AppCompatDelegate.class);
        final SharedPreferences prefs = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        final Map<String, Object> store = new HashMap<>();

        InMemoryPrefsHarness() {
            when(activity.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
            when(activity.getApplicationContext()).thenReturn(appContext);
            when(activity.getDelegate()).thenReturn(delegate);
            when(appContext.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);

            when(prefs.contains(anyString())).thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
            when(prefs.getString(anyString(), nullable(String.class))).thenAnswer(inv -> {
                Object val = store.get(inv.getArgument(0));
                return val instanceof String ? (String) val : inv.getArgument(1);
            });
            when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
                Object val = store.get(inv.getArgument(0));
                return val instanceof Boolean ? (Boolean) val : inv.getArgument(1);
            });

            when(prefs.edit()).thenReturn(editor);
            when(editor.putString(anyString(), nullable(String.class))).thenAnswer(inv -> {
                String k = inv.getArgument(0);
                String v = inv.getArgument(1);
                if (v == null) store.remove(k);
                else store.put(k, v);
                return editor;
            });
            when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
                store.put(inv.getArgument(0), inv.getArgument(1));
                return editor;
            });
            when(editor.remove(anyString())).thenAnswer(inv -> {
                store.remove(inv.getArgument(0));
                return editor;
            });
        }
    }

    @Test
    public void freshInstallDefaultsToFollowSystem() {
        InMemoryPrefsHarness harness = new InMemoryPrefsHarness();
        assertEquals(ThemeMode.FOLLOW_SYSTEM, UiThemeController.savedThemeMode(harness.activity));
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, UiThemeController.savedMode(harness.activity));
    }

    @Test
    public void legacyLightThemeMigratesCleanlyUnlessOverriddenByThreeStateKey() {
        InMemoryPrefsHarness harness = new InMemoryPrefsHarness();
        harness.store.put(UiThemeController.KEY_LEGACY_LIGHT_THEME, false);
        assertEquals(ThemeMode.DARK, UiThemeController.savedThemeMode(harness.activity));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, UiThemeController.savedMode(harness.activity));

        harness.store.put(UiThemeController.KEY_LEGACY_LIGHT_THEME, true);
        assertEquals(ThemeMode.LIGHT, UiThemeController.savedThemeMode(harness.activity));
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, UiThemeController.savedMode(harness.activity));

        harness.store.put(UiThemeController.KEY_THEME_MODE, ThemeMode.FOLLOW_SYSTEM.key);
        assertEquals(ThemeMode.FOLLOW_SYSTEM, UiThemeController.savedThemeMode(harness.activity));
    }

    @Test
    public void resolveNightMaskCoversFollowSystemDarkAndLight() {
        assertEquals(Configuration.UI_MODE_NIGHT_YES,
                UiThemeController.resolveNightMask(ThemeMode.DARK, Configuration.UI_MODE_NIGHT_NO));
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
                UiThemeController.resolveNightMask(ThemeMode.LIGHT, Configuration.UI_MODE_NIGHT_YES));
        assertEquals(Configuration.UI_MODE_NIGHT_YES,
                UiThemeController.resolveNightMask(ThemeMode.FOLLOW_SYSTEM, Configuration.UI_MODE_NIGHT_YES));
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
                UiThemeController.resolveNightMask(ThemeMode.FOLLOW_SYSTEM, Configuration.UI_MODE_NIGHT_NO));
    }

    @Test
    public void switchingThemeDuringRecordingAppliesInPlaceWithoutRecreate() {
        InMemoryPrefsHarness harness = new InMemoryPrefsHarness();
        when(harness.delegate.getLocalNightMode()).thenReturn(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

        UiThemeController controller = new UiThemeController(harness.activity, () -> true, () -> false);
        assertFalse(UiThemeController.shouldRecreateActivityOnThemeChange());

        boolean switched = controller.selectThemeMode(ThemeMode.LIGHT);
        assertTrue(switched);
        assertEquals(ThemeMode.LIGHT.key, harness.store.get(UiThemeController.KEY_THEME_MODE));
        assertEquals(Boolean.TRUE, harness.store.get(UiThemeController.KEY_LEGACY_LIGHT_THEME));
        verify(harness.delegate).setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        verify(harness.activity, never()).recreate();
    }

    @Test
    public void switchingThemeWhileEditingLayoutIsRejected() {
        InMemoryPrefsHarness harness = new InMemoryPrefsHarness();
        UiThemeController controller = new UiThemeController(harness.activity, () -> false, () -> true);

        assertFalse(controller.selectThemeMode(ThemeMode.DARK));
        assertFalse(harness.store.containsKey(UiThemeController.KEY_THEME_MODE));
        verify(harness.activity, never()).recreate();
    }

    @Test
    public void configurationChangeInFollowSystemUpdatesWithoutRecreate() {
        InMemoryPrefsHarness harness = new InMemoryPrefsHarness();
        when(harness.delegate.getLocalNightMode()).thenReturn(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        Configuration sysConfig = new Configuration();
        sysConfig.uiMode = Configuration.UI_MODE_NIGHT_YES;

        UiThemeController controller = new UiThemeController(harness.activity, () -> true);
        controller.onConfigurationChanged(sysConfig);

        verify(harness.activity, never()).recreate();
    }
}
