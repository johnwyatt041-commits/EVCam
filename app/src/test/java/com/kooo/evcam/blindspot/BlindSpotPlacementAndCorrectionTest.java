package com.kooo.evcam.blindspot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BlindSpotPlacementAndCorrectionTest {

    @Test
    public void testPlacementDefaultsAndPixelConversion2560x1600() {
        BlindSpotPlacement.RectRatio left = BlindSpotPlacement.defaultForSlot(BlindSpotPlacement.SLOT_LEFT);
        assertEquals(2, left.xPercent());
        assertEquals(20, left.yPercent());
        assertEquals(42, left.widthPercent());
        assertEquals(60, left.heightPercent());

        int[] pxMain = left.toPixelBounds(2560, 1600);
        assertEquals(51, pxMain[0]);
        assertEquals(320, pxMain[1]);
        assertEquals(1075, pxMain[2]);
        assertEquals(960, pxMain[3]);

        // Different HUD resolution scales proportionally
        int[] pxHud = left.toPixelBounds(1920, 720);
        assertEquals(38, pxHud[0]);
        assertEquals(144, pxHud[1]);
        assertEquals(806, pxHud[2]);
        assertEquals(432, pxHud[3]);
    }

    @Test
    public void testPlacementClampingAndResizeBounds() {
        BlindSpotPlacement.RectRatio tooSmall = new BlindSpotPlacement.RectRatio(0.95f, 0.95f, 0.05f, 0.05f);
        assertEquals(BlindSpotPlacement.MIN_WIDTH_RATIO, tooSmall.width, 1e-4f);
        assertEquals(BlindSpotPlacement.MIN_HEIGHT_RATIO, tooSmall.height, 1e-4f);
        assertEquals(0.85f, tooSmall.x, 1e-4f);
        assertEquals(0.85f, tooSmall.y, 1e-4f);

        BlindSpotPlacement.RectRatio base = new BlindSpotPlacement.RectRatio(0.36f, 0.31f, 0.42f, 0.60f);
        BlindSpotPlacement.RectRatio movedPastEdge = base.movedBy(0.50f, 0.50f);
        assertEquals(0.58f, movedPastEdge.x, 1e-4f);
        assertEquals(0.40f, movedPastEdge.y, 1e-4f);

        BlindSpotPlacement.RectRatio resizedPastEdge = base.resizedRightBottomBy(0.50f, 0.50f);
        assertEquals(0.36f, resizedPastEdge.x, 1e-4f);
        assertEquals(0.31f, resizedPastEdge.y, 1e-4f);
        assertEquals(0.64f, resizedPastEdge.width, 1e-4f);
        assertEquals(0.69f, resizedPastEdge.height, 1e-4f);
    }

    @Test
    public void testFromPixelsRoundTrip() {
        BlindSpotPlacement.RectRatio roundTrip = BlindSpotPlacement.fromPixels(
                922, 496, 1075, 960, 2560, 1600);
        assertEquals(36, roundTrip.xPercent());
        assertEquals(31, roundTrip.yPercent());
        assertEquals(42, roundTrip.widthPercent());
        assertEquals(60, roundTrip.heightPercent());
    }

    @Test
    public void testSideSyncAndRainSlotResolution() {
        assertTrue(BlindSpotPlacement.isNormalSideSlot(BlindSpotPlacement.SLOT_LEFT));
        assertTrue(BlindSpotPlacement.isNormalSideSlot(BlindSpotPlacement.SLOT_RIGHT));
        assertFalse(BlindSpotPlacement.isNormalSideSlot(BlindSpotPlacement.SLOT_RAIN_LEFT));
        assertFalse(BlindSpotPlacement.isNormalSideSlot(BlindSpotPlacement.SLOT_FRONT));

        assertEquals(BlindSpotPlacement.SLOT_RIGHT,
                BlindSpotPlacement.oppositeNormalSideSlot(BlindSpotPlacement.SLOT_LEFT));
        assertNull(BlindSpotPlacement.oppositeNormalSideSlot(BlindSpotPlacement.SLOT_RAIN_LEFT));

        assertEquals(BlindSpotPlacement.SLOT_RAIN_LEFT,
                BlindSpotPlacement.resolveSlotForCamera("left", true));
        assertEquals(BlindSpotPlacement.SLOT_RAIN_RIGHT,
                BlindSpotPlacement.resolveSlotForCamera("right", true));
        assertEquals(BlindSpotPlacement.SLOT_FRONT,
                BlindSpotPlacement.resolveSlotForCamera("front", true));
    }

    @Test
    public void testEdgeCropMathExcludesCroppedMarginsAndPreventsCollapse() {
        BlindSpotCorrectionMath.EdgeCrop cropLeftOnly = new BlindSpotCorrectionMath.EdgeCrop(0.20f, 0f, 0f, 0f);
        assertEquals(0.80f, cropLeftOnly.visibleWidth(), 1e-4f);
        assertEquals(1.00f, cropLeftOnly.visibleHeight(), 1e-4f);
        assertEquals(0.60f, cropLeftOnly.centerU(), 1e-4f);
        assertEquals(0.50f, cropLeftOnly.centerV(), 1e-4f);
        assertEquals(1.25f, cropLeftOnly.uniformZoom(), 1e-4f);

        // Verify point u = 0.20 (left cut line) lands at normalized view x = 0.0 after translate+scale
        float shiftedU = 0.20f + cropLeftOnly.preZoomShiftXNorm();
        float finalU = 0.5f + (shiftedU - 0.5f) * cropLeftOnly.uniformZoom();
        assertEquals(0.0f, finalU, 1e-4f);

        // Verify extreme opposite edges still preserve >= 20% visible band
        BlindSpotCorrectionMath.EdgeCrop extreme = new BlindSpotCorrectionMath.EdgeCrop(0.45f, 0.45f, 0.45f, 0.45f);
        assertTrue(extreme.visibleWidth() >= 0.20f - 1e-4f);
        assertTrue(extreme.visibleHeight() >= 0.20f - 1e-4f);
    }

    @Test
    public void testIsotropicCoverScaleCoversRotatedViewportWithoutBlackCorners() {
        // 1280x720 camera in 640x400 view @ 0 deg
        float s0 = BlindSpotCorrectionMath.computeIsotropicCoverScale(1280, 720, 640, 400, 0);
        assertEquals(400f / 720f, s0, 1e-4f);

        // 1280x720 camera in portrait 400x640 view @ 90 deg
        float s90 = BlindSpotCorrectionMath.computeIsotropicCoverScale(1280, 720, 400, 640, 90);
        assertEquals(400f / 720f, s90, 1e-4f);

        // Oblique 45 deg guarantees both rotated source dims exceed corner projections
        float s45 = BlindSpotCorrectionMath.computeIsotropicCoverScale(1280, 720, 640, 400, 45);
        double req = (640 + 400) / Math.sqrt(2.0);
        assertEquals((float) (req / 720.0), s45, 1e-3f);
    }
}
