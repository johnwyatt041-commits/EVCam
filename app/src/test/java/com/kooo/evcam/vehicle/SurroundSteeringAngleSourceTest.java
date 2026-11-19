package com.kooo.evcam.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.VhalSignalObserver;
import com.kooo.evcam.vehicle.SurroundSteeringAngleSource.ApvpSignalValue;
import com.kooo.evcam.vehicle.SurroundSteeringAngleSource.ApvpWireCodec;
import com.kooo.evcam.vehicle.SurroundSteeringAngleSource.Decoder;
import com.kooo.evcam.vehicle.SurroundSteeringAngleSource.SignalRef;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class SurroundSteeringAngleSourceTest {

    @Test
    public void pinionRadAndVhalDegConversionsMatchE5SignAndBounds() {
        assertEquals(0f, SurroundSteeringAngleSource.pinionRadToWheelAngleDeg(0.0), 0.001f);
        assertEquals(0f, SurroundSteeringAngleSource.vhalWheelDegToRoadAngleDeg(0.0), 0.001f);

        // APVP: 左转正弧度 -> 负等效前轮角；右转负弧度 -> 正等效前轮角
        Float leftFromRad = SurroundSteeringAngleSource.pinionRadToWheelAngleDeg(Math.toRadians(360.0));
        Float rightFromRad = SurroundSteeringAngleSource.pinionRadToWheelAngleDeg(Math.toRadians(-360.0));
        assertNotNull(leftFromRad);
        assertNotNull(rightFromRad);
        assertTrue(leftFromRad < -18f && leftFromRad > -30f);
        assertTrue(rightFromRad > 18f && rightFromRad < 30f);

        // VHAL 0x11600209: 顺时针/右转为正 -> 正等效前轮角
        Float rightFromVhal = SurroundSteeringAngleSource.vhalWheelDegToRoadAngleDeg(360.0);
        Float leftFromVhal = SurroundSteeringAngleSource.vhalWheelDegToRoadAngleDeg(-360.0);
        assertNotNull(rightFromVhal);
        assertNotNull(leftFromVhal);
        assertEquals(rightFromRad, rightFromVhal, 0.01f);
        assertEquals(leftFromRad, leftFromVhal, 0.01f);

        // 非法值/越界拒绝
        assertNull(SurroundSteeringAngleSource.pinionRadToWheelAngleDeg(Double.NaN));
        assertNull(SurroundSteeringAngleSource.pinionRadToWheelAngleDeg(15.0));
        assertNull(SurroundSteeringAngleSource.pinionRadToWheelAngleDeg(-15.0));
        assertNull(SurroundSteeringAngleSource.vhalWheelDegToRoadAngleDeg(900.0));
    }

    @Test
    public void backgroundSignalWhitelistRemainsStrictlyFourCategories() {
        assertEquals(4, VehicleSignalCategory.values().length);
        assertNull(VehicleSignalCategory.classifyPropertyId(SurroundSteeringAngleSource.PROP_PERF_STEERING_ANGLE));
    }

    @Test
    public void decoderParsesVhalPerfSteeringAngleAndRespectsAreaAndTimestamp() {
        Decoder decoder = new Decoder();
        byte[] valid = encodeVhalBatch(SurroundSteeringAngleSource.PROP_PERF_STEERING_ANGLE, 0, 0, 200L, 450.0f);
        Float angle = decoder.acceptVhalBatch(valid, 1000L);
        assertNotNull(angle);
        assertTrue(angle > 25f && angle <= 45f);

        // 非全局 areaId 或 unavailable 或乱序旧时间戳必须被忽略
        assertNull(decoder.acceptVhalBatch(
                encodeVhalBatch(SurroundSteeringAngleSource.PROP_PERF_STEERING_ANGLE, 1, 0, 300L, -300f), 1100L));
        assertNull(decoder.acceptVhalBatch(
                encodeVhalBatch(SurroundSteeringAngleSource.PROP_PERF_STEERING_ANGLE, 0, 1, 300L, -300f), 1100L));
        assertNull(decoder.acceptVhalBatch(
                encodeVhalBatch(SurroundSteeringAngleSource.PROP_PERF_STEERING_ANGLE, 0, 0, 100L, -300f), 1100L));
    }

    @Test
    public void apvpWireCodecAndQualityGatingWorkEndToEnd() {
        byte[] transferListResp = wrapField(1, 0, 7, null);
        List<Integer> transfers = ApvpWireCodec.parseAllTransferIds(transferListResp);
        assertEquals(Arrays.asList(7), transfers);

        byte[] reqCfg = ApvpWireCodec.buildTransferConfigRequest(7);
        assertTrue(reqCfg.length >= 2);

        byte[] meta = concat(
                wrapField(1, 0, 101, null),
                wrapField(2, 2, 0, SurroundSteeringAngleSource.SIG_PINION_STEER_AG.getBytes(StandardCharsets.UTF_8))
        );
        SignalRef parsedMeta = ApvpWireCodec.parseTransferConfigItem(wrapField(1, 2, 0, meta));
        assertNotNull(parsedMeta);
        assertEquals(101, parsedMeta.id);
        assertEquals(SurroundSteeringAngleSource.SIG_PINION_STEER_AG, parsedMeta.name);

        byte[] subReq = ApvpWireCodec.buildSubscribeStreamRequest(Arrays.asList(
                new SignalRef(101, SurroundSteeringAngleSource.SIG_PINION_STEER_AG),
                new SignalRef(102, SurroundSteeringAngleSource.SIG_PINION_STEER_QF),
                new SignalRef(103, SurroundSteeringAngleSource.SIG_PINION_TIMEOUT)
        ));
        assertTrue(new String(subReq, StandardCharsets.UTF_8).contains(SurroundSteeringAngleSource.SIG_PINION_STEER_AG));

        Decoder decoder = new Decoder();
        decoder.configureApvpExpectations(true, true);

        // QF != 3 时丢弃
        byte[] badQfPayload = buildApvpStreamFrame(
                (float) Math.toRadians(-360.0), 2, 0
        );
        assertNull(decoder.acceptApvpStreamPayload(badQfPayload, 2000L));

        // Timeout != 0 时丢弃
        byte[] timeoutPayload = buildApvpStreamFrame(
                (float) Math.toRadians(-360.0), 3, 1
        );
        assertNull(decoder.acceptApvpStreamPayload(timeoutPayload, 2100L));

        // QF == 3 且 Timeout == 0 时正常输出等效车轮角
        byte[] goodPayload = buildApvpStreamFrame(
                (float) Math.toRadians(-360.0), 3, 0
        );
        List<ApvpSignalValue> decodedList = ApvpWireCodec.parseStreamSignalUpdates(goodPayload);
        assertEquals(3, decodedList.size());
        Float apvpAngle = decoder.acceptApvpStreamPayload(goodPayload, 2200L);
        assertNotNull(apvpAngle);
        assertTrue(apvpAngle > 18f && apvpAngle < 30f);

        // APVP 新鲜期内优先使用 APVP，屏蔽 VHAL 同窗口反向抖动
        assertNull(decoder.acceptVhalBatch(
                encodeVhalBatch(SurroundSteeringAngleSource.PROP_PERF_STEERING_ANGLE, 0, 0, 500L, -500f), 2500L));
    }

    @Test
    public void apvpUsesDebugPort40007ParsesNestedTransferIdsAndHandlesIdOnlyAndReadSignal() {
        assertEquals(40005, SurroundSteeringAngleSource.APVP_PORT);
        assertEquals(40007, SurroundSteeringAngleSource.APVP_DEBUG_PORT);

        // E5 TransferDebugServer/getAllTransfer 实际嵌套格式: repeated field 1 (wireType=2) -> inner field 1 (id)
        byte[] nestedTransfers = concat(
                wrapField(1, 2, 0, wrapField(1, 0, 0, null)),
                wrapField(1, 2, 0, wrapField(1, 0, 2, null)),
                wrapField(1, 2, 0, wrapField(1, 0, 5, null)),
                wrapField(1, 2, 0, wrapField(1, 0, 2, null))
        );
        assertEquals(Arrays.asList(2, 5), ApvpWireCodec.parseAllTransferIds(nestedTransfers));

        SignalRef steerRef = new SignalRef(101, SurroundSteeringAngleSource.SIG_PINION_STEER_AG);
        SignalRef qfRef = new SignalRef(102, SurroundSteeringAngleSource.SIG_PINION_STEER_QF);
        SignalRef timeoutRef = new SignalRef(103, SurroundSteeringAngleSource.SIG_PINION_TIMEOUT);
        byte[] keyReq = ApvpWireCodec.buildSignalKeyRequest(steerRef);
        assertTrue(new String(keyReq, StandardCharsets.UTF_8).contains(SurroundSteeringAngleSource.SIG_PINION_STEER_AG));

        Decoder decoder = new Decoder();
        decoder.registerApvpSignals(Arrays.asList(steerRef, qfRef, timeoutRef));

        // 1) 通过 readSignal 初始化 Qf=3, Timeout=0，并在静默超过 3.5 秒（20s 后）收到仅含 id 的流帧仍可正常输出
        byte[] qfSingle = concat(wrapField(1, 2, 0, wrapField(1, 0, 102, null)), wrapField(3, 0, zigzag(3), null));
        byte[] toSingle = concat(wrapField(1, 2, 0, wrapField(1, 0, 103, null)), wrapField(3, 0, zigzag(0), null));
        assertNull(decoder.acceptApvpSinglePayload(qfSingle, 2000L));
        assertNull(decoder.acceptApvpSinglePayload(toSingle, 2000L));
        decoder.configureApvpExpectations(true, true);

        byte[] steerSingle = concat(
                wrapField(1, 2, 0, wrapField(1, 0, 101, null)),
                wrapFloatField(5, (float) Math.toRadians(-360.0)));
        Float initAngle = decoder.acceptApvpSinglePayload(steerSingle, 2010L);
        assertNotNull(initAngle);
        assertTrue(initAngle > 18f && initAngle < 30f);

        // 20 秒后方向盘改为左打 360°（ID-only 帧，空 name），on-change 静默的 Qf/Timeout 仍有效
        byte[] idOnlyLeftFrame = wrapField(1, 2, 0, wrapField(3, 2, 0, concat(
                wrapField(1, 2, 0, wrapField(1, 0, 101, null)),
                wrapFloatField(5, (float) Math.toRadians(360.0))
        )));
        Float movedAngle = decoder.acceptApvpStreamPayload(idOnlyLeftFrame, 22_010L);
        assertNotNull(movedAngle);
        assertTrue(movedAngle < -18f && movedAngle > -30f);

        // 显式收到 Qf=2 立即门控拒绝；resetApvp() 清空状态
        byte[] badQfSingle = concat(wrapField(1, 2, 0, wrapField(1, 0, 102, null)), wrapField(3, 0, zigzag(2), null));
        assertNull(decoder.acceptApvpSinglePayload(badQfSingle, 22_500L));
        assertNull(decoder.acceptApvpSinglePayload(steerSingle, 22_600L));
        decoder.resetApvp();
        assertTrue(Float.isNaN(orZero(decoder.getLatestValidAngleDeg(23_000L))));
    }

    private static float orZero(Float f) {
        return f != null ? f : Float.NaN;
    }

    @Test
    public void vhalObserverSupportsConcurrentWatermarkAndSteeringListeners() {
        VhalSignalObserver observer = new VhalSignalObserver(null);
        AtomicInteger countA = new AtomicInteger();
        AtomicInteger countB = new AtomicInteger();

        VhalSignalObserver.PropertyListener listenerA = new VhalSignalObserver.PropertyListener() {
            @Override public void onBatch(byte[] bytes) { countA.incrementAndGet(); }
            @Override public void onConnectionChanged(boolean connected) {}
        };
        VhalSignalObserver.PropertyListener listenerB = new VhalSignalObserver.PropertyListener() {
            @Override public void onBatch(byte[] bytes) { countB.incrementAndGet(); }
            @Override public void onConnectionChanged(boolean connected) {}
        };

        observer.addPropertyListener(listenerA);
        observer.addPropertyListener(listenerB);
        observer.removePropertyListener(listenerA);
        assertFalse(listenerA == listenerB);
        observer.removePropertyListener(listenerB);
    }

    private static byte[] encodeVhalBatch(int propId, int areaId, int availability, long timestamp, float floatVal) {
        ByteArrayOutputStream propMsg = new ByteArrayOutputStream();
        writeVarint(propMsg, 8); writeVarint(propMsg, propId & 0xFFFFFFFFL);
        writeVarint(propMsg, 24); writeVarint(propMsg, timestamp);
        writeVarint(propMsg, 32); writeVarint(propMsg, areaId & 0xFFFFFFFFL);
        writeVarint(propMsg, 61);
        byte[] fBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(floatVal).array();
        propMsg.write(fBytes, 0, 4);
        writeVarint(propMsg, 80); writeVarint(propMsg, availability);

        byte[] item = wrapField(1, 2, 0, propMsg.toByteArray());
        return wrapField(1, 2, 0, item);
    }

    private static byte[] buildApvpStreamFrame(float steerRad, int qf, int timeout) {
        byte[] s1 = concat(
                wrapField(1, 2, 0, wrapSignalKey(101, SurroundSteeringAngleSource.SIG_PINION_STEER_AG)),
                wrapFloatField(5, steerRad)
        );
        byte[] s2 = concat(
                wrapField(1, 2, 0, wrapSignalKey(102, SurroundSteeringAngleSource.SIG_PINION_STEER_QF)),
                wrapField(3, 0, zigzag(qf), null)
        );
        byte[] s3 = concat(
                wrapField(1, 2, 0, wrapSignalKey(103, SurroundSteeringAngleSource.SIG_PINION_TIMEOUT)),
                wrapField(3, 0, zigzag(timeout), null)
        );
        byte[] mid = concat(
                wrapField(3, 2, 0, s1),
                wrapField(3, 2, 0, s2),
                wrapField(3, 2, 0, s3)
        );
        return wrapField(1, 2, 0, mid);
    }

    private static byte[] wrapSignalKey(int id, String name) {
        return concat(
                wrapField(1, 0, id, null),
                wrapField(2, 2, 0, name.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static byte[] wrapFloatField(int fieldNum, float value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        writeVarint(out, ((long) fieldNum << 3) | 5L);
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array();
        out.write(bytes, 0, 4);
        return out.toByteArray();
    }

    private static byte[] wrapField(int fieldNum, int wireType, long varint, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, ((long) fieldNum << 3) | wireType);
        if (wireType == 0) {
            writeVarint(out, varint);
        } else if (wireType == 2 && payload != null) {
            writeVarint(out, payload.length);
            out.write(payload, 0, payload.length);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) {
            out.write(a, 0, a.length);
        }
        return out.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0L) {
            out.write((int) ((value & 0x7FL) | 0x80L));
            value >>>= 7;
        }
        out.write((int) value);
    }
}
