package com.kooo.evcam.camera;

/** Temporary photo capture never changes the user's persistent preview choice. */
final class PreviewDemand {
    private boolean mainPreview;
    private int photoRequests;

    void setMainPreview(boolean enabled) { mainPreview = enabled; }
    void acquirePhoto() { photoRequests++; }
    void releasePhoto() { if (photoRequests > 0) photoRequests--; }
    boolean isRequired() { return mainPreview || photoRequests > 0; }
}
