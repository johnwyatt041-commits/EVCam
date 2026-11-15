package com.kooo.evcam.blindspot;

import com.kooo.evcam.ui.PreviewTransformHelper;

import org.junit.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class CameraMirrorPolicyTest {

    private static final class InMemoryMirrorStore implements CameraMirrorPolicy.Store {
        private final Map<String, Boolean> physH = new HashMap<>();
        private final Map<String, Boolean> physV = new HashMap<>();
        private final Map<MirrorTarget, Map<String, Boolean>> outH = new EnumMap<>(MirrorTarget.class);
        private final Map<MirrorTarget, Map<String, Boolean>> outV = new EnumMap<>(MirrorTarget.class);

        void setPhys(String cam, boolean h, boolean v) {
            physH.put(cam, h);
            physV.put(cam, v);
        }

        void setOut(MirrorTarget target, String cam, boolean h, boolean v) {
            outH.computeIfAbsent(target, k -> new HashMap<>()).put(cam, h);
            outV.computeIfAbsent(target, k -> new HashMap<>()).put(cam, v);
        }

        @Override
        public boolean isPhysicalMirrorH(String cameraPosition) {
            return Boolean.TRUE.equals(physH.get(cameraPosition));
        }

        @Override
        public boolean isPhysicalMirrorV(String cameraPosition) {
            return Boolean.TRUE.equals(physV.get(cameraPosition));
        }

        @Override
        public boolean isOutputMirrorH(MirrorTarget target, String cameraPosition) {
            Map<String, Boolean> map = outH.get(target);
            if (map != null && map.containsKey(cameraPosition)) {
                return Boolean.TRUE.equals(map.get(cameraPosition));
            }
            return CameraMirrorPolicy.defaultOutputMirrorH(target, cameraPosition);
        }

        @Override
        public boolean isOutputMirrorV(MirrorTarget target, String cameraPosition) {
            Map<String, Boolean> map = outV.get(target);
            return map != null && Boolean.TRUE.equals(map.get(cameraPosition));
        }
    }

    @Test
    public void effectiveMirrorIsXorOfPhysicalAndOutputAcrossAllTruthCombinations() {
        boolean[] bools = {false, true};
        for (boolean pH : bools) {
            for (boolean pV : bools) {
                for (boolean oH : bools) {
                    for (boolean oV : bools) {
                        CameraMirrorPolicy.ResolvedMirror resolved =
                                CameraMirrorPolicy.combine(pH, pV, oH, oV);
                        assertEquals(pH ^ oH, resolved.effectiveH);
                        assertEquals(pV ^ oV, resolved.effectiveV);
                        assertEquals(pH, resolved.physicalH);
                        assertEquals(pV, resolved.physicalV);
                        assertEquals(oH, resolved.outputH);
                        assertEquals(oV, resolved.outputV);
                    }
                }
            }
        }
    }

    @Test
    public void defaultOutputMirrorMirrorsOnlyMainPreviewRearCamera() {
        assertTrue(CameraMirrorPolicy.defaultOutputMirrorH(MirrorTarget.MAIN_PREVIEW, "back"));
        assertFalse(CameraMirrorPolicy.defaultOutputMirrorH(MirrorTarget.MAIN_PREVIEW, "front"));
        assertFalse(CameraMirrorPolicy.defaultOutputMirrorH(MirrorTarget.MAIN_PREVIEW, "left"));
        assertFalse(CameraMirrorPolicy.defaultOutputMirrorH(MirrorTarget.MAIN_PREVIEW, "right"));

        assertFalse(CameraMirrorPolicy.defaultOutputMirrorH(MirrorTarget.BLIND_SPOT, "back"));
        assertFalse(CameraMirrorPolicy.defaultOutputMirrorH(MirrorTarget.SECONDARY_HUD, "back"));
        assertFalse(CameraMirrorPolicy.defaultOutputMirrorH(MirrorTarget.NETWORK_STREAM, "back"));
    }

    @Test
    public void physicalAndPerTargetOutputMirrorsAreDecoupled() {
        InMemoryMirrorStore store = new InMemoryMirrorStore();
        store.setPhys("left", true, false);
        store.setOut(MirrorTarget.BLIND_SPOT, "left", true, false);
        store.setOut(MirrorTarget.SECONDARY_HUD, "left", false, false);
        store.setOut(MirrorTarget.NETWORK_STREAM, "left", false, true);

        // Blind spot output flips physicalH back cleanly; secondary HUD & network stream stay unaffected.
        CameraMirrorPolicy.ResolvedMirror blindSpot =
                CameraMirrorPolicy.resolve(store, MirrorTarget.BLIND_SPOT, "left");
        assertTrue(blindSpot.physicalH);
        assertTrue(blindSpot.outputH);
        assertFalse(blindSpot.effectiveH);

        CameraMirrorPolicy.ResolvedMirror secondary =
                CameraMirrorPolicy.resolve(store, MirrorTarget.SECONDARY_HUD, "left");
        assertTrue(secondary.physicalH);
        assertFalse(secondary.outputH);
        assertTrue(secondary.effectiveH);

        CameraMirrorPolicy.ResolvedMirror stream =
                CameraMirrorPolicy.resolve(store, MirrorTarget.NETWORK_STREAM, "left");
        assertTrue(stream.physicalH);
        assertFalse(stream.outputH);
        assertTrue(stream.outputV);
        assertTrue(stream.effectiveH);
        assertTrue(stream.effectiveV);
    }

    @Test
    public void mirrorTargetKeyNormalizationFallsBackToBlindSpot() {
        assertEquals(MirrorTarget.MAIN_PREVIEW, MirrorTarget.fromKey("main"));
        assertEquals(MirrorTarget.MAIN_PREVIEW, MirrorTarget.fromKey("main_preview"));
        assertEquals(MirrorTarget.SECONDARY_HUD, MirrorTarget.fromKey("secondary"));
        assertEquals(MirrorTarget.SECONDARY_HUD, MirrorTarget.fromKey("SECONDARY_HUD"));
        assertEquals(MirrorTarget.NETWORK_STREAM, MirrorTarget.fromKey("stream"));
        assertEquals(MirrorTarget.NETWORK_STREAM, MirrorTarget.fromKey("network_stream"));
        assertEquals(MirrorTarget.BLIND_SPOT, MirrorTarget.fromKey("blind_spot"));
        assertEquals(MirrorTarget.BLIND_SPOT, MirrorTarget.fromKey("unknown"));
        assertEquals(MirrorTarget.BLIND_SPOT, MirrorTarget.fromKey(null));
    }

    @Test
    public void nullGuardsAndMatrixBuilderHandleBoundaryInputs() {
        CameraMirrorPolicy.ResolvedMirror empty = CameraMirrorPolicy.resolve(null, MirrorTarget.MAIN_PREVIEW, "back");
        assertFalse(empty.effectiveH);
        assertFalse(empty.effectiveV);

        assertNotNull(PreviewTransformHelper.buildMatrix(0, 0, 90f, 1f, 1f, 0f, 0f, empty));
        assertNotNull(PreviewTransformHelper.buildMatrix(1280, 720, 270f, 1.1f, 1.1f, 10f, -5f,
                CameraMirrorPolicy.combine(true, false, false, true)));
    }
}
