package com.kooo.evcam.blindspot;

/**
 * EC + SC 补盲择优融合协调器（纯 Java 状态机）。
 *
 * 负责统一两种信号入口（CarSignalManagerObserver 直接调用与 VehicleSignalHub/Logcat/VHAL 状态切换）的：
 * 1. 预创建左右窗口复用判定；
 * 2. 同向防重入 / 异向平滑切换；
 * 3. 360 与 AVM 抑制下「主屏暂停 + 双 HUD 保持联动」的通道分发决策；
 * 4. 相机资源占用的统一统计。
 */
public final class BlindSpotCoordinator {

    public enum WindowSlot {
        PRE_CREATED_LEFT,
        PRE_CREATED_RIGHT,
        STANDARD_MAIN
    }

    public static final class DispatchPlan {
        public final String direction;
        public final WindowSlot mainWindowSlot;
        public final boolean showMainWindow;
        public final boolean showSecondaryHud;
        public final boolean updateNetworkStreamHud;
        public final boolean switchFromOpposite;
        public final String oppositeDirection;
        public final boolean suppressedOnMainOnly;

        public DispatchPlan(
                String direction,
                WindowSlot mainWindowSlot,
                boolean showMainWindow,
                boolean showSecondaryHud,
                boolean updateNetworkStreamHud,
                boolean switchFromOpposite,
                String oppositeDirection,
                boolean suppressedOnMainOnly) {
            this.direction = direction;
            this.mainWindowSlot = mainWindowSlot;
            this.showMainWindow = showMainWindow;
            this.showSecondaryHud = showSecondaryHud;
            this.updateNetworkStreamHud = updateNetworkStreamHud;
            this.switchFromOpposite = switchFromOpposite;
            this.oppositeDirection = oppositeDirection;
            this.suppressedOnMainOnly = suppressedOnMainOnly;
        }
    }

    private final BlindSpotSuppressionController suppressionController = new BlindSpotSuppressionController();
    private String activeDirection;

    public BlindSpotSuppressionController getSuppressionController() {
        return suppressionController;
    }

    public synchronized String getActiveDirection() {
        return activeDirection;
    }

    public static WindowSlot resolveWindowSlot(
            String direction,
            boolean hasPreCreatedLeft,
            boolean hasPreCreatedRight) {
        if ("left".equals(direction) && hasPreCreatedLeft) {
            return WindowSlot.PRE_CREATED_LEFT;
        }
        if ("right".equals(direction) && hasPreCreatedRight) {
            return WindowSlot.PRE_CREATED_RIGHT;
        }
        return WindowSlot.STANDARD_MAIN;
    }

    /**
     * 计算当触发某一侧转向/补盲时各通道（主屏、EVCC 副屏 HUD、ESP32 推流 HUD）的执行计划。
     */
    public synchronized DispatchPlan planActivate(
            String direction,
            boolean secondaryDisplayEnabled,
            boolean hasPreCreatedLeft,
            boolean hasPreCreatedRight) {
        return planActivate(direction, true, secondaryDisplayEnabled, hasPreCreatedLeft, hasPreCreatedRight);
    }

    /**
     * 计算当触发某一侧转向/补盲时各通道（仅主屏 / 仅副屏 / 主副屏同时、ESP32 推流）的执行计划。
     */
    public synchronized DispatchPlan planActivate(
            String direction,
            boolean mainDisplayEnabled,
            boolean secondaryDisplayEnabled,
            boolean hasPreCreatedLeft,
            boolean hasPreCreatedRight) {
        if (!"left".equals(direction) && !"right".equals(direction)) {
            return new DispatchPlan(direction, WindowSlot.STANDARD_MAIN,
                    false, false, false, false, null, false);
        }

        String opposite = "left".equals(direction) ? "right" : "left";
        boolean switching = opposite.equals(activeDirection);
        activeDirection = direction;

        BlindSpotSuppressionController.Decision decision = suppressionController.evaluate();
        boolean suppressedMainOnly = mainDisplayEnabled && decision.suppressMainScreen;
        if (suppressedMainOnly) {
            suppressionController.recordSuppressedTurn(direction);
        } else if (!mainDisplayEnabled) {
            suppressionController.clearTurnIfMatching(null);
        }

        WindowSlot slot = resolveWindowSlot(direction, hasPreCreatedLeft, hasPreCreatedRight);
        return new DispatchPlan(
                direction,
                slot,
                mainDisplayEnabled && !decision.suppressMainScreen,
                secondaryDisplayEnabled && !decision.suppressSecondaryHud,
                !decision.suppressNetworkStreamHud,
                switching,
                switching ? opposite : null,
                suppressedMainOnly
        );
    }

