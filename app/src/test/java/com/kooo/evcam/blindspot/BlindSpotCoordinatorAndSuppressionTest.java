package com.kooo.evcam.blindspot;

import org.junit.Test;

import static org.junit.Assert.*;

public class BlindSpotCoordinatorAndSuppressionTest {

    @Test
    public void planActivateReusesPreCreatedWindowsAndTracksOppositeSwitches() {
        BlindSpotCoordinator coordinator = new BlindSpotCoordinator();

        BlindSpotCoordinator.DispatchPlan leftPlan =
                coordinator.planActivate("left", true, true, true);
        assertEquals("left", leftPlan.direction);
        assertEquals(BlindSpotCoordinator.WindowSlot.PRE_CREATED_LEFT, leftPlan.mainWindowSlot);
        assertTrue(leftPlan.showMainWindow);
        assertTrue(leftPlan.showSecondaryHud);
        assertTrue(leftPlan.updateNetworkStreamHud);
        assertFalse(leftPlan.switchFromOpposite);
        assertNull(leftPlan.oppositeDirection);
        assertFalse(leftPlan.suppressedOnMainOnly);

        // Switch immediately to opposite side without pre-created right window.
        BlindSpotCoordinator.DispatchPlan rightPlan =
                coordinator.planActivate("right", false, true, false);
        assertEquals("right", rightPlan.direction);
        assertEquals(BlindSpotCoordinator.WindowSlot.STANDARD_MAIN, rightPlan.mainWindowSlot);
        assertTrue(rightPlan.showMainWindow);
        assertFalse(rightPlan.showSecondaryHud);
        assertTrue(rightPlan.updateNetworkStreamHud);
        assertTrue(rightPlan.switchFromOpposite);
        assertEquals("left", rightPlan.oppositeDirection);
    }

    @Test
    public void internal360AndAvmPauseOnlyMainScreenAndResumeWhenCleared() {
        BlindSpotCoordinator coordinator = new BlindSpotCoordinator();
        BlindSpotSuppressionController controller = coordinator.getSuppressionController();

        controller.setSuppressOnInternal360(true);
        controller.setInternal360Active(true);

        BlindSpotCoordinator.DispatchPlan suppressedPlan =
                coordinator.planActivate("left", true, true, false);
        assertFalse(suppressedPlan.showMainWindow);
        assertTrue(suppressedPlan.suppressedOnMainOnly);
        // EVCC secondary display HUD & ESP32 Wi-Fi HUD MUST stay linked.
        assertTrue(suppressedPlan.showSecondaryHud);
        assertTrue(suppressedPlan.updateNetworkStreamHud);

        // Exiting 360 before timeout resumes main-screen blind spot for the active direction.
        controller.setInternal360Active(false);
        assertEquals("left", controller.consumeResumeDirectionIfClear());
        // Consumed once.
        assertNull(controller.consumeResumeDirectionIfClear());
    }

    @Test
    public void clearingTurnDirectionBeforeSuppressionEndsPreventsStaleResume() {
        BlindSpotCoordinator coordinator = new BlindSpotCoordinator();
        BlindSpotSuppressionController controller = coordinator.getSuppressionController();

        controller.setAvmAvoidanceActive(true);
        coordinator.planActivate("right", true, false, true);
        coordinator.clearDirection("right");

        controller.setAvmAvoidanceActive(false);
        assertNull(controller.consumeResumeDirectionIfClear());
    }

    @Test
    public void internal360DoesNotSuppressWhenSettingDisabled() {
        BlindSpotCoordinator coordinator = new BlindSpotCoordinator();
        BlindSpotSuppressionController controller = coordinator.getSuppressionController();

        controller.setSuppressOnInternal360(false);
        controller.setInternal360Active(true);

        BlindSpotCoordinator.DispatchPlan plan =
                coordinator.planActivate("right", true, false, true);
        assertTrue(plan.showMainWindow);
        assertFalse(plan.suppressedOnMainOnly);
        assertEquals(BlindSpotCoordinator.WindowSlot.PRE_CREATED_RIGHT, plan.mainWindowSlot);
    }

    @Test
    public void hasAnyActiveCameraWindowTracksAllFourWindowSources() {
        assertFalse(BlindSpotCoordinator.hasAnyActiveCameraWindow(false, false, false, false));
        assertTrue(BlindSpotCoordinator.hasAnyActiveCameraWindow(true, false, false, false));
        assertTrue(BlindSpotCoordinator.hasAnyActiveCameraWindow(false, true, false, false));
        assertTrue(BlindSpotCoordinator.hasAnyActiveCameraWindow(false, false, true, false));
        assertTrue(BlindSpotCoordinator.hasAnyActiveCameraWindow(false, false, false, true));
    }

