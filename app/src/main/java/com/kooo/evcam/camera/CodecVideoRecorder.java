package com.kooo.evcam.camera;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.SystemClock;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.RecordingStorageAccess;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用 MediaCodec + MediaMuxer 进行视频编码和录制
 * 用于 L6/L7 等不支持 MediaRecorder 直接录制的车机平台
 * 
 * 工作流程：
 * 1. 创建 MediaCodec 编码器，获取其输入 Surface
 * 2. 使用 EglSurfaceEncoder 将 Camera 的帧渲染到编码器输入 Surface
 * 3. 从 MediaCodec 获取编码后的数据
 * 4. 通过 MediaMuxer 写入 MP4 文件
 */
public class CodecVideoRecorder {
    private static final String TAG = "CodecVideoRecorder";

    // 编码参数（常量）
    private static final String MIME_TYPE_H264 = MediaFormat.MIMETYPE_VIDEO_AVC;      // H.264
    private static final String MIME_TYPE_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;     // H.265/HEVC
    private String mimeType = MIME_TYPE_H264;  // 默认使用 H.264，支持时自动切换到 HEVC
    
    private static final int I_FRAME_INTERVAL = 3;  // I帧间隔（秒）- 改为3秒减少CPU占用和文件大小
    
    // 编码参数（可配置）
    private int frameRate = 20;       // 默认 20fps - 降低帧率减少CPU占用，同时保持流畅
    private int bitRate = 0;          // 默认自动计算码率
    
    // 性能优化：码率上限（防止过高码率导致卡顿）
    private static final int MAX_BITRATE = 12000000;  // 最大码率 12Mbps
    private static final int MAX_HEVC_BITRATE = 8000000;  // HEVC 最大码率 8Mbps
    
    // 录制时补盲优化模式
    private boolean blindSpotOptimizeMode = false;  // 是否启用补盲优化模式（录制时降低负载）
    private static final int BLIND_SPOT_OPTIMIZED_FPS = 15;  // 补盲优化模式帧率

    // 编码器选择：是否强制使用 H.264（默认 false，优先使用 HEVC）
    private boolean forceH264 = false;
    private int keyFrameIntervalSeconds = I_FRAME_INTERVAL;
    public void setKeyFrameIntervalSeconds(int seconds) { keyFrameIntervalSeconds = Math.max(1, seconds); }
    private boolean hardwareOnly;
    private boolean e5Compatibility;
    public void setE5Compatibility(boolean enabled) { e5Compatibility = enabled; }
    public void setHardwareOnly(boolean required) { hardwareOnly = required; }
    
    // 画质等级：0=低, 1=中, 2=高, 3=最高
    private int qualityLevel = 2;
    
    private static final int DRAIN_BATCH_SIZE = 8;
    private static final long EOS_DRAIN_TIMEOUT_MS = 1500;
    private static final long DIRECT_PRE_START_DRAIN_INTERVAL_MS = 30L;
    private static final long DIRECT_NO_OUTPUT_STALL_MS = 5000L;
    private final MuxerTimestampClock muxerClock = new MuxerTimestampClock();
    private long inputFrameCallbacks = 0;

    // M2: 无水印无调色直连编码跳过 GL 路径状态
    private volatile boolean directInputEnabled = false;
    private volatile boolean forceGlPipeline = false;
    private volatile boolean runtimeGlFallback = false;
    private volatile boolean cameraSupportsDirectInput = true;
    private volatile int nativeSourceFrameRate = 25;
    private volatile DirectEncodingPolicy.PathMode activePathMode = DirectEncodingPolicy.PathMode.GL;
    private volatile DirectEncodingPolicy.FallbackReason pathFallbackReason = DirectEncodingPolicy.FallbackReason.SWITCH_DISABLED;
    private volatile boolean pendingGlConfigChange = false;
    private volatile boolean awaitingInitialKeyFrame = false;
    private long directSkippedPreKeyFrames = 0;

    private final String cameraId;
    private final int width;
    private final int height;
    private int sourceWidth;
    private int sourceHeight;

    // MediaCodec 相关
    private MediaCodec encoder;
    private Surface encoderInputSurface;
    private MediaCodec.BufferInfo bufferInfo;

    // MediaMuxer 相关
    private volatile AsyncMuxerWriter muxer;
    private int videoTrackIndex = -1;
    private volatile boolean muxerStarted = false;
    private MediaFormat outputFormat;
    private volatile boolean segmentRequested;
    private final Object codecLock = new Object();
    private HandlerThread drainThread;
    private Handler drainHandler;

    // EGL 渲染器
    private EglSurfaceEncoder eglEncoder;
    private SurfaceTexture inputSurfaceTexture;
    private int textureId;

    // 编码线程
    private HandlerThread encoderThread;
    private Handler encoderHandler;

    // 状态
    private final AtomicBoolean isRecording = new AtomicBoolean(false);  // 使用 AtomicBoolean 确保线程安全
    private final AtomicBoolean drainWakeupPending = new AtomicBoolean(false);
    private final Object prepareReleaseLock = new Object();
    private final AtomicBoolean releaseFinalized = new AtomicBoolean(false);
    private volatile boolean isReleased = false;
    private volatile boolean stopRequested;
    private volatile String lastPrepareErrorMessage;
    private int consecutiveEmptyDrains = 0;

    private static final class PrepareCancelledException extends RuntimeException {
    }

    public String getLastPrepareErrorMessage() {
        return lastPrepareErrorMessage;
    }

    static long computeNextDrainDelayMs(boolean gotOutput, int inFlightFrames, int emptyDrainCount) {
        if (gotOutput) {
            return 10L;
        }
        if (inFlightFrames > 0) {
            return emptyDrainCount <= 2 ? 15L : 30L;
        }
        return 120L;
    }

    static long computeNextDrainDelayMs(
            boolean gotOutput,
            int inFlightFrames,
            int emptyDrainCount,
            boolean directInput) {
        if (!directInput) {
            return computeNextDrainDelayMs(gotOutput, inFlightFrames, emptyDrainCount);
        }
        if (gotOutput) {
            return 10L;
        }
        if (emptyDrainCount <= 2) {
            return 20L;
        }
        if (emptyDrainCount <= 6) {
            return 40L;
        }
        return 80L;
    }

    private final Runnable preStartDrain = () -> {
        synchronized (codecLock) {
            if (isReleased || stopRequested || isRecording.get()
                    || !isDirectInputActive() || !this.encoderHealthy || encoder == null) {
                return;
            }
            drainPreStartEncoderLocked();
        }
        Handler handler = drainHandler;
        if (!isReleased && !stopRequested && !isRecording.get()
                && isDirectInputActive() && this.encoderHealthy && handler != null) {
            handler.postDelayed(this.preStartDrain, DIRECT_PRE_START_DRAIN_INTERVAL_MS);
        }
    };

    private void schedulePreStartDrain() {
        Handler handler = drainHandler;
        if (handler != null && isDirectInputActive() && !isReleased && !stopRequested && !isRecording.get()) {
            handler.removeCallbacks(preStartDrain);
            handler.post(preStartDrain);
        }
    }

    private final Runnable delayedDrain = () -> {
        drainWakeupPending.set(false);
        boolean gotOutput;
        synchronized (codecLock) {
            if (isReleased || stopRequested || !isRecording.get() || !this.encoderHealthy || encoder == null) return;
            gotOutput = drainEncoderWithResult(false);
        }
        if (gotOutput) {
            consecutiveEmptyDrains = 0;
        } else {
            consecutiveEmptyDrains++;
        }
        if (!isReleased && !stopRequested && isRecording.get() && drainHandler != null) {
            long nextDelay = computeNextDrainDelayMs(
                    gotOutput,
                    this.framesWithoutEncoderOutput,
                    this.consecutiveEmptyDrains,
                    isDirectInputActive());
            drainHandler.postDelayed(this.delayedDrain, nextDelay);
        }
    };

    private void scheduleDrain() {
        Handler handler = drainHandler;
        if (handler != null) {
            drainWakeupPending.set(true);
            consecutiveEmptyDrains = 0;
            handler.removeCallbacks(delayedDrain);
            handler.post(delayedDrain);
        }
    }

    private void notifyDrainFrameSubmitted() {
        Handler handler = drainHandler;
        if (handler != null && drainWakeupPending.compareAndSet(false, true)) {
            handler.removeCallbacks(delayedDrain);
            handler.post(delayedDrain);
        }
    }
    private volatile String currentFilePath;
    
    // 缓存的录制 Surface，避免重复创建导致内存泄漏
    private Surface cachedRecordSurface = null;
    
    // 编码器输出帧计数（仅用于日志和统计，不再用于 PTS 计算）
    private volatile long encodedOutputFrameCount = 0;
    private volatile long totalEncodedOutputFrameCount = 0;  // 跨分段累计编码输出帧数（供直连路径日志统计）

    // 分段录制相关
    private long segmentDurationMs = 60000;  // 分段时长，默认1分钟，可通过 setSegmentDuration 配置
    private static final long SEGMENT_DURATION_COMPENSATION_MS = 0;  // 分段时长补偿（H3修复后定时器更精确，不再需要补偿）
    private static final long MIN_VALID_FILE_SIZE = 1 * 1024;   // 最小有效文件大小 1KB（降低阈值，短录制也能保存）
    
    // 使用独立的后台线程处理分段和文件 I/O 操作，避免阻塞主线程导致 ANR
    private HandlerThread segmentThread;
    private Handler segmentHandler;
    
    private volatile Runnable segmentRunnable;
    private int segmentIndex = 0;
    private String saveDirectory;
    private String cameraPosition;
    private VideoRecorder.SegmentTimestampProvider timestampProvider;  // 分段时间戳提供者（用于多路同步）
    private long lastFileSize = 0;
    private static final long FILE_SIZE_CHECK_INTERVAL_MS = 5000;
    private static final long FIRST_CHECK_DELAY_MS = 500;  // 首次检查延迟（更快检测首次写入）
    private volatile Runnable fileSizeCheckRunnable;
    private long recordedFrameCount = 0;
    private List<String> recordedFilePaths = new java.util.concurrent.CopyOnWriteArrayList<>();  // 本次录制的所有文件路径
    
    // 首次写入检测（与 VideoRecorder 保持一致）
    private static final long FIRST_WRITE_TIMEOUT_MS = 10000;  // 首次写入超时（10秒）
    private volatile boolean hasFirstWrite = false;  // 是否已有首次写入
    private volatile Runnable firstWriteTimeoutRunnable;  // 首次写入超时检查任务
    
