package com.kooo.evcam;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

/**
 * 通过车辆API 监听车辆信号（转向灯 + 车门状态）。
 * 协议解码由 native 层完成。
 */
public class VhalSignalObserver {
    private static final String TAG = "VhalSignalObserver";

    /** Optional raw consumer; inactive unless watermark or Mode A steering guidance is enabled. */
    public interface PropertyListener {
        void onBatch(byte[] bytes);
        void onConnectionChanged(boolean connected);
    }
    private volatile PropertyListener propertyListener;
    private final java.util.concurrent.CopyOnWriteArrayList<PropertyListener> extraPropertyListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.List<VhalSignalObserver> liveObservers = new java.util.ArrayList<>();
    private static volatile Runnable observerLifecycleListener;
    private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> extraLifecycleListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addPropertyListener(PropertyListener listener) {
        if (listener != null) {
            extraPropertyListeners.addIfAbsent(listener);
        }
    }

    public void removePropertyListener(PropertyListener listener) {
        if (listener != null) {
            extraPropertyListeners.remove(listener);
        }
    }

    public void setPropertyListener(PropertyListener listener) {
        propertyListener = listener;
    }

    public static void addObserverLifecycleListener(Runnable listener) {
        if (listener != null) {
            extraLifecycleListeners.addIfAbsent(listener);
        }
    }

    public static void removeObserverLifecycleListener(Runnable listener) {
        if (listener != null) {
            extraLifecycleListeners.remove(listener);
        }
    }

    public static void setObserverLifecycleListener(Runnable listener) {
        observerLifecycleListener = listener;
    }

    public static VhalSignalObserver findRunningObserverExcept(VhalSignalObserver excluded) {
        synchronized (liveObservers) {
            for (VhalSignalObserver observer : liveObservers) {
                if (observer != excluded && observer.running) return observer;
            }
        }
        return null;
    }

    private void notifyObserverLifecycle() {
        Runnable primary = observerLifecycleListener;
        if (primary != null) {
            primary.run();
        }
        for (Runnable callback : extraLifecycleListeners) {
            if (callback != null) {
                callback.run();
            }
        }
    }

