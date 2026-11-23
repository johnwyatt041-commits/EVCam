package com.kooo.evcam.vehicle.rule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 车辆状态触发规则模型，含 SC 默认 2 条规则与 R8 安全的无反射 JSON 编解码。
 */
public final class VehicleTriggerRule {
    public static final String DEFAULT_DOOR_STOP_ID = "default_door_stop";
    public static final String DEFAULT_GEAR_START_ID = "default_gear_start";

    @NonNull
    public final String id;
    @NonNull
    public final String name;
    public final boolean enabled;
    @NonNull
    public final VehicleRuleCondition primaryCondition;
    @NonNull
    public final List<VehicleRuleCondition> additionalConditions;
    @NonNull
    public final ConditionLogic conditionLogic;
    public final int delaySeconds;
    @NonNull
    public final RuleAction action;

    public VehicleTriggerRule(@NonNull String id,
                              @NonNull String name,
                              boolean enabled,
                              @NonNull VehicleRuleCondition primaryCondition,
                              @Nullable List<VehicleRuleCondition> additionalConditions,
                              @NonNull ConditionLogic conditionLogic,
                              int delaySeconds,
                              @NonNull RuleAction action) {
        this.id = id.trim().isEmpty() ? generateId() : id.trim();
        this.name = name.trim();
        this.enabled = enabled;
        this.primaryCondition = primaryCondition;
        this.additionalConditions = additionalConditions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(additionalConditions));
        this.conditionLogic = conditionLogic;
        this.delaySeconds = Math.max(0, delaySeconds);
        this.action = action;
    }

    public VehicleTriggerRule withEnabled(boolean nextEnabled) {
        return new VehicleTriggerRule(
                id,
                name,
                nextEnabled,
                primaryCondition,
                additionalConditions,
                conditionLogic,
                delaySeconds,
                action
        );
    }

    public boolean isDefaultRule() {
        return DEFAULT_DOOR_STOP_ID.equals(id) || DEFAULT_GEAR_START_ID.equals(id);
    }

    /** 判断两条规则的评估语义（ID/启用/条件/逻辑/延迟/动作）是否一致，供配置重载时保留已锁存的运行状态。 */
    public boolean hasSameEvaluationDefinition(@Nullable VehicleTriggerRule other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        return enabled == other.enabled
                && delaySeconds == other.delaySeconds
                && action == other.action
                && conditionLogic == other.conditionLogic
                && id.equals(other.id)
                && primaryCondition.equals(other.primaryCondition)
                && additionalConditions.equals(other.additionalConditions);
    }

    public boolean matches(@Nullable VehicleSignalSnapshot snapshot) {
        if (!enabled || snapshot == null) {
            return false;
        }
        boolean firstMatch = primaryCondition.matches(snapshot);
        if (additionalConditions.isEmpty()) {
            return firstMatch;
        }
        if (conditionLogic == ConditionLogic.OR) {
            if (firstMatch) {
                return true;
            }
            for (VehicleRuleCondition extra : additionalConditions) {
                if (extra.matches(snapshot)) {
                    return true;
                }
            }
            return false;
        } else {
            if (!firstMatch) {
                return false;
            }
            for (VehicleRuleCondition extra : additionalConditions) {
                if (!extra.matches(snapshot)) {
                    return false;
                }
            }
            return true;
        }
    }

    @NonNull
    public String getDisplayName() {
        if (!name.isEmpty()) {
            return name;
        }
        return primaryCondition.toReadableText() + " → " + action.label;
    }

    @NonNull
    public String getConditionsSummary() {
        StringBuilder sb = new StringBuilder(primaryCondition.toReadableText());
        String joiner = conditionLogic == ConditionLogic.OR ? " OR " : " AND ";
        for (VehicleRuleCondition extra : additionalConditions) {
            sb.append(joiner).append(extra.toReadableText());
        }
        if (delaySeconds > 0) {
            sb.append("（持续 ").append(delaySeconds).append(" 秒）");
        }
        return sb.toString();
    }

    @NonNull
    public static String generateId() {
        return "rule_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * SC V3.0.56 Lgk1.k() 默认 2 条内置规则：
     * 1. 主驾开门 (DOOR_FL == 1) -> 停录释放并退后台
     * 2. 离开 P 挡 (GEAR != 4) -> 开始录制
     */
    @NonNull
    public static List<VehicleTriggerRule> createDefaultRules() {
        List<VehicleTriggerRule> defaults = new ArrayList<>(2);
        defaults.add(new VehicleTriggerRule(
                DEFAULT_DOOR_STOP_ID,
                "默认：主驾开门退到后台",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.DOOR_FL, RuleComparison.EQUALS, 1),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.EXIT_APP
        ));
        defaults.add(new VehicleTriggerRule(
                DEFAULT_GEAR_START_ID,
                "默认：离开 P 挡自动录制",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.NOT_EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.START
        ));
        return defaults;
    }

    @NonNull
    public static String toJsonArray(@Nullable List<VehicleTriggerRule> rules) {
        JsonArray array = new JsonArray();
        if (rules != null) {
            for (VehicleTriggerRule rule : rules) {
                if (rule != null) {
                    array.add(toJsonObject(rule));
                }
            }
        }
        return array.toString();
    }

    /**
     * 从持久化 JSON 解析规则列表；仅当 json 为 null 或不可解析时回退默认 2 条规则，空数组 [] 视为用户主动清空。
     */
    @NonNull
    public static List<VehicleTriggerRule> fromJsonArray(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return createDefaultRules();
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return createDefaultRules();
            }
            JsonArray array = root.getAsJsonArray();
            List<VehicleTriggerRule> parsed = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                if (element != null && element.isJsonObject()) {
                    VehicleTriggerRule rule = fromJsonObject(element.getAsJsonObject());
                    if (rule != null) {
                        parsed.add(rule);
                    }
                }
            }
            return parsed;
        } catch (Exception ignored) {
            return createDefaultRules();
        }
    }

    private static JsonObject toJsonObject(@NonNull VehicleTriggerRule rule) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", rule.id);
        obj.addProperty("name", rule.name);
        obj.addProperty("enabled", rule.enabled);
        obj.add("primary", conditionToJson(rule.primaryCondition));
        JsonArray extras = new JsonArray();
        for (VehicleRuleCondition extra : rule.additionalConditions) {
            extras.add(conditionToJson(extra));
        }
        obj.add("extras", extras);
        obj.addProperty("logic", rule.conditionLogic.name());
        obj.addProperty("delaySeconds", rule.delaySeconds);
        obj.addProperty("action", rule.action.name());
        return obj;
    }

    @Nullable
    private static VehicleTriggerRule fromJsonObject(@NonNull JsonObject obj) {
        if (!obj.has("primary") || !obj.get("primary").isJsonObject()) {
            return null;
        }
        VehicleRuleCondition primary = conditionFromJson(obj.getAsJsonObject("primary"));
        if (primary == null) {
            return null;
        }
        String id = getString(obj, "id", generateId());
        String name = getString(obj, "name", "");
        boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
        ConditionLogic logic = ConditionLogic.fromName(getString(obj, "logic", ConditionLogic.AND.name()));
        int delaySeconds = obj.has("delaySeconds") ? obj.get("delaySeconds").getAsInt() : 0;
        RuleAction action = RuleAction.fromName(getString(obj, "action", RuleAction.START.name()));

        List<VehicleRuleCondition> extras = new ArrayList<>();
        if (obj.has("extras") && obj.get("extras").isJsonArray()) {
            for (JsonElement element : obj.getAsJsonArray("extras")) {
                if (element != null && element.isJsonObject()) {
                    VehicleRuleCondition extra = conditionFromJson(element.getAsJsonObject());
                    if (extra != null) {
                        extras.add(extra);
                    }
                }
            }
        }
        return new VehicleTriggerRule(id, name, enabled, primary, extras, logic, delaySeconds, action);
    }

    private static JsonObject conditionToJson(@NonNull VehicleRuleCondition condition) {
        JsonObject obj = new JsonObject();
        obj.addProperty("signal", condition.signal.name());
        obj.addProperty("comparison", condition.comparison.name());
        obj.addProperty("expected", condition.expectedValue);
        return obj;
    }

    @Nullable
    private static VehicleRuleCondition conditionFromJson(@NonNull JsonObject obj) {
        if (!obj.has("signal")) {
            return null;
        }
        VehicleRuleSignal signal = VehicleRuleSignal.fromName(getString(obj, "signal", "GEAR"));
        RuleComparison comparison = RuleComparison.fromName(getString(obj, "comparison", "EQUALS"));
        int expected = obj.has("expected") ? obj.get("expected").getAsInt() : 0;
        return new VehicleRuleCondition(signal, comparison, expected);
    }

    private static String getString(@NonNull JsonObject obj, @NonNull String key, @NonNull String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        return obj.get(key).getAsString();
    }
}
