package com.kooo.evcam.remote;

import android.content.Context;

import com.kooo.evcam.AppLog;

import com.kooo.evcam.feishu.FeishuApiClient;
import com.kooo.evcam.remote.core.ChatIdentifier;
import com.kooo.evcam.remote.core.RemotePlatform;

import com.kooo.evcam.remote.handler.FeishuHandler;
import com.kooo.evcam.remote.handler.RemoteCommandHandler;

import java.util.EnumMap;
import java.util.Map;

/**
 * 远程命令分发器
 * 作为 MainActivity 调用远程功能的统一入口
 * 负责将命令分发到对应平台的处理器
 */
public class RemoteCommandDispatcher {
    private static final String TAG = "RemoteCommandDispatcher";
    
    private final Context context;
    private final Map<RemotePlatform, RemoteCommandHandler> handlers;
    
    // 摄像头控制器和状态监听器（由 MainActivity 提供）
    private RemoteCommandHandler.CameraController cameraController;
    private RemoteCommandHandler.RecordingStateListener recordingStateListener;
    
    public RemoteCommandDispatcher(Context context) {
        this.context = context.getApplicationContext();
        this.handlers = new EnumMap<>(RemotePlatform.class);
        // T10: 不在构造器中预创建 FeishuHandler，确保未配置/未启用飞书时纯后台录像零远程初始化
        AppLog.d(TAG, "RemoteCommandDispatcher 初始化完成（懒加载模式）");
    }

    private synchronized FeishuHandler getOrCreateFeishuHandler() {
        RemoteCommandHandler existing = handlers.get(RemotePlatform.FEISHU);
        if (existing instanceof FeishuHandler) {
            return (FeishuHandler) existing;
        }
        FeishuHandler handler = new FeishuHandler(context);
        if (cameraController != null) {
            handler.setCameraController(cameraController);
        }
        if (recordingStateListener != null) {
            handler.setRecordingStateListener(recordingStateListener);
        }
        handlers.put(RemotePlatform.FEISHU, handler);
        return handler;
    }

    public synchronized boolean hasFeishuHandler() {
        return handlers.containsKey(RemotePlatform.FEISHU);
    }
    
    // ==================== 依赖注入 ====================
    
    /**
     * 设置摄像头控制器
     * 必须在使用前调用
     */
    public void setCameraController(RemoteCommandHandler.CameraController controller) {
        this.cameraController = controller;
        // 传递给所有处理器
        for (RemoteCommandHandler handler : handlers.values()) {
            handler.setCameraController(controller);
        }
        AppLog.d(TAG, "CameraController 已设置");
    }
    
    /**
     * 设置录制状态监听器
     */
    public void setRecordingStateListener(RemoteCommandHandler.RecordingStateListener listener) {
        this.recordingStateListener = listener;
        // 传递给所有处理器
        for (RemoteCommandHandler handler : handlers.values()) {
            handler.setRecordingStateListener(listener);
        }
        AppLog.d(TAG, "RecordingStateListener 已设置");
    }
    
    /**
     * 设置飞书 API 客户端（传入 null 时仅清理已有客户端，不触发懒创建）
     */
    public synchronized void setFeishuApiClient(FeishuApiClient apiClient) {
        if (apiClient == null) {
            RemoteCommandHandler existing = handlers.get(RemotePlatform.FEISHU);
            if (existing instanceof FeishuHandler) {
                ((FeishuHandler) existing).setApiClient(null);
                AppLog.d(TAG, "Feishu API 客户端已清空");
            }
            return;
        }
        getOrCreateFeishuHandler().setApiClient(apiClient);
        AppLog.d(TAG, "Feishu API 客户端已设置");
    }
    
    // ==================== 命令分发 ====================
    
    /**
     * 启动远程录制
     */
    public void startRemoteRecording(RemotePlatform platform, ChatIdentifier chatId, int durationSeconds) {
        RemoteCommandHandler handler = getHandler(platform);
        if (handler != null) {
            AppLog.d(TAG, "分发远程录制命令到 " + platform.getDisplayName());
            handler.startRemoteRecording(chatId, durationSeconds);
        } else {
            AppLog.e(TAG, "未找到 " + platform.getDisplayName() + " 处理器");
        }
    }
    
