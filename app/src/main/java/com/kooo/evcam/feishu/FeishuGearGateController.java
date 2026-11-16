package com.kooo.evcam.feishu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.vehicle.VehicleGearStatePublisher;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;

/**
 * T10 飞书远程 P 档门控与懒加载状态机（纯 Java，依赖 T06 {@link VehicleGearStatePublisher} 契约）。
 *
 * 规则：
 * 1. 未配置、总开关关闭或处于 user_exited 时，0ms 立即断连并保持零连接请求；
 * 2. 开启仅 P 档时，冷启动 UNKNOWN 绝不臆测建连；确认进入 P 档后等待 2000ms（叠加 T06 300ms）再触发懒加载；
 * 3. 切离 P 档确认后经 500ms（叠加 T06 300ms）彻底断连；已连接态遇到临时 UNKNOWN 给与 3000ms 宽限，超时仍未恢复 P 档则强制断连。
 */
public final class FeishuGearGateController {

    public static final long DEFAULT_ENTER_PARK_DEBOUNCE_MS = 2000L;
    public static final long DEFAULT_LEAVE_PARK_DEBOUNCE_MS = 500L;
    public static final long DEFAULT_UNKNOWN_GRACE_MS = 3000L;

    public enum GateStatus {
        DISABLED,
        USER_EXITED,
        ALWAYS_ON_ACTIVE,
        WAITING_GEAR_CONFIRM,
        WAITING_NON_PARK,
        ENTERING_PARK_DEBOUNCE,
        PARK_CONNECTED,
        LEAVING_PARK_DEBOUNCE,
        SIGNAL_LOST_GRACE
    }

    public interface Host {
        void onConnectRequested(@NonNull String reason);

        void onDisconnectRequested(@NonNull String reason);

        default void onStatusChanged(@NonNull GateStatus status,
                                     @NonNull VehicleGearStatePublisher.GearState gearState) {
        }
    }

    private final Host host;
    private final VehicleGearStatePublisher.Clock clock;
    @Nullable
    private final VehicleGearStatePublisher.Scheduler scheduler;
    private final long enterParkDebounceMs;
    private final long leaveParkDebounceMs;
    private final long unknownGraceMs;

    private final Object lock = new Object();

    private boolean configured = false;
    private boolean enabled = false;
    private boolean pGearOnly = true;
    private boolean userExited = false;
    private boolean connectionRequested = false;

    @NonNull
    private VehicleGearStatePublisher.GearState latestGearState =
            new VehicleGearStatePublisher.GearState(
                    VehicleSignalSnapshot.Gear.UNKNOWN,
                    VehicleSignalSnapshot.Gear.UNKNOWN,
                    false,
                    0L);
    @NonNull
    private GateStatus status = GateStatus.DISABLED;

    @Nullable
    private Object enterParkToken;
    private long enterParkDueMs;
    @Nullable
    private Object leaveParkToken;
    private long leaveParkDueMs;
    @Nullable
    private Object unknownGraceToken;
    private long unknownGraceDueMs;

    public FeishuGearGateController(@NonNull Host host) {
        this(host, System::currentTimeMillis,
                VehicleGearStatePublisher.createDefaultScheduler("FeishuGearGate"),
                DEFAULT_ENTER_PARK_DEBOUNCE_MS,
                DEFAULT_LEAVE_PARK_DEBOUNCE_MS,
                DEFAULT_UNKNOWN_GRACE_MS);
    }

    public FeishuGearGateController(@NonNull Host host,
                                    @NonNull VehicleGearStatePublisher.Clock clock,
                                    @Nullable VehicleGearStatePublisher.Scheduler scheduler,
                                    long enterParkDebounceMs,
                                    long leaveParkDebounceMs,
                                    long unknownGraceMs) {
        this.host = host;
        this.clock = clock;
        this.scheduler = scheduler;
        this.enterParkDebounceMs = Math.max(0L, enterParkDebounceMs);
        this.leaveParkDebounceMs = Math.max(0L, leaveParkDebounceMs);
        this.unknownGraceMs = Math.max(0L, unknownGraceMs);
    }

