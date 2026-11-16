package com.kooo.evcam.feishu;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.feishu.pb.Pbbp2Frame;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 飞书 Bot 管理器（轻量级实现）
 * 使用 OkHttp WebSocket + 轻量级 Protobuf 实现，不依赖官方 SDK
 */
public class FeishuBotManager {
    private static final String TAG = "FeishuBotManager";
    private static final int PING_INTERVAL_MS = 120000; // 2分钟发送一次 ping
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 5000; // 5秒

    private final Context context;
    private final FeishuConfig config;
    private final FeishuApiClient apiClient;
    private final ConnectionCallback connectionCallback;
    private final Handler mainHandler;
    private final Gson gson;

    private final Object stateLock = new Object();
    private OkHttpClient wsClient;
    private WebSocket webSocket;
    private Thread connectThread;
    private Runnable reconnectRunnable;
    private long activeGeneration = 0L;
    private volatile boolean isRunning = false;
    private volatile boolean isStarting = false;
    private volatile boolean shouldStop = false;
    private int reconnectAttempts = 0;
    private CommandCallback currentCommandCallback;

    // WebSocket 连接信息
    private int serviceId = 0;
    private String connId = "";

    // 消息分包缓存
    private final FeishuPacketCache messageCache = new FeishuPacketCache();

    // 心跳定时器
    private Handler pingHandler;
    private Runnable pingRunnable;

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public interface CommandCallback {
        void onRecordCommand(String chatId, String messageId, int durationSeconds);
        void onPhotoCommand(String chatId, String messageId);
        String getStatusInfo();
        String onStartRecordingCommand();
        String onStopRecordingCommand();
        String onExitCommand(boolean confirmed);
        
        /**
         * 切换到前台
         * @return 执行结果消息
         */
        default String onForegroundCommand() {
            return "功能不可用";
        }
        
        /**
         * 切换到后台
         * @return 执行结果消息
         */
        default String onBackgroundCommand() {
            return "功能不可用";
        }
    }

    public FeishuBotManager(Context context, FeishuConfig config,
                            FeishuApiClient apiClient, ConnectionCallback callback) {
        this.context = context;
        this.config = config;
        this.apiClient = apiClient;
        this.connectionCallback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.gson = new Gson();
        this.pingHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 启动 WebSocket 连接
     */
    public void start(CommandCallback commandCallback) {
        String appId = config.getAppId();
        String appSecret = config.getAppSecret();

        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            AppLog.e(TAG, "App ID 或 App Secret 未配置");
            mainHandler.post(() -> connectionCallback.onError("App ID 或 App Secret 未配置"));
            return;
        }

        final long gen;
        synchronized (stateLock) {
            if (isRunning || isStarting) {
                AppLog.w(TAG, "Bot 已在运行或正在连接");
                return;
            }
            gen = ++activeGeneration;
            this.currentCommandCallback = commandCallback;
            this.shouldStop = false;
            this.isStarting = true;
            this.reconnectAttempts = 0;
        }

        AppLog.d(TAG, "正在初始化飞书 WebSocket 连接... (gen=" + gen + ")");
        startConnection(gen);
    }

    private boolean isGenerationCancelled(long generation) {
        synchronized (stateLock) {
            return shouldStop || generation != activeGeneration;
        }
    }

