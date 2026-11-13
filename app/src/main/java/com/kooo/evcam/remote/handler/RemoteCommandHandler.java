package com.kooo.evcam.remote.handler;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.FloatingWindowService;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.remote.core.ChatIdentifier;
import com.kooo.evcam.remote.core.RecordingContext;
import com.kooo.evcam.remote.core.RemotePlatform;
import com.kooo.evcam.remote.core.RemoteUploadCallback;
import com.kooo.evcam.remote.upload.MediaFileFinder;
import com.kooo.evcam.remote.upload.MediaUploadService;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 远程命令处理器抽象基类
 * 包含所有平台公共的远程录制/拍照逻辑（约90%的代码）
 * 子类只需实现平台特定的方法
 */
public abstract class RemoteCommandHandler {
    private static final String TAG = "RemoteCommandHandler";
    
    protected final Context context;
    protected final AppConfig appConfig;
    protected final MediaFileFinder mediaFileFinder;
    protected final Handler mainHandler;
    
    // 状态管理
    private volatile boolean isRemoteRecording = false;
    private volatile boolean isPreparingRecording = false;
    private RecordingContext currentContext = null;
    
    // 自动停止相关
    private Handler autoStopHandler;
    private Runnable autoStopRunnable;
    private int pendingDurationSeconds = 0;
    
    // 摄像头控制器接口（由 MainActivity 提供）
    private CameraController cameraController;
    
    // 录制状态监听器（由 MainActivity 提供）
    private RecordingStateListener recordingStateListener;
    
    /**
     * 摄像头控制器接口
     * 由 MainActivity 实现，提供摄像头操作能力
     */
    public interface CameraController {
        boolean isRecording();
        boolean hasAvailableCameras();
        boolean startRecording(String timestamp);
        void stopRecording(boolean skipTransfer);
        void stopRecording(boolean skipTransfer, Runnable onStopped);
        void takePicture(String timestamp);
        void stopRecordingTimer();
        void stopBlinkAnimation();
        void startRecording();  // 恢复手动录制
        void setSegmentDurationOverride(long durationMs);  // 设置分段时长覆盖（用于远程录制）
        void clearSegmentDurationOverride();  // 清除分段时长覆盖
    }
    
    /**
     * 录制状态监听器
     * 由 MainActivity 实现，用于更新 UI 状态
     */
    public interface RecordingStateListener {
        void onRemoteRecordingStart();
        void onRemoteRecordingStop();
        void onPreparing();
        void onPreparingComplete();
        void returnToBackgroundIfRemoteWakeUp();
        boolean isRemoteWakeUp();
    }
    
