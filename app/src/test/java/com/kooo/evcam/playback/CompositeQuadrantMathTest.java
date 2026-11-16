package com.kooo.evcam.playback;

import org.junit.Test;

import static org.junit.Assert.*;

public class CompositeQuadrantMathTest {

    @Test
    public void quadrantHitTestMatchesCompositorLayoutOrder() {
        assertEquals(VideoGroup.POSITION_LEFT, CompositeQuadrantMath.quadrantAtNormalized(0.25f, 0.25f));
        assertEquals(VideoGroup.POSITION_BACK, CompositeQuadrantMath.quadrantAtNormalized(0.75f, 0.25f));
        assertEquals(VideoGroup.POSITION_RIGHT, CompositeQuadrantMath.quadrantAtNormalized(0.25f, 0.75f));
        assertEquals(VideoGroup.POSITION_FRONT, CompositeQuadrantMath.quadrantAtNormalized(0.75f, 0.75f));
        assertNull(CompositeQuadrantMath.quadrantAtNormalized(-0.1f, 0.5f));
        assertNull(CompositeQuadrantMath.quadrantAtNormalized(1.1f, 0.5f));
    }

    @Test
    public void transformZoomsEachQuadrantIntoFullViewportWithoutAspectDistortion() {
        float viewW = 1600f;
        float viewH = 1000f;
        float videoW = 1600f;
        float videoH = 1000f;

        CompositeQuadrantMath.TransformSpec full = CompositeQuadrantMath.computeTransform(
                viewW, viewH, videoW, videoH, VideoGroup.POSITION_COMBINED);
        assertEquals(1f, full.scaleX, 0.001f);
        assertEquals(1f, full.scaleY, 0.001f);
        assertEquals(0f, full.translateX, 0.001f);
        assertEquals(0f, full.translateY, 0.001f);

        // Top-left (left): [0..800, 0..500] -> [0..1600, 0..1000]
        CompositeQuadrantMath.TransformSpec left = CompositeQuadrantMath.computeTransform(
                viewW, viewH, videoW, videoH, VideoGroup.POSITION_LEFT);
        assertEquals(2f, left.scaleX, 0.001f);
        assertEquals(2f, left.scaleY, 0.001f);
        assertEquals(0f, left.translateX, 0.001f);
        assertEquals(0f, left.translateY, 0.001f);

        // Bottom-right (front): [800..1600, 500..1000] -> [0..1600, 0..1000]
        CompositeQuadrantMath.TransformSpec front = CompositeQuadrantMath.computeTransform(
                viewW, viewH, videoW, videoH, VideoGroup.POSITION_FRONT);
        assertEquals(2f, front.scaleX, 0.001f);
        assertEquals(2f, front.scaleY, 0.001f);
        assertEquals(-1600f, front.translateX, 0.001f);
        assertEquals(-1000f, front.translateY, 0.001f);
    }
}