    @Test
    public void outputModesSupportMainOnlySecondaryOnlyAndBothWithoutLeakingMainPopup() {
        BlindSpotCoordinator coordinator = new BlindSpotCoordinator();
        BlindSpotSuppressionController controller = coordinator.getSuppressionController();

        // 1. 仅副屏：即使未在 360 下，主屏也绝不弹出，仅副屏与 ESP32 联动
        BlindSpotCoordinator.DispatchPlan secOnlyPlan =
                coordinator.planActivate("left", false, true, true, true);
        assertFalse(secOnlyPlan.showMainWindow);
        assertFalse(secOnlyPlan.suppressedOnMainOnly);
        assertTrue(secOnlyPlan.showSecondaryHud);
        assertTrue(secOnlyPlan.updateNetworkStreamHud);

        // 仅副屏模式下即使经过 360 抑制再退出，也不能恢复弹出主屏窗口
        controller.setSuppressOnInternal360(true);
        controller.setInternal360Active(true);
        BlindSpotCoordinator.DispatchPlan secOnlyDuring360 =
                coordinator.planActivate("right", false, true, true, true);
        assertFalse(secOnlyDuring360.showMainWindow);
        assertFalse(secOnlyDuring360.suppressedOnMainOnly);
        assertTrue(secOnlyDuring360.showSecondaryHud);
        controller.setInternal360Active(false);
        assertNull(controller.consumeResumeDirectionIfClear());

        // 2. 仅主屏：只弹出主屏，不开启副屏
        BlindSpotCoordinator.DispatchPlan mainOnlyPlan =
                coordinator.planActivate("left", true, false, true, false);
        assertTrue(mainOnlyPlan.showMainWindow);
        assertFalse(mainOnlyPlan.showSecondaryHud);
        assertTrue(mainOnlyPlan.updateNetworkStreamHud);

        // 3. 主副屏同时：两边同时显示，且 360 抑制时仅停主屏保副屏
        BlindSpotCoordinator.DispatchPlan bothPlan =
                coordinator.planActivate("right", true, true, false, true);
        assertTrue(bothPlan.showMainWindow);
        assertTrue(bothPlan.showSecondaryHud);
        controller.setInternal360Active(true);
        BlindSpotCoordinator.DispatchPlan bothDuring360 =
                coordinator.planActivate("left", true, true, true, true);
        assertFalse(bothDuring360.showMainWindow);
        assertTrue(bothDuring360.suppressedOnMainOnly);
        assertTrue(bothDuring360.showSecondaryHud);
    }

    @Test
    public void backgroundAvmPollingRunsOnlyWithActiveMainDemandInBackground() {
        String avmClass = "com.geely.avm_app.AvmRenderActivity";

        // 1. 仅开启转向灯补盲，但当前未打转向且未开常驻主悬浮窗：日常直行后台绝不空转轮询
        assertFalse(BlindSpotCoordinator.shouldPollAvmUsageStats(
                true, true, avmClass, false, true, true, false, false, false, true));

        // 2. 转向灯激活且启用主屏补盲，在后台非 all 模式：立即需要轮询
        assertTrue(BlindSpotCoordinator.shouldPollAvmUsageStats(
                true, true, avmClass, false, true, true, true, false, false, true));

        // 3. 仅有可见主屏窗（如临时窗口尚未关闭）：需要轮询
        assertTrue(BlindSpotCoordinator.shouldPollAvmUsageStats(
                true, true, avmClass, false, true, true, false, true, false, true));

        // 4. 开启主屏常驻悬浮窗且在后台：需要轮询
        assertTrue(BlindSpotCoordinator.shouldPollAvmUsageStats(
                true, true, avmClass, true, false, false, false, false, false, true));

        // 5. EVCam 自身在前台时暂停 UsageStatsManager 轮询
        assertFalse(BlindSpotCoordinator.shouldPollAvmUsageStats(
                true, true, avmClass, true, true, true, true, true, true, true));

        // 6. 仅副屏模式（无主屏悬浮窗且无主屏补盲）即使转向灯激活也不轮询
        assertFalse(BlindSpotCoordinator.shouldPollAvmUsageStats(
                true, true, avmClass, false, true, false, true, false, false, true));
        assertFalse(BlindSpotCoordinator.hasActiveAvmAvoidanceFeature(
                true, true, false, true, false));

        // 7. "all" 模式始终抑制且永不轮询
        assertTrue(BlindSpotCoordinator.isAvmAvoidanceAllMode(true, "all"));
        assertTrue(BlindSpotCoordinator.isAvmAvoidanceAllMode(true, " ALL "));
        assertFalse(BlindSpotCoordinator.isAvmAvoidanceAllMode(false, "all"));
        assertFalse(BlindSpotCoordinator.shouldPollAvmUsageStats(
                true, true, "all", true, true, true, true, true, false, true));

        // 8. 全局关闭、AVM 关闭、无权限或目标为空：全部停止轮询
        assertFalse(BlindSpotCoordinator.shouldPollAvmUsageStats(
                false, true, avmClass, true, true, true, true, true, false, true));
        assertFalse(BlindSpotCoordinator.shouldPollAvmUsageStats(
                true, false, avmClass, true, true, true, true, true, false, true));
        assertFalse(BlindSpotCoordinator.shouldPollAvmUsageStats(
                true, true, "  ", true, true, true, true, true, false, true));
        assertFalse(BlindSpotCoordinator.shouldPollAvmUsageStats(
                true, true, avmClass, true, true, true, true, true, false, false));
    }

    @Test
    public void preCreateMainWindowsRunsOnlyWhenIndependentMainBlindSpotIsEligible() {
        assertTrue(BlindSpotCoordinator.shouldPreCreateMainBlindSpotWindows(
                true, true, true, false, false, true));

        // 任意一项不满足（全局关 / 未开转向联动 / 仅副屏 / 复用主悬浮窗 / AVM all / 无权限）均不预创建
        assertFalse(BlindSpotCoordinator.shouldPreCreateMainBlindSpotWindows(
                false, true, true, false, false, true));
        assertFalse(BlindSpotCoordinator.shouldPreCreateMainBlindSpotWindows(
                true, false, true, false, false, true));
        assertFalse(BlindSpotCoordinator.shouldPreCreateMainBlindSpotWindows(
                true, true, false, false, false, true));
        assertFalse(BlindSpotCoordinator.shouldPreCreateMainBlindSpotWindows(
                true, true, true, true, false, true));
        assertFalse(BlindSpotCoordinator.shouldPreCreateMainBlindSpotWindows(
                true, true, true, false, true, true));
        assertFalse(BlindSpotCoordinator.shouldPreCreateMainBlindSpotWindows(
                true, true, true, false, false, false));
    }
}