    // 快速恢复机制
    private static final long RECOVERY_RETRY_INTERVAL_MS = 5000;  // 恢复重试间隔：5秒
    private static final int MAX_RECOVERY_ATTEMPTS = 60;  // 最大重试次数（5秒 × 60 = 5分钟内重试）
    private int recoveryAttempts = 0;  // 当前重试次数
    private volatile Runnable recoveryRunnable;  // 恢复重试任务

    // 编码器健康检查
    private static final long ENCODER_HEALTH_CHECK_INTERVAL_MS = 3000;  // 健康检查间隔：3秒
    private static final int MAX_FRAMES_WITHOUT_OUTPUT = 30;  // 无输出的最大帧数阈值
    private volatile long lastEncoderOutputTime = 0;  // 最后一次编码器输出时间
    private volatile int framesWithoutEncoderOutput = 0;  // 无编码器输出的连续帧数
    private volatile boolean encoderHealthy = true;  // 编码器是否健康
    private volatile boolean fatalWriteError = false;  // 首次写入前的致命存储/权限错误（不可通过重建编码器恢复）
    private volatile String fatalWriteErrorMessage = null;
    private long maxGlUs;
    private long totalGlUs;
    private long codecPolls, totalCodecPollUs, maxCodecPollUs;
    private volatile Runnable healthCheckRunnable;  // 健康检查任务

    // 回调
    private RecordCallback callback;

    // 时间水印设置
    private boolean watermarkEnabled = false;
    private volatile boolean vehicleStatusWatermarkEnabled;
    private volatile ImageAdjustmentConfig imageAdjustmentConfig = ImageAdjustmentConfig.disabled();
    private volatile VehicleStatusWatermark.WatermarkOptions watermarkOptions;

    /** 设置源 Camera SurfaceTexture 缓冲尺寸（确保画质缩放时 Camera2 仍使用硬件原生预览尺寸）。 */
    public void setSourceSize(int srcWidth, int srcHeight) {
        if (srcWidth > 0 && srcHeight > 0) {
            this.sourceWidth = srcWidth;
            this.sourceHeight = srcHeight;
        }
    }

    public void setDirectInputEnabled(boolean enabled) {
        this.directInputEnabled = enabled;
    }

    public boolean isDirectInputEnabled() {
        return directInputEnabled;
    }

    public void setForceGlPipeline(boolean force) {
        this.forceGlPipeline = force;
    }

    public void setRuntimeGlFallback(boolean fallback) {
        this.runtimeGlFallback = fallback;
    }

    public void setCameraSupportsDirectInput(boolean supported) {
        this.cameraSupportsDirectInput = supported;
    }

    public void setNativeSourceFrameRate(int fps) {
        this.nativeSourceFrameRate = Math.max(0, fps);
    }

    public boolean isDirectInputActive() {
        return activePathMode == DirectEncodingPolicy.PathMode.DIRECT;
    }

    public DirectEncodingPolicy.PathMode getActivePathMode() {
        return activePathMode;
    }

    public DirectEncodingPolicy.FallbackReason getPathFallbackReason() {
        return pathFallbackReason;
    }

    public boolean isPendingGlConfigChange() {
        return pendingGlConfigChange;
    }

    DirectEncodingPolicy.Decision evaluateDirectEncodingDecision(boolean allowDirect) {
        int effectiveTargetFps = blindSpotOptimizeMode ? BLIND_SPOT_OPTIMIZED_FPS : frameRate;
        return DirectEncodingPolicy.evaluate(
                allowDirect && directInputEnabled,
                forceGlPipeline,
                runtimeGlFallback,
                watermarkEnabled,
                vehicleStatusWatermarkEnabled,
                watermarkOptions,
                imageAdjustmentConfig,
                sourceWidth,
                sourceHeight,
                width,
                height,
                cameraSupportsDirectInput,
                blindSpotOptimizeMode,
                effectiveTargetFps,
                nativeSourceFrameRate);
    }

    private void evaluateDynamicConfigChangeIfDirect(String source) {
        if (activePathMode != DirectEncodingPolicy.PathMode.DIRECT) {
            pendingGlConfigChange = false;
            return;
        }
        DirectEncodingPolicy.Decision next = evaluateDirectEncodingDecision(true);
        boolean needsGl = !next.isDirect();
        pendingGlConfigChange = needsGl;
        if (needsGl && isRecording.get() && !isReleased && !stopRequested) {
            AppLog.i(TAG, "Camera " + cameraId + " dynamic " + source
                    + " change requires GL (" + next.reason.code
                    + "); keeping active direct CaptureSession intact, will apply on next recording");
        }
    }

    public void setImageAdjustmentConfig(ImageAdjustmentConfig config) {
        this.imageAdjustmentConfig = config != null ? config : ImageAdjustmentConfig.disabled();
        Handler handler = encoderHandler;
        if (handler != null) {
            final ImageAdjustmentConfig snapshot = this.imageAdjustmentConfig;
            handler.post(() -> {
                if (eglEncoder != null) {
                    eglEncoder.setImageAdjustmentConfig(snapshot);
                }
            });
        }
        evaluateDynamicConfigChangeIfDirect("image_adjustment");
    }

    public void setWatermarkOptions(VehicleStatusWatermark.WatermarkOptions options) {
        this.watermarkOptions = options;
        this.watermarkEnabled = options != null && options.showDateTime;
        this.vehicleStatusWatermarkEnabled = options != null && options.needsVehicleSource();
        updateWatermarkOnEncoder();
        evaluateDynamicConfigChangeIfDirect("watermark_options");
    }

    public void setVehicleStatusWatermarkEnabled(boolean enabled) {
        vehicleStatusWatermarkEnabled = enabled;
        VehicleStatusWatermark.WatermarkOptions current = watermarkOptions;
        if (current != null) {
            watermarkOptions = new VehicleStatusWatermark.WatermarkOptions(
                    current.showDateTime,
                    current.showCustomText,
                    current.customText,
                    enabled,
                    enabled,
                    enabled,
                    current.position,
                    current.style);
        }
        updateWatermarkOnEncoder();
        evaluateDynamicConfigChangeIfDirect("vehicle_status_watermark");
    }

    private void updateVehicleStatusWatermark() {
        updateWatermarkOnEncoder();
    }

    private void updateWatermarkOnEncoder() {
        Handler handler = encoderHandler;
        if (handler != null) {
            handler.post(() -> {
                if (eglEncoder == null) return;
                boolean active = isRecording.get() && !isReleased && !stopRequested;
                VehicleStatusWatermark.WatermarkOptions opts = watermarkOptions;
                if (opts != null) {
                    eglEncoder.setWatermarkOptions(active ? opts : VehicleStatusWatermark.WatermarkOptions.disabled());
                } else {
                    eglEncoder.setVehicleStatusWatermarkEnabled(vehicleStatusWatermarkEnabled && active);
                }
            });
        }
    }

    // 注意：帧同步变量已移除，帧处理现在直接在 onFrameAvailable 回调中完成

    public CodecVideoRecorder(String cameraId, int width, int height) {
        this.cameraId = cameraId;
        this.width = width;
        this.height = height;
        this.sourceWidth = width;
        this.sourceHeight = height;
        // 创建独立的后台线程用于分段处理和文件 I/O 操作
        segmentThread = new HandlerThread("CodecRecorder-Segment-" + cameraId) {
            @Override
            protected void onLooperPrepared() {
                // 降低分段线程优先级，减少对主线程的影响
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            }
        };
        segmentThread.start();
        this.segmentHandler = new Handler(segmentThread.getLooper());
    }

