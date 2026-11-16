package com.kooo.evcam.lifecycle;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.BlindSpotService;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.FloatingWindowService;
import com.kooo.evcam.KeepAliveManager;
import com.kooo.evcam.KeepAliveReceiver;
import com.kooo.evcam.RemoteServiceManager;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.service.RecordingFloatingService;
import com.kooo.evcam.vehicle.VehicleSignalHub;

import java.util.List;

/**
 * 全局退出与退后台协调器。
 *
 * 将完全退出（user_exited）与仅退出界面（ui_exited）从 MainActivity 抽离，
 * 保证本地 UI 点击与后台远程指令执行一致的停服与防复活顺序。
 */
public final class AppExitCoordinator {
    private static final String TAG = "AppExitCoordinator";
    private static final long REMOTE_EXIT_FLUSH_DELAY_MS = 600L;

    /** 退出过程中与 UI/相机/进程相关的可注入回调，便于单元测试与解耦。 */
    public interface ExitHooks {
        void stopRecordingIfNeeded();
        void releaseCameras();
        void finishUiTasks();
        void terminateProcess();
    }

    /** 内部依赖抽象，用于无 Android 环境的纯逻辑校验测试。 */
    interface PlatformActions {
        void log(String message);
        void markUserExited();
        void stopKeepAlive();
        void stopServices();
        void releaseWakeLocks();
        void resetVehicleSignals();
        void saveLogs();
        void finishAppTasks();
        void stopRemoteServices();
        void scheduleDelayed(Runnable runnable, long delayMs);
    }

    private AppExitCoordinator() {
    }

    /** 仅退回后台 / 隐藏 UI：写入 ui_exited，保持允许的后台服务继续运行。 */
    public static void moveUiToBackground(Activity activity) {
        if (activity == null) {
            return;
        }
        AppLifecycleState.markUiExited(activity);
        activity.moveTaskToBack(true);
    }

    /** 从 MainActivity 触发的本地彻底退出（同步关闭所有服务并结束进程）。 */
    public static void exitFromActivity(Activity activity, ExitHooks uiHooks) {
        if (activity == null) {
            return;
        }
        ExitHooks safeHooks = wrapActivityHooks(activity, uiHooks);
        executeExit(createAndroidActions(activity), false, safeHooks);
    }

    /** 从后台远程指令触发的彻底退出（先返回回执消息，短暂延迟后关闭飞书通道并退出进程）。 */
    public static void exitFromRemoteService(Context context, ExitHooks optionalUiHooks) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        ExitHooks safeHooks = (context instanceof Activity)
                ? wrapActivityHooks((Activity) context, optionalUiHooks)
                : (optionalUiHooks != null ? optionalUiHooks : defaultProcessExitHooks());
        executeExit(createAndroidActions(appContext), true, safeHooks);
    }

    static void executeExit(PlatformActions actions, boolean deferRemoteShutdown, ExitHooks hooks) {
        actions.log("执行彻底退出流程, deferRemoteShutdown=" + deferRemoteShutdown);

        // 先落盘 user_exited，阻止服务 onDestroy / onTaskRemoved / KeepAlive 广播在退出过程中复活
        actions.markUserExited();

        if (hooks != null) {
            hooks.stopRecordingIfNeeded();
        }

        actions.stopKeepAlive();
        actions.stopServices();
        actions.releaseWakeLocks();
        actions.resetVehicleSignals();

        if (hooks != null) {
            hooks.releaseCameras();
        }

        actions.saveLogs();
        actions.finishAppTasks();
        if (hooks != null) {
            hooks.finishUiTasks();
        }

        if (deferRemoteShutdown) {
            // 给飞书命令回复留出短暂发送窗口后再断开远程长连接并终止进程
            actions.scheduleDelayed(() -> {
                actions.stopRemoteServices();
                if (hooks != null) {
                    hooks.terminateProcess();
                }
            }, REMOTE_EXIT_FLUSH_DELAY_MS);
        } else {
            actions.stopRemoteServices();
            if (hooks != null) {
                hooks.terminateProcess();
            }
        }
    }

    private static ExitHooks wrapActivityHooks(Activity activity, ExitHooks uiHooks) {
        return new ExitHooks() {
            @Override
            public void stopRecordingIfNeeded() {
                if (uiHooks != null) {
                    uiHooks.stopRecordingIfNeeded();
                }
            }

            @Override
            public void releaseCameras() {
                if (uiHooks != null) {
                    uiHooks.releaseCameras();
                }
                CameraManagerHolder.getInstance().release();
            }

            @Override
            public void finishUiTasks() {
                if (uiHooks != null) {
                    uiHooks.finishUiTasks();
                }
                try {
                    activity.finishAffinity();
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void terminateProcess() {
                if (uiHooks != null) {
                    uiHooks.terminateProcess();
                } else {
                    System.exit(0);
                }
            }
        };
    }

    private static ExitHooks defaultProcessExitHooks() {
        return new ExitHooks() {
            @Override
            public void stopRecordingIfNeeded() {
            }

            @Override
            public void releaseCameras() {
                CameraManagerHolder.getInstance().release();
            }

            @Override
            public void finishUiTasks() {
            }

            @Override
            public void terminateProcess() {
                System.exit(0);
            }
        };
    }

    private static PlatformActions createAndroidActions(Context context) {
        Context appContext = context.getApplicationContext();
        return new PlatformActions() {
            @Override
            public void log(String message) {
                AppLog.d(TAG, message);
            }

            @Override
            public void markUserExited() {
                AppLifecycleState.markUserExited(appContext);
            }

            @Override
            public void stopKeepAlive() {
                KeepAliveManager.stopKeepAliveWork(appContext);
                KeepAliveReceiver.unregisterTimeTick(appContext);
            }

            @Override
            public void stopServices() {
                CameraForegroundService.stop(appContext);
                FloatingWindowService.stop(appContext);
                safeStopService(appContext, BlindSpotService.class);
                safeStopService(appContext, RecordingFloatingService.class);
                safeStopService(appContext, com.kooo.evcam.transfer.LanShareService.class);
            }

            @Override
            public void releaseWakeLocks() {
                WakeUpHelper.releaseWakeLock();
                WakeUpHelper.releasePersistentWakeLock();
            }

            @Override
            public void resetVehicleSignals() {
                VehicleSignalHub.getInstance().reset();
            }

            @Override
            public void saveLogs() {
                AppLog.saveToPersistentLog(appContext);
            }

            @Override
            public void finishAppTasks() {
                removeAllAppTasks(appContext);
            }

            @Override
            public void stopRemoteServices() {
                RemoteServiceManager.getInstance().stopAllServices();
            }

            @Override
            public void scheduleDelayed(Runnable runnable, long delayMs) {
                new Handler(Looper.getMainLooper()).postDelayed(runnable, delayMs);
            }
        };
    }

    private static void safeStopService(Context context, Class<?> serviceClass) {
        try {
            context.stopService(new Intent(context, serviceClass));
        } catch (Exception e) {
            AppLog.w(TAG, "停止服务异常 (" + serviceClass.getSimpleName() + "): " + e.getMessage());
        }
    }

    private static void removeAllAppTasks(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return;
            }
            List<ActivityManager.AppTask> tasks = am.getAppTasks();
            if (tasks == null) {
                return;
            }
            for (ActivityManager.AppTask task : tasks) {
                if (task != null) {
                    task.finishAndRemoveTask();
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "移除 AppTask 异常: " + e.getMessage());
        }
    }
}
