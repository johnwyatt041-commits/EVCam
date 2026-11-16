package com.kooo.evcam.vehicle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 4.2 R 档自动拉起预览与车速 / P 档延时返桌状态机（纯 Java，无 Android UI 依赖）。
 * <ul>
 *     <li>进入 R 档并持续 reversePreviewDelayMs（0..5000ms，步长 50ms，默认 200ms）后拉起预览，期间退出 R 档立即取消。</li>
 *     <li>车速自动返回桌面（15..50 km/h，默认 20 km/h）与 P 档延时返回桌面（1..60s，默认 3s）仅对 R 档自动拉起的预览会话生效。</li>
 *     <li>用户手动打开或操作界面（如切换设置/编辑布局）后立即解除自动返桌接管，绝不误关手动会话。</li>
 * </ul>
 */
public final class ReversePreviewAutoCloseCoordinator {

    public static final int DEFAULT_REVERSE_DELAY_MS = 200;
    public static final int DEFAULT_SPEED_THRESHOLD_KMH = 20;
    public static final int DEFAULT_PARK_CLOSE_SECONDS = 3;

    public interface Host {
        boolean isUiInForeground();

        void launchReversePreviewToForeground();

        void moveReversePreviewToBackground(@NonNull String reason);
    }

    private final Host host;
    private final VehicleGearStatePublisher.Clock clock;
    private final VehicleGearStatePublisher.Scheduler scheduler;

    private boolean reversePreviewEnabled = false;
    private int reversePreviewDelayMs = DEFAULT_REVERSE_DELAY_MS;
    private boolean speedCloseEnabled = false;
    private int speedThresholdKmh = DEFAULT_SPEED_THRESHOLD_KMH;
    private boolean parkCloseEnabled = false;
    private int parkCloseDelaySeconds = DEFAULT_PARK_CLOSE_SECONDS;

    private boolean initialized = false;
    @NonNull
    private VehicleSignalSnapshot.Gear lastGear = VehicleSignalSnapshot.Gear.UNKNOWN;

    private boolean sessionOpenedByReverse = false;
    private boolean userInteracted = false;

    @Nullable
    private Object reverseDelayToken;
    private long reverseTriggerDueMs = -1L;

    @Nullable
    private Object parkCloseToken;
    private long parkCloseDueMs = -1L;

    public ReversePreviewAutoCloseCoordinator(@NonNull Host host) {
        this(host, System::currentTimeMillis,
                VehicleGearStatePublisher.createDefaultScheduler("ReversePreview"));
    }

