package com.kooo.evcam.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SingleCameraRecordingPreviewIsolationTest {

    private Context context;
    private CameraManager cameraManager;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder requestBuilder;
    private CaptureRequest captureRequest;
    private OutputConfiguration sharedConfig;
    private Surface recordSurface;
    private Surface previewSurface;
    private SurfaceTexture previewSurfaceTexture;
    private TextureView textureView;

    @Before
    public void setUp() throws Exception {
        context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.getString(anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if ("car_model".equals(key)) {
                return "e5";
            }
            return invocation.getArgument(1);
        });
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
        when(prefs.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(prefs.getLong(anyString(), anyLong())).thenAnswer(invocation -> invocation.getArgument(1));
        when(prefs.getFloat(anyString(), anyFloat())).thenAnswer(invocation -> invocation.getArgument(1));

        cameraManager = mock(CameraManager.class);
        CameraCharacteristics characteristics = mock(CameraCharacteristics.class);
        StreamConfigurationMap map = mock(StreamConfigurationMap.class);
        Size size = mock(Size.class);
        when(size.getWidth()).thenReturn(1280);
        when(size.getHeight()).thenReturn(800);
        when(map.getOutputSizes(ImageFormat.PRIVATE)).thenReturn(new Size[]{size});
        when(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)).thenReturn(map);
        when(cameraManager.getCameraCharacteristics("0")).thenReturn(characteristics);

        device = mock(CameraDevice.class);
        session = mock(CameraCaptureSession.class);
        requestBuilder = mock(CaptureRequest.Builder.class);
        captureRequest = mock(CaptureRequest.class);
        when(device.createCaptureRequest(anyInt())).thenReturn(requestBuilder);
        when(requestBuilder.build()).thenReturn(captureRequest);

        sharedConfig = mock(OutputConfiguration.class);
        when(sharedConfig.getSurfaces()).thenReturn(Collections.emptyList());

        recordSurface = mock(Surface.class);
        when(recordSurface.isValid()).thenReturn(true);

        previewSurface = mock(Surface.class);
        when(previewSurface.isValid()).thenReturn(true);

        previewSurfaceTexture = mock(SurfaceTexture.class);
        textureView = mock(TextureView.class);
        when(textureView.isAvailable()).thenReturn(true);
        when(textureView.getSurfaceTexture()).thenReturn(previewSurfaceTexture);
    }

    private SingleCamera createSpyCamera(TextureView initialView) {
        SingleCamera camera = spy(new SingleCamera(context, "0", initialView, cameraManager));
        doNothing().when(camera).openCamera();
        doNothing().when(camera).recreateSession();
        doNothing().when(camera).recreateSession(anyBoolean());
        doNothing().when(camera).applyPreviewTransformIfUnmanaged();
        return camera;
    }

    @Test
    public void hidingAndRestoringMainPreviewDuringRecordingSwitchesRepeatingTargetsWithoutClosingSession()
            throws Exception {
        SingleCamera camera = createSpyCamera(textureView);
        camera.setRecordSurface(recordSurface, true);
        camera.attachRecordingSessionStateForTest(
                device, session, sharedConfig,
                previewSurface, previewSurfaceTexture, previewSurface,
                true, previewSurface, recordSurface);

        // Hide preview (e.g., Mode D side switch or onStop)
        camera.setMainPreviewEnabled(false);

        verify(session, never()).close();
        verify(session, never()).stopRepeating();
        verify(camera, never()).recreateSession();
        verify(camera, never()).recreateSession(anyBoolean());
        verify(previewSurface, never()).release();
        assertFalse(camera.isSessionPreviewInRepeatingForTest());

        InOrder hideOrder = inOrder(requestBuilder, session);
        hideOrder.verify(requestBuilder).addTarget(recordSurface);
        hideOrder.verify(session).setRepeatingRequest(eq(captureRequest), any(), nullable(android.os.Handler.class));
        verify(requestBuilder, never()).addTarget(previewSurface);

        // Restore preview (same TextureView still available)
        reset(requestBuilder, session);
        when(device.createCaptureRequest(anyInt())).thenReturn(requestBuilder);
        when(requestBuilder.build()).thenReturn(captureRequest);

        camera.setMainPreviewEnabled(true);

        verify(session, never()).close();
        verify(camera, never()).recreateSession();
        verify(camera, never()).recreateSession(anyBoolean());
        assertTrue(camera.isSessionPreviewInRepeatingForTest());
        verify(requestBuilder).addTarget(recordSurface);
        verify(requestBuilder).addTarget(previewSurface);
        verify(session).setRepeatingRequest(eq(captureRequest), any(), nullable(android.os.Handler.class));
    }

    @Test
    public void hidingAndRestoringMainPreviewPreservesBlindSpotExternalDisplayAndStreamSurfaces()
            throws Exception {
        Surface blindSpotSurface = mock(Surface.class);
        Surface secondarySurface = mock(Surface.class);
        Surface streamSurface = mock(Surface.class);
        when(blindSpotSurface.isValid()).thenReturn(true);
        when(secondarySurface.isValid()).thenReturn(true);
        when(streamSurface.isValid()).thenReturn(true);

        SingleCamera camera = createSpyCamera(textureView);
        camera.setRecordSurface(recordSurface, true);
        camera.setMainFloatingSurface(blindSpotSurface);
        camera.setSecondaryDisplaySurface(secondarySurface);
        camera.setStreamSurface(streamSurface, null);
        reset(camera);
        doNothing().when(camera).openCamera();
        doNothing().when(camera).recreateSession();
        doNothing().when(camera).recreateSession(anyBoolean());
        doNothing().when(camera).applyPreviewTransformIfUnmanaged();

        camera.attachRecordingSessionStateForTest(
                device, session, sharedConfig,
                previewSurface, previewSurfaceTexture, previewSurface,
                true, previewSurface, blindSpotSurface, secondarySurface, streamSurface, recordSurface);

        camera.setMainPreviewEnabled(false);

        verify(session, never()).close();
        verify(requestBuilder).addTarget(recordSurface);
        verify(requestBuilder).addTarget(blindSpotSurface);
        verify(requestBuilder).addTarget(secondarySurface);
        verify(requestBuilder).addTarget(streamSurface);
        verify(requestBuilder, never()).addTarget(previewSurface);
        verify(session).setRepeatingRequest(eq(captureRequest), any(), nullable(android.os.Handler.class));
    }

    @Test
    public void previewTextureDestroyedDuringRecordingDetachesTargetSynchronouslyAndDefersSurfaceRelease()
            throws Exception {
        SingleCamera camera = createSpyCamera(textureView);
        camera.setRecordSurface(recordSurface, true);
        camera.attachRecordingSessionStateForTest(
                device, session, sharedConfig,
                previewSurface, previewSurfaceTexture, previewSurface,
                true, previewSurface, recordSurface);

        // Stale surfaceTexture must be ignored.
        SurfaceTexture staleTexture = mock(SurfaceTexture.class);
        camera.onPreviewTextureDestroyed(staleTexture);
        verify(session, never()).setRepeatingRequest(any(), any(), any());

        // Matching surfaceTexture stops repeating to previewSurface immediately, keeps session & recordSurface alive,
        // and defers previewSurface.release() until the session closes.
        camera.onPreviewTextureDestroyed(previewSurfaceTexture);

        verify(session, never()).close();
        verify(session, never()).stopRepeating();
        verify(camera, never()).recreateSession();
        verify(camera, never()).recreateSession(anyBoolean());
        verify(requestBuilder).addTarget(recordSurface);
        verify(requestBuilder, never()).addTarget(previewSurface);
        verify(session).setRepeatingRequest(eq(captureRequest), any(), nullable(android.os.Handler.class));
        verify(previewSurface, never()).release();
        assertTrue(camera.isSessionPreviewSurfaceDeadForTest());
        assertTrue(camera.isActivePreviewPrimaryAbandonedForTest());
        assertFalse(camera.isSessionPreviewInRepeatingForTest());
        assertEquals(1, camera.getPendingReleasePreviewSurfaceCountForTest());

        camera.closeCameraAndAwait(1000L);
        verify(previewSurface).release();
        assertEquals(0, camera.getPendingReleasePreviewSurfaceCountForTest());
    }

    @Test
    public void newTextureOnForegroundUsesDynamicAddWhenSharedPrimaryHealthyAndRecreatesWhenPrimaryAbandoned()
            throws Exception {
        // Case A: Recording started with blind-spot surface as primary; foreground TextureView arrives later.
        Surface blindSpotSurface = mock(Surface.class);
        when(blindSpotSurface.isValid()).thenReturn(true);
        Surface newPreviewSurface = mock(Surface.class);
        when(newPreviewSurface.isValid()).thenReturn(true);
        SurfaceTexture newTexture = mock(SurfaceTexture.class);
        TextureView newView = mock(TextureView.class);
        when(newView.isAvailable()).thenReturn(true);
        when(newView.getSurfaceTexture()).thenReturn(newTexture);

        SingleCamera cameraA = createSpyCamera(null);
        doReturn(newPreviewSurface).when(cameraA).createPreviewSurfaceFromTexture(newTexture);
        cameraA.setRecordSurface(recordSurface, true);
        cameraA.setMainFloatingSurface(blindSpotSurface);
        cameraA.setMainPreviewEnabled(false);
        cameraA.attachRecordingSessionStateForTest(
                device, session, sharedConfig,
                null, null, blindSpotSurface,
                false, blindSpotSurface, recordSurface);

        cameraA.setTextureView(newView);
        cameraA.setMainPreviewEnabled(true);

        verify(sharedConfig).addSurface(newPreviewSurface);
        verify(session).updateOutputConfiguration(sharedConfig);
        verify(requestBuilder).addTarget(recordSurface);
        verify(requestBuilder).addTarget(newPreviewSurface);
        verify(requestBuilder).addTarget(blindSpotSurface);
        verify(session).setRepeatingRequest(eq(captureRequest), any(), nullable(android.os.Handler.class));
        verify(session, never()).close();

        // Case B: updateOutputConfiguration fails -> gracefully degrades to full rebuild instead of crashing.
        Surface failingPreviewSurface = mock(Surface.class);
        when(failingPreviewSurface.isValid()).thenReturn(true);
        OutputConfiguration failingConfig = mock(OutputConfiguration.class);
        when(failingConfig.getSurfaces()).thenReturn(Collections.emptyList());
        CameraCaptureSession sessionB = mock(CameraCaptureSession.class);
        doThrow(new IllegalArgumentException("driver does not support updateOutputConfiguration"))
                .when(sessionB).updateOutputConfiguration(failingConfig);

        SingleCamera cameraB = createSpyCamera(null);
        doReturn(failingPreviewSurface).when(cameraB).createPreviewSurfaceFromTexture(newTexture);
        cameraB.setRecordSurface(recordSurface, true);
        cameraB.setMainFloatingSurface(blindSpotSurface);
        cameraB.setMainPreviewEnabled(false);
        cameraB.attachRecordingSessionStateForTest(
                device, sessionB, failingConfig,
                null, null, blindSpotSurface,
                false, blindSpotSurface, recordSurface);

        cameraB.setTextureView(newView);
        cameraB.setMainPreviewEnabled(true);

        // Fallback calls createCameraPreviewSession() -> closes sessionB once to rebuild cleanly.
        verify(sessionB).close();

        // Case C: Old primary preview was destroyed in background -> new foreground TextureView triggers single recreate.
        SingleCamera cameraC = createSpyCamera(textureView);
        cameraC.setRecordSurface(recordSurface, true);
        cameraC.attachRecordingSessionStateForTest(
                device, session, sharedConfig,
                previewSurface, previewSurfaceTexture, previewSurface,
                true, previewSurface, recordSurface);
        cameraC.onPreviewTextureDestroyed(previewSurfaceTexture);

        reset(cameraC);
        doNothing().when(cameraC).openCamera();
        doNothing().when(cameraC).recreateSession();
        doNothing().when(cameraC).recreateSession(anyBoolean());
        doNothing().when(cameraC).applyPreviewTransformIfUnmanaged();

        cameraC.setTextureView(newView);
        cameraC.syncMainPreviewOutput();
        verify(cameraC).recreateSession();
    }

    @Test
    public void repeatingRequestFailureFallsBackToSessionRebuildInsteadOfStalling() throws Exception {
        doThrow(new IllegalStateException("session closed"))
                .when(session).setRepeatingRequest(any(), any(), any());

        SingleCamera camera = createSpyCamera(textureView);
        camera.setRecordSurface(recordSurface, true);
        camera.attachRecordingSessionStateForTest(
                device, session, sharedConfig,
                previewSurface, previewSurfaceTexture, previewSurface,
                true, previewSurface, recordSurface);

        camera.setMainPreviewEnabled(false);

        // Falls back to createCameraPreviewSession() which initiates closing the broken session.
        verify(session).close();
    }

    @Test
    public void clearingPrimarySurround360SurfaceDuringRecordingSwitchesRepeatingWithoutStoppingOrClosingSession()
            throws Exception {
        Surface surroundSurface = mock(Surface.class);
        when(surroundSurface.isValid()).thenReturn(true);

        SingleCamera camera = createSpyCamera(null);
        camera.setRecordSurface(recordSurface, true);
        camera.setMainPreviewEnabled(false);
        camera.setSurroundPreviewSurface(surroundSurface, null, false);
        reset(camera);
        doNothing().when(camera).openCamera();
        doNothing().when(camera).recreateSession();
        doNothing().when(camera).recreateSession(anyBoolean());
        doNothing().when(camera).applyPreviewTransformIfUnmanaged();

        camera.attachRecordingSessionStateForTest(
                device, session, sharedConfig,
                null, null, surroundSurface,
                false, surroundSurface, recordSurface);

        camera.setSurroundPreviewSurface(null, null);

        verify(session, never()).stopRepeating();
        verify(session, never()).close();
        verify(camera, never()).recreateSession();
        verify(camera, never()).recreateSession(anyBoolean());
        assertTrue(camera.isActivePreviewPrimaryAbandonedForTest());
        verify(requestBuilder).addTarget(recordSurface);
        verify(requestBuilder, never()).addTarget(surroundSurface);
        verify(session).setRepeatingRequest(eq(captureRequest), any(), nullable(android.os.Handler.class));
    }

    @Test
    public void previewFrameTimestampOnlyAdvancesWhilePreviewIsInRepeating() throws Exception {
        SingleCamera camera = new SingleCamera(context, "0", textureView, cameraManager);
        camera.setRecordSurface(recordSurface, true);
        camera.attachRecordingSessionStateForTest(
                device, session, sharedConfig,
                previewSurface, previewSurfaceTexture, previewSurface,
                true, previewSurface, recordSurface);

        TotalCaptureResult result = mock(TotalCaptureResult.class);

        // While preview is active in repeating, both timestamps advance.
        camera.triggerOnCaptureCompletedForTest(session, captureRequest, result);
        long firstFrameTs = camera.getLastFrameTimestampMs();
        long firstPreviewTs = camera.getLastPreviewFrameTimestampMs();
        assertTrue(firstFrameTs > 0L);
        assertEquals(firstFrameTs, firstPreviewTs);

        // Pause preview during recording.
        camera.setMainPreviewEnabled(false);
        assertFalse(camera.isSessionPreviewInRepeatingForTest());

        Thread.sleep(15L);
        camera.triggerOnCaptureCompletedForTest(session, captureRequest, result);
        assertTrue(camera.getLastFrameTimestampMs() > firstFrameTs);
        assertEquals(firstPreviewTs, camera.getLastPreviewFrameTimestampMs());

        // Restore preview -> preview frame timestamp advances again.
        camera.setMainPreviewEnabled(true);
        assertTrue(camera.isSessionPreviewInRepeatingForTest());
        Thread.sleep(15L);
        camera.triggerOnCaptureCompletedForTest(session, captureRequest, result);
        assertTrue(camera.getLastPreviewFrameTimestampMs() > firstPreviewTs);
    }

    @Test
    public void enablingMainPreviewDuringRecordingBeforeTextureAvailableIsNoOp() throws Exception {
        TextureView unavailableView = mock(TextureView.class);
        when(unavailableView.isAvailable()).thenReturn(false);

        SingleCamera camera = createSpyCamera(unavailableView);
        camera.setRecordSurface(recordSurface, true);
        camera.setMainPreviewEnabled(false);
        camera.attachRecordingSessionStateForTest(
                device, session, sharedConfig,
                null, null, null,
                false, recordSurface);

        camera.setMainPreviewEnabled(true);

        verify(session, never()).close();
        verify(session, never()).setRepeatingRequest(any(), any(), any());
        verify(camera, never()).recreateSession();
        verify(camera, never()).recreateSession(anyBoolean());
    }

    @Test
    public void directCodecInputSizeSupportChecksMediaCodecAndFallsBackToMediaRecorder() throws Exception {
        CameraCharacteristics chars = mock(CameraCharacteristics.class);
        StreamConfigurationMap map = mock(StreamConfigurationMap.class);
        Size size1280 = mock(Size.class);
        when(size1280.getWidth()).thenReturn(1280);
        when(size1280.getHeight()).thenReturn(800);
        Size size640 = mock(Size.class);
        when(size640.getWidth()).thenReturn(640);
        when(size640.getHeight()).thenReturn(480);

        when(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)).thenReturn(map);
        when(cameraManager.getCameraCharacteristics("1")).thenReturn(chars);

        when(map.getOutputSizes(android.media.MediaCodec.class)).thenReturn(new Size[]{size1280});
        SingleCamera camWithCodecSizes = new SingleCamera(context, "1", textureView, cameraManager);
        assertTrue(camWithCodecSizes.isDirectCodecInputSizeSupported(1280, 800));
        assertFalse(camWithCodecSizes.isDirectCodecInputSizeSupported(1920, 1080));
        assertFalse(camWithCodecSizes.isDirectCodecInputSizeSupported(0, 800));

        CameraCharacteristics fallbackChars = mock(CameraCharacteristics.class);
        StreamConfigurationMap fallbackMap = mock(StreamConfigurationMap.class);
        when(fallbackChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)).thenReturn(fallbackMap);
        when(fallbackMap.getOutputSizes(android.media.MediaCodec.class)).thenReturn(null);
        when(fallbackMap.getOutputSizes(android.media.MediaRecorder.class)).thenReturn(new Size[]{size640});
        when(cameraManager.getCameraCharacteristics("2")).thenReturn(fallbackChars);

        SingleCamera camFallbackRecorder = new SingleCamera(context, "2", textureView, cameraManager);
        assertTrue(camFallbackRecorder.isDirectCodecInputSizeSupported(640, 480));
        assertFalse(camFallbackRecorder.isDirectCodecInputSizeSupported(1280, 800));
    }

    @Test
    public void pauseRecordSurfaceDeliveryStopsSendingToRecordSurfaceWithoutClosingSession() throws Exception {
        SingleCamera foregroundCam = createSpyCamera(textureView);
        foregroundCam.setRecordSurface(recordSurface, true);
        foregroundCam.setMainPreviewEnabled(true);
        foregroundCam.attachRecordingSessionStateForTest(
                device, session, sharedConfig,
                previewSurface, previewSurfaceTexture, previewSurface,
                true, previewSurface, recordSurface);

        assertTrue(foregroundCam.pauseRecordSurfaceDelivery());
        verify(session, never()).close();
        verify(session, never()).stopRepeating();
        verify(requestBuilder).addTarget(previewSurface);
        verify(requestBuilder, never()).addTarget(recordSurface);
        verify(session).setRepeatingRequest(eq(captureRequest), any(), nullable(android.os.Handler.class));
        assertTrue(foregroundCam.hasActiveRecordSurface());

        CameraCaptureSession bgSession = mock(CameraCaptureSession.class);
        SingleCamera backgroundCam = createSpyCamera(null);
        backgroundCam.setRecordSurface(recordSurface, true);
        backgroundCam.setMainPreviewEnabled(false);
        backgroundCam.attachRecordingSessionStateForTest(
                device, bgSession, sharedConfig,
                null, null, null,
                false, recordSurface);

        assertTrue(backgroundCam.pauseRecordSurfaceDelivery());
        verify(bgSession).stopRepeating();
        verify(bgSession, never()).close();
        assertTrue(backgroundCam.hasActiveRecordSurface());
    }
}