    /**
     * 取消某侧转向灯或超时清除状态。
     */
    public synchronized void clearDirection(String direction) {
        if (direction == null || direction.equals(activeDirection)) {
            activeDirection = null;
        }
        suppressionController.clearTurnIfMatching(direction);
    }

    /**
     * 当前是否存在任何占用摄像头的界面窗口（补盲主窗 / 副屏 HUD / 主悬浮预览 / 独立预览悬浮窗）。
     */
    public static boolean hasAnyActiveCameraWindow(
            boolean blindSpotFloatingShowing,
            boolean secondaryHudShowing,
            boolean mainFloatingShowing,
            boolean standalonePreviewShowing) {
        return blindSpotFloatingShowing
                || secondaryHudShowing
                || mainFloatingShowing
                || standalonePreviewShowing;
    }

    /** 判断 AVM 避让是否处于“始终隐藏主屏（all）”模式。 */
    public static boolean isAvmAvoidanceAllMode(
            boolean avmAvoidanceEnabled,
            String avmTargetActivity) {
        return avmAvoidanceEnabled
                && avmTargetActivity != null
                && "all".equalsIgnoreCase(avmTargetActivity.trim());
    }

    /** 判断当前补盲配置下 AVM 避让是否真的会对任何主屏功能产生影响（纯副屏模式下无需空跑前台服务）。 */
    public static boolean hasActiveAvmAvoidanceFeature(
            boolean blindSpotGlobalEnabled,
            boolean avmAvoidanceEnabled,
            boolean mainFloatingEnabled,
            boolean turnSignalLinkageEnabled,
            boolean mainBlindSpotEnabled) {
        if (!blindSpotGlobalEnabled || !avmAvoidanceEnabled) {
            return false;
        }
        return mainFloatingEnabled || (turnSignalLinkageEnabled && mainBlindSpotEnabled);
    }

    /**
     * 判断后台是否确有需要每秒轮询 UsageStatsManager（AVM 前台检测）的活跃需求。
     * 日常既未开常驻主悬浮窗、又无进行中的转向灯信号/可见主屏窗时，停止每秒轮询以消除后台空转。
     */
    public static boolean shouldPollAvmUsageStats(
            boolean blindSpotGlobalEnabled,
            boolean avmAvoidanceEnabled,
            String avmTargetActivity,
            boolean mainFloatingEnabled,
            boolean turnSignalLinkageEnabled,
            boolean mainBlindSpotEnabled,
            boolean hasActiveTurnSignal,
            boolean hasVisibleMainWindow,
            boolean selfInForeground,
            boolean canShowMainOverlay) {
        if (!blindSpotGlobalEnabled || !avmAvoidanceEnabled) {
            return false;
        }
        if (avmTargetActivity == null || avmTargetActivity.trim().isEmpty()) {
            return false;
        }
        if ("all".equalsIgnoreCase(avmTargetActivity.trim())) {
            return false;
        }
        if (selfInForeground || !canShowMainOverlay) {
            return false;
        }
        if (mainFloatingEnabled) {
            return true;
        }
        if (turnSignalLinkageEnabled && mainBlindSpotEnabled && hasActiveTurnSignal) {
            return true;
        }
        return hasVisibleMainWindow;
    }

    /**
     * 判断是否需要预创建独立主屏转向补盲悬浮窗（左/右）。
     * 仅副屏模式、复用常驻主悬浮窗、AVM "all" 始终隐藏模式或无主屏浮窗权限时均跳过预创建。
     */
    public static boolean shouldPreCreateMainBlindSpotWindows(
            boolean blindSpotGlobalEnabled,
            boolean turnSignalLinkageEnabled,
            boolean mainBlindSpotEnabled,
            boolean turnSignalReuseMainFloating,
            boolean avmAvoidanceAllMode,
            boolean canShowMainOverlay) {
        return blindSpotGlobalEnabled
                && turnSignalLinkageEnabled
                && mainBlindSpotEnabled
                && !turnSignalReuseMainFloating
                && !avmAvoidanceAllMode
                && canShowMainOverlay;
    }

    public synchronized void reset() {
        activeDirection = null;
        suppressionController.reset();
    }
}
