package com.kooo.evcam.vehicle.rule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 多条件组合逻辑（AND / OR）。
 */
public enum ConditionLogic {
    AND("全部满足 (AND)"),
    OR("任一满足 (OR)");

    public final String label;

    ConditionLogic(@NonNull String label) {
        this.label = label;
    }

    @NonNull
    public static ConditionLogic fromName(@Nullable String name) {
        if (name != null && OR.name().equalsIgnoreCase(name.trim())) {
            return OR;
        }
        return AND;
    }
}
