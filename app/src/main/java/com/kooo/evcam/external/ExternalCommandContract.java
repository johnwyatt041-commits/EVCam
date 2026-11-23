package com.kooo.evcam.external;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.ui.PreviewMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 外部广播快捷控制契约定义（Action、Extra、防抖窗口及纯映射辅助方法）。
 */
public final class ExternalCommandContract {

    public static final String ACTION_TOGGLE_FOREGROUND = "com.kooo.evcam.action.TOGGLE_FOREGROUND";
    public static final String ACTION_MOVE_TO_FOREGROUND = "com.kooo.evcam.action.MOVE_TO_FOREGROUND";
    public static final String ACTION_MOVE_TO_BACKGROUND = "com.kooo.evcam.action.MOVE_TO_BACKGROUND";

    public static final String ACTION_TOGGLE_RECORDING = "com.kooo.evcam.action.TOGGLE_RECORDING";
    public static final String ACTION_START_RECORDING = "com.kooo.evcam.action.START_RECORDING";
    public static final String ACTION_STOP_RECORDING = "com.kooo.evcam.action.STOP_RECORDING";
    public static final String ACTION_TAKE_PHOTO = "com.kooo.evcam.action.TAKE_PHOTO";

    public static final String ACTION_OPEN_PREVIEW_MODE_A = "com.kooo.evcam.action.OPEN_PREVIEW_MODE_A";
    public static final String ACTION_OPEN_PREVIEW_MODE_B = "com.kooo.evcam.action.OPEN_PREVIEW_MODE_B";
    public static final String ACTION_OPEN_PREVIEW_MODE_C = "com.kooo.evcam.action.OPEN_PREVIEW_MODE_C";
    public static final String ACTION_OPEN_PREVIEW_MODE_D = "com.kooo.evcam.action.OPEN_PREVIEW_MODE_D";
    public static final String ACTION_OPEN_PREVIEW_MODE_E = "com.kooo.evcam.action.OPEN_PREVIEW_MODE_E";
    public static final String ACTION_SWITCH_PREVIEW_MODE = "com.kooo.evcam.action.SWITCH_PREVIEW_MODE";

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_TOGGLE_BACKGROUND = "toggle_background";

    public static final String EXTRA_EXTERNAL_COMMAND = "external_command";
    public static final String EXTRA_PREVIEW_MODE = "external_preview_mode";
    public static final String EXTRA_RETURN_TO_BACKGROUND_AFTER_PHOTO = "external_photo_return_bg";
    public static final String CMD_OPEN_PREVIEW_MODE = "open_preview_mode";
    public static final String CMD_TAKE_PHOTO = "take_photo";

    public static final long DEBOUNCE_UI_MS = 400L;
    public static final long DEBOUNCE_ACTION_MS = 1000L;

    private static final Set<String> SUPPORTED_ACTIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            ACTION_TOGGLE_FOREGROUND,
            ACTION_MOVE_TO_FOREGROUND,
            ACTION_MOVE_TO_BACKGROUND,
            ACTION_TOGGLE_RECORDING,
            ACTION_START_RECORDING,
            ACTION_STOP_RECORDING,
            ACTION_TAKE_PHOTO,
            ACTION_OPEN_PREVIEW_MODE_A,
            ACTION_OPEN_PREVIEW_MODE_B,
            ACTION_OPEN_PREVIEW_MODE_C,
            ACTION_OPEN_PREVIEW_MODE_D,
            ACTION_OPEN_PREVIEW_MODE_E,
            ACTION_SWITCH_PREVIEW_MODE
    )));

    private ExternalCommandContract() {
    }

    public static boolean isSupportedAction(@Nullable String action) {
        return action != null && SUPPORTED_ACTIONS.contains(action);
    }

    @Nullable
    public static PreviewMode resolveFixedPreviewMode(@Nullable String action) {
        if (action == null) {
            return null;
        }
        switch (action) {
            case ACTION_OPEN_PREVIEW_MODE_A:
                return PreviewMode.MODE_A;
            case ACTION_OPEN_PREVIEW_MODE_B:
                return PreviewMode.MODE_B;
            case ACTION_OPEN_PREVIEW_MODE_C:
                return PreviewMode.MODE_C;
            case ACTION_OPEN_PREVIEW_MODE_D:
                return PreviewMode.MODE_D;
            case ACTION_OPEN_PREVIEW_MODE_E:
                return PreviewMode.MODE_E;
            default:
                return null;
        }
    }

    @Nullable
    public static PreviewMode parsePreviewModeExtra(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "mode_a":
            case "a":
                return PreviewMode.MODE_A;
            case "mode_b":
            case "b":
                return PreviewMode.MODE_B;
            case "mode_c":
            case "c":
                return PreviewMode.MODE_C;
            case "mode_d":
            case "d":
                return PreviewMode.MODE_D;
            case "mode_e":
            case "e":
                return PreviewMode.MODE_E;
            default:
                return null;
        }
    }

    @NonNull
    public static PreviewMode nextPreviewMode(@Nullable PreviewMode current) {
        if (current == null) {
            return PreviewMode.MODE_A;
        }
        switch (current) {
            case MODE_A:
                return PreviewMode.MODE_B;
            case MODE_B:
                return PreviewMode.MODE_C;
            case MODE_C:
                return PreviewMode.MODE_D;
            case MODE_D:
                return PreviewMode.MODE_E;
            case MODE_E:
            default:
                return PreviewMode.MODE_A;
        }
    }

    public static long debounceWindowMs(@Nullable String action) {
        if (ACTION_TOGGLE_RECORDING.equals(action)
                || ACTION_START_RECORDING.equals(action)
                || ACTION_STOP_RECORDING.equals(action)
                || ACTION_TAKE_PHOTO.equals(action)) {
            return DEBOUNCE_ACTION_MS;
        }
        return DEBOUNCE_UI_MS;
    }

    public static boolean shouldSkipStartupPageOverrides(@Nullable String externalCommand) {
        return externalCommand != null && !externalCommand.isEmpty();
    }
}