    public ReversePreviewAutoCloseCoordinator(@NonNull Host host,
                                              @NonNull VehicleGearStatePublisher.Clock clock,
                                              @Nullable VehicleGearStatePublisher.Scheduler scheduler) {
        this.host = host;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public synchronized void setConfig(boolean reversePreviewEnabled,
                                       int reversePreviewDelayMs,
                                       boolean speedCloseEnabled,
                                       int speedThresholdKmh,
                                       boolean parkCloseEnabled,
                                       int parkCloseDelaySeconds) {
        this.reversePreviewEnabled = reversePreviewEnabled;
        this.reversePreviewDelayMs = Math.max(0, Math.min(5000, reversePreviewDelayMs));
        this.speedCloseEnabled = speedCloseEnabled;
        this.speedThresholdKmh = Math.max(15, Math.min(50, speedThresholdKmh));
        this.parkCloseEnabled = parkCloseEnabled;
        this.parkCloseDelaySeconds = Math.max(1, Math.min(60, parkCloseDelaySeconds));

        if (!this.reversePreviewEnabled) {
            cancelReverseTimerLocked();
        }
        if (!this.parkCloseEnabled) {
            cancelParkTimerLocked();
        }
    }

    public synchronized boolean isSessionOpenedByReverse() {
        return sessionOpenedByReverse && !userInteracted;
    }

    public void onSnapshot(@Nullable VehicleSignalSnapshot snapshot) {
        if (snapshot == null) {
            onSignal(VehicleSignalSnapshot.Gear.UNKNOWN, -1);
        } else {
            onSignal(snapshot.gear, snapshot.speedKmh);
        }
    }

    public void onSignal(@NonNull VehicleSignalSnapshot.Gear gear, int speedKmh) {
        boolean shouldLaunchReverse = false;
        String autoReturnReason = null;

        synchronized (this) {
            if (gear == VehicleSignalSnapshot.Gear.UNKNOWN) {
                cancelReverseTimerLocked();
                cancelParkTimerLocked();
                initialized = false;
                lastGear = VehicleSignalSnapshot.Gear.UNKNOWN;
                return;
            }

            long nowMs = clock.nowMs();

            if (!initialized) {
                initialized = true;
                lastGear = gear;
                if (reversePreviewEnabled
                        && gear == VehicleSignalSnapshot.Gear.REVERSE
                        && !host.isUiInForeground()) {
                    shouldLaunchReverse = startOrFlushReverseTimerLocked(nowMs);
                }
            } else if (gear != lastGear) {
                lastGear = gear;

                if (gear == VehicleSignalSnapshot.Gear.REVERSE) {
                    cancelParkTimerLocked();
                    if (reversePreviewEnabled && !host.isUiInForeground()) {
                        shouldLaunchReverse = startOrFlushReverseTimerLocked(nowMs);
                    }
                } else {
                    cancelReverseTimerLocked();
                    if (gear == VehicleSignalSnapshot.Gear.PARK) {
                        if (parkCloseEnabled && isSessionOpenedByReverse()) {
                            startOrFlushParkTimerLocked(nowMs);
                        }
                    } else {
                        cancelParkTimerLocked();
                    }
                }
            }

            // 仅对 R 档自动拉起的预览会话检查车速自动返桌
            if (!shouldLaunchReverse
                    && speedCloseEnabled
                    && isSessionOpenedByReverse()
                    && gear != VehicleSignalSnapshot.Gear.REVERSE
                    && speedKmh >= speedThresholdKmh
                    && host.isUiInForeground()) {
                clearReverseSessionLocked();
                autoReturnReason = "speed";
            }
        }

        if (shouldLaunchReverse) {
            host.launchReversePreviewToForeground();
        }
        if (autoReturnReason != null) {
            host.moveReversePreviewToBackground(autoReturnReason);
        }
    }

    /** 确定性推进已到期的 R 档打开计时或 P 档返桌计时（供单测及定时器复用）。 */
    public void flushTimers() {
        boolean shouldLaunch = false;
        String returnReason = null;
        synchronized (this) {
            long nowMs = clock.nowMs();
            if (reverseTriggerDueMs >= 0L && nowMs >= reverseTriggerDueMs) {
                reverseDelayToken = null;
                reverseTriggerDueMs = -1L;
                if (reversePreviewEnabled
                        && lastGear == VehicleSignalSnapshot.Gear.REVERSE
                        && !host.isUiInForeground()) {
                    markReverseSessionLocked();
                    shouldLaunch = true;
                }
            }
            if (parkCloseDueMs >= 0L && nowMs >= parkCloseDueMs) {
                parkCloseToken = null;
                parkCloseDueMs = -1L;
                if (parkCloseEnabled
                        && isSessionOpenedByReverse()
                        && lastGear == VehicleSignalSnapshot.Gear.PARK
                        && host.isUiInForeground()) {
                    clearReverseSessionLocked();
                    returnReason = "park";
                }
            }
        }
        if (shouldLaunch) {
            host.launchReversePreviewToForeground();
        }
        if (returnReason != null) {
            host.moveReversePreviewToBackground(returnReason);
        }
    }

    /** 用户手动进入/操作界面（如点选设置/手动打开预览/切换模式），立即解除自动返桌锁定。 */
    public synchronized boolean notifyUserManualInteraction() {
        boolean hadReverseSession = sessionOpenedByReverse && !userInteracted;
        cancelParkTimerLocked();
        userInteracted = true;
        sessionOpenedByReverse = false;
        return hadReverseSession;
    }

    /** 记录 UI 已回到桌面/后台。 */
    public synchronized void notifyUiMovedToBackground() {
        cancelParkTimerLocked();
        sessionOpenedByReverse = false;
        userInteracted = false;
    }

    public synchronized void reset() {
        cancelReverseTimerLocked();
        cancelParkTimerLocked();
        initialized = false;
        lastGear = VehicleSignalSnapshot.Gear.UNKNOWN;
        sessionOpenedByReverse = false;
        userInteracted = false;
    }

    private boolean startOrFlushReverseTimerLocked(long nowMs) {
        cancelReverseTimerLocked();
        if (reversePreviewDelayMs <= 0) {
            markReverseSessionLocked();
            return true;
        }
        reverseTriggerDueMs = nowMs + reversePreviewDelayMs;
        if (scheduler != null) {
            reverseDelayToken = scheduler.schedule(this::onReverseTimerFired, reversePreviewDelayMs);
        }
        return false;
    }

    private void onReverseTimerFired() {
        boolean shouldLaunch = false;
        synchronized (this) {
            reverseDelayToken = null;
            reverseTriggerDueMs = -1L;
            if (reversePreviewEnabled
                    && lastGear == VehicleSignalSnapshot.Gear.REVERSE
                    && !host.isUiInForeground()) {
                markReverseSessionLocked();
                shouldLaunch = true;
            }
        }
        if (shouldLaunch) {
            host.launchReversePreviewToForeground();
        }
    }

    private void startOrFlushParkTimerLocked(long nowMs) {
        cancelParkTimerLocked();
        long delayMs = parkCloseDelaySeconds * 1000L;
        parkCloseDueMs = nowMs + delayMs;
        if (scheduler != null) {
            parkCloseToken = scheduler.schedule(this::onParkTimerFired, delayMs);
        }
    }

    private void onParkTimerFired() {
        String reason = null;
        synchronized (this) {
            parkCloseToken = null;
            parkCloseDueMs = -1L;
            if (parkCloseEnabled
                    && isSessionOpenedByReverse()
                    && lastGear == VehicleSignalSnapshot.Gear.PARK
                    && host.isUiInForeground()) {
                clearReverseSessionLocked();
                reason = "park";
            }
        }
        if (reason != null) {
            host.moveReversePreviewToBackground(reason);
        }
    }

    private void markReverseSessionLocked() {
        sessionOpenedByReverse = true;
        userInteracted = false;
    }

    private void clearReverseSessionLocked() {
        cancelParkTimerLocked();
        sessionOpenedByReverse = false;
        userInteracted = false;
    }

    private void cancelReverseTimerLocked() {
        if (reverseDelayToken != null && scheduler != null) {
            scheduler.cancel(reverseDelayToken);
        }
        reverseDelayToken = null;
        reverseTriggerDueMs = -1L;
    }

    private void cancelParkTimerLocked() {
        if (parkCloseToken != null && scheduler != null) {
            scheduler.cancel(parkCloseToken);
        }
        parkCloseToken = null;
        parkCloseDueMs = -1L;
    }
}
