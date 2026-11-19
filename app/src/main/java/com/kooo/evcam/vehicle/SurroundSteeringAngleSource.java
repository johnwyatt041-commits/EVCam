package com.kooo.evcam.vehicle;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.VhalNative;
import com.kooo.evcam.VhalSignalObserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

/**
 * Mode A / Mode D 前台按需方向盘转角数据源（方案 1）：
 * - 严格独立于 T01/T06 后台 4 类核心信号白名单（不修改 VehicleSignalCategory / VehicleSignalHub / 3.1 规则引擎）；
 * - 仅在主界面处于前台「模式 A（360 全景）」或「模式 D（随方向盘联动）」且功能启用时通过 Lease.acquire() 临时订阅；
 * - 退出模式 A/D、切离录制页、关闭引导线或退后台时立即 close() 释放，零后台唤醒与轮询开销；
 * - 同步兼容标准 AOSP VHAL (`PERF_STEERING_ANGLE = 0x11600209`, 40004) 与银河 E5 APVP
 *   (`TransferDebugServer` 40007 查表 + `TransferServer` 40005 `readSignal`/`listenerSignalStream` 订阅 `PinionSteerAgGroupPinionSteerAg1`)。
 */
public final class SurroundSteeringAngleSource {

    public interface AngleListener {
        void onSteeringWheelAngleDeg(float equivalentWheelAngleDeg);
    }

    public static final int PROP_PERF_STEERING_ANGLE = 0x11600209;
    public static final int APVP_PORT = 40005;
    public static final int APVP_DEBUG_PORT = 40007;
    public static final String SIG_PINION_STEER_AG = "PinionSteerAgGroupPinionSteerAg1";
    public static final String SIG_PINION_STEER_QF = "PinionSteerAgGroupPinionSteerAg1Qf";
    public static final String SIG_PINION_TIMEOUT = "PinionSteerAgGroupTimeout";

    private static final String TAG = "SurroundSteeringSource";
    private static final double MAX_STEERING_RAD = 14.5;
    private static final float MIN_EMIT_DELTA_DEG = 0.25f;
    private static final long APVP_FRESH_MS = 3500L;
    private static final long APVP_RETRY_DELAY_MS = 3000L;
    private static final int APVP_MAX_RETRIES = 2;

    private static SurroundSteeringAngleSource instance;

    private final CopyOnWriteArrayList<AngleListener> listeners = new CopyOnWriteArrayList<>();
    private final Decoder decoder = new Decoder();

    private int users;
    private boolean switchingVhal;
    @Nullable private VhalSignalObserver attachedVhal;
    @Nullable private VhalSignalObserver.PropertyListener attachedVhalListener;
    @Nullable private VhalSignalObserver ownedVhal;
    private final Runnable observerLifecycleCallback = this::refreshVhalAttachment;

    private volatile boolean running;
    private volatile int sessionGeneration;
    @Nullable private Thread apvpThread;
    @Nullable private volatile ManagedChannel apvpDebugChannel;
    @Nullable private volatile ManagedChannel apvpChannel;
    @Nullable private volatile ClientCall<byte[], byte[]> apvpStreamCall;
    private volatile boolean loggedFirstApvpAngle = false;

    private SurroundSteeringAngleSource() {
    }

    /** 仅在模式 A 前台且动态引导线启用时获取临时订阅租约；关闭即释放全部连接与监听。 */
    @NonNull
    public static synchronized Lease acquire(@NonNull AngleListener listener) {
        if (instance == null) {
            instance = new SurroundSteeringAngleSource();
        }
        instance.retain(listener);
        return new Lease(instance, listener);
    }

    private synchronized void retain(@NonNull AngleListener listener) {
        listeners.addIfAbsent(listener);
        if (++users == 1) {
            running = true;
            sessionGeneration++;
            loggedFirstApvpAngle = false;
            decoder.reset();
            VhalSignalObserver.addObserverLifecycleListener(observerLifecycleCallback);
            refreshVhalAttachment();
            startApvpWorkerLocked(sessionGeneration);
        } else {
            Float cached = decoder.getLatestValidAngleDeg(SystemClock.elapsedRealtime());
            if (cached != null) {
                listener.onSteeringWheelAngleDeg(cached);
            }
        }
    }

    private synchronized void release(@NonNull AngleListener listener) {
        listeners.remove(listener);
        if (users <= 0) {
            return;
        }
        if (--users != 0) {
            return;
        }
        running = false;
        sessionGeneration++;
        VhalSignalObserver.removeObserverLifecycleListener(observerLifecycleCallback);
        if (attachedVhal != null && attachedVhalListener != null) {
            attachedVhal.removePropertyListener(attachedVhalListener);
        }
        attachedVhal = null;
        attachedVhalListener = null;

        VhalSignalObserver vhalToStop = ownedVhal;
        ownedVhal = null;
        decoder.reset();

        if (vhalToStop != null) {
            vhalToStop.stopAsync();
        }
        stopApvpAsyncLocked();
    }

