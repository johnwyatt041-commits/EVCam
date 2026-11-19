package com.kooo.evcam.vehicle.rule;

import com.kooo.evcam.vehicle.VehicleSignalCategory;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 T06 车辆状态规则引擎：SC 默认 2 条规则、多规则仲裁、AND/OR 组合条件、持续延迟防抖、
 * UNKNOWN 信号安全抑制与 4 类自定义属性 ID 映射。
 */
public class VehicleRuleEngineTest {

    @After
    public void tearDown() {
        VehicleSignalCategory.resetCustomPropertyIds();
    }

    private static VehicleSignalSnapshot snap(VehicleSignalSnapshot.Gear gear,
                                              int speedKmh,
                                              int fl, int fr, int rl, int rr,
                                              int leftTurn, int rightTurn) {
        return new VehicleSignalSnapshot(gear, speedKmh, fl, fr, rl, rr, leftTurn, rightTurn, 1000L);
    }

    @Test
    public void unknownSignalsNeverTriggerDefaultGearStartOrDoorStop() {
        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(VehicleTriggerRule.createDefaultRules());

        assertNull(engine.evaluate(VehicleSignalSnapshot.UNKNOWN, 1000L));
        // 仅车速已知、档位与车门未知时不应误判为 != PARK 或开门
        assertNull(engine.evaluate(snap(VehicleSignalSnapshot.Gear.UNKNOWN, 0, -1, -1, -1, -1, 0, 0), 2000L));
    }

    @Test
    public void defaultTwoRulesEdgeTriggerAndExitSuppressesStart() {
        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(VehicleTriggerRule.createDefaultRules());

        // P 挡关门：不触发
        assertNull(engine.evaluate(snap(VehicleSignalSnapshot.Gear.PARK, 0, 0, 0, 0, 0, 0, 0), 1000L));

        // 切到 D 挡（非 P 挡）-> 触发 default_gear_start (START)
        VehicleRuleEngine.TriggerEvent startEvent = engine.evaluate(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 12, 0, 0, 0, 0, 0, 0), 1200L);
        assertNotNull(startEvent);
        assertEquals(VehicleTriggerRule.DEFAULT_GEAR_START_ID, startEvent.rule.id);
        assertEquals(RuleAction.START, startEvent.action);

        // 保持 D 挡车速变化：边沿锁存，不重复触发 START
        assertNull(engine.evaluate(snap(VehicleSignalSnapshot.Gear.DRIVE, 35, 0, 0, 0, 0, 0, 0), 1500L));

