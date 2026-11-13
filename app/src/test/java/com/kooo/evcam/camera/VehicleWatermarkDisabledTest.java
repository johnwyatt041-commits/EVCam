package com.kooo.evcam.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.EGL14;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.vehicle.RecordingVehicleSource;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class VehicleWatermarkDisabledTest {
    @Test public void freshInstallDefaultsToDisabled() {
        Context context = mock(Context.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences);
        AppConfig config = new AppConfig(context);
        assertFalse(config.isVehicleStatusWatermarkEnabled());
        verify(preferences).getBoolean("vehicle_status_watermark", false);
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
