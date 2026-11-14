package com.kooo.evcam.camera;

import android.os.Build;
import android.util.Size;
import android.view.Surface;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;
import java.io.File;
import java.util.Map;

/** Prepares the experimental daily mosaic, with an explicit hardware-size fallback. */
final class CompositeRecording implements AutoCloseable {
    final CodecVideoRecorder recorder;
    final MultiCameraCompositor inputs;
    private CompositeRecording(CodecVideoRecorder recorder, MultiCameraCompositor inputs) {
        this.recorder = recorder; this.inputs = inputs;
    }

    static CompositeRecording prepare(Map<String, Size> cameras, File directory, String timestamp,
            AppConfig config, long segmentMs, RecordCallback callback,
            VideoRecorder.SegmentTimestampProvider timestamps) throws Exception {
        int maxWidth = 0, maxHeight = 0;
        for (Size size : cameras.values()) {
            maxWidth = Math.max(maxWidth, size.getWidth());
            maxHeight = Math.max(maxHeight, size.getHeight());
        }
        boolean debugEmulator = BuildConfig.DEBUG && ("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE));
        boolean e5=RecordingDeviceProfile.isE5(config.getCarModel());
        Exception failure = null;
        for (double scale : new double[]{1, .75, .5}) {
            int cellWidth = Math.max(16, ((int) (maxWidth * scale) / 16) * 16);
            int height = Math.max(16, ((int) (maxHeight * scale) / 2) * 2);
            int width = e5 ? cellWidth : cellWidth * cameras.size();
            CodecVideoRecorder recorder = new CodecVideoRecorder("combined", width, height);
            try {
                recorder.setHardwareOnly(!debugEmulator);
                recorder.setE5Compatibility(e5);
                recorder.setForceH264(true);
                recorder.setFrameRate(config.getActualFrameRate(25));
                recorder.setBitRate(config.getActualBitrate(width,height,config.getActualFrameRate(25)));
                recorder.setKeyFrameIntervalSeconds(1);
                recorder.setSegmentDuration(segmentMs);
                recorder.setTimestampProvider(timestamps);
                recorder.setCallback(callback);
                recorder.setWatermarkEnabled(config.isTimestampWatermarkEnabled());
                recorder.setVehicleStatusWatermarkEnabled(config.isVehicleStatusWatermarkEnabled());
                String path = new File(directory,timestamp+"_combined.mp4").getAbsolutePath();
                if (recorder.prepareRecording(path) == null) throw new IllegalStateException("Composite encoder preparation failed");
                MultiCameraCompositor compositor = new MultiCameraCompositor(recorder.getRecordSurface(),cameras,
                        width,height,config.getActualFrameRate(25),e5 ? 2 : cameras.size());
                AppLog.i("CompositeRecording","Selected mosaic size="+width+"x"+height+", scale="+scale
                        +", inputs="+cameras+", e5Grid="+e5+", debugEmulator="+debugEmulator);
                return new CompositeRecording(recorder,compositor);
            } catch (Exception e) {
                failure=e;
                recorder.release();
            }
        }
        throw new IllegalStateException("No hardware H.264 composite size could be prepared",failure);
    }

    Surface surface(String key) { return inputs.getInput(key); }
    void pause() { inputs.pause(); }
    @Override public void close() { inputs.close(); }
}
