package com.kooo.evcam.vehicle;

import org.junit.Test;

import java.util.PriorityQueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 T06 R/D 换挡自动弹出记录仪与 X 秒后自动退后台状态机：
 * 首次快照不抢焦点、250ms 去抖、用户原本在前台不踢回后台、交互接管取消自动退后台。
 */
public class GearShiftPopupCoordinatorTest {

    private static final class ScheduledItem implements Comparable<ScheduledItem> {
        final long runAt;
        final Runnable task;
        boolean cancelled;

        ScheduledItem(long runAt, Runnable task) {
            this.runAt = runAt;
            this.task = task;
        }

        @Override
        public int compareTo(ScheduledItem o) {
            return Long.compare(this.runAt, o.runAt);
        }
    }

    private static final class FakeHarness implements GearShiftPopupCoordinator.Host {
        long nowMs = 10_000L;
        boolean inForeground = false;
        int launchCount = 0;
        int moveBackgroundCount = 0;
        final PriorityQueue<ScheduledItem> queue = new PriorityQueue<>();

        final VehicleGearStatePublisher.Clock clock = () -> nowMs;
        final VehicleGearStatePublisher.Scheduler scheduler = new VehicleGearStatePublisher.Scheduler() {
            @Override
            public Object schedule(Runnable task, long delayMs) {
                ScheduledItem item = new ScheduledItem(nowMs + Math.max(0L, delayMs), task);
                queue.add(item);
                return item;
            }

            @Override
            public void cancel(Object token) {
                if (token instanceof ScheduledItem) {
                    ((ScheduledItem) token).cancelled = true;
                }
            }
        };

        void advanceBy(long deltaMs) {
            long target = nowMs + deltaMs;
            while (!queue.isEmpty() && queue.peek().runAt <= target) {
                ScheduledItem next = queue.poll();
                if (!next.cancelled) {
                    nowMs = next.runAt;
                    next.task.run();
                }
            }
            nowMs = target;
        }

        @Override
        public boolean isUiInForeground() {
            return inForeground;
        }

        @Override
        public void launchRecordingPageToForeground() {
            launchCount++;
            inForeground = true;
        }

        @Override
        public void moveRecordingPageToBackground() {
            moveBackgroundCount++;
            inForeground = false;
        }
    }

    @Test
    public void launchesOnStableRdShiftAndAutoReturnsAfterConfiguredSeconds() {
        FakeHarness host = new FakeHarness();
        GearShiftPopupCoordinator coordinator =
                new GearShiftPopupCoordinator(host, 250L, host.clock, host.scheduler);
        coordinator.setConfig(true, 5);

        // 首次连接快照即便已经在 D 挡，也不抢前台焦点
        coordinator.onRawGear(VehicleSignalSnapshot.Gear.DRIVE);
        host.advanceBy(1000L);
        assertEquals(0, host.launchCount);

        // 经 P -> R 经过 250ms 去抖后弹出前台，并在 5 秒后自动退后台
        coordinator.onRawGear(VehicleSignalSnapshot.Gear.PARK);
        host.advanceBy(300L);
        coordinator.onRawGear(VehicleSignalSnapshot.Gear.REVERSE);
        host.advanceBy(249L);
        assertEquals(0, host.launchCount);

        host.advanceBy(1L);
        assertEquals(1, host.launchCount);
        assertTrue(coordinator.isAutoPopupActive());
        assertEquals(0, host.moveBackgroundCount);

        host.advanceBy(4999L);
        assertEquals(0, host.moveBackgroundCount);
        host.advanceBy(1L);
        assertEquals(1, host.moveBackgroundCount);
        assertFalse(coordinator.isAutoPopupActive());

        // UNKNOWN 断连重连回 R 不重复弹窗
        coordinator.onRawGear(VehicleSignalSnapshot.Gear.UNKNOWN);
        coordinator.onRawGear(VehicleSignalSnapshot.Gear.REVERSE);
        host.advanceBy(500L);
        assertEquals(1, host.launchCount);
    }

    @Test
    public void neverHijacksExistingForegroundAndCancelsAutoHideOnUserInteraction() {
        FakeHarness host = new FakeHarness();
        GearShiftPopupCoordinator coordinator =
                new GearShiftPopupCoordinator(host, 250L, host.clock, host.scheduler);
        coordinator.setConfig(true, 3);

        // 场景 A：用户原本就在前台设置页 -> 换入 D 挡不得触发弹出也不得 3 秒后把用户踢回后台
        host.inForeground = true;
        coordinator.onRawGear(VehicleSignalSnapshot.Gear.PARK);
        coordinator.onRawGear(VehicleSignalSnapshot.Gear.DRIVE);
        host.advanceBy(5000L);
        assertEquals(0, host.launchCount);
        assertEquals(0, host.moveBackgroundCount);

        // 场景 B：后台自动弹出 R 挡后，用户在倒计时内操作切页 -> 解除自动退后台
        host.inForeground = false;
        coordinator.onRawGear(VehicleSignalSnapshot.Gear.PARK);
        host.advanceBy(300L);
        coordinator.onRawGear(VehicleSignalSnapshot.Gear.REVERSE);
        host.advanceBy(300L);
        assertEquals(1, host.launchCount);
        assertTrue(coordinator.isAutoPopupActive());

        assertTrue(coordinator.notifyUserInteraction());
        assertFalse(coordinator.isAutoPopupActive());
        host.advanceBy(5000L);
        assertEquals(0, host.moveBackgroundCount);
    }

    @Test
    public void popupSecondsNormalizationMatchesContract() {
        assertEquals(5, GearShiftPopupCoordinator.normalizePopupSeconds(0));
        assertEquals(5, GearShiftPopupCoordinator.normalizePopupSeconds(99));
        for (int valid : new int[]{3, 5, 10, 15, 30}) {
            assertEquals(valid, GearShiftPopupCoordinator.normalizePopupSeconds(valid));
        }
    }
}
