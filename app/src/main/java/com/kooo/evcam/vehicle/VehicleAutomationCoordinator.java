package com.kooo.evcam.vehicle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.BlindSpotService;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.lifecycle.AppLifecycleState;
import com.kooo.evcam.vehicle.rule.RuleAction;
import com.kooo.evcam.vehicle.rule.VehicleRuleEngine;
import com.kooo.evcam.vehicle.rule.VehicleTriggerRule;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * T06 车辆状态自动化总协调器（服务侧生命周期拥有者，避免 Activity 泄漏与业务堆积）：
 * <ul>
 *     <li>统一订阅 {@link VehicleSignalHub} 4 类核心快照；按需持有 {@link RecordingVehicleSource.Lease} 保活。</li>
 *     <li>驱动 {@link VehicleRuleEngine} 评估自定义/默认规则、5 秒防误退启动保护与开门确认弹窗分流。</li>
 *     <li>驱动 {@link GearShiftPopupCoordinator} 处理前进档（D 档）自动弹出记录仪并在 X 秒后自动退后台。</li>
 *     <li>驱动 {@link CameraRecoveryCoordinator} 处理 3.1 后台 5s×3 重连与有门槛前台拉起恢复。</li>
 * </ul>
 */
public final class VehicleAutomationCoordinator {
    private static final String TAG = "VehicleAutoCoord";
    public static final long STARTUP_EXIT_GUARD_MS = 5_000L;

    public interface UiHost {
        boolean isUiInForeground();

        boolean isRecordingActive();

        void startRecordingForAutomation(@NonNull String reason);

        void stopRecordingForAutomation(boolean releaseCameras, @NonNull String reason);

        void exitUiToBackgroundForAutomation(@NonNull String reason);

        void requestDoorExitConfirmationDialog(int delaySeconds,
                                               @NonNull String reason,
                                               @NonNull Runnable onConfirmExit);

        void moveRecordingToBackgroundForGearShift();
    }

    private static final VehicleAutomationCoordinator INSTANCE = new VehicleAutomationCoordinator();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final VehicleRuleEngine ruleEngine = new VehicleRuleEngine();
    private final VehicleGearStatePublisher.Scheduler mainScheduler = new VehicleGearStatePublisher.Scheduler() {
        @NonNull
        @Override
        public Object schedule(@NonNull Runnable task, long delayMs) {
            mainHandler.postDelayed(task, Math.max(0L, delayMs));
            return task;
        }

        @Override
        public void cancel(@Nullable Object token) {
            if (token instanceof Runnable) {
                mainHandler.removeCallbacks((Runnable) token);
            }
        }
    };

    private final GearShiftPopupCoordinator gearPopupCoordinator = new GearShiftPopupCoordinator(
            new GearShiftPopupCoordinator.Host() {
                @Override
                public boolean isUiInForeground() {
                    UiHost host = getUiHost();
                    return host != null && host.isUiInForeground();
                }

                @Override
                public void launchRecordingPageToForeground() {
                    Context ctx = appContext;
                    if (ctx == null || !AppLifecycleState.canRunBackgroundServices(ctx)) {
                        return;
                    }
                    if (!WakeUpHelper.hasOverlayPermission(ctx)) {
                        AppLog.w(TAG, "前进档（D 档）自动弹出跳过：缺少悬浮窗后台弹出权限");
                        return;
                    }
                    WakeUpHelper.launchForGearShiftPopup(ctx);
                }

                @Override
                public void moveRecordingPageToBackground() {
                    mainHandler.post(() -> {
                        UiHost host = getUiHost();
                        if (host != null && host.isUiInForeground()) {
                            host.moveRecordingToBackgroundForGearShift();
                        }
                    });
                }
            },
            GearShiftPopupCoordinator.DEFAULT_DEBOUNCE_MS,
            SystemClock::uptimeMillis,
            mainScheduler
    );

