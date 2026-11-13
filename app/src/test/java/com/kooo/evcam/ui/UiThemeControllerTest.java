package com.kooo.evcam.ui;

import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class UiThemeControllerTest {
    private AppCompatActivity activity(boolean light) {
        AppCompatActivity activity = mock(AppCompatActivity.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(activity.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        when(preferences.getBoolean("light_theme", false)).thenReturn(light);
        return activity;
    }
    @Test public void savedPreferenceSelectsExactlyOneColorMode() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, UiThemeController.savedMode(activity(false)));
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, UiThemeController.savedMode(activity(true)));
    }
    @Test public void applyingAThemeNeverRecreatesUiDuringRecording() {
        AppCompatActivity activity = activity(true);
        new UiThemeController(activity, () -> true).applyIfIdle();
        verify(activity, never()).getDelegate();
    }
    @Test public void idleUiAppliesTheSavedPreference() {
        AppCompatActivity activity = activity(true);
        AppCompatDelegate delegate = mock(AppCompatDelegate.class);
        when(activity.getDelegate()).thenReturn(delegate);
        new UiThemeController(activity, () -> false).applyIfIdle();
        verify(delegate).setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO);
    }
}
