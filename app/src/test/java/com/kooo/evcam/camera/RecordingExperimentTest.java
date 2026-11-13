package com.kooo.evcam.camera;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class RecordingExperimentTest {
    @Test public void remoteFourCameraCaptureAlwaysRetainsIndependentFiles() {
        assertFalse(RecordingExperiment.compositeDaily(Arrays.asList("front","back","left","right")));
        assertFalse(RecordingExperiment.compositeDaily(Arrays.asList("back")));
    }
}
