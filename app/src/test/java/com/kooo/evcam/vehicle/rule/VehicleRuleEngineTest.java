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

    @Test
    public void suppressedExpiredDelayedStartClearsPendingDueTimerAndReFiresWhenSuppressionClears() {
        VehicleTriggerRule doorExit = new VehicleTriggerRule(
                "r_door_exit",
                "开门退出",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.DOOR_FL, RuleComparison.EQUALS, 1),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.EXIT_APP
        );
        VehicleTriggerRule delayedStart = new VehicleTriggerRule(
                "r_delayed_start",
                "挂 D 档延迟 3 秒录制",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_DRIVE),
                null,
                ConditionLogic.AND,
                3,
                RuleAction.START
        );
        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(Arrays.asList(doorExit, delayedStart));

        // 1000ms：开着主驾门挂 D 档，立即触发 EXIT_APP，START 进入 3 秒倒计时
        List<VehicleRuleEngine.TriggerEvent> initial = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 0, 1, 0, 0, 0, 0, 0), 1000L);
        assertEquals(1, initial.size());
        assertEquals(RuleAction.EXIT_APP, initial.get(0).action);
        assertEquals(3000L, engine.getNextDelayDueMs(1000L));

        // 4000ms：门仍未关，delayedStart 已到期但被 EXIT_APP 压制
        assertTrue(engine.tickAll(4000L).isEmpty());
        // 关键：不能再返回 0，必须返回 -1，避免 20ms 忙等循环
        assertEquals(-1L, engine.getNextDelayDueMs(4000L));

        // 车速等无关变化或持续 tick 期间依然保持 -1，直到门关闭
        assertTrue(engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 1, 1, 0, 0, 0, 0, 0), 4500L).isEmpty());
        assertEquals(-1L, engine.getNextDelayDueMs(4500L));

        // 关门后门锁解除压制，由于已满 3 秒立即恢复触发 START
        List<VehicleRuleEngine.TriggerEvent> recovered = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 5, 0, 0, 0, 0, 0, 0), 5000L);
        assertEquals(1, recovered.size());
        assertEquals(RuleAction.START, recovered.get(0).action);
        assertEquals(-1L, engine.getNextDelayDueMs(5000L));
    }

    @Test
    public void higherPriorityDelayedRuleSuppressesLowerPriorityDelayedRuleWithoutBusyLoop() {
        VehicleTriggerRule delayedStop3s = new VehicleTriggerRule(
                "r_stop_3s",
                "停车3秒停录",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                3,
                RuleAction.STOP
        );
        VehicleTriggerRule delayedRelease5s = new VehicleTriggerRule(
                "r_release_5s",
                "停车5秒释放",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                5,
                RuleAction.STOP_RELEASE
        );
        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(Arrays.asList(delayedStop3s, delayedRelease5s));

        assertTrue(engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.PARK, 0, 0, 0, 0, 0, 0, 0), 1000L).isEmpty());
        assertEquals(3000L, engine.getNextDelayDueMs(1000L));

        // 4000ms：STOP 到期先触发，此时还剩 STOP_RELEASE 在 6000ms 到期（2000ms 后）
        List<VehicleRuleEngine.TriggerEvent> at4s = engine.tickAll(4000L);
        assertEquals(1, at4s.size());
        assertEquals(RuleAction.STOP, at4s.get(0).action);
        assertEquals(2000L, engine.getNextDelayDueMs(4000L));

        // 6000ms：STOP_RELEASE 胜出并把 STOP 的 fired 清除；STOP 虽未 fired 且已到期，但已被压制，不得再唤醒 20ms 轮询
        List<VehicleRuleEngine.TriggerEvent> at6s = engine.tickAll(6000L);
        assertEquals(1, at6s.size());
        assertEquals(RuleAction.STOP_RELEASE, at6s.get(0).action);
        assertEquals(-1L, engine.getNextDelayDueMs(6000L));
    }

    @Test
    public void equalPriorityExpiredDelayedRulesDoNotSpinDelayScheduler() {
        VehicleTriggerRule stopA = new VehicleTriggerRule(
                "r_stop_a",
                "规则A",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                2,
                RuleAction.STOP
        );
        VehicleTriggerRule stopB = new VehicleTriggerRule(
                "r_stop_b",
                "规则B",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.SPEED, RuleComparison.EQUALS, 0),
                null,
                ConditionLogic.AND,
                2,
                RuleAction.STOP
        );
        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(Arrays.asList(stopA, stopB));

        assertTrue(engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.PARK, 0, 0, 0, 0, 0, 0, 0), 1000L).isEmpty());
        assertEquals(2000L, engine.getNextDelayDueMs(1000L));

        List<VehicleRuleEngine.TriggerEvent> at3s = engine.tickAll(3000L);
        assertEquals(1, at3s.size());
        assertEquals(RuleAction.STOP, at3s.get(0).action);
        assertEquals(-1L, engine.getNextDelayDueMs(3000L));
    }

    @Test
    public void guidanceGroupSuppressedExpiredDelayClearsTimerUntilUnblocked() {
        VehicleTriggerRule delayedEnable = new VehicleTriggerRule(
                "r_guide_enable_2s",
                "低速2秒开引导线",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.SPEED, RuleComparison.LESS_THAN, 30),
                null,
                ConditionLogic.AND,
                2,
                RuleAction.ENABLE_GUIDANCE
        );
        VehicleTriggerRule highGearDisable = new VehicleTriggerRule(
                "r_guide_disable_park",
                "P档关引导线",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.DISABLE_GUIDANCE
        );
        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(Arrays.asList(delayedEnable, highGearDisable));

        List<VehicleRuleEngine.TriggerEvent> first = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.PARK, 0, 0, 0, 0, 0, 0, 0), 1000L);
        assertEquals(1, first.size());
        assertEquals(RuleAction.DISABLE_GUIDANCE, first.get(0).action);
        assertEquals(2000L, engine.getNextDelayDueMs(1000L));

        assertTrue(engine.tickAll(3000L).isEmpty());
        assertEquals(-1L, engine.getNextDelayDueMs(3000L));

        // 切出 P 档后，被压制的 ENABLE_GUIDANCE 立即生效
        List<VehicleRuleEngine.TriggerEvent> drive = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 10, 0, 0, 0, 0, 0, 0), 3500L);
        assertEquals(1, drive.size());
        assertEquals(RuleAction.ENABLE_GUIDANCE, drive.get(0).action);
        assertEquals(-1L, engine.getNextDelayDueMs(3500L));
    }

    @Test
    public void setRulesWithIdenticalDefinitionsPreservesLatchedRuntimeState() {
        VehicleTriggerRule delayedStop = new VehicleTriggerRule(
                "r_delayed_stop",
                "停车3秒停录",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                3,
                RuleAction.STOP
        );
        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(Arrays.asList(
                VehicleTriggerRule.createDefaultRules().get(1),
                delayedStop
        ));

        // 切入 D 挡触发一次 START，并锁存 fired=true
        List<VehicleRuleEngine.TriggerEvent> first = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 5, 0, 0, 0, 0, 0, 0), 1000L);
        assertEquals(1, first.size());
        assertEquals(RuleAction.START, first.get(0).action);

        // 切入 P 挡后启动 3 秒倒计时（已走 1 秒）
        assertTrue(engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.PARK, 0, 0, 0, 0, 0, 0, 0), 2000L).isEmpty());
        assertEquals(3000L, engine.getNextDelayDueMs(2000L));
        assertTrue(engine.tickAll(3000L).isEmpty());
        assertEquals(2000L, engine.getNextDelayDueMs(3000L));

        // 模拟切换预览模式触发 startOrReload() 重新传入语义相同的新规则实例列表
        VehicleTriggerRule reloadedGearStart = VehicleTriggerRule.fromJsonArray(
                VehicleTriggerRule.toJsonArray(VehicleTriggerRule.createDefaultRules())).get(1);
        VehicleTriggerRule reloadedDelayedStop = new VehicleTriggerRule(
                "r_delayed_stop",
                "停车3秒停录",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                3,
                RuleAction.STOP
        );
        engine.setRules(Arrays.asList(reloadedGearStart, reloadedDelayedStop));

        // 倒计时剩余 2000ms 未被清零重新算 3000ms
        assertEquals(2000L, engine.getNextDelayDueMs(3000L));
        List<VehicleRuleEngine.TriggerEvent> stopAt5s = engine.tickAll(5000L);
        assertEquals(1, stopAt5s.size());
        assertEquals(RuleAction.STOP, stopAt5s.get(0).action);

        // 重新切回 D 挡之前如果原本就在 D 挡（或继续在 D 挡收到心跳）：未改规则不得在相同状态下重复触发 START
        engine.setRules(Arrays.asList(reloadedGearStart, reloadedDelayedStop));
        List<VehicleRuleEngine.TriggerEvent> driveFirst = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 10, 0, 0, 0, 0, 0, 0), 6000L);
        assertEquals(1, driveFirst.size());
        assertEquals(RuleAction.START, driveFirst.get(0).action);

        // 在 D 挡再次调用 setRules（如切换预览模式）并收到 D 挡快照 -> 不得重复触发 START
        engine.setRules(Arrays.asList(VehicleTriggerRule.createDefaultRules().get(1), reloadedDelayedStop));
        assertTrue(engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 12, 0, 0, 0, 0, 0, 0), 6500L).isEmpty());
    }

    @Test
    public void setRulesResetsOnlyChangedOrRemovedRules() {
        VehicleTriggerRule gearStart = VehicleTriggerRule.createDefaultRules().get(1);
        VehicleTriggerRule delayedStop3s = new VehicleTriggerRule(
                "r_delayed_stop",
                "停车3秒停录",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                3,
                RuleAction.STOP
        );
        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(Arrays.asList(gearStart, delayedStop3s));

        List<VehicleRuleEngine.TriggerEvent> start = engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 15, 0, 0, 0, 0, 0, 0), 1000L);
        assertEquals(1, start.size());

        // 修改 delayedStop 延迟为 5s，保留 gearStart 不变
        VehicleTriggerRule modifiedStop5s = new VehicleTriggerRule(
                "r_delayed_stop",
                "停车5秒停录",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                5,
                RuleAction.STOP
        );
        engine.setRules(Arrays.asList(
                VehicleTriggerRule.createDefaultRules().get(1),
                modifiedStop5s
        ));

        // 未改动的 gearStart 仍锁存，不重复触发
        assertTrue(engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.DRIVE, 20, 0, 0, 0, 0, 0, 0), 1500L).isEmpty());

        // 新延迟 5s 按新规则生效
        assertTrue(engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.PARK, 0, 0, 0, 0, 0, 0, 0), 2000L).isEmpty());
        assertEquals(5000L, engine.getNextDelayDueMs(2000L));
        assertTrue(engine.tickAll(5500L).isEmpty());
        List<VehicleRuleEngine.TriggerEvent> at7s = engine.tickAll(7000L);
        assertEquals(1, at7s.size());
        assertEquals(RuleAction.STOP, at7s.get(0).action);

        // 移除 r_delayed_stop 并再加回后，新实例从未触发态重新开始
        engine.setRules(Collections.singletonList(gearStart));
        engine.setRules(Arrays.asList(gearStart, modifiedStop5s));
        assertTrue(engine.evaluateAll(
                snap(VehicleSignalSnapshot.Gear.PARK, 0, 0, 0, 0, 0, 0, 0), 8000L).isEmpty());
        assertEquals(5000L, engine.getNextDelayDueMs(8000L));
    }
}
