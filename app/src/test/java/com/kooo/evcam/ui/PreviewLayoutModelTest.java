package com.kooo.evcam.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewLayoutModelTest {
    private static final float EPSILON = .0001f;
    @Test public void defaultHasTallSidesAndFrontAboveRearInTheCenter() {
        PreviewLayoutModel model = new PreviewLayoutModel();
        assertEquals(0, model.pane(PreviewLayoutModel.LEFT).x, EPSILON);
        assertEquals(1, model.pane(PreviewLayoutModel.LEFT).height, EPSILON);
        assertEquals(.7f, model.pane(PreviewLayoutModel.RIGHT).x, EPSILON);
        assertEquals(.3f, model.pane(PreviewLayoutModel.FRONT).x, EPSILON);
        assertEquals(model.pane(PreviewLayoutModel.FRONT).x, model.pane(PreviewLayoutModel.BACK).x, EPSILON);
        assertEquals(0, model.pane(PreviewLayoutModel.FRONT).y, EPSILON);
        assertEquals(.5f, model.pane(PreviewLayoutModel.BACK).y, EPSILON);
    }
    @Test public void draggingCannotLoseAPaneOutsideTheCanvas() {
        PreviewLayoutModel model = new PreviewLayoutModel();
        model.move(PreviewLayoutModel.FRONT, 5, -3);
        assertEquals(.6f, model.pane(PreviewLayoutModel.FRONT).x, EPSILON);
        assertEquals(0, model.pane(PreviewLayoutModel.FRONT).y, EPSILON);
    }
    @Test public void resizingEnforcesMinimumAndAvailableSpace() {
        PreviewLayoutModel model = new PreviewLayoutModel();
        model.resize(PreviewLayoutModel.FRONT, 0, 5);
        assertEquals(PreviewLayoutModel.MIN_SIZE, model.pane(PreviewLayoutModel.FRONT).width, EPSILON);
        assertEquals(1, model.pane(PreviewLayoutModel.FRONT).height, EPSILON);
        model.resize(PreviewLayoutModel.RIGHT, 5, 5);
        assertEquals(.3f, model.pane(PreviewLayoutModel.RIGHT).width, EPSILON);
    }
    @Test public void savedSnapshotRestoresLayoutAndSupportsCancel() {
        PreviewLayoutModel model = new PreviewLayoutModel();
        float[] before = model.snapshot();
        model.preset(1); model.move(0, .4f, .4f); model.resize(0, .2f, .2f);
        float[] edited = model.snapshot();
        PreviewLayoutModel loaded = new PreviewLayoutModel();
        assertTrue(loaded.restore(edited)); assertArrayEquals(edited, loaded.snapshot(), EPSILON);
        assertTrue(model.restore(before)); assertArrayEquals(before, model.snapshot(), EPSILON);
    }
    @Test public void corruptSavedNumbersCannotPartiallyReplaceTheDefault() {
        PreviewLayoutModel model = new PreviewLayoutModel();
        float[] initial = model.snapshot(), invalid = model.snapshot(); invalid[15] = Float.NaN;
        assertFalse(model.restore(invalid)); assertArrayEquals(initial, model.snapshot(), EPSILON);
        invalid = initial.clone(); invalid[2] = -.1f;
        assertFalse(model.restore(invalid)); assertArrayEquals(initial, model.snapshot(), EPSILON);
    }
    @Test public void allPresetsFitTheCanvasAndIgnoreInvalidGestures() {
        PreviewLayoutModel model = new PreviewLayoutModel();
        for (int preset = 0; preset < 3; preset++) {
            model.preset(preset);
            assertTrue(new PreviewLayoutModel().restore(model.snapshot()));
        }
        float[] before = model.snapshot();
        model.move(0, Float.NaN, 0); model.resize(1, Float.POSITIVE_INFINITY, .2f);
        assertArrayEquals(before, model.snapshot(), EPSILON);
    }
}
