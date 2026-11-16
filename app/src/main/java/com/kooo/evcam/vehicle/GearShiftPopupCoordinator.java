package com.kooo.evcam.vehicle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * R/D 换挡自动弹出记录仪并在 X 秒后自动退后台的状态机（纯 Java，无 Android UI 依赖）。
 * <ul>
 *     <li>首次连上信号快照仅建立基线，不抢前台。</li>
 *     <li>250ms 挡位去抖，防止 P->R->N->D 换挡过程抖动连发。</li>
 *     <li>仅当目标挡位稳定为 R/D 且与上次稳定触发挡位不同，且当前不在非自动弹出前台会话中时触发。</li>
 *     <li>自动弹出期间一旦检测到用户主动操作（切页/编辑/手动取消），立即解除自动退后台接管。</li>
 * </ul>
 */
public final class GearShiftPopupCoordinator {
    public static final int DEFAULT_POPUP_SECONDS = 5;
    public static final long DEFAULT_DEBOUNCE_MS = 250L;

    public interface Host {
        boolean isUiInForeground();

        void launchRecordingPageToForeground();

        void moveRecordingPageToBackground();
    }

    private final Host host;
    private final long debounceMs;
    private final VehicleGearStatePublisher.Clock clock;
    private final VehicleGearStatePublisher.Scheduler scheduler;

    private boolean enabled = false;
    private int autoHideSeconds = DEFAULT_POPUP_SECONDS;

    private boolean initialized = false;
    @NonNull
    private VehicleSignalSnapshot.Gear lastCommittedGear = VehicleSignalSnapshot.Gear.UNKNOWN;
    @Nullable
    private VehicleSignalSnapshot.Gear pendingGear = null;
    private long pendingSinceMs = 0L;
    @Nullable
    private Object debounceToken = null;

    private boolean autoPopupActive = false;
    private boolean userInteracted = false;
    private long popupSessionSeq = 0L;
    @Nullable
    private Object autoHideToken = null;

    public GearShiftPopupCoordinator(@NonNull Host host) {
        this(host, DEFAULT_DEBOUNCE_MS, System::currentTimeMillis,
                VehicleGearStatePublisher.createDefaultScheduler("GearPopup"));
    }