    private synchronized void refreshVhalAttachment() {
        if (!running || users == 0 || switchingVhal) {
            return;
        }
        switchingVhal = true;
        try {
            VhalSignalObserver candidate = VhalSignalObserver.findRunningObserverExcept(ownedVhal);
            if (candidate == null) {
                if (!VhalNative.isLibraryLoaded()) {
                    return;
                }
                if (ownedVhal == null) {
                    ownedVhal = new VhalSignalObserver(null);
                }
                candidate = ownedVhal;
            }
            if (candidate == attachedVhal) {
                return;
            }
            if (attachedVhal != null && attachedVhalListener != null) {
                attachedVhal.removePropertyListener(attachedVhalListener);
            }
            attachedVhal = candidate;
            final VhalSignalObserver selected = candidate;
            attachedVhalListener = new VhalSignalObserver.PropertyListener() {
                @Override
                public void onBatch(byte[] bytes) {
                    if (!running) {
                        return;
                    }
                    Float angle = decoder.acceptVhalBatch(bytes, SystemClock.elapsedRealtime());
                    if (angle != null) {
                        dispatchAngle(angle);
                    }
                }

                @Override
                public void onConnectionChanged(boolean connected) {
                    synchronized (SurroundSteeringAngleSource.this) {
                        if (attachedVhal == selected && !connected) {
                            decoder.resetVhal();
                        }
                    }
                }
            };
            candidate.addPropertyListener(attachedVhalListener);
            if (candidate == ownedVhal) {
                ownedVhal.start();
            } else {
                if (ownedVhal != null) {
                    VhalSignalObserver previous = ownedVhal;
                    ownedVhal = null;
                    previous.stopAsync();
                }
                candidate.requestPropertySnapshot();
            }
        } finally {
            switchingVhal = false;
        }
    }

    private void startApvpWorkerLocked(int gen) {
        Thread worker = new Thread(() -> runApvpLoop(gen), "SurroundApvpSteer");
        worker.setDaemon(true);
        this.apvpThread = worker;
        worker.start();
    }

    private void stopApvpAsyncLocked() {
        final ClientCall<byte[], byte[]> call = this.apvpStreamCall;
        final ManagedChannel channel = this.apvpChannel;
        final ManagedChannel debugChannel = this.apvpDebugChannel;
        final Thread thread = this.apvpThread;
        this.apvpStreamCall = null;
        this.apvpChannel = null;
        this.apvpDebugChannel = null;
        this.apvpThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        if (call != null || channel != null || debugChannel != null) {
            Thread stopper = new Thread(() -> {
                if (call != null) {
                    try {
                        call.cancel("surround steering lease closed", null);
                    } catch (Exception ignored) {
                    }
                }
                shutdownChannelQuietly(debugChannel);
                shutdownChannelQuietly(channel);
            }, "SurroundApvpShutdown");
            stopper.setDaemon(true);
            stopper.start();
        }
    }

