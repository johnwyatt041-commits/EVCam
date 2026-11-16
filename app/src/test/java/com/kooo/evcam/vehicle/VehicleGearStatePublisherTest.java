package com.kooo.evcam.vehicle;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 T06 向 T10 导出的标准化挡位去抖发布器：300ms 去抖、瞬时毛刺过滤与 UNKNOWN 立即失效。
 */
public class VehicleGearStatePublisherTest {

    private static final class FakeTiming {
        long nowMs = 1000L;
        Runnable scheduledTask;
        long runAtMs = Long.MAX_VALUE;

        final VehicleGearStatePublisher.Clock clock = () -> nowMs;
        final VehicleGearStatePublisher.Scheduler scheduler = new VehicleGearStatePublisher.Scheduler() {
            @Override
            public Object schedule(Runnable task, long delayMs) {
                scheduledTask = task;
                runAtMs = nowMs + delayMs;
                return task;
            }

            @Override
            public void cancel(Object token) {
                if (token == scheduledTask) {
                    scheduledTask = null;
                    runAtMs = Long.MAX_VALUE;
                }
            }
        };

        void advanceBy(long deltaMs) {
            nowMs += deltaMs;
            if (scheduledTask != null && nowMs >= runAtMs) {
                Runnable task = scheduledTask;
                scheduledTask = null;
                runAtMs = Long.MAX_VALUE;
                task.run();
            }
        }
    }

    private static VehicleSignalSnapshot gearSnap(VehicleSignalSnapshot.Gear gear) {
        return new VehicleSignalSnapshot(gear, -1, -1, -1, -1, -1, -1, -1, 1L);
    }

    @Test
    public void debouncesGearTransitionsAndIgnoresMomentaryGlitch() {
        FakeTiming timing = new FakeTiming();
        VehicleGearStatePublisher publisher = new VehicleGearStatePublisher(300L, timing.clock, timing.scheduler);
        List<VehicleGearStatePublisher.GearState> events = new ArrayList<>();
        publisher.addListener(events::add);

        assertFalse(publisher.isParkConfirmed());
        assertEquals(VehicleSignalSnapshot.Gear.UNKNOWN, publisher.getStableGear());

        // 切入 P 挡，200ms 时尚未去抖确认
        publisher.onSnapshot(gearSnap(VehicleSignalSnapshot.Gear.PARK));
        timing.advanceBy(200L);
        assertFalse(publisher.isParkConfirmed());
        assertEquals(0, events.size());

        // 满 300ms：正式确认 P 挡
        timing.advanceBy(100L);
        assertTrue(publisher.isParkConfirmed());
        assertEquals(VehicleSignalSnapshot.Gear.PARK, publisher.getStableGear());
        assertEquals(1, events.size());
        assertTrue(events.get(0).isParkConfirmed());

        // D 挡毛刺持续 120ms 后退回 P 挡：不应取消 P 挡确认状态
        publisher.onSnapshot(gearSnap(VehicleSignalSnapshot.Gear.DRIVE));
        timing.advanceBy(120L);
        publisher.onSnapshot(gearSnap(VehicleSignalSnapshot.Gear.PARK));
        timing.advanceBy(400L);
        assertTrue(publisher.isParkConfirmed());
        assertEquals(1, events.size());

        // 真实挂挡经过 P -> R(80ms) -> N(80ms) -> D(300ms)：仅最终 D 挡确认
        publisher.onSnapshot(gearSnap(VehicleSignalSnapshot.Gear.REVERSE));
        timing.advanceBy(80L);
        publisher.onSnapshot(gearSnap(VehicleSignalSnapshot.Gear.NEUTRAL));
        timing.advanceBy(80L);
        publisher.onSnapshot(gearSnap(VehicleSignalSnapshot.Gear.DRIVE));
        timing.advanceBy(299L);
        assertTrue(publisher.isParkConfirmed());
        timing.advanceBy(1L);
        assertFalse(publisher.isParkConfirmed());
        assertTrue(publisher.isDrivingConfirmed());
        assertEquals(VehicleSignalSnapshot.Gear.DRIVE, publisher.getStableGear());
        assertEquals(2, events.size());
    }

    @Test
    public void signalDisconnectImmediatelyClearsParkConfirmed() {
        FakeTiming timing = new FakeTiming();
        VehicleGearStatePublisher publisher = new VehicleGearStatePublisher(300L, timing.clock, timing.scheduler);

        publisher.onSnapshot(gearSnap(VehicleSignalSnapshot.Gear.PARK));
        timing.advanceBy(300L);
        assertTrue(publisher.isParkConfirmed());

        publisher.onSnapshot(VehicleSignalSnapshot.UNKNOWN);
        assertFalse(publisher.isParkConfirmed());
        assertEquals(VehicleSignalSnapshot.Gear.UNKNOWN, publisher.getStableGear());
    }
}
