package com.kooo.evcam.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.util.Size;
import android.view.TextureView;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SingleCameraLifecycleTest {

    private Context context;
    private CameraManager cameraManager;

    @Before
    public void setUp() throws Exception {
        context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.getString(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        cameraManager = mock(CameraManager.class);
        when(cameraManager.getCameraIdList()).thenReturn(new String[]{"0"});

        CameraCharacteristics characteristics = mock(CameraCharacteristics.class);
        StreamConfigurationMap map = mock(StreamConfigurationMap.class);
        Size size = mock(Size.class);
        when(size.getWidth()).thenReturn(1280);
        when(size.getHeight()).thenReturn(800);
        when(map.getOutputSizes(ImageFormat.PRIVATE)).thenReturn(new Size[]{size});
        when(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)).thenReturn(map);
        when(cameraManager.getCameraCharacteristics("0")).thenReturn(characteristics);
    }

    @Test
    public void closeCameraDoesNotBlockCallerThreadWhenDeviceCloseIsSlow() throws Exception {
        SingleCamera camera = new SingleCamera(context, "0", null, cameraManager);
        CameraDevice slowDevice = mock(CameraDevice.class);
        CameraCaptureSession slowSession = mock(CameraCaptureSession.class);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch allowCloseFinish = new CountDownLatch(1);
        CountDownLatch closedCallback = new CountDownLatch(1);

        doAnswer(invocation -> {
            closeStarted.countDown();
            allowCloseFinish.await(2, TimeUnit.SECONDS);
            return null;
        }).when(slowDevice).close();

        camera.setCallback(new CameraCallback() {
            @Override public void onCameraOpened(String cameraId) {}
            @Override public void onCameraConfigured(String cameraId) {}
            @Override public void onCameraClosed(String cameraId) { closedCallback.countDown(); }
            @Override public void onCameraError(String cameraId, int errorCode) {}
            @Override public void onPreviewSizeChosen(String cameraId, Size previewSize) {}
        });
        camera.attachLifecycleStateForTest(slowDevice, slowSession, null);

        long startMs = System.currentTimeMillis();
        camera.closeCamera();
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertTrue("closeCamera() should return without blocking caller thread, took " + elapsedMs + "ms",
                elapsedMs < 150);
        assertFalse(camera.isConnected());
        assertTrue(closeStarted.await(1, TimeUnit.SECONDS));

        allowCloseFinish.countDown();
        assertTrue(closedCallback.await(1, TimeUnit.SECONDS));
        verify(slowSession).close();
        verify(slowDevice).close();
        camera.shutdownLifecycleExecutor(500);
    }

    @Test
    public void rapidCloseThenOpenSerializesCloseBeforeOpen() throws Exception {
        SingleCamera camera = new SingleCamera(context, "0", null, cameraManager);
        CameraDevice oldDevice = mock(CameraDevice.class);
        CameraCaptureSession oldSession = mock(CameraCaptureSession.class);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch openInvoked = new CountDownLatch(1);

        doAnswer(invocation -> {
            events.add("close_start");
            Thread.sleep(150);
            events.add("close_end");
            return null;
        }).when(oldDevice).close();

        doAnswer(invocation -> {
            events.add("open_call");
            openInvoked.countDown();
            return null;
        }).when(cameraManager).openCamera(eq("0"), any(CameraDevice.StateCallback.class), nullable(Handler.class));

        camera.attachLifecycleStateForTest(oldDevice, oldSession, null);

        camera.closeCamera();
        camera.openCamera();

        assertTrue(openInvoked.await(2, TimeUnit.SECONDS));
        assertEquals(3, events.size());
        assertEquals("close_start", events.get(0));
        assertEquals("close_end", events.get(1));
        assertEquals("open_call", events.get(2));
        camera.shutdownLifecycleExecutor(500);
    }

    @Test
    public void staleOnOpenedAfterCloseIsImmediatelyClosedAndIgnored() throws Exception {
        SingleCamera camera = new SingleCamera(context, "0", null, cameraManager);
        CountDownLatch openAttempt = new CountDownLatch(1);
        AtomicInteger openedCallbacks = new AtomicInteger(0);

        doAnswer(invocation -> {
            openAttempt.countDown();
            return null;
        }).when(cameraManager).openCamera(eq("0"), any(CameraDevice.StateCallback.class), nullable(Handler.class));

        camera.setCallback(new CameraCallback() {
            @Override public void onCameraOpened(String cameraId) { openedCallbacks.incrementAndGet(); }
            @Override public void onCameraConfigured(String cameraId) {}
            @Override public void onCameraClosed(String cameraId) {}
            @Override public void onCameraError(String cameraId, int errorCode) {}
            @Override public void onPreviewSizeChosen(String cameraId, Size previewSize) {}
        });

        camera.openCamera();
        assertTrue(openAttempt.await(1, TimeUnit.SECONDS));
        CameraDevice staleDevice = mock(CameraDevice.class);

        // Simulate close occurring before delayed onOpened callback arrives.
        camera.closeCamera();
        camera.triggerOnOpenedForTest(staleDevice);

        assertFalse(camera.isConnected());
        assertEquals(0, openedCallbacks.get());
        verify(staleDevice).close();
        camera.shutdownLifecycleExecutor(500);
    }

    @Test
    public void closeCameraAndAwaitWaitsForAsyncTeardown() {
        SingleCamera camera = new SingleCamera(context, "0", null, cameraManager);
        CameraDevice device = mock(CameraDevice.class);
        doAnswer(invocation -> {
            Thread.sleep(80);
            return null;
        }).when(device).close();

        camera.attachLifecycleStateForTest(device, null, null);
        camera.closeCameraAndAwait(1500L);

        verify(device).close();
        assertFalse(camera.isConnected());
        camera.shutdownLifecycleExecutor(500);
    }

    @Test
    public void hiddenModeSwitchDoesNotBlockUiThreadAcrossMultipleIdleCameras() throws Exception {
        TextureView backPreview = mock(TextureView.class);
        TextureView rightPreview = mock(TextureView.class);
        when(backPreview.isAvailable()).thenReturn(true);
        when(rightPreview.isAvailable()).thenReturn(true);

        SingleCamera back = new SingleCamera(context, "0", backPreview, cameraManager);
        SingleCamera right = new SingleCamera(context, "0", rightPreview, cameraManager);
        CameraDevice slowBack = mock(CameraDevice.class);
        CameraDevice slowRight = mock(CameraDevice.class);
        CountDownLatch bothClosed = new CountDownLatch(2);

        doAnswer(inv -> { Thread.sleep(200); bothClosed.countDown(); return null; }).when(slowBack).close();
        doAnswer(inv -> { Thread.sleep(200); bothClosed.countDown(); return null; }).when(slowRight).close();

        back.attachLifecycleStateForTest(slowBack, null, null);
        right.attachLifecycleStateForTest(slowRight, null, null);

        long startMs = System.currentTimeMillis();
        back.setMainPreviewEnabled(false);
        right.setMainPreviewEnabled(false);
        long uiElapsedMs = System.currentTimeMillis() - startMs;

        assertTrue("Switching B->D must not block UI thread while 2 cameras tear down, took " + uiElapsedMs + "ms",
                uiElapsedMs < 150);
        assertTrue(bothClosed.await(2, TimeUnit.SECONDS));
        back.shutdownLifecycleExecutor(500);
        right.shutdownLifecycleExecutor(500);
    }

    @Test
    public void sessionCloseFallbackClearsStuckClosingFlag() {
        SingleCamera camera = new SingleCamera(context, "0", null, cameraManager);
        CameraDevice device = mock(CameraDevice.class);
        camera.attachLifecycleStateForTest(device, null, null);

        camera.setSessionFlagsForTest(false, true);
        assertTrue(camera.isConfiguringOrClosingForTest());

        camera.triggerSessionCloseFallbackForTest();
        assertFalse("Timed-out session close fallback must clear closing flag instead of deadlocking",
                camera.isConfiguringOrClosingForTest());
        camera.shutdownLifecycleExecutor(500);
    }

    @Test
    public void onDisconnectedClearsConfiguringAndClosingFlags() {
        SingleCamera camera = new SingleCamera(context, "0", null, cameraManager);
        CameraDevice device = mock(CameraDevice.class);
        CameraCaptureSession session = mock(CameraCaptureSession.class);
        camera.attachLifecycleStateForTest(device, session, null);

        camera.setSessionFlagsForTest(true, true);
        assertTrue(camera.isConfiguringOrClosingForTest());

        camera.triggerOnDisconnectedForTest(device);
        assertFalse("onDisconnected must reset configuring/closing session flags",
                camera.isConfiguringOrClosingForTest());
        assertFalse(camera.hasActiveCaptureSession());
        assertFalse(camera.isConnected());
        verify(session).close();
        verify(device).close();
        camera.shutdownLifecycleExecutor(500);
    }
}
