package com.kooo.evcam.camera;

import android.media.MediaCodec;
import android.media.MediaMuxer;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
}
