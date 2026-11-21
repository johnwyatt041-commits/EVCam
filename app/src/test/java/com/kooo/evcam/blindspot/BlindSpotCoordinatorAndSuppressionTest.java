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
}
