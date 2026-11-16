package com.kooo.evcam.lifecycle;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.BlindSpotService;
import com.kooo.evcam.FloatingWindowService;
import com.kooo.evcam.KeepAliveManager;
import com.kooo.evcam.KeepAliveReceiver;
import com.kooo.evcam.RemoteServiceManager;

/**
 * 后台保活与服务按需加载统一入口。
 *
 * 为 T06（信号驱动退后台/自动控停）与 T10（P 档驻留按需启动飞书）预留稳定接口，
 * 避免调用方各自重复判断退出态或直接散落启动代码。
 */
public final class BackgroundCapabilityController {
    private static final String TAG = "BgCapabilityCtrl";

    /** 后台触发来源，便于后续规则引擎与远程懒加载区分准入策略。 */
    public enum TriggerReason {
        APP_FOREGROUND_ENTRY,
        BOOT_COMPLETED,
        SERVICE_STICKY_RECOVERY,
        SIGNAL_ON_DEMAND,
        REMOTE_LAZY_START
    }

    private BackgroundCapabilityController() {
    }

    /** 根据当前全局退出状态同步 WorkManager 保活与 TIME_TICK 监听。 */
    public static void syncBackgroundKeepAlive(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        if (AppLifecycleState.canRunBackgroundServices(appContext)) {
            KeepAliveManager.startKeepAliveWork(appContext);
            KeepAliveReceiver.registerTimeTick(appContext);
        } else {
            AppLog.d(TAG, "user_exited=true，关闭定时保活与 TIME_TICK");
            KeepAliveManager.stopKeepAliveWork(appContext);
            KeepAliveReceiver.unregisterTimeTick(appContext);
        }
    }

    /** 判断当前是否仍有需要在 UI 销毁后继续存活的后台能力。 */
    public static boolean hasActiveBackgroundCapabilities(Context context) {
        boolean remoteRunning = RemoteServiceManager.getInstance().hasAnyServiceRunning();
        boolean floatingRunning = FloatingWindowService.isRunning();
        boolean blindSpotRunning = BlindSpotService.hasActiveCameraWindows()
                || (BlindSpotService.isServiceRunning() && AppLifecycleState.isUiExited(context));
        com.kooo.evcam.AppConfig appConfig = new com.kooo.evcam.AppConfig(context);
        boolean vehicleAutomationActive = appConfig.isKeepCameraConnectedEnabled()
                || appConfig.isRdShiftAutoPopupEnabled()
                || appConfig.hasActiveVehicleTriggerRules();
        return remoteRunning || floatingRunning || blindSpotRunning || vehicleAutomationActive;
    }

    /**
     * 预留给后续 T10 P 档驻留/信号触发的飞书服务懒加载接口。
     * 仅在未触发 user_exited 时委托 RemoteServiceManager 按需拉起飞书连接。
     */
    public static boolean ensureFeishuLazyStarted(Context context, TriggerReason reason) {
        if (context == null || !AppLifecycleState.canRunBackgroundServices(context)) {
            AppLog.d(TAG, "跳过飞书懒加载（user_exited=true 或上下文无效）, reason=" + reason);
            return false;
        }
        return RemoteServiceManager.getInstance().ensureFeishuLazyStarted(
                context.getApplicationContext(),
                reason != null ? reason.name() : TriggerReason.REMOTE_LAZY_START.name()
        );
    }
}
