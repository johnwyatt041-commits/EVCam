package com.kooo.evcam.camera;
import android.media.MediaCodec;
import org.junit.Test;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public class CodecRecoveryStopTest {
    @Test public void cancelledRecordingCannotBeRebuiltByAnAlreadyQueuedHealthTask() throws Exception {
        CodecVideoRecorder recorder=new CodecVideoRecorder("left",192,128);
        MediaCodec codec=mock(MediaCodec.class);
        Field field=CodecVideoRecorder.class.getDeclaredField("encoder"); field.setAccessible(true); field.set(recorder,codec);
        recorder.requestStop();
        for (String name : new String[]{"attemptRecovery","rebuildEncoder"}) {
            Method method=CodecVideoRecorder.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(recorder);
        }
        verifyNoInteractions(codec);
    }

    @Test public void fatalStartupWriteErrorStopsRebuildAndNotifiesCallback() throws Exception {
        CodecVideoRecorder recorder=new CodecVideoRecorder("front",192,128);
        MediaCodec codec=mock(MediaCodec.class);
        Field encoderField=CodecVideoRecorder.class.getDeclaredField("encoder");
        encoderField.setAccessible(true);
        encoderField.set(recorder,codec);
        Field recordingField=CodecVideoRecorder.class.getDeclaredField("isRecording");
        recordingField.setAccessible(true);
        ((AtomicBoolean) recordingField.get(recorder)).set(true);

        AtomicReference<String> errorMsg=new AtomicReference<>();
        recorder.setCallback(new RecordCallback() {
            @Override public void onRecordStart(String cameraId) {}
            @Override public void onRecordStop(String cameraId) {}
            @Override public void onRecordError(String cameraId, String error) { errorMsg.set(error); }
            @Override public void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex) {}
            @Override public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {}
            @Override public void onCorruptedFilesDeleted(String cameraId, java.util.List<String> deletedFiles) {}
            @Override public void onRecordingRebuildRequested(String cameraId, String reason) {}
            @Override public void onFirstDataWritten(String cameraId) {}
        });

        recorder.handleMuxerFailure(new FileNotFoundException(
                "/storage/emulated/0/DCIM/EVCam_Video/test_front.mp4.pending: open failed: EPERM (Operation not permitted)"));

        assertTrue(recorder.hasFatalWriteError());
        assertFalse(recorder.isRecording());
        assertNotNull(errorMsg.get());
        assertTrue(errorMsg.get().contains("EPERM"));
        assertTrue(recorder.startupDiagnostics().contains("fatalWriteError=true"));

        for (String name : new String[]{"attemptRecovery","rebuildEncoder"}) {
            Method method=CodecVideoRecorder.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(recorder);
        }
        verifyNoInteractions(codec);
    }

    @Test public void nonFatalWriterErrorDoesNotMarkFatalWriteError() {
        CodecVideoRecorder recorder=new CodecVideoRecorder("front",192,128);
        recorder.handleMuxerFailure(new IOException("Refusing to overwrite video: test_front.mp4"));
        assertFalse(recorder.hasFatalWriteError());
    }
}
