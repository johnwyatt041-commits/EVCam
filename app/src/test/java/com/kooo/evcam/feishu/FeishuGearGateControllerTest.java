package com.kooo.evcam.feishu;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.configplaza.E5ConfigSection;
import com.kooo.evcam.remote.RemoteCommandDispatcher;
import com.kooo.evcam.vehicle.VehicleGearStatePublisher;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FeishuGearGateControllerTest {

    private static final class FakeScheduler implements VehicleGearStatePublisher.Scheduler {
        long timeMs = 10_000L;
        private Runnable pendingTask;
        private long dueMs;
        private Object currentToken;

        @Override
        public Object schedule(Runnable task, long delayMs) {
            Object token = new Object();
            pendingTask = task;
            dueMs = timeMs + Math.max(0L, delayMs);
            currentToken = token;
            return token;
        }

        @Override
        public void cancel(Object token) {
            if (token != null && token == currentToken) {
                pendingTask = null;
                currentToken = null;
            }
        }

        void advanceBy(long deltaMs, FeishuGearGateController controller) {
            timeMs += deltaMs;
            if (pendingTask != null && timeMs >= dueMs) {
                Runnable task = pendingTask;
                pendingTask = null;
                currentToken = null;
                task.run();
            } else {
                controller.flushDueTimers();
            }
        }
    }

    private static final class RecordingHost implements FeishuGearGateController.Host {
        final List<String> events = new ArrayList<>();

        @Override
        public void onConnectRequested(String reason) {
            events.add("CONNECT:" + reason);
        }

        @Override
        public void onDisconnectRequested(String reason) {
            events.add("DISCONNECT:" + reason);
        }
    }

    private static VehicleGearStatePublisher.GearState gearState(VehicleSignalSnapshot.Gear gear, boolean confirmed) {
        return new VehicleGearStatePublisher.GearState(gear, gear, confirmed, 0L);
    }

    private SharedPreferences createBackingPrefs(Map<String, Object> store) {
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(prefs.edit()).thenReturn(editor);
        when(prefs.contains(anyString())).thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
        when(prefs.getString(anyString(), nullable(String.class))).thenAnswer(inv -> {
            Object v = store.get(inv.getArgument(0));
            return v instanceof String ? (String) v : inv.getArgument(1);
        });
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            Object v = store.get(inv.getArgument(0));
            return v instanceof Boolean ? (Boolean) v : inv.getArgument(1);
        });
        when(prefs.getLong(anyString(), anyLong())).thenAnswer(inv -> {
            Object v = store.get(inv.getArgument(0));
            return v instanceof Long ? (Long) v : inv.getArgument(1);
        });
        when(editor.putString(anyString(), nullable(String.class))).thenAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.putLong(anyString(), anyLong())).thenAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.remove(anyString())).thenAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return editor;
        });
        when(editor.clear()).thenAnswer(inv -> {
            store.clear();
            return editor;
        });
        return prefs;
    }

    @Test
    public void coldStartUnknownDoesNotConnect() {
        FakeScheduler scheduler = new FakeScheduler();
        RecordingHost host = new RecordingHost();
        FeishuGearGateController controller = new FeishuGearGateController(
                host, () -> scheduler.timeMs, scheduler, 2000L, 500L, 3000L);

        controller.updateConfig(true, true, true, false);
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.UNKNOWN, false));
        scheduler.advanceBy(60_000L, controller);

        assertEquals(FeishuGearGateController.GateStatus.WAITING_GEAR_CONFIRM, controller.getStatus());
        assertFalse(controller.isConnectionRequested());
        assertTrue(host.events.isEmpty());
    }

    @Test
    public void enterParkDebounceRequires2000msAndCancelsTransientPark() {
        FakeScheduler scheduler = new FakeScheduler();
        RecordingHost host = new RecordingHost();
        FeishuGearGateController controller = new FeishuGearGateController(
                host, () -> scheduler.timeMs, scheduler, 2000L, 500L, 3000L);

        controller.updateConfig(true, true, true, false);
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.PARK, true));
        assertEquals(FeishuGearGateController.GateStatus.ENTERING_PARK_DEBOUNCE, controller.getStatus());

        // 1500ms 时切回 D 档：必须取消建连
        scheduler.advanceBy(1500L, controller);
        assertTrue(host.events.isEmpty());
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.DRIVE, true));
        scheduler.advanceBy(2000L, controller);
        assertEquals(FeishuGearGateController.GateStatus.WAITING_NON_PARK, controller.getStatus());
        assertTrue(host.events.isEmpty());

        // 再次进入 P 档并稳定 >= 2000ms：只发起一次懒加载建连
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.PARK, true));
        scheduler.advanceBy(1999L, controller);
        assertTrue(host.events.isEmpty());
        scheduler.advanceBy(1L, controller);

        assertEquals(FeishuGearGateController.GateStatus.PARK_CONNECTED, controller.getStatus());
        assertTrue(controller.isConnectionRequested());
        assertEquals(1, host.events.size());
        assertTrue(host.events.get(0).startsWith("CONNECT:"));
    }

    @Test
    public void leaveParkDebounceDisconnectsCleanlyAfter500ms() {
        FakeScheduler scheduler = new FakeScheduler();
        RecordingHost host = new RecordingHost();
        FeishuGearGateController controller = new FeishuGearGateController(
                host, () -> scheduler.timeMs, scheduler, 2000L, 500L, 3000L);

        controller.updateConfig(true, true, true, false);
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.PARK, true));
        scheduler.advanceBy(2000L, controller);
        assertEquals(1, host.events.size());

        // 短暂误报切出 P 档后 300ms 回到 P：不断开
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.REVERSE, true));
        assertEquals(FeishuGearGateController.GateStatus.LEAVING_PARK_DEBOUNCE, controller.getStatus());
        scheduler.advanceBy(300L, controller);
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.PARK, true));
        scheduler.advanceBy(500L, controller);
        assertEquals(FeishuGearGateController.GateStatus.PARK_CONNECTED, controller.getStatus());
        assertEquals(1, host.events.size());

        // 真实挂 D 档离开并保持 500ms：立即彻底断连
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.DRIVE, true));
        scheduler.advanceBy(499L, controller);
        assertEquals(1, host.events.size());
        scheduler.advanceBy(1L, controller);

        assertEquals(FeishuGearGateController.GateStatus.WAITING_NON_PARK, controller.getStatus());
        assertFalse(controller.isConnectionRequested());
        assertEquals(2, host.events.size());
        assertTrue(host.events.get(1).startsWith("DISCONNECT:left_park_DRIVE"));
    }

    @Test
    public void unknownGraceDisconnectsAfter3000msOnlyWhenPreviouslyConnected() {
        FakeScheduler scheduler = new FakeScheduler();
        RecordingHost host = new RecordingHost();
        FeishuGearGateController controller = new FeishuGearGateController(
                host, () -> scheduler.timeMs, scheduler, 2000L, 500L, 3000L);

        controller.updateConfig(true, true, true, false);
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.PARK, true));
        scheduler.advanceBy(2000L, controller);
        assertTrue(controller.isConnectionRequested());

        // 遇到短暂 UNKNOWN，2000ms 内恢复 P 档 -> 保活不断
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.UNKNOWN, false));
        assertEquals(FeishuGearGateController.GateStatus.SIGNAL_LOST_GRACE, controller.getStatus());
        scheduler.advanceBy(2000L, controller);
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.PARK, true));
        scheduler.advanceBy(3000L, controller);
        assertTrue(controller.isConnectionRequested());
        assertEquals(1, host.events.size());

        // 再次信号失联超过 3000ms -> 强制安全断连
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.UNKNOWN, false));
        scheduler.advanceBy(3000L, controller);
        assertFalse(controller.isConnectionRequested());
        assertEquals(FeishuGearGateController.GateStatus.WAITING_GEAR_CONFIRM, controller.getStatus());
        assertEquals(2, host.events.size());
        assertEquals("DISCONNECT:gear_unknown_timeout", host.events.get(1));
    }

    @Test
    public void disableOrUserExitedDisconnectsImmediatelyWithZeroDebounce() {
        FakeScheduler scheduler = new FakeScheduler();
        RecordingHost host = new RecordingHost();
        FeishuGearGateController controller = new FeishuGearGateController(
                host, () -> scheduler.timeMs, scheduler, 2000L, 500L, 3000L);

        controller.updateConfig(true, true, true, false);
        controller.onGearStateChanged(gearState(VehicleSignalSnapshot.Gear.PARK, true));
        scheduler.advanceBy(2000L, controller);
        assertEquals(1, host.events.size());

        controller.updateConfig(true, false, true, false);
        assertEquals(FeishuGearGateController.GateStatus.DISABLED, controller.getStatus());
        assertFalse(controller.isConnectionRequested());
        assertEquals(2, host.events.size());
        assertEquals("DISCONNECT:disabled_or_unconfigured", host.events.get(1));
    }

    @Test
    public void feishuConfigMigrationAndZeroRemoteInitGuarantees() {
        Map<String, Object> freshStore = new HashMap<>();
        SharedPreferences freshPrefs = createBackingPrefs(freshStore);
        FeishuConfig freshConfig = new FeishuConfig(freshPrefs);
        assertFalse(FeishuConfig.isFeatureActive(freshPrefs));
        assertFalse(freshConfig.isEnabled());
        assertTrue(freshConfig.isPGearOnly());

        // 老用户保留映射：已配置 appId+appSecret 且原 auto_start=true，迁移为 enabled=true + pGearOnly=true
        Map<String, Object> legacyStore = new HashMap<>();
        legacyStore.put("app_id", "cli_xxx");
        legacyStore.put("app_secret", "sec_yyy");
        legacyStore.put("auto_start", true);
        SharedPreferences legacyPrefs = createBackingPrefs(legacyStore);
        FeishuConfig legacyConfig = new FeishuConfig(legacyPrefs);
        assertTrue(legacyConfig.isEnabled());
        assertTrue(legacyConfig.isPGearOnly());
        assertTrue(FeishuConfig.isFeatureActive(legacyPrefs));

        legacyConfig.setEnabled(false);
        assertFalse(FeishuConfig.isFeatureActive(legacyPrefs));

        // 验证纯后台无远程场景下 RemoteCommandDispatcher 不预创建 FeishuHandler
        Context mockContext = mock(Context.class);
        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        RemoteCommandDispatcher dispatcher = new RemoteCommandDispatcher(mockContext);
        assertFalse(dispatcher.hasFeishuHandler());
        dispatcher.setFeishuApiClient(null);
        assertFalse(dispatcher.hasFeishuHandler());

        // 验证飞书密钥与 PAT 均不在 T09 配置广场白名单内
        assertNull(E5ConfigSection.fromId("feishu"));
        assertNull(E5ConfigSection.fromId("github_update_secrets"));
    }
}