        // 挂 D 挡时主驾车门突然打开：default_door_stop (EXIT_APP) 压制 START 并立即触发 EXIT_APP
        VehicleRuleEngine.TriggerEvent exitEvent = engine.evaluate(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 0, 1, 0, 0, 0, 0, 0), 2000L);
        assertNotNull(exitEvent);
        assertEquals(VehicleTriggerRule.DEFAULT_DOOR_STOP_ID, exitEvent.rule.id);
        assertEquals(RuleAction.EXIT_APP, exitEvent.action);

        // 先退回 P 挡再关门：不触发 START，之后再挂 R 挡才触发 START
        assertNull(engine.evaluate(snap(VehicleSignalSnapshot.Gear.PARK, 0, 0, 0, 0, 0, 0, 0), 2500L));
        VehicleRuleEngine.TriggerEvent restartEvent = engine.evaluate(
                snap(VehicleSignalSnapshot.Gear.REVERSE, 0, 0, 0, 0, 0, 0, 0), 3000L);
        assertNotNull(restartEvent);
        assertEquals(RuleAction.START, restartEvent.action);
    }

    @Test
    public void delayRuleRequiresSustainedMatchAndPriorityResolvesStopActions() {
        VehicleTriggerRule delayedStop = new VehicleTriggerRule(
                "r_speed_stop",
                "停车10秒停录",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.SPEED, RuleComparison.EQUALS, 0),
                Collections.singletonList(
                        new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_PARK)
                ),
                ConditionLogic.AND,
                3,
                RuleAction.STOP
        );
        VehicleTriggerRule doorRelease = new VehicleTriggerRule(
                "r_any_door_release",
                "任一车门打开释放",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.ANY_DOOR, RuleComparison.EQUALS, 1),
                null,
                ConditionLogic.OR,
                0,
                RuleAction.STOP_RELEASE
        );

        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(Arrays.asList(delayedStop, doorRelease));

        // 0ms: P 挡车速 0，刚进入等待 3 秒倒计时
        assertNull(engine.evaluate(snap(VehicleSignalSnapshot.Gear.PARK, 0, 0, 0, 0, 0, 0, 0), 1000L));
        assertEquals(3000L, engine.getNextDelayDueMs(1000L));

        // 2.5s 尚未满 3s
        assertNull(engine.tick(3500L));
        assertEquals(500L, engine.getNextDelayDueMs(3500L));

        // 满 3.0s (4000ms) 触发 STOP
        VehicleRuleEngine.TriggerEvent stopEvent = engine.tick(4000L);
        assertNotNull(stopEvent);
        assertEquals(RuleAction.STOP, stopEvent.action);

        // 此时右后门打开，高优先级 STOP_RELEASE 触发
        VehicleRuleEngine.TriggerEvent relEvent = engine.evaluate(
                snap(VehicleSignalSnapshot.Gear.PARK, 0, 0, 0, 0, 1, 0, 0), 4200L);
        assertNotNull(relEvent);
        assertEquals(RuleAction.STOP_RELEASE, relEvent.action);
    }

    @Test
    public void jsonRoundTripPreservesDefaultFallbackCustomRulesAndExplicitEmptyList() {
        List<VehicleTriggerRule> defaults = VehicleTriggerRule.fromJsonArray(null);
        assertEquals(2, defaults.size());
        assertEquals(VehicleTriggerRule.DEFAULT_DOOR_STOP_ID, defaults.get(0).id);
        assertEquals(VehicleTriggerRule.DEFAULT_GEAR_START_ID, defaults.get(1).id);

        assertTrue(VehicleTriggerRule.fromJsonArray("[]").isEmpty());

        VehicleTriggerRule custom = new VehicleTriggerRule(
                "custom_turn_or_speed",
                "左转或低速",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.TURN_SIGNAL, RuleComparison.EQUALS, VehicleRuleSignal.TURN_LEFT),
                Collections.singletonList(
                        new VehicleRuleCondition(VehicleRuleSignal.SPEED, RuleComparison.GREATER_OR_EQUAL, 15)
                ),
                ConditionLogic.OR,
                2,
                RuleAction.START
        );
        String json = VehicleTriggerRule.toJsonArray(Collections.singletonList(custom));
        List<VehicleTriggerRule> parsed = VehicleTriggerRule.fromJsonArray(json);
        assertEquals(1, parsed.size());
        VehicleTriggerRule roundTripped = parsed.get(0);
        assertEquals("custom_turn_or_speed", roundTripped.id);
        assertEquals(ConditionLogic.OR, roundTripped.conditionLogic);
        assertEquals(2, roundTripped.delaySeconds);
        assertEquals(RuleAction.START, roundTripped.action);
        assertEquals(1, roundTripped.additionalConditions.size());
        assertEquals(VehicleRuleSignal.SPEED, roundTripped.additionalConditions.get(0).signal);
        assertTrue(roundTripped.matches(snap(VehicleSignalSnapshot.Gear.DRIVE, 0, 0, 0, 0, 0, 1, 0)));
        assertFalse(roundTripped.matches(snap(VehicleSignalSnapshot.Gear.DRIVE, 10, 0, 0, 0, 0, 0, 1)));
    }

    @Test
    public void customFourCategoryPropertyIdsUpdateClassifierWithoutExpandingScope() {
        VehicleSignalCategory.setCustomPropertyIds(0x21400400, 0x21400401, 0x21600207, 0x26200b00, 0x21400408);
        assertEquals(VehicleSignalCategory.GEAR, VehicleSignalCategory.classifyPropertyId(0x21400400));
        assertEquals(VehicleSignalCategory.SPEED, VehicleSignalCategory.classifyPropertyId(0x21600207));
        assertEquals(VehicleSignalCategory.DOORS, VehicleSignalCategory.classifyPropertyId(0x26200b00));
        assertEquals(VehicleSignalCategory.TURN_SIGNALS, VehicleSignalCategory.classifyPropertyId(0x21400408));
        // 非 4 类属性依旧严格屏蔽
        assertNull(VehicleSignalCategory.classifyPropertyId(0x11600305));

        assertEquals(Integer.valueOf(0x11400400), VehicleSignalCategory.parsePropertyId("0x11400400"));
        assertNull(VehicleSignalCategory.parsePropertyId("not_hex"));
        assertNull(VehicleSignalCategory.parsePropertyId("0"));

        VehicleSignalCategory.resetCustomPropertyIds();
        assertEquals(VehicleSignalCategory.GEAR, VehicleSignalCategory.classifyPropertyId(VehicleSignalCategory.PROP_GEAR_SELECTION));
    }

    @Test
    public void guidanceRulesArbitrateIndependentlyWithoutSuppressingRecording() {
        VehicleTriggerRule gearStart = new VehicleTriggerRule(
                "r_rec_start",
                "挂前进档录制",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_DRIVE),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.START
        );
        VehicleTriggerRule lowSpeedGuidanceOn = new VehicleTriggerRule(
                "r_guide_on",
                "低速开启引导线",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.SPEED, RuleComparison.LESS_THAN, 20),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.ENABLE_GUIDANCE
        );
        VehicleTriggerRule highSpeedGuidanceOff = new VehicleTriggerRule(
                "r_guide_off",
                "高速关闭引导线",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.SPEED, RuleComparison.GREATER_OR_EQUAL, 25),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.DISABLE_GUIDANCE
        );

        assertFalse(RuleAction.ENABLE_GUIDANCE.isStopOrExit());
        assertFalse(RuleAction.DISABLE_GUIDANCE.isStopOrExit());
        assertTrue(RuleAction.ENABLE_GUIDANCE.isGuidanceAction());
        assertTrue(RuleAction.DISABLE_GUIDANCE.isGuidanceAction());

        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(Arrays.asList(gearStart, lowSpeedGuidanceOn, highSpeedGuidanceOff));

        // 挂 D 档 10km/h：同时触发 START（录制域）与 ENABLE_GUIDANCE（引导线域），互不压制
        List<VehicleRuleEngine.TriggerEvent> first = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 10, 0, 0, 0, 0, 0, 0), 1000L);
        assertEquals(2, first.size());
        assertEquals(RuleAction.START, first.get(0).action);
        assertEquals(RuleAction.ENABLE_GUIDANCE, first.get(1).action);

        // 提速到 30km/h：触发 DISABLE_GUIDANCE，录制保持不重触
        List<VehicleRuleEngine.TriggerEvent> fast = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 30, 0, 0, 0, 0, 0, 0), 2000L);
        assertEquals(1, fast.size());
        assertEquals(RuleAction.DISABLE_GUIDANCE, fast.get(0).action);

        // 回落到 12km/h：再次触发 ENABLE_GUIDANCE
        List<VehicleRuleEngine.TriggerEvent> slowAgain = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 12, 0, 0, 0, 0, 0, 0), 3000L);
        assertEquals(1, slowAgain.size());
        assertEquals(RuleAction.ENABLE_GUIDANCE, slowAgain.get(0).action);
    }

    @Test
    public void guidanceGroupHigherPriorityDisableSuppressesAndReArmsEnable() {
        VehicleTriggerRule enableByGear = new VehicleTriggerRule(
                "r_guide_gear",
                "D 档尝试开启引导线",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_DRIVE),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.ENABLE_GUIDANCE
        );
        VehicleTriggerRule disableOver20 = new VehicleTriggerRule(
                "r_guide_fast",
                "车速 >= 20 关闭引导线",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.SPEED, RuleComparison.GREATER_OR_EQUAL, 20),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.DISABLE_GUIDANCE
        );
        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(Arrays.asList(enableByGear, disableOver20));

        // 同时满足 D 档与 >=20km/h，DISABLE_GUIDANCE（priority 1）压制 ENABLE_GUIDANCE（priority 0）
        List<VehicleRuleEngine.TriggerEvent> fastStart = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 25, 0, 0, 0, 0, 0, 0), 1000L);
        assertEquals(1, fastStart.size());
        assertEquals(RuleAction.DISABLE_GUIDANCE, fastStart.get(0).action);

        // 车速降到 10km/h 但仍在 D 档：由于被压制期清零了 fired，立即恢复触发 ENABLE_GUIDANCE
        List<VehicleRuleEngine.TriggerEvent> slowed = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 10, 0, 0, 0, 0, 0, 0), 2000L);
        assertEquals(1, slowed.size());
        assertEquals(RuleAction.ENABLE_GUIDANCE, slowed.get(0).action);
    }
}