    /**
     * 内部方法：启动连接（绑定代际令牌，防止 stop() 后在途线程继续创建幽灵连接）
     */
    private void startConnection(final long gen) {
        Thread worker = new Thread(() -> {
            OkHttpClient localClient = null;
            try {
                if (isGenerationCancelled(gen)) {
                    return;
                }
                AppLog.d(TAG, "正在获取 WebSocket 连接地址... (gen=" + gen + ")");
                FeishuApiClient.WebSocketConnection wsInfo = apiClient.getWebSocketConnection();
                if (isGenerationCancelled(gen)) {
                    AppLog.d(TAG, "连接已被取消，中止建立 WebSocket (gen=" + gen + ")");
                    return;
                }
                String wsUrl = wsInfo.url;
                AppLog.d(TAG, "WebSocket URL: " + wsUrl);

                parseUrlParams(wsUrl);

                localClient = new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(0, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build();

                Request request = new Request.Builder()
                        .url(wsUrl)
                        .build();

                synchronized (stateLock) {
                    if (shouldStop || gen != activeGeneration) {
                        shutdownOkHttpClientQuietly(localClient);
                        return;
                    }
                    wsClient = localClient;
                    webSocket = localClient.newWebSocket(request, new FeishuWebSocketListener(gen));
                }
                AppLog.d(TAG, "WebSocket 连接请求已发送 (gen=" + gen + ")");

            } catch (Exception e) {
                if (localClient != null) {
                    synchronized (stateLock) {
                        if (wsClient == null) {
                            shutdownOkHttpClientQuietly(localClient);
                        }
                    }
                }
                if (isGenerationCancelled(gen)) {
                    AppLog.d(TAG, "飞书已主动停止，忽略在途连接异常 (gen=" + gen + ")");
                    return;
                }
                AppLog.e(TAG, "启动 WebSocket 连接失败", e);
                handleConnectionError(gen, e.getMessage());
            } finally {
                synchronized (stateLock) {
                    if (connectThread == Thread.currentThread()) {
                        connectThread = null;
                    }
                }
            }
        }, "Feishu-Connect-" + gen);
        synchronized (stateLock) {
            if (shouldStop || gen != activeGeneration) {
                return;
            }
            connectThread = worker;
        }
        worker.start();
    }

    /**
     * 从 WebSocket URL 中解析参数
     */
    private void parseUrlParams(String wsUrl) {
        try {
            // 将 wss:// 替换为 https:// 以便使用 Uri 解析
            String httpUrl = wsUrl.replace("wss://", "https://").replace("ws://", "http://");
            Uri uri = Uri.parse(httpUrl);

            String serviceIdStr = uri.getQueryParameter("service_id");
            if (serviceIdStr != null) {
                serviceId = Integer.parseInt(serviceIdStr);
            }

            connId = uri.getQueryParameter("device_id");
            if (connId == null) {
                connId = "";
            }

            AppLog.d(TAG, "解析 URL 参数: serviceId=" + serviceId + ", connId=" + connId);
        } catch (Exception e) {
            AppLog.e(TAG, "解析 URL 参数失败", e);
        }
    }

    /**
     * WebSocket 监听器（绑定代际令牌，旧会话关闭或失败回调绝不污染新会话或触发幽灵重连）
     */
    private class FeishuWebSocketListener extends WebSocketListener {
        private final long generation;

        FeishuWebSocketListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket socket, Response response) {
            synchronized (stateLock) {
                if (shouldStop || generation != activeGeneration || socket != webSocket) {
                    AppLog.d(TAG, "忽略过期代际 WebSocket onOpen (gen=" + generation + ")");
                    socket.close(1000, "Stale generation");
                    return;
                }
                isStarting = false;
                isRunning = true;
                reconnectAttempts = 0;
            }
            AppLog.d(TAG, "WebSocket 连接已建立 (gen=" + generation + ")");
            startPingTimer(generation);
            mainHandler.post(() -> connectionCallback.onConnected());
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            if (isGenerationCancelled(generation)) {
                return;
            }
            AppLog.d(TAG, "收到文本消息: " + text);
        }

        @Override
        public void onMessage(WebSocket socket, ByteString bytes) {
            if (isGenerationCancelled(generation)) {
                return;
            }
            AppLog.d(TAG, "收到二进制消息: " + bytes.size() + " 字节");
            processProtobufMessage(bytes.toByteArray());
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            AppLog.d(TAG, "WebSocket 正在关闭: code=" + code + ", reason=" + reason);
        }

        @Override
        public void onClosed(WebSocket socket, int code, String reason) {
            boolean cancelled;
            synchronized (stateLock) {
                if (generation != activeGeneration) {
                    return;
                }
                isStarting = false;
                isRunning = false;
                cancelled = shouldStop;
            }
            AppLog.d(TAG, "WebSocket 已关闭: code=" + code + ", reason=" + reason);
            stopPingTimer();

            if (!cancelled) {
                attemptReconnect(generation);
            } else {
                mainHandler.post(() -> connectionCallback.onDisconnected());
            }
        }

        @Override
        public void onFailure(WebSocket socket, Throwable t, Response response) {
            synchronized (stateLock) {
                if (generation != activeGeneration) {
                    return;
                }
                isStarting = false;
                isRunning = false;
                if (shouldStop) {
                    AppLog.d(TAG, "飞书已停止，忽略断开故障回调 (gen=" + generation + ")");
                    return;
                }
            }
            AppLog.e(TAG, "WebSocket 连接失败", t);
            stopPingTimer();
            handleConnectionError(generation, t != null ? t.getMessage() : "unknown");
        }
    }

