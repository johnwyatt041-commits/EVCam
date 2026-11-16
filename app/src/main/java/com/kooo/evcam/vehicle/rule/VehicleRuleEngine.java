package com.kooo.evcam.vehicle.rule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.vehicle.VehicleSignalSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 车辆状态规则评估与仲裁引擎（纯 Java 状态机，支持边沿触发、持续延迟与停止类规则压制 START）。
 */
public final class VehicleRuleEngine {

    public static final class TriggerEvent {
        @NonNull
        public final VehicleTriggerRule rule;
        @NonNull
        public final RuleAction action;

        public TriggerEvent(@NonNull VehicleTriggerRule rule, @NonNull RuleAction action) {
            this.rule = rule;
            this.action = action;
        }
    }

    private static final class RuleRuntime {
        boolean matching;
        long matchStartMs;
        boolean fired;
    }

    private List<VehicleTriggerRule> rules = Collections.emptyList();
    private final Map<String, RuleRuntime> runtimes = new HashMap<>();
    @Nullable
    private VehicleSignalSnapshot lastSnapshot;

    public synchronized void setRules(@Nullable List<VehicleTriggerRule> nextRules) {
        if (nextRules == null) {
            this.rules = Collections.emptyList();
        } else {
            this.rules = Collections.unmodifiableList(new ArrayList<>(nextRules));
        }
        runtimes.clear();
    }

    @NonNull
    public synchronized List<VehicleTriggerRule> getRules() {
        return rules;
    }

    public synchronized void reset() {
        runtimes.clear();
        lastSnapshot = null;
    }

    /**
     * 评估输入信号快照，返回本轮边沿触发的获胜动作（无新触发则返回 null）。
     */
    @Nullable
    public synchronized TriggerEvent evaluate(@Nullable VehicleSignalSnapshot snapshot, long nowMs) {
        this.lastSnapshot = snapshot;
        if (snapshot == null || rules.isEmpty()) {
            return null;
        }

        List<VehicleTriggerRule> eligibleRules = new ArrayList<>();
        boolean hasStopOrExitEligible = false;

        for (VehicleTriggerRule rule : rules) {
            RuleRuntime runtime = runtimes.get(rule.id);
            if (runtime == null) {
                runtime = new RuleRuntime();
                runtimes.put(rule.id, runtime);
            }
            if (!rule.enabled) {
                runtime.matching = false;
                runtime.fired = false;
                continue;
            }

            boolean matched = rule.matches(snapshot);
            if (!matched) {
                runtime.matching = false;
                runtime.fired = false;
                continue;
            }

            if (!runtime.matching) {
                runtime.matching = true;
                runtime.matchStartMs = nowMs;
                runtime.fired = false;
            }

            long requiredMs = rule.delaySeconds * 1000L;
            if (nowMs - runtime.matchStartMs >= requiredMs) {
                eligibleRules.add(rule);
                if (rule.action.isStopOrExit()) {
                    hasStopOrExitEligible = true;
                }
            }
        }

        if (eligibleRules.isEmpty()) {
            return null;
        }

        // SC Lgk1.m() 仲裁语义：只要存在任何停止/退出类规则已满足生效，则压制 START 类规则
        VehicleTriggerRule winner = null;
        for (VehicleTriggerRule candidate : eligibleRules) {
            if (hasStopOrExitEligible && candidate.action == RuleAction.START) {
                RuleRuntime startRuntime = runtimes.get(candidate.id);
                if (startRuntime != null) {
                    // 被压制期间清除 fired，待退出条件消失若仍在 D 挡可恢复边沿重评
                    startRuntime.fired = false;
                }
                continue;
            }
            if (winner == null || candidate.action.priority > winner.action.priority) {
                winner = candidate;
            }
        }

        if (winner == null) {
            return null;
        }

        RuleRuntime winnerRuntime = runtimes.get(winner.id);
        if (winnerRuntime != null && !winnerRuntime.fired) {
            winnerRuntime.fired = true;
            return new TriggerEvent(winner, winner.action);
        }
        return null;
    }

    /**
     * 使用最近一次快照按最新时间重新推进延迟计时规则（用于 durationSeconds > 0 到期时无新快照的场景）。
     */
    @Nullable
    public synchronized TriggerEvent tick(long nowMs) {
        if (lastSnapshot == null) {
            return null;
        }
        return evaluate(lastSnapshot, nowMs);
    }

    /**
     * 计算距离下一个仍在倒计时中的匹配规则到期的最短毫秒数；无待到期规则返回 -1。
     */
    public synchronized long getNextDelayDueMs(long nowMs) {
        long minRemaining = -1L;
        for (VehicleTriggerRule rule : rules) {
            if (!rule.enabled || rule.delaySeconds <= 0) {
                continue;
            }
            RuleRuntime runtime = runtimes.get(rule.id);
            if (runtime == null || !runtime.matching || runtime.fired) {
                continue;
            }
            long dueAt = runtime.matchStartMs + rule.delaySeconds * 1000L;
            long remaining = Math.max(0L, dueAt - nowMs);
            if (minRemaining < 0 || remaining < minRemaining) {
                minRemaining = remaining;
            }
        }
        return minRemaining;
    }
}
