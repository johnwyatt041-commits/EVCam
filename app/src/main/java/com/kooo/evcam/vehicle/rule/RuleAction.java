package com.kooo.evcam.vehicle.rule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 规则触发动作类型及分域冲突仲裁优先级（同域内数值越大优先级越高，跨动作域互不压制）。
 */
public enum RuleAction {
    START("开始录制", 0, ActionGroup.RECORDING),
    STOP("仅停止录制", 1, ActionGroup.RECORDING),
    STOP_RELEASE("停止录制并释放摄像头", 2, ActionGroup.RECORDING),
    EXIT_APP("停录释放并退回后台", 3, ActionGroup.RECORDING),
    ENABLE_GUIDANCE("开启 360 引导线", 0, ActionGroup.GUIDANCE),
    DISABLE_GUIDANCE("关闭 360 引导线", 1, ActionGroup.GUIDANCE);

    public enum ActionGroup {
        RECORDING,
        GUIDANCE
    }

    public final String label;
    public final int priority;
    public final ActionGroup group;

    RuleAction(@NonNull String label, int priority, @NonNull ActionGroup group) {
        this.label = label;
        this.priority = priority;
        this.group = group;
    }

    public boolean isStopOrExit() {
        return group == ActionGroup.RECORDING && this != START;
    }

    public boolean isGuidanceAction() {
        return group == ActionGroup.GUIDANCE;
    }

    @NonNull
    public static RuleAction fromName(@Nullable String name) {
        if (name != null) {
            for (RuleAction value : values()) {
                if (value.name().equalsIgnoreCase(name.trim())) {
                    return value;
                }
            }
        }
        return START;
    }
}
