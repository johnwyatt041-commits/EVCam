package com.kooo.evcam.vehicle.rule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 规则条件比较运算符。
 */
public enum RuleComparison {
    EQUALS("=", "等于"),
    NOT_EQUALS("!=", "不等于"),
    GREATER_THAN(">", "大于"),
    GREATER_OR_EQUAL(">=", "大于等于"),
    LESS_THAN("<", "小于"),
    LESS_OR_EQUAL("<=", "小于等于");

    public final String symbol;
    public final String label;

    RuleComparison(@NonNull String symbol, @NonNull String label) {
        this.symbol = symbol;
        this.label = label;
    }

    public boolean evaluate(int actual, int expected) {
        switch (this) {
            case EQUALS:
                return actual == expected;
            case NOT_EQUALS:
                return actual != expected;
            case GREATER_THAN:
                return actual > expected;
            case GREATER_OR_EQUAL:
                return actual >= expected;
            case LESS_THAN:
                return actual < expected;
            case LESS_OR_EQUAL:
                return actual <= expected;
            default:
                return false;
        }
    }

    @NonNull
    public static RuleComparison fromName(@Nullable String name) {
        if (name != null) {
            for (RuleComparison value : values()) {
                if (value.name().equalsIgnoreCase(name.trim())) {
                    return value;
                }
            }
        }
        return EQUALS;
    }
}