    /**
     * 处理 Protobuf 消息
     */
    private void processProtobufMessage(byte[] data) {
        try {
            Pbbp2Frame frame = Pbbp2Frame.parseFrom(data);
            AppLog.d(TAG, "解析帧: " + frame.toString());

            if (frame.isControlFrame()) {
                handleControlFrame(frame);
            } else if (frame.isDataFrame()) {
                handleDataFrame(frame, data);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "处理 Protobuf 消息失败", e);
        }
    }

    /**
     * 处理控制帧
     */
    private void handleControlFrame(Pbbp2Frame frame) {
        String type = frame.getMessageType();

        if (Pbbp2Frame.TYPE_PING.equals(type)) {
            AppLog.d(TAG, "收到服务器 ping");
            return;
        }

        if (Pbbp2Frame.TYPE_PONG.equals(type)) {
            AppLog.d(TAG, "收到心跳响应 pong");
            // 可以从 payload 中获取配置更新
            return;
        }
    }

    /**
     * 处理数据帧
     */
    private void handleDataFrame(Pbbp2Frame frame, byte[] rawData) {
        try {
            String msgId = frame.getHeaderValue(Pbbp2Frame.HEADER_MESSAGE_ID);
            String traceId = frame.getHeaderValue(Pbbp2Frame.HEADER_TRACE_ID);
            String sumStr = frame.getHeaderValue(Pbbp2Frame.HEADER_SUM);
            String seqStr = frame.getHeaderValue(Pbbp2Frame.HEADER_SEQ);
            String type = frame.getMessageType();

            int sum = sumStr != null ? Integer.parseInt(sumStr) : 1;
            int seq = seqStr != null ? Integer.parseInt(seqStr) : 0;

            byte[] payload = frame.getPayload();

            // 处理分包消息
            if (sum > 1) {
                payload = combinePackets(msgId, sum, seq, payload);
                if (payload == null) {
                    // 还有包未到达
                    return;
                }
            }

            AppLog.d(TAG, "数据帧类型: " + type + ", msgId: " + msgId + ", traceId: " + traceId);

            // 处理事件消息
            if (Pbbp2Frame.TYPE_EVENT.equals(type)) {
                String payloadStr = new String(payload, StandardCharsets.UTF_8);
                AppLog.d(TAG, "事件 payload: " + payloadStr);

                long startTime = System.currentTimeMillis();
                processEventPayload(payloadStr);
                long bizRt = System.currentTimeMillis() - startTime;

                // 发送响应
                sendEventResponse(frame, bizRt);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "处理数据帧失败", e);
        }
    }

    /**
     * 合并分包消息
     */
    private byte[] combinePackets(String msgId, int sum, int seq, byte[] data) {
        return messageCache.add(msgId, sum, seq, data);
    }

