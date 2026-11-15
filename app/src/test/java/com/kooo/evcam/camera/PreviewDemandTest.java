package com.kooo.evcam.camera;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewDemandTest {
    @Test public void previewIsOffByDefaultAndPhotoLeaseIsTemporary() {
        PreviewDemand demand = new PreviewDemand();
        assertFalse(demand.isRequired());
        demand.acquirePhoto();
        assertTrue(demand.isRequired());
        demand.releasePhoto();
        assertFalse(demand.isRequired());
    }

    @Test public void backgroundingDuringPhotoDoesNotInterruptCapture() {
        PreviewDemand demand = new PreviewDemand();
        demand.setMainPreview(true);
        demand.acquirePhoto();
        demand.setMainPreview(false);
        assertTrue(demand.isRequired());
        demand.releasePhoto();
        assertFalse(demand.isRequired());
    }

    @Test public void userCanEnablePreviewWhilePhotoIsInProgress() {
        PreviewDemand demand = new PreviewDemand();
        demand.acquirePhoto();
        demand.setMainPreview(true);
        demand.releasePhoto();
        assertTrue(demand.isRequired());
    }

    @Test public void overlappingPhotosReleaseOnlyAfterBothFinish() {
        PreviewDemand demand = new PreviewDemand();
        demand.acquirePhoto();
        demand.acquirePhoto();
        demand.releasePhoto();
        assertTrue(demand.isRequired());
        demand.releasePhoto();
        demand.releasePhoto();
        assertFalse(demand.isRequired());
    }

    @Test public void perCameraVisibilityDisablesHiddenStreamWhileKeepingOthersActive() {
        PreviewDemand demand = new PreviewDemand();
        demand.setMainPreview(true);
        assertTrue(demand.isRequired("front"));
        assertTrue(demand.isRequired("back"));

        demand.setCameraVisible("front", false);
        assertFalse(demand.isRequired("front"));
        assertTrue(demand.isRequired("back"));
        assertTrue(demand.isRequired("left"));
        assertTrue(demand.isRequired("right"));
    }

    @Test public void perCameraPhotoLeaseWakesHiddenCameraTemporarilyWithoutClobberingVisibility() {
        PreviewDemand demand = new PreviewDemand();
        demand.setMainPreview(true);
        demand.setCameraVisible("front", false);
        assertFalse(demand.isRequired("front"));

        demand.acquirePhoto("front");
        assertTrue(demand.isRequired("front"));
        assertFalse(demand.isCameraVisible("front"));

        demand.releasePhoto("front");
        assertFalse(demand.isRequired("front"));
        assertTrue(demand.isRequired("back"));
    }

    @Test public void backgroundingTurnsOffAllPerCameraPreviewUnlessPhotoLeaseActive() {
        PreviewDemand demand = new PreviewDemand();
        demand.setMainPreview(false);
        demand.setCameraVisible("back", true);
        assertFalse(demand.isRequired("back"));

        demand.acquirePhoto("back");
        assertTrue(demand.isRequired("back"));
        demand.releasePhoto("back");
        assertFalse(demand.isRequired("back"));
    }
}
