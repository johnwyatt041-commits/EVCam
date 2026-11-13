package com.kooo.evcam.remote.handler;

import android.content.Context;
import android.content.SharedPreferences;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.FloatingWindowService;
import com.kooo.evcam.remote.core.ChatIdentifier;
import com.kooo.evcam.remote.core.RemotePlatform;
import com.kooo.evcam.remote.upload.MediaUploadService;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RemoteRecordingHandoffTest {
    private static class TestHandler extends RemoteCommandHandler {
        TestHandler(Context context) { super(context); }
        protected String getPlatformName() { return "Feishu"; }
        protected RemotePlatform getPlatform() { return RemotePlatform.FEISHU; }
        protected boolean isApiClientReady() { return false; }
        public void sendMessage(ChatIdentifier chat, String message) {}
        public void sendError(ChatIdentifier chat, String message) {}
        protected MediaUploadService createVideoUploadService() { return null; }
        protected MediaUploadService createPhotoUploadService() { return null; }
    }

    private TestHandler createHandler(RemoteCommandHandler.CameraController controller) {
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(mock(SharedPreferences.class));
        TestHandler handler = new TestHandler(context);
        handler.setCameraController(controller);
        when(controller.hasAvailableCameras()).thenReturn(true);
        return handler;
    }

    @Test public void remoteRecordingWaitsForDailyRecordingToStopAndRejectsDuplicateStart() {
        RemoteCommandHandler.CameraController controller = mock(RemoteCommandHandler.CameraController.class);
        TestHandler handler = createHandler(controller);
        when(controller.isRecording()).thenReturn(true);
        when(controller.startRecording(anyString())).thenReturn(false);
        ChatIdentifier chat = new ChatIdentifier.FeishuChatId("test");

        handler.startRemoteRecording(chat, 30);
        ArgumentCaptor<Runnable> stopped = ArgumentCaptor.forClass(Runnable.class);
        verify(controller).stopRecording(eq(false), stopped.capture());
        verify(controller, never()).startRecording(anyString());
        handler.startRemoteRecording(chat, 30);
        verify(controller, times(1)).stopRecording(eq(false), any(Runnable.class));

        stopped.getValue().run();
        verify(controller).startRecording(anyString());
        verify(controller).startRecording(); // Restore daily recording if the remote start fails.
        assertFalse(handler.isPreparingRecording());
        assertFalse(handler.isRemoteRecording());
    }

    @Test public void remoteCompletionWaitsForFinalFramesAndMuxerToBeClosed() throws Exception {
        try (MockedStatic<CameraForegroundService> foreground = mockStatic(CameraForegroundService.class);
             MockedStatic<FloatingWindowService> floating = mockStatic(FloatingWindowService.class)) {
            RemoteCommandHandler.CameraController controller = mock(RemoteCommandHandler.CameraController.class);
            TestHandler handler = createHandler(controller);
            when(controller.startRecording(anyString())).thenReturn(true);
            RemoteCommandHandler.RecordingStateListener listener = mock(RemoteCommandHandler.RecordingStateListener.class);
            handler.setRecordingStateListener(listener);
            handler.startRemoteRecording(new ChatIdentifier.FeishuChatId("test"), 30);
            Field stop = RemoteCommandHandler.class.getDeclaredField("autoStopRunnable");
            stop.setAccessible(true);
            ((Runnable) stop.get(handler)).run();

            ArgumentCaptor<Runnable> finished = ArgumentCaptor.forClass(Runnable.class);
            verify(controller).stopRecording(eq(true), finished.capture());
            assertTrue(handler.isRemoteRecording());
            verify(listener, never()).onRemoteRecordingStop();
            finished.getValue().run();
            assertFalse(handler.isRemoteRecording());
            verify(listener).onRemoteRecordingStop();
            verify(controller).clearSegmentDurationOverride();
        }
    }
}