    public void updateConfig(boolean configured, boolean enabled, boolean pGearOnly, boolean userExited) {
        PendingAction action = null;
        GateStatus newStatus;
        VehicleGearStatePublisher.GearState gearSnapshot;

        synchronized (lock) {
            this.configured = configured;
            this.enabled = enabled;
            this.pGearOnly = pGearOnly;
            this.userExited = userExited;
            gearSnapshot = this.latestGearState;

            if (!configured || !enabled || userExited) {
                cancelAllTimersLocked();
                boolean needDisconnect = connectionRequested;
                connectionRequested = false;
                status = userExited ? GateStatus.USER_EXITED : GateStatus.DISABLED;
                if (needDisconnect) {
                    action = PendingAction.disconnect(userExited ? "user_exited" : "disabled_or_unconfigured");
                }
            } else if (!pGearOnly) {
                cancelAllTimersLocked();
                status = GateStatus.ALWAYS_ON_ACTIVE;
                if (!connectionRequested) {
                    connectionRequested = true;
                    action = PendingAction.connect("always_on_mode");
                }
            } else {
                action = evaluateParkGateLocked(clock.nowMs(), gearSnapshot);
            }
            newStatus = status;
        }

        host.onStatusChanged(newStatus, gearSnapshot);
        dispatchAction(action);
    }

    public void onGearStateChanged(@Nullable VehicleGearStatePublisher.GearState gearState) {
        VehicleGearStatePublisher.GearState safeState = gearState != null
                ? gearState
                : new VehicleGearStatePublisher.GearState(
                        VehicleSignalSnapshot.Gear.UNKNOWN,
                        VehicleSignalSnapshot.Gear.UNKNOWN,
                        false,
                        0L);

        PendingAction action = null;
        GateStatus newStatus;

        synchronized (lock) {
            this.latestGearState = safeState;
            if (!configured || !enabled || userExited || !pGearOnly) {
                return;
            }
            action = evaluateParkGateLocked(clock.nowMs(), safeState);
            newStatus = status;
        }

        host.onStatusChanged(newStatus, safeState);
        dispatchAction(action);
    }

    public boolean flushDueTimers() {
        long nowMs = clock.nowMs();
        boolean fired = false;
        synchronized (lock) {
            if (enterParkToken != null && nowMs >= enterParkDueMs) {
                fired = true;
            } else if (leaveParkToken != null && nowMs >= leaveParkDueMs) {
                fired = true;
            } else if (unknownGraceToken != null && nowMs >= unknownGraceDueMs) {
                fired = true;
            }
        }
        if (!fired) {
            return false;
        }
        onTimerCheck();
        return true;
    }

    public void reset() {
        boolean needDisconnect;
        VehicleGearStatePublisher.GearState resetGear = new VehicleGearStatePublisher.GearState(
                VehicleSignalSnapshot.Gear.UNKNOWN,
                VehicleSignalSnapshot.Gear.UNKNOWN,
                false,
                clock.nowMs());
        synchronized (lock) {
            cancelAllTimersLocked();
            needDisconnect = connectionRequested;
            connectionRequested = false;
            latestGearState = resetGear;
            status = (!configured || !enabled)
                    ? GateStatus.DISABLED
                    : (userExited ? GateStatus.USER_EXITED : GateStatus.WAITING_GEAR_CONFIRM);
        }
        host.onStatusChanged(getStatus(), resetGear);
        if (needDisconnect) {
            host.onDisconnectRequested("gate_reset");
        }
    }

    @NonNull
    public GateStatus getStatus() {
        synchronized (lock) {
            return status;
        }
    }

    public boolean isConnectionRequested() {
        synchronized (lock) {
            return connectionRequested;
        }
    }

