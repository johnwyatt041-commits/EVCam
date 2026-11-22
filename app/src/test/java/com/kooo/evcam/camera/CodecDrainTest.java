package com.kooo.evcam.camera;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class CodecDrainTest {
    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private boolean drain(CodecVideoRecorder recorder, boolean eos) throws Exception {
        Method method = CodecVideoRecorder.class.getDeclaredMethod("drainEncoderWithResult", boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(recorder, eos);
    }

    @Test public void finalDrainWritesAllTailFramesAndPreservesCaptureGaps() throws Exception {
        CodecVideoRecorder recorder = new CodecVideoRecorder("left", 1280, 800);
        MediaCodec encoder = mock(MediaCodec.class);
        AsyncMuxerWriter muxer = mock(AsyncMuxerWriter.class);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        set(recorder, "encoder", encoder);
        set(recorder, "muxer", muxer);
        set(recorder, "bufferInfo", info);
        set(recorder, "muxerStarted", true);
        set(recorder, "videoTrackIndex", 0);
        set(recorder, "framesWithoutEncoderOutput", 40);
        AtomicInteger sample = new AtomicInteger();
        when(encoder.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong())).thenAnswer(call -> {
            int index = sample.getAndIncrement();
            if (index >= 12) {
                info.size = 0;
                info.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            } else {
                info.offset = 0;
                info.size = 4;
                info.flags = index == 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.presentationTimeUs = 7_000_000 + index * 80_000L;
            }
            return index;
        });
        when(encoder.getOutputBuffer(anyInt())).thenReturn(ByteBuffer.allocate(4));
        List<Long> writtenTimes = new ArrayList<>();
        doAnswer(call -> {
            writtenTimes.add(((MediaCodec.BufferInfo) call.getArgument(2)).presentationTimeUs);
            return null;
        }).when(muxer).writeSampleData(eq(0), any(ByteBuffer.class), any(MediaCodec.BufferInfo.class));

        assertTrue(drain(recorder, true));
        assertEquals(12, writtenTimes.size()); // Final drain must exceed the normal batch of eight.
        assertEquals(Long.valueOf(0), writtenTimes.get(0));
        assertEquals(Long.valueOf(880_000), writtenTimes.get(11));
        verify(encoder, times(13)).releaseOutputBuffer(anyInt(), eq(false));
        Field stalled = CodecVideoRecorder.class.getDeclaredField("framesWithoutEncoderOutput");
        stalled.setAccessible(true);
        assertEquals(0, stalled.getInt(recorder));
    }

    @Test public void normalDrainReturnsImmediatelyWhenOutputIsNotReady() throws Exception {
        CodecVideoRecorder recorder = new CodecVideoRecorder("right", 1280, 800);
        MediaCodec encoder = mock(MediaCodec.class);
        set(recorder, "encoder", encoder);
        set(recorder, "bufferInfo", new MediaCodec.BufferInfo());
        when(encoder.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong()))
                .thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER);
        assertFalse(drain(recorder, false));
        verify(encoder, times(1)).dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), eq(0L));
        verify(encoder, never()).releaseOutputBuffer(anyInt(), anyBoolean());
    }

    @Test public void adaptiveDrainDelayBacksOffWhenIdle() {
        assertEquals(10L, CodecVideoRecorder.computeNextDrainDelayMs(true, 0, 0));
        assertEquals(10L, CodecVideoRecorder.computeNextDrainDelayMs(true, 5, 10));
        assertEquals(15L, CodecVideoRecorder.computeNextDrainDelayMs(false, 2, 1));
        assertEquals(15L, CodecVideoRecorder.computeNextDrainDelayMs(false, 2, 2));
        assertEquals(30L, CodecVideoRecorder.computeNextDrainDelayMs(false, 2, 3));
        assertEquals(120L, CodecVideoRecorder.computeNextDrainDelayMs(false, 0, 1));
        assertEquals(120L, CodecVideoRecorder.computeNextDrainDelayMs(false, 0, 10));
    }

    @Test public void segmentRotationFinalizesPreviousMuxerAsynchronouslyWithoutBlockingDrain() throws Exception {
        CodecVideoRecorder recorder = new CodecVideoRecorder("front", 1280, 800);
        AsyncMuxerWriter oldMuxer = mock(AsyncMuxerWriter.class);
        MediaFormat format = mock(MediaFormat.class);
        Handler mockSegmentHandler = mock(Handler.class);
        ExecutorService segExec = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch stopEntered = new CountDownLatch(1);
            CountDownLatch allowStopFinish = new CountDownLatch(1);
            CountDownLatch segmentSwitched = new CountDownLatch(1);
            AtomicReference<String> switchedPath = new AtomicReference<>();

            doAnswer(inv -> {
                stopEntered.countDown();
                assertTrue(allowStopFinish.await(5, TimeUnit.SECONDS));
                return null;
            }).when(oldMuxer).stop();

            when(mockSegmentHandler.post(any(Runnable.class))).thenAnswer(inv -> {
                Runnable task = inv.getArgument(0);
                segExec.execute(task);
                return true;
            });

            File tempDir = new File(System.getProperty("java.io.tmpdir"), "evcam_seg_rot_" + System.nanoTime());
            tempDir.mkdirs();
            String firstPath = new File(tempDir, "20261122_090000_front.mp4").getAbsolutePath();

            set(recorder, "muxer", oldMuxer);
            set(recorder, "outputFormat", format);
            set(recorder, "currentFilePath", firstPath);
            set(recorder, "saveDirectory", tempDir.getAbsolutePath());
            set(recorder, "cameraPosition", "front");
            set(recorder, "segmentHandler", mockSegmentHandler);
            recorder.setTimestampProvider(() -> "20261122_090100");
            recorder.setCallback(new RecordCallback() {
                @Override public void onRecordStart(String cameraId) {}
                @Override public void onRecordStop(String cameraId) {}
                @Override public void onRecordError(String cameraId, String error) {}
                @Override public void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex) {}
                @Override public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {
                    switchedPath.set(completedFilePath);
                    segmentSwitched.countDown();
                }
                @Override public void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles) {}
                @Override public void onRecordingRebuildRequested(String cameraId, String reason) {}
                @Override public void onFirstDataWritten(String cameraId) {}
            });

            Method rotate = CodecVideoRecorder.class.getDeclaredMethod("rotateMuxerAtKeyFrame");
            rotate.setAccessible(true);
            rotate.invoke(recorder);

            // 旧 writer 正在后台阻塞收尾，而 rotateMuxerAtKeyFrame 已无阻塞返回并切到了新 writer
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS));
            Field muxerField = CodecVideoRecorder.class.getDeclaredField("muxer");
            muxerField.setAccessible(true);
            AsyncMuxerWriter newMuxer = (AsyncMuxerWriter) muxerField.get(recorder);
            assertNotNull(newMuxer);
            assertNotSame(oldMuxer, newMuxer);
            assertEquals(1L, segmentSwitched.getCount());

            // 放行旧 writer 后，异步完成 release 并回调 onSegmentSwitch
            allowStopFinish.countDown();
            assertTrue(segmentSwitched.await(2, TimeUnit.SECONDS));
            assertEquals(firstPath, switchedPath.get());
            verify(oldMuxer, times(1)).stop();
            verify(oldMuxer, times(1)).release();

            newMuxer.abort(new RuntimeException("cleanup"));
            try { newMuxer.release(); } catch (Exception ignored) {}
            File pending = new File(tempDir, "20261122_090100_front.mp4.pending");
            if (pending.exists()) pending.delete();
            tempDir.delete();
        } finally {
            segExec.shutdownNow();
        }
    }
}
