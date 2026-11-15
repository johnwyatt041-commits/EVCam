package com.kooo.evcam.lifecycle;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import com.kooo.evcam.BootReceiver;

/**
 * 统一单进程生命周期与退出状态管理。
 *
 * 借鉴 Smartcam ui_exited / user_exited 双状态语义，适配 EVCam 单进程架构：
 * - user_exited: 用户主动彻底退出（屏蔽开机自启、系统唤醒广播与 WorkManager 保活复活，直至再次打开主界面）
 * - ui_exited: 仅退出/隐藏主界面（保留后台服务与补盲监听，禁止后台逻辑擅自将 MainActivity 拉回前台）
 */
public final class AppLifecycleState {
    public static final String PREF_NAME = "app_keep_alive";
    public static final String KEY_USER_EXITED = "user_exited";
    public static final String KEY_UI_EXITED = "ui_exited";

    private AppLifecycleState() {
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 是否处于用户主动彻底退出状态。 */
    public static boolean isUserExited(Context context) {
        if (context == null) {
            return false;
        }
        return isUserExited(getPrefs(context));
    }

    /** 基于 SharedPreferences 判断是否处于用户主动彻底退出状态（便于纯 JVM 单测）。 */
    public static boolean isUserExited(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(KEY_USER_EXITED, false);
    }

    /** 是否处于仅退出/隐藏 UI 的后台状态。 */
    public static boolean isUiExited(Context context) {
        if (context == null) {
            return false;
        }
        return isUiExited(getPrefs(context));
    }

    /** 基于 SharedPreferences 判断是否处于仅退出 UI 的后台状态。 */
    public static boolean isUiExited(SharedPreferences prefs) {
        return prefs != null && prefs.getBoolean(KEY_UI_EXITED, false);
    }

    /** 标记用户主动彻底退出：同步落盘，并禁用开机自启动接收器（Option A）。 */
    public static void markUserExited(Context context) {
        if (context == null) {
            return;
        }
        markUserExited(getPrefs(context));
        syncBootReceiverEnabled(context, false);
    }

    /** 纯存储层标记用户彻底退出（同步 commit，避免立即杀进程丢失状态）。 */
    public static void markUserExited(SharedPreferences prefs) {
        if (prefs == null) {
            return;
        }
        prefs.edit()
                .putBoolean(KEY_USER_EXITED, true)
                .putBoolean(KEY_UI_EXITED, true)
                .commit();
    }

    /** 标记界面退到后台，保留后台服务运行能力。 */
    public static void markUiExited(Context context) {
        if (context == null) {
            return;
        }
        markUiExited(getPrefs(context));
    }

    /** 纯存储层标记界面已退出到后台。 */
    public static void markUiExited(SharedPreferences prefs) {
        if (prefs == null) {
            return;
        }
        prefs.edit().putBoolean(KEY_UI_EXITED, true).apply();
    }

    /** 标记用户显式进入主界面，清除彻底退出与 UI 退出标记并恢复开机接收器。 */
    public static void markUserEnteredUi(Context context) {
        if (context == null) {
            return;
        }
        markUserEnteredUi(getPrefs(context));
        syncBootReceiverEnabled(context, true);
    }

    /** 纯存储层清除彻底退出与 UI 退出标记。 */
    public static void markUserEnteredUi(SharedPreferences prefs) {
        if (prefs == null) {
            return;
        }
        prefs.edit()
                .putBoolean(KEY_USER_EXITED, false)
                .putBoolean(KEY_UI_EXITED, false)
                .commit();
    }

    /** 界面回到前台时清除 ui_exited 标记；若处于 user_exited 则不同步修改。 */
    public static void markUiReturnedForeground(Context context) {
        if (context == null) {
            return;
        }
        markUiReturnedForeground(getPrefs(context));
    }

    /** 纯存储层恢复 UI 前台标记。 */
    public static void markUiReturnedForeground(SharedPreferences prefs) {
        if (prefs == null || isUserExited(prefs)) {
            return;
        }
        if (prefs.getBoolean(KEY_UI_EXITED, false)) {
            prefs.edit().putBoolean(KEY_UI_EXITED, false).apply();
        }
    }

    /** 基于 SharedPreferences 判断是否允许运行后台服务与保活机制。 */
    public static boolean canRunBackgroundServices(SharedPreferences prefs) {
        return !isUserExited(prefs);
    }

    /** 是否允许运行任何后台服务与保活机制。 */
    public static boolean canRunBackgroundServices(Context context) {
        return !isUserExited(context);
    }

    /** 纯状态判断是否允许运行后台服务与保活机制。 */
    public static boolean canRunBackgroundServices(boolean userExited) {
        return !userExited;
    }

    /** 是否允许系统开机广播启动后台服务与自启流程（Option A：user_exited=true 时拦截）。 */
    public static boolean canHandleBootCompleted(boolean userExited) {
        return !userExited;
    }

    /** 是否允许在开机后拉起 MainActivity。 */
    public static boolean canLaunchUiOnBoot(boolean userExited, boolean autoStartOnBoot) {
        return !userExited && autoStartOnBoot;
    }

    /** 是否允许后台服务在无用户交互时拉起 MainActivity（ui_exited 或 user_exited 时禁止抢前台）。 */
    public static boolean shouldAutoLaunchUiFromBackground(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = getPrefs(context);
        return shouldAutoLaunchUiFromBackground(isUserExited(prefs), isUiExited(prefs));
    }

    /** 纯状态判断是否允许后台自动拉起主界面。 */
    public static boolean shouldAutoLaunchUiFromBackground(boolean userExited, boolean uiExited) {
        return !userExited && !uiExited;
    }

    /** 判断前台服务在被销毁或划掉任务后是否允许自动调度重启。 */
    public static boolean shouldScheduleServiceRestart(Context context, boolean explicitStopRequested) {
        return shouldScheduleServiceRestart(isUserExited(context), explicitStopRequested);
    }

    /** 纯状态判断前台服务是否允许自重启。 */
    public static boolean shouldScheduleServiceRestart(boolean userExited, boolean explicitStopRequested) {
        return !userExited && !explicitStopRequested;
    }

    /**
     * 判断 MainActivity.onDestroy() 时是否应当停止 CameraForegroundService。
     * 配置变更（主题切换）或仍有后台服务需要在 ui_exited 期间保活时，不应误杀前台服务。
     */
    public static boolean shouldStopForegroundOnUiDestroy(
            boolean userExited,
            boolean changingConfigurations,
            boolean hasActiveBackgroundServices) {
        if (userExited) {
            return true;
        }
        if (changingConfigurations) {
            return false;
        }
        return !hasActiveBackgroundServices;
    }

    /** 同步 BootReceiver 组件启用状态（Option A）。 */
    public static void syncBootReceiverEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        try {
            Context appContext = context.getApplicationContext();
            PackageManager pm = appContext.getPackageManager();
            if (pm == null) {
                return;
            }
            int state = enabled
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(
                    new ComponentName(appContext, BootReceiver.class),
                    state,
                    PackageManager.DONT_KILL_APP
            );
        } catch (Throwable ignored) {
            // 受限测试上下文或系统权限差异时保持静态 SharedPreferences 门禁兜底
        }
    }
}