    private final ReversePreviewAutoCloseCoordinator reversePreviewCoordinator = new ReversePreviewAutoCloseCoordinator(
            new ReversePreviewAutoCloseCoordinator.Host() {
                @Override
                public boolean isUiInForeground() {
                    UiHost host = getUiHost();
                    return host != null && host.isUiInForeground();
                }

                @Override
                public void launchReversePreviewToForeground() {
                    Context ctx = appContext;
                    if (ctx == null || !AppLifecycleState.canRunBackgroundServices(ctx)) {
                        return;
                    }
                    if (!WakeUpHelper.hasOverlayPermission(ctx)) {
                        AppLog.w(TAG, "R 档自动打开预览跳过：缺少悬浮窗后台弹出权限");
                        return;
                    }
                    WakeUpHelper.launchForReversePreview(ctx);
                }

                @Override
                public void moveReversePreviewToBackground(@NonNull String reason) {
                    mainHandler.post(() -> {
                        UiHost host = getUiHost();
                        if (host != null && host.isUiInForeground()) {
                            host.exitUiToBackgroundForAutomation("reverse_preview_" + reason);
                        }
                    });
                }
            },
            SystemClock::uptimeMillis,
            mainScheduler
    );

    private final CameraRecoveryCoordinator cameraRecoveryCoordinator = new CameraRecoveryCoordinator(
            new CameraRecoveryCoordinator.Host() {
                @Override
                public boolean isBackgroundEligible() {
                    Context ctx = appContext;
                    if (ctx == null || !AppLifecycleState.canRunBackgroundServices(ctx)) {
                        return false;
                    }
                    AppConfig config = new AppConfig(ctx);
                    if (config.isKeepCameraConnectedEnabled() || BlindSpotService.hasActiveCameraWindows()) {
                        return true;
                    }
                    UiHost host = getUiHost();
                    if (host != null && host.isRecordingActive()) {
                        return true;
                    }
                    MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
                    return manager != null && manager.isRecording();
                }

                @Override
                public boolean isUiInForeground() {
                    UiHost host = getUiHost();
                    return host != null && host.isUiInForeground();
                }

                @Override
                public boolean isScreenInteractive() {
                    Context ctx = appContext;
                    if (ctx == null) return false;
                    PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                    return pm == null || pm.isInteractive();
                }

                @Override
                public boolean hasDisconnectedActiveCamera() {
                    MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
                    return manager != null && manager.hasDisconnectedActiveCamera();
                }

                @Override
                public int repairCamerasInBackground() {
                    MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
                    return manager != null ? manager.checkAndRepairCameras() : 0;
                }

                @Override
                public void launchForegroundRecovery() {
                    Context ctx = appContext;
                    if (ctx == null || !AppLifecycleState.canRunBackgroundServices(ctx)) {
                        return;
                    }
                    if (!WakeUpHelper.hasOverlayPermission(ctx)) {
                        AppLog.w(TAG, "摄像头掉线前台恢复跳过：缺少悬浮窗后台弹出权限");
                        return;
                    }
                    AppLog.w(TAG, "摄像头后台连续重连未果，执行有门槛前台拉起恢复");
                    WakeUpHelper.launchForCameraRecovery(ctx);
                }

                @Override
                public void returnToBackgroundAfterRecovery() {
                    mainHandler.post(() -> {
                        UiHost host = getUiHost();
                        if (host != null && host.isUiInForeground()) {
                            host.exitUiToBackgroundForAutomation("camera_recovery_settled");
                        }
                    });
                }

                @Override
                public void stopRecordingOnRecoveryExhausted() {
                    mainHandler.post(() -> {
                        UiHost host = getUiHost();
                        if (host != null) {
                            host.stopRecordingForAutomation(true, "camera_recovery_exhausted");
                        } else {
                            MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
                            if (manager != null) {
                                if (manager.isRecording()) {
                                    manager.stopRecording();
                                }
                                manager.closeAllCameras();
                            }
                        }
                    });
                }
            },
            SystemClock::uptimeMillis
    );

    private final VehicleSignalHub.Listener hubListener = snapshot ->
            mainHandler.post(() -> onSignalSnapshotOnMain(snapshot));

    private final Runnable ruleDelayTickRunnable = () -> {
        VehicleRuleEngine.TriggerEvent event = ruleEngine.tick(SystemClock.uptimeMillis());
        if (event != null) {
            handleRuleTrigger(event);
        }
        scheduleNextRuleDelayLocked();
    };

