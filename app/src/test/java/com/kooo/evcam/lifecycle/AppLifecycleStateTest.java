package com.kooo.evcam.lifecycle;

import android.content.SharedPreferences;

import org.junit.Test;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 T01 统一退出双状态（user_exited / ui_exited）、Option A 开机自启拦截与协调器执行顺序。
 */
public class AppLifecycleStateTest {

    private static final class InMemoryPrefs {
        final Map<String, Boolean> map = new HashMap<>();
        final SharedPreferences prefs = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        boolean committed = false;

        InMemoryPrefs() {
            when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                boolean def = inv.getArgument(1);
                Boolean val = map.get(key);
                return val != null ? val : def;
            });
            when(prefs.edit()).thenReturn(editor);
            when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
                map.put(inv.getArgument(0), inv.getArgument(1));
                return editor;
            });
            when(editor.commit()).thenAnswer(inv -> {
                committed = true;
                return true;
            });
        }
    }

    @Test
    public void fullExitPersistsUserAndUiExitedAndBlocksOptionABoot() {
        InMemoryPrefs store = new InMemoryPrefs();
        assertTrue(AppLifecycleState.canRunBackgroundServices(store.prefs));

        AppLifecycleState.markUserExited(store.prefs);

        assertTrue(store.committed);
        assertTrue(AppLifecycleState.isUserExited(store.prefs));
        assertTrue(AppLifecycleState.isUiExited(store.prefs));
        assertFalse(AppLifecycleState.canRunBackgroundServices(store.prefs));
        assertFalse(AppLifecycleState.canHandleBootCompleted(AppLifecycleState.isUserExited(store.prefs)));
        assertFalse(AppLifecycleState.canLaunchUiOnBoot(true, true));
        assertFalse(AppLifecycleState.shouldAutoLaunchUiFromBackground(true, true));
        assertFalse(AppLifecycleState.shouldScheduleServiceRestart(true, false));

        // 即使错误触发返回前台回调，user_exited=true 期间也不得抹掉退出态
        AppLifecycleState.markUiReturnedForeground(store.prefs);
        assertTrue(AppLifecycleState.isUserExited(store.prefs));
        assertTrue(AppLifecycleState.isUiExited(store.prefs));
    }

    @Test
    public void uiExitPreservesBackgroundServicesButBlocksSilentUiPullUp() {
        InMemoryPrefs store = new InMemoryPrefs();

        AppLifecycleState.markUiExited(store.prefs);

        assertFalse(AppLifecycleState.isUserExited(store.prefs));
        assertTrue(AppLifecycleState.isUiExited(store.prefs));
        assertTrue(AppLifecycleState.canRunBackgroundServices(false));
        assertTrue(AppLifecycleState.canHandleBootCompleted(false));
        assertFalse(AppLifecycleState.shouldAutoLaunchUiFromBackground(false, true));

        AppLifecycleState.markUiReturnedForeground(store.prefs);
        assertFalse(AppLifecycleState.isUiExited(store.prefs));
        assertTrue(AppLifecycleState.shouldAutoLaunchUiFromBackground(false, false));
    }

    @Test
    public void manualUiEntryClearsUserExitedAndRestoresBootCapability() {
        InMemoryPrefs store = new InMemoryPrefs();
        AppLifecycleState.markUserExited(store.prefs);
        store.committed = false;

        AppLifecycleState.markUserEnteredUi(store.prefs);

        assertTrue(store.committed);
        assertFalse(AppLifecycleState.isUserExited(store.prefs));
        assertFalse(AppLifecycleState.isUiExited(store.prefs));
        assertTrue(AppLifecycleState.canHandleBootCompleted(false));
        assertTrue(AppLifecycleState.canLaunchUiOnBoot(false, true));
        assertTrue(AppLifecycleState.shouldScheduleServiceRestart(false, false));
        assertFalse(AppLifecycleState.shouldScheduleServiceRestart(false, true));
    }

    @Test
    public void foregroundServiceStopPolicyOnUiDestroy() {
        assertTrue(AppLifecycleState.shouldStopForegroundOnUiDestroy(true, true, true));
        assertFalse(AppLifecycleState.shouldStopForegroundOnUiDestroy(false, true, false));
        assertFalse(AppLifecycleState.shouldStopForegroundOnUiDestroy(false, false, true));
        assertTrue(AppLifecycleState.shouldStopForegroundOnUiDestroy(false, false, false));
    }

    @Test
    public void localExitStopsRemoteAndProcessImmediatelyAfterMarkingUserExited() {
        AppExitCoordinator.PlatformActions actions = mock(AppExitCoordinator.PlatformActions.class);
        AppExitCoordinator.ExitHooks hooks = mock(AppExitCoordinator.ExitHooks.class);

        AppExitCoordinator.executeExit(actions, false, hooks);

        InOrder order = inOrder(actions, hooks);
        order.verify(actions).markUserExited();
        order.verify(hooks).stopRecordingIfNeeded();
        order.verify(actions).stopKeepAlive();
        order.verify(actions).stopServices();
        order.verify(actions).releaseWakeLocks();
        order.verify(actions).resetVehicleSignals();
        order.verify(hooks).releaseCameras();
        order.verify(actions).saveLogs();
        order.verify(actions).finishAppTasks();
        order.verify(hooks).finishUiTasks();
        order.verify(actions).stopRemoteServices();
        order.verify(hooks).terminateProcess();
        verify(actions, never()).scheduleDelayed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    public void remoteExitDefersRemoteDisconnectAndProcessTerminationForReplyFlush() {
        AppExitCoordinator.PlatformActions actions = mock(AppExitCoordinator.PlatformActions.class);
        AppExitCoordinator.ExitHooks hooks = mock(AppExitCoordinator.ExitHooks.class);

        org.mockito.Mockito.doAnswer(inv -> {
            Runnable delayed = inv.getArgument(0);
            long delayMs = inv.getArgument(1);
            assertEquals(600L, delayMs);
            verify(actions, never()).stopRemoteServices();
            verify(hooks, never()).terminateProcess();
            delayed.run();
            return null;
        }).when(actions).scheduleDelayed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());

        AppExitCoordinator.executeExit(actions, true, hooks);

        verify(actions).markUserExited();
        verify(actions).stopKeepAlive();
        verify(actions).stopServices();
        verify(actions).resetVehicleSignals();
        verify(actions).stopRemoteServices();
        verify(hooks).terminateProcess();
    }
}