    private PendingAction evaluateParkGateLocked(long nowMs, VehicleGearStatePublisher.GearState state) {
        if (state.isParkConfirmed()) {
            cancelLeaveParkLocked();
            cancelUnknownGraceLocked();
            if (connectionRequested) {
                status = GateStatus.PARK_CONNECTED;
                return null;
            }
            if (enterParkDebounceMs <= 0L) {
                cancelEnterParkLocked();
                connectionRequested = true;
                status = GateStatus.PARK_CONNECTED;
                return PendingAction.connect("park_confirmed_immediate");
            }
            status = GateStatus.ENTERING_PARK_DEBOUNCE;
            if (enterParkToken == null) {
                enterParkDueMs = nowMs + enterParkDebounceMs;
                enterParkToken = scheduleTimerLocked(this::onTimerCheck, enterParkDebounceMs);
            }
            return null;
        }

        // 已明确进入非 P 档（D / R / N）
        if (state.confirmed && state.stableGear != VehicleSignalSnapshot.Gear.UNKNOWN) {
            cancelEnterParkLocked();
            cancelUnknownGraceLocked();
            if (!connectionRequested) {
                cancelLeaveParkLocked();
                status = GateStatus.WAITING_NON_PARK;
                return null;
            }
            if (leaveParkDebounceMs <= 0L) {
                cancelLeaveParkLocked();
                connectionRequested = false;
                status = GateStatus.WAITING_NON_PARK;
                return PendingAction.disconnect("left_park_" + state.stableGear.name());
            }
            status = GateStatus.LEAVING_PARK_DEBOUNCE;
            if (leaveParkToken == null) {
                leaveParkDueMs = nowMs + leaveParkDebounceMs;
                leaveParkToken = scheduleTimerLocked(this::onTimerCheck, leaveParkDebounceMs);
            }
            return null;
        }

        // 未确认或 UNKNOWN 信号
        cancelEnterParkLocked();
        if (!connectionRequested) {
            cancelLeaveParkLocked();
            cancelUnknownGraceLocked();
            status = GateStatus.WAITING_GEAR_CONFIRM;
            return null;
        }

        // 若已在离开 P 档倒计时中遇到 UNKNOWN，继续执行离开倒计时，不因断帧延迟断连
        if (leaveParkToken != null) {
            status = GateStatus.LEAVING_PARK_DEBOUNCE;
            return null;
        }

        if (unknownGraceMs <= 0L) {
            cancelUnknownGraceLocked();
            connectionRequested = false;
            status = GateStatus.WAITING_GEAR_CONFIRM;
            return PendingAction.disconnect("gear_unknown_immediate");
        }
        status = GateStatus.SIGNAL_LOST_GRACE;
        if (unknownGraceToken == null) {
            unknownGraceDueMs = nowMs + unknownGraceMs;
            unknownGraceToken = scheduleTimerLocked(this::onTimerCheck, unknownGraceMs);
        }
        return null;
    }

    private void onTimerCheck() {
        long nowMs = clock.nowMs();
        PendingAction action = null;
        GateStatus notifyStatus = null;
        VehicleGearStatePublisher.GearState notifyGear = null;

        synchronized (lock) {
            if (!configured || !enabled || userExited || !pGearOnly) {
                cancelAllTimersLocked();
                return;
            }
            if (enterParkToken != null && nowMs >= enterParkDueMs) {
                enterParkToken = null;
                enterParkDueMs = 0L;
                if (latestGearState.isParkConfirmed() && !connectionRequested) {
                    connectionRequested = true;
                    status = GateStatus.PARK_CONNECTED;
                    action = PendingAction.connect("park_debounced");
                    notifyStatus = status;
                    notifyGear = latestGearState;
                }
            } else if (leaveParkToken != null && nowMs >= leaveParkDueMs) {
                leaveParkToken = null;
                leaveParkDueMs = 0L;
                if (!latestGearState.isParkConfirmed() && connectionRequested) {
                    connectionRequested = false;
                    status = latestGearState.confirmed
                            ? GateStatus.WAITING_NON_PARK
                            : GateStatus.WAITING_GEAR_CONFIRM;
                    action = PendingAction.disconnect("left_park_" + latestGearState.stableGear.name());
                    notifyStatus = status;
                    notifyGear = latestGearState;
                }
            } else if (unknownGraceToken != null && nowMs >= unknownGraceDueMs) {
                unknownGraceToken = null;
                unknownGraceDueMs = 0L;
                if (!latestGearState.isParkConfirmed() && connectionRequested) {
                    connectionRequested = false;
                    status = GateStatus.WAITING_GEAR_CONFIRM;
                    action = PendingAction.disconnect("gear_unknown_timeout");
                    notifyStatus = status;
                    notifyGear = latestGearState;
                }
            }
        }

        if (notifyStatus != null && notifyGear != null) {
            host.onStatusChanged(notifyStatus, notifyGear);
        }
        dispatchAction(action);
    }

