package com.kooo.evcam.camera;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.StorageHelper;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Exercises the real Camera2/session/manager/first-write handshake that synthetic GL tests bypass. */
@RunWith(AndroidJUnit4.class)
public class Camera2RecordingStartupTest {
    @Test public void hiddenPreviewStartsThroughActualCameraSessionAndNotifiesFirstWrite() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppConfig config=new AppConfig(context);
        config.setCarModel(AppConfig.CAR_MODEL_GALAXY_E5);
        config.setTargetResolution("640x480");
        config.setForceH264Encoding(true);
        config.setTimestampWatermarkEnabled(false); config.setVehicleStatusWatermarkEnabled(false);
        String id=((CameraManager)context.getSystemService(Context.CAMERA_SERVICE)).getCameraIdList()[0];
        MultiCameraManager manager=new MultiCameraManager(context);
        CountDownLatch first=new CountDownLatch(1), stopped=new CountDownLatch(1);
        AtomicReference<String> failure=new AtomicReference<>();
        manager.setMainPreviewEnabled(false); manager.setCodecRecordingMode(true);
        manager.setFirstDataWrittenCallback(first::countDown);
        manager.setRecordingStartupFailureCallback(failure::set);
        manager.initCameras(null,null,null,null,null,null,id,null);
        String stamp="camera2_"+System.currentTimeMillis();
        try {
            assertTrue(manager.startRecording(stamp,Collections.singleton("right")));
            assertTrue("Actual Camera2 never reached first write: "+failure.get(),first.await(18,TimeUnit.SECONDS));
            assertNull(failure.get());
            Thread.sleep(500);
            manager.stopRecording(false,stopped::countDown);
            assertTrue(stopped.await(10,TimeUnit.SECONDS));
            File file=new File(StorageHelper.getRecordingDir(context),stamp+"_right.mp4");
            assertTrue("No finalized Camera2 video",file.isFile() && file.length()>1024);
            assertFalse(new File(file+".pending").exists());
        } finally { manager.release(); }
    }
}