    /** One initial snapshot when attaching to a stream already used by blind-spot monitoring. */
    public void requestPropertySnapshot() {
        ManagedChannel channel = grpcChannel;
        if (!running || channel == null) return;
        new Thread(() -> {
            try {
                MethodDescriptor<byte[], byte[]> method = MethodDescriptor.<byte[], byte[]>newBuilder()
                        .setType(MethodDescriptor.MethodType.UNARY)
                        .setFullMethodName(VhalNative.getSendAllMethod())
                        .setRequestMarshaller(ByteMarshaller.INSTANCE)
                        .setResponseMarshaller(ByteMarshaller.INSTANCE).build();
                ClientCalls.blockingUnaryCall(channel, method,
                        CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS), new byte[0]);
            } catch (Exception | UnsatisfiedLinkError e) {
                AppLog.w(TAG, "Optional vehicle snapshot unavailable: " + e.getMessage());
            }
        }, "VehicleStatusSnapshot").start();
    }

    /**
     * 转向灯信号回调接口
     */
    public interface TurnSignalListener {
        /** 转向灯状态变化 */
        void onTurnSignal(String direction, boolean on);
        /** 连接状态变化 */
        void onConnectionStateChanged(boolean connected);
    }

    /**
     * 定制键唤醒回调接口
     */

    private final TurnSignalListener listener;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile float currentSpeed = 0f;

    private volatile ManagedChannel grpcChannel;
    private Thread connectThread;
    private volatile boolean running = false;
    private volatile boolean connected = false;

    // 上一次的转向灯状态，避免重复回调
    private int lastSignalState = -1;
    private long lastBatchDiagUptimeMs = 0L;
    private long lastGearDiagUptimeMs = 0L;
    private int lastLoggedGearProp = 0;
    private int lastLoggedGearArea = 0;
    private int lastLoggedGearAvail = Integer.MIN_VALUE;
    private Integer lastLoggedGearIntVal = null;

         // 副驾门
      // 左后门
     // 右后门

    // 重连参数
    private static final long RECONNECT_DELAY_MS = 3000;
    static final long STREAM_TIMEOUT_MS = 120_000; // gRPC stream 最大沉默时间
    static final long SILENT_RECONNECT_GRACE_MS = 10_000L; // 静默重连恢复确认宽限期
    private static final long SEND_ALL_RPC_TIMEOUT_MS = 3_000L;

    private final ReconnectStateMachine reconnectStateMachine = new ReconnectStateMachine();

    /**
     * 线程安全的重连与流活跃度状态机（纯 Java，可在 JVM 单元测试直接验证）：
     * - 120s 空闲超时触发静默重连时，保留已解码挡位且不复位 VehicleSignalHub；
     * - 仅在明确断流、重连失败、超过宽限期仍未恢复或主动停止时才通知断连并退回 UNKNOWN。
     */
    static final class ReconnectStateMachine {
        enum StreamEndReason {
            IDLE_TIMEOUT,
            RECONNECT_TIMEOUT,
            STREAM_ERROR,
            STREAM_COMPLETED,
            STOPPED
        }

        enum ConnectAction {
            NONE,
            NOTIFY_CONNECTED,
            NOTIFY_DISCONNECTED_AND_RESET
        }

        enum DisconnectAction {
            NONE,
            SILENT_RECONNECT,
            NOTIFY_DISCONNECTED_AND_RESET,
            RECONNECT_AFTER_DELAY
        }

        private boolean connected = false;
        private boolean silentReconnecting = false;
        private long streamStartMs = 0L;
        private long lastActivityMs = 0L;
        private boolean confirmedInCurrentStream = false;

        synchronized void reset() {
            connected = false;
            silentReconnecting = false;
            streamStartMs = 0L;
            lastActivityMs = 0L;
            confirmedInCurrentStream = false;
        }

        synchronized boolean isSilentReconnecting() {
            return silentReconnecting;
        }

        synchronized boolean isConnected() {
            return connected;
        }

        synchronized ConnectAction onConnectAttemptResult(boolean connectOk, long nowMs) {
            if (connectOk) {
                connected = true;
                if (!silentReconnecting) {
                    return ConnectAction.NOTIFY_CONNECTED;
                }
                return ConnectAction.NONE;
            }
            boolean shouldReset = connected || silentReconnecting;
            silentReconnecting = false;
            connected = false;
            confirmedInCurrentStream = false;
            return shouldReset ? ConnectAction.NOTIFY_DISCONNECTED_AND_RESET : ConnectAction.NONE;
        }

        synchronized void onStreamOpened(long nowMs) {
            streamStartMs = nowMs;
            lastActivityMs = nowMs;
            confirmedInCurrentStream = !silentReconnecting;
        }

        synchronized void onStreamActivity(long nowMs) {
            lastActivityMs = nowMs;
            confirmedInCurrentStream = true;
            silentReconnecting = false;
        }

        synchronized StreamEndReason evaluateTimeout(long nowMs, long idleTimeoutMs, long silentGraceMs) {
            if (silentReconnecting && !confirmedInCurrentStream) {
                return (nowMs - streamStartMs >= silentGraceMs) ? StreamEndReason.RECONNECT_TIMEOUT : null;
            }
            return (nowMs - lastActivityMs >= idleTimeoutMs) ? StreamEndReason.IDLE_TIMEOUT : null;
        }

        synchronized long computeWaitMs(long nowMs, long idleTimeoutMs, long silentGraceMs) {
            if (silentReconnecting && !confirmedInCurrentStream) {
                return Math.max(0L, silentGraceMs - (nowMs - streamStartMs));
            }
            return Math.max(0L, idleTimeoutMs - (nowMs - lastActivityMs));
        }

        synchronized DisconnectAction onStreamEnded(StreamEndReason reason, boolean stillRunning) {
            if (!stillRunning || reason == StreamEndReason.STOPPED) {
                boolean shouldReset = connected || silentReconnecting;
                silentReconnecting = false;
                connected = false;
                confirmedInCurrentStream = false;
                return shouldReset ? DisconnectAction.NOTIFY_DISCONNECTED_AND_RESET : DisconnectAction.NONE;
            }
            if (reason == StreamEndReason.IDLE_TIMEOUT) {
                if (connected && !silentReconnecting) {
                    silentReconnecting = true;
                    confirmedInCurrentStream = false;
                    return DisconnectAction.SILENT_RECONNECT;
                }
                boolean shouldReset = connected || silentReconnecting;
                silentReconnecting = false;
                connected = false;
                confirmedInCurrentStream = false;
                return shouldReset ? DisconnectAction.NOTIFY_DISCONNECTED_AND_RESET : DisconnectAction.RECONNECT_AFTER_DELAY;
            }
            boolean shouldReset = connected || silentReconnecting;
            silentReconnecting = false;
            connected = false;
            confirmedInCurrentStream = false;
            return shouldReset ? DisconnectAction.NOTIFY_DISCONNECTED_AND_RESET : DisconnectAction.RECONNECT_AFTER_DELAY;
        }
    }

    private static long monotonicNowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    public VhalSignalObserver(TurnSignalListener listener) {
        this.listener = listener;
    }

    /**
     * 获取当前速度值（用于定制键唤醒速度条件判断）
     */
    public float getCurrentSpeed() {
        return currentSpeed;
    }

    /**
     * 启动连接和监听
     */
    public void start() {
        if (running) return;
        running = true;
        lastSignalState = -1;
        reconnectStateMachine.reset();

        connectThread = new Thread(this::connectLoop, "VehicleApiConnect");
        connectThread.setDaemon(true);
        connectThread.start();
        synchronized (liveObservers) { liveObservers.add(this); }
        notifyObserverLifecycle();
    }

    /**
     * 停止连接和监听
     */
    public void stop() {
        running = false;
        synchronized (liveObservers) { liveObservers.remove(this); }
        notifyObserverLifecycle();
        disconnect();
        if (connectThread != null) {
            connectThread.interrupt();
            connectThread = null;
        }
    }

    /** Detach immediately; perform bounded channel shutdown away from GL/callback threads. */
    public void stopAsync() {
        running = false;
        propertyListener = null;
        extraPropertyListeners.clear();
        synchronized (liveObservers) { liveObservers.remove(this); }
        notifyObserverLifecycle();
        Thread previous = connectThread;
        connectThread = null;
        if (previous != null) previous.interrupt();
        new Thread(this::disconnect, "VehicleApiShutdown").start();
    }

    /**
     * 当前是否已连接
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 观察者是否存活（线程仍在运行）
     */
    public boolean isAlive() {
        return running && connectThread != null && connectThread.isAlive();
    }

    /**
     * 一次性连接测试（阻塞调用，用于 UI 状态检查）
     */
    public static boolean testConnection() {
        // 检查 native 库是否已加载
        if (!VhalNative.isLibraryLoaded()) {
            AppLog.w(TAG, "Native library not loaded, skipping gRPC connection test");
            return false;
        }
        
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(VhalNative.getGrpcHost(), VhalNative.getGrpcPort()), 2000);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Internal ====================

    private void connectLoop() {
        while (running) {
            boolean sleepBeforeRetry = true;
            try {
                AppLog.d(TAG, "Connecting to vehicle API service...");
                boolean ok = connect();
                ReconnectStateMachine.ConnectAction connectAction =
                        reconnectStateMachine.onConnectAttemptResult(ok, monotonicNowMs());
                if (ok) {
                    connected = true;
                    if (connectAction == ReconnectStateMachine.ConnectAction.NOTIFY_CONNECTED) {
                        AppLog.d(TAG, "Connected, starting property stream");
                        notifyConnectionState(true);
                    } else {
                        AppLog.d(TAG, "Silent reconnect channel established, resuming property stream");
                    }
                    ReconnectStateMachine.StreamEndReason endReason = streamProperties(); // blocks until disconnected
                    ReconnectStateMachine.DisconnectAction disconnectAction =
                            reconnectStateMachine.onStreamEnded(endReason, running);
                    if (disconnectAction == ReconnectStateMachine.DisconnectAction.SILENT_RECONNECT) {
                        AppLog.w(TAG, "Stream idle timeout (" + STREAM_TIMEOUT_MS
                                + "ms), silent reconnecting without resetting state");
                        disconnectChannelOnly();
                        sleepBeforeRetry = false;
                    } else if (disconnectAction == ReconnectStateMachine.DisconnectAction.NOTIFY_DISCONNECTED_AND_RESET) {
                        connected = false;
                        notifyConnectionState(false);
                        disconnectChannelOnly();
                    } else {
                        connected = false;
                        disconnectChannelOnly();
                    }
                } else {
                    connected = false;
                    if (connectAction == ReconnectStateMachine.ConnectAction.NOTIFY_DISCONNECTED_AND_RESET) {
                        notifyConnectionState(false);
                    }
                    disconnectChannelOnly();
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Connection error: " + e.getMessage());
                ReconnectStateMachine.ConnectAction errAction =
                        reconnectStateMachine.onConnectAttemptResult(false, monotonicNowMs());
                connected = false;
                if (errAction == ReconnectStateMachine.ConnectAction.NOTIFY_DISCONNECTED_AND_RESET) {
                    notifyConnectionState(false);
                }
                disconnectChannelOnly();
            }

            if (!running) {
                if (reconnectStateMachine.onStreamEnded(ReconnectStateMachine.StreamEndReason.STOPPED, false)
                        == ReconnectStateMachine.DisconnectAction.NOTIFY_DISCONNECTED_AND_RESET) {
                    connected = false;
                    notifyConnectionState(false);
                }
                break;
            }
            if (!sleepBeforeRetry) {
                continue;
            }

            try {
                AppLog.d(TAG, "Reconnecting in " + RECONNECT_DELAY_MS + "ms...");
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                if (reconnectStateMachine.onStreamEnded(ReconnectStateMachine.StreamEndReason.STOPPED, false)
                        == ReconnectStateMachine.DisconnectAction.NOTIFY_DISCONNECTED_AND_RESET) {
                    connected = false;
                    notifyConnectionState(false);
                }
                break;
            }
        }
    }

    private boolean connect() {
        try {
            // 检查 native 库是否已加载
            if (!VhalNative.isLibraryLoaded()) {
                AppLog.w(TAG, "Native library not loaded, skipping VHAL connection");
                return false;
            }
            
            // 构建连接，附带 session_id 和 client_id metadata
            String sessionId = UUID.randomUUID().toString();
            Metadata headers = new Metadata();
            headers.put(
                    Metadata.Key.of("session_id", Metadata.ASCII_STRING_MARSHALLER),
                    sessionId
            );
            headers.put(
                    Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER),
                    "evcam_signal"
            );

            grpcChannel = OkHttpChannelBuilder.forAddress(VhalNative.getGrpcHost(), VhalNative.getGrpcPort())
                    .usePlaintext()
                    .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .build();

            AppLog.d(TAG, "Channel created, session_id=" + sessionId);
            return true;
        } catch (UnsatisfiedLinkError e) {
            AppLog.e(TAG, "Native method not found: " + e.getMessage());
            return false;
        } catch (Exception e) {
            AppLog.e(TAG, "Connect failed: " + e.getMessage());
            disconnectChannelOnly();
            return false;
        }
    }

    private void disconnect() {
        connected = false;
        disconnectChannelOnly();
    }

    private void disconnectChannelOnly() {
        ManagedChannel channel = grpcChannel;
        grpcChannel = null;
        if (channel != null) {
            try {
                channel.shutdown();
                if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (Exception ignored) {
                try { channel.shutdownNow(); } catch (Exception ignored2) {}
            }
        }
    }

    /**
     * 开始属性流监听（阻塞直到断开、空闲超时或出错）
     */
    private ReconnectStateMachine.StreamEndReason streamProperties() {
        final ManagedChannel streamChannel = grpcChannel;
        if (streamChannel == null) {
            return ReconnectStateMachine.StreamEndReason.STREAM_ERROR;
        }

        // 使用 CountDownLatch 等待流结束
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean streamClosed = new AtomicBoolean(false);
        final AtomicBoolean streamError = new AtomicBoolean(false);
        final AtomicBoolean streamCompleted = new AtomicBoolean(false);
        io.grpc.ClientCall<byte[], byte[]> call = null;

        try {
            MethodDescriptor<byte[], byte[]> streamMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                    .setFullMethodName(VhalNative.getStreamMethod())
                    .setRequestMarshaller(ByteMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteMarshaller.INSTANCE)
                    .build();

            call = streamChannel.newCall(streamMethod, CallOptions.DEFAULT);
            reconnectStateMachine.onStreamOpened(monotonicNowMs());

            ClientCalls.asyncServerStreamingCall(call, new byte[0], new StreamObserver<byte[]>() {
                @Override
                public void onNext(byte[] value) {
                    if (streamClosed.get()) {
                        return;
                    }
                    reconnectStateMachine.onStreamActivity(monotonicNowMs());
                    try {
                        processPropertyBatch(value);
                    } catch (Exception e) {
                        AppLog.e(TAG, "Failed to process property batch: " + e.getMessage());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    if (streamClosed.get()) {
                        return;
                    }
                    AppLog.e(TAG, "Property stream error: " + t.getMessage());
                    streamError.set(true);
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    if (streamClosed.get()) {
                        return;
                    }
                    AppLog.d(TAG, "Property stream completed");
                    streamCompleted.set(true);
                    latch.countDown();
                }
            });

            // 请求服务器推送所有当前属性值
            // 服务器通过 channel metadata 中的 session_id 关联此请求和 stream
            // 延迟发送，等待 stream 在服务端注册完成；失败后重试
            new Thread(() -> {
                final int MAX_RETRIES = 3;
                final long INITIAL_DELAY_MS = 500;
                
                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    try {
                        Thread.sleep(attempt == 1 ? INITIAL_DELAY_MS : INITIAL_DELAY_MS * attempt);
                    } catch (InterruptedException e) {
                        return;
                    }
                    
                    if (!running || streamClosed.get() || grpcChannel != streamChannel) return;
                    
                    try {
                        MethodDescriptor<byte[], byte[]> sendAllMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                                .setType(MethodDescriptor.MethodType.UNARY)
                                .setFullMethodName(VhalNative.getSendAllMethod())
                                .setRequestMarshaller(ByteMarshaller.INSTANCE)
                                .setResponseMarshaller(ByteMarshaller.INSTANCE)
                                .build();
                        var sendCall = streamChannel.newCall(
                                sendAllMethod,
                                CallOptions.DEFAULT.withDeadlineAfter(SEND_ALL_RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                        ClientCalls.blockingUnaryCall(sendCall, new byte[0]);
                        if (!streamClosed.get() && !streamError.get()) {
                            reconnectStateMachine.onStreamActivity(monotonicNowMs());
                        }
                        AppLog.d(TAG, "Requested all property values to stream (attempt " + attempt + ")");
                        return;
                    } catch (UnsatisfiedLinkError e) {
                        AppLog.e(TAG, "Native method not found: " + e.getMessage());
                        return;
                    } catch (Exception e) {
                        AppLog.w(TAG, "SendAll attempt " + attempt + "/" + MAX_RETRIES + " failed: " + e.getMessage());
                    }
                }
                AppLog.e(TAG, "SendAll exhausted all retries");
            }, "VehicleApiSendAll").start();

            // 等待流结束（仅在真正连续静默满 STREAM_TIMEOUT_MS 时触发静默重连，防止半开连接卡死）
            while (running) {
                ReconnectStateMachine.StreamEndReason timeoutReason = reconnectStateMachine.evaluateTimeout(
                        monotonicNowMs(), STREAM_TIMEOUT_MS, SILENT_RECONNECT_GRACE_MS);
                if (timeoutReason != null) {
                    return timeoutReason;
                }
                long waitMs = Math.max(1L, reconnectStateMachine.computeWaitMs(
                        monotonicNowMs(), STREAM_TIMEOUT_MS, SILENT_RECONNECT_GRACE_MS));
                if (latch.await(waitMs, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }

            if (!running) {
                return ReconnectStateMachine.StreamEndReason.STOPPED;
            }
            if (streamError.get()) {
                return ReconnectStateMachine.StreamEndReason.STREAM_ERROR;
            }
            if (streamCompleted.get()) {
                return ReconnectStateMachine.StreamEndReason.STREAM_COMPLETED;
            }
            return ReconnectStateMachine.StreamEndReason.STREAM_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return running ? ReconnectStateMachine.StreamEndReason.STREAM_ERROR
                    : ReconnectStateMachine.StreamEndReason.STOPPED;
        } catch (UnsatisfiedLinkError e) {
            AppLog.e(TAG, "Native method not found: " + e.getMessage());
            return ReconnectStateMachine.StreamEndReason.STREAM_ERROR;
        } catch (Exception e) {
            AppLog.e(TAG, "Stream setup failed: " + e.getMessage());
            return ReconnectStateMachine.StreamEndReason.STREAM_ERROR;
        } finally {
            streamClosed.set(true);
            if (call != null) {
                try {
                    call.cancel("VHAL stream closed", null);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 处理一批属性值更新（由 native 层解码）
     */
    private void processPropertyBatch(byte[] data) {
        logBatchDiagnostics(data);
        com.kooo.evcam.vehicle.VehicleSignalHub.getInstance().onRawBatch(data);
        if (running) {
            PropertyListener primary = propertyListener;
            if (primary != null) {
                primary.onBatch(data);
            }
            for (PropertyListener consumer : extraPropertyListeners) {
                if (consumer != null) {
                    consumer.onBatch(data);
                }
            }
        }
        if (!VhalNative.isLibraryLoaded()) {
            AppLog.w(TAG, "Native library not loaded, skipping property batch processing");
            return;
        }
        
        int[] events;
        try {
            events = VhalNative.decode(data);
        } catch (UnsatisfiedLinkError e) {
            AppLog.e(TAG, "Native method not found: " + e.getMessage());
            return;
        }
        
        if (events == null || events.length < 1) return;
        com.kooo.evcam.vehicle.VehicleSignalHub.getInstance().onNativeEvents(events);

        int numEvents = events[0];
        for (int i = 0; i < numEvents; i++) {
            int offset = 1 + i * 3;
            if (offset + 2 >= events.length) break;
            int type = events[offset];
            int p1 = events[offset + 1];
            int p2 = events[offset + 2];

            switch (type) {
                case VhalNative.EVT_TURN_SIGNAL:
                    handleTurnSignalEvent(p1);
                    break;
                case VhalNative.EVT_SPEED:
                    currentSpeed = Float.intBitsToFloat(p1);
                    break;
            }
        }
    }

    private void logBatchDiagnostics(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        final long nowUptime = android.os.SystemClock.uptimeMillis();
        final int[] propCount = {0};
        com.kooo.evcam.vehicle.VhalProtoBatchReader.parseBatch(data,
                (propId, areaId, availability, timestamp, intValue, floatValue) -> {
                    propCount[0]++;
                    com.kooo.evcam.vehicle.VehicleSignalCategory category =
                            com.kooo.evcam.vehicle.VehicleSignalCategory.classifyPropertyId(propId);
                    if (category == com.kooo.evcam.vehicle.VehicleSignalCategory.GEAR) {
                        boolean changed = propId != lastLoggedGearProp
                                || areaId != lastLoggedGearArea
                                || availability != lastLoggedGearAvail
                                || !java.util.Objects.equals(intValue, lastLoggedGearIntVal);
                        if (changed || nowUptime - lastGearDiagUptimeMs >= 10_000L) {
                            lastGearDiagUptimeMs = nowUptime;
                            lastLoggedGearProp = propId;
                            lastLoggedGearArea = areaId;
                            lastLoggedGearAvail = availability;
                            lastLoggedGearIntVal = intValue;
                            com.kooo.evcam.vehicle.VehicleSignalSnapshot.Gear decoded =
                                    (availability == 0 && intValue != null)
                                            ? com.kooo.evcam.vehicle.VehicleSignalSnapshot.Gear.fromVhalValue(intValue)
                                            : com.kooo.evcam.vehicle.VehicleSignalSnapshot.Gear.UNKNOWN;
                            AppLog.i(TAG, "VHAL 挡位诊断: prop="
                                    + com.kooo.evcam.vehicle.VehicleSignalCategory.formatPropertyId(propId)
                                    + ", area=" + areaId
                                    + ", avail=" + availability
                                    + ", raw=" + intValue
                                    + ", parsed=" + decoded);
                        }
                    }
                });
        if (nowUptime - lastBatchDiagUptimeMs >= 15_000L) {
            lastBatchDiagUptimeMs = nowUptime;
            AppLog.d(TAG, "VHAL batch 心跳: bytes=" + data.length + ", props=" + propCount[0]);
        }
    }

    /**
     * 处理转向灯事件
     */
    private void handleTurnSignalEvent(int direction) {
        if (direction == lastSignalState) return;

        AppLog.d(TAG, "Turn signal changed: " + lastSignalState + " -> " + direction);

        int previousDirection = lastSignalState;
        lastSignalState = direction;

        mainHandler.post(() -> {
            if (listener == null) return;

            switch (direction) {
                case VhalNative.DIR_LEFT:
                    listener.onTurnSignal("left", true);
                    break;
                case VhalNative.DIR_RIGHT:
                    listener.onTurnSignal("right", true);
                    break;
                case VhalNative.DIR_NONE:
                    if (previousDirection == VhalNative.DIR_LEFT) {
                        listener.onTurnSignal("left", false);
                    } else if (previousDirection == VhalNative.DIR_RIGHT) {
                        listener.onTurnSignal("right", false);
                    }
                    break;
            }
        });
    }

    /**
     * 处理定制键按钮事件
     */

    private void notifyConnectionState(boolean isConnected) {
        if (!isConnected) {
            com.kooo.evcam.vehicle.VehicleSignalHub.getInstance().reset();
        }
        PropertyListener primary = propertyListener;
        if (primary != null) {
            primary.onConnectionChanged(isConnected);
        }
        for (PropertyListener consumer : extraPropertyListeners) {
            if (consumer != null) {
                consumer.onConnectionChanged(isConnected);
            }
        }
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnectionStateChanged(isConnected);
            }

        });
    }

    // ==================== ByteMarshaller ====================

    /** gRPC marshaller that passes raw bytes (same as EVCC's approach) */
    private enum ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        INSTANCE;

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                // 不使用 readAllBytes()，Android 11 (API 30) 不支持该方法
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
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
