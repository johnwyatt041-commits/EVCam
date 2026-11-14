package com.kooo.evcam.camera;

import com.kooo.evcam.AppConfig;

/** E5 uses the camera defaults and encoder format from the working recording baseline. */
final class RecordingDeviceProfile {
    private RecordingDeviceProfile() {}
    static boolean isE5(String model) {
        return AppConfig.CAR_MODEL_GALAXY_E5.equals(model) || AppConfig.CAR_MODEL_E5_MULTI.equals(model);
    }
}
