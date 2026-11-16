package com.kooo.evcam.vehicle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 标准挡位订阅与去抖契约（T06 内建，向后续 T10 导出稳定的 P 挡/行车挡确认状态）。
 * <ul>
 *     <li>对非 UNKNOWN 挡位跳变进行可配置去抖（默认 300ms），避免换挡瞬间经由 R/N 或毛刺误触发。</li>
 *     <li>在信号断开或显式 reset 转为 UNKNOWN 时立即取消确认，避免保留过期 P 挡状态。</li>
 * </ul>
 */
public final class VehicleGearStatePublisher {
    public static final long DEFAULT_DEBOUNCE_MS = 300L;

    public interface GearStateListener {
        void onGearStateChanged(@NonNull GearState state);
    }

    public interface Clock {
        long nowMs();
    }

    public interface Scheduler {
        @NonNull
        Object schedule(@NonNull Runnable task, long delayMs);

        void cancel(@Nullable Object token);
    }

    public static final class GearState {
        @NonNull
        public final VehicleSignalSnapshot.Gear stableGear;
        @NonNull
        public final VehicleSignalSnapshot.Gear rawGear;
        public final boolean confirmed;
        public final long changedAtMs;

        public GearState(@NonNull VehicleSignalSnapshot.Gear stableGear,
                         @NonNull VehicleSignalSnapshot.Gear rawGear,
                         boolean confirmed,
                         long changedAtMs) {
            this.stableGear = stableGear;
            this.rawGear = rawGear;
            this.confirmed = confirmed;
            this.changedAtMs = changedAtMs;
        }

        public boolean isParkConfirmed() {
            return confirmed && stableGear == VehicleSignalSnapshot.Gear.PARK;
        }

        public boolean isDrivingConfirmed() {
            return confirmed && (stableGear == VehicleSignalSnapshot.Gear.DRIVE
                    || stableGear == VehicleSignalSnapshot.Gear.REVERSE);
        }

        @NonNull
        @Override
        public String toString() {
            return "GearState{stableGear=" + stableGear
                    + ", rawGear=" + rawGear
                    + ", confirmed=" + confirmed
                    + ", changedAtMs=" + changedAtMs + '}';
        }
    }

    /** 创建可供 T06 协调器共用的守护线程调度器，避免重复线程工厂模板。 */
    @NonNull
    public static Scheduler createDefaultScheduler(@NonNull String threadName) {
        return new DaemonScheduler(threadName);
    }

    private static final class DaemonScheduler implements Scheduler {
        private final ScheduledExecutorService executor;

