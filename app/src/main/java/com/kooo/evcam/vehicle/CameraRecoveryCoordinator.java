package com.kooo.evcam.vehicle;

import androidx.annotation.NonNull;

/**
 * 3.1 后台保摄像头与有门槛前台拉起恢复状态机（纯 Java，对标 SC Lho2 / Lk13）。
 * <ul>
 *     <li>后台阶段先每 5 秒重连最多 3 次。</li>
 *     <li>若仍断开且用户开启了“前台拉起恢复”，需满足亮屏 + 60 秒冷却 + 最多 3 次。</li>
 *     <li>拉起后若摄像头恢复，自动退回后台；若 3 次均耗尽，则收敛停录防止无限弹窗。</li>
 * </ul>
 */
public final class CameraRecoveryCoordinator {
    public static final long BG_RETRY_INTERVAL_MS = 5_000L;
    public static final int MAX_BG_RETRIES = 3;
    public static final long FG_COOLDOWN_MS = 60_000L;
    public static final int MAX_FG_ATTEMPTS = 3;
    public static final long FG_SETTLE_RETURN_MS = 1_500L;

    public interface Host {
        /** 是否处于允许后台保活恢复的状态（录制中或已开启后台保摄像头，且未 user_exited）。 */
        boolean isBackgroundEligible();

        boolean isUiInForeground();

        boolean isScreenInteractive();

        boolean hasDisconnectedActiveCamera();

        int repairCamerasInBackground();

        void launchForegroundRecovery();

        void returnToBackgroundAfterRecovery();

        void stopRecordingOnRecoveryExhausted();
    }

    private final Host host;
    private final VehicleGearStatePublisher.Clock clock;

    private boolean keepCameraConnected = false;
    private boolean recoveryPageEnabled = false;

    private int bgRetryCount = 0;
    private long lastBgRetryMs = 0L;
    private int fgAttemptCount = 0;
    private long lastFgLaunchMs = 0L;
    private boolean uiLaunchedForRecovery = false;
    private boolean awaitingFgVerification = false;

    public CameraRecoveryCoordinator(@NonNull Host host) {
        this(host, System::currentTimeMillis);
    }

    public CameraRecoveryCoordinator(@NonNull Host host, @NonNull VehicleGearStatePublisher.Clock clock) {
        this.host = host;
        this.clock = clock;
    }

    public synchronized void setConfig(boolean keepCameraConnected, boolean recoveryPageEnabled) {
        this.keepCameraConnected = keepCameraConnected;
        this.recoveryPageEnabled = recoveryPageEnabled;
        if (!recoveryPageEnabled) {
            awaitingFgVerification = false;
            fgAttemptCount = 0;
        }
    }

    public synchronized boolean isKeepCameraConnected() {
        return keepCameraConnected;
    }

    public synchronized boolean isRecoveryPageEnabled() {
        return recoveryPageEnabled;
    }

    public synchronized int getBgRetryCount() {
        return bgRetryCount;
    }

    public synchronized int getFgAttemptCount() {
        return fgAttemptCount;
    }

    public synchronized boolean isUiLaunchedForRecovery() {
        return uiLaunchedForRecovery;
    }

    public void reset() {
        synchronized (this) {
            bgRetryCount = 0;
            lastBgRetryMs = 0L;
            fgAttemptCount = 0;
            lastFgLaunchMs = 0L;
            uiLaunchedForRecovery = false;
            awaitingFgVerification = false;
        }
    }

    /**
     * 由后台保活循环或摄像头错误回调周期性推进恢复状态机。
     */
    public void onCameraTick() {
        boolean callRepairBg = false;
        boolean callLaunchFg = false;
        boolean callReturnBg = false;
        boolean callStopExhausted = false;

        synchronized (this) {
            if (!host.isBackgroundEligible()) {
                bgRetryCount = 0;
                fgAttemptCount = 0;
                uiLaunchedForRecovery = false;
                awaitingFgVerification = false;
                return;
            }

            long nowMs = clock.nowMs();
            boolean hasDisconnected = host.hasDisconnectedActiveCamera();
            if (!hasDisconnected) {
                if (uiLaunchedForRecovery) {
                    if (nowMs - lastFgLaunchMs >= FG_SETTLE_RETURN_MS) {
                        uiLaunchedForRecovery = false;
                        awaitingFgVerification = false;
                        if (host.isUiInForeground()) {
                            callReturnBg = true;
                        }
                        bgRetryCount = 0;
                        fgAttemptCount = 0;
                    }
                } else {
                    bgRetryCount = 0;
                    fgAttemptCount = 0;
                    awaitingFgVerification = false;
                }
            } else {
                if (host.isUiInForeground()) {
                    if (awaitingFgVerification && nowMs - lastFgLaunchMs >= FG_SETTLE_RETURN_MS) {
                        if (fgAttemptCount >= MAX_FG_ATTEMPTS) {
                            awaitingFgVerification = false;
                            callStopExhausted = true;
                            if (uiLaunchedForRecovery) {
                                uiLaunchedForRecovery = false;
                                callReturnBg = true;
                            }
                            bgRetryCount = 0;
                            fgAttemptCount = 0;
                        } else if (nowMs - lastFgLaunchMs >= FG_SETTLE_RETURN_MS * 2) {
                            awaitingFgVerification = false;
                            if (uiLaunchedForRecovery) {
                                uiLaunchedForRecovery = false;
                                callReturnBg = true;
                            }
                        } else {
                            callRepairBg = true;
                        }
                    }
                } else {
                    if (bgRetryCount < MAX_BG_RETRIES) {
                        if (lastBgRetryMs == 0L || nowMs - lastBgRetryMs >= BG_RETRY_INTERVAL_MS) {
                            bgRetryCount++;
                            lastBgRetryMs = nowMs;
                            callRepairBg = true;
                        }
                    } else if (!recoveryPageEnabled) {
                        if (lastBgRetryMs == 0L || nowMs - lastBgRetryMs >= BG_RETRY_INTERVAL_MS) {
                            lastBgRetryMs = nowMs;
                            callRepairBg = true;
                        }
                    } else {
                        boolean cooldownOk = lastFgLaunchMs == 0L || (nowMs - lastFgLaunchMs >= FG_COOLDOWN_MS);
                        if (fgAttemptCount < MAX_FG_ATTEMPTS) {
                            if (cooldownOk && host.isScreenInteractive()) {
                                fgAttemptCount++;
                                lastFgLaunchMs = nowMs;
                                bgRetryCount = 0;
                                uiLaunchedForRecovery = true;
                                awaitingFgVerification = true;
                                callLaunchFg = true;
                            }
                        } else {
                            callStopExhausted = true;
                            bgRetryCount = 0;
                            fgAttemptCount = 0;
                            uiLaunchedForRecovery = false;
                            awaitingFgVerification = false;
                            lastFgLaunchMs = nowMs;
                        }
                    }
                }
            }
        }

        if (callRepairBg) {
            host.repairCamerasInBackground();
        }
        if (callLaunchFg) {
            host.launchForegroundRecovery();
        }
        if (callStopExhausted) {
            host.stopRecordingOnRecoveryExhausted();
        }
        if (callReturnBg) {
            host.returnToBackgroundAfterRecovery();
        }
    }
}
