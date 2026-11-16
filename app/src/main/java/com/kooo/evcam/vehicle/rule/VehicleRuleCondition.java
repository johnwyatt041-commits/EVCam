package com.kooo.evcam.vehicle.rule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.vehicle.VehicleSignalSnapshot;

import java.util.Objects;

/**
 * 单个车辆信号判定条件。
 */
public final class VehicleRuleCondition {
    @NonNull
    public final VehicleRuleSignal signal;
    @NonNull
    public final RuleComparison comparison;
    public final int expectedValue;

    public VehicleRuleCondition(@NonNull VehicleRuleSignal signal,
                                @NonNull RuleComparison comparison,
                                int expectedValue) {
        this.signal = signal;
        this.comparison = comparison;
        this.expectedValue = expectedValue;
    }

    public boolean matches(@Nullable VehicleSignalSnapshot snapshot) {
        Integer actual = signal.extractValue(snapshot);
        if (actual == null) {
            return false;
        }
        return comparison.evaluate(actual, expectedValue);
    }

    @NonNull
    public String toReadableText() {
        return signal.label + " " + comparison.symbol + " " + signal.formatValue(expectedValue);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VehicleRuleCondition)) return false;
        VehicleRuleCondition that = (VehicleRuleCondition) o;
        return expectedValue == that.expectedValue
                && signal == that.signal
                && comparison == that.comparison;
    }

    @Override
    public int hashCode() {
        return Objects.hash(signal, comparison, expectedValue);
    }
}