    /**
     * 设置是否启用时间水印
     * @param enabled true 表示启用水印
     */
    public void setWatermarkEnabled(boolean enabled) {
        this.watermarkEnabled = enabled;
        // 如果 EGL 编码器已初始化，同步设置
        if (eglEncoder != null) {
            eglEncoder.setWatermarkEnabled(enabled);
        }
        evaluateDynamicConfigChangeIfDirect("timestamp_watermark");
        AppLog.d(TAG, "Camera " + cameraId + " Watermark " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * 检查是否启用了时间水印
     */
    public boolean isWatermarkEnabled() {
        return watermarkEnabled;
    }

    public void setCallback(RecordCallback callback) {
        this.callback = callback;
    }

    /**
     * 设置分段时间戳提供者
     * 用于多路摄像头分段切换时使用统一的时间戳，避免时间戳差1秒导致分组错误
     * @param provider 时间戳提供者
     */
    public void setTimestampProvider(VideoRecorder.SegmentTimestampProvider provider) {
        this.timestampProvider = provider;
    }

    /**
     * 设置分段时长
     * @param durationMs 分段时长（毫秒）
     */
    public void setSegmentDuration(long durationMs) {
        this.segmentDurationMs = durationMs;
        AppLog.d(TAG, "Camera " + cameraId + " segment duration set to " + (durationMs / 1000) + " seconds");
    }

    /**
     * 获取分段时长（毫秒）
     */
    public long getSegmentDuration() {
        return segmentDurationMs;
    }

    /**
     * 设置录制码率
     * @param bitrate 码率（bps）
     */
    public void setBitRate(int bitrate) {
        this.bitRate = bitrate;
        AppLog.d(TAG, "Camera " + cameraId + " bitrate set to " + (bitrate / 1000) + " Kbps");
    }

    /**
     * 设置录制帧率
     * @param fps 帧率（fps）
     */
    public void setFrameRate(int fps) {
        this.frameRate = fps;
        AppLog.d(TAG, "Camera " + cameraId + " frame rate set to " + fps + " fps");
    }

    /**
     * 设置画质等级
     * @param level 画质等级：0=低, 1=中, 2=高, 3=最高
     */
    public void setQualityLevel(int level) {
        this.qualityLevel = Math.max(0, Math.min(3, level));
        AppLog.d(TAG, "Camera " + cameraId + " quality level set to " + this.qualityLevel);
    }

    /**
     * 设置是否强制使用 H.264 编码器
     * @param force true 表示强制 H.264（兼容性优先），false 表示优先使用 HEVC
     */
    public void setForceH264(boolean force) {
        this.forceH264 = force;
        AppLog.d(TAG, "Camera " + cameraId + " forceH264 = " + force);
    }

    /**
     * 获取当前配置的码率
     */
    public int getBitRate() {
        return bitRate;
    }

    /**
     * 获取当前配置的帧率
     */
    public int getFrameRate() {
        return frameRate;
    }
    
    /**
     * 设置补盲优化模式
     * 启用后降低帧率以减少CPU/GPU负载，改善补盲画面延迟
     * @param enabled true 表示启用补盲优化模式
     */
    public void setBlindSpotOptimizeMode(boolean enabled) {
        this.blindSpotOptimizeMode = enabled;
        if (enabled) {
            AppLog.i(TAG, "Camera " + cameraId + " 启用补盲优化模式，帧率降至 " + BLIND_SPOT_OPTIMIZED_FPS + "fps");
        } else {
            AppLog.i(TAG, "Camera " + cameraId + " 关闭补盲优化模式，恢复 " + frameRate + "fps");
        }
    }
    
    /**
     * 获取补盲优化模式状态
     */
    public boolean isBlindSpotOptimizeMode() {
        return blindSpotOptimizeMode;
    }

    /**
     * 准备录制（兼容旧接口，固定走 GL 路径并返回 SurfaceTexture）
     */
    public SurfaceTexture prepareRecording(String filePath) {
        // 检查是否在主线程调用（可能导致 ANR）
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppLog.w(TAG, "Camera " + cameraId + " WARNING: prepareRecording() called on MAIN THREAD! " +
                    "This may cause ANR due to blocking operations. Consider using prepareRecordingAsync().");
        }
        Surface prepared = prepareRecordingSurfaceInternal(filePath, true, false);
        return prepared != null ? inputSurfaceTexture : null;
    }

    /**
     * 准备录制输出 Surface（M2：满足条件时返回直连 MediaCodec 输入 Surface 跳过 GL；否则回退 GL SurfaceTexture）
     */
    public Surface prepareRecordingSurface(String filePath) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppLog.w(TAG, "Camera " + cameraId + " WARNING: prepareRecordingSurface() called on MAIN THREAD! " +
                    "This may cause ANR due to blocking operations. Consider using prepareRecordingSurfaceAsync().");
        }
        return prepareRecordingSurfaceInternal(filePath, true, true);
    }

    private Surface prepareRecordingSurfaceInternal(
            String filePath,
            boolean notifyCallbackOnError,
            boolean allowDirect) {
        synchronized (prepareReleaseLock) {
            if (stopRequested || isReleased) {
                lastPrepareErrorMessage = "Cancelled";
                return null;
            }
            if (isRecording.get()) {
                AppLog.w(TAG, "Camera " + cameraId + " is already recording");
                return getRecordSurface();
            }
        }

        final DirectEncodingPolicy.Decision decision = evaluateDirectEncodingDecision(allowDirect);
        this.activePathMode = decision.mode;
        this.pathFallbackReason = decision.reason;
        this.pendingGlConfigChange = false;
        this.awaitingInitialKeyFrame = false;
        this.directSkippedPreKeyFrames = 0;

        AppLog.i(TAG, "Camera " + cameraId + " Preparing codec recording: " + width + "x" + height
                + ", src=" + sourceWidth + "x" + sourceHeight
                + ", path=" + decision.mode.logTag
                + ", reason=" + decision.reason.code);

        // 保存录制参数（切勿无条件清除已置位的 stopRequested，避免与中途取消竞态）
        this.currentFilePath = filePath;
        this.lastPrepareErrorMessage = null;
        this.segmentIndex = 0;
        this.recordedFrameCount = 0;
        this.inputFrameCallbacks = 0;
        this.maxGlUs = this.totalGlUs = 0;
        this.codecPolls = this.totalCodecPollUs = this.maxCodecPollUs = 0;
        this.segmentRequested = false;
        this.encodedOutputFrameCount = 0;  // 重置编码输出帧计数
        this.totalEncodedOutputFrameCount = 0;

        // 重置健康检查状态
        this.encoderHealthy = true;
        this.fatalWriteError = false;
        this.fatalWriteErrorMessage = null;
        this.framesWithoutEncoderOutput = 0;
        this.lastEncoderOutputTime = System.currentTimeMillis();

        // 清空并初始化本次录制的文件列表
        recordedFilePaths.clear();
        recordedFilePaths.add(filePath);

        // 从文件路径中提取保存目录和摄像头位置
        File file = new File(filePath);
        this.saveDirectory = file.getParent();
        String fileName = file.getName();
        int lastUnderscoreIndex = fileName.lastIndexOf('_');
        if (lastUnderscoreIndex > 0 && fileName.endsWith(".mp4")) {
            this.cameraPosition = fileName.substring(lastUnderscoreIndex + 1, fileName.length() - 4);
        } else {
            this.cameraPosition = "unknown";
        }

        HandlerThread localEncoderThread = null;
        Handler localEncoderHandler = null;
        HandlerThread localDrainThread = null;
        Handler localDrainHandler = null;
        try {
            if (stopRequested || isReleased) {
                throw new PrepareCancelledException();
            }

            // 创建 MediaCodec 编码器
            createEncoder();
            if (stopRequested || isReleased) {
                throw new PrepareCancelledException();
            }

            // 创建 MediaMuxer
            createMuxer(filePath);
            if (stopRequested || isReleased) {
                throw new PrepareCancelledException();
            }

            final Surface encInputSurface = encoderInputSurface;
            if (encInputSurface == null || !encInputSurface.isValid()) {
                throw new IllegalStateException("Encoder input surface is invalid");
            }

            localDrainThread = new HandlerThread("CodecDrain-" + cameraId);
            localDrainThread.start();
            localDrainHandler = new Handler(localDrainThread.getLooper());

            if (decision.isDirect()) {
                boolean cancelled;
                synchronized (prepareReleaseLock) {
                    cancelled = stopRequested || isReleased;
                    if (!cancelled) {
                        encoderThread = null;
                        encoderHandler = null;
                        drainThread = localDrainThread;
                        drainHandler = localDrainHandler;
                        eglEncoder = null;
                        inputSurfaceTexture = null;
                        textureId = 0;
                    }
                }
                if (cancelled) {
                    releaseLocalGlAndThreads(null, null, localDrainThread, null, null);
                    throw new PrepareCancelledException();
                }
                schedulePreStartDrain();
                AppLog.i(TAG, "Camera " + cameraId + " Direct codec recording prepared (GL skipped), surface=" + encInputSurface);
                return encInputSurface;
            }

            // 创建编码线程（先以局部引用构建，完成后在锁内原子发布，防与并发 release() 竞争）
            localEncoderThread = new HandlerThread("Encoder-" + cameraId) {
                @Override
                protected void onLooperPrepared() {
                    // Encoding is time-sensitive; do not throttle it as background file I/O.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    AppLog.d(TAG, "Camera " + cameraId + " Encoding thread uses normal priority");
                }
            };
            localEncoderThread.start();
            localEncoderHandler = new Handler(localEncoderThread.getLooper());

            // 在编码线程上初始化 EGL 和 SurfaceTexture（重要：必须在同一线程上）
            // 使用 CountDownLatch 等待初始化完成
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final int[] resultTextureId = {0};
            final Exception[] initException = {null};
            final EglSurfaceEncoder[] holderEgl = {null};
            final SurfaceTexture[] holderSt = {null};
            final Handler targetEncoderHandler = localEncoderHandler;

            localEncoderHandler.post(() -> {
                try {
                    if (stopRequested || isReleased) {
                        throw new PrepareCancelledException();
                    }
                    // 创建 EGL 渲染器（在编码线程上）
                    EglSurfaceEncoder localEgl = new EglSurfaceEncoder(cameraId, width, height);
                    localEgl.setTargetFrameRate(blindSpotOptimizeMode ? BLIND_SPOT_OPTIMIZED_FPS : frameRate);
                    int texId = localEgl.initialize(encInputSurface);
                    resultTextureId[0] = texId;

                    // 创建 SurfaceTexture 供 Camera 输出（在编码线程上，绑定到 EGL context）
                    SurfaceTexture localSt = new SurfaceTexture(texId);
                    localSt.setDefaultBufferSize(sourceWidth, sourceHeight);

                    // 设置帧可用回调（在编码线程上）
                    // 直接在回调中处理帧，避免 Handler 死锁
                    localSt.setOnFrameAvailableListener(surfaceTexture -> {
                        if (isReleased) return;
                        if (stopRequested || !isRecording.get() || !encoderHealthy) {
                            if (eglEncoder != null) eglEncoder.consumeFrame();
                            return;
                        }
                        inputFrameCallbacks++;
                        try {
                            long frameStart = System.nanoTime();
                            if (eglEncoder != null && eglEncoder.drawFrame()) {
                                recordedFrameCount++;
                                framesWithoutEncoderOutput++;
                                long glUs = (System.nanoTime() - frameStart) / 1000;
                                totalGlUs += glUs;
                                maxGlUs = Math.max(maxGlUs, glUs);
                                notifyDrainFrameSubmitted();
                            }
                        } catch (Exception e) {
                            AppLog.e(TAG, "Camera " + cameraId + " Error processing frame", e);
                            encoderHealthy = false;
                        }
                    }, targetEncoderHandler);

                    // 设置 EGL 渲染器的输入
                    localEgl.setInputSurfaceTexture(localSt);
                    localEgl.setImageAdjustmentConfig(imageAdjustmentConfig);

                    // 设置水印（优先使用统一四模块水印配置，兼容旧接口）
                    VehicleStatusWatermark.WatermarkOptions opts = watermarkOptions;
                    if (opts != null) {
                        if (opts.hasAnyEnabled() && isRecording.get()) {
                            localEgl.setWatermarkOptions(opts);
                        }
                    } else if (watermarkEnabled) {
                        localEgl.setWatermarkEnabled(true);
                    }

                    holderEgl[0] = localEgl;
                    holderSt[0] = localSt;
                    AppLog.d(TAG, "Camera " + cameraId + " EGL/SurfaceTexture initialized on encoder thread, textureId=" + texId + ", watermark=" + watermarkEnabled);

                } catch (Exception e) {
                    if (!(e instanceof PrepareCancelledException)) {
                        AppLog.e(TAG, "Camera " + cameraId + " Failed to initialize EGL on encoder thread", e);
                    }
                    initException[0] = e;
                } finally {
                    latch.countDown();
                }
            });

            // 等待初始化完成（最多 5 秒）
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout waiting for EGL initialization");
            }

            // 检查是否有初始化错误
            if (initException[0] != null) {
                throw initException[0];
            }

            boolean cancelled;
            synchronized (prepareReleaseLock) {
                cancelled = stopRequested || isReleased;
                if (!cancelled) {
                    encoderThread = localEncoderThread;
                    encoderHandler = localEncoderHandler;
                    drainThread = localDrainThread;
                    drainHandler = localDrainHandler;
                    eglEncoder = holderEgl[0];
                    inputSurfaceTexture = holderSt[0];
                    textureId = resultTextureId[0];
                }
            }
            if (cancelled) {
                releaseLocalGlAndThreads(localEncoderHandler, localEncoderThread, localDrainThread, holderEgl[0], holderSt[0]);
                throw new PrepareCancelledException();
            }

            AppLog.d(TAG, "Camera " + cameraId + " Codec recording prepared, textureId=" + textureId);
            return getRecordSurface();

        } catch (PrepareCancelledException ignored) {
            releaseLocalGlAndThreads(localEncoderHandler, localEncoderThread, localDrainThread, null, null);
            lastPrepareErrorMessage = "Cancelled";
            isReleased = true;
            stopRequested = true;
            cleanupAllocatedResources();
            return null;
        } catch (Exception e) {
            releaseLocalGlAndThreads(localEncoderHandler, localEncoderThread, localDrainThread, null, null);
            String detail = RecordingStorageAccess.describeThrowable(e);
            if (detail == null || detail.isEmpty()) {
                detail = e.getMessage();
            }
            if (detail == null || detail.isEmpty()) {
                detail = e.getClass().getSimpleName();
            }
            lastPrepareErrorMessage = detail;
            AppLog.e(TAG, "Camera " + cameraId + " Failed to prepare codec recording", e);
            isReleased = true;
            stopRequested = true;
            cleanupAllocatedResources();
            if (notifyCallbackOnError && callback != null) {
                callback.onRecordError(cameraId, detail);
            }
            return null;
        }
    }

    private void releaseLocalGlAndThreads(
            Handler localEncoderHandler,
            HandlerThread localEncoderThread,
            HandlerThread localDrainThread,
            EglSurfaceEncoder localEgl,
            SurfaceTexture localSt) {
        if (localEgl != null && localEncoderHandler != null && localEncoderHandler != encoderHandler) {
            final java.util.concurrent.CountDownLatch glLatch = new java.util.concurrent.CountDownLatch(1);
            Runnable task = () -> {
                try {
                    localEgl.release();
                } finally {
                    glLatch.countDown();
                }
            };
            if (Looper.myLooper() == localEncoderHandler.getLooper()) {
                task.run();
            } else if (localEncoderHandler.post(task)) {
                try {
                    glLatch.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (localSt != null && localSt != inputSurfaceTexture) {
            localSt.release();
        }
        if (localDrainThread != null && localDrainThread != drainThread) {
            localDrainThread.quitSafely();
            try {
                localDrainThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (localEncoderThread != null && localEncoderThread != encoderThread) {
            localEncoderThread.quitSafely();
            try {
                localEncoderThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 准备录制回调接口
     */
    public interface PrepareCallback {
        /**
         * 准备完成回调
         * @param success 是否成功
         * @param surfaceTexture 成功时返回的 SurfaceTexture，失败时为 null
         * @param errorMessage 失败时的错误信息，成功时为 null
         */
        void onPrepareComplete(boolean success, SurfaceTexture surfaceTexture, String errorMessage);
    }

    /**
     * 准备录制输出 Surface 回调接口（兼容 GL 与直连输入双路径）
     */
    public interface PrepareSurfaceCallback {
        void onPrepareComplete(boolean success, Surface recordSurface, boolean directPath, String errorMessage);
    }

    /**
     * 异步准备录制（兼容旧接口，固定走 GL 路径）
     */
    public void prepareRecordingAsync(String filePath, PrepareCallback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        prepareRecordingAsync(filePath, main::post, callback);
    }

    void prepareRecordingAsync(
            String filePath,
            java.util.concurrent.Executor callbackExecutor,
            PrepareCallback callback) {
        final java.util.concurrent.Executor executor = callbackExecutor != null
                ? callbackExecutor
                : new Handler(Looper.getMainLooper())::post;
        new Thread(() -> {
            SurfaceTexture result = null;
            String error = null;
            boolean success = false;
            try {
                Surface prepared = prepareRecordingSurfaceInternal(filePath, false, false);
                if (stopRequested || isReleased) {
                    if (prepared != null) {
                        release();
                    }
                    result = null;
                    error = "Cancelled";
                } else if (prepared != null && inputSurfaceTexture != null) {
                    result = inputSurfaceTexture;
                    success = true;
                } else {
                    error = (lastPrepareErrorMessage != null && !lastPrepareErrorMessage.isEmpty())
                            ? lastPrepareErrorMessage
                            : "Preparation failed";
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " prepareRecordingAsync failed", e);
                String detail = RecordingStorageAccess.describeThrowable(e);
                error = (detail != null && !detail.isEmpty()) ? detail : e.getMessage();
            }
            if (callback != null) {
                final boolean finalSuccess = success;
                final SurfaceTexture finalResult = result;
                final String finalError = error;
                executor.execute(() -> callback.onPrepareComplete(finalSuccess, finalResult, finalError));
            }
        }, "CodecRecorderPrepare-" + cameraId).start();
    }

    /**
     * 异步准备录制输出 Surface（支持无水印无调色直连编码跳过 GL）
     */
    public void prepareRecordingSurfaceAsync(String filePath, PrepareSurfaceCallback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        prepareRecordingSurfaceAsync(filePath, main::post, callback);
    }

    void prepareRecordingSurfaceAsync(
            String filePath,
            java.util.concurrent.Executor callbackExecutor,
            PrepareSurfaceCallback callback) {
        final java.util.concurrent.Executor executor = callbackExecutor != null
                ? callbackExecutor
                : new Handler(Looper.getMainLooper())::post;
        new Thread(() -> {
            Surface result = null;
            String error = null;
            boolean success = false;
            boolean directPath = false;
            try {
                result = prepareRecordingSurfaceInternal(filePath, false, true);
                if (stopRequested || isReleased) {
                    if (result != null) {
                        release();
                        result = null;
                    }
                    error = "Cancelled";
                } else if (result != null && result.isValid()) {
                    success = true;
                    directPath = isDirectInputActive();
                } else {
                    error = (lastPrepareErrorMessage != null && !lastPrepareErrorMessage.isEmpty())
                            ? lastPrepareErrorMessage
                            : "Preparation failed";
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " prepareRecordingSurfaceAsync failed", e);
                String detail = RecordingStorageAccess.describeThrowable(e);
                error = (detail != null && !detail.isEmpty()) ? detail : e.getMessage();
            }
            if (callback != null) {
                final boolean finalSuccess = success;
                final Surface finalResult = result;
                final boolean finalDirect = directPath;
                final String finalError = error;
                executor.execute(() -> callback.onPrepareComplete(finalSuccess, finalResult, finalDirect, finalError));
            }
        }, "CodecRecorderPrepare-" + cameraId).start();
    }

    /**
     * 开始录制
     */
    public boolean startRecording() {
        if (stopRequested || isReleased) return false;
        final boolean isDirect = isDirectInputActive();
        if (encoder == null || (!isDirect && eglEncoder == null) || (isDirect && encoderInputSurface == null)) {
            AppLog.e(TAG, "Camera " + cameraId + " Encoder not prepared (direct=" + isDirect + ")");
            return false;
        }

        if (isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " Already recording");
            return false;
        }

        AppLog.i(TAG, "Camera " + cameraId + " Starting codec recording: path=" + activePathMode.logTag
                + ", reason=" + pathFallbackReason.code);

        // 记录分段开始时间（用于 PTS 计算）
        encodedOutputFrameCount = 0;

        // 重置首次写入状态
        hasFirstWrite = false;
        lastFileSize = 0;
        directSkippedPreKeyFrames = 0;
        lastEncoderOutputTime = System.currentTimeMillis();

        if (isDirect) {
            Handler dHandler = drainHandler;
            if (dHandler != null) {
                dHandler.removeCallbacks(preStartDrain);
            }
            synchronized (codecLock) {
                awaitingInitialKeyFrame = true;
                drainPreStartEncoderLocked();
                requestSyncFrameLocked();
            }
        } else {
            awaitingInitialKeyFrame = false;
        }

        isRecording.set(true);
        updateVehicleStatusWatermark();
        scheduleDrain();

        // 【重要】分段定时器延迟到首次写入后启动
        scheduleFirstWriteTimeout();
        scheduleFileSizeCheck();
        scheduleEncoderHealthCheck();

        if (callback != null && segmentIndex == 0) {
            callback.onRecordStart(cameraId);
        }

        AppLog.d(TAG, "Camera " + cameraId + " Codec recording started");
        return true;
    }

    /**
     * 停止录制
     */
    public void stopRecording() {
        stopRequested = true;
        cancelMonitoringTimers();
        if (!isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " Not recording");
            return;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Stopping codec recording (path=" + activePathMode.logTag + ")");

        // 立即标记停止状态，防止新帧处理
        isRecording.set(false);
        segmentRequested = false;
        drainWakeupPending.set(false);
        if (drainHandler != null) {
            drainHandler.removeCallbacks(delayedDrain);
            drainHandler.removeCallbacks(preStartDrain);
        }
        recoveryAttempts = 0;

        // 在编码线程（直连路径为 drain 线程）上执行停止操作
        Handler stopExecutorHandler = (encoderHandler != null) ? encoderHandler : drainHandler;
        Runnable stopTaskBase = () -> {
            if (eglEncoder != null) eglEncoder.setVehicleStatusWatermarkEnabled(false);
            // 发送结束信号给编码器
            if (encoder != null) {
                try {
                    encoder.signalEndOfInputStream();
                    // 排空编码器
                    drainEncoder(true);
                } catch (Exception e) {
                    AppLog.e(TAG, "Camera " + cameraId + " Error signaling end of stream", e);
                }
            }

            // 停止 muxer
            if (muxerStarted && muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception e) {
                    AppLog.e(TAG, "Camera " + cameraId + " Error stopping muxer", e);
                }
                muxerStarted = false;
            }
            logRecordingStats();

            AppLog.d(TAG, "Camera " + cameraId + " Codec recording stopped on worker thread, frames recorded: "
                    + getDisplayRecordedFrameCount());
        };

        if (stopExecutorHandler != null) {
            final java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);
            Runnable stopTask = () -> {
                try {
                    stopTaskBase.run();
                } catch (Exception e) {
                    AppLog.e(TAG, "Camera " + cameraId + " Error in stopRecording on worker thread", e);
                } finally {
                    stopped.countDown();
                }
            };
            if (Looper.myLooper() == stopExecutorHandler.getLooper()) {
                stopTask.run();
            } else {
                if (!stopExecutorHandler.post(stopTask)) stopTask.run();
                try {
                    if (!stopped.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        AppLog.w(TAG, "Camera " + cameraId + " Timed out awaiting stopRecording task");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } else {
            stopTaskBase.run();
        }

        // 等待已排队的异步分段收尾落盘完成，再统一验证并清理文件
        awaitPendingSegmentFinalizations(3000L);

        // 验证并清理所有录制的文件
        List<String> deletedFiles = validateAndCleanupAllFiles();

        AppLog.d(TAG, "Camera " + cameraId + " Codec recording stopped, frames recorded: "
                + getDisplayRecordedFrameCount());

        if (callback != null) {
            callback.onRecordStop(cameraId);
            // 通知损坏文件被删除
            if (!deletedFiles.isEmpty()) {
                callback.onCorruptedFilesDeleted(cameraId, deletedFiles);
            }
        }
        
        recordedFilePaths.clear();
    }

    /** Pause every camera's submissions before waiting for any one camera's disk finalization. */
    public void requestStop() { stopRequested = true; }

    /**
     * 释放资源（幂等，且可安全收敛与并发 prepareRecordingAsync 的交错资源）
     */
    public void release() {
        boolean firstRelease = releaseFinalized.compareAndSet(false, true);
        isReleased = true;
        stopRequested = true;

        if (firstRelease) {
            AppLog.d(TAG, "Camera " + cameraId + " Releasing CodecVideoRecorder");
            if (isRecording.get()) {
                stopRecording();
            }
        }

        cleanupAllocatedResources();

        if (firstRelease) {
            AppLog.d(TAG, "Camera " + cameraId + " CodecVideoRecorder released");
        }
    }

    private void cleanupAllocatedResources() {
        cancelMonitoringTimers();

        EglSurfaceEncoder eglToRelease;
        Handler encoderHandlerForGl;
        Surface cachedSurfToRelease;
        SurfaceTexture inputStToRelease;
        MediaCodec encoderToRelease;
        Surface encoderInputToRelease;
        AsyncMuxerWriter muxerToRelease;
        boolean wasMuxerStarted;
        Handler drainHandlerToClear;
        HandlerThread drainThreadToQuit;
        Handler encoderHandlerToClear;
        HandlerThread encoderThreadToQuit;
        HandlerThread segmentThreadToQuit;

        synchronized (prepareReleaseLock) {
            eglToRelease = eglEncoder;
            eglEncoder = null;
            encoderHandlerForGl = encoderHandler;

            cachedSurfToRelease = cachedRecordSurface;
            cachedRecordSurface = null;

            inputStToRelease = inputSurfaceTexture;
            inputSurfaceTexture = null;

            encoderToRelease = encoder;
            encoder = null;

            encoderInputToRelease = encoderInputSurface;
            encoderInputSurface = null;

            muxerToRelease = muxer;
            muxer = null;
            wasMuxerStarted = muxerStarted;
            muxerStarted = false;

            drainHandlerToClear = drainHandler;
            drainHandler = null;
            drainThreadToQuit = drainThread;
            drainThread = null;

            encoderHandlerToClear = encoderHandler;
            encoderHandler = null;
            encoderThreadToQuit = encoderThread;
            encoderThread = null;

            segmentThreadToQuit = segmentThread;
            segmentThread = null;
            segmentHandler = null;
        }

        // GL objects must be destroyed on their owning thread, after its last frame callback.
        if (eglToRelease != null && encoderHandlerForGl != null) {
            final java.util.concurrent.CountDownLatch glReleased = new java.util.concurrent.CountDownLatch(1);
            final EglSurfaceEncoder targetEgl = eglToRelease;
            Runnable task = () -> {
                try {
                    targetEgl.release();
                } finally {
                    glReleased.countDown();
                }
            };
            if (Looper.myLooper() == encoderHandlerForGl.getLooper()) {
                task.run();
            } else if (encoderHandlerForGl.post(task)) {
                try {
                    glReleased.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // 释放缓存的录制 Surface（必须在 SurfaceTexture 之前释放）
        if (cachedSurfToRelease != null) {
            cachedSurfToRelease.release();
        }

        // 释放 SurfaceTexture
        if (inputStToRelease != null) {
            inputStToRelease.release();
        }

        // 释放编码器
        if (encoderToRelease != null) {
            try {
                encoderToRelease.stop();
            } catch (Exception ignored) {
            }
            encoderToRelease.release();
        }

        // 释放编码器输入 Surface
        if (encoderInputToRelease != null) {
            encoderInputToRelease.release();
        }

        // 释放 muxer
        if (muxerToRelease != null) {
            try {
                if (wasMuxerStarted) {
                    muxerToRelease.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                muxerToRelease.release();
            } catch (Exception e) {
                AppLog.w(TAG, "Writer close failed; pending file retained: " + e.getMessage());
            }
        }

        // 停止编码线程
        if (drainHandlerToClear != null) {
            drainHandlerToClear.removeCallbacksAndMessages(null);
        }
        if (drainThreadToQuit != null) {
            drainThreadToQuit.quitSafely();
            try {
                drainThreadToQuit.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (encoderHandlerToClear != null) {
            encoderHandlerToClear.removeCallbacksAndMessages(null);
        }
        if (encoderThreadToQuit != null) {
            encoderThreadToQuit.quitSafely();
            try {
                encoderThreadToQuit.join(1000);
            } catch (InterruptedException ignored) {
            }
        }

        // 清理分段处理线程
        if (segmentThreadToQuit != null) {
            segmentThreadToQuit.quitSafely();
            try {
                segmentThreadToQuit.join(3000);  // 等待异步分段收尾完成
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AppLog.w(TAG, "Camera " + cameraId + " segment thread join interrupted");
            }
        }
    }

    /**
     * 获取录制用的 Surface（供 Camera 使用）
     * 直连路径返回 MediaCodec.createInputSurface()；GL 路径缓存包装 SurfaceTexture 的 Surface
     */
    public Surface getRecordSurface() {
        if (activePathMode == DirectEncodingPolicy.PathMode.DIRECT) {
            Surface direct = encoderInputSurface;
            return (direct != null && direct.isValid()) ? direct : null;
        }
        if (inputSurfaceTexture == null) {
            return null;
        }
        
        // 检查缓存的 Surface 是否有效
        if (cachedRecordSurface != null && cachedRecordSurface.isValid()) {
            return cachedRecordSurface;
        }
        
        // 释放旧的无效 Surface
        if (cachedRecordSurface != null) {
            AppLog.d(TAG, "Camera " + cameraId + " releasing invalid cached record surface");
            cachedRecordSurface.release();
            cachedRecordSurface = null;
        }
        
        // 创建新的 Surface 并缓存
        cachedRecordSurface = new Surface(inputSurfaceTexture);
        AppLog.d(TAG, "Camera " + cameraId + " created new record surface");
        return cachedRecordSurface;
    }

    /**
     * 获取当前文件路径
     */
    public String getCurrentFilePath() {
        return currentFilePath;
    }

    /**
     * 检查是否正在录制
     */
    public boolean isRecording() {
        return isRecording.get();
    }

    // ===== 私有方法 =====

    /**
     * 创建 MediaCodec 编码器
     * 优先尝试 HEVC (H.265)，如果不支持则回退到 H.264
     */
    private void createEncoder() throws IOException {
        // 如果启用了补盲优化模式，使用降低的帧率
        int effectiveFrameRate = blindSpotOptimizeMode ? BLIND_SPOT_OPTIMIZED_FPS : frameRate;
        if (eglEncoder != null) eglEncoder.setTargetFrameRate(effectiveFrameRate);

        // 码率：显式配置了 bitRate 时统一尊重用户码率等级（HEVC 按 55% 效率折算），未配置时回退自动估算
        int avcBitrate = resolveTargetBitrate(MIME_TYPE_H264);
        int hevcBitrate = resolveTargetBitrate(MIME_TYPE_HEVC);

        RecordingEncoderSelector.Result selected = RecordingEncoderSelector.create(width, height,
                effectiveFrameRate, avcBitrate, hevcBitrate, forceH264, keyFrameIntervalSeconds, hardwareOnly,
                e5Compatibility);
        MediaCodec localCodec = selected.codec;
        Surface localInputSurface = null;
        try {
            localInputSurface = localCodec.createInputSurface();
            localCodec.start();
        } catch (Exception e) {
            if (localInputSurface != null) {
                localInputSurface.release();
            }
            try {
                localCodec.release();
            } catch (Exception ignored) {
            }
            throw e;
        }

        synchronized (prepareReleaseLock) {
            if (stopRequested || isReleased) {
                localInputSurface.release();
                try {
                    localCodec.stop();
                } catch (Exception ignored) {
                }
                localCodec.release();
                throw new PrepareCancelledException();
            }
            encoder = localCodec;
            mimeType = selected.mime;
            outputFormat = null;
            encoderInputSurface = localInputSurface;
            bufferInfo = new MediaCodec.BufferInfo();
        }

        AppLog.d(TAG, "Camera " + cameraId + " Encoder created: " + width + "x" + height +
                " @ " + effectiveFrameRate + "fps" + (blindSpotOptimizeMode ? "(补盲优化)" : "") +
                ", " + (selected.bitrate / 1000) + " Kbps, " +
                (mimeType.equals(MIME_TYPE_HEVC) ? "HEVC" : "H.264") +
                (forceH264 ? " [兼容模式]" : ""));
    }


    /**
     * 解析目标编码格式的最终码率：
     * 1. 若调用方已按用户设置（低/标准/高）显式传入 bitRate > 0，优先尊重该配置（HEVC 按 55% 效率折算）
     * 2. 否则回退到 calculateOptimalBitrate() 自动估算
     */
    int resolveTargetBitrate(String targetMime) {
        if (bitRate <= 0) {
            return calculateOptimalBitrate(targetMime);
        }
        boolean isHevc = MIME_TYPE_HEVC.equals(targetMime);
        long resolved = isHevc ? Math.round(bitRate * 0.55) : (long) bitRate;
        int maxAllowed = isHevc ? MAX_HEVC_BITRATE : MAX_BITRATE;
        int minAllowed = 500_000;
        resolved = Math.max(minAllowed, Math.min(resolved, maxAllowed));
        if (isHevc) {
            resolved = ((resolved + 50_000) / 100_000) * 100_000;
        }
        return (int) resolved;
    }

    /**
     * 计算最优码率
     * 根据分辨率、帧率和画质等级计算
     * HEVC 使用更低的码率（相同画质下约为 H.264 的 60%）
     * 
     * 优化策略：
     * 1. 降低各画质等级的 bpp 值，减少编码压力
     * 2. 根据分辨率动态调整，高分辨率适当降低 bpp
     * 3. 严格限制最大码率，防止编码器过载
     */
    private int calculateOptimalBitrate(String targetMime) {
        // 基础码率计算（每像素每帧的比特数）- 优化后的值，降低码率减少CPU占用
        double bitsPerPixelPerFrame;

        switch (qualityLevel) {
            case 0: // 低画质 - 适合长时间录制
                bitsPerPixelPerFrame = 0.03;
                break;
            case 1: // 中画质 - 平衡画质和性能
                bitsPerPixelPerFrame = 0.05;
                break;
            case 2: // 高画质（推荐）- 优化后的值
                bitsPerPixelPerFrame = 0.07;
                break;
            case 3: // 最高画质 - 适当降低 bpp 减少CPU
                bitsPerPixelPerFrame = 0.10;
                break;
            default:
                bitsPerPixelPerFrame = 0.07;
        }

        // 根据分辨率调整 bpp：分辨率越高，bpp 适当降低（编码效率提升）
        long totalPixels = (long) width * height;
        if (totalPixels > 2073600) { // 超过 1080p (1920x1080)
            bitsPerPixelPerFrame *= 0.85; // 降低 15%
        } else if (totalPixels > 921600) { // 超过 720p (1280x720)
            bitsPerPixelPerFrame *= 0.90; // 降低 10%
        }

        // HEVC 效率更高，相同画质下使用 55% 的码率
        boolean isHevc = targetMime.equals(MIME_TYPE_HEVC);
        if (isHevc) {
            bitsPerPixelPerFrame *= 0.55;
        }

        // 计算总码率
        long bitrate = (long) (width * height * frameRate * bitsPerPixelPerFrame);

        // 严格设置码率上限（防止过高导致编码器卡顿）
        int maxAllowedBitrate = isHevc ? MAX_HEVC_BITRATE : MAX_BITRATE;
        bitrate = Math.min(bitrate, maxAllowedBitrate);

        // 设置码率下限（保证基本画质）
        int minAllowedBitrate = isHevc ? 1000000 : 1500000; // HEVC 1Mbps, H.264 1.5Mbps
        bitrate = Math.max(bitrate, minAllowedBitrate);

        // 四舍五入到 100Kbps，便于日志阅读
        bitrate = ((bitrate + 50000) / 100000) * 100000;

        return (int) bitrate;
    }

    /**
     * 创建 MediaMuxer
     */
    private void createMuxer(String filePath) throws IOException {
        AsyncMuxerWriter localMuxer = new AsyncMuxerWriter(filePath, this::handleMuxerFailure);
        synchronized (prepareReleaseLock) {
            if (stopRequested || isReleased) {
                try {
                    localMuxer.release();
                } catch (Exception ignored) {
                }
                throw new PrepareCancelledException();
            }
            muxer = localMuxer;
            videoTrackIndex = -1;
            muxerStarted = false;
            muxerClock.reset();
            encodedOutputFrameCount = 0;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Muxer created: " + filePath);
    }

    /** 处理写入器异常；首次写入前若遇到权限/只读/满盘等致命存储错误，立即中止无意义的重建循环。 */
    void handleMuxerFailure(Throwable error) {
        encoderHealthy = false;
        AppLog.e(TAG, "Camera " + cameraId + " Writer failed; incomplete video remains .pending", error);
        String detail = RecordingStorageAccess.describeThrowable(error);
        boolean fatal = !hasFirstWrite && RecordingStorageAccess.isFatalWriteError(error);
        if (fatal) {
            fatalWriteError = true;
            fatalWriteErrorMessage = detail;
            stopRequested = true;
            isRecording.set(false);
            cancelMonitoringTimers();
            if (drainHandler != null) {
                drainHandler.removeCallbacks(delayedDrain);
                drainHandler.removeCallbacks(preStartDrain);
            }
            AppLog.e(TAG, "Camera " + cameraId + " Fatal startup write error, aborting rebuild: " + detail);
        }
        if (callback != null) {
            String message = detail.isEmpty() ? "unknown error" : detail;
            callback.onRecordError(cameraId, fatal ? message : ("Video writer failed: " + message));
        }
    }

    private void cancelMonitoringTimers() {
        Handler handler = segmentHandler;
        Runnable segment = segmentRunnable;
        segmentRunnable = null;
        Runnable sizeCheck = fileSizeCheckRunnable;
        fileSizeCheckRunnable = null;
        Runnable firstWrite = firstWriteTimeoutRunnable;
        firstWriteTimeoutRunnable = null;
        Runnable recovery = recoveryRunnable;
        recoveryRunnable = null;
        Runnable health = healthCheckRunnable;
        healthCheckRunnable = null;
        if (handler == null) {
            return;
        }
        if (segment != null) handler.removeCallbacks(segment);
        if (sizeCheck != null) handler.removeCallbacks(sizeCheck);
        if (firstWrite != null) handler.removeCallbacks(firstWrite);
        if (recovery != null) handler.removeCallbacks(recovery);
        if (health != null) handler.removeCallbacks(health);
    }

    public boolean hasFatalWriteError() {
        return fatalWriteError;
    }

    public String getFatalWriteErrorMessage() {
        return fatalWriteErrorMessage;
    }

    // GL submission and output draining have separate owning threads.

    void drainPreStartEncoderLocked() {
        if (encoder == null || bufferInfo == null) {
            return;
        }
        int processed = 0;
        try {
            while (processed < DRAIN_BATCH_SIZE) {
                int index = encoder.dequeueOutputBuffer(bufferInfo, 0);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = encoder.getOutputFormat();
                    encoderHealthy = true;
                    continue;
                }
                if (index < 0) {
                    continue;
                }
                try {
                    processed++;
                } finally {
                    encoder.releaseOutputBuffer(index, false);
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Error draining pre-start direct encoder", e);
            encoderHealthy = false;
        }
    }

    private void requestSyncFrameLocked() {
        try {
            android.os.Bundle parameters = new android.os.Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            if (encoder != null) {
                encoder.setParameters(parameters);
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Sync-frame request unavailable; waiting for natural key frame");
        }
    }

    private void drainEncoder(boolean endOfStream) {
        drainEncoderWithResult(endOfStream);
    }

    /** Normal drains never wait; final drains read all samples through EOS with a deadline. */
    private boolean drainEncoderWithResult(boolean endOfStream) {
        synchronized (codecLock) { return drainEncoderLocked(endOfStream); }
    }

    private boolean drainEncoderLocked(boolean endOfStream) {
        if (encoder == null) return false;
        boolean gotOutput = false;
        int processed = 0;
        long deadline = SystemClock.elapsedRealtime() + EOS_DRAIN_TIMEOUT_MS;
        try {
            while (endOfStream || processed < DRAIN_BATCH_SIZE) {
                if (endOfStream && SystemClock.elapsedRealtime() >= deadline) {
                    IllegalStateException timeout = new IllegalStateException("Timed out waiting for encoder EOS");
                    if (muxer != null) muxer.abort(timeout);
                    throw timeout;
                }
                long pollStart = System.nanoTime();
                int index = encoder.dequeueOutputBuffer(bufferInfo, endOfStream ? 10000 : 0);
                if (!endOfStream) {
                    long us = (System.nanoTime() - pollStart) / 1000;
                    codecPolls++; totalCodecPollUs += us; maxCodecPollUs = Math.max(maxCodecPollUs,us);
                }
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) break;
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = encoder.getOutputFormat();
                    if (!awaitingInitialKeyFrame && !muxerStarted && muxer != null) {
                        videoTrackIndex = muxer.addTrack(outputFormat);
                        muxer.start();
                        muxerStarted = true;
                        encoderHealthy = true;
                    }
                    continue;
                }
                if (index < 0) continue;
                boolean eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                try {
                    ByteBuffer data = encoder.getOutputBuffer(index);
                    if (data != null && bufferInfo.size > 0
                            && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        if (awaitingInitialKeyFrame && !isKeyFrame) {
                            directSkippedPreKeyFrames++;
                        } else {
                            if (awaitingInitialKeyFrame) {
                                awaitingInitialKeyFrame = false;
                            }
                            if (!muxerStarted) {
                                if (outputFormat != null && muxer != null) {
                                    videoTrackIndex = muxer.addTrack(outputFormat);
                                    muxer.start();
                                    muxerStarted = true;
                                    encoderHealthy = true;
                                } else {
                                    throw new IllegalStateException("Muxer not started");
                                }
                            }
                            if (!endOfStream && segmentRequested && isKeyFrame) {
                                rotateMuxerAtKeyFrame();
                            }
                            long correctedBefore = muxerClock.getCorrectedSamples();
                            bufferInfo.presentationTimeUs = muxerClock.normalize(bufferInfo.presentationTimeUs);
                            if (muxerClock.getCorrectedSamples() != correctedBefore) {
                                AppLog.w(TAG, "Camera " + cameraId + " Corrected non-monotonic encoder PTS");
                            }
                            data.position(bufferInfo.offset);
                            data.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(videoTrackIndex, data, bufferInfo);
                            encodedOutputFrameCount++;
                            totalEncodedOutputFrameCount++;
                            framesWithoutEncoderOutput = 0;
                            lastEncoderOutputTime = System.currentTimeMillis();
                            gotOutput = true;
                        }
                    }
                } finally {
                    encoder.releaseOutputBuffer(index, false);
                }
                processed++;
                if (eos) break;
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Error draining encoder", e);
            encoderHealthy = false;
        }
        return gotOutput;
    }

    long getDisplayRecordedFrameCount() {
        return isDirectInputActive() ? totalEncodedOutputFrameCount : recordedFrameCount;
    }

    String formatFileSizeLogFrames() {
        if (isDirectInputActive()) {
            return totalEncodedOutputFrameCount + " (segment: " + encodedOutputFrameCount + ")";
        }
        return String.valueOf(recordedFrameCount);
    }

    private void logRecordingStats() {
        long durationUs = muxerClock.getLastPtsUs();
        double actualFps = durationUs > 0 ? (encodedOutputFrameCount - 1) * 1_000_000.0 / durationUs : 0;
        boolean direct = isDirectInputActive();
        AppLog.i(TAG, "Recording stats camera=" + cameraId + ", path=" + activePathMode.logTag
                + ", fallbackReason=" + pathFallbackReason.code
                + ", size=" + width + "x" + height
                + ", codec=" + mimeType + ", targetFps=" + frameRate
                + ", actualFps=" + String.format(Locale.US, "%.2f", actualFps)
                + ", segmentFrames=" + encodedOutputFrameCount + ", durationUs=" + durationUs
                + ", inputCallbacks=" + inputFrameCallbacks + ", submittedFrames=" + recordedFrameCount
                + ", preKeySkips=" + directSkippedPreKeyFrames
                + ", correctedPts=" + muxerClock.getCorrectedSamples());
        long glAvg = (direct || recordedFrameCount == 0) ? 0 : totalGlUs / recordedFrameCount;
        long glMax = direct ? 0 : maxGlUs;
        AppLog.i(TAG, "Recording timing camera=" + cameraId + ", path=" + activePathMode.logTag
                + ", glAvgUs=" + glAvg
                + ", glMaxUs=" + glMax + ", " + (muxer == null ? "noWriter" : muxer.stats()));
        AppLog.i(TAG, "Recording codec poll camera=" + cameraId + ", path=" + activePathMode.logTag
                + ", avgUs=" + (codecPolls == 0 ? 0 : totalCodecPollUs / codecPolls) + ", maxUs=" + maxCodecPollUs);
        if (direct) {
            AppLog.i(TAG, "Recording input camera=" + cameraId + ", path=direct, glSkipped=true, preKeySkips="
                    + directSkippedPreKeyFrames);
        } else if (eglEncoder != null) {
            AppLog.i(TAG, "Recording input camera=" + cameraId + ", path=gl, " + eglEncoder.frameStats());
        }
    }

    public String startupDiagnostics() {
        AsyncMuxerWriter writer = muxer;
        String base = "camera=" + cameraId + ", path=" + activePathMode.logTag
                + ", fallbackReason=" + pathFallbackReason.code
                + ", started=" + isRecording.get() + ", inputCallbacks=" + inputFrameCallbacks
                + ", submitted=" + recordedFrameCount + ", encoded=" + encodedOutputFrameCount
                + ", awaitingKey=" + awaitingInitialKeyFrame + ", preKeySkips=" + directSkippedPreKeyFrames
                + ", muxerStarted=" + muxerStarted + ", written=" + (writer == null ? 0 : writer.getWrittenSamples())
                + ", healthy=" + encoderHealthy + ", fatalWriteError=" + fatalWriteError
                + ", e5Compatibility=" + e5Compatibility;
        return fatalWriteErrorMessage != null ? base + ", fatalDetail=" + fatalWriteErrorMessage : base;
    }

    /**
     * 调度下一段录制
     * 
     * 注意：分段时长需要加上补偿时间，因为：
     * 1. 编码器初始化需要时间
     * 2. 停止时需要排空编码器缓冲区
     * 3. 这样可以确保实际录制的视频时长达到设定的分段时长
     */
    private void scheduleNextSegment() {
        Handler segHandler = segmentHandler;
        if (segHandler == null) {
            return;
        }
        if (segmentRunnable != null) {
            segHandler.removeCallbacks(segmentRunnable);
        }

        segmentRunnable = () -> {
            if (!isRecording.get() || isReleased || stopRequested) {
                return;
            }
            Handler targetHandler = encoderHandler != null ? encoderHandler : drainHandler;
            if (targetHandler != null) {
                AppLog.d(TAG, "Camera " + cameraId + " Scheduling segment switch on "
                        + (encoderHandler != null ? "encoder" : "drain") + " thread");
                targetHandler.post(this::switchToNextSegment);
            } else {
                switchToNextSegment();
            }
        };

        // 延迟执行（使用配置的分段时长 + 补偿时间）
        // 补偿编码器初始化延迟和停止时的帧丢失
        long actualDelayMs = segmentDurationMs + SEGMENT_DURATION_COMPENSATION_MS;
        segHandler.postDelayed(segmentRunnable, actualDelayMs);
        AppLog.d(TAG, "Camera " + cameraId + " Scheduled next segment in " + (segmentDurationMs / 1000) + " seconds (actual delay: " + actualDelayMs + "ms)");
    }

    /** Request a key frame; the output thread rotates only the muxer at that frame. */
    private void switchToNextSegment() {
        if (!isRecording.get() || isReleased || stopRequested || segmentRequested) return;
        segmentRequested = true;
        synchronized (codecLock) {
            requestSyncFrameLocked();
        }
    }

    /** Runs on the output thread. The same codec/camera/GL input continues across files. */
    private void rotateMuxerAtKeyFrame() throws IOException {
        if (outputFormat == null) return;
        segmentRequested = false;
        final AsyncMuxerWriter previousMuxer = this.muxer;
        final String completedPath = this.currentFilePath;
        logRecordingStats();
        segmentIndex++;
        currentFilePath = generateSegmentPath();
        recordedFilePaths.add(currentFilePath);
        createMuxer(currentFilePath);
        videoTrackIndex = muxer.addTrack(outputFormat);
        muxer.start();
        muxerStarted = true;
        lastFileSize = 0;
        final int index = segmentIndex;
        Runnable finalizePrevious = () -> {
            boolean published = true;
            if (previousMuxer != null) {
                try {
                    previousMuxer.stop();
                } catch (Exception e) {
                    published = false;
                    AppLog.e(TAG, "Camera " + cameraId + " Async segment finalize failed: " + completedPath, e);
                } finally {
                    try {
                        previousMuxer.release();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (published) {
                validateAndCleanupFile(completedPath);
                if (callback != null) callback.onSegmentSwitch(cameraId, index, completedPath);
            }
            if (isRecording.get() && !stopRequested && !isReleased) {
                scheduleNextSegment();
            }
        };
        Handler segHandler = segmentHandler;
        if (segHandler != null && segHandler.post(finalizePrevious)) {
            return;
        }
        finalizePrevious.run();
    }

    private void awaitPendingSegmentFinalizations(long timeoutMs) {
        Handler segHandler = segmentHandler;
        if (segHandler == null || Looper.myLooper() == segHandler.getLooper()) {
            return;
        }
        final java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        if (segHandler.post(drained::countDown)) {
            try {
                drained.await(Math.max(1L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 调度快速恢复重试
     */
    private void scheduleRecoveryRetry() {
        if (stopRequested || isReleased || fatalWriteError || segmentHandler == null) {
            return;
        }
        // 取消之前的恢复任务
        if (recoveryRunnable != null) {
            segmentHandler.removeCallbacks(recoveryRunnable);
        }
        
        recoveryRunnable = () -> {
            if (!isReleased && !stopRequested && !fatalWriteError && encoderHandler != null) {
                AppLog.d(TAG, "Camera " + cameraId + " Recovery retry triggered");
                // 在编码线程上执行恢复
                encoderHandler.post(() -> attemptRecovery());
            }
        };
        
        segmentHandler.postDelayed(recoveryRunnable, RECOVERY_RETRY_INTERVAL_MS);
    }
    
    /**
     * 尝试恢复录制
     */
    private void attemptRecovery() {
        synchronized (codecLock) { attemptRecoveryLocked(); }
    }

    private void attemptRecoveryLocked() {
        if (stopRequested || isReleased || fatalWriteError) return;
        if (isDirectInputActive()) {
            AppLog.w(TAG, "Camera " + cameraId + " Skipping in-place encoder recovery on direct input path");
            return;
        }
        AppLog.d(TAG, "Camera " + cameraId + " Attempting recovery (attempt " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ")");
        
        boolean recoverySuccess = false;
        
        try {
            // 确保编码器和 EGL 已准备好
            if (encoder == null) {
                createEncoder();
                if (eglEncoder != null && encoderInputSurface != null) {
                    eglEncoder.updateOutputSurface(encoderInputSurface);
                }
            }
            
            // 创建新的 Muxer
            if (muxer == null) {
                String nextSegmentPath = generateSegmentPath();
                currentFilePath = nextSegmentPath;
                createMuxer(nextSegmentPath);
            }
            
            // 重置分段开始时间和帧计数
            encodedOutputFrameCount = 0;
            
            // 恢复录制
            if (stopRequested || isReleased) return;
            encoderHealthy = true;
            isRecording.set(true);
            scheduleDrain();
            recoverySuccess = true;
            
            // 成功：重置恢复计数器
            recoveryAttempts = 0;
            
            AppLog.d(TAG, "Camera " + cameraId + " Recovery successful, recording resumed: " + currentFilePath);
            
            // 调度正常的1分钟定时器
            segmentHandler.post(() -> scheduleNextSegment());
            
        } catch (Exception e) {
            if (e instanceof PrepareCancelledException || stopRequested || isReleased) {
                isRecording.set(false);
                return;
            }
            AppLog.e(TAG, "Camera " + cameraId + " Recovery attempt failed", e);
            isRecording.set(false);
            
            // 继续快速重试或回到正常间隔
            recoveryAttempts++;
            if (recoveryAttempts <= MAX_RECOVERY_ATTEMPTS) {
                AppLog.w(TAG, "Camera " + cameraId + " Recovery failed, quick retry in " 
                    + (RECOVERY_RETRY_INTERVAL_MS / 1000) + "s (attempt " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ")");
                scheduleRecoveryRetry();
            } else {
                AppLog.w(TAG, "Camera " + cameraId + " Max recovery attempts reached, will retry in " 
                    + (segmentDurationMs / 1000) + " seconds");
                recoveryAttempts = 0;
                segmentHandler.post(() -> scheduleNextSegment());
            }
        }
    }
    

    /**
     * 生成新的分段文件路径
     * 优先使用 TimestampProvider 获取统一时间戳（多路摄像头同步）
     * 如果没有设置 provider，则使用当前时间
     */
    private String generateSegmentPath() {
        String timestamp;
        if (timestampProvider != null) {
            // 使用统一的时间戳提供者（确保多路摄像头使用相同时间戳）
            timestamp = timestampProvider.getSegmentTimestamp();
            AppLog.d(TAG, "Camera " + cameraId + " using provider timestamp: " + timestamp);
        } else {
            // 回退到独立生成时间戳（兼容旧逻辑）
            timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            AppLog.d(TAG, "Camera " + cameraId + " using local timestamp: " + timestamp);
        }
        String fileName = timestamp + "_" + cameraPosition + ".mp4";
        return new File(saveDirectory, fileName).getAbsolutePath();
    }

    /**
     * 调度编码器健康检查
     * 检测编码器是否正常工作，如果长时间无输出则尝试重建
     */
    private void scheduleEncoderHealthCheck() {
        if (healthCheckRunnable != null) {
            segmentHandler.removeCallbacks(healthCheckRunnable);
        }

        healthCheckRunnable = () -> {
            if (!isRecording.get() || isReleased || stopRequested || fatalWriteError) {
                return;
            }

            // 检查编码器健康状态
            boolean needsRecovery = false;
            String reason = "";
            boolean directActive = isDirectInputActive();

            if (!encoderHealthy) {
                needsRecovery = true;
                reason = "encoder marked unhealthy";
            } else if (directActive) {
                if (hasFirstWrite && (System.currentTimeMillis() - lastEncoderOutputTime) > DIRECT_NO_OUTPUT_STALL_MS) {
                    needsRecovery = true;
                    reason = "direct encoder output stalled";
                }
            } else if (!muxerStarted && recordedFrameCount > MAX_FRAMES_WITHOUT_OUTPUT) {
                // Muxer 从未启动，但已经处理了很多帧
                needsRecovery = true;
                reason = "muxer never started after " + recordedFrameCount + " frames";
            } else if (framesWithoutEncoderOutput > MAX_FRAMES_WITHOUT_OUTPUT
                    && System.currentTimeMillis() - lastEncoderOutputTime > 1500) {
                needsRecovery = true;
                reason = "no encoder output for " + framesWithoutEncoderOutput + " frames";
            }

            if (needsRecovery) {
                AppLog.w(TAG, "Camera " + cameraId + " Encoder health check FAILED: " + reason
                        + " (path=" + activePathMode.logTag + ")");
                if (directActive) {
                    encoderHealthy = false;
                    if (callback != null) {
                        callback.onRecordingRebuildRequested(cameraId, "direct_health_failed:" + reason);
                    }
                    return;
                }
                AppLog.w(TAG, "Camera " + cameraId + " Attempting to rebuild encoder...");

                // 在编码线程上执行重建
                if (encoderHandler != null) {
                    encoderHandler.post(() -> rebuildEncoder());
                }
            } else {
                // 编码器健康，继续调度下一次检查
                scheduleEncoderHealthCheck();
            }
        };

        segmentHandler.postDelayed(healthCheckRunnable, ENCODER_HEALTH_CHECK_INTERVAL_MS);
    }

    /**
     * 重建编码器（在编码线程上执行）
     * 当检测到编码器不健康时调用
     */
    private void rebuildEncoder() {
        synchronized (codecLock) { rebuildEncoderLocked(); }
    }

    private void rebuildEncoderLocked() {
        if (stopRequested || isReleased || fatalWriteError) return;
        if (isDirectInputActive()) {
            AppLog.w(TAG, "Camera " + cameraId + " Skipping in-place encoder rebuild on direct input path");
            return;
        }
        AppLog.d(TAG, "Camera " + cameraId + " Rebuilding encoder due to health check failure");

        // 暂停录制
        isRecording.set(false);

        try {
            // 1. 清理旧的 Muxer（可能已损坏）
            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        muxer.stop();
                    }
                    muxer.release();
                } catch (Exception e) {
                    AppLog.w(TAG, "Camera " + cameraId + " Error releasing old muxer: " + e.getMessage());
                }
                muxer = null;
                muxerStarted = false;
                videoTrackIndex = -1;
            }

            // 2. 清理旧的编码器
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception e) {
                    // Ignore
                }
                try {
                    encoder.release();
                } catch (Exception e) {
                    // Ignore
                }
                encoder = null;
            }

            if (encoderInputSurface != null) {
                try {
                    encoderInputSurface.release();
                } catch (Exception e) {
                    // Ignore
                }
                encoderInputSurface = null;
            }

            // 3. 小延迟让系统释放资源
            Thread.sleep(100);

            // 4. 重新创建编码器
            createEncoder();

            // 5. 更新 EGL 输出 Surface
            if (eglEncoder != null && encoderInputSurface != null) {
                eglEncoder.updateOutputSurface(encoderInputSurface);
            }

            // 6. 创建新的 Muxer（生成新的文件名）
            segmentIndex++;
            String newFilePath = generateSegmentPath();
            currentFilePath = newFilePath;
            recordedFilePaths.add(newFilePath);
            createMuxer(newFilePath);

            // 7. 重置状态
            encodedOutputFrameCount = 0;
            framesWithoutEncoderOutput = 0;
            encoderHealthy = true;
            lastEncoderOutputTime = System.currentTimeMillis();

            // 8. 恢复录制
            if (stopRequested || isReleased) return;
            isRecording.set(true);
            segmentRequested = false;
            scheduleDrain();

            AppLog.d(TAG, "Camera " + cameraId + " Encoder rebuilt successfully, new file: " + newFilePath);

            // 9. 继续健康检查
            segmentHandler.post(() -> scheduleEncoderHealthCheck());

            // 10. 重新调度分段定时器
            segmentHandler.post(() -> scheduleNextSegment());

        } catch (Exception e) {
            if (e instanceof PrepareCancelledException || stopRequested || isReleased) {
                return;
            }
            AppLog.e(TAG, "Camera " + cameraId + " Failed to rebuild encoder", e);

            // 重建失败，启动恢复重试机制
            recoveryAttempts++;
            if (recoveryAttempts <= MAX_RECOVERY_ATTEMPTS) {
                AppLog.w(TAG, "Camera " + cameraId + " Will retry encoder rebuild in " 
                    + (RECOVERY_RETRY_INTERVAL_MS / 1000) + "s (attempt " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ")");
                scheduleRecoveryRetry();
            } else {
                AppLog.e(TAG, "Camera " + cameraId + " Max recovery attempts reached, giving up");
                if (callback != null) {
                    final String errorMsg = e.getMessage();
                    segmentHandler.post(() -> callback.onRecordError(cameraId, "Encoder rebuild failed: " + errorMsg));
                }
            }
        }
    }

    /**
     * 调度文件大小检查
     */
    private void scheduleFileSizeCheck() {
        if (fileSizeCheckRunnable != null) {
            segmentHandler.removeCallbacks(fileSizeCheckRunnable);
        }

        fileSizeCheckRunnable = () -> {
            if (!stopRequested && isRecording.get() && currentFilePath != null) {
                File file = new File(currentFilePath + ".pending");
                if (!file.exists()) file = new File(currentFilePath);
                long currentSize = file.exists() ? file.length() : 0;
                long sizeIncrease = currentSize - lastFileSize;

                // 检查是否有写入
                AsyncMuxerWriter writer = muxer;
                boolean hasWrite = writer != null && writer.getWrittenSamples() > 0;
                
                if (hasWrite) {
                    // 首次写入检测
                    if (!hasFirstWrite) {
                        hasFirstWrite = true;
                        AppLog.d(TAG, "Camera " + cameraId + " first write detected! Size: " + currentSize + " bytes");
                        // 取消首次写入超时检查
                        cancelFirstWriteTimeout();
                        
                        // 【核心改动】首次写入后才启动分段定时器
                        // 这确保了分段时长是"有效录制时长"而非"尝试录制时长"
                        scheduleNextSegment();
                        AppLog.d(TAG, "Camera " + cameraId + " segment timer started after first write");
                        
                        // 通知外部：首次写入成功，录制已真正开始
                        // 外部可以据此开始钉钉录制计时等
                        if (callback != null) {
                            callback.onFirstDataWritten(cameraId);
                        }
                    }
                    AppLog.d(TAG, "Camera " + cameraId + " file size: " + currentSize + " bytes (" + (currentSize / 1024) + " KB), frames: " + formatFileSizeLogFrames());
                } else if (sizeIncrease == 0 && lastFileSize > 0) {
                    AppLog.w(TAG, "Camera " + cameraId + " WARNING: File size not growing! Current: " + currentSize + " bytes");
                }

                lastFileSize = currentSize;
                
                // 继续下一次检查（首次写入前用快速间隔，之后用正常间隔）
                long nextDelay = hasFirstWrite ? FILE_SIZE_CHECK_INTERVAL_MS : FIRST_CHECK_DELAY_MS;
                segmentHandler.postDelayed(fileSizeCheckRunnable, nextDelay);
            }
        };

        // 首次检查使用更短的延迟，快速检测首次写入
        long initialDelay = hasFirstWrite ? FILE_SIZE_CHECK_INTERVAL_MS : FIRST_CHECK_DELAY_MS;
        segmentHandler.postDelayed(fileSizeCheckRunnable, initialDelay);
    }

    /**
     * 调度首次写入超时检查
     */
    private void scheduleFirstWriteTimeout() {
        // 取消之前的超时检查
        cancelFirstWriteTimeout();

        firstWriteTimeoutRunnable = () -> {
            if (isRecording.get() && !hasFirstWrite && !stopRequested && !fatalWriteError) {
                AppLog.e(TAG, "Camera " + cameraId + " FIRST WRITE TIMEOUT: No data written in " + (FIRST_WRITE_TIMEOUT_MS / 1000) + " seconds");
                // 触发编码器重建（通过健康检查机制处理）
                encoderHealthy = false;
                // 也可以通过回调通知外部
                if (callback != null) {
                    segmentHandler.post(() -> callback.onRecordingRebuildRequested(cameraId, "first_write_timeout"));
                }
            }
        };

        segmentHandler.postDelayed(firstWriteTimeoutRunnable, FIRST_WRITE_TIMEOUT_MS);
        AppLog.d(TAG, "Camera " + cameraId + " first write timeout scheduled: " + (FIRST_WRITE_TIMEOUT_MS / 1000) + " seconds");
    }

    /**
     * 取消首次写入超时检查
     */
    private void cancelFirstWriteTimeout() {
        if (firstWriteTimeoutRunnable != null) {
            segmentHandler.removeCallbacks(firstWriteTimeoutRunnable);
            firstWriteTimeoutRunnable = null;
        }
    }

    /**
     * 验证并清理所有录制的文件
     * @return 被删除的文件名列表
     */
    private List<String> validateAndCleanupAllFiles() {
        List<String> deletedFiles = new ArrayList<>();
        
        AppLog.d(TAG, "Camera " + cameraId + " validating " + recordedFilePaths.size() + " recorded files");
        
        for (String filePath : recordedFilePaths) {
            String deletedFileName = validateAndCleanupFile(filePath);
            if (deletedFileName != null) {
                deletedFiles.add(deletedFileName);
            }
        }
        
        if (!deletedFiles.isEmpty()) {
            AppLog.w(TAG, "Camera " + cameraId + " deleted " + deletedFiles.size() + " corrupted files: " + deletedFiles);
        }
        
        return deletedFiles;
    }

    /**
     * 验证并清理损坏的文件
     * @return 如果文件被删除，返回文件名；否则返回 null
     */
    private String validateAndCleanupFile(String filePath) {
        if (filePath == null) {
            return null;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }

        long fileSize = file.length();

        if (fileSize < MIN_VALID_FILE_SIZE) {
            AppLog.w(TAG, "Camera " + cameraId + " Video file too small: " + filePath + " (" + fileSize + " bytes). Deleting...");
            file.delete();
            return file.getName();
        } else {
            AppLog.d(TAG, "Camera " + cameraId + " Video file validated: " + filePath + " (" + (fileSize / 1024) + " KB)");
            return null;
        }
    }
}
