package com.kooo.evcam.vehicle;

import com.kooo.evcam.vehicle.VehicleSignalSnapshot.Gear;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 验证 4.2 R 档延时打开预览、仅对 R 档自动拉起会话生效的车速返桌与 P 档延时返桌，以及手动操作不误返桌。
 */
public class ReversePreviewAutoCloseCoordinatorTest {

    private static final class FakeHost implements ReversePreviewAutoCloseCoordinator.Host {
        boolean foreground = false;
        int launchCount = 0;
        final List<String> returnReasons = new ArrayList<>();

        @Override
        public boolean isUiInForeground() {
            return foreground;
        }

        @Override
        public void launchReversePreviewToForeground() {
            launchCount++;
            foreground = true;
        }

        @Override
        public void moveReversePreviewToBackground(String reason) {
            returnReasons.add(reason);
            foreground = false;
        }
    }

    @Test
    public void reverseDelayOpensAfterConfiguredDelayAndCancelsIfLeftEarly() {
        final long[] now = {1000L};
        FakeHost host = new FakeHost();
        ReversePreviewAutoCloseCoordinator coordinator =
                new ReversePreviewAutoCloseCoordinator(host, () -> now[0], null);
        coordinator.setConfig(true, 200, true, 20, true, 3);

        // 先收到 P 档基线
        coordinator.onSignal(Gear.PARK, 0);
        assertEquals(0, host.launchCount);

        // 切入 R 档但 150ms 后切走 -> 取消，不拉起
        coordinator.onSignal(Gear.REVERSE, 0);
        now[0] += 150L;
        coordinator.flushTimers();
        assertEquals(0, host.launchCount);

        coordinator.onSignal(Gear.NEUTRAL, 0);
        now[0] += 300L;
        coordinator.flushTimers();
        assertEquals(0, host.launchCount);

        // 再次切入 R 档并稳住 200ms -> 拉起预览
        coordinator.onSignal(Gear.REVERSE, 0);
        now[0] += 200L;
        coordinator.flushTimers();
        assertEquals(1, host.launchCount);
        assertTrue(coordinator.isSessionOpenedByReverse());
    }

    @Test
    public void speedCloseReturnsToDesktopOnlyForReverseAutoOpenedSession() {
        final long[] now = {1000L};
        FakeHost host = new FakeHost();
        ReversePreviewAutoCloseCoordinator coordinator =
                new ReversePreviewAutoCloseCoordinator(host, () -> now[0], null);
        coordinator.setConfig(true, 0, true, 20, true, 3);

        // 场景 A：R 档自动拉起后挂 D 档加速到 20km/h -> 返回桌面
        coordinator.onSignal(Gear.PARK, 0);
        coordinator.onSignal(Gear.REVERSE, 0);
        assertEquals(1, host.launchCount);
        assertTrue(coordinator.isSessionOpenedByReverse());

        // 仍在 R 档哪怕报车速也不关闭
        coordinator.onSignal(Gear.REVERSE, 25);
        assertTrue(host.returnReasons.isEmpty());

        // 切入 D 档低速（18km/h < 20km/h）不关闭
        coordinator.onSignal(Gear.DRIVE, 18);
        assertTrue(host.returnReasons.isEmpty());

        // 加速到 20km/h -> 自动返桌
        coordinator.onSignal(Gear.DRIVE, 20);
        assertEquals(1, host.returnReasons.size());
        assertEquals("speed", host.returnReasons.get(0));
        assertFalse(coordinator.isSessionOpenedByReverse());

        // 场景 B：用户本来就在前台手动看画面时进 R/D/高速 -> 不误关
        host.foreground = true;
        coordinator.onSignal(Gear.REVERSE, 0);
        coordinator.onSignal(Gear.DRIVE, 40);
        assertEquals(1, host.returnReasons.size());
    }

    @Test
    public void parkDelayReturnsAfterWaitAndManualInteractionCancelsIt() {
        final long[] now = {1000L};
        FakeHost host = new FakeHost();
        ReversePreviewAutoCloseCoordinator coordinator =
                new ReversePreviewAutoCloseCoordinator(host, () -> now[0], null);
        coordinator.setConfig(true, 0, false, 20, true, 3);

        coordinator.onSignal(Gear.DRIVE, 0);
        coordinator.onSignal(Gear.REVERSE, 0);
        assertTrue(coordinator.isSessionOpenedByReverse());

        // 切回 P 档等待 2 秒离开 -> 取消
        coordinator.onSignal(Gear.PARK, 0);
        now[0] += 2000L;
        coordinator.flushTimers();
        assertTrue(host.returnReasons.isEmpty());
        coordinator.onSignal(Gear.DRIVE, 0);
        now[0] += 2000L;
        coordinator.flushTimers();
        assertTrue(host.returnReasons.isEmpty());

        // 再次切回 P 档稳住 3 秒 -> 返回桌面
        coordinator.onSignal(Gear.PARK, 0);
        now[0] += 3000L;
        coordinator.flushTimers();
        assertEquals(1, host.returnReasons.size());
        assertEquals("park", host.returnReasons.get(0));

        // 再次由 R 档拉起，但用户手动操作了界面（如点设置）-> 解除返桌锁定
        coordinator.onSignal(Gear.REVERSE, 0);
        assertTrue(coordinator.isSessionOpenedByReverse());
        assertTrue(coordinator.notifyUserManualInteraction());
        assertFalse(coordinator.isSessionOpenedByReverse());
        coordinator.onSignal(Gear.PARK, 0);
        now[0] += 5000L;
        coordinator.flushTimers();
        assertEquals(1, host.returnReasons.size());
    }
}
