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

    @Test public void modeBDefaultThreeColumnPlacesFrontBackFirstAndSidesNext() {
        PreviewLayoutModel model = new PreviewLayoutModel(PreviewLayoutModel.PRESET_MODE_B_THREE_COL);
        assertEquals(0f, model.pane(PreviewLayoutModel.FRONT).x, EPSILON);
        assertEquals(0f, model.pane(PreviewLayoutModel.BACK).x, EPSILON);
        assertEquals(.40f, model.pane(PreviewLayoutModel.FRONT).width, EPSILON);
        assertEquals(.5f, model.pane(PreviewLayoutModel.FRONT).height, EPSILON);
        assertEquals(.5f, model.pane(PreviewLayoutModel.BACK).y, EPSILON);
        assertEquals(.40f, model.pane(PreviewLayoutModel.LEFT).x, EPSILON);
        assertEquals(.30f, model.pane(PreviewLayoutModel.LEFT).width, EPSILON);
        assertEquals(1f, model.pane(PreviewLayoutModel.LEFT).height, EPSILON);
        assertEquals(.70f, model.pane(PreviewLayoutModel.RIGHT).x, EPSILON);
        assertEquals(.30f, model.pane(PreviewLayoutModel.RIGHT).width, EPSILON);
    }

    @Test public void modeBSmartReflowFillsCanvasWithoutDeadHolesWhenHidingPanes() {
        PreviewLayoutModel model = new PreviewLayoutModel(PreviewLayoutModel.PRESET_MODE_B_THREE_COL);

        // Hide BACK -> FRONT expands vertically to fill the full first column.
        PreviewLayoutModel.EffectiveLayout noBack = model.computeEffectiveModeB(
                new boolean[]{true, true, false, true});
        assertFalse(noBack.visible[PreviewLayoutModel.BACK]);
        assertTrue(noBack.visible[PreviewLayoutModel.FRONT]);
        assertEquals(.40f, noBack.panes[PreviewLayoutModel.FRONT].width, EPSILON);
        assertEquals(1f, noBack.panes[PreviewLayoutModel.FRONT].height, EPSILON);
        assertEquals(.40f, noBack.panes[PreviewLayoutModel.LEFT].x, EPSILON);
        assertEquals(.70f, noBack.panes[PreviewLayoutModel.RIGHT].x, EPSILON);

        // Hide LEFT -> [FRONT/BACK] (0.40) and RIGHT (0.30) rescale cleanly to fill width 1.0.
        PreviewLayoutModel.EffectiveLayout noLeft = model.computeEffectiveModeB(
                new boolean[]{false, true, true, true});
        assertFalse(noLeft.visible[PreviewLayoutModel.LEFT]);
        assertEquals(0f, noLeft.panes[PreviewLayoutModel.FRONT].x, EPSILON);
        assertEquals(0.40f / 0.70f, noLeft.panes[PreviewLayoutModel.FRONT].width, EPSILON);
        assertEquals(noLeft.panes[PreviewLayoutModel.FRONT].width,
                noLeft.panes[PreviewLayoutModel.RIGHT].x, EPSILON);
        assertEquals(1f, noLeft.panes[PreviewLayoutModel.RIGHT].x + noLeft.panes[PreviewLayoutModel.RIGHT].width, EPSILON);

        // Single remaining pane expands to [0, 0, 1, 1].
        PreviewLayoutModel.EffectiveLayout single = model.computeEffectiveModeB(
                new boolean[]{false, false, false, true});
        assertFalse(single.visible[PreviewLayoutModel.FRONT]);
        assertTrue(single.visible[PreviewLayoutModel.RIGHT]);
        assertEquals(0f, single.panes[PreviewLayoutModel.RIGHT].x, EPSILON);
        assertEquals(1f, single.panes[PreviewLayoutModel.RIGHT].width, EPSILON);
        assertEquals(1f, single.panes[PreviewLayoutModel.RIGHT].height, EPSILON);
    }

    @Test public void modeCDefaultDriveReverseAndSideTogglesWorkAndPreserveCustomWidth() {
        PreviewLayoutModel model = new PreviewLayoutModel(PreviewLayoutModel.PRESET_MODE_C_FRONT);

        PreviewLayoutModel.EffectiveLayout drive = model.computeEffectiveModeC(
                PreviewLayoutModel.FRONT, true, true);
        assertTrue(drive.visible[PreviewLayoutModel.FRONT]);
        assertFalse(drive.visible[PreviewLayoutModel.BACK]);
        assertTrue(drive.visible[PreviewLayoutModel.LEFT]);
        assertTrue(drive.visible[PreviewLayoutModel.RIGHT]);
        assertEquals(.64f, drive.panes[PreviewLayoutModel.FRONT].width, EPSILON);
        assertEquals(.64f, drive.panes[PreviewLayoutModel.LEFT].x, EPSILON);
        assertEquals(.18f, drive.panes[PreviewLayoutModel.LEFT].width, EPSILON);
        assertEquals(.82f, drive.panes[PreviewLayoutModel.RIGHT].x, EPSILON);
        assertEquals(.18f, drive.panes[PreviewLayoutModel.RIGHT].width, EPSILON);

        // Switching to Reverse flips primary to BACK while preserving width.
        model.resize(PreviewLayoutModel.BACK, .50f, 1f);
        PreviewLayoutModel.EffectiveLayout reverseOneSide = model.computeEffectiveModeC(
                PreviewLayoutModel.BACK, true, false);
        assertTrue(reverseOneSide.visible[PreviewLayoutModel.BACK]);
        assertFalse(reverseOneSide.visible[PreviewLayoutModel.FRONT]);
        assertTrue(reverseOneSide.visible[PreviewLayoutModel.LEFT]);
        assertFalse(reverseOneSide.visible[PreviewLayoutModel.RIGHT]);
        assertEquals(.50f, reverseOneSide.panes[PreviewLayoutModel.BACK].width, EPSILON);
        assertEquals(.50f, reverseOneSide.panes[PreviewLayoutModel.LEFT].x, EPSILON);
        assertEquals(.50f, reverseOneSide.panes[PreviewLayoutModel.LEFT].width, EPSILON);

        // Both side channels off -> active primary fills 100%.
        PreviewLayoutModel.EffectiveLayout solo = model.computeEffectiveModeC(
                PreviewLayoutModel.BACK, false, false);
        assertEquals(1f, solo.panes[PreviewLayoutModel.BACK].width, EPSILON);
        assertEquals(1f, solo.panes[PreviewLayoutModel.BACK].height, EPSILON);
    }
}
