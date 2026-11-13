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
}
