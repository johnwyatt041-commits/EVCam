package com.kooo.evcam.camera;
import android.media.MediaCodec;
import org.junit.Test;
import java.lang.reflect.*;
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
}
