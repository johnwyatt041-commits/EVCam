package com.kooo.evcam.playback;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.ViewGroup;
import android.widget.ImageView;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlaybackImageMemoryTest {

    @Test
    public void findActivityUnwrapsNestedContextWrappersAndGuardsCycles() {
        Activity activity = mock(Activity.class);
        ContextWrapper inner = mock(ContextWrapper.class);
        ContextWrapper outer = mock(ContextWrapper.class);
        when(inner.getBaseContext()).thenReturn(activity);
        when(outer.getBaseContext()).thenReturn(inner);

        assertSame(activity, PlaybackImageMemory.findActivity(outer));

        Context appContext = mock(Context.class);
        assertNull(PlaybackImageMemory.findActivity(appContext));
        assertNull(PlaybackImageMemory.findActivity(null));

        ContextWrapper cyclic = mock(ContextWrapper.class);
        when(cyclic.getBaseContext()).thenReturn(cyclic);
        assertNull(PlaybackImageMemory.findActivity(cyclic));
    }

    @Test
    public void canClearAndCanLoadRespectActivityLifecycleStates() {
        assertFalse(PlaybackImageMemory.canClear(null));
        assertFalse(PlaybackImageMemory.canLoad(null));

        Context appContext = mock(Context.class);
        assertTrue(PlaybackImageMemory.canClear(appContext));
        assertTrue(PlaybackImageMemory.canLoad(appContext));

        Activity active = mock(Activity.class);
        when(active.isDestroyed()).thenReturn(false);
        when(active.isFinishing()).thenReturn(false);
        assertTrue(PlaybackImageMemory.canClear(active));
        assertTrue(PlaybackImageMemory.canLoad(active));

        Activity finishingOnly = mock(Activity.class);
        when(finishingOnly.isDestroyed()).thenReturn(false);
        when(finishingOnly.isFinishing()).thenReturn(true);
        assertTrue(PlaybackImageMemory.canClear(finishingOnly));
        assertFalse(PlaybackImageMemory.canLoad(finishingOnly));

        Activity destroyed = mock(Activity.class);
        when(destroyed.isDestroyed()).thenReturn(true);
        when(destroyed.isFinishing()).thenReturn(true);
        ContextWrapper wrappedDestroyed = mock(ContextWrapper.class);
        when(wrappedDestroyed.getBaseContext()).thenReturn(destroyed);
        assertFalse(PlaybackImageMemory.canClear(destroyed));
        assertFalse(PlaybackImageMemory.canClear(wrappedDestroyed));
        assertFalse(PlaybackImageMemory.canLoad(destroyed));
        assertFalse(PlaybackImageMemory.canLoad(wrappedDestroyed));
    }

    @Test
    public void clearViewsSkipsGlideOnDestroyedActivityButClearsDrawables() {
        Activity destroyed = mock(Activity.class);
        when(destroyed.isDestroyed()).thenReturn(true);
        ContextWrapper wrappedDestroyed = mock(ContextWrapper.class);
        when(wrappedDestroyed.getBaseContext()).thenReturn(destroyed);

        ImageView childImage = mock(ImageView.class);
        when(childImage.getContext()).thenReturn(wrappedDestroyed);
        ImageView nestedImage = mock(ImageView.class);
        when(nestedImage.getContext()).thenReturn(destroyed);

        ViewGroup nestedGroup = mock(ViewGroup.class);
        when(nestedGroup.getChildCount()).thenReturn(1);
        when(nestedGroup.getChildAt(0)).thenReturn(nestedImage);

        ViewGroup rootGroup = mock(ViewGroup.class);
        when(rootGroup.getChildCount()).thenReturn(2);
        when(rootGroup.getChildAt(0)).thenReturn(childImage);
        when(rootGroup.getChildAt(1)).thenReturn(nestedGroup);

        AtomicInteger glideCalls = new AtomicInteger();
        PlaybackImageMemory.clearViews(rootGroup, view -> glideCalls.incrementAndGet());

        assertEquals(0, glideCalls.get());
        verify(childImage).setImageDrawable(null);
        verify(nestedImage).setImageDrawable(null);
    }

    @Test
    public void clearViewsInvokesGlideOnActiveActivityAndHandlesMidTeardownException() {
        Activity active = mock(Activity.class);
        when(active.isDestroyed()).thenReturn(false);

        ImageView okImage = mock(ImageView.class);
        when(okImage.getContext()).thenReturn(active);
        ImageView racingImage = mock(ImageView.class);
        when(racingImage.getContext()).thenReturn(active);

        ViewGroup rootGroup = mock(ViewGroup.class);
        when(rootGroup.getChildCount()).thenReturn(2);
        when(rootGroup.getChildAt(0)).thenReturn(okImage);
        when(rootGroup.getChildAt(1)).thenReturn(racingImage);

        AtomicInteger glideCalls = new AtomicInteger();
        PlaybackImageMemory.clearViews(rootGroup, view -> {
            glideCalls.incrementAndGet();
            if (view == racingImage) {
                throw new IllegalArgumentException("You cannot start a load for a destroyed activity");
            }
        });

        assertEquals(2, glideCalls.get());
        verify(okImage).setImageDrawable(null);
        verify(racingImage).setImageDrawable(null);
    }
}
