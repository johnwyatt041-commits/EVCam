package com.kooo.evcam.vehicle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 覆盖 T06 3.1 后台保摄像头 5s×3 重连与有门槛（亮屏 + 60s 冷却 + 上限 3 次）前台拉起恢复。
 */
public class CameraRecoveryCoordinatorTest {

    private static final class FakeHost implements CameraRecoveryCoordinator.Host {
        long nowMs = 1_000L;
        boolean backgroundEligible = true;
        boolean inForeground = false;
        boolean screenInteractive = true;
        boolean hasDisconnected = true;

        int bgRepairCalls = 0;
        int fgLaunchCalls = 0;
        int returnBgCalls = 0;
        int stopExhaustedCalls = 0;

        @Override
        public boolean isBackgroundEligible() {
            return backgroundEligible;
        }

        @Override
        public boolean isUiInForeground() {
            return inForeground;
        }

        @Override
        public boolean isScreenInteractive() {
            return screenInteractive;
        }

        @Override
        public boolean hasDisconnectedActiveCamera() {
            return hasDisconnected;
        }

        @Override
        public int repairCamerasInBackground() {
            bgRepairCalls++;
            return 1;
        }

        @Override
        public void launchForegroundRecovery() {
            fgLaunchCalls++;
            inForeground = true;
        }

        @Override
        public void returnToBackgroundAfterRecovery() {
            returnBgCalls++;
            inForeground = false;
        }

        @Override
        public void stopRecordingOnRecoveryExhausted() {
            stopExhaustedCalls++;
        }
    }

    @Test
    public void runsThreeBgRetriesThenGatesForegroundRecoveryAndReturnsWhenHealthy() {
        FakeHost host = new FakeHost();
        CameraRecoveryCoordinator coordinator = new CameraRecoveryCoordinator(host, () -> host.nowMs);
        coordinator.setConfig(true, true);

        // 后台 5s × 3 重连
        coordinator.onCameraTick(); // attempt 1
        host.nowMs += 5_000L;
        coordinator.onCameraTick(); // attempt 2
        host.nowMs += 5_000L;
        coordinator.onCameraTick(); // attempt 3
        assertEquals(3, host.bgRepairCalls);
        assertEquals(0, host.fgLaunchCalls);

        // 息屏状态不得拉起前台
        host.screenInteractive = false;
        host.nowMs += 5_000L;
        coordinator.onCameraTick();
        assertEquals(0, host.fgLaunchCalls);

        // 亮屏后第 4 拍允许首次拉起前台恢复
        host.screenInteractive = true;
        coordinator.onCameraTick();
        assertEquals(1, host.fgLaunchCalls);

        // 前台恢复成功 -> 1.5s 后自动退回后台并清零计数
        host.hasDisconnected = false;
        host.nowMs += 1_500L;
        coordinator.onCameraTick();
        assertEquals(1, host.returnBgCalls);
        assertEquals(0, coordinator.getFgAttemptCount());
    }

    @Test
    public void enforcesCooldownAndStopsAfterThreeExhaustedForegroundAttempts() {
        FakeHost host = new FakeHost();
        CameraRecoveryCoordinator coordinator = new CameraRecoveryCoordinator(host, () -> host.nowMs);
        coordinator.setConfig(true, true);

        for (int attempt = 1; attempt <= 3; attempt++) {
            for (int i = 0; i < 3; i++) {
                host.nowMs += 5_000L;
                coordinator.onCameraTick();
            }
            host.nowMs += attempt == 1 ? 5_000L : 60_000L;
            coordinator.onCameraTick();
            assertEquals(attempt, host.fgLaunchCalls);

            // 模拟前台恢复未能连上，经过 3s 未恢复后回到后台或第 3 次直接停录收敛
            host.nowMs += 3_000L;
            coordinator.onCameraTick();
        }
        assertEquals(1, host.stopExhaustedCalls);
    }

    @Test
    public void disabledForegroundRecoveryNeverLaunchesForeground() {
        FakeHost host = new FakeHost();
        CameraRecoveryCoordinator coordinator = new CameraRecoveryCoordinator(host, () -> host.nowMs);
        coordinator.setConfig(true, false);

        for (int i = 0; i < 6; i++) {
            host.nowMs += 5_000L;
            coordinator.onCameraTick();
        }
        assertEquals(6, host.bgRepairCalls);
        assertEquals(0, host.fgLaunchCalls);
    }
}
