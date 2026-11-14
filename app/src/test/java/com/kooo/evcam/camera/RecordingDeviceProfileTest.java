package com.kooo.evcam.camera;

import com.kooo.evcam.AppConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingDeviceProfileTest {
    @Test public void compatibilityCoversBothE5LayoutsWithoutTreatingOtherModelsAsE5() {
        assertTrue(RecordingDeviceProfile.isE5(AppConfig.CAR_MODEL_GALAXY_E5));
        assertTrue(RecordingDeviceProfile.isE5(AppConfig.CAR_MODEL_E5_MULTI));
        assertFalse(RecordingDeviceProfile.isE5("galaxy_l6"));
        assertFalse(RecordingDeviceProfile.isE5("custom"));
    }
}
