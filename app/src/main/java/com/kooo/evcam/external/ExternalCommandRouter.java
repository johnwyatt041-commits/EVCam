package com.kooo.evcam.external;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.ui.PreviewMode;

import java.util.HashMap;
import java.util.Map;

/**
 * 外部广播快捷控制状态机与按 Action 防抖保护（纯 Java 可单测）。
 */
public final class ExternalCommandRouter {

    public interface Clock {
        long elapsedRealtimeMs();
    }

    public enum ForegroundDecision {
        NO_OP,
        MOVE_TO_BACKGROUND,
        LAUNCH_TO_FOREGROUND
    }

    public enum PreviewModeDecision {
        MOVE_TO_BACKGROUND,
        APPLY_IN_PLACE,
        LAUNCH_TO_FOREGROUND_WITH_MODE
    }

    public enum RecordingDecision {
        NO_OP,
        START_IN_PLACE,
        LAUNCH_START_RECORDING,
        STOP_IN_PLACE,
        STOP_VIA_CAMERA_HOLDER
    }

    public enum PhotoDecision {
        TAKE_PHOTO_IN_PLACE,
        LAUNCH_TAKE_PHOTO_KEEP_FOREGROUND,
        LAUNCH_TAKE_PHOTO_AND_RETURN_BACKGROUND
    }

    private static final ExternalCommandRouter DEFAULT_INSTANCE =
            new ExternalCommandRouter(SystemClock::elapsedRealtime);

    private final Clock clock;
    private final Map<String, Long> lastAcceptedMs = new HashMap<>();

    public ExternalCommandRouter(@NonNull Clock clock) {
        this.clock = clock;
    }

    @NonNull
    public static ExternalCommandRouter getDefault() {
        return DEFAULT_INSTANCE;
    }

    public synchronized boolean shouldDropDuplicate(@Nullable String action) {
        return shouldDropDuplicate(action, clock.elapsedRealtimeMs());
    }

    synchronized boolean shouldDropDuplicate(@Nullable String action, long nowMs) {
        if (action == null) {
            return true;
        }
        long windowMs = ExternalCommandContract.debounceWindowMs(action);
        Long last = lastAcceptedMs.get(action);
        if (last != null && nowMs >= last && (nowMs - last) < windowMs) {
            return true;
        }
        lastAcceptedMs.put(action, nowMs);
        return false;
    }

    @NonNull
    public static ForegroundDecision decideForegroundAction(@Nullable String action, boolean appInForeground) {
        if (ExternalCommandContract.ACTION_TOGGLE_FOREGROUND.equals(action)) {
            return appInForeground
                    ? ForegroundDecision.MOVE_TO_BACKGROUND
                    : ForegroundDecision.LAUNCH_TO_FOREGROUND;
        }
        if (ExternalCommandContract.ACTION_MOVE_TO_FOREGROUND.equals(action)) {
            return appInForeground
                    ? ForegroundDecision.NO_OP
                    : ForegroundDecision.LAUNCH_TO_FOREGROUND;
        }
        if (ExternalCommandContract.ACTION_MOVE_TO_BACKGROUND.equals(action)) {
            return appInForeground
                    ? ForegroundDecision.MOVE_TO_BACKGROUND
                    : ForegroundDecision.NO_OP;
        }
        return ForegroundDecision.NO_OP;
    }

    @NonNull
    public static PreviewModeDecision decidePreviewModeAction(
            boolean mainActivityForeground,
            boolean onRecordingSection,
            boolean mainPreviewEnabled,
            @Nullable PreviewMode currentMode,
            @NonNull PreviewMode targetMode,
            boolean toggleBackgroundWhenAlreadyActive) {
        if (toggleBackgroundWhenAlreadyActive
                && mainActivityForeground
                && onRecordingSection
                && mainPreviewEnabled
                && currentMode == targetMode) {
            return PreviewModeDecision.MOVE_TO_BACKGROUND;
        }
        if (mainActivityForeground) {
            return PreviewModeDecision.APPLY_IN_PLACE;
        }
        return PreviewModeDecision.LAUNCH_TO_FOREGROUND_WITH_MODE;
    }

    @NonNull
    public static RecordingDecision decideRecordingAction(
            @Nullable String action,
            boolean mainActivityAlive,
            boolean mainActivityForeground,
            boolean hasAvailableCameras,
            boolean anyRecordingActive) {
        if (ExternalCommandContract.ACTION_START_RECORDING.equals(action)) {
            if (anyRecordingActive) {
                return RecordingDecision.NO_OP;
            }
            return (mainActivityAlive && (mainActivityForeground || hasAvailableCameras))
                    ? RecordingDecision.START_IN_PLACE
                    : RecordingDecision.LAUNCH_START_RECORDING;
        }
        if (ExternalCommandContract.ACTION_STOP_RECORDING.equals(action)) {
            if (!anyRecordingActive) {
                return RecordingDecision.NO_OP;
            }
            return mainActivityAlive
                    ? RecordingDecision.STOP_IN_PLACE
                    : RecordingDecision.STOP_VIA_CAMERA_HOLDER;
        }
        if (ExternalCommandContract.ACTION_TOGGLE_RECORDING.equals(action)) {
            if (anyRecordingActive) {
                return mainActivityAlive
                        ? RecordingDecision.STOP_IN_PLACE
                        : RecordingDecision.STOP_VIA_CAMERA_HOLDER;
            }
            return (mainActivityAlive && (mainActivityForeground || hasAvailableCameras))
                    ? RecordingDecision.START_IN_PLACE
                    : RecordingDecision.LAUNCH_START_RECORDING;
        }
        return RecordingDecision.NO_OP;
    }

    @NonNull
    public static PhotoDecision decidePhotoAction(boolean mainActivityForeground, boolean appInForeground) {
        if (mainActivityForeground) {
            return PhotoDecision.TAKE_PHOTO_IN_PLACE;
        }
        if (appInForeground) {
            return PhotoDecision.LAUNCH_TAKE_PHOTO_KEEP_FOREGROUND;
        }
        return PhotoDecision.LAUNCH_TAKE_PHOTO_AND_RETURN_BACKGROUND;
    }
}