    private volatile Context appContext;
    private volatile WeakReference<UiHost> uiHostRef = new WeakReference<>(null);
    private volatile long uiForegroundSinceUptimeMs = 0L;
    private volatile boolean started = false;
    private volatile long lastSignalDiagLogUptimeMs = 0L;
    @NonNull
    private volatile VehicleSignalSnapshot.Gear lastLoggedGear = VehicleSignalSnapshot.Gear.UNKNOWN;
    @Nullable
    private RecordingVehicleSource.Lease signalLease;

    private VehicleAutomationCoordinator() {
    }

    @NonNull
    public static VehicleAutomationCoordinator getInstance() {
        return INSTANCE;
    }

    public synchronized void startOrReload(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        if (!AppLifecycleState.canRunBackgroundServices(this.appContext)) {
            stop();
            return;
        }
        AppConfig config = new AppConfig(this.appContext);
        config.syncVehicleSignalCategoryFromConfig();

        List<VehicleTriggerRule> rules = config.getVehicleTriggerRules();
        ruleEngine.setRules(rules);
        gearPopupCoordinator.setConfig(config.isRdShiftAutoPopupEnabled(), config.getRdShiftPopupSeconds());
        reversePreviewCoordinator.setConfig(
                config.isReversePreviewEnabled(),
                config.getReversePreviewDelayMs(),
                config.isPreviewSpeedCloseEnabled(),
                config.getPreviewSpeedThresholdKmh(),
                config.isParkPreviewCloseEnabled(),
                config.getParkPreviewCloseDelaySeconds()
        );
        cameraRecoveryCoordinator.setConfig(config.isKeepCameraConnectedEnabled(), config.isCameraRecoveryEnabled());

        boolean hasActiveRules = config.hasActiveVehicleTriggerRules();
        boolean rdPopup = config.isRdShiftAutoPopupEnabled();
        boolean reversePreview = config.isReversePreviewEnabled();
        String previewMode = config.getPreviewMode();
        boolean foregroundGearPreview = getUiHost() != null
                && AppConfig.PREVIEW_MODE_A.equals(previewMode);
        boolean needsVhalLease = shouldAcquireVhalLease(
                config.isCarSignalManagerTriggerMode(),
                hasActiveRules,
                rdPopup,
                reversePreview,
                foregroundGearPreview
        );
        if (needsVhalLease && signalLease == null) {
            signalLease = RecordingVehicleSource.acquire();
            AppLog.i(TAG, "启动 VHAL 信号租约 (blindSpot=" + config.isBlindSpotGlobalEnabled()
                    + ", triggerMode=" + config.getTurnSignalTriggerMode()
                    + ", reversePreview=" + reversePreview
                    + ", rdPopup=" + rdPopup
                    + ", activeRules=" + hasActiveRules
                    + ", previewMode=" + previewMode + ")");
        } else if (!needsVhalLease && signalLease != null) {
            signalLease.close();
            signalLease = null;
            AppLog.i(TAG, "释放 VHAL 信号租约 (triggerMode=" + config.getTurnSignalTriggerMode()
                    + ", reversePreview=" + reversePreview
                    + ", rdPopup=" + rdPopup
                    + ", activeRules=" + hasActiveRules
                    + ", previewMode=" + previewMode + ")");
        } else if (!needsVhalLease) {
            long now = SystemClock.uptimeMillis();
            if (now - lastSignalDiagLogUptimeMs >= 15_000L) {
                lastSignalDiagLogUptimeMs = now;
                AppLog.d(TAG, "未请求 VHAL 租约 (triggerMode=" + config.getTurnSignalTriggerMode()
                        + ", reversePreview=" + reversePreview
                        + ", rdPopup=" + rdPopup
                        + ", activeRules=" + hasActiveRules + ")");
            }
        }

        if (!started) {
            started = true;
            VehicleSignalHub.getInstance().addListener(hubListener);
            VehicleSignalSnapshot current = VehicleSignalHub.getInstance().getSnapshot();
            if (!VehicleSignalSnapshot.UNKNOWN.equals(current)) {
                mainHandler.post(() -> onSignalSnapshotOnMain(current));
            }
        }
    }

