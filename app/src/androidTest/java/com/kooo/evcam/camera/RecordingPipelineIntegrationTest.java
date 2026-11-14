package com.kooo.evcam.camera;

import android.content.Context;
import android.graphics.*;
import android.media.*;
import android.util.Size;
import android.view.Surface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.BuildConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import static org.junit.Assert.*;

/** Actual SurfaceTexture -> GL -> codec -> asynchronous MP4, using synthetic camera inputs. */
@RunWith(AndroidJUnit4.class)
public class RecordingPipelineIntegrationTest {
    private final ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
    private final RecordCallback callback = new RecordCallback() {
        @Override public void onRecordStart(String id) {}
        @Override public void onRecordStop(String id) {}
        @Override public void onRecordError(String id,String error) { errors.add(error); }
        @Override public void onPrepareSegmentSwitch(String id,int index) {}
        @Override public void onSegmentSwitch(String id,int index,String path) {}
        @Override public void onCorruptedFilesDeleted(String id,List<String> files) {}
        @Override public void onRecordingRebuildRequested(String id,String reason) { errors.add(reason); }
        @Override public void onFirstDataWritten(String id) {}
    };

    @Test public void recordsRealFilesAcrossKeyframeSegmentsAndFinalizesBeforeReturning() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory=new File(context.getFilesDir(),"recording-ab-"+BuildConfig.FLAVOR);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        String timestamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());
        Map<String,Size> sizes=new LinkedHashMap<>();
        for (String name : new String[]{"left","back","right"}) sizes.put(name,new Size(192,128));
        Map<String,Surface> surfaces=new LinkedHashMap<>();
        List<CodecVideoRecorder> recorders=new ArrayList<>();
        CompositeRecording composite=null;
        try {
            if (BuildConfig.SMARTCAM_COMPOSITE) {
                AppConfig config=new AppConfig(context);
                config.setCarModel(AppConfig.CAR_MODEL_GALAXY_E5);
                config.setTimestampWatermarkEnabled(false);
                config.setVehicleStatusWatermarkEnabled(false);
                composite=CompositeRecording.prepare(sizes,directory,timestamp,config,1200,callback,null);
                recorders.add(composite.recorder);
                for (String name : sizes.keySet()) surfaces.put(name,composite.surface(name));
            } else {
                for (String name : sizes.keySet()) {
                    CodecVideoRecorder recorder=new CodecVideoRecorder(name,192,128);
                    recorder.setBitRate(600000); recorder.setFrameRate(25); recorder.setSegmentDuration(1200);
                    recorder.setE5Compatibility(true);
                    recorder.setForceH264(true); recorder.setCallback(callback);
                    assertNotNull(recorder.prepareRecording(new File(directory,timestamp+"_"+name+".mp4").getAbsolutePath()));
                    recorders.add(recorder); surfaces.put(name,recorder.getRecordSurface());
                }
            }
            for (CodecVideoRecorder recorder : recorders) assertTrue(recorder.startRecording());
            Paint text=new Paint(Paint.ANTI_ALIAS_FLAG); text.setColor(Color.WHITE); text.setTextSize(18);
            int[] colors={Color.RED,Color.GREEN,Color.BLUE};
            for (int frame=0; frame<110; frame++) {
                int index=0;
                for (Map.Entry<String,Surface> entry : surfaces.entrySet()) {
                    Canvas canvas=entry.getValue().lockCanvas(null);
                    canvas.drawColor(colors[index++]);
                    canvas.drawText(entry.getKey()+" "+frame,12,24,text);
                    entry.getValue().unlockCanvasAndPost(canvas);
                }
                Thread.sleep(frame==35 ? 240 : 40);
            }
            if (composite!=null) composite.pause();
            for (CodecVideoRecorder recorder : recorders) recorder.stopRecording();
        } finally {
            if (composite!=null) composite.close();
            for (CodecVideoRecorder recorder : recorders) recorder.release();
        }
        assertTrue("Recording errors: "+errors,errors.isEmpty());
        File[] files=directory.listFiles((dir,name) -> name.compareTo(timestamp)>=0 && name.endsWith(".mp4"));
        assertNotNull(files);
        int required=BuildConfig.SMARTCAM_COMPOSITE ? 2 : 6;
        assertTrue("Expected actual segments, found "+files.length,files.length>=required);
        for (File file : files) {
            MediaExtractor extractor=new MediaExtractor();
            try {
                extractor.setDataSource(file.getAbsolutePath());
                assertEquals(1,extractor.getTrackCount());
                MediaFormat format=extractor.getTrackFormat(0);
                assertEquals("video/avc",format.getString(MediaFormat.KEY_MIME));
                extractor.selectTrack(0);
                assertTrue("Segment must start at a key frame",(extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC)!=0);
                assertEquals(0,extractor.getSampleTime());
                long previous=-1; int count=0;
                while (extractor.getSampleTime()>=0) {
                    long pts=extractor.getSampleTime(); assertTrue(pts>previous); previous=pts; count++;
                    extractor.advance();
                }
                assertTrue("Empty video "+file,count>0);
                assertFalse(new File(file.getAbsolutePath()+".pending").exists());
            } finally { extractor.release(); }
        }
        if (BuildConfig.SMARTCAM_COMPOSITE) {
            File longest=Arrays.stream(files).max(Comparator.comparingLong(File::length)).get();
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try {
                retriever.setDataSource(longest.getAbsolutePath());
                Bitmap image=retriever.getFrameAtTime(500000,MediaMetadataRetriever.OPTION_CLOSEST);
                assertNotNull(image);
                // Left/rear/right are independent source textures, not three copies of one camera.
                int h=image.getHeight(), w=image.getWidth();
                int left=image.getPixel(w/4,h/4), back=image.getPixel(w*3/4,h/4), right=image.getPixel(w/4,h*3/4);
                assertTrue(Color.red(left)>180 && Color.green(left)<90 && Color.blue(left)<90);
                assertTrue(Color.green(back)>180 && Color.red(back)<90 && Color.blue(back)<90);
                assertTrue(Color.blue(right)>180 && Color.red(right)<90 && Color.green(right)<90);
                try (FileOutputStream out=new FileOutputStream(new File(directory,"decoded-composite.png"))) {
                    image.compress(Bitmap.CompressFormat.PNG,100,out);
                }
                image.recycle();
            } finally { retriever.release(); }
        }
    }
}
