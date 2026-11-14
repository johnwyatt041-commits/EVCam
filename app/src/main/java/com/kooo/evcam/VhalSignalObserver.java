package com.kooo.evcam;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    /** Optional raw consumer; inactive unless a recording status watermark is enabled. */
    public interface PropertyListener {
        void onBatch(byte[] bytes);
        void onConnectionChanged(boolean connected);
    }
    private volatile PropertyListener propertyListener;
    private static final java.util.List<VhalSignalObserver> liveObservers = new java.util.ArrayList<>();
    private static volatile Runnable observerLifecycleListener;

    public void setPropertyListener(PropertyListener listener) { propertyListener = listener; }

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
        Runnable callback = observerLifecycleListener;
        if (callback != null) callback.run();
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

         // 副驾门
      // 左后门
     // 右后门

    // 重连参数
    private static final long RECONNECT_DELAY_MS = 3000;
    private static final long STREAM_TIMEOUT_MS = 120_000; // gRPC stream 最大沉默时间

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
            try {
                AppLog.d(TAG, "Connecting to vehicle API service...");
                boolean ok = connect();
                if (ok) {
                    AppLog.d(TAG, "Connected, starting property stream");
                    notifyConnectionState(true);
                    streamProperties(); // blocks until disconnected
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Connection error: " + e.getMessage());
            }

            connected = false;
            notifyConnectionState(false);
            disconnect();

            if (!running) break;

            try {
                AppLog.d(TAG, "Reconnecting in " + RECONNECT_DELAY_MS + "ms...");
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
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

            connected = true;
            AppLog.d(TAG, "Channel created, session_id=" + sessionId);
            return true;
        } catch (UnsatisfiedLinkError e) {
            AppLog.e(TAG, "Native method not found: " + e.getMessage());
            return false;
        } catch (Exception e) {
            AppLog.e(TAG, "Connect failed: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    private void disconnect() {
        connected = false;
        if (grpcChannel != null) {
            try {
                grpcChannel.shutdown();
                if (!grpcChannel.awaitTermination(2, TimeUnit.SECONDS)) {
                    grpcChannel.shutdownNow();
                }
            } catch (Exception ignored) {
                try { grpcChannel.shutdownNow(); } catch (Exception ignored2) {}
            }
            grpcChannel = null;
        }
    }

    /**
     * 开始属性流监听（阻塞直到断开或出错）
     */
    private void streamProperties() {
        if (grpcChannel == null) return;

        // 使用 CountDownLatch 等待流结束
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] streamError = {false};

        try {
            MethodDescriptor<byte[], byte[]> streamMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                    .setFullMethodName(VhalNative.getStreamMethod())
                    .setRequestMarshaller(ByteMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteMarshaller.INSTANCE)
                    .build();

            var call = grpcChannel.newCall(streamMethod, CallOptions.DEFAULT);

            ClientCalls.asyncServerStreamingCall(call, new byte[0], new StreamObserver<byte[]>() {
                @Override
                public void onNext(byte[] value) {
                    try {
                        processPropertyBatch(value);
                    } catch (Exception e) {
                        AppLog.e(TAG, "Failed to process property batch: " + e.getMessage());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    AppLog.e(TAG, "Property stream error: " + t.getMessage());
                    streamError[0] = true;
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    AppLog.d(TAG, "Property stream completed");
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
                    
                    if (grpcChannel == null || !running) return;
                    
                    try {
                        MethodDescriptor<byte[], byte[]> sendAllMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                                .setType(MethodDescriptor.MethodType.UNARY)
                                .setFullMethodName(VhalNative.getSendAllMethod())
                                .setRequestMarshaller(ByteMarshaller.INSTANCE)
                                .setResponseMarshaller(ByteMarshaller.INSTANCE)
                                .build();
                        var sendCall = grpcChannel.newCall(sendAllMethod, CallOptions.DEFAULT);
                        ClientCalls.blockingUnaryCall(sendCall, new byte[0]);
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

            // 等待流结束（带超时，防止半开连接卡死 reconnect 循环）
            if (!latch.await(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                AppLog.w(TAG, "Stream idle timeout (" + STREAM_TIMEOUT_MS + "ms), forcing reconnect");
            }

        } catch (UnsatisfiedLinkError e) {
            AppLog.e(TAG, "Native method not found: " + e.getMessage());
        } catch (Exception e) {
            AppLog.e(TAG, "Stream setup failed: " + e.getMessage());
        }
    }

    /**
     * 处理一批属性值更新（由 native 层解码）
     */
    private void processPropertyBatch(byte[] data) {
        PropertyListener consumer = propertyListener;
        if (running && consumer != null) consumer.onBatch(data);
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
        PropertyListener consumer = propertyListener;
        if (consumer != null) consumer.onConnectionChanged(isConnected);
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
