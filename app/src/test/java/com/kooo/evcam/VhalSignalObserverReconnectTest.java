package com.kooo.evcam;

import com.kooo.evcam.vehicle.VehicleGearStatePublisher;
import com.kooo.evcam.vehicle.VehicleSignalCategory;
import com.kooo.evcam.vehicle.VehicleSignalHub;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;
import com.kooo.evcam.vehicle.rule.ConditionLogic;
import com.kooo.evcam.vehicle.rule.RuleAction;
import com.kooo.evcam.vehicle.rule.RuleComparison;
import com.kooo.evcam.vehicle.rule.VehicleRuleCondition;
import com.kooo.evcam.vehicle.rule.VehicleRuleEngine;
import com.kooo.evcam.vehicle.rule.VehicleRuleSignal;
import com.kooo.evcam.vehicle.rule.VehicleTriggerRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VhalSignalObserverReconnectTest {

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int b = (int) (value & 127);
            value >>>= 7;
            out.write(value == 0 ? b : b | 128);
        } while (value != 0);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static byte[] field(int tag, long value) {
        return concat(varint(tag), varint(value));
    }

    private static byte[] msg(int tag, byte[] bytes) {
        return concat(field(tag, bytes.length), bytes);
    }

    private static byte[] gearBatch(int rawGearValue, long timestamp) {
        long zigzag = Integer.toUnsignedLong((rawGearValue << 1) ^ (rawGearValue >> 31));
        byte[] prop = concat(
                field(8, VehicleSignalCategory.PROP_CURRENT_GEAR),
                field(24, timestamp),
                field(80, 0),
                field(40, zigzag)
        );
        return msg(10, msg(10, prop));
    }

    @Before
    public void setUp() {
        VehicleSignalHub.getInstance().reset();
    }

    @After
    public void tearDown() {
        VehicleSignalHub.getInstance().reset();
    }

    @Test
    public void streamActivityRefreshesIdleWindowPastTwoMinutesWithoutReconnect() {
        VhalSignalObserver.ReconnectStateMachine sm = new VhalSignalObserver.ReconnectStateMachine();
        long t = 1_000L;

        assertEquals(VhalSignalObserver.ReconnectStateMachine.ConnectAction.NOTIFY_CONNECTED,
                sm.onConnectAttemptResult(true, t));
        sm.onStreamOpened(t);

        // 110 秒时收到新事件刷新活跃时间
        t += 110_000L;
        assertNull(sm.evaluateTimeout(t,
                VhalSignalObserver.STREAM_TIMEOUT_MS, VhalSignalObserver.SILENT_RECONNECT_GRACE_MS));
        sm.onStreamActivity(t);

        // 距离开流已达 130 秒，但距离上次活跃仅 20 秒，不应触发超时重连
        t += 20_000L;
        assertNull(sm.evaluateTimeout(t,
                VhalSignalObserver.STREAM_TIMEOUT_MS, VhalSignalObserver.SILENT_RECONNECT_GRACE_MS));
        assertEquals(100_000L, sm.computeWaitMs(t,
                VhalSignalObserver.STREAM_TIMEOUT_MS, VhalSignalObserver.SILENT_RECONNECT_GRACE_MS));

        // 再静默满 100 秒（距上次活跃 120 秒），才触发 IDLE_TIMEOUT
        t += 100_000L;
        assertEquals(VhalSignalObserver.ReconnectStateMachine.StreamEndReason.IDLE_TIMEOUT,
                sm.evaluateTimeout(t,
                        VhalSignalObserver.STREAM_TIMEOUT_MS, VhalSignalObserver.SILENT_RECONNECT_GRACE_MS));
    }

    @Test
    public void silentReconnectPreservesGearAndPreventsRuleReplayWhenSameGearResumes() {
        VhalSignalObserver.ReconnectStateMachine sm = new VhalSignalObserver.ReconnectStateMachine();
        VehicleSignalHub hub = VehicleSignalHub.getInstance();
        VehicleRuleEngine engine = new VehicleRuleEngine();
        VehicleTriggerRule gearStart = new VehicleTriggerRule(
                VehicleTriggerRule.DEFAULT_GEAR_START_ID,
                "非P档开始录制",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.NOT_EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.START
        );
        VehicleTriggerRule parkStop = new VehicleTriggerRule(
                "r_park_stop",
                "P档停录",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.STOP
        );
        engine.setRules(Arrays.asList(gearStart, parkStop));

        final long[] fakeNow = {1_000L};
        VehicleGearStatePublisher publisher = new VehicleGearStatePublisher(0L, () -> fakeNow[0], null);
        hub.addListener(publisher::onSnapshot);

        // 首次连接并进入 D 挡，触发一次 START 且确认 D 挡
        assertEquals(VhalSignalObserver.ReconnectStateMachine.ConnectAction.NOTIFY_CONNECTED,
                sm.onConnectAttemptResult(true, fakeNow[0]));
        sm.onStreamOpened(fakeNow[0]);
        sm.onStreamActivity(fakeNow[0]);
        hub.onRawBatch(gearBatch(0x0008, 100L), fakeNow[0]); // DRIVE

        List<VehicleRuleEngine.TriggerEvent> firstEvents = engine.evaluateAll(hub.getSnapshot(fakeNow[0]), fakeNow[0]);
        assertEquals(1, firstEvents.size());
        assertEquals(RuleAction.START, firstEvents.get(0).action);
        assertTrue(publisher.isDrivingConfirmed());
        assertEquals(VehicleSignalSnapshot.Gear.DRIVE, publisher.getStableGear());

        // 连续静默 120s 触发 IDLE_TIMEOUT -> 进入静默重连，不能复位 Hub 或通知断连
        fakeNow[0] += VhalSignalObserver.STREAM_TIMEOUT_MS;
        VhalSignalObserver.ReconnectStateMachine.StreamEndReason endReason = sm.evaluateTimeout(
                fakeNow[0], VhalSignalObserver.STREAM_TIMEOUT_MS, VhalSignalObserver.SILENT_RECONNECT_GRACE_MS);
        assertEquals(VhalSignalObserver.ReconnectStateMachine.StreamEndReason.IDLE_TIMEOUT, endReason);
        assertEquals(VhalSignalObserver.ReconnectStateMachine.DisconnectAction.SILENT_RECONNECT,
                sm.onStreamEnded(endReason, true));
        assertTrue(sm.isSilentReconnecting());

        // 重连通道建立期间，不发通知，挡位保持 DRIVE，规则和 Publisher 均不抖动
        assertEquals(VehicleSignalSnapshot.Gear.DRIVE, hub.getSnapshot(fakeNow[0]).gear);
        assertTrue(publisher.isDrivingConfirmed());
        assertTrue(engine.evaluateAll(hub.getSnapshot(fakeNow[0]), fakeNow[0]).isEmpty());

        assertEquals(VhalSignalObserver.ReconnectStateMachine.ConnectAction.NONE,
                sm.onConnectAttemptResult(true, fakeNow[0]));
        sm.onStreamOpened(fakeNow[0]);
        sm.onStreamActivity(fakeNow[0]);
        assertFalse(sm.isSilentReconnecting());

        // 新流推送当前原挡位 DRIVE：规则不重复触发 START，Publisher 保持 D 挡确认态
        fakeNow[0] += 200L;
        hub.onRawBatch(gearBatch(0x0008, 200L), fakeNow[0]);
        assertTrue(engine.evaluateAll(hub.getSnapshot(fakeNow[0]), fakeNow[0]).isEmpty());
        assertEquals(VehicleSignalSnapshot.Gear.DRIVE, publisher.getStableGear());
        assertTrue(publisher.isDrivingConfirmed());
    }

    @Test
    public void genuineGearChangeDuringSilentReconnectStillTriggersRuleOnce() {
        VhalSignalObserver.ReconnectStateMachine sm = new VhalSignalObserver.ReconnectStateMachine();
        VehicleSignalHub hub = VehicleSignalHub.getInstance();
        VehicleRuleEngine engine = new VehicleRuleEngine();
        engine.setRules(Collections.singletonList(new VehicleTriggerRule(
                VehicleTriggerRule.DEFAULT_GEAR_START_ID,
                "非P档开始录制",
                true,
                new VehicleRuleCondition(VehicleRuleSignal.GEAR, RuleComparison.NOT_EQUALS, VehicleRuleSignal.GEAR_PARK),
                null,
                ConditionLogic.AND,
                0,
                RuleAction.START
        )));

        long t = 1_000L;
        sm.onConnectAttemptResult(true, t);
        sm.onStreamOpened(t);
        sm.onStreamActivity(t);
        hub.onRawBatch(gearBatch(0x0004, 100L), t); // PARK
        assertTrue(engine.evaluateAll(hub.getSnapshot(t), t).isEmpty());

        // 120s 静默重连
        t += VhalSignalObserver.STREAM_TIMEOUT_MS;
        assertEquals(VhalSignalObserver.ReconnectStateMachine.DisconnectAction.SILENT_RECONNECT,
                sm.onStreamEnded(VhalSignalObserver.ReconnectStateMachine.StreamEndReason.IDLE_TIMEOUT, true));
        assertEquals(VhalSignalObserver.ReconnectStateMachine.ConnectAction.NONE,
                sm.onConnectAttemptResult(true, t));
        sm.onStreamOpened(t);

        // 重连后首帧变成真实 D 挡 -> 立即正常触发一次 START
        t += 100L;
        sm.onStreamActivity(t);
        hub.onRawBatch(gearBatch(0x0008, 200L), t);
        List<VehicleRuleEngine.TriggerEvent> events = engine.evaluateAll(hub.getSnapshot(t), t);
        assertEquals(1, events.size());
        assertEquals(RuleAction.START, events.get(0).action);
    }

    @Test
    public void silentReconnectFailuresFallBackToDisconnectedAndResetToUnknown() {
        VehicleSignalHub hub = VehicleSignalHub.getInstance();

        // 情况 1：静默重连后连接建立直接失败
        VhalSignalObserver.ReconnectStateMachine smConnectFail = new VhalSignalObserver.ReconnectStateMachine();
        long t = 1_000L;
        smConnectFail.onConnectAttemptResult(true, t);
        smConnectFail.onStreamOpened(t);
        hub.onRawBatch(gearBatch(0x0008, 100L), t);
        assertEquals(VhalSignalObserver.ReconnectStateMachine.DisconnectAction.SILENT_RECONNECT,
                smConnectFail.onStreamEnded(VhalSignalObserver.ReconnectStateMachine.StreamEndReason.IDLE_TIMEOUT, true));
        assertEquals(VhalSignalObserver.ReconnectStateMachine.ConnectAction.NOTIFY_DISCONNECTED_AND_RESET,
                smConnectFail.onConnectAttemptResult(false, t + 100L));
        hub.reset();
        assertEquals(VehicleSignalSnapshot.Gear.UNKNOWN, hub.getSnapshot(t + 100L).gear);

        // 情况 2：静默重连建链后宽限期(10s)内既无 SendAll 成功也无事件到达 -> RECONNECT_TIMEOUT -> 重置 UNKNOWN
        VhalSignalObserver.ReconnectStateMachine smGraceTimeout = new VhalSignalObserver.ReconnectStateMachine();
        t = 1_000L;
        smGraceTimeout.onConnectAttemptResult(true, t);
        smGraceTimeout.onStreamOpened(t);
        smGraceTimeout.onStreamActivity(t);
        hub.onRawBatch(gearBatch(0x0008, 150L), t);
        assertEquals(VhalSignalObserver.ReconnectStateMachine.DisconnectAction.SILENT_RECONNECT,
                smGraceTimeout.onStreamEnded(VhalSignalObserver.ReconnectStateMachine.StreamEndReason.IDLE_TIMEOUT, true));
        assertEquals(VhalSignalObserver.ReconnectStateMachine.ConnectAction.NONE,
                smGraceTimeout.onConnectAttemptResult(true, t + VhalSignalObserver.STREAM_TIMEOUT_MS));
        long reopenAt = t + VhalSignalObserver.STREAM_TIMEOUT_MS;
        smGraceTimeout.onStreamOpened(reopenAt);
        assertNull(smGraceTimeout.evaluateTimeout(
                reopenAt + VhalSignalObserver.SILENT_RECONNECT_GRACE_MS - 1L,
                VhalSignalObserver.STREAM_TIMEOUT_MS,
                VhalSignalObserver.SILENT_RECONNECT_GRACE_MS));
        VhalSignalObserver.ReconnectStateMachine.StreamEndReason graceReason = smGraceTimeout.evaluateTimeout(
                reopenAt + VhalSignalObserver.SILENT_RECONNECT_GRACE_MS,
                VhalSignalObserver.STREAM_TIMEOUT_MS,
                VhalSignalObserver.SILENT_RECONNECT_GRACE_MS);
        assertEquals(VhalSignalObserver.ReconnectStateMachine.StreamEndReason.RECONNECT_TIMEOUT, graceReason);
        assertEquals(VhalSignalObserver.ReconnectStateMachine.DisconnectAction.NOTIFY_DISCONNECTED_AND_RESET,
                smGraceTimeout.onStreamEnded(graceReason, true));
        hub.reset();
        assertEquals(VehicleSignalSnapshot.Gear.UNKNOWN, hub.getSnapshot(reopenAt + 10_000L).gear);

        // 情况 3：静默重连后新流收到 STREAM_ERROR -> 立即重置 UNKNOWN
        VhalSignalObserver.ReconnectStateMachine smStreamErr = new VhalSignalObserver.ReconnectStateMachine();
        smStreamErr.onConnectAttemptResult(true, 1000L);
        smStreamErr.onStreamOpened(1000L);
        smStreamErr.onStreamEnded(VhalSignalObserver.ReconnectStateMachine.StreamEndReason.IDLE_TIMEOUT, true);
        smStreamErr.onConnectAttemptResult(true, 121_000L);
        smStreamErr.onStreamOpened(121_000L);
        assertEquals(VhalSignalObserver.ReconnectStateMachine.DisconnectAction.NOTIFY_DISCONNECTED_AND_RESET,
                smStreamErr.onStreamEnded(VhalSignalObserver.ReconnectStateMachine.StreamEndReason.STREAM_ERROR, true));
    }

    @Test
    public void regularStreamFailureOrExplicitStopResetsImmediately() {
        VhalSignalObserver.ReconnectStateMachine sm = new VhalSignalObserver.ReconnectStateMachine();
        sm.onConnectAttemptResult(true, 1_000L);
        sm.onStreamOpened(1_000L);
        assertEquals(VhalSignalObserver.ReconnectStateMachine.DisconnectAction.NOTIFY_DISCONNECTED_AND_RESET,
                sm.onStreamEnded(VhalSignalObserver.ReconnectStateMachine.StreamEndReason.STREAM_COMPLETED, true));

        sm.reset();
        sm.onConnectAttemptResult(true, 2_000L);
        sm.onStreamOpened(2_000L);
        sm.onStreamEnded(VhalSignalObserver.ReconnectStateMachine.StreamEndReason.IDLE_TIMEOUT, true);
        assertTrue(sm.isSilentReconnecting());
        // 静默重连过渡期间外部显式 stop()
        assertEquals(VhalSignalObserver.ReconnectStateMachine.DisconnectAction.NOTIFY_DISCONNECTED_AND_RESET,
                sm.onStreamEnded(VhalSignalObserver.ReconnectStateMachine.StreamEndReason.STOPPED, false));
        assertFalse(sm.isSilentReconnecting());
        assertFalse(sm.isConnected());
    }
}
