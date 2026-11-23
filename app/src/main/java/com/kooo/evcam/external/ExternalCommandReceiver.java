package com.kooo.evcam.external;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.EVCamApplication;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.lifecycle.AppExitCoordinator;
import com.kooo.evcam.ui.PreviewMode;
import com.kooo.evcam.ui.StandalonePreviewWindow;

/**
 * 外部显式广播快捷控制入口（静态注册，仅在收到目标包名广播时按需拉起处理，日常零后台占用）。
 */
public final class ExternalCommandReceiver extends BroadcastReceiver {

    private static final String TAG = "ExternalCommandReceiver";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!ExternalCommandContract.isSupportedAction(action)) {
            return;
        }
        if (ExternalCommandRouter.getDefault().shouldDropDuplicate(action)) {
            AppLog.d(TAG, "防抖丢弃重复外部广播: " + action);
            return;
        }

        Activity resumedActivity = EVCamApplication.getResumedUiActivity();
        MainActivity main = MainActivity.getInstance();
        boolean mainAlive = main != null && !main.isFinishing() && !main.isDestroyed();
        boolean appInForeground = resumedActivity != null;
        boolean mainForeground = mainAlive && resumedActivity == main;

        AppLog.i(TAG, "收到外部快捷广播: action=" + action
                + ", appForeground=" + appInForeground
                + ", mainAlive=" + mainAlive
                + ", mainForeground=" + mainForeground);

        if (isForegroundAction(action)) {
            handleForegroundAction(context, action, appInForeground, resumedActivity);
            return;
        }

        PreviewMode fixedMode = ExternalCommandContract.resolveFixedPreviewMode(action);
        if (fixedMode != null || ExternalCommandContract.ACTION_SWITCH_PREVIEW_MODE.equals(action)) {
            handlePreviewModeAction(context, intent, action, fixedMode, main, mainAlive, mainForeground);
            return;
        }

        if (isRecordingAction(action)) {
            handleRecordingAction(context, action, main, mainAlive, mainForeground);
            return;
        }

        if (ExternalCommandContract.ACTION_TAKE_PHOTO.equals(action)) {
            handlePhotoAction(context, main, mainAlive, mainForeground, appInForeground);
        }
    }

    private static boolean isForegroundAction(@NonNull String action) {
        return ExternalCommandContract.ACTION_TOGGLE_FOREGROUND.equals(action)
                || ExternalCommandContract.ACTION_MOVE_TO_FOREGROUND.equals(action)
                || ExternalCommandContract.ACTION_MOVE_TO_BACKGROUND.equals(action);
    }

    private static boolean isRecordingAction(@NonNull String action) {
        return ExternalCommandContract.ACTION_TOGGLE_RECORDING.equals(action)
                || ExternalCommandContract.ACTION_START_RECORDING.equals(action)
                || ExternalCommandContract.ACTION_STOP_RECORDING.equals(action);
    }

    private void handleForegroundAction(@NonNull Context context,
                                        @NonNull String action,
                                        boolean appInForeground,
                                        Activity resumedActivity) {
        ExternalCommandRouter.ForegroundDecision decision =
                ExternalCommandRouter.decideForegroundAction(action, appInForeground);
        switch (decision) {
            case MOVE_TO_BACKGROUND:
                if (resumedActivity != null) {
                    mainHandler.post(() -> {
                        if (!resumedActivity.isFinishing() && !resumedActivity.isDestroyed()) {
                            AppExitCoordinator.moveUiToBackground(resumedActivity);
                        }
                    });
                }
                break;
            case LAUNCH_TO_FOREGROUND:
                launchMainToForeground(context);
                break;
            case NO_OP:
            default:
                if (ExternalCommandContract.ACTION_MOVE_TO_FOREGROUND.equals(action)
                        && StandalonePreviewWindow.isShowing()) {
                    mainHandler.post(() -> StandalonePreviewWindow.dismiss(false));
                }
                AppLog.d(TAG, "前后台指令无需切换: action=" + action + ", appForeground=" + appInForeground);
                break;
        }
    }

    private void handlePreviewModeAction(@NonNull Context context,
                                         @NonNull Intent intent,
                                         @NonNull String action,
                                         PreviewMode fixedMode,
                                         MainActivity main,
                                         boolean mainAlive,
                                         boolean mainForeground) {
        PreviewMode targetMode;
        boolean toggleBg;
        if (fixedMode != null) {
            targetMode = fixedMode;
            toggleBg = true;
        } else {
            PreviewMode explicit = ExternalCommandContract.parsePreviewModeExtra(
                    intent.getStringExtra(ExternalCommandContract.EXTRA_MODE));
            if (explicit != null) {
                targetMode = explicit;
                toggleBg = intent.getBooleanExtra(ExternalCommandContract.EXTRA_TOGGLE_BACKGROUND, false);
            } else {
                PreviewMode current = mainAlive
                        ? main.getCurrentPreviewMode()
                        : PreviewMode.fromKey(new AppConfig(context).getPreviewMode());
                targetMode = ExternalCommandContract.nextPreviewMode(current);
                toggleBg = false;
            }
        }

        ExternalCommandRouter.PreviewModeDecision decision =
                ExternalCommandRouter.decidePreviewModeAction(
                        mainForeground,
                        mainAlive && main.isOnRecordingSection(),
                        mainAlive && main.isMainPreviewCurrentlyEnabled(),
                        mainAlive ? main.getCurrentPreviewMode() : null,
                        targetMode,
                        toggleBg);

        switch (decision) {
            case MOVE_TO_BACKGROUND:
                mainHandler.post(() -> {
                    if (!main.isFinishing() && !main.isDestroyed()) {
                        AppExitCoordinator.moveUiToBackground(main);
                    }
                });
                break;
            case APPLY_IN_PLACE:
                mainHandler.post(() -> {
                    if (!main.isFinishing() && !main.isDestroyed()) {
                        main.handleExternalPreviewModeCommand(targetMode);
                    }
                });
                break;
            case LAUNCH_TO_FOREGROUND_WITH_MODE:
            default:
                launchMainWithPreviewMode(context, targetMode);
                break;
        }
    }

    private void handleRecordingAction(@NonNull Context context,
                                       @NonNull String action,
                                       MainActivity main,
                                       boolean mainAlive,
                                       boolean mainForeground) {
        MultiCameraManager holderCm = CameraManagerHolder.getInstance().getCameraManager();
        boolean anyRecording = mainAlive
                ? main.isAnyRecordingActive()
                : (holderCm != null && holderCm.isRecording());
        boolean hasCameras = mainAlive
                ? main.hasAvailableCamerasForExternalCommand()
                : (holderCm != null && holderCm.hasAvailableCameras());

        ExternalCommandRouter.RecordingDecision decision =
                ExternalCommandRouter.decideRecordingAction(
                        action, mainAlive, mainForeground, hasCameras, anyRecording);

        switch (decision) {
            case START_IN_PLACE:
                mainHandler.post(() -> {
                    if (!main.isFinishing() && !main.isDestroyed()) {
                        main.handleExternalStartRecordingCommand();
                    }
                });
                break;
            case LAUNCH_START_RECORDING:
                try {
                    WakeUpHelper.launchForStartRecording(context);
                } catch (Exception e) {
                    AppLog.e(TAG, "外部广播拉起开始录制失败: " + e.getMessage(), e);
                }
                break;
            case STOP_IN_PLACE:
                mainHandler.post(() -> {
                    if (!main.isFinishing() && !main.isDestroyed()) {
                        main.handleExternalStopRecordingCommand();
                    }
                });
                break;
            case STOP_VIA_CAMERA_HOLDER:
                if (holderCm != null) {
                    mainHandler.post(() -> {
                        MultiCameraManager cm = CameraManagerHolder.getInstance().getCameraManager();
                        if (cm != null && cm.isRecording()) {
                            cm.stopRecording();
                        }
                    });
                }
                break;
            case NO_OP:
            default:
                AppLog.d(TAG, "录制指令无需执行: action=" + action + ", anyRecording=" + anyRecording);
                break;
        }
    }

    private void handlePhotoAction(@NonNull Context context,
                                   MainActivity main,
                                   boolean mainAlive,
                                   boolean mainForeground,
                                   boolean appInForeground) {
        ExternalCommandRouter.PhotoDecision decision =
                ExternalCommandRouter.decidePhotoAction(mainForeground, appInForeground);
        switch (decision) {
            case TAKE_PHOTO_IN_PLACE:
                if (mainAlive) {
                    mainHandler.post(() -> {
                        if (!main.isFinishing() && !main.isDestroyed()) {
                            main.handleExternalTakePhotoCommand(false);
                        }
                    });
                }
                break;
            case LAUNCH_TAKE_PHOTO_KEEP_FOREGROUND:
                launchMainForTakePhoto(context, false);
                break;
            case LAUNCH_TAKE_PHOTO_AND_RETURN_BACKGROUND:
            default:
                launchMainForTakePhoto(context, true);
                break;
        }
    }

    private static void launchMainToForeground(@NonNull Context context) {
        try {
            WakeUpHelper.acquireCpuWakeLock(context);
            Intent launch = new Intent(context, MainActivity.class);
            launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            context.startActivity(launch);
        } catch (Exception e) {
            AppLog.e(TAG, "外部广播拉起前台失败: " + e.getMessage(), e);
        }
    }

    private static void launchMainWithPreviewMode(@NonNull Context context, @NonNull PreviewMode mode) {
        try {
            WakeUpHelper.acquireCpuWakeLock(context);
            Intent launch = new Intent(context, MainActivity.class);
            launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launch.putExtra(ExternalCommandContract.EXTRA_EXTERNAL_COMMAND,
                    ExternalCommandContract.CMD_OPEN_PREVIEW_MODE);
            launch.putExtra(ExternalCommandContract.EXTRA_PREVIEW_MODE, mode.key);
            context.startActivity(launch);
        } catch (Exception e) {
            AppLog.e(TAG, "外部广播切入视图 " + mode.key + " 失败: " + e.getMessage(), e);
        }
    }

    private static void launchMainForTakePhoto(@NonNull Context context, boolean returnToBackground) {
        try {
            WakeUpHelper.acquireCpuWakeLock(context);
            Intent launch = new Intent(context, MainActivity.class);
            launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launch.putExtra(ExternalCommandContract.EXTRA_EXTERNAL_COMMAND,
                    ExternalCommandContract.CMD_TAKE_PHOTO);
            launch.putExtra(ExternalCommandContract.EXTRA_RETURN_TO_BACKGROUND_AFTER_PHOTO,
                    returnToBackground);
            context.startActivity(launch);
        } catch (Exception e) {
            AppLog.e(TAG, "外部广播拉起抓拍失败: " + e.getMessage(), e);
        }
    }
}