    /**
     * 发送事件响应
     */
    private void sendEventResponse(Pbbp2Frame requestFrame, long bizRt) {
        try {
            // 构建响应 JSON
            JsonObject response = new JsonObject();
            response.addProperty("code", 200);
            byte[] responsePayload = gson.toJson(response).getBytes(StandardCharsets.UTF_8);

            // 复制请求帧并设置响应 payload
            Pbbp2Frame responseFrame = requestFrame.copyWithPayload(responsePayload);
            responseFrame.addHeader(Pbbp2Frame.HEADER_BIZ_RT, String.valueOf(bizRt));

            byte[] frameBytes = responseFrame.toByteArray();
            webSocket.send(ByteString.of(frameBytes));
            AppLog.d(TAG, "已发送事件响应");
        } catch (Exception e) {
            AppLog.e(TAG, "发送事件响应失败", e);
        }
    }

    /**
     * 处理事件 payload
     */
    private void processEventPayload(String payloadStr) {
        try {
            JsonObject payload = gson.fromJson(payloadStr, JsonObject.class);

            // 检查是否有 header 和 event
            if (!payload.has("header") || !payload.has("event")) {
                AppLog.d(TAG, "非事件消息格式");
                return;
            }

            JsonObject header = payload.getAsJsonObject("header");
            JsonObject event = payload.getAsJsonObject("event");

            String eventType = header.has("event_type") ? header.get("event_type").getAsString() : "";
            AppLog.d(TAG, "事件类型: " + eventType);

            // 只处理消息接收事件
            if (!"im.message.receive_v1".equals(eventType)) {
                AppLog.d(TAG, "非消息事件，忽略: " + eventType);
                return;
            }

            // 解析消息
            JsonObject messageObj = event.getAsJsonObject("message");
            String messageType = messageObj.has("message_type") ? messageObj.get("message_type").getAsString() : "";
            String chatId = messageObj.has("chat_id") ? messageObj.get("chat_id").getAsString() : "";
            String messageId = messageObj.has("message_id") ? messageObj.get("message_id").getAsString() : "";
            String chatType = messageObj.has("chat_type") ? messageObj.get("chat_type").getAsString() : "";

            // 获取发送者信息
            String senderId = "";
            if (event.has("sender")) {
                JsonObject sender = event.getAsJsonObject("sender");
                if (sender.has("sender_id")) {
                    JsonObject senderIdObj = sender.getAsJsonObject("sender_id");
                    senderId = senderIdObj.has("open_id") ? senderIdObj.get("open_id").getAsString() : "";
                }
            }

            AppLog.d(TAG, "消息类型: " + messageType + ", chatId: " + chatId + ", senderId: " + senderId);

            // 检查用户是否被允许
            if (!config.isUserIdAllowed(senderId)) {
                AppLog.d(TAG, "用户不在白名单中: " + senderId);
                return;
            }

            // 只处理文本消息
            if (!"text".equals(messageType)) {
                AppLog.d(TAG, "非文本消息，忽略: " + messageType);
                return;
            }

            // 解析消息内容
            String content = messageObj.has("content") ? messageObj.get("content").getAsString() : "";
            Map<String, String> contentMap = new HashMap<>();
            try {
                contentMap = gson.fromJson(content, new TypeToken<Map<String, String>>() {}.getType());
            } catch (Exception e) {
                AppLog.e(TAG, "解析消息内容失败", e);
                return;
            }

            String text = contentMap.get("text");
            if (text == null || text.isEmpty()) {
                AppLog.d(TAG, "消息内容为空");
                return;
            }

            AppLog.d(TAG, "收到文本消息: " + text);

            // 处理指令
            handleCommand(chatId, messageId, chatType, text);

        } catch (Exception e) {
            AppLog.e(TAG, "处理事件 payload 失败", e);
        }
    }

