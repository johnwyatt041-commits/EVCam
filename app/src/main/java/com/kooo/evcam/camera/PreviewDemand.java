package com.kooo.evcam.camera;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks global preview, per-camera preview visibility, and temporary photo capture leases.
 * Temporary photo capture never alters the user's persistent preview visibility choice.
 */
final class PreviewDemand {
    private boolean mainPreview;
    private int photoRequests;
    private final Map<String, Boolean> cameraVisibility = new HashMap<>();
    private final Map<String, Integer> cameraPhotoRequests = new HashMap<>();

    void setMainPreview(boolean enabled) {
        mainPreview = enabled;
    }

    boolean isMainPreview() {
        return mainPreview;
    }

    void setCameraVisible(String cameraKey, boolean visible) {
        if (cameraKey == null) return;
        cameraVisibility.put(cameraKey, visible);
    }

    void setAllCameraVisibility(Map<String, Boolean> visibilityMap) {
        if (visibilityMap == null) return;
        for (Map.Entry<String, Boolean> entry : visibilityMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                cameraVisibility.put(entry.getKey(), entry.getValue());
            }
        }
    }

    boolean isCameraVisible(String cameraKey) {
        if (cameraKey == null) return true;
        Boolean visible = cameraVisibility.get(cameraKey);
        return visible == null || visible;
    }

    void acquirePhoto() {
        photoRequests++;
    }

    void releasePhoto() {
        if (photoRequests > 0) photoRequests--;
    }

    void acquirePhoto(String cameraKey) {
        if (cameraKey == null) {
            acquirePhoto();
            return;
        }
        Integer count = cameraPhotoRequests.get(cameraKey);
        cameraPhotoRequests.put(cameraKey, (count == null ? 0 : count) + 1);
    }

    void releasePhoto(String cameraKey) {
        if (cameraKey == null) {
            releasePhoto();
            return;
        }
        Integer count = cameraPhotoRequests.get(cameraKey);
        if (count != null && count > 1) {
            cameraPhotoRequests.put(cameraKey, count - 1);
        } else {
            cameraPhotoRequests.remove(cameraKey);
        }
    }

    boolean hasActivePhotoLease() {
        if (photoRequests > 0) return true;
        for (Integer count : cameraPhotoRequests.values()) {
            if (count != null && count > 0) return true;
        }
        return false;
    }

    boolean isRequired() {
        return mainPreview || hasActivePhotoLease();
    }

    boolean isRequired(String cameraKey) {
        Integer perCameraPhoto = cameraKey != null ? cameraPhotoRequests.get(cameraKey) : null;
        if (photoRequests > 0 || (perCameraPhoto != null && perCameraPhoto > 0)) {
            return true;
        }
        return mainPreview && isCameraVisible(cameraKey);
    }
}