    /**
     * 启动远程拍照
     */
    public void startRemotePhoto(RemotePlatform platform, ChatIdentifier chatId) {
        RemoteCommandHandler handler = getHandler(platform);
        if (handler != null) {
            AppLog.d(TAG, "分发远程拍照命令到 " + platform.getDisplayName());
            handler.startRemotePhoto(chatId);
        } else {
            AppLog.e(TAG, "未找到 " + platform.getDisplayName() + " 处理器");
        }
    }
    
    /**
     * 发送消息
     */
    public void sendMessage(RemotePlatform platform, ChatIdentifier chatId, String message) {
        RemoteCommandHandler handler = getHandler(platform);
        if (handler != null) {
            handler.sendMessage(chatId, message);
        }
    }
    
    /**
     * 发送错误消息
     */
    public void sendError(RemotePlatform platform, ChatIdentifier chatId, String error) {
        RemoteCommandHandler handler = getHandler(platform);
        if (handler != null) {
            handler.sendError(chatId, error);
        }
    }
    
    // ==================== 便捷方法 - 飞书 ====================
    
    /**
     * 飞书远程录制（便捷方法）
     */
    public void startFeishuRecording(String chatId, int durationSeconds) {
        ChatIdentifier id = ChatIdentifier.feishu(chatId);
        startRemoteRecording(RemotePlatform.FEISHU, id, durationSeconds);
    }
    
    /**
     * 飞书远程拍照（便捷方法）
     */
    public void startFeishuPhoto(String chatId) {
        ChatIdentifier id = ChatIdentifier.feishu(chatId);
        startRemotePhoto(RemotePlatform.FEISHU, id);
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 检查是否有任何平台正在进行远程录制
     */
    public boolean isAnyRemoteRecording() {
        for (RemoteCommandHandler handler : handlers.values()) {
            if (handler.isRemoteRecording()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查是否正在准备录制
     */
    public boolean isAnyPreparingRecording() {
        for (RemoteCommandHandler handler : handlers.values()) {
            if (handler.isPreparingRecording()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 获取当前正在进行远程录制的平台
     */
    public RemotePlatform getActiveRecordingPlatform() {
        for (Map.Entry<RemotePlatform, RemoteCommandHandler> entry : handlers.entrySet()) {
            if (entry.getValue().isRemoteRecording()) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    /**
     * 通知首次数据写入完成
     * 由 MainActivity 在检测到录制数据写入时调用
     */
    public void onFirstDataWritten() {
        for (RemoteCommandHandler handler : handlers.values()) {
            if (handler.isRemoteRecording()) {
                handler.onFirstDataWritten();
            }
        }
    }
    
    /**
     * 通知时间戳更新（Watchdog 重建录制后调用）
     * 由 MainActivity 在录制时间戳变化时调用
     */
    public void onTimestampUpdated(String newTimestamp) {
        for (RemoteCommandHandler handler : handlers.values()) {
            if (handler.isRemoteRecording()) {
                handler.onTimestampUpdated(newTimestamp);
            }
        }
    }

    /**
     * 通知录制启动致命故障或超时，清理远程状态并回复失败原因
     */
    public void onRecordingStartupFailure(String reason) {
        for (RemoteCommandHandler handler : handlers.values()) {
            if (handler.isRemoteRecording() || handler.isPreparingRecording()) {
                handler.onRecordingStartupFailure(reason);
            }
        }
    }
    
    // ==================== 辅助方法 ====================
    
    private RemoteCommandHandler getHandler(RemotePlatform platform) {
        return handlers.get(platform);
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        for (RemoteCommandHandler handler : handlers.values()) {
            handler.cleanup();
        }
        AppLog.d(TAG, "RemoteCommandDispatcher 资源已清理");
    }
}