    /**
     * 处理指令
     */
    private void handleCommand(String chatId, String messageId, String chatType, String content) {
        // 移除 @机器人 部分
        String command = content.replaceAll("@\\S+\\s*", "").trim();
        AppLog.d(TAG, "解析指令: " + command);

        try {
            if (command.startsWith("录制") || command.toLowerCase().startsWith("record")) {
                int durationSeconds = parseRecordDuration(command);
                AppLog.d(TAG, "收到录制指令，时长: " + durationSeconds + " 秒");

                String confirmMsg = String.format("收到录制指令，开始录制 %d 秒视频...", durationSeconds);
                sendReplyAndThen(chatId, messageId, chatType, confirmMsg, () -> {
                    WakeUpHelper.launchForRecordingFeishu(context, chatId, messageId, durationSeconds);
                });

            } else if ("拍照".equals(command) || "photo".equalsIgnoreCase(command)) {
                AppLog.d(TAG, "收到拍照指令");

                sendReplyAndThen(chatId, messageId, chatType, "收到拍照指令，正在拍照...", () -> {
                    WakeUpHelper.launchForPhotoFeishu(context, chatId, messageId);
                });

            } else if ("状态".equals(command) || "status".equalsIgnoreCase(command)) {
                AppLog.d(TAG, "收到状态指令");
                String statusInfo = currentCommandCallback != null ?
                        currentCommandCallback.getStatusInfo() : "✅ Bot 正在运行中";
                sendReply(chatId, messageId, chatType, statusInfo);

            } else if ("启动录制".equals(command) || "开始录制".equals(command) ||
                       "start".equalsIgnoreCase(command)) {
                AppLog.d(TAG, "收到启动录制指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onStartRecordingCommand();
                    sendReply(chatId, messageId, chatType, result);
                } else {
                    sendReply(chatId, messageId, chatType, "❌ 功能不可用");
                }

            } else if ("结束录制".equals(command) || "停止录制".equals(command) ||
                       "stop".equalsIgnoreCase(command)) {
                AppLog.d(TAG, "收到结束录制指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onStopRecordingCommand();
                    sendReply(chatId, messageId, chatType, result);
                } else {
                    sendReply(chatId, messageId, chatType, "❌ 功能不可用");
                }

            } else if ("退出".equals(command) || "exit".equalsIgnoreCase(command)) {
                AppLog.d(TAG, "收到退出指令（需二次确认）");
                sendReply(chatId, messageId, chatType,
                    "⚠️ 确认要退出 EVCam 吗？\n\n" +
                    "退出后将停止所有录制和远程服务。\n" +
                    "发送「确认退出」执行退出操作。");

            } else if ("确认退出".equals(command)) {
                AppLog.d(TAG, "收到确认退出指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onExitCommand(true);
                    sendReply(chatId, messageId, chatType, result);
                } else {
                    sendReply(chatId, messageId, chatType, "❌ 功能不可用");
                }

            } else if ("前台".equals(command) || "foreground".equalsIgnoreCase(command)) {
                // 前台指令：将应用切换到前台
                AppLog.d(TAG, "收到前台指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onForegroundCommand();
                    sendReply(chatId, messageId, chatType, result);
                } else {
                    sendReply(chatId, messageId, chatType, "❌ 功能不可用");
                }

            } else if ("后台".equals(command) || "background".equalsIgnoreCase(command)) {
                // 后台指令：将应用切换到后台
                AppLog.d(TAG, "收到后台指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onBackgroundCommand();
                    sendReply(chatId, messageId, chatType, result);
                } else {
                    sendReply(chatId, messageId, chatType, "❌ 功能不可用");
                }

            } else if ("帮助".equals(command) || "help".equalsIgnoreCase(command)) {
                sendReply(chatId, messageId, chatType,
                    "📋 EVCam 远程控制\n" +
                    "━━━━━━━━━━━━━━\n\n" +
                    "📹 远程录制\n" +
                    "• 录制 - 录制60秒视频\n" +
                    "• 录制30 - 录制30秒视频\n\n" +
                    "▶️ 持续录制\n" +
                    "• 启动录制 - 开始持续录制\n" +
                    "• 结束录制 - 停止录制\n\n" +
                    "📷 拍照\n" +
                    "• 拍照 - 拍摄照片\n\n" +
                    "🔄 前后台切换\n" +
                    "• 前台 - 切换到前台\n" +
                    "• 后台 - 切换到后台\n\n" +
                    "ℹ️ 其他\n" +
                    "• 状态 - 查看应用状态\n" +
                    "• 退出 - 退出应用\n" +
                    "• 帮助 - 显示此帮助");

            } else {
                AppLog.d(TAG, "未识别的指令: " + command);
                sendReply(chatId, messageId, chatType, "未识别的指令。发送「帮助」查看可用指令。");
            }

        } catch (Exception e) {
            AppLog.e(TAG, "处理指令失败", e);
        }
    }