    public GearShiftPopupCoordinator(@NonNull Host host,
                                     long debounceMs,
                                     @NonNull VehicleGearStatePublisher.Clock clock,
                                     @Nullable VehicleGearStatePublisher.Scheduler scheduler) {
        this.host = host;
        this.debounceMs = Math.max(0L, debounceMs);
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public static int normalizePopupSeconds(int seconds) {
        if (seconds == 3 || seconds == 5 || seconds == 10 || seconds == 15 || seconds == 30) {
            return seconds;
        }
        return DEFAULT_POPUP_SECONDS;
    }

    public synchronized void setConfig(boolean enabled, int autoHideSeconds) {
        this.enabled = enabled;
        this.autoHideSeconds = normalizePopupSeconds(autoHideSeconds);
        if (!enabled) {
            cancelDebounceLocked();
            cancelAutoHideLocked();
            pendingGear = null;
            autoPopupActive = false;
            userInteracted = false;
        }
    }

    public synchronized boolean isAutoPopupActive() {
        return autoPopupActive && !userInteracted;
    }

    /**
     * 处理来自 VehicleSignalHub 的最新信号快照。
     */
    public void onSnapshot(@Nullable VehicleSignalSnapshot snapshot) {
        VehicleSignalSnapshot.Gear incoming = snapshot != null
                ? snapshot.gear
                : VehicleSignalSnapshot.Gear.UNKNOWN;
        onRawGear(incoming);
    }

    public void onRawGear(@NonNull VehicleSignalSnapshot.Gear incoming) {
        boolean shouldLaunch = false;
        boolean shouldAutoHideImmediate = false;
        synchronized (this) {
            if (incoming == VehicleSignalSnapshot.Gear.UNKNOWN) {
                cancelDebounceLocked();
                pendingGear = null;
                initialized = false;
                lastCommittedGear = VehicleSignalSnapshot.Gear.UNKNOWN;
                return;
            }
            if (!initialized) {
                initialized = true;
                lastCommittedGear = incoming;
                pendingGear = null;
                return;
            }
            long nowMs = clock.nowMs();
            if (incoming == lastCommittedGear) {
                cancelDebounceLocked();
                pendingGear = null;
                pendingSinceMs = 0L;
                return;
            }
            if (pendingGear == incoming) {
                if (nowMs - pendingSinceMs >= debounceMs) {
                    CommitOutcome outcome = commitGearLocked(incoming, nowMs);
                    shouldLaunch = outcome.launchForeground;
                    shouldAutoHideImmediate = outcome.autoHideImmediate;
                }
                // 否则继续等待既定去抖到期
            } else {
                cancelDebounceLocked();
                pendingGear = incoming;
                pendingSinceMs = nowMs;
                if (debounceMs <= 0L) {
                    CommitOutcome outcome = commitGearLocked(incoming, nowMs);
                    shouldLaunch = outcome.launchForeground;
                    shouldAutoHideImmediate = outcome.autoHideImmediate;
                } else if (scheduler != null) {
                    final VehicleSignalSnapshot.Gear target = incoming;
                    debounceToken = scheduler.schedule(() -> onDebounceExpired(target), debounceMs);
                }
            }
        }
        if (shouldLaunch) {
            host.launchRecordingPageToForeground();
        }
        if (shouldAutoHideImmediate) {
            host.moveRecordingPageToBackground();
        }
    }

    /**
     * 确定性推进已到期的挡位去抖（主要用于单元测试或轮询校时）。
     */
    public boolean flushDebounceIfReady() {
        boolean shouldLaunch = false;
        boolean shouldAutoHideImmediate = false;
        boolean committed = false;
        synchronized (this) {
            long nowMs = clock.nowMs();
            if (pendingGear != null && nowMs - pendingSinceMs >= debounceMs) {
                CommitOutcome outcome = commitGearLocked(pendingGear, nowMs);
                shouldLaunch = outcome.launchForeground;
                shouldAutoHideImmediate = outcome.autoHideImmediate;
                committed = true;
            }
        }
        if (shouldLaunch) {
            host.launchRecordingPageToForeground();
        }
        if (shouldAutoHideImmediate) {
            host.moveRecordingPageToBackground();
        }
        return committed;
    }

    /**
     * 用户在自动弹出期间进行了交互（切换 Tab、编辑预览或主动保持前台）：取消自动退后台。
     */
    public synchronized boolean notifyUserInteraction() {
        boolean cancelledAutoHide = autoPopupActive && !userInteracted;
        cancelAutoHideLocked();
        userInteracted = true;
        autoPopupActive = false;
        return cancelledAutoHide;
    }

    public synchronized void notifyUiMovedToBackground() {
        cancelAutoHideLocked();
        autoPopupActive = false;
        userInteracted = false;
    }

    public synchronized void reset() {
        cancelDebounceLocked();
        cancelAutoHideLocked();
        initialized = false;
        lastCommittedGear = VehicleSignalSnapshot.Gear.UNKNOWN;
        pendingGear = null;
        pendingSinceMs = 0L;
        autoPopupActive = false;
        userInteracted = false;
    }

    private void onDebounceExpired(@NonNull VehicleSignalSnapshot.Gear targetGear) {
        boolean shouldLaunch = false;
        boolean shouldAutoHideImmediate = false;
        synchronized (this) {
            long nowMs = clock.nowMs();
            if (pendingGear == targetGear && nowMs - pendingSinceMs >= debounceMs) {
                CommitOutcome outcome = commitGearLocked(targetGear, nowMs);
                shouldLaunch = outcome.launchForeground;
                shouldAutoHideImmediate = outcome.autoHideImmediate;
            }
        }
        if (shouldLaunch) {
            host.launchRecordingPageToForeground();
        }
        if (shouldAutoHideImmediate) {
            host.moveRecordingPageToBackground();
        }
    }

    private void onAutoHideExpired(long expectedSessionSeq) {
        boolean shouldMoveToBackground = false;
        synchronized (this) {
            if (popupSessionSeq == expectedSessionSeq
                    && autoPopupActive
                    && !userInteracted) {
                autoPopupActive = false;
                autoHideToken = null;
                shouldMoveToBackground = host.isUiInForeground();
            }
        }
        if (shouldMoveToBackground) {
            host.moveRecordingPageToBackground();
        }
    }

    private CommitOutcome commitGearLocked(@NonNull VehicleSignalSnapshot.Gear target, long nowMs) {
        cancelDebounceLocked();
        VehicleSignalSnapshot.Gear previous = lastCommittedGear;
        lastCommittedGear = target;
        pendingGear = null;
        pendingSinceMs = 0L;

        if (!enabled || previous == target) {
            return CommitOutcome.NONE;
        }
        boolean isTargetRd = target == VehicleSignalSnapshot.Gear.REVERSE
                || target == VehicleSignalSnapshot.Gear.DRIVE;
        if (!isTargetRd) {
            return CommitOutcome.NONE;
        }

        boolean alreadyForeground = host.isUiInForeground();
        // 用户原本主动留在前台使用应用时，不抢夺也不在倒计时后踢回后台
        if (alreadyForeground && !autoPopupActive) {
            return CommitOutcome.NONE;
        }

        autoPopupActive = true;
        userInteracted = false;
        popupSessionSeq++;
        cancelAutoHideLocked();

        long delayMs = autoHideSeconds * 1000L;
        boolean launchForeground = !alreadyForeground;
        if (delayMs <= 0L) {
            autoPopupActive = false;
            return new CommitOutcome(launchForeground, alreadyForeground);
        }
        if (scheduler != null) {
            final long seq = popupSessionSeq;
            autoHideToken = scheduler.schedule(() -> onAutoHideExpired(seq), delayMs);
        }
        return new CommitOutcome(launchForeground, false);
    }

    private void cancelDebounceLocked() {
        if (scheduler != null && debounceToken != null) {
            scheduler.cancel(debounceToken);
        }
        debounceToken = null;
    }

    private void cancelAutoHideLocked() {
        if (scheduler != null && autoHideToken != null) {
            scheduler.cancel(autoHideToken);
        }
        autoHideToken = null;
    }

    private static final class CommitOutcome {
        static final CommitOutcome NONE = new CommitOutcome(false, false);
        final boolean launchForeground;
        final boolean autoHideImmediate;

        CommitOutcome(boolean launchForeground, boolean autoHideImmediate) {
            this.launchForeground = launchForeground;
            this.autoHideImmediate = autoHideImmediate;
        }
    }
}
