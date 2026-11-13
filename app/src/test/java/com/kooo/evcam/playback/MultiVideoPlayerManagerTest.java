package com.kooo.evcam.playback;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.widget.VideoView;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MultiVideoPlayerManagerTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private MultiVideoPlayerManager manager;
    private VideoView front, back, left, right, single;
    private MultiVideoPlayerManager.OnPlaybackListener listener;
    private VideoGroup group;

    @Before public void setUp() throws IOException {
        front = mock(VideoView.class);
        back = mock(VideoView.class);
        left = mock(VideoView.class);
        right = mock(VideoView.class);
        single = mock(VideoView.class);
        listener = mock(MultiVideoPlayerManager.OnPlaybackListener.class);
        manager = new MultiVideoPlayerManager(mock(Context.class));
        manager.setVideoViews(front, back, left, right, single);
        manager.setPlaybackListener(listener);
        group = new VideoGroup("20261113_000000");
        for (String position : new String[]{"front", "back", "left", "right"}) {
            group.addFile(files.newFile("20261113_000000_" + position + ".mp4"));
        }
    }

    private MediaPlayer.OnPreparedListener preparedListener(VideoView view) {
        ArgumentCaptor<MediaPlayer.OnPreparedListener> capture =
                ArgumentCaptor.forClass(MediaPlayer.OnPreparedListener.class);
        verify(view, atLeastOnce()).setOnPreparedListener(capture.capture());
        return capture.getValue();
    }

    private void prepare(VideoView view) {
        MediaPlayer player = mock(MediaPlayer.class);
        when(player.getDuration()).thenReturn(60000);
        preparedListener(view).onPrepared(player);
    }

    @Test public void loadingSingleViewDoesNotAllocateHiddenPlayers() {
        manager.updateSingleModePosition(true, VideoGroup.POSITION_BACK);
        manager.loadVideoGroup(group);

        verify(single).setVideoURI(nullable(Uri.class));
        for (VideoView hidden : new VideoView[]{front, back, left, right}) {
            verify(hidden, never()).setVideoURI(nullable(Uri.class));
        }
        prepare(single);
        assertTrue(manager.isPlaying());
        verify(single).start();
    }

    @Test public void switchingModeReleasesHiddenPlayersAndPreservesPausedPosition() {
        manager.loadVideoGroup(group);
        for (VideoView view : new VideoView[]{front, back, left, right}) prepare(view);
        manager.pause();
        when(front.getCurrentPosition()).thenReturn(12000);
        clearInvocations(front, back, left, right, single);

        manager.setSingleMode(true, VideoGroup.POSITION_LEFT);
        InOrder order = inOrder(front, back, left, right, single);
        order.verify(front).stopPlayback();
        order.verify(back).stopPlayback();
        order.verify(left).stopPlayback();
        order.verify(right).stopPlayback();
        order.verify(single).stopPlayback();
        order.verify(single).setVideoURI(nullable(Uri.class));
        prepare(single);

        assertFalse(manager.isPlaying());
        verify(single).seekTo(12000);
        verify(single, never()).start();
    }

    @Test public void callbacksFromPreviousGroupCannotPrepareOrStartCurrentPlayback() {
        manager.loadVideoGroup(group);
        MediaPlayer.OnPreparedListener stale = preparedListener(front);
        manager.setSingleMode(true, VideoGroup.POSITION_BACK);
        clearInvocations(listener);

        stale.onPrepared(mock(MediaPlayer.class));
        assertFalse(manager.isPlaying());
        verify(listener, never()).onPrepared(anyInt());
        verify(single, never()).start();

        prepare(single);
        assertTrue(manager.isPlaying());
    }

    @Test public void returningToMultiViewReleasesSingleDecoderAndRestoresPlayback() {
        manager.updateSingleModePosition(true, VideoGroup.POSITION_BACK);
        manager.loadVideoGroup(group);
        prepare(single);
        when(single.getCurrentPosition()).thenReturn(9000);
        clearInvocations(front, back, left, right, single);

        manager.setSingleMode(false, null);
        verify(single).stopPlayback();
        verify(single, never()).setVideoURI(nullable(Uri.class));
        for (VideoView view : new VideoView[]{front, back, left, right}) prepare(view);

        assertTrue(manager.isPlaying());
        for (VideoView view : new VideoView[]{front, back, left, right}) {
            verify(view).seekTo(9000);
            verify(view).start();
        }
    }

    @Test public void oneFailedPlayerReleaseDoesNotLeakOtherDecoders() {
        doThrow(new IllegalStateException("already released")).when(front).stopPlayback();
        manager.stopAll();
        verify(back).stopPlayback();
        verify(left).stopPlayback();
        verify(right).stopPlayback();
        verify(single).stopPlayback();
    }
}
