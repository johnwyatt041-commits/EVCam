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
        if (!"left".equals(direction) && !"right".equals(direction)) {
            return new DispatchPlan(direction, WindowSlot.STANDARD_MAIN,
                    false, false, false, false, null, false);
        }

        String opposite = "left".equals(direction) ? "right" : "left";
        boolean switching = opposite.equals(activeDirection);
        activeDirection = direction;

        BlindSpotSuppressionController.Decision decision = suppressionController.evaluate();
        if (decision.suppressMainScreen) {
            suppressionController.recordSuppressedTurn(direction);
        }

        WindowSlot slot = resolveWindowSlot(direction, hasPreCreatedLeft, hasPreCreatedRight);
        return new DispatchPlan(
                direction,
                slot,
                !decision.suppressMainScreen,
                secondaryDisplayEnabled && !decision.suppressSecondaryHud,
                !decision.suppressNetworkStreamHud,
                switching,
                switching ? opposite : null,
                decision.suppressMainScreen
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

    public synchronized void reset() {
        activeDirection = null;
        suppressionController.reset();
    }
}