    /** 判断当前配置是否需要拉起 VHAL gRPC 信号采集（不因未开补盲或切 logcat 转向而阻断档位/倒车）。 */
    public static boolean shouldAcquireVhalLease(boolean isCarSignalManagerMode,
                                                 boolean hasActiveRules,
                                                 boolean rdShiftAutoPopup,
                                                 boolean reversePreview,
                                                 boolean foregroundGearPreview) {
        if (isCarSignalManagerMode) {
            return false;
        }
        return hasActiveRules || rdShiftAutoPopup || reversePreview || foregroundGearPreview;
    }

    public synchronized void stop() {
        started = false;
        lastLoggedGear = VehicleSignalSnapshot.Gear.UNKNOWN;
        mainHandler.removeCallbacks(ruleDelayTickRunnable);
        VehicleSignalHub.getInstance().removeListener(hubListener);
        if (signalLease != null) {
            signalLease.close();
            signalLease = null;
        }
        ruleEngine.reset();
        gearPopupCoordinator.reset();
        reversePreviewCoordinator.reset();
        cameraRecoveryCoordinator.reset();
    }

    public void attachUiHost(@NonNull UiHost host) {
        attachUiHost(null, host);
    }

    public void attachUiHost(@Nullable Context context, @NonNull UiHost host) {
        uiHostRef = new WeakReference<>(host);
        uiForegroundSinceUptimeMs = SystemClock.uptimeMillis();
        Context targetCtx = context != null ? context.getApplicationContext() : appContext;
        if (targetCtx != null) {
            startOrReload(targetCtx);
        }
    }

    public void notifyUiResumed(@NonNull UiHost host) {
        uiHostRef = new WeakReference<>(host);
        uiForegroundSinceUptimeMs = SystemClock.uptimeMillis();
    }

    public void notifyUiPaused(@NonNull UiHost host) {
        UiHost current = getUiHost();
        if (current == host) {
            gearPopupCoordinator.notifyUiMovedToBackground();
            reversePreviewCoordinator.notifyUiMovedToBackground();
        }
    }

    public void detachUiHost(@NonNull UiHost host) {
        UiHost current = getUiHost();
        if (current == host) {
            uiHostRef = new WeakReference<>(null);
            gearPopupCoordinator.notifyUiMovedToBackground();
            reversePreviewCoordinator.notifyUiMovedToBackground();
            if (appContext != null) {
                startOrReload(appContext);
            }
        }
    }

    /**
     * 自动弹出记录仪或 R 档自动预览期间用户发生主动交互（切 Tab / 编辑布局 / 开关设置），解除自动退后台接管。
     */
    public boolean notifyUserInteraction() {
        boolean gearCancelled = gearPopupCoordinator.notifyUserInteraction();
        boolean reverseCancelled = reversePreviewCoordinator.notifyUserManualInteraction();
        return gearCancelled || reverseCancelled;
    }

    public boolean isGearPopupActive() {
        return gearPopupCoordinator.isAutoPopupActive();
    }

    public void onCameraTick() {
        cameraRecoveryCoordinator.onCameraTick();
    }

    @NonNull
    public GearShiftPopupCoordinator getGearPopupCoordinator() {
        return gearPopupCoordinator;
    }

    @NonNull
    public CameraRecoveryCoordinator getCameraRecoveryCoordinator() {
        return cameraRecoveryCoordinator;
    }

    @Nullable
    private UiHost getUiHost() {
        return uiHostRef.get();
    }