    /**
     * 解析录制时长
     */
    private int parseRecordDuration(String command) {
        String durationStr = command.replaceAll("(?i)(录制|record)", "").trim();

        if (durationStr.isEmpty()) {
            return 60; // 默认 1 分钟
        }

        try {
            int duration = Integer.parseInt(durationStr);
            if (duration < 5) return 5;
            if (duration > 600) return 600;
            return duration;
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    /**
     * 发送回复消息
     */
    private void sendReply(String chatId, String messageId, String chatType, String text) {
        new Thread(() -> {
            try {
                if ("p2p".equals(chatType)) {
                    // 私聊：使用 create 发送
                    apiClient.sendTextMessage("chat_id", chatId, text);
                } else {
                    // 群聊：使用 reply 回复
                    apiClient.replyMessage(messageId, text);
                }
                AppLog.d(TAG, "消息发送成功");
            } catch (Exception e) {
                AppLog.e(TAG, "发送消息失败", e);
            }
        }).start();
    }

    /**
     * 发送回复消息并执行回调
     */
    private void sendReplyAndThen(String chatId, String messageId, String chatType, String text, Runnable callback) {
        new Thread(() -> {
            try {
                if ("p2p".equals(chatType)) {
                    apiClient.sendTextMessage("chat_id", chatId, text);
                } else {
                    apiClient.replyMessage(messageId, text);
                }
                AppLog.d(TAG, "回复消息已发送");

                if (callback != null) {
                    callback.run();
                }
            } catch (Exception e) {
                AppLog.e(TAG, "发送回复失败", e);
                if (callback != null) {
                    callback.run();
                }
            }
        }).start();
    }

    /**
     * 启动心跳定时器
     */
    private void startPingTimer(final long gen) {
        stopPingTimer();
        pingRunnable = new Runnable() {
            @Override
            public void run() {
                messageCache.prune();
                WebSocket targetSocket;
                synchronized (stateLock) {
                    if (shouldStop || gen != activeGeneration || !isRunning || webSocket == null) {
                        return;
                    }
                    targetSocket = webSocket;
                }
                try {
                    Pbbp2Frame pingFrame = Pbbp2Frame.createPingFrame(serviceId);
                    byte[] frameBytes = pingFrame.toByteArray();
                    targetSocket.send(ByteString.of(frameBytes));
                    AppLog.d(TAG, "发送心跳 ping");
                } catch (Exception e) {
                    AppLog.e(TAG, "发送心跳失败", e);
                }

                synchronized (stateLock) {
                    if (!shouldStop && gen == activeGeneration && isRunning && pingRunnable == this) {
                        pingHandler.postDelayed(this, PING_INTERVAL_MS);
                    }
                }
            }
        };
        pingHandler.postDelayed(pingRunnable, PING_INTERVAL_MS);
    }

    /**
     * 停止并彻底清理心跳定时器
     */
    private void stopPingTimer() {
        pingHandler.removeCallbacksAndMessages(null);
        pingRunnable = null;
    }

    private void cancelReconnectTimerLocked() {
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    private void scheduleReconnect(final long gen, String logPrefix) {
        Runnable scheduled = () -> {
            synchronized (stateLock) {
                reconnectRunnable = null;
                if (shouldStop || gen != activeGeneration) {
                    return;
                }
                isStarting = true;
            }
            startConnection(gen);
        };
        synchronized (stateLock) {
            if (shouldStop || gen != activeGeneration) {
                return;
            }
            cancelReconnectTimerLocked();
            reconnectAttempts++;
            AppLog.d(TAG, logPrefix + "将在 " + RECONNECT_DELAY_MS + "ms 后尝试第 " + reconnectAttempts + " 次重连 (gen=" + gen + ")");
            reconnectRunnable = scheduled;
            mainHandler.postDelayed(scheduled, RECONNECT_DELAY_MS);
        }
    }

    /**
     * 处理连接错误
     */
    private void handleConnectionError(final long gen, String errorMsg) {
        boolean maxReached = false;
        synchronized (stateLock) {
            if (shouldStop || gen != activeGeneration) {
                return;
            }
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                maxReached = true;
            }
        }
        if (!maxReached) {
            scheduleReconnect(gen, "连接错误，");
        } else {
            AppLog.w(TAG, "达到最大重连次数（" + MAX_RECONNECT_ATTEMPTS + "），连接失败");
            mainHandler.post(() -> connectionCallback.onError("连接失败: " + errorMsg));
        }
    }

    /**
     * 尝试重连
     */
    private void attemptReconnect(final long gen) {
        boolean maxReached = false;
        synchronized (stateLock) {
            if (shouldStop || gen != activeGeneration) {
                return;
            }
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                maxReached = true;
            }
        }
        if (!maxReached) {
            scheduleReconnect(gen, "连接断开，");
        } else {
            mainHandler.post(() -> connectionCallback.onDisconnected());
        }
    }

    private static void shutdownOkHttpClientQuietly(OkHttpClient client) {
        if (client == null) {
            return;
        }
        try {
            client.dispatcher().cancelAll();
        } catch (Exception ignored) {
        }
        try {
            client.connectionPool().evictAll();
        } catch (Exception ignored) {
        }
        try {
            client.dispatcher().executorService().shutdownNow();
        } catch (Exception ignored) {
        }
    }

    /**
     * 停止 Bot：递增代际、清理重连与心跳定时器、取消在途请求及 WebSocket 连接池。
     */
    public void stop() {
        AppLog.d(TAG, "正在彻底停止 Bot...");
        Thread pendingThread;
        Runnable pendingReconnect;
        WebSocket socketToClose;
        OkHttpClient clientToShutdown;
        boolean wasActiveOrStarting;

        synchronized (stateLock) {
            activeGeneration++;
            shouldStop = true;
            wasActiveOrStarting = isRunning || isStarting;
            isRunning = false;
            isStarting = false;
            reconnectAttempts = 0;

            pendingThread = connectThread;
            connectThread = null;
            pendingReconnect = reconnectRunnable;
            reconnectRunnable = null;
            socketToClose = webSocket;
            webSocket = null;
            clientToShutdown = wsClient;
            wsClient = null;
        }

        stopPingTimer();
        if (pendingReconnect != null) {
            mainHandler.removeCallbacks(pendingReconnect);
        }
        if (pendingThread != null) {
            pendingThread.interrupt();
        }
        if (apiClient != null) {
            apiClient.cancelAllRequests();
        }
        if (socketToClose != null) {
            try {
                socketToClose.close(1000, "Normal closure");
            } catch (Exception ignored) {
            }
            try {
                socketToClose.cancel();
            } catch (Exception ignored) {
            }
        }
        shutdownOkHttpClientQuietly(clientToShutdown);
        messageCache.clear();

        if (wasActiveOrStarting && connectionCallback != null) {
            mainHandler.post(() -> connectionCallback.onDisconnected());
        }
        AppLog.d(TAG, "Bot 已彻底停止");
    }

    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 检查是否正在建立连接或运行中
     */
    public boolean isStartingOrRunning() {
        synchronized (stateLock) {
            return isRunning || isStarting || reconnectRunnable != null;
        }
    }
}
