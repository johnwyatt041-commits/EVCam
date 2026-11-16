package com.kooo.evcam.vehicle.rule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 规则触发动作类型及冲突仲裁优先级（数值越大优先级越高）。
 */
public enum RuleAction {
    START("开始录制", 0),
    STOP("仅停止录制", 1),
    STOP_RELEASE("停止录制并释放摄像头", 2),
    EXIT_APP("停录释放并退回后台", 3);

    public final String label;
    public final int priority;

    RuleAction(@NonNull String label, int priority) {
        this.label = label;
        this.priority = priority;
    }

    public boolean isStopOrExit() {
        return this != START;
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