    private Object scheduleTimerLocked(Runnable task, long delayMs) {
        if (scheduler != null) {
            return scheduler.schedule(task, delayMs);
        }
        return new Object();
    }

    private void cancelEnterParkLocked() {
        if (enterParkToken != null && scheduler != null) {
            scheduler.cancel(enterParkToken);
        }
        enterParkToken = null;
        enterParkDueMs = 0L;
    }

    private void cancelLeaveParkLocked() {
        if (leaveParkToken != null && scheduler != null) {
            scheduler.cancel(leaveParkToken);
        }
        leaveParkToken = null;
        leaveParkDueMs = 0L;
    }

    private void cancelUnknownGraceLocked() {
        if (unknownGraceToken != null && scheduler != null) {
            scheduler.cancel(unknownGraceToken);
        }
        unknownGraceToken = null;
        unknownGraceDueMs = 0L;
    }

    private void cancelAllTimersLocked() {
        cancelEnterParkLocked();
        cancelLeaveParkLocked();
        cancelUnknownGraceLocked();
    }

    private void dispatchAction(@Nullable PendingAction action) {
        if (action == null) {
            return;
        }
        if (action.connect) {
            host.onConnectRequested(action.reason);
        } else {
            host.onDisconnectRequested(action.reason);
        }
    }

    public static String formatStatusLabel(@NonNull GateStatus status,
                                           @Nullable VehicleSignalSnapshot.Gear stableGear,
                                           boolean wsConnected,
                                           boolean wsConnecting) {
        switch (status) {
            case DISABLED:
                return "未启用（已断开，零后台连接）";
            case USER_EXITED:
                return "应用已主动退出（已断开）";
            case ALWAYS_ON_ACTIVE:
                if (wsConnected) return "已连接（全挡位常驻模式）";
                return wsConnecting ? "正在连接（全挡位常驻模式）..." : "待连接（全挡位常驻模式）";
            case WAITING_GEAR_CONFIRM:
                return "等待挡位确认（信号未就绪，已断开）";
            case WAITING_NON_PARK:
                return "等待进入 P 档（当前 " + formatGearName(stableGear) + " 档，已彻底断开）";
            case ENTERING_PARK_DEBOUNCE:
                return "已入 P 档，2 秒防抖稳定后自动连接...";
            case LEAVING_PARK_DEBOUNCE:
                return "已切离 P 档（" + formatGearName(stableGear) + " 档），正在彻底断开...";
            case SIGNAL_LOST_GRACE:
                return "挡位信号暂失，宽限保持中（超时自动断开）";
            case PARK_CONNECTED:
                if (wsConnected) return "P 档已连接";
                return wsConnecting ? "P 档就绪 · 正在懒加载连接..." : "P 档就绪 · 准备重连...";
            default:
                return "未连接";
        }
    }

    private static String formatGearName(@Nullable VehicleSignalSnapshot.Gear gear) {
        if (gear == null) {
            return "未知";
        }
        switch (gear) {
            case PARK:
                return "P";
            case REVERSE:
                return "R";
            case NEUTRAL:
                return "N";
            case DRIVE:
                return "D";
            default:
                return "未知";
        }
    }

    private static final class PendingAction {
        final boolean connect;
        final String reason;

        private PendingAction(boolean connect, String reason) {
            this.connect = connect;
            this.reason = reason;
        }

        static PendingAction connect(String reason) {
            return new PendingAction(true, reason);
        }

        static PendingAction disconnect(String reason) {
            return new PendingAction(false, reason);
        }
    }
}
