package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FeatureProfileTest {
    @Test public void e5ProfileSelectsThreeDailyCamerasOnlyOnce() {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(preferences.getString(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(preferences.edit()).thenReturn(editor);
        AppConfig config = new AppConfig(context);
        config.applyE5RecordingProfile();
        verify(editor).putBoolean("recording_camera_front_enabled", false);
        verify(editor).putBoolean("recording_camera_back_enabled", true);
        verify(editor).putBoolean("recording_camera_left_enabled", true);
        verify(editor).putBoolean("recording_camera_right_enabled", true);
        when(preferences.getBoolean("e5_three_camera_profile_applied", false)).thenReturn(true);
        config.applyE5RecordingProfile();
        verify(editor, times(1)).apply();
    }

    @Test public void oldEnabledPreferencesCannotRestartRemovedFeatures() {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(preferences.getBoolean(anyString(), anyBoolean())).thenReturn(true);
        AppConfig config = new AppConfig(context);
        assertFalse(config.isFisheyeCorrectionEnabled());
        assertFalse(config.isPreviewCorrectionEnabled());
        assertFalse(config.isImageAdjustEnabled());
        assertTrue(config.isSecondaryDisplayEnabled());
        assertFalse(config.isDoorSecondaryDisplayEnabled());
        assertFalse(config.isSupervisionModeEnabled());
        assertFalse(config.isLongViewModeEnabled());
        assertFalse(config.isDoorLinkageEnabled());
        assertFalse(config.isCustomKeyWakeupEnabled());
        assertFalse(config.isMockTurnSignalFloatingEnabled());
        assertTrue(config.isMjpegStreamEnabled());
        assertTrue(config.isTurnSignalLinkageEnabled());
        assertTrue(config.isBlindSpotGlobalEnabled());
    }

    @Test public void mainPreviewDefaultsOffOnExistingInstallWithoutNewPreference() {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(preferences.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        assertFalse(new AppConfig(context).isMainPreviewEnabled());
    }

    @Test public void secondaryHudOutputKeepsStoredSwitchAndDisplayWithMainPreviewOff() {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(preferences.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        when(preferences.getBoolean("secondary_display_enabled", false)).thenReturn(true);
        when(preferences.getInt("secondary_display_id", 0)).thenReturn(-100);
        AppConfig config = new AppConfig(context);
        assertTrue(config.isSecondaryDisplayEnabled());
        assertFalse(config.isMainPreviewEnabled());
        assertEquals(EvccDashcastDisplayResolver.PICK_ID, config.getSecondaryDisplayId());
        when(preferences.getBoolean("secondary_display_enabled", false)).thenReturn(false);
        assertFalse(config.isSecondaryDisplayEnabled());
    }

    @Test public void wifiHudOutputKeepsItsOwnSwitchAndDoesNotEnableMainPreview() {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(preferences.getBoolean(anyString(), anyBoolean())).thenAnswer(call -> call.getArgument(1));
        AppConfig config = new AppConfig(context);
        assertFalse(config.isMjpegStreamEnabled());
        assertFalse(config.isSecondaryDisplayEnabled());
        when(preferences.getBoolean("mjpeg_stream_enabled", false)).thenReturn(true);
        assertTrue(config.isMjpegStreamEnabled());
        assertFalse(config.isMainPreviewEnabled());
        assertFalse(config.isSecondaryDisplayEnabled());
    }
}