    public RemoteCommandHandler(Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(context);
        this.mediaFileFinder = new MediaFileFinder(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.autoStopHandler = new Handler(Looper.getMainLooper());
    }
    
    // ==================== 依赖注入 ====================
    
    public void setCameraController(CameraController controller) {
        this.cameraController = controller;
    }
    
    public void setRecordingStateListener(RecordingStateListener listener) {
        this.recordingStateListener = listener;
    }
    
    // ==================== 状态查询 ====================
    
    public boolean isRemoteRecording() {
        return isRemoteRecording;
    }
    
    public boolean isPreparingRecording() {
        return isPreparingRecording;
    }
    
    public RecordingContext getCurrentContext() {
        return currentContext;
    }
    
    // ==================== 远程录制 - 公共逻辑 ====================
    
    /**
     * 启动远程录制
     * 这是主入口方法，包含完整的录制流程
     */
    public void startRemoteRecording(ChatIdentifier chatId, int durationSeconds) {
        String platformName = getPlatformName();
        AppLog.d(TAG, platformName + " 远程录制: chatId=" + chatId.getId() + ", duration=" + durationSeconds);
        
        // 1. 检查是否已有远程录制任务正在进行
        if (isRemoteRecording || isPreparingRecording) {
            AppLog.w(TAG, "远程录制任务正在进行中，拒绝新的" + platformName + "录制指令");
            sendError(chatId, "远程录制任务正在进行中，请等待完成后再试");
            return;
        }
        
        // 2. 检查摄像头控制器
        if (cameraController == null) {
            AppLog.e(TAG, "摄像头控制器未设置");
            sendError(chatId, "摄像头未初始化");
            returnToBackgroundIfNeeded();
            return;
        }
        
        // 3. 检查是否有已连接的摄像头
        if (!cameraController.hasAvailableCameras()) {
            AppLog.e(TAG, "没有可用的相机");
            sendError(chatId, "没有可用的相机");
            returnToBackgroundIfNeeded();
            return;
        }
        
        // 4. 生成统一的时间戳
        String timestamp = generateTimestamp();
        AppLog.d(TAG, platformName + " 录制统一时间戳: " + timestamp);
        
        // 5. 创建录制上下文
        currentContext = new RecordingContext(chatId, durationSeconds, timestamp);
        
        isPreparingRecording = true;
        if (cameraController.isRecording()) {
            currentContext.setWasManualRecordingBefore(true);
            cameraController.stopRecordingTimer();
            cameraController.stopBlinkAnimation();
            cameraController.stopRecording(false, () -> beginRemoteRecording(timestamp, durationSeconds));
        } else {
            beginRemoteRecording(timestamp, durationSeconds);
        }
    }

    private void beginRemoteRecording(String timestamp, int durationSeconds) {
        if (cameraController == null || currentContext == null) return;
        isRemoteRecording = true;
        cameraController.setSegmentDurationOverride((durationSeconds + 30L) * 1000L);
        if (cameraController.startRecording(timestamp)) {
            onRecordingStarted(currentContext, durationSeconds);
        } else {
            onRecordingFailed(currentContext);
        }
    }

    /**
     * 录制成功启动后的处理
     */
    private void onRecordingStarted(RecordingContext ctx, int durationSeconds) {
        String platformName = getPlatformName();
        AppLog.d(TAG, platformName + " 远程录制已开始");
        isPreparingRecording = true;
        
        // 通知监听器
        if (recordingStateListener != null) {
            recordingStateListener.onRemoteRecordingStart();
            recordingStateListener.onPreparing();
        }
        
        // 启动前台服务保护
        CameraForegroundService.start(context, platformName + " 远程录制", 
                "正在录制 " + durationSeconds + " 秒视频...");
        
        // 发送录制状态广播
        FloatingWindowService.sendRecordingStateChanged(context, true);
        
        // 设置自动停止定时器
        setupAutoStop(ctx, durationSeconds);
    }
    
    /**
     * 设置自动停止定时器
     */
    private void setupAutoStop(RecordingContext ctx, int durationSeconds) {
        autoStopRunnable = () -> {
            if (cameraController != null) {
                cameraController.stopRecording(true, () -> finishRemoteRecording(ctx));
            }
        };

        // 定时器延迟到首次数据写入后启动
        pendingDurationSeconds = durationSeconds;
        AppLog.d(TAG, getPlatformName() + " 录制定时器将在首次数据写入后启动，时长: " + durationSeconds + " 秒");
    }
    
    private void finishRemoteRecording(RecordingContext ctx) {
        if (cameraController != null) cameraController.clearSegmentDurationOverride();
        CameraForegroundService.stop(context);
        FloatingWindowService.sendRecordingStateChanged(context, false);
        isPreparingRecording = false;
        isRemoteRecording = false;
        if (recordingStateListener != null) recordingStateListener.onRemoteRecordingStop();
        handleRecordingComplete(ctx);
    }

    /**
     * 通知首次数据写入完成，启动定时器
     * 由 MainActivity 在检测到录制数据写入时调用
     */
    public void onFirstDataWritten() {
        if (pendingDurationSeconds > 0 && autoStopRunnable != null) {
            AppLog.d(TAG, "首次数据写入，启动定时器: " + pendingDurationSeconds + " 秒");
            autoStopHandler.postDelayed(autoStopRunnable, pendingDurationSeconds * 1000L);
            pendingDurationSeconds = 0;
        }
    }
    
    /**
     * 通知时间戳更新（Watchdog 重建录制后调用）
     * 由 MainActivity 在录制时间戳变化时调用
     */
    public void onTimestampUpdated(String newTimestamp) {
        if (isRemoteRecording && currentContext != null) {
            String oldTimestamp = currentContext.getTimestamp();
            currentContext.setTimestamp(newTimestamp);
            AppLog.d(TAG, getPlatformName() + " 远程录制时间戳更新: " + oldTimestamp + " -> " + newTimestamp);
        }
    }
    
    /**
     * 录制完成后的处理（上传和恢复）
     */
    private void handleRecordingComplete(RecordingContext ctx) {
        final boolean shouldResumeRecording = ctx.wasManualRecordingBefore();
        
        // 上传视频
        uploadVideos(ctx);
        
        // 恢复手动录制（如果之前有）
        if (shouldResumeRecording && cameraController != null) {
            mainHandler.postDelayed(() -> {
                if (!isRemoteRecording && cameraController != null && !cameraController.isRecording()) {
                    AppLog.d(TAG, "恢复之前的手动录制");
                    cameraController.startRecording();
                }
            }, 500);
        }
    }
    
    /**
     * 录制启动失败的处理
     */
    private void onRecordingFailed(RecordingContext ctx) {
        isPreparingRecording = false;
        String platformName = getPlatformName();
        AppLog.e(TAG, platformName + " 远程录制启动失败");
        isRemoteRecording = false;
        
        // 清除分段时长覆盖
        if (cameraController != null) {
            cameraController.clearSegmentDurationOverride();
        }
        
        // 如果之前有手动录制，尝试恢复
        if (ctx.wasManualRecordingBefore() && cameraController != null) {
            AppLog.d(TAG, platformName + " 远程录制启动失败，尝试恢复手动录制");
            cameraController.startRecording();
        }
        
        sendError(ctx.getChatId(), "录制启动失败");
        returnToBackgroundIfNeeded();
    }
    
    // ==================== 远程拍照 - 公共逻辑 ====================
    
    /**
     * 启动远程拍照
     */
    public void startRemotePhoto(ChatIdentifier chatId) {
        String platformName = getPlatformName();
        AppLog.d(TAG, platformName + " 远程拍照: chatId=" + chatId.getId());
        
        // 1. 检查摄像头控制器
        if (cameraController == null) {
            AppLog.e(TAG, "摄像头控制器未设置");
            sendError(chatId, "摄像头未初始化");
            returnToBackgroundIfNeeded();
            return;
        }
        
        // 2. 检查摄像头连接
        if (!cameraController.hasAvailableCameras()) {
            AppLog.e(TAG, "没有可用的相机");
            sendError(chatId, "没有可用的相机");
            returnToBackgroundIfNeeded();
            return;
        }
        
        // 3. 生成时间戳
        String timestamp = generateTimestamp();
        AppLog.d(TAG, platformName + " 拍照时间戳: " + timestamp);
        
        // 4. 执行拍照
        cameraController.takePicture(timestamp);
        AppLog.d(TAG, platformName + " 远程拍照已执行");
        
        // 5. 等待拍照完成后上传（5秒延迟）
        final String finalTimestamp = timestamp;
        mainHandler.postDelayed(() -> {
            uploadPhotos(chatId, finalTimestamp);
        }, 8000);
    }
    
    // ==================== 上传逻辑 ====================
    
    /**
     * 上传录制的视频
     */
    private void uploadVideos(RecordingContext ctx) {
        String platformName = getPlatformName();
        List<String> allTimestamps = ctx.getAllTimestamps();
        ChatIdentifier chatId = ctx.getChatId();
        
        // 检查 API 客户端
        if (!isApiClientReady()) {
            AppLog.e(TAG, platformName + " API 客户端未初始化");
            returnToBackgroundIfNeeded();
            return;
        }
        
        // 查找视频文件（使用所有时间戳，包括 Watchdog 重建前后的）
        List<File> videoFiles = mediaFileFinder.findVideoFiles(allTimestamps);
        if (videoFiles.isEmpty()) {
            AppLog.e(TAG, "未找到录制的视频文件，时间戳: " + allTimestamps);
            sendError(chatId, "未找到录制的视频文件");
            returnToBackgroundIfNeeded();
            return;
        }
        
        AppLog.d(TAG, "找到 " + videoFiles.size() + " 个视频文件，开始上传到" + platformName);
        
        // 创建上传服务并上传
        MediaUploadService uploadService = createVideoUploadService();
        uploadService.uploadVideos(videoFiles, chatId, new RemoteUploadCallback() {
            @Override
            public void onProgress(String message) {
                AppLog.d(TAG, platformName + " 视频上传进度: " + message);
            }
            
            @Override
            public void onSuccess(String message) {
                AppLog.d(TAG, platformName + " 视频上传成功: " + message);
                
                // 传输临时文件到最终目录
                mediaFileFinder.transferToFinalDir(videoFiles);
                
                returnToBackgroundIfNeeded();
            }
            
            @Override
            public void onError(String error) {
                AppLog.e(TAG, platformName + " 视频上传失败: " + error);
                
                // 即使上传失败，也要传输文件到最终存储位置（保留视频）
                mediaFileFinder.transferToFinalDir(videoFiles);
                
                // 平台特定的错误处理（如文件大小限制提示）
                handleUploadError(chatId, error);
                
                returnToBackgroundIfNeeded();
            }
        });
    }
    
    /**
     * 上传拍摄的照片
     */
    private void uploadPhotos(ChatIdentifier chatId, String timestamp) {
        String platformName = getPlatformName();
        
        // 检查 API 客户端
        if (!isApiClientReady()) {
            AppLog.e(TAG, platformName + " API 客户端未初始化");
            returnToBackgroundIfNeeded();
            return;
        }
        
        // 查找照片文件
        List<File> photoFiles = mediaFileFinder.findPhotoFiles(timestamp);
        if (photoFiles.isEmpty()) {
            AppLog.e(TAG, "未找到拍摄的照片，时间戳: " + timestamp);
            sendError(chatId, "未找到拍摄的照片");
            returnToBackgroundIfNeeded();
            return;
        }
        
        AppLog.d(TAG, "找到 " + photoFiles.size() + " 张照片，开始上传到" + platformName);
        
        // 创建上传服务并上传
        MediaUploadService uploadService = createPhotoUploadService();
        uploadService.uploadPhotos(photoFiles, chatId, new RemoteUploadCallback() {
            @Override
            public void onProgress(String message) {
                AppLog.d(TAG, platformName + " 照片上传进度: " + message);
            }
            
            @Override
            public void onSuccess(String message) {
                AppLog.d(TAG, platformName + " 照片上传成功: " + message);
                returnToBackgroundIfNeeded();
            }
            
            @Override
            public void onError(String error) {
                AppLog.e(TAG, platformName + " 照片上传失败: " + error);
                returnToBackgroundIfNeeded();
            }
        });
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 生成时间戳
     */
    protected String generateTimestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
    }
    
    /**
     * 返回后台（如果是远程唤醒的）
     */
    protected void returnToBackgroundIfNeeded() {
        if (recordingStateListener != null) {
            recordingStateListener.returnToBackgroundIfRemoteWakeUp();
        }
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (autoStopHandler != null && autoStopRunnable != null) {
            autoStopHandler.removeCallbacks(autoStopRunnable);
        }
        isRemoteRecording = false;
        isPreparingRecording = false;
        currentContext = null;
    }
    
    // ==================== 抽象方法 - 平台特定实现 ====================
    
    /**
     * 获取平台名称
     */
    protected abstract String getPlatformName();
    
    /**
     * 获取平台类型
     */
    protected abstract RemotePlatform getPlatform();
    
    /**
     * 检查 API 客户端是否就绪
     */
    protected abstract boolean isApiClientReady();
    
    /**
     * 发送消息
     */
    public abstract void sendMessage(ChatIdentifier chatId, String message);
    
    /**
     * 发送错误消息
     */
    public abstract void sendError(ChatIdentifier chatId, String error);
    
    /**
     * 创建视频上传服务
     */
    protected abstract MediaUploadService createVideoUploadService();
    
    /**
     * 创建照片上传服务
     */
    protected abstract MediaUploadService createPhotoUploadService();
    
    /**
     * 处理上传错误（平台特定，如文件大小限制提示）
     * 子类可重写以添加平台特定的错误处理
     */
    protected void handleUploadError(ChatIdentifier chatId, String error) {
        // 默认不做额外处理，子类可重写
    }
}
