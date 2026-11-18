package com.kooo.evcam.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.EGL14;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.vehicle.RecordingVehicleSource;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class VehicleWatermarkDisabledTest {
    private static AppConfig createInMemoryAppConfig(Map<String, Object> backingStore) {
        Context context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(editor);
        when(prefs.contains(anyString())).thenAnswer(inv -> backingStore.containsKey(inv.getArgument(0)));
        when(prefs.getString(anyString(), nullable(String.class))).thenAnswer(inv -> {
            Object v = backingStore.get(inv.getArgument(0));
            return v instanceof String ? (String) v : inv.getArgument(1);
        });
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            Object v = backingStore.get(inv.getArgument(0));
            return v instanceof Boolean ? (Boolean) v : inv.getArgument(1);
        });
        when(editor.putString(anyString(), nullable(String.class))).thenAnswer(inv -> {
            backingStore.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            backingStore.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        return new AppConfig(context);
    }

    @Test public void legacyPreferencesMigrateSmoothlyToFourIndependentSwitches() {
        // 1) Legacy master=true with implicit sub-checkbox defaults (all vehicle items enabled)
        Map<String, Object> legacyOnStore = new HashMap<>();
        legacyOnStore.put("timestamp_watermark_enabled", true);
        legacyOnStore.put("vehicle_status_watermark", true);
        AppConfig legacyOn = createInMemoryAppConfig(legacyOnStore);
        assertTrue(legacyOn.isTimestampWatermarkEnabled());
        assertTrue(legacyOn.isWatermarkSpeedEnabled());
        assertTrue(legacyOn.isWatermarkGearEnabled());
        assertTrue(legacyOn.isWatermarkTurnSignalEnabled());

        // Toggling one switch preserves migrated states of the remaining switches
        legacyOn.setWatermarkTurnSignalEnabled(false);
        assertTrue(legacyOn.isWatermarkSpeedEnabled());
        assertTrue(legacyOn.isWatermarkGearEnabled());
        assertFalse(legacyOn.isWatermarkTurnSignalEnabled());
        assertTrue(legacyOn.isVehicleStatusWatermarkEnabled());

        // 2) Legacy master=false even with stale speed=true must remain disabled
        Map<String, Object> legacyOffStore = new HashMap<>();
        legacyOffStore.put("vehicle_status_watermark", false);
        legacyOffStore.put("watermark_speed_enabled", true);
        legacyOffStore.put("watermark_gear_turn_enabled", true);
        AppConfig legacyOff = createInMemoryAppConfig(legacyOffStore);
        assertFalse(legacyOff.isWatermarkSpeedEnabled());
        assertFalse(legacyOff.isWatermarkGearEnabled());
        assertFalse(legacyOff.isWatermarkTurnSignalEnabled());
        assertFalse(legacyOff.isVehicleStatusWatermarkEnabled());

        // Enabling only gear does not accidentally enable speed or turn signal
        legacyOff.setWatermarkGearEnabled(true);
        assertFalse(legacyOff.isWatermarkSpeedEnabled());
        assertTrue(legacyOff.isWatermarkGearEnabled());
        assertFalse(legacyOff.isWatermarkTurnSignalEnabled());
        VehicleStatusWatermark.WatermarkOptions opts = legacyOff.getWatermarkOptions();
        assertFalse(opts.showDateTime);
        assertFalse(opts.showSpeed);
        assertTrue(opts.showGear);
        assertFalse(opts.showTurnSignal);
    }

    @Test public void freshInstallDefaultsToDisabled() {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        AppConfig config = new AppConfig(context);
        assertFalse(config.isVehicleStatusWatermarkEnabled());
        verify(preferences).getBoolean("vehicle_status_watermark", false);
        assertFalse(config.isTimestampWatermarkEnabled());
        assertFalse(config.isWatermarkSpeedEnabled());
        assertFalse(config.isWatermarkGearEnabled());
        assertFalse(config.isWatermarkTurnSignalEnabled());
        assertFalse(config.getWatermarkOptions().hasAnyEnabled());
        assertFalse(config.getWatermarkOptions().needsVehicleSource());
    }

    @Test public void disabledEncoderDoesNotCreateSourceOrTouchGl() throws Exception {
        EglSurfaceEncoder encoder = new EglSurfaceEncoder("right", 1280, 800);
        Field initialized = EglSurfaceEncoder.class.getDeclaredField("isInitialized");
        initialized.setAccessible(true);
        initialized.setBoolean(encoder, true);
        try (MockedStatic<RecordingVehicleSource> source = mockStatic(RecordingVehicleSource.class);
             MockedStatic<EGL14> egl = mockStatic(EGL14.class)) {
            encoder.setVehicleStatusWatermarkEnabled(false);
            encoder.setVehicleStatusWatermarkEnabled(false);
            source.verifyNoInteractions();
            egl.verifyNoInteractions();
        }
    }
}