    private void onSignalSnapshotOnMain(@NonNull VehicleSignalSnapshot snapshot) {
        if (!started || appContext == null || !AppLifecycleState.canRunBackgroundServices(appContext)) {
            return;
        }
        if (snapshot.gear != lastLoggedGear) {
            AppLog.i(TAG, "车辆挡位变化: " + lastLoggedGear + " -> " + snapshot.gear
                    + ", speed=" + snapshot.speedKmh + "km/h");
            lastLoggedGear = snapshot.gear;
        } else {
            long now = SystemClock.uptimeMillis();
            if (now - lastSignalDiagLogUptimeMs >= 15_000L) {
                lastSignalDiagLogUptimeMs = now;
                AppLog.d(TAG, "信号心跳: gear=" + snapshot.gear + ", speed=" + snapshot.speedKmh);
            }
        }
        gearPopupCoordinator.onSnapshot(snapshot);
        reversePreviewCoordinator.onSnapshot(snapshot);
        VehicleRuleEngine.TriggerEvent event = ruleEngine.evaluate(snapshot, SystemClock.uptimeMillis());
        if (event != null) {
            handleRuleTrigger(event);
        }
        scheduleNextRuleDelayLocked();
    }

    private void scheduleNextRuleDelayLocked() {
        mainHandler.removeCallbacks(ruleDelayTickRunnable);
        long nextDueMs = ruleEngine.getNextDelayDueMs(SystemClock.uptimeMillis());
        if (nextDueMs >= 0L) {
            mainHandler.postDelayed(ruleDelayTickRunnable, nextDueMs + 20L);
        }
    }

    private void handleRuleTrigger(@NonNull VehicleRuleEngine.TriggerEvent event) {
        Context ctx = appContext;
        if (ctx == null || !AppLifecycleState.canRunBackgroundServices(ctx)) {
            return;
        }
        String reason = "rule:" + event.rule.id + "(" + event.action.name() + ")";
        AppLog.i(TAG, "触发车辆状态规则: " + event.rule.getDisplayName() + " -> " + event.action.name());
        UiHost host = getUiHost();
        AppConfig config = new AppConfig(ctx);

        switch (event.action) {
            case START:
                if (host != null) {
                    host.startRecordingForAutomation(reason);
                } else if (WakeUpHelper.hasOverlayPermission(ctx)) {
                    WakeUpHelper.launchForStartRecording(ctx);
                } else {
                    AppLog.w(TAG, "规则触发开始录制跳过：UI 未绑定且无后台弹窗权限");
                }
                break;

            case STOP:
                if (host != null) {
                    host.stopRecordingForAutomation(false, reason);
                } else {
                    MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
                    if (manager != null && manager.isRecording()) {
                        manager.stopRecording();
                    }
                }
                break;

            case STOP_RELEASE:
                if (host != null) {
                    host.stopRecordingForAutomation(true, reason);
                } else {
                    stopAndReleaseWithoutUi();
                }
                break;

            case EXIT_APP:
                if (host == null || !host.isUiInForeground()) {
                    // 已在后台或无界面：直接静默停录释放，不弹确认框且保留信号保活
                    gearPopupCoordinator.notifyUiMovedToBackground();
                    if (host != null) {
                        host.stopRecordingForAutomation(true, reason);
                        host.exitUiToBackgroundForAutomation(reason);
                    } else {
                        stopAndReleaseWithoutUi();
                        AppLifecycleState.markUiExited(ctx);
                    }
                    return;
                }
                // 前台防误退：UI 进入前台不足 5 秒时忽略，防止带门上车打开即被退回桌面
                if (SystemClock.uptimeMillis() - uiForegroundSinceUptimeMs < STARTUP_EXIT_GUARD_MS) {
                    AppLog.d(TAG, "前台启动保护期（<5s）内跳过 EXIT_APP: " + reason);
                    return;
                }
                Runnable performExit = () -> {
                    gearPopupCoordinator.notifyUiMovedToBackground();
                    UiHost currentHost = getUiHost();
                    if (currentHost != null) {
                        currentHost.stopRecordingForAutomation(true, reason);
                        currentHost.exitUiToBackgroundForAutomation(reason);
                    }
                };
                if (config.isConfirmDoorExitEnabled()) {
                    host.requestDoorExitConfirmationDialog(config.getDoorExitDelaySeconds(), reason, performExit);
                } else {
                    performExit.run();
                }
                break;
        }
    }

    private static void stopAndReleaseWithoutUi() {
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager != null) {
            if (manager.isRecording()) {
                manager.stopRecording(true, manager::closeAllCameras);
            } else {
                manager.closeAllCameras();
            }
        }
    }
}