        DaemonScheduler(@NonNull String threadName) {
            this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
            });
        }

        @NonNull
        @Override
        public Object schedule(@NonNull Runnable task, long delayMs) {
            return executor.schedule(task, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        }

        @Override
        public void cancel(@Nullable Object token) {
            if (token instanceof ScheduledFuture<?>) {
                ((ScheduledFuture<?>) token).cancel(false);
            }
        }
    }

    private final long debounceMs;
    private final Clock clock;
    private final Scheduler scheduler;
    private final CopyOnWriteArrayList<GearStateListener> listeners = new CopyOnWriteArrayList<>();

    private VehicleSignalSnapshot.Gear stableGear = VehicleSignalSnapshot.Gear.UNKNOWN;
    private VehicleSignalSnapshot.Gear rawGear = VehicleSignalSnapshot.Gear.UNKNOWN;
    private boolean confirmed = false;
    private long changedAtMs = 0L;

    @Nullable
    private VehicleSignalSnapshot.Gear pendingGear = null;
    private long pendingSinceMs = 0L;
    @Nullable
    private Object pendingToken = null;

    public VehicleGearStatePublisher() {
        this(DEFAULT_DEBOUNCE_MS, System::currentTimeMillis, createDefaultScheduler("GearDebounce"));
    }

    public VehicleGearStatePublisher(long debounceMs, @NonNull Clock clock, @Nullable Scheduler scheduler) {
        this.debounceMs = Math.max(0L, debounceMs);
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public void addListener(@NonNull GearStateListener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(@NonNull GearStateListener listener) {
        listeners.remove(listener);
    }

    @NonNull
    public synchronized GearState getCurrentState() {
        return new GearState(stableGear, rawGear, confirmed, changedAtMs);
    }

    @NonNull
    public synchronized VehicleSignalSnapshot.Gear getStableGear() {
        return stableGear;
    }

    public synchronized boolean isParkConfirmed() {
        return confirmed && stableGear == VehicleSignalSnapshot.Gear.PARK;
    }

    public synchronized boolean isDrivingConfirmed() {
        return confirmed && (stableGear == VehicleSignalSnapshot.Gear.DRIVE
                || stableGear == VehicleSignalSnapshot.Gear.REVERSE);
    }

    public void onSnapshot(@Nullable VehicleSignalSnapshot snapshot) {
        GearState toNotify = null;
        synchronized (this) {
            VehicleSignalSnapshot.Gear incoming = snapshot != null
                    ? snapshot.gear
                    : VehicleSignalSnapshot.Gear.UNKNOWN;
            long nowMs = clock.nowMs();
            if (incoming == VehicleSignalSnapshot.Gear.UNKNOWN) {
                cancelPendingLocked();
                rawGear = VehicleSignalSnapshot.Gear.UNKNOWN;
                pendingGear = null;
                pendingSinceMs = 0L;
                if (confirmed || stableGear != VehicleSignalSnapshot.Gear.UNKNOWN) {
                    stableGear = VehicleSignalSnapshot.Gear.UNKNOWN;
                    confirmed = false;
                    changedAtMs = nowMs;
                    toNotify = getCurrentState();
                }
            } else {
                rawGear = incoming;
                if (confirmed && incoming == stableGear) {
                    // 短时毛刺已退回原稳定挡位：取消向其他挡位的待确认迁移
                    cancelPendingLocked();
                    pendingGear = null;
                    pendingSinceMs = 0L;
                } else if (pendingGear == incoming) {
                    if (nowMs - pendingSinceMs >= debounceMs) {
                        toNotify = commitPendingLocked(nowMs);
                    }
                } else {
                    cancelPendingLocked();
                    pendingGear = incoming;
                    pendingSinceMs = nowMs;
                    if (debounceMs <= 0L) {
                        toNotify = commitPendingLocked(nowMs);
                    } else if (scheduler != null) {
                        final VehicleSignalSnapshot.Gear targetGear = incoming;
                        pendingToken = scheduler.schedule(() -> onDebounceExpired(targetGear), debounceMs);
                    }
                }
            }
        }
        if (toNotify != null) {
            notifyListeners(toNotify, listeners);
        }
    }

    public boolean flushIfReady() {
        GearState toNotify = null;
        synchronized (this) {
            long nowMs = clock.nowMs();
            if (pendingGear != null && nowMs - pendingSinceMs >= debounceMs) {
                toNotify = commitPendingLocked(nowMs);
            }
        }
        if (toNotify != null) {
            notifyListeners(toNotify, listeners);
            return true;
        }
        return false;
    }

    public void reset() {
        GearState toNotify = null;
        synchronized (this) {
            cancelPendingLocked();
            boolean changed = confirmed
                    || stableGear != VehicleSignalSnapshot.Gear.UNKNOWN
                    || rawGear != VehicleSignalSnapshot.Gear.UNKNOWN;
            stableGear = VehicleSignalSnapshot.Gear.UNKNOWN;
            rawGear = VehicleSignalSnapshot.Gear.UNKNOWN;
            confirmed = false;
            pendingGear = null;
            pendingSinceMs = 0L;
            changedAtMs = clock.nowMs();
            if (changed) {
                toNotify = getCurrentState();
            }
        }
        if (toNotify != null) {
            notifyListeners(toNotify, listeners);
        }
    }

    private void onDebounceExpired(@NonNull VehicleSignalSnapshot.Gear targetGear) {
        GearState toNotify = null;
        synchronized (this) {
            long nowMs = clock.nowMs();
            if (pendingGear == targetGear && nowMs - pendingSinceMs >= debounceMs) {
                toNotify = commitPendingLocked(nowMs);
            }
        }
        if (toNotify != null) {
            notifyListeners(toNotify, listeners);
        }
    }

    @Nullable
    private GearState commitPendingLocked(long nowMs) {
        if (pendingGear == null) {
            return null;
        }
        cancelPendingLocked();
        VehicleSignalSnapshot.Gear target = pendingGear;
        pendingGear = null;
        pendingSinceMs = 0L;
        boolean nextConfirmed = target != VehicleSignalSnapshot.Gear.UNKNOWN;
        boolean stateChanged = !confirmed || stableGear != target;
        stableGear = target;
        confirmed = nextConfirmed;
        if (stateChanged) {
            changedAtMs = nowMs;
            return getCurrentState();
        }
        return null;
    }

    private void cancelPendingLocked() {
        if (scheduler != null && pendingToken != null) {
            scheduler.cancel(pendingToken);
        }
        pendingToken = null;
    }

    private static void notifyListeners(@NonNull GearState state, @NonNull List<GearStateListener> listeners) {
        for (GearStateListener listener : listeners) {
            listener.onGearStateChanged(state);
        }
    }
}
