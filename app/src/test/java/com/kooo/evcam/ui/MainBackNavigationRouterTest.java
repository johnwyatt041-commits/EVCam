package com.kooo.evcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;

import org.junit.Test;
import org.mockito.InOrder;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主界面返回导航优先级路由及 OnBackPressedDispatcher 分发测试。
 */
public class MainBackNavigationRouterTest {

    @Test
    public void dispatchPrioritizesLayoutEditOverFullscreenAndFragments() {
        MainBackNavigationRouter.Host host = mock(MainBackNavigationRouter.Host.class);
        when(host.isLayoutEditing()).thenReturn(true);
        when(host.isFullscreenPreview()).thenReturn(true);
        when(host.handleSettingsSubPageBack()).thenReturn(true);

        MainBackNavigationRouter.Action action = MainBackNavigationRouter.dispatch(host);

        assertEquals(MainBackNavigationRouter.Action.FINISH_LAYOUT_EDIT, action);
        verify(host).finishLayoutEditing();
        verify(host, never()).exitFullscreenPreview();
        verify(host, never()).handleSettingsSubPageBack();
        verify(host, never()).popFragmentBackStack();
        verify(host, never()).returnToRecordingInterface();
        verify(host, never()).moveToBackground();
    }

    @Test
    public void dispatchExitsFullscreenBeforeCheckingSettingsOrBackStack() {
        MainBackNavigationRouter.Host host = mock(MainBackNavigationRouter.Host.class);
        when(host.isLayoutEditing()).thenReturn(false);
        when(host.isFullscreenPreview()).thenReturn(true);

        MainBackNavigationRouter.Action action = MainBackNavigationRouter.dispatch(host);

        assertEquals(MainBackNavigationRouter.Action.EXIT_FULLSCREEN_PREVIEW, action);
        verify(host).exitFullscreenPreview();
        verify(host, never()).handleSettingsSubPageBack();
        verify(host, never()).popFragmentBackStack();
    }

    @Test
    public void dispatchDelegatesToSettingsSubPageBeforeMainBackStack() {
        MainBackNavigationRouter.Host host = mock(MainBackNavigationRouter.Host.class);
        when(host.isLayoutEditing()).thenReturn(false);
        when(host.isFullscreenPreview()).thenReturn(false);
        when(host.handleSettingsSubPageBack()).thenReturn(true);
        when(host.getFragmentBackStackEntryCount()).thenReturn(2);

        MainBackNavigationRouter.Action action = MainBackNavigationRouter.dispatch(host);

        assertEquals(MainBackNavigationRouter.Action.POP_SETTINGS_SUBPAGE, action);
        verify(host).handleSettingsSubPageBack();
        verify(host, never()).popFragmentBackStack();
        verify(host, never()).returnToRecordingInterface();
    }

    @Test
    public void dispatchPopsFragmentBackStackBeforeReturningToRecording() {
        MainBackNavigationRouter.Host host = mock(MainBackNavigationRouter.Host.class);
        when(host.isLayoutEditing()).thenReturn(false);
        when(host.isFullscreenPreview()).thenReturn(false);
        when(host.handleSettingsSubPageBack()).thenReturn(false);
        when(host.getFragmentBackStackEntryCount()).thenReturn(1);
        when(host.isFragmentContainerVisible()).thenReturn(true);

        MainBackNavigationRouter.Action action = MainBackNavigationRouter.dispatch(host);

        assertEquals(MainBackNavigationRouter.Action.POP_FRAGMENT_BACK_STACK, action);
        verify(host).popFragmentBackStack();
        verify(host, never()).returnToRecordingInterface();
        verify(host, never()).moveToBackground();
    }

    @Test
    public void dispatchReturnsToRecordingWhenFragmentContainerVisibleAndNoBackStack() {
        MainBackNavigationRouter.Host host = mock(MainBackNavigationRouter.Host.class);
        when(host.isLayoutEditing()).thenReturn(false);
        when(host.isFullscreenPreview()).thenReturn(false);
        when(host.handleSettingsSubPageBack()).thenReturn(false);
        when(host.getFragmentBackStackEntryCount()).thenReturn(0);
        when(host.isFragmentContainerVisible()).thenReturn(true);

        MainBackNavigationRouter.Action action = MainBackNavigationRouter.dispatch(host);

        assertEquals(MainBackNavigationRouter.Action.RETURN_TO_RECORDING, action);
        verify(host).returnToRecordingInterface();
        verify(host, never()).moveToBackground();
    }

    @Test
    public void dispatchMovesToBackgroundOnRecordingScreen() {
        MainBackNavigationRouter.Host host = mock(MainBackNavigationRouter.Host.class);
        when(host.isLayoutEditing()).thenReturn(false);
        when(host.isFullscreenPreview()).thenReturn(false);
        when(host.handleSettingsSubPageBack()).thenReturn(false);
        when(host.getFragmentBackStackEntryCount()).thenReturn(0);
        when(host.isFragmentContainerVisible()).thenReturn(false);

        MainBackNavigationRouter.Action action = MainBackNavigationRouter.dispatch(host);

        assertEquals(MainBackNavigationRouter.Action.MOVE_TO_BACKGROUND, action);
        verify(host).moveToBackground();
    }

    @Test
    public void onBackPressedDispatcherRoutesThroughRegisteredCallbackWithoutFallback() {
        MainBackNavigationRouter.Host host = mock(MainBackNavigationRouter.Host.class);
        when(host.isFragmentContainerVisible()).thenReturn(true);

        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);
        OnBackPressedDispatcher dispatcher = new OnBackPressedDispatcher(() -> fallbackInvoked.set(true));
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                MainBackNavigationRouter.dispatch(host);
            }
        };
        dispatcher.addCallback(callback);

        dispatcher.onBackPressed();

        assertFalse(fallbackInvoked.get());
        InOrder order = inOrder(host);
        order.verify(host).isLayoutEditing();
        order.verify(host).isFullscreenPreview();
        order.verify(host).handleSettingsSubPageBack();
        order.verify(host).getFragmentBackStackEntryCount();
        order.verify(host).isFragmentContainerVisible();
        order.verify(host).returnToRecordingInterface();

        callback.setEnabled(false);
        dispatcher.onBackPressed();
        assertTrue(fallbackInvoked.get());
    }
}