    @NonNull
    private static ManagedChannel buildApvpChannel(@NonNull String host, int port) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("client_pid", Metadata.ASCII_STRING_MARSHALLER),
                "android_thread_id_" + android.os.Process.myTid());
        return OkHttpChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(20, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(false)
                .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                .build();
    }

    private void runApvpLoop(int gen) {
        String host = resolveGrpcHost();
        if (host == null || host.isEmpty()) {
            return;
        }
        int failures = 0;
        while (running && sessionGeneration == gen && failures <= APVP_MAX_RETRIES) {
            ManagedChannel debugChannel = null;
            ManagedChannel channel = null;
            try {
                debugChannel = buildApvpChannel(host, APVP_DEBUG_PORT);
                this.apvpDebugChannel = debugChannel;
                Map<String, SignalRef> catalog = fetchApvpSteeringSignals(debugChannel);
                if (this.apvpDebugChannel == debugChannel) {
                    this.apvpDebugChannel = null;
                }
                shutdownChannelQuietly(debugChannel);
                debugChannel = null;

                if (!running || sessionGeneration != gen) {
                    return;
                }
                SignalRef steerRef = catalog.get(SIG_PINION_STEER_AG);
                if (steerRef == null) {
                    AppLog.i(TAG, "APVP steering catalog unavailable on port " + APVP_DEBUG_PORT
                            + " (found=" + catalog.size() + "), fallback VHAL only");
                    return;
                }

                List<SignalRef> subscribeList = new ArrayList<>(3);
                subscribeList.add(steerRef);
                SignalRef qfRef = catalog.get(SIG_PINION_STEER_QF);
                SignalRef timeoutRef = catalog.get(SIG_PINION_TIMEOUT);
                if (qfRef != null) {
                    subscribeList.add(qfRef);
                }
                if (timeoutRef != null) {
                    subscribeList.add(timeoutRef);
                }
                AppLog.i(TAG, "APVP steering catalog resolved on port " + APVP_DEBUG_PORT
                        + ": steerId=" + steerRef.id
                        + ", qfId=" + (qfRef != null ? qfRef.id : -1)
                        + ", timeoutId=" + (timeoutRef != null ? timeoutRef.id : -1));

                decoder.resetApvp();
                decoder.registerApvpSignals(subscribeList);

                channel = buildApvpChannel(host, APVP_PORT);
                this.apvpChannel = channel;

                boolean qfReadOk = qfRef != null && readInitialSignal(channel, qfRef, gen);
                boolean timeoutReadOk = timeoutRef != null && readInitialSignal(channel, timeoutRef, gen);
                decoder.configureApvpExpectations(qfRef != null && qfReadOk, timeoutRef != null && timeoutReadOk);
                readInitialSignal(channel, steerRef, gen);

                boolean cleanClose = streamApvpSignals(channel, gen, subscribeList);
                failures = cleanClose ? 0 : (failures + 1);
            } catch (Exception e) {
                failures++;
                if (running && sessionGeneration == gen) {
                    AppLog.w(TAG, "APVP steering attempt " + failures + " ended: " + e.getMessage());
                }
            } finally {
                decoder.resetApvp();
                if (this.apvpDebugChannel == debugChannel) {
                    this.apvpDebugChannel = null;
                }
                if (this.apvpChannel == channel) {
                    this.apvpChannel = null;
                }
                shutdownChannelQuietly(debugChannel);
                shutdownChannelQuietly(channel);
            }

            if (!running || sessionGeneration != gen || failures > APVP_MAX_RETRIES) {
                break;
            }
            try {
                Thread.sleep(APVP_RETRY_DELAY_MS);
            } catch (InterruptedException ignored) {
                break;
            }
        }
    }

    @Nullable
    private static String resolveGrpcHost() {
        if (VhalNative.isLibraryLoaded()) {
            try {
                String h = VhalNative.getGrpcHost();
                if (h != null && !h.trim().isEmpty()) {
                    return h.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return "localhost";
    }

    @NonNull
    private Map<String, SignalRef> fetchApvpSteeringSignals(@NonNull ManagedChannel channel) {
        Map<String, SignalRef> found = new LinkedHashMap<>(3);
        MethodDescriptor<byte[], byte[]> getAllMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("transfer_proto.TransferDebugServer/getAllTransfer")
                .setRequestMarshaller(ByteMarshaller.INSTANCE)
                .setResponseMarshaller(ByteMarshaller.INSTANCE)
                .build();
        byte[] allResp = ClientCalls.blockingUnaryCall(
                channel, getAllMethod, CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS), new byte[0]);
        List<Integer> transferIds = ApvpWireCodec.parseAllTransferIds(allResp);
        if (transferIds.isEmpty()) {
            return found;
        }

        MethodDescriptor<byte[], byte[]> getConfigMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                .setFullMethodName("transfer_proto.TransferDebugServer/getTransferSignalConfig")
                .setRequestMarshaller(ByteMarshaller.INSTANCE)
                .setResponseMarshaller(ByteMarshaller.INSTANCE)
                .build();

        for (int transferId : transferIds) {
            if (!running) {
                break;
            }
            CountDownLatch latch = new CountDownLatch(1);
            byte[] req = ApvpWireCodec.buildTransferConfigRequest(transferId);
            ClientCall<byte[], byte[]> call = channel.newCall(
                    getConfigMethod, CallOptions.DEFAULT.withDeadlineAfter(4, TimeUnit.SECONDS));
            ClientCalls.asyncServerStreamingCall(call, req, new StreamObserver<byte[]>() {
                @Override
                public void onNext(byte[] value) {
                    SignalRef ref = ApvpWireCodec.parseTransferConfigItem(value);
                    if (ref != null && (SIG_PINION_STEER_AG.equals(ref.name)
                            || SIG_PINION_STEER_QF.equals(ref.name)
                            || SIG_PINION_TIMEOUT.equals(ref.name))) {
                        synchronized (found) {
                            found.put(ref.name, ref);
                        }
                    }
                }

                @Override
                public void onError(Throwable t) {
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    latch.countDown();
                }
            });
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (found.size() >= 3) {
                break;
            }
        }
        return found;
    }

    private boolean readInitialSignal(@NonNull ManagedChannel channel,
                                      @NonNull SignalRef ref,
                                      int gen) {
        if (!running || sessionGeneration != gen) {
            return false;
        }
        try {
            MethodDescriptor<byte[], byte[]> readMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("transfer_proto.TransferServer/readSignal")
                    .setRequestMarshaller(ByteMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteMarshaller.INSTANCE)
                    .build();
            byte[] req = ApvpWireCodec.buildSignalKeyRequest(ref);
            byte[] resp = ClientCalls.blockingUnaryCall(
                    channel, readMethod, CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS), req);
            if (!running || sessionGeneration != gen) {
                return false;
            }
            ApvpSignalValue parsed = ApvpWireCodec.parseSingleSignalUpdate(resp, decoder.snapshotApvpIdToName());
            Float angle = decoder.acceptApvpSinglePayload(resp, SystemClock.elapsedRealtime());
            if (angle != null) {
                logFirstApvpAngleOnce(angle, "readSignal");
                dispatchAngle(angle);
            }
            return parsed != null;
        } catch (Exception e) {
            AppLog.d(TAG, "APVP readSignal skipped for " + ref.name + ": " + e.getMessage());
            return false;
        }
    }

    private boolean streamApvpSignals(@NonNull ManagedChannel channel,
                                      int gen,
                                      @NonNull List<SignalRef> subscribeList) {
        MethodDescriptor<byte[], byte[]> streamMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setFullMethodName("transfer_proto.TransferServer/listenerSignalStream")
                .setRequestMarshaller(ByteMarshaller.INSTANCE)
                .setResponseMarshaller(ByteMarshaller.INSTANCE)
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] hadError = {false};
        ClientCall<byte[], byte[]> call = channel.newCall(streamMethod, CallOptions.DEFAULT);
        this.apvpStreamCall = call;

        StreamObserver<byte[]> reqObserver = ClientCalls.asyncBidiStreamingCall(call, new StreamObserver<byte[]>() {
            @Override
            public void onNext(byte[] value) {
                if (!running || sessionGeneration != gen) {
                    return;
                }
                Float angle = decoder.acceptApvpStreamPayload(value, SystemClock.elapsedRealtime());
                if (angle != null) {
                    logFirstApvpAngleOnce(angle, "stream");
                    dispatchAngle(angle);
                }
            }

            @Override
            public void onError(Throwable t) {
                hadError[0] = true;
                Status s = Status.fromThrowable(t);
                if (running && sessionGeneration == gen && s.getCode() != Status.Code.CANCELLED) {
                    AppLog.w(TAG, "APVP steering stream error: " + s.getCode());
                }
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });

        reqObserver.onNext(ApvpWireCodec.buildSubscribeStreamRequest(subscribeList));
        AppLog.i(TAG, "APVP steering stream subscribed on port " + APVP_PORT + " (" + subscribeList.size() + " signals)");

        try {
            while (running && sessionGeneration == gen) {
                if (latch.await(1500L, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                reqObserver.onCompleted();
            } catch (Exception ignored) {
            }
            if (this.apvpStreamCall == call) {
                this.apvpStreamCall = null;
            }
        }
        return !hadError[0];
    }

    private void logFirstApvpAngleOnce(float angleDeg, @NonNull String via) {
        if (!loggedFirstApvpAngle) {
            loggedFirstApvpAngle = true;
            AppLog.i(TAG, "APVP steering angle first sample (" + via + "): wheelAngleDeg=" + angleDeg);
        }
    }

    private void dispatchAngle(float angleDeg) {
        for (AngleListener listener : listeners) {
            if (listener != null) {
                listener.onSteeringWheelAngleDeg(angleDeg);
            }
        }
    }

    private static void shutdownChannelQuietly(@Nullable ManagedChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.shutdown();
            if (!channel.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                channel.shutdownNow();
            }
        } catch (Exception ignored) {
            try {
                channel.shutdownNow();
            } catch (Exception ignored2) {
            }
        }
    }

    // ==================== 方向盘转角 -> 等效前轮偏转角转换与双协议解码（可单测） ====================

    /**
     * 将 E5 CAN/APVP 原始方向盘转角（弧度，`PinionSteerAgGroupPinionSteerAg1`，左转为正、右转为负）
     * 换算为 360 引导线等效前轮偏转角（度，`[-45, +45]`，右转为正、左转为负）。
     */
    @Nullable
    public static Float pinionRadToWheelAngleDeg(double steeringRad) {
        if (!Double.isFinite(steeringRad) || steeringRad < -MAX_STEERING_RAD || steeringRad > MAX_STEERING_RAD) {
            return null;
        }
        if (Math.abs(steeringRad) < 1e-4) {
            return 0f;
        }
        double steerDegCcwPositive = steeringRad * 180.0 / Math.PI;
        return convertCcwSteerDegToRoadAngle(steerDegCcwPositive);
    }

    /**
     * 将标准 AOSP VHAL (`PERF_STEERING_ANGLE = 0x11600209`，方向盘转角度数，顺时针/右转为正)
     * 换算为 360 引导线等效前轮偏转角（度，`[-45, +45]`，右转为正、左转为负）。
     */
    @Nullable
    public static Float vhalWheelDegToRoadAngleDeg(double cwPositiveSteerDeg) {
        if (!Double.isFinite(cwPositiveSteerDeg) || Math.abs(cwPositiveSteerDeg) > 850.0) {
            return null;
        }
        if (Math.abs(cwPositiveSteerDeg) < 0.5) {
            return 0f;
        }
        return convertCcwSteerDegToRoadAngle(-cwPositiveSteerDeg);
    }

    private static float convertCcwSteerDegToRoadAngle(double ccwSteerDeg) {
        double d2 = ccwSteerDeg * ccwSteerDeg;
        double d3 = d2 * ccwSteerDeg;
        float left = (float) -((0.0622 * ccwSteerDeg) + (1e-5 * d2) + (2e-8 * d3) - 0.1289);
        float right = (float) ((0.0618 * ccwSteerDeg) - (1e-5 * d2) + (2e-8 * d3) + 0.0954);
        float avg = (left - right) * 0.5f;
        if (!Float.isFinite(avg)) {
            return 0f;
        }
        return Math.max(-45f, Math.min(45f, avg));
    }

    /** 纯 Java 双协议方向盘角度解码器（零 Android 依赖）。 */
    public static final class Decoder {
        private final Map<Integer, String> apvpIdToName = new HashMap<>();
        private long lastVhalTimestamp;
        private long lastApvpTimestampMs;
        private boolean expectQf;
        private boolean expectTimeout;
        private int lastQf = -1;
        private int lastTimeout = -1;
        private float lastVhalAngle = Float.NaN;
        private float lastApvpAngle = Float.NaN;
        private float lastEmittedAngle = Float.NaN;

        public synchronized void reset() {
            lastVhalTimestamp = 0L;
            lastVhalAngle = Float.NaN;
            lastEmittedAngle = Float.NaN;
            resetApvp();
        }

        public synchronized void resetVhal() {
            lastVhalTimestamp = 0L;
            lastVhalAngle = Float.NaN;
        }

        public synchronized void resetApvp() {
            lastApvpTimestampMs = 0L;
            expectQf = false;
            expectTimeout = false;
            lastQf = -1;
            lastTimeout = -1;
            lastApvpAngle = Float.NaN;
            apvpIdToName.clear();
        }

        public synchronized void registerApvpSignals(@Nullable List<SignalRef> refs) {
            apvpIdToName.clear();
            if (refs == null) {
                return;
            }
            for (SignalRef ref : refs) {
                if (ref != null && ref.id != 0 && !ref.name.isEmpty()) {
                    apvpIdToName.put(ref.id, ref.name);
                }
            }
        }

        @NonNull
        synchronized Map<Integer, String> snapshotApvpIdToName() {
            return new HashMap<>(apvpIdToName);
        }

        public synchronized void configureApvpExpectations(boolean requireInitialQf, boolean requireInitialTimeout) {
            this.expectQf = requireInitialQf;
            this.expectTimeout = requireInitialTimeout;
        }

        @Nullable
        public synchronized Float getLatestValidAngleDeg(long nowMs) {
            if (!Float.isNaN(lastApvpAngle) && (nowMs - lastApvpTimestampMs) <= APVP_FRESH_MS) {
                return lastApvpAngle;
            }
            if (!Float.isNaN(lastVhalAngle)) {
                return lastVhalAngle;
            }
            return !Float.isNaN(lastApvpAngle) ? lastApvpAngle : null;
        }

        @Nullable
        public synchronized Float acceptVhalBatch(@Nullable byte[] bytes, long nowMs) {
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            final Float[] candidate = {null};
            VhalProtoBatchReader.parseBatch(bytes, (prop, area, availability, timestamp, intVal, floatVal) -> {
                if (prop != PROP_PERF_STEERING_ANGLE || area != 0) {
                    return;
                }
                if (timestamp > 0 && timestamp < lastVhalTimestamp) {
                    return;
                }
                lastVhalTimestamp = timestamp;
                if (availability != 0 || floatVal == null || !Float.isFinite(floatVal)) {
                    return;
                }
                Float converted = vhalWheelDegToRoadAngleDeg(floatVal);
                if (converted != null) {
                    lastVhalAngle = converted;
                    candidate[0] = converted;
                }
            });
            if (candidate[0] == null) {
                return null;
            }
            // APVP 有效期间以 APVP 为准，避免两路轻微相位差引起引导线抖动
            if (!Float.isNaN(lastApvpAngle) && (nowMs - lastApvpTimestampMs) <= APVP_FRESH_MS) {
                return null;
            }
            return emitIfChanged(candidate[0]);
        }

        @Nullable
        public synchronized Float acceptApvpSinglePayload(@Nullable byte[] bytes, long nowMs) {
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            ApvpSignalValue single = ApvpWireCodec.parseSingleSignalUpdate(bytes, apvpIdToName);
            if (single == null) {
                return null;
            }
            return applyApvpUpdates(Collections.singletonList(single), nowMs);
        }

        @Nullable
        public synchronized Float acceptApvpStreamPayload(@Nullable byte[] bytes, long nowMs) {
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            List<ApvpSignalValue> updates = ApvpWireCodec.parseStreamSignalUpdates(bytes, apvpIdToName);
            if (updates.isEmpty()) {
                return null;
            }
            return applyApvpUpdates(updates, nowMs);
        }

        @Nullable
        private Float applyApvpUpdates(@NonNull List<ApvpSignalValue> updates, long nowMs) {
            Double rawRad = null;
            for (ApvpSignalValue u : updates) {
                String resolvedName = !u.name.isEmpty() ? u.name : (u.id != 0 ? apvpIdToName.get(u.id) : null);
                if (SIG_PINION_STEER_QF.equals(resolvedName)) {
                    Integer qf = u.asInt();
                    if (qf != null) {
                        lastQf = qf;
                    }
                } else if (SIG_PINION_TIMEOUT.equals(resolvedName)) {
                    Integer timeout = u.asInt();
                    if (timeout != null) {
                        lastTimeout = timeout;
                    }
                } else if (SIG_PINION_STEER_AG.equals(resolvedName)) {
                    Double v = u.asDouble();
                    if (v != null) {
                        rawRad = v;
                    }
                }
            }
            if (rawRad == null) {
                return null;
            }
            // 质量信号在当前 APVP 连接周期内持续有效，不因 on-change 静默超时丢弃正常转角
            if ((lastQf != -1 && lastQf != 3) || (expectQf && lastQf == -1)) {
                return null;
            }
            if ((lastTimeout != -1 && lastTimeout != 0) || (expectTimeout && lastTimeout == -1)) {
                return null;
            }
            Float wheelDeg = pinionRadToWheelAngleDeg(rawRad);
            if (wheelDeg == null) {
                return null;
            }
            lastApvpAngle = wheelDeg;
            lastApvpTimestampMs = nowMs;
            return emitIfChanged(wheelDeg);
        }

        @Nullable
        private Float emitIfChanged(float angle) {
            if (!Float.isNaN(lastEmittedAngle) && Math.abs(angle - lastEmittedAngle) < MIN_EMIT_DELTA_DEG) {
                return null;
            }
            lastEmittedAngle = angle;
            return angle;
        }
    }

    /** 活跃租约；close() 保证幂等退出。 */
    public static final class Lease implements AutoCloseable {
        @Nullable private SurroundSteeringAngleSource source;
        @Nullable private AngleListener listener;

        private Lease(@NonNull SurroundSteeringAngleSource source, @NonNull AngleListener listener) {
            this.source = source;
            this.listener = listener;
        }

        @Override
        public void close() {
            SurroundSteeringAngleSource s = this.source;
            AngleListener l = this.listener;
            this.source = null;
            this.listener = null;
            if (s != null && l != null) {
                s.release(l);
            }
        }
    }

    // ==================== 轻量 APVP transfer_proto 线协议编解码（无外部 .proto/反射依赖） ====================

    public static final class SignalRef {
        public final int id;
        @NonNull
        public final String name;

        public SignalRef(int id, @NonNull String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class ApvpSignalValue {
        public final int id;
        @NonNull
        public final String name;
        @Nullable public final Integer intValue;
        @Nullable public final Long longValue;
        @Nullable public final Float floatValue;
        @Nullable public final String stringValue;

        public ApvpSignalValue(int id,
                               @NonNull String name,
                               @Nullable Integer intValue,
                               @Nullable Long longValue,
                               @Nullable Float floatValue,
                               @Nullable String stringValue) {
            this.id = id;
            this.name = name;
            this.intValue = intValue;
            this.longValue = longValue;
            this.floatValue = floatValue;
            this.stringValue = stringValue;
        }

        @Nullable
        public Integer asInt() {
            if (intValue != null) {
                return intValue;
            }
            if (longValue != null) {
                return longValue.intValue();
            }
            if (floatValue != null && Float.isFinite(floatValue)) {
                return Math.round(floatValue);
            }
            if (stringValue != null && !stringValue.isEmpty()) {
                try {
                    return (int) Double.parseDouble(stringValue.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return null;
        }

        @Nullable
        public Double asDouble() {
            if (floatValue != null && Float.isFinite(floatValue)) {
                return (double) floatValue;
            }
            if (intValue != null) {
                return (double) intValue;
            }
            if (longValue != null) {
                return (double) longValue;
            }
            if (stringValue != null && !stringValue.isEmpty()) {
                try {
                    double v = Double.parseDouble(stringValue.trim());
                    return Double.isFinite(v) ? v : null;
                } catch (NumberFormatException ignored) {
                }
            }
            return null;
        }
    }

    public static final class ApvpWireCodec {
        private static final int MAX_WIRE_BYTES = 1024 * 1024;

        private ApvpWireCodec() {
        }

        @NonNull
        public static List<Integer> parseAllTransferIds(@Nullable byte[] bytes) {
            List<Integer> ids = new ArrayList<>();
            for (WireField f : parseFields(bytes)) {
                if (f.fieldNumber != 1) {
                    continue;
                }
                if (f.wireType == 2 && f.bytesValue != null) {
                    // E5 TransferDebugServer/getAllTransfer: repeated TransferInfo { int32 id = 1; ... }
                    for (WireField inner : parseFields(f.bytesValue)) {
                        if (inner.fieldNumber == 1 && inner.wireType == 0) {
                            int id = (int) inner.varintValue;
                            if (id > 0 && !ids.contains(id)) {
                                ids.add(id);
                            }
                        }
                    }
                } else if (f.wireType == 0) {
                    int id = (int) f.varintValue;
                    if (id > 0 && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        }

        @NonNull
        public static byte[] buildTransferConfigRequest(int transferId) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8);
            writeVarint(out, 8);
            writeVarint(out, transferId & 0xFFFFFFFFL);
            return out.toByteArray();
        }

        @NonNull
        public static byte[] buildSignalKeyRequest(@NonNull SignalRef ref) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16);
            writeVarint(out, 8);
            writeVarint(out, ref.id & 0xFFFFFFFFL);
            if (!ref.name.isEmpty()) {
                writeLengthDelimited(out, 2, ref.name.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        }

        @Nullable
        public static SignalRef parseTransferConfigItem(@Nullable byte[] bytes) {
            for (WireField outer : parseFields(bytes)) {
                if (outer.fieldNumber == 1 && outer.wireType == 2 && outer.bytesValue != null) {
                    int id = 0;
                    String name = "";
                    for (WireField inner : parseFields(outer.bytesValue)) {
                        if (inner.fieldNumber == 1 && inner.wireType == 0) {
                            id = (int) inner.varintValue;
                        } else if (inner.fieldNumber == 2 && inner.wireType == 2 && inner.bytesValue != null) {
                            name = new String(inner.bytesValue, StandardCharsets.UTF_8).trim();
                        }
                    }
                    if (!name.isEmpty()) {
                        return new SignalRef(id, name);
                    }
                }
            }
            return null;
        }

        @NonNull
        public static byte[] buildSubscribeStreamRequest(@NonNull List<SignalRef> refs) {
            ByteArrayOutputStream root = new ByteArrayOutputStream();
            writeVarint(root, 8);
            writeVarint(root, 0);
            for (SignalRef ref : refs) {
                if (ref == null || ref.name.isEmpty()) {
                    continue;
                }
                writeLengthDelimited(root, 2, buildSignalKeyRequest(ref));
            }
            return root.toByteArray();
        }

        @NonNull
        public static List<ApvpSignalValue> parseStreamSignalUpdates(@Nullable byte[] bytes) {
            return parseStreamSignalUpdates(bytes, null);
        }

        @NonNull
        public static List<ApvpSignalValue> parseStreamSignalUpdates(@Nullable byte[] bytes,
                                                                     @Nullable Map<Integer, String> idToName) {
            List<ApvpSignalValue> out = new ArrayList<>();
            for (WireField outer : parseFields(bytes)) {
                if (outer.fieldNumber != 1 || outer.wireType != 2 || outer.bytesValue == null) {
                    continue;
                }
                for (WireField mid : parseFields(outer.bytesValue)) {
                    if (mid.fieldNumber != 3 || mid.wireType != 2 || mid.bytesValue == null) {
                        continue;
                    }
                    ApvpSignalValue val = parseSingleSignalUpdate(mid.bytesValue, idToName);
                    if (val != null) {
                        out.add(val);
                    }
                }
            }
            return out;
        }

        @Nullable
        public static ApvpSignalValue parseSingleSignalUpdate(@Nullable byte[] bytes,
                                                              @Nullable Map<Integer, String> idToName) {
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            int id = 0;
            String name = "";
            Integer intVal = null;
            Long longVal = null;
            Float floatVal = null;
            String strVal = null;
            for (WireField f : parseFields(bytes)) {
                switch (f.fieldNumber) {
                    case 1:
                        if (f.wireType == 2 && f.bytesValue != null) {
                            for (WireField keyField : parseFields(f.bytesValue)) {
                                if (keyField.fieldNumber == 1 && keyField.wireType == 0) {
                                    id = (int) keyField.varintValue;
                                } else if (keyField.fieldNumber == 2 && keyField.wireType == 2 && keyField.bytesValue != null) {
                                    name = new String(keyField.bytesValue, StandardCharsets.UTF_8).trim();
                                }
                            }
                        } else if (f.wireType == 0) {
                            id = (int) f.varintValue;
                        }
                        break;
                    case 3:
                        if (f.wireType == 0) {
                            int v = (int) f.varintValue;
                            intVal = (-(v & 1)) ^ (v >>> 1);
                        } else if (f.wireType == 2 && f.bytesValue != null) {
                            List<Long> packed = readPackedVarints(f.bytesValue);
                            if (!packed.isEmpty()) {
                                int v = packed.get(0).intValue();
                                intVal = (-(v & 1)) ^ (v >>> 1);
                            }
                        }
                        break;
                    case 4:
                        if (f.wireType == 0) {
                            longVal = f.varintValue;
                        } else if (f.wireType == 2 && f.bytesValue != null) {
                            List<Long> packed = readPackedVarints(f.bytesValue);
                            if (!packed.isEmpty()) {
                                longVal = packed.get(0);
                            }
                        }
                        break;
                    case 5:
                        if (f.wireType == 5) {
                            floatVal = Float.intBitsToFloat((int) f.varintValue);
                        } else if (f.wireType == 1) {
                            double dv = Double.longBitsToDouble(f.varintValue);
                            if (Double.isFinite(dv)) {
                                floatVal = (float) dv;
                            }
                        } else if (f.wireType == 2 && f.bytesValue != null && f.bytesValue.length >= 4) {
                            floatVal = ByteBuffer.wrap(f.bytesValue, 0, 4)
                                    .order(ByteOrder.LITTLE_ENDIAN).getFloat();
                        }
                        break;
                    case 6:
                        if (f.wireType == 2 && f.bytesValue != null) {
                            strVal = new String(f.bytesValue, StandardCharsets.UTF_8);
                        }
                        break;
                    default:
                        break;
                }
            }
            if (name.isEmpty() && id != 0 && idToName != null) {
                String resolved = idToName.get(id);
                if (resolved != null) {
                    name = resolved;
                }
            }
            if (name.isEmpty() && id == 0) {
                return null;
            }
            return new ApvpSignalValue(id, name, intVal, longVal, floatVal, strVal);
        }

        private static List<WireField> parseFields(@Nullable byte[] bytes) {
            List<WireField> out = new ArrayList<>();
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_WIRE_BYTES) {
                return out;
            }
            int pos = 0;
            while (pos < bytes.length) {
                long[] tagRes = readVarint(bytes, pos);
                if (tagRes == null) {
                    break;
                }
                long tag = tagRes[0];
                pos = (int) tagRes[1];
                int fieldNum = (int) (tag >>> 3);
                int wireType = (int) (tag & 7L);
                if (fieldNum <= 0) {
                    break;
                }
                if (wireType == 0) {
                    long[] valRes = readVarint(bytes, pos);
                    if (valRes == null) {
                        break;
                    }
                    out.add(new WireField(fieldNum, 0, valRes[0], null));
                    pos = (int) valRes[1];
                } else if (wireType == 1) {
                    if (pos + 8 > bytes.length) {
                        break;
                    }
                    long v = ByteBuffer.wrap(bytes, pos, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                    out.add(new WireField(fieldNum, 1, v, null));
                    pos += 8;
                } else if (wireType == 2) {
                    long[] lenRes = readVarint(bytes, pos);
                    if (lenRes == null) {
                        break;
                    }
                    int len = (int) lenRes[0];
                    pos = (int) lenRes[1];
                    if (len < 0 || pos + len > bytes.length) {
                        break;
                    }
                    byte[] slice = new byte[len];
                    System.arraycopy(bytes, pos, slice, 0, len);
                    out.add(new WireField(fieldNum, 2, 0L, slice));
                    pos += len;
                } else if (wireType == 5) {
                    if (pos + 4 > bytes.length) {
                        break;
                    }
                    int v = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    out.add(new WireField(fieldNum, 5, v & 0xFFFFFFFFL, null));
                    pos += 4;
                } else {
                    break;
                }
            }
            return out;
        }

        private static List<Long> readPackedVarints(@NonNull byte[] data) {
            List<Long> values = new ArrayList<>();
            int pos = 0;
            while (pos < data.length) {
                long[] res = readVarint(data, pos);
                if (res == null) {
                    break;
                }
                values.add(res[0]);
                pos = (int) res[1];
            }
            return values;
        }

        @Nullable
        private static long[] readVarint(@NonNull byte[] data, int pos) {
            long value = 0L;
            for (int shift = 0; shift < 64 && pos < data.length; shift += 7) {
                int b = data[pos++] & 0xFF;
                if (shift == 63 && (b & 0xFE) != 0) {
                    return null;
                }
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return new long[]{value, pos};
                }
            }
            return null;
        }

        private static void writeVarint(@NonNull ByteArrayOutputStream out, long value) {
            while ((value & ~0x7FL) != 0L) {
                out.write((int) ((value & 0x7FL) | 0x80L));
                value >>>= 7;
            }
            out.write((int) value);
        }

        private static void writeLengthDelimited(@NonNull ByteArrayOutputStream out, int fieldNum, @NonNull byte[] payload) {
            writeVarint(out, ((long) fieldNum << 3) | 2L);
            writeVarint(out, payload.length);
            out.write(payload, 0, payload.length);
        }

        private static final class WireField {
            final int fieldNumber;
            final int wireType;
            final long varintValue;
            @Nullable final byte[] bytesValue;

            WireField(int fieldNumber, int wireType, long varintValue, @Nullable byte[] bytesValue) {
                this.fieldNumber = fieldNumber;
                this.wireType = wireType;
                this.varintValue = varintValue;
                this.bytesValue = bytesValue;
            }
        }
    }

    private enum ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        INSTANCE;

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = stream.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                return baos.toByteArray();
            } catch (Exception e) {
                return new byte[0];
            }
        }
    }
}
