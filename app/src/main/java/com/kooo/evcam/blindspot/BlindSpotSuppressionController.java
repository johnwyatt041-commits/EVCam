package com.kooo.evcam.blindspot;

/**
 * 补盲抑制决策控制器（纯 Java 状态机）。
 *
 * 规则：
 * 1. 当 EVCam 内部处于 360 视图（且设置开启了 "显示 360 时暂停补盲"）或外部全景 AVM 避让生效时，
 *    仅抑制主屏补盲弹窗，同时记忆当前仍有效的转向灯方向；
 * 2. 抑制期间 EVCC 副屏 HUD 与 ESP32 网络推流补盲继续正常联动，确保辅助视角不中断；
 * 3. 当抑制条件解除且之前仍存在未超时的转向方向时，通知主屏恢复对应侧补盲弹窗。
 */
public final class BlindSpotSuppressionController {

    public static final class Decision {
        public final boolean suppressMainScreen;
        public final boolean suppressSecondaryHud;
        public final boolean suppressNetworkStreamHud;
        public final boolean internal360Active;
        public final boolean avmAvoidanceActive;

        public Decision(
                boolean suppressMainScreen,
                boolean suppressSecondaryHud,
                boolean suppressNetworkStreamHud,
                boolean internal360Active,
                boolean avmAvoidanceActive) {
            this.suppressMainScreen = suppressMainScreen;
            this.suppressSecondaryHud = suppressSecondaryHud;
            this.suppressNetworkStreamHud = suppressNetworkStreamHud;
            this.internal360Active = internal360Active;
            this.avmAvoidanceActive = avmAvoidanceActive;
        }
    }

    private boolean internal360Active;
    private boolean suppressOnInternal360 = true;
    private boolean avmAvoidanceActive;
    private String suppressedTurnDirection;

    public synchronized void setSuppressOnInternal360(boolean enabled) {
        this.suppressOnInternal360 = enabled;
        if (!isMainScreenSuppressed()) {
            // 解除标记由 evaluate/consumePendingResumeDirection 消费，不在此直接清空，除非开关关闭后外部查询
        }
    }

    public synchronized boolean isSuppressOnInternal360() {
        return suppressOnInternal360;
    }

    /** 返回状态是否发生变化。 */
    public synchronized boolean setInternal360Active(boolean active) {
        if (this.internal360Active == active) {
            return false;
        }
        this.internal360Active = active;
        return true;
    }

    public synchronized boolean isInternal360Active() {
        return internal360Active;
    }

    /** 返回状态是否发生变化。 */
    public synchronized boolean setAvmAvoidanceActive(boolean active) {
        if (this.avmAvoidanceActive == active) {
            return false;
        }
        this.avmAvoidanceActive = active;
        return true;
    }

    public synchronized boolean isMainScreenSuppressed() {
        return (internal360Active && suppressOnInternal360) || avmAvoidanceActive;
    }

    public synchronized Decision evaluate() {
        boolean suppressMain = isMainScreenSuppressed();
        return new Decision(suppressMain, false, false, internal360Active, avmAvoidanceActive);
    }

    public synchronized void recordSuppressedTurn(String direction) {
        if ("left".equals(direction) || "right".equals(direction)) {
            this.suppressedTurnDirection = direction;
        }
    }

    public synchronized void clearTurnIfMatching(String direction) {
        if (direction == null || direction.equals(suppressedTurnDirection)) {
            suppressedTurnDirection = null;
        }
    }

    public synchronized String consumeResumeDirectionIfClear() {
        if (isMainScreenSuppressed()) {
            return null;
        }
        String dir = suppressedTurnDirection;
        suppressedTurnDirection = null;
        return dir;
    }

    public synchronized String peekSuppressedDirection() {
        return suppressedTurnDirection;
    }

    public synchronized void reset() {
        internal360Active = false;
        avmAvoidanceActive = false;
        suppressedTurnDirection = null;
    }
}
