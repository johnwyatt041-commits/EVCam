package com.kooo.evcam.camera;

import android.content.Context;
import android.view.Surface;
import android.view.TextureView;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SingleCameraResourceTest {
    private SingleCamera camera;
    private Surface surface;

    @Before public void setUp() {
        camera = spy(new SingleCamera(mock(Context.class), "front", null));
        doNothing().when(camera).openCamera();
        surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
    }

    @Test public void hiddenMainPreviewDoesNotKeepUnusedCameraAlive() {
        TextureView preview = mock(TextureView.class);
        when(preview.isAvailable()).thenReturn(true);
        camera.setTextureView(preview);
        assertTrue(camera.hasActiveOutputs());

        camera.setMainPreviewEnabled(false);
        assertFalse(camera.hasActiveOutputs());
        assertTrue(camera.closeIfUnused());
    }

    @Test public void backgroundRecordingRemainsRequiredUntilItsSurfaceIsCleared() {
        camera.setRecordSurface(surface, false);
        camera.setMainPreviewEnabled(false);
        assertTrue(camera.hasActiveOutputs());
        assertFalse(camera.closeIfUnused());

        camera.clearRecordSurface();
        assertFalse(camera.hasActiveOutputs());
        assertTrue(camera.closeIfUnused());
    }

    @Test public void blindSpotWindowKeepsCameraAliveWithoutMainPreview() {
        camera.setMainFloatingSurface(surface);
        camera.setMainPreviewEnabled(false);
        assertFalse(camera.closeIfUnused());

        camera.setMainFloatingSurface(null);
        assertTrue(camera.closeIfUnused());
    }

    @Test public void externalDisplayFullscreenAndStreamEachRetainTheirCamera() {
        camera.setMainPreviewEnabled(false);
        camera.setSecondaryDisplaySurface(surface);
        assertFalse(camera.closeIfUnused());
        camera.setSecondaryDisplaySurface(null);
        assertTrue(camera.closeIfUnused());

        camera.setFullscreenPreviewSurface(surface);
        assertFalse(camera.closeIfUnused());
        camera.setFullscreenPreviewSurface(null);
        assertTrue(camera.closeIfUnused());

        camera.setStreamSurface(surface, null);
        assertFalse(camera.closeIfUnused());
        camera.setStreamSurface(null, null);
        assertTrue(camera.closeIfUnused());
    }

    @Test public void invalidSurfaceDoesNotKeepCameraAlive() {
        when(surface.isValid()).thenReturn(false);
        camera.setRecordSurface(surface, false);
        assertTrue(camera.closeIfUnused());
    }

    @Test public void wifiHudOpensCameraWithMainPreviewOffAndReleasesItsDemandOnStop() {
        camera.setMainPreviewEnabled(false);
        camera.setStreamSurface(surface, null);
        verify(camera).openCamera();
        assertTrue(camera.hasActiveOutputs());
        camera.setStreamSurface(null, null);
        assertFalse(camera.hasActiveOutputs());
    }

    @Test public void stoppingHudStreamPreservesAnActiveRecordingOutput() {
        camera.setMainPreviewEnabled(false);
        camera.setRecordSurface(surface, false);
        camera.setStreamSurface(surface, null);
        camera.setStreamSurface(null, null);
        assertTrue(camera.hasActiveOutputs());
        assertFalse(camera.closeIfUnused());
    }

    @Test public void streamSurfaceServesAsPrimaryFallbackWhenMainPreviewIsHidden() {
        camera.setMainPreviewEnabled(false);
        camera.setStreamSurface(surface, null);
        assertEquals(surface, camera.selectPrimaryPreviewSurface());
        assertEquals(1, camera.collectActiveNonRecordSurfaces().size());
        assertEquals(surface, camera.collectActiveNonRecordSurfaces().get(0));
    }

    @Test public void hiddenCameraReopenedWhenPreviewReEnabled() {
        TextureView preview = mock(TextureView.class);
        when(preview.isAvailable()).thenReturn(true);
        camera.setTextureView(preview);
        camera.setMainPreviewEnabled(false);
        assertFalse(camera.isMainPreviewEnabled());
        assertTrue(camera.closeIfUnused());

        reset(camera);
        doNothing().when(camera).openCamera();
        camera.setMainPreviewEnabled(true);
        assertTrue(camera.isMainPreviewEnabled());
        verify(camera).openCamera();
    }

    @Test public void surround360SurfaceRetainsCameraOnlyWhileModeAActiveAndReleasesCleanly() {
        camera.setMainPreviewEnabled(false);
        camera.setSurroundPreviewSurface(surface, null);
        verify(camera).openCamera();
        assertTrue(camera.hasActiveOutputs());
        assertTrue(camera.isSurroundPreviewSurfaceBound());
        assertFalse(camera.closeIfUnused());

        // 若正在录像时退出模式 A，只解绑 360 预览流，不断开录像
        camera.setRecordSurface(surface, true);
        camera.setSurroundPreviewSurface(null, null);
        assertFalse(camera.isSurroundPreviewSurfaceBound());
        assertTrue(camera.hasActiveOutputs());

        // 若未在录像时退出模式 A，释放所有闲置相机
        camera.clearRecordSurface();
        assertFalse(camera.hasActiveOutputs());
        assertTrue(camera.closeIfUnused());
    }
}
