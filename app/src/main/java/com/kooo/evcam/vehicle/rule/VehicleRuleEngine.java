package com.kooo.evcam.vehicle.rule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.vehicle.VehicleSignalSnapshot;
import com.kooo.evcam.vehicle.rule.RuleAction.ActionGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 车辆状态规则评估与仲裁引擎（纯 Java 状态机，支持边沿触发、持续延迟，以及录制域/引导线域独立仲裁）。
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
        boolean suppressed;
    }

    private List<VehicleTriggerRule> rules = Collections.emptyList();
    private final Map<String, RuleRuntime> runtimes = new HashMap<>();
    @Nullable
    private VehicleSignalSnapshot lastSnapshot;

    public synchronized void setRules(@Nullable List<VehicleTriggerRule> nextRules) {
        Map<String, VehicleTriggerRule> previousRulesById = new HashMap<>(this.rules.size());
        for (VehicleTriggerRule previous : this.rules) {
            previousRulesById.put(previous.id, previous);
        }
        if (nextRules == null) {
            this.rules = Collections.emptyList();
            runtimes.clear();
            return;
        }
        List<VehicleTriggerRule> copied = new ArrayList<>(nextRules.size());
        Map<String, RuleRuntime> preservedRuntimes = new HashMap<>(nextRules.size());
        for (VehicleTriggerRule next : nextRules) {
            if (next == null) {
                continue;
            }
            copied.add(next);
            VehicleTriggerRule previous = previousRulesById.get(next.id);
            RuleRuntime existingRuntime = runtimes.get(next.id);
            if (previous != null && existingRuntime != null && previous.hasSameEvaluationDefinition(next)) {
                preservedRuntimes.put(next.id, existingRuntime);
            }
        }
        this.rules = Collections.unmodifiableList(copied);
        runtimes.clear();
        runtimes.putAll(preservedRuntimes);
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
     * 评估输入信号快照，按动作域（RECORDING / GUIDANCE）分别仲裁并返回本轮新触发的事件列表。
     */
    @NonNull
    public synchronized List<TriggerEvent> evaluateAll(@Nullable VehicleSignalSnapshot snapshot, long nowMs) {
        this.lastSnapshot = snapshot;
        if (snapshot == null || rules.isEmpty()) {
            return Collections.emptyList();
        }

        EnumMap<ActionGroup, List<VehicleTriggerRule>> groupedEligible = new EnumMap<>(ActionGroup.class);
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
                runtime.suppressed = false;
                continue;
            }

            boolean matched = rule.matches(snapshot);
            if (!matched) {
                runtime.matching = false;
                runtime.fired = false;
                runtime.suppressed = false;
                continue;
            }

            if (!runtime.matching) {
                runtime.matching = true;
                runtime.matchStartMs = nowMs;
                runtime.fired = false;
            }
            runtime.suppressed = false;

            long requiredMs = rule.delaySeconds * 1000L;
            if (nowMs - runtime.matchStartMs >= requiredMs) {
                List<VehicleTriggerRule> bucket = groupedEligible.get(rule.action.group);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    groupedEligible.put(rule.action.group, bucket);
                }
                bucket.add(rule);
                if (rule.action.isStopOrExit()) {
                    hasStopOrExitEligible = true;
                }
            }
        }

        if (groupedEligible.isEmpty()) {
            return Collections.emptyList();
        }

        List<TriggerEvent> events = new ArrayList<>(groupedEligible.size());
        for (ActionGroup group : ActionGroup.values()) {
            List<VehicleTriggerRule> bucket = groupedEligible.get(group);
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            VehicleTriggerRule winner = arbitrateGroup(group, bucket, hasStopOrExitEligible);
            if (winner == null) {
                continue;
            }
            RuleRuntime winnerRuntime = runtimes.get(winner.id);
            if (winnerRuntime != null && !winnerRuntime.fired) {
                winnerRuntime.fired = true;
                events.add(new TriggerEvent(winner, winner.action));
            }
        }
        return events;
    }

    @Nullable
    private VehicleTriggerRule arbitrateGroup(@NonNull ActionGroup group,
                                              @NonNull List<VehicleTriggerRule> bucket,
                                              boolean hasStopOrExitEligible) {
        VehicleTriggerRule winner = null;
        for (VehicleTriggerRule candidate : bucket) {
            if (group == ActionGroup.RECORDING && hasStopOrExitEligible && candidate.action == RuleAction.START) {
                RuleRuntime startRuntime = runtimes.get(candidate.id);
                if (startRuntime != null) {
                    // 被压制期间清除 fired，并标记 suppressed 避免已到期的延迟规则反复唤醒 20ms 轮询
                    startRuntime.fired = false;
                    startRuntime.suppressed = true;
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
        if (winnerRuntime != null) {
            winnerRuntime.suppressed = false;
        }
        // 同域内胜出时，标记其余到期候选为 suppressed 避免每 20ms 重复调度；高优先级胜出时重置低优先级 fired 标记以便后续重触发
        for (VehicleTriggerRule candidate : bucket) {
            if (candidate != winner) {
                RuleRuntime suppressed = runtimes.get(candidate.id);
                if (suppressed != null) {
                    suppressed.suppressed = true;
                    if (candidate.action.priority < winner.action.priority) {
                        suppressed.fired = false;
                    }
                }
            }
        }
        return winner;
    }

    /**
     * 评估输入信号快照，返回本轮边沿触发的单个代表动作（优先返回 RECORDING 域事件，兼容旧调用方）。
     */
    @Nullable
    public synchronized TriggerEvent evaluate(@Nullable VehicleSignalSnapshot snapshot, long nowMs) {
        return pickPrimaryEvent(evaluateAll(snapshot, nowMs));
    }

    /**
     * 使用最近一次快照按最新时间重新推进延迟计时规则（返回各动作域到期的全部新触发事件）。
     */
    @NonNull
    public synchronized List<TriggerEvent> tickAll(long nowMs) {
        if (lastSnapshot == null) {
            return Collections.emptyList();
        }
        return evaluateAll(lastSnapshot, nowMs);
    }

    /**
     * 使用最近一次快照按最新时间重新推进延迟计时规则（用于 durationSeconds > 0 到期时无新快照的场景）。
     */
    @Nullable
    public synchronized TriggerEvent tick(long nowMs) {
        return pickPrimaryEvent(tickAll(nowMs));
    }

    @Nullable
    private static TriggerEvent pickPrimaryEvent(@NonNull List<TriggerEvent> events) {
        if (events.isEmpty()) {
            return null;
        }
        for (TriggerEvent event : events) {
            if (event.action.group == ActionGroup.RECORDING) {
                return event;
            }
        }
        return events.get(0);
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
            if (runtime == null || !runtime.matching || runtime.fired || runtime.suppressed) {
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
