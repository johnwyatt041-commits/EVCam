package com.kooo.evcam.camera;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.view.Surface;

import org.junit.Test;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class CodecVideoRecorderDirectPathTest {

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean drain(CodecVideoRecorder recorder, boolean eos) throws Exception {
        Method method = CodecVideoRecorder.class.getDeclaredMethod("drainEncoderWithResult", boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(recorder, eos);
    }

    @Test
    public void evaluateDecisionCoversDirectAndGlBoundaries() {
        CodecVideoRecorder recorder = new CodecVideoRecorder("front", 1280, 800);
        try {
            recorder.setSourceSize(1280, 800);
            recorder.setFrameRate(25);
            recorder.setNativeSourceFrameRate(25);
            recorder.setCameraSupportsDirectInput(true);
            recorder.setDirectInputEnabled(true);

            // Legacy prepareRecording() path keeps GL even when switch is enabled.
            assertFalse(recorder.evaluateDirectEncodingDecision(false).isDirect());
            assertEquals(DirectEncodingPolicy.FallbackReason.SWITCH_DISABLED,
                    recorder.evaluateDirectEncodingDecision(false).reason);

            // Surface-first path selects DIRECT.
            DirectEncodingPolicy.Decision direct = recorder.evaluateDirectEncodingDecision(true);
            assertTrue(direct.isDirect());
            assertEquals(DirectEncodingPolicy.FallbackReason.NONE, direct.reason);

            // Identity enabled ImageAdjustmentConfig still stays DIRECT.
            recorder.setImageAdjustmentConfig(new ImageAdjustmentConfig(true, 100, 100, 100, 100, 0, false));
            assertTrue(recorder.evaluateDirectEncodingDecision(true).isDirect());

            // Mirror / crop / color / watermark / low-fps / switch each trigger GL.
            recorder.setImageAdjustmentConfig(new ImageAdjustmentConfig(true, 100, 100, 100, 100, 0, true));
            assertEquals(DirectEncodingPolicy.FallbackReason.IMAGE_ADJUST_UV,
                    recorder.evaluateDirectEncodingDecision(true).reason);

            recorder.setImageAdjustmentConfig(ImageAdjustmentConfig.disabled());
            recorder.setWatermarkEnabled(true);
            assertEquals(DirectEncodingPolicy.FallbackReason.WATERMARK_ENABLED,
                    recorder.evaluateDirectEncodingDecision(true).reason);

            recorder.setWatermarkEnabled(false);
            recorder.setFrameRate(15);
            assertEquals(DirectEncodingPolicy.FallbackReason.FRAME_PACING_REQUIRED,
                    recorder.evaluateDirectEncodingDecision(true).reason);

            recorder.setFrameRate(25);
            recorder.setDirectInputEnabled(false);
            assertEquals(DirectEncodingPolicy.FallbackReason.SWITCH_DISABLED,
                    recorder.evaluateDirectEncodingDecision(true).reason);

            recorder.setDirectInputEnabled(true);
            recorder.setRuntimeGlFallback(true);
            assertEquals(DirectEncodingPolicy.FallbackReason.RUNTIME_GL_FALLBACK,
                    recorder.evaluateDirectEncodingDecision(true).reason);
        } finally {
            recorder.release();
        }
    }

    @Test
    public void getRecordSurfaceReturnsEncoderInputSurfaceDirectlyWhenDirectActive() throws Exception {
        CodecVideoRecorder recorder = new CodecVideoRecorder("left", 1280, 800);
        try {
            Surface directSurface = mock(Surface.class);
            when(directSurface.isValid()).thenReturn(true);

            setField(recorder, "activePathMode", DirectEncodingPolicy.PathMode.DIRECT);
            setField(recorder, "encoderInputSurface", directSurface);

            assertTrue(recorder.isDirectInputActive());
            assertSame(directSurface, recorder.getRecordSurface());
            assertNull(getField(recorder, "inputSurfaceTexture"));
            assertNull(getField(recorder, "eglEncoder"));

            when(directSurface.isValid()).thenReturn(false);
            assertNull(recorder.getRecordSurface());
        } finally {
            recorder.release();
        }
    }

    @Test
    public void directPreStartDrainCapturesOutputFormatAndStartRecordingGatesUntilFirstKeyFrameAndRotatesCleanly()
            throws Exception {
        CodecVideoRecorder recorder = new CodecVideoRecorder("front", 1280, 800);
        MediaCodec encoder = mock(MediaCodec.class);
        Surface directSurface = mock(Surface.class);
        when(directSurface.isValid()).thenReturn(true);
        MediaFormat format = mock(MediaFormat.class);
        AsyncMuxerWriter firstMuxer = mock(AsyncMuxerWriter.class);
        Handler mockSegHandler = mock(Handler.class);
        ExecutorService segExec = Executors.newSingleThreadExecutor();
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "evcam_direct_seg_" + System.nanoTime());
        tempDir.mkdirs();

        try {
            CountDownLatch rotated = new CountDownLatch(1);
            AtomicReference<String> rotatedCompletedPath = new AtomicReference<>();
            when(mockSegHandler.post(any(Runnable.class))).thenAnswer(inv -> {
                Runnable task = inv.getArgument(0);
                segExec.execute(task);
                return true;
            });

            String seg0Path = new File(tempDir, "20261122_210000_front.mp4").getAbsolutePath();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            setField(recorder, "activePathMode", DirectEncodingPolicy.PathMode.DIRECT);
            setField(recorder, "pathFallbackReason", DirectEncodingPolicy.FallbackReason.NONE);
            setField(recorder, "encoder", encoder);
            setField(recorder, "encoderInputSurface", directSurface);
            setField(recorder, "bufferInfo", info);
            setField(recorder, "muxer", firstMuxer);
            setField(recorder, "currentFilePath", seg0Path);
            setField(recorder, "saveDirectory", tempDir.getAbsolutePath());
            setField(recorder, "cameraPosition", "front");
            setField(recorder, "segmentHandler", mockSegHandler);
            setField(recorder, "drainHandler", null);
            recorder.setTimestampProvider(() -> "20261122_210100");
            recorder.setCallback(new RecordCallback() {
                @Override public void onRecordStart(String cameraId) {}
                @Override public void onRecordStop(String cameraId) {}
                @Override public void onRecordError(String cameraId, String error) {}
                @Override public void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex) {}
                @Override public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {
                    rotatedCompletedPath.set(completedFilePath);
                    rotated.countDown();
                }
                @Override public void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles) {}
                @Override public void onRecordingRebuildRequested(String cameraId, String reason) {}
                @Override public void onFirstDataWritten(String cameraId) {}
            });

            // 1. Pre-start drain captures INFO_OUTPUT_FORMAT_CHANGED without starting muxer and releases buffer 0.
            AtomicInteger preStep = new AtomicInteger();
            when(encoder.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong())).thenAnswer(inv -> {
                int s = preStep.getAndIncrement();
                if (s == 0) return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
                if (s == 1) {
                    info.offset = 0;
                    info.size = 4;
                    info.flags = 0;
                    info.presentationTimeUs = 1_000_000L;
                    return 0;
                }
                return MediaCodec.INFO_TRY_AGAIN_LATER;
            });
            when(encoder.getOutputFormat()).thenReturn(format);
            when(encoder.getOutputBuffer(anyInt())).thenReturn(ByteBuffer.allocate(4));

            recorder.drainPreStartEncoderLocked();
            assertSame(format, getField(recorder, "outputFormat"));
            assertFalse((boolean) getField(recorder, "muxerStarted"));
            verify(firstMuxer, never()).start();
            verify(encoder, times(1)).releaseOutputBuffer(0, false);

            // 2. startRecording() succeeds without EGL/encoderHandler and requests a sync frame.
            when(encoder.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong()))
                    .thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER);
            assertTrue(recorder.startRecording());
            assertTrue((boolean) getField(recorder, "awaitingInitialKeyFrame"));
            verify(encoder, times(1)).setParameters(any());

            // 3. Leading P-frame is dropped; first IDR starts muxer at pts=0; non-monotonic PTS is corrected.
            List<Long> seg0Pts = new ArrayList<>();
            doAnswer(inv -> {
                seg0Pts.add(((MediaCodec.BufferInfo) inv.getArgument(2)).presentationTimeUs);
                return null;
            }).when(firstMuxer).writeSampleData(eq(0), any(ByteBuffer.class), any(MediaCodec.BufferInfo.class));

            AtomicInteger drainStep = new AtomicInteger();
            when(encoder.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong())).thenAnswer(inv -> {
                int s = drainStep.getAndIncrement();
                info.offset = 0;
                info.size = 4;
                if (s == 0) {
                    // Trailing P-frame before IDR must be skipped.
                    info.flags = 0;
                    info.presentationTimeUs = 2_000_000L;
                    return 1;
                } else if (s == 1) {
                    // First IDR starts muxer with segment 0 origin at 0.
                    info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    info.presentationTimeUs = 5_000_000L;
                    return 2;
                } else if (s == 2) {
                    info.flags = 0;
                    info.presentationTimeUs = 5_040_000L;
                    return 3;
                } else if (s == 3) {
                    // Regressing timestamp corrected to +1us.
                    info.flags = 0;
                    info.presentationTimeUs = 4_900_000L;
                    return 4;
                }
                return MediaCodec.INFO_TRY_AGAIN_LATER;
            });

            assertTrue(drain(recorder, false));
            assertFalse((boolean) getField(recorder, "awaitingInitialKeyFrame"));
            assertTrue((boolean) getField(recorder, "muxerStarted"));
            verify(firstMuxer, times(1)).addTrack(format);
            verify(firstMuxer, times(1)).start();
            assertEquals(1L, getField(recorder, "directSkippedPreKeyFrames"));
            assertEquals(3, seg0Pts.size());
            assertEquals(Long.valueOf(0L), seg0Pts.get(0));
            assertEquals(Long.valueOf(40_000L), seg0Pts.get(1));
            assertEquals(Long.valueOf(40_001L), seg0Pts.get(2));

            // 4. Segment switch requests another sync frame and rotates cleanly on the next IDR with new PTS zero.
            Method switchSeg = CodecVideoRecorder.class.getDeclaredMethod("switchToNextSegment");
            switchSeg.setAccessible(true);
            switchSeg.invoke(recorder);
            assertTrue((boolean) getField(recorder, "segmentRequested"));
            verify(encoder, times(2)).setParameters(any());

            AtomicInteger seg1Step = new AtomicInteger();
            when(encoder.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong())).thenAnswer(inv -> {
                int s = seg1Step.getAndIncrement();
                info.offset = 0;
                info.size = 4;
                if (s == 0) {
                    info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    info.presentationTimeUs = 65_000_000L;
                    return 5;
                }
                return MediaCodec.INFO_TRY_AGAIN_LATER;
            });

            assertTrue(drain(recorder, false));
            assertTrue(rotated.await(2, TimeUnit.SECONDS));
            assertEquals(seg0Path, rotatedCompletedPath.get());
            verify(firstMuxer, times(1)).stop();
            verify(firstMuxer, times(1)).release();

            AsyncMuxerWriter newMuxer = (AsyncMuxerWriter) getField(recorder, "muxer");
            assertNotNull(newMuxer);
            assertNotSame(firstMuxer, newMuxer);
            MuxerTimestampClock clock = (MuxerTimestampClock) getField(recorder, "muxerClock");
            assertEquals(0L, clock.getLastPtsUs());

            // Cleanup background writer created by rotateMuxerAtKeyFrame and return EOS for stopRecording.
            newMuxer.abort(new RuntimeException("test cleanup"));
            try {
                newMuxer.release();
            } catch (Exception ignored) {
            }
            setField(recorder, "muxer", null);
            setField(recorder, "muxerStarted", false);
            when(encoder.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong())).thenAnswer(inv -> {
                info.size = 0;
                info.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                return 6;
            });
            recorder.stopRecording();
        } finally {
            segExec.shutdownNow();
            setField(recorder, "muxer", null);
            setField(recorder, "encoder", null);
            recorder.release();
            File[] children = tempDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    child.delete();
                }
            }
            tempDir.delete();
        }
    }

    @Test
    public void directAdaptiveDrainDelayAndStopRecordingWorkWithoutEncoderThread() throws Exception {
        assertEquals(10L, CodecVideoRecorder.computeNextDrainDelayMs(true, 0, 0, true));
        assertEquals(20L, CodecVideoRecorder.computeNextDrainDelayMs(false, 0, 1, true));
        assertEquals(20L, CodecVideoRecorder.computeNextDrainDelayMs(false, 0, 2, true));
        assertEquals(40L, CodecVideoRecorder.computeNextDrainDelayMs(false, 0, 3, true));
        assertEquals(40L, CodecVideoRecorder.computeNextDrainDelayMs(false, 0, 6, true));
        assertEquals(80L, CodecVideoRecorder.computeNextDrainDelayMs(false, 0, 7, true));
        // GL path stays unchanged.
        assertEquals(120L, CodecVideoRecorder.computeNextDrainDelayMs(false, 0, 1, false));

        CodecVideoRecorder recorder = new CodecVideoRecorder("right", 1280, 800);
        MediaCodec encoder = mock(MediaCodec.class);
        AsyncMuxerWriter muxer = mock(AsyncMuxerWriter.class);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        AtomicBoolean stopNotified = new AtomicBoolean(false);
        try {
            setField(recorder, "activePathMode", DirectEncodingPolicy.PathMode.DIRECT);
            setField(recorder, "encoder", encoder);
            setField(recorder, "encoderInputSurface", mock(Surface.class));
            setField(recorder, "muxer", muxer);
            setField(recorder, "muxerStarted", true);
            setField(recorder, "bufferInfo", info);
            setField(recorder, "encoderHandler", null);
            setField(recorder, "drainHandler", null);
            ((AtomicBoolean) getField(recorder, "isRecording")).set(true);

            when(encoder.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong())).thenAnswer(inv -> {
                info.size = 0;
                info.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                return 0;
            });
            recorder.setCallback(new RecordCallback() {
                @Override public void onRecordStart(String cameraId) {}
                @Override public void onRecordStop(String cameraId) { stopNotified.set(true); }
                @Override public void onRecordError(String cameraId, String error) {}
                @Override public void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex) {}
                @Override public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {}
                @Override public void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles) {}
                @Override public void onRecordingRebuildRequested(String cameraId, String reason) {}
                @Override public void onFirstDataWritten(String cameraId) {}
            });

            recorder.stopRecording();
            verify(encoder, times(1)).signalEndOfInputStream();
            verify(muxer, times(1)).stop();
            assertTrue(stopNotified.get());
        } finally {
            setField(recorder, "muxer", null);
            setField(recorder, "encoder", null);
            recorder.release();
        }
    }

    @Test
    public void dynamicConfigChangeFlagsPendingGlAndDirectSkipsInPlaceRebuild() throws Exception {
        CodecVideoRecorder recorder = new CodecVideoRecorder("back", 1280, 800);
        MediaCodec codec = mock(MediaCodec.class);
        try {
            recorder.setSourceSize(1280, 800);
            recorder.setFrameRate(25);
            recorder.setNativeSourceFrameRate(25);
            recorder.setCameraSupportsDirectInput(true);
            recorder.setDirectInputEnabled(true);

            setField(recorder, "activePathMode", DirectEncodingPolicy.PathMode.DIRECT);
            setField(recorder, "pathFallbackReason", DirectEncodingPolicy.FallbackReason.NONE);
            setField(recorder, "encoder", codec);
            ((AtomicBoolean) getField(recorder, "isRecording")).set(true);

            assertFalse(recorder.isPendingGlConfigChange());

            recorder.setWatermarkEnabled(true);
            assertTrue(recorder.isPendingGlConfigChange());

            recorder.setWatermarkEnabled(false);
            assertFalse(recorder.isPendingGlConfigChange());

            recorder.setImageAdjustmentConfig(new ImageAdjustmentConfig(true, 100, 120, 100, 100, 0, false));
            assertTrue(recorder.isPendingGlConfigChange());

            recorder.setImageAdjustmentConfig(ImageAdjustmentConfig.disabled());
            assertFalse(recorder.isPendingGlConfigChange());

            // In-place rebuild/recovery must never silently swap encoderInputSurface on direct mode.
            for (String methodName : new String[]{"attemptRecovery", "rebuildEncoder"}) {
                Method m = CodecVideoRecorder.class.getDeclaredMethod(methodName);
                m.setAccessible(true);
                m.invoke(recorder);
            }
            verifyNoInteractions(codec);

            String diag = recorder.startupDiagnostics();
            assertTrue(diag.contains("path=direct"));
            assertTrue(diag.contains("fallbackReason=none"));
        } finally {
            setField(recorder, "encoder", null);
            recorder.release();
        }
    }

    @Test
    public void prepareRecordingSurfaceAsyncHonorsPreCancelledAndReleasedState() throws Exception {
        CodecVideoRecorder stopped = new CodecVideoRecorder("front", 1280, 800);
        try {
            stopped.requestStop();
            CountDownLatch latch1 = new CountDownLatch(1);
            AtomicBoolean success1 = new AtomicBoolean(true);
            AtomicReference<String> err1 = new AtomicReference<>();
            stopped.prepareRecordingSurfaceAsync(
                    "dummy_direct_front.mp4",
                    Runnable::run,
                    (success, surface, directPath, err) -> {
                        success1.set(success);
                        err1.set(err);
                        latch1.countDown();
                    });
            assertTrue(latch1.await(2, TimeUnit.SECONDS));
            assertFalse(success1.get());
            assertEquals("Cancelled", err1.get());
        } finally {
            stopped.release();
        }

        CodecVideoRecorder released = new CodecVideoRecorder("back", 1280, 800);
        released.release();
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicBoolean success2 = new AtomicBoolean(true);
        AtomicReference<Surface> surface2 = new AtomicReference<>(mock(Surface.class));
        released.prepareRecordingSurfaceAsync(
                "dummy_direct_back.mp4",
                Runnable::run,
                (success, surface, directPath, err) -> {
                    success2.set(success);
                    surface2.set(surface);
                    latch2.countDown();
                });
        assertTrue(latch2.await(2, TimeUnit.SECONDS));
        assertFalse(success2.get());
        assertNull(surface2.get());
    }

    @Test
    public void directPathDisplayFrameCountTracksCumulativeAndSegmentEncodedFrames() throws Exception {
        CodecVideoRecorder recorder = new CodecVideoRecorder("right", 1280, 800);
        MediaCodec encoder = mock(MediaCodec.class);
        AsyncMuxerWriter firstMuxer = mock(AsyncMuxerWriter.class);
        Handler mockSegHandler = mock(Handler.class);
        ExecutorService segExec = Executors.newSingleThreadExecutor();
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "evcam_direct_frames_" + System.nanoTime());
        tempDir.mkdirs();

        try {
            CountDownLatch rotated = new CountDownLatch(1);
            when(mockSegHandler.post(any(Runnable.class))).thenAnswer(inv -> {
                segExec.execute(inv.getArgument(0));
                return true;
            });

            String seg0Path = new File(tempDir, "20261123_012300_right.mp4").getAbsolutePath();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaFormat format = mock(MediaFormat.class);

            setField(recorder, "activePathMode", DirectEncodingPolicy.PathMode.DIRECT);
            setField(recorder, "encoder", encoder);
            setField(recorder, "bufferInfo", info);
            setField(recorder, "muxer", firstMuxer);
            setField(recorder, "outputFormat", format);
            setField(recorder, "muxerStarted", true);
            setField(recorder, "currentFilePath", seg0Path);
            setField(recorder, "saveDirectory", tempDir.getAbsolutePath());
            setField(recorder, "cameraPosition", "right");
            setField(recorder, "segmentHandler", mockSegHandler);
            setField(recorder, "drainHandler", null);
            recorder.setTimestampProvider(() -> "20261123_012400");
            recorder.setCallback(new RecordCallback() {
                @Override public void onRecordStart(String cameraId) {}
                @Override public void onRecordStop(String cameraId) {}
                @Override public void onRecordError(String cameraId, String error) {}
                @Override public void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex) {}
                @Override public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {
                    rotated.countDown();
                }
                @Override public void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles) {}
                @Override public void onRecordingRebuildRequested(String cameraId, String reason) {}
                @Override public void onFirstDataWritten(String cameraId) {}
            });

            when(encoder.getOutputBuffer(anyInt())).thenReturn(ByteBuffer.allocate(4));
            AtomicInteger step = new AtomicInteger();
            when(encoder.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong())).thenAnswer(inv -> {
                int s = step.getAndIncrement();
                info.offset = 0;
                info.size = 4;
                if (s < 3) {
                    info.flags = (s == 0) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    info.presentationTimeUs = (s + 1) * 40_000L;
                    return s;
                }
                return MediaCodec.INFO_TRY_AGAIN_LATER;
            });

            assertTrue(drain(recorder, false));
            assertEquals(0L, ((Number) getField(recorder, "recordedFrameCount")).longValue());
            assertEquals(3L, ((Number) getField(recorder, "encodedOutputFrameCount")).longValue());
            assertEquals(3L, recorder.getDisplayRecordedFrameCount());
            assertEquals("3 (segment: 3)", recorder.formatFileSizeLogFrames());

            // 触发关键帧切段后，segment 计数归零重计，累计计数跨分段延续
            setField(recorder, "segmentRequested", true);
            step.set(0);
            when(encoder.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong())).thenAnswer(inv -> {
                int s = step.getAndIncrement();
                info.offset = 0;
                info.size = 4;
                if (s == 0) {
                    info.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    info.presentationTimeUs = 200_000L;
                    return 0;
                } else if (s == 1) {
                    info.flags = 0;
                    info.presentationTimeUs = 240_000L;
                    return 1;
                }
                return MediaCodec.INFO_TRY_AGAIN_LATER;
            });

            assertTrue(drain(recorder, false));
            assertTrue(rotated.await(2, TimeUnit.SECONDS));
            assertEquals(0L, ((Number) getField(recorder, "recordedFrameCount")).longValue());
            assertEquals(2L, ((Number) getField(recorder, "encodedOutputFrameCount")).longValue());
            assertEquals(5L, recorder.getDisplayRecordedFrameCount());
            assertEquals("5 (segment: 2)", recorder.formatFileSizeLogFrames());

            // GL 模式仍沿用 recordedFrameCount
            setField(recorder, "activePathMode", DirectEncodingPolicy.PathMode.GL);
            setField(recorder, "recordedFrameCount", 12L);
            assertEquals(12L, recorder.getDisplayRecordedFrameCount());
            assertEquals("12", recorder.formatFileSizeLogFrames());
        } finally {
            segExec.shutdownNow();
            setField(recorder, "encoder", null);
            recorder.release();
            File[] children = tempDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    child.delete();
                }
            }
            tempDir.delete();
        }
    }
}
