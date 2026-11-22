package com.kooo.evcam.camera;


import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.FileTransferManager;
import com.kooo.evcam.RecordingStorageAccess;
import com.kooo.evcam.StorageCleanupManager;
import com.kooo.evcam.StorageHelper;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Environment;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 四路摄像头管理器
 */
public class MultiCameraManager {
    private static final String TAG = "MultiCameraManager";

    private static final int DEFAULT_MAX_OPEN_CAMERAS = 4;
    private static final long RECORDING_STABLE_FRAME_MAX_AGE_MS = 1500;
    private static final int MAX_STABLE_WAIT_ATTEMPTS = 10;
    private static final long STABLE_WAIT_INTERVAL_MS = 200;
    // 录制分辨率将使用预览的实际分辨率，不再硬编码

    private final Context context;
    private final Map<String, SingleCamera> cameras = new LinkedHashMap<>();
    private final Map<String, VideoRecorder> recorders = new LinkedHashMap<>();
    private final Map<String, CodecVideoRecorder> codecRecorders = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Boolean vehicleStatusWatermarkOverride;
    private volatile CompositeRecording compositeRecording;

    /** Applies on each recorder's own GL thread; never rebuilds a camera session. */
    public synchronized void setVehicleStatusWatermarkEnabled(boolean enabled) {
        vehicleStatusWatermarkOverride = enabled;
        for (CodecVideoRecorder recorder : codecRecorders.values()) {
            recorder.setVehicleStatusWatermarkEnabled(enabled);
        }
    }

    /** 将 3.2 画面调节与 3.3 四模块水印配置实时同步到正在录制的解码器（分辨率缩放下一段/下次录制生效）。 */
    public synchronized void refreshActiveRecorderSettings() {
        AppConfig config = new AppConfig(context);
        vehicleStatusWatermarkOverride = null;
        VehicleStatusWatermark.WatermarkOptions watermarkOpts = config.getWatermarkOptions();
        for (Map.Entry<String, CodecVideoRecorder> entry : codecRecorders.entrySet()) {
            String key = entry.getKey();
            CodecVideoRecorder recorder = entry.getValue();
            if (recorder == null) continue;
            if ("combined".equals(key)) {
                ImageAdjustmentConfig globalAdj = config.isVideoImageAdjustEnabled()
                        ? new ImageAdjustmentConfig(true, config.getVideoImageQualityPercent(),
                                config.getVideoImageBrightnessPercent(),
                                config.getVideoImageContrastPercent(),
                                config.getVideoImageSaturationPercent(),
                                0, false)
                        : ImageAdjustmentConfig.disabled();
                recorder.setImageAdjustmentConfig(globalAdj);
            } else {
                recorder.setImageAdjustmentConfig(config.getImageAdjustmentConfig(key));
            }
            recorder.setWatermarkOptions(watermarkOpts);
        }
    }

    /** 当非录制空闲预览态更新预览限帧档位时，平滑刷新各已打开摄像头的捕获会话。 */
    public synchronized void refreshPreviewFrameLimit() {
        if (isRecording || preparingRecording) return;
        for (SingleCamera camera : cameras.values()) {
            if (camera != null && camera.isCameraOpened() && camera.hasValidPreviewSurface()) {
                camera.recreateSession();
            }
        }
    }

    private synchronized void registerCodecRecorder(String key, CodecVideoRecorder recorder) {
        codecRecorders.put(key, recorder);
        Boolean override = vehicleStatusWatermarkOverride;
        if (override != null) recorder.setVehicleStatusWatermarkEnabled(override);
    }
    private final List<String> activeCameraKeys = new ArrayList<>();
    private int maxOpenCameras = DEFAULT_MAX_OPEN_CAMERAS;

    private boolean isRecording = false;
    private volatile boolean stoppingRecording;
    private final List<Runnable> recordingStoppedCallbacks = new ArrayList<>();
    private final PreviewDemand previewDemand = new PreviewDemand();
    private volatile boolean repairSuppressed = false;  // 主动关闭摄像头时抑制 repair loop
    private boolean useCodecRecording = false;  // 是否使用软编码录制（用于 L6/L7）
    private boolean useRelayWrite = false;      // 是否使用中转写入（录制到内部存储，异步传输到U盘）
    private File finalSaveDir = null;           // 最终存储目录（用于中转写入模式）
    private volatile int lastNotifiedSegmentIndex = -1;  // 已通知的分段索引，避免重复通知
    private long overrideSegmentDurationMs = 0;  // 临时覆盖分段时长（0=使用配置值，>0=使用此值）
    
    // 统一分段时间戳管理（解决多路摄像头分段切换时时间戳差1秒的问题）
    private String cachedSegmentTimestamp = null;  // 缓存的分段时间戳
    private long timestampGeneratedTime = 0;  // 时间戳生成时间（毫秒）
    private static final long TIMESTAMP_CACHE_DURATION_MS = 10000;  // 时间戳缓存有效期（10秒，需覆盖各摄像头首次写入的时间差）
    private final Object timestampLock = new Object();  // 时间戳访问锁
    
    // Watchdog 回退相关
    private String currentRecordingTimestamp = null;  // 当前录制的时间戳（用于重建时继续录制）
    private Set<String> currentEnabledCameras = null;  // 当前启用的摄像头集合
    private int rebuildAttemptCount = 0;  // 重建尝试次数（0=首次, 1=重建MediaRecorder, 2+=回退Codec）
    private static final int CODEC_FALLBACK_THRESHOLD = 2;  // 触发 Codec 回退的阈值
    private static final int MAX_NON_AUTO_REBUILD_ATTEMPTS = CODEC_FALLBACK_THRESHOLD + 1;
    private volatile boolean isRebuildingRecording = false;  // 是否正在重建录制（防止多摄像头并发触发）
    private final AtomicBoolean startupFailureReported = new AtomicBoolean(false);
    private final RecordingStartupTracker startupTracker = new RecordingStartupTracker();
    private volatile List<String> currentRecordingKeys = java.util.Collections.emptyList();
    private volatile long currentSegmentDurationMs = 60_000L;
    private volatile boolean startupInSyncPrepare = false;
    private final Object prepareLock = new Object();
    private final java.util.concurrent.atomic.AtomicLong prepareGeneration = new java.util.concurrent.atomic.AtomicLong(0L);
    private volatile boolean preparingRecording = false;
    private volatile CodecPrepareCoordinator<CodecVideoRecorder, android.view.Surface> activePrepareCoordinator = null;
    private final DirectEncodingPolicy.FallbackGuard directFallbackGuard = new DirectEncodingPolicy.FallbackGuard();
    private final Set<String> pendingDirectFallbackStartKeys = java.util.Collections.synchronizedSet(new HashSet<>());
    private final Map<String, CodecVideoRecorder> inFlightFallbackRecorders = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String lastStartupErrorMessage = null;
    private StatusCallback statusCallback;
    private PreviewSizeCallback previewSizeCallback;
    private PhotoSurfaceProvider photoSurfaceProvider;
    private final Map<String, TextureView> savedPhotoTextureViews = new LinkedHashMap<>();
    private final List<String> stagedPhotoCameraKeys = new ArrayList<>();
    private volatile int sessionConfiguredCount = 0;
    private volatile int expectedSessionCount = 0;
    private Runnable pendingRecordingStart = null;
    private android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private java.util.function.Consumer<String> recordingStartupFailureCallback;
    private final RecordingStartupWatchdog startupWatchdog = new RecordingStartupWatchdog(mainHandler,20000,
            this::recordingStartupDiagnostics,this::failRecordingStartup);

    public void setRecordingStartupFailureCallback(java.util.function.Consumer<String> callback) {
        recordingStartupFailureCallback=callback;
    }

    public String getLastStartupErrorMessage() {
        return lastStartupErrorMessage;
    }

    private String recordingStartupDiagnostics() {
        Map<String,Boolean> ready;
        synchronized (sessionLock) { ready=new LinkedHashMap<>(cameraSessionReady); }
        StringBuilder detail=new StringBuilder("Recording startup timeout; sessionReady="+ready);
        detail.append("; startup=").append(startupTracker.formatSummary());
        for (Map.Entry<String,CodecVideoRecorder> entry : codecRecorders.entrySet()) {
            detail.append("; ").append(entry.getKey()).append(':').append(entry.getValue().startupDiagnostics());
        }
        return detail.toString();
    }

    private void failRecordingStartup(String detail) {
        if (startupTracker.isTracking()) {
            RecordingStartupTracker.Outcome outcome = startupTracker.onWatchdogTimeout();
            if (!outcome.allFailed && outcome.partialFailure) {
                AppLog.e(TAG, "Partial recording startup timeout: " + detail
                        + ", active=" + outcome.writtenKeys + ", failed=" + outcome.failedKeys);
                cleanupFailedCameraRecorders(outcome.failedKeys);
                if (recordingStatusCallback != null) {
                    final Set<String> activeCopy = new java.util.LinkedHashSet<>(outcome.writtenKeys);
                    final Set<String> failedCopy = new java.util.LinkedHashSet<>(outcome.failedKeys);
                    mainHandler.post(() -> recordingStatusCallback.onPartialRecordingStart(activeCopy, failedCopy));
                }
                return;
            }
        }
        failRecordingStartupWithUserMessage(detail, "录像启动超时，未收到有效视频数据，请在设置中保存日志");
    }

    private void failRecordingStartupWithUserMessage(String detail, String userMessage) {
        startupWatchdog.cancel();
        AppLog.e(TAG, detail);
        if (!startupFailureReported.compareAndSet(false, true)) {
            AppLog.d(TAG, "Suppressing duplicate startup failure notification: " + detail);
            return;
        }
        if (lastStartupErrorMessage == null && detail != null) {
            lastStartupErrorMessage = detail;
        }
        // Clear the waiting UI immediately, even if a failing storage device is slow to close.
        stopRecording(false);
        final String message = (userMessage == null || userMessage.isEmpty())
                ? "录像启动失败，请在设置中保存日志"
                : userMessage;
        if (recordingStartupFailureCallback != null) {
            Runnable notify = () -> recordingStartupFailureCallback.accept(message);
            if (Looper.myLooper() == Looper.getMainLooper()) {
                notify.run();
            } else {
                mainHandler.post(notify);
            }
        }
    }

    private String resolveRecordingKeyByCameraId(String cameraId) {
        if (cameraId == null || cameraId.isEmpty()) {
            return null;
        }
        if ("combined".equals(cameraId)) {
            return "combined";
        }
        List<String> recordingKeys = currentRecordingKeys;
        if (recordingKeys != null) {
            for (String key : recordingKeys) {
                SingleCamera camera = cameras.get(key);
                if (camera != null && cameraId.equals(camera.getCameraId())) {
                    return key;
                }
            }
        }
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            if (cameraId.equals(entry.getValue().getCameraId())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static boolean isTerminalRecorderError(String error) {
        if (error == null || error.isEmpty()) {
            return false;
        }
        return error.contains("Encoder rebuild failed")
                || error.contains("Max recovery")
                || error.contains("Video writer failed")
                || error.contains("MediaRecorder error")
                || error.contains("Failed to switch segment");
    }

    private void cleanupFailedCameraRecorders(Set<String> failedKeys) {
        if (failedKeys == null || failedKeys.isEmpty()) {
            return;
        }
        for (String failedKey : failedKeys) {
            cleanupFailedCameraRecorder(failedKey);
        }
    }

    private void cleanupFailedCameraRecorder(String key) {
        if (key == null || key.isEmpty() || "combined".equals(key)) {
            return;
        }
        final CodecVideoRecorder codecRecorder = codecRecorders.remove(key);
        if (codecRecorder != null) {
            codecRecorder.requestStop();
            new Thread(() -> {
                try {
                    codecRecorder.release();
                } catch (Exception e) {
                    AppLog.w(TAG, "Error releasing failed codec recorder for " + key, e);
                }
            }, "ReleaseFailedCodec-" + key).start();
        }
        final VideoRecorder videoRecorder = !useCodecRecording ? recorders.get(key) : null;
        if (videoRecorder != null) {
            new Thread(() -> {
                try {
                    videoRecorder.reset();
                } catch (Exception e) {
                    AppLog.w(TAG, "Error resetting failed video recorder for " + key, e);
                }
            }, "ResetFailedRecorder-" + key).start();
        }
        synchronized (sessionLock) {
            cameraRecordingActive.put(key, false);
        }
        SingleCamera camera = cameras.get(key);
        if (camera != null) {
            camera.clearRecordSurface();
            if (!camera.closeIfUnused()) {
                camera.recreateSession();
            }
        }
    }

    private void handleFirstDataWrittenForKey(String key) {
        RecordingStartupTracker.Outcome outcome = startupTracker.recordFirstDataWritten(key);
        if (outcome.shouldCancelWatchdog) {
            startupWatchdog.cancel();
        }
        if (outcome.shouldNotifyFirstWrite && !hasNotifiedFirstDataWritten) {
            hasNotifiedFirstDataWritten = true;
            if (firstDataWrittenCallback != null) {
                AppLog.d(TAG, "Notifying external: first data written (" + key + "), recording truly started");
                mainHandler.post(() -> firstDataWrittenCallback.onFirstDataWritten());
            }
        }
        if (outcome.partialFailure) {
            cleanupFailedCameraRecorders(outcome.failedKeys);
            if (recordingStatusCallback != null) {
                final Set<String> activeCopy = new java.util.LinkedHashSet<>(outcome.writtenKeys);
                final Set<String> failedCopy = new java.util.LinkedHashSet<>(outcome.failedKeys);
                mainHandler.post(() -> recordingStatusCallback.onPartialRecordingStart(activeCopy, failedCopy));
            }
        }
    }

    private void handleRecorderError(String mode, String cameraId, String error) {
        AppLog.e(TAG, mode + " recording error for camera " + cameraId + ": " + error);
        if (error != null && !error.isEmpty()) {
            lastStartupErrorMessage = error;
        }
        if (RecordingStorageAccess.isOutOfSpaceMessage(error)) {
            StorageCleanupManager.triggerEmergencyCleanupIfActive();
        }
        if (startupInSyncPrepare) {
            return;
        }
        String key = resolveRecordingKeyByCameraId(cameraId);
        CodecPrepareCoordinator<CodecVideoRecorder, android.view.Surface> activePrepare = activePrepareCoordinator;
        if (activePrepare != null && activePrepare.isKeyPending(key)) {
            return;
        }
        boolean fatalStorage = RecordingStorageAccess.isFatalWriteErrorMessage(error);
        boolean codecFatal = false;
        if (key != null) {
            CodecVideoRecorder codecRecorder = codecRecorders.get(key);
            codecFatal = codecRecorder != null && codecRecorder.hasFatalWriteError();
        }
        boolean terminal = fatalStorage || codecFatal || isTerminalRecorderError(error);
        if (!terminal) {
            return;
        }
        if (startupTracker.isTracking()) {
            RecordingStartupTracker.Outcome outcome =
                    startupTracker.recordCameraFailure(key, error, fatalStorage || codecFatal);
            if (outcome.shouldCancelWatchdog) {
                startupWatchdog.cancel();
            }
            if (outcome.allFailed) {
                boolean missingAllFiles = RecordingStorageAccess.isMissingAllFilesAccessOnAndroid11Plus(context);
                String userMessage = RecordingStorageAccess.formatUserStartupFailureMessage(error, missingAllFiles);
                failRecordingStartupWithUserMessage(
                        "Recording failure (" + mode + ", camera=" + cameraId + "): " + error,
                        userMessage);
                return;
            }
            if (outcome.partialFailure) {
                cleanupFailedCameraRecorder(key);
                if (recordingStatusCallback != null) {
                    final Set<String> activeCopy = new java.util.LinkedHashSet<>(outcome.writtenKeys);
                    final Set<String> failedCopy = new java.util.LinkedHashSet<>(outcome.failedKeys);
                    mainHandler.post(() -> recordingStatusCallback.onPartialRecordingStart(activeCopy, failedCopy));
                }
            }
            return;
        }
        if (fatalStorage) {
            boolean missingAllFiles = RecordingStorageAccess.isMissingAllFilesAccessOnAndroid11Plus(context);
            String userMessage = RecordingStorageAccess.formatUserStartupFailureMessage(error, missingAllFiles);
            failRecordingStartupWithUserMessage(
                    "Fatal storage write failure (" + mode + ", camera=" + cameraId + "): " + error,
                    userMessage);
        }
    }
    private Runnable sessionTimeoutRunnable = null;
    private final Object sessionLock = new Object();  // 用于同步 session 配置计数
    
    // 按摄像头维度跟踪配置状态（解决超时强制启动问题）
    private final Map<String, Boolean> cameraSessionReady = new LinkedHashMap<>();
    private final Map<String, Boolean> cameraRecordingActive = new LinkedHashMap<>();
    private RecordingStatusCallback recordingStatusCallback;

    public interface StatusCallback {
        void onCameraStatusUpdate(String cameraId, String status);
    }

    public interface PreviewSizeCallback {
        void onPreviewSizeChosen(String cameraKey, String cameraId, Size previewSize);
    }
    
    public interface SegmentSwitchCallback {
        void onSegmentSwitch(int newSegmentIndex);
    }

    /**
     * 损坏文件删除回调
     */
    public interface CorruptedFilesCallback {
        void onCorruptedFilesDeleted(List<String> deletedFiles);
    }

    /**
     * Codec 回退通知回调
     */
    public interface CodecFallbackCallback {
        void onCodecFallback();
    }
    
    /**
     * 录制状态回调（用于通知部分摄像头录制失败）
     */
    public interface RecordingStatusCallback {
        /**
         * 当部分摄像头启动录制成功，部分失败时调用
         * @param activeCameras 成功启动录制的摄像头 key 集合
         * @param failedCameras 启动录制失败的摄像头 key 集合
         */
        void onPartialRecordingStart(Set<String> activeCameras, Set<String> failedCameras);
    }

    /**
     * 首次数据写入回调
     * 用于通知外部录制已真正开始（有数据写入），可以开始计时
     */
    public interface FirstDataWrittenCallback {
        /**
         * 当任一摄像头首次成功写入数据时调用（只通知一次）
         */
        void onFirstDataWritten();
    }

    /**
     * 录制时间戳更新回调
     * 当 Watchdog 触发重建录制时，时间戳会改变，需要通知外部更新
     */
    public interface TimestampUpdateCallback {
        /**
         * 当录制时间戳更新时调用（通常在 Watchdog 重建后）
         * @param newTimestamp 新的录制时间戳
         */
        void onTimestampUpdated(String newTimestamp);
    }

    public MultiCameraManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 统一的分段时间戳提供者
     * 确保在短时间内（3秒）所有摄像头获取到相同的时间戳
     * 解决多路摄像头分段切换时因启动时机不同导致时间戳差1秒的问题
     */
    private final VideoRecorder.SegmentTimestampProvider segmentTimestampProvider = 
            new VideoRecorder.SegmentTimestampProvider() {
        @Override
        public String getSegmentTimestamp() {
            synchronized (timestampLock) {
                long now = System.currentTimeMillis();
                // 如果缓存的时间戳仍在有效期内，返回缓存值
                if (cachedSegmentTimestamp != null && 
                    (now - timestampGeneratedTime) < TIMESTAMP_CACHE_DURATION_MS) {
                    AppLog.d(TAG, "Using cached segment timestamp: " + cachedSegmentTimestamp + 
                            " (age: " + (now - timestampGeneratedTime) + "ms)");
                    return cachedSegmentTimestamp;
                }
                // 生成新的时间戳
                cachedSegmentTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(new Date());
                timestampGeneratedTime = now;
                AppLog.d(TAG, "Generated new segment timestamp: " + cachedSegmentTimestamp);
                return cachedSegmentTimestamp;
            }
        }
    };

    /**
     * 清除缓存的分段时间戳
     * 在开始新的录制时调用，确保使用新的时间戳
     */
    private void clearCachedSegmentTimestamp() {
        synchronized (timestampLock) {
            cachedSegmentTimestamp = null;
            timestampGeneratedTime = 0;
        }
    }
    
    private SegmentSwitchCallback segmentSwitchCallback;
    private CorruptedFilesCallback corruptedFilesCallback;
    private CodecFallbackCallback codecFallbackCallback;
    private FirstDataWrittenCallback firstDataWrittenCallback;
    private TimestampUpdateCallback timestampUpdateCallback;
    private boolean hasNotifiedFirstDataWritten = false;  // 是否已通知首次写入（每次录制只通知一次）

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    public void setPreviewSizeCallback(PreviewSizeCallback callback) {
        this.previewSizeCallback = callback;
    }
    
    public void setCorruptedFilesCallback(CorruptedFilesCallback callback) {
        this.corruptedFilesCallback = callback;
    }
    
    public void setRecordingStatusCallback(RecordingStatusCallback callback) {
        this.recordingStatusCallback = callback;
    }
    
    public void setSegmentSwitchCallback(SegmentSwitchCallback callback) {
        this.segmentSwitchCallback = callback;
    }

    /**
     * 设置临时分段时长覆盖值
     * 用于远程录制时禁用分段（设置为录制时长+余量）
     * @param durationMs 分段时长（毫秒），0表示使用配置值
     */
    public void setSegmentDurationOverride(long durationMs) {
        this.overrideSegmentDurationMs = durationMs;
        AppLog.d(TAG, "Segment duration override set to: " + (durationMs > 0 ? (durationMs / 1000) + " seconds" : "disabled"));
    }

    /**
     * 清除分段时长覆盖，恢复使用配置值
     */
    public void clearSegmentDurationOverride() {
        this.overrideSegmentDurationMs = 0;
        AppLog.d(TAG, "Segment duration override cleared, using config value");
    }

    public void setCodecFallbackCallback(CodecFallbackCallback callback) {
        this.codecFallbackCallback = callback;
    }

    public void setFirstDataWrittenCallback(FirstDataWrittenCallback callback) {
        this.firstDataWrittenCallback = callback;
    }

    public void setTimestampUpdateCallback(TimestampUpdateCallback callback) {
        this.timestampUpdateCallback = callback;
    }

    public void setMaxOpenCameras(int maxOpenCameras) {
        this.maxOpenCameras = Math.max(1, maxOpenCameras);
    }

    /**
     * 设置单一输出模式（用于不支持多路输出的车机平台，如 L6/L7）
     * 在此模式下，录制时只使用 MediaRecorder Surface，不使用 TextureView Surface
     * 这会导致录制期间预览冻结，但能确保录制正常工作
     * 
     * @param enabled true 表示启用单一输出模式
     * @deprecated 请使用 setCodecRecordingMode(true) 代替，它使用 OpenGL 渲染方案
     */
    @Deprecated
    public void setSingleOutputMode(boolean enabled) {
        AppLog.d(TAG, "Setting single output mode: " + (enabled ? "ENABLED" : "DISABLED"));
        for (SingleCamera camera : cameras.values()) {
            camera.setSingleOutputMode(enabled);
        }
    }

    /**
     * 设置软编码录制模式（用于 L6/L7 等不支持 MediaRecorder 直接录制的车机平台）
     * 在此模式下，使用 OpenGL 渲染 + MediaCodec 编码 + MediaMuxer 写入文件
     * 
     * 优点：
     * - 预览保持流畅，不会冻结
     * - 不依赖硬件对 MediaRecorder Surface 的支持
     * 
     * @param enabled true 表示启用软编码录制模式
     */
    public void setCodecRecordingMode(boolean enabled) {
        this.useCodecRecording = enabled;
        AppLog.d(TAG, "Codec recording mode: " + (enabled ? "ENABLED" : "DISABLED"));
    }

    /**
     * 检查是否使用软编码录制模式
     */
    public boolean isCodecRecordingMode() {
        return useCodecRecording;
    }

    /**
     * 获取指定位置的摄像头实例
     * @param position 位置（front/back/left/right）
     * @return SingleCamera实例，如果不存在则返回null
     */
    public SingleCamera getCamera(String position) {
        return cameras.get(position);
    }

    public void updatePreviewTextureViews(TextureView frontView,
                                          TextureView backView,
                                          TextureView leftView,
                                          TextureView rightView) {
        updatePreviewTextureView("front", frontView);
        updatePreviewTextureView("back", backView);
        updatePreviewTextureView("left", leftView);
        updatePreviewTextureView("right", rightView);
    }

    public void onPreviewTextureDestroyed(String cameraKey) {
        onPreviewTextureDestroyed(cameraKey, null);
    }

    public void onPreviewTextureDestroyed(String cameraKey, SurfaceTexture destroyedSurfaceTexture) {
        SingleCamera camera = cameras.get(cameraKey);
        if (camera == null) {
            return;
        }
        camera.onPreviewTextureDestroyed(destroyedSurfaceTexture);
    }

    public void setMainPreviewEnabled(boolean enabled) {
        previewDemand.setMainPreview(enabled);
        applyPreviewDemand();
    }

    public void setCameraPreviewVisible(String cameraKey, boolean visible) {
        previewDemand.setCameraVisible(cameraKey, visible);
        applyPreviewDemand(cameraKey);
    }

    public void setCameraPreviewVisibilities(Map<String, Boolean> visibilityMap) {
        previewDemand.setAllCameraVisibility(visibilityMap);
        applyPreviewDemand();
    }

    public boolean isCameraPreviewVisible(String cameraKey) {
        return previewDemand.isCameraVisible(cameraKey);
    }

    public void setPhotoSurfaceProvider(PhotoSurfaceProvider provider) {
        this.photoSurfaceProvider = provider;
    }

    /** T07: 仅在模式 A 激活时挂载四路 360 OES Surface。 */
    public void setSurroundPreviewSurfaces(Surface front, SurfaceTexture stFront,
                                           Surface back, SurfaceTexture stBack,
                                           Surface left, SurfaceTexture stLeft,
                                           Surface right, SurfaceTexture stRight) {
        setSingleSurroundSurface("front", front, stFront);
        setSingleSurroundSurface("back", back, stBack);
        setSingleSurroundSurface("left", left, stLeft);
        setSingleSurroundSurface("right", right, stRight);
    }

    /** 模式 A 原子挂载入口：合并关闭常规主预览与绑定 360 OES Surface，避免双次 session 重建抖动。 */
    public void bindSurroundPreviewForModeA(Surface front, SurfaceTexture stFront,
                                            Surface back, SurfaceTexture stBack,
                                            Surface left, SurfaceTexture stLeft,
                                            Surface right, SurfaceTexture stRight) {
        bindSingleSurroundSurfaceForModeA("front", front, stFront);
        bindSingleSurroundSurfaceForModeA("back", back, stBack);
        bindSingleSurroundSurfaceForModeA("left", left, stLeft);
        bindSingleSurroundSurfaceForModeA("right", right, stRight);
    }

    public void bindSingleSurroundSurfaceForModeA(String cameraKey, Surface surface, SurfaceTexture st) {
        previewDemand.setCameraVisible(cameraKey, false);
        SingleCamera camera = cameras.get(cameraKey);
        if (camera != null) {
            camera.setSurroundPreviewSurface(surface, st, true);
        }
    }

    /** T07: 退出模式 A 或退后台时立即解绑全部四路 360 OES Surface。 */
    public void clearSurroundPreviewSurfaces() {
        for (SingleCamera camera : cameras.values()) {
            camera.setSurroundPreviewSurface(null, null);
        }
    }

    /** 仅解绑仍匹配指定 Surface 引用的四路 360 OES 通道，避免误清主界面新建的预览。 */
    public void clearSurroundPreviewSurfacesIfMatching(Surface front,
                                                       Surface back,
                                                       Surface left,
                                                       Surface right) {
        clearSingleSurroundSurfaceIfMatching("front", front);
        clearSingleSurroundSurfaceIfMatching("back", back);
        clearSingleSurroundSurfaceIfMatching("left", left);
        clearSingleSurroundSurfaceIfMatching("right", right);
    }

    private void clearSingleSurroundSurfaceIfMatching(String cameraKey, Surface expectedSurface) {
        SingleCamera camera = cameras.get(cameraKey);
        if (camera != null) {
            camera.clearSurroundPreviewSurfaceIfMatching(expectedSurface);
        }
    }

    private void setSingleSurroundSurface(String cameraKey, Surface surface, SurfaceTexture st) {
        SingleCamera camera = cameras.get(cameraKey);
        if (camera != null) {
            camera.setSurroundPreviewSurface(surface, st);
        }
    }

    public Size getCameraPreviewSize(String cameraKey) {
        SingleCamera camera = cameras.get(cameraKey);
        return camera != null ? camera.getPreviewSize() : null;
    }

    public void firePreviewSizeCallback(String cameraKey) {
        if (previewSizeCallback == null || cameraKey == null) return;
        SingleCamera camera = cameras.get(cameraKey);
        if (camera == null) return;
        Size size = camera.getPreviewSize();
        if (size != null) {
            previewSizeCallback.onPreviewSizeChosen(cameraKey, camera.getCameraId(), size);
        }
    }

    private void applyPreviewDemand() {
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            entry.getValue().setMainPreviewEnabled(previewDemand.isRequired(entry.getKey()));
        }
    }

    private void applyPreviewDemand(String cameraKey) {
        if (cameraKey == null) return;
        SingleCamera camera = cameras.get(cameraKey);
        if (camera != null) {
            camera.setMainPreviewEnabled(previewDemand.isRequired(cameraKey));
        }
    }

    public void updatePreviewTextureView(String cameraKey, TextureView view) {
        SingleCamera camera = cameras.get(cameraKey);
        if (camera == null) {
            return;
        }
        boolean required = previewDemand.isRequired(cameraKey);
        camera.setTextureView(view);
        if (camera.isMainPreviewEnabled() != required) {
            camera.setMainPreviewEnabled(required);
            return;
        }
        camera.syncMainPreviewOutput();
    }

    public void attachPhotoTextureView(String cameraKey, TextureView stagingView) {
        SingleCamera camera = cameras.get(cameraKey);
        if (camera == null || stagingView == null) return;
        if (!savedPhotoTextureViews.containsKey(cameraKey)) {
            savedPhotoTextureViews.put(cameraKey, camera.getTextureView());
        }
        if (!stagedPhotoCameraKeys.contains(cameraKey)) {
            stagedPhotoCameraKeys.add(cameraKey);
        }
        camera.setTextureView(stagingView);
        if (previewDemand.isRequired(cameraKey) && stagingView.isAvailable()) {
            if (!camera.isConnected()) {
                camera.openCamera();
            } else {
                camera.syncMainPreviewOutput();
            }
        }
    }

    /**
     * 手动触发所有已有 previewSize 的摄像头的 PreviewSizeCallback。
     * 用于后台初始化（CameraManagerHolder）复用场景：
     * 摄像头在 BlindSpotService 中已打开并确定了预览尺寸，
     * 但 MainActivity 的回调（旋转变换等）此时尚未注册。
     * 在 MainActivity 注册回调后调用此方法，补偿缺失的回调触发。
     */
    public void firePreviewSizeCallbacks() {
        if (previewSizeCallback == null) return;
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            SingleCamera camera = entry.getValue();
            Size size = camera.getPreviewSize();
            if (size != null) {
                previewSizeCallback.onPreviewSizeChosen(entry.getKey(), camera.getCameraId(), size);
            }
        }
    }

    /**
     * 初始化摄像头
     * 支持 null 参数以适配不同数量的摄像头配置（1摄/2摄/4摄）
     */
    public void initCameras(String frontId, TextureView frontView,
                           String backId, TextureView backView,
                           String leftId, TextureView leftView,
                           String rightId, TextureView rightView) {

        // 清空之前的摄像头实例
        cameras.clear();
        
        // 根据参数创建摄像头实例（支持 null TextureView 用于后台初始化）
        if (frontId != null) {
            SingleCamera frontCamera = new SingleCamera(context, frontId, frontView);
            frontCamera.setCameraPosition("front");
            cameras.put("front", frontCamera);
            AppLog.d(TAG, "初始化前摄像头: ID=" + frontId);
        }

        if (backId != null) {
            SingleCamera backCamera = new SingleCamera(context, backId, backView);
            backCamera.setCameraPosition("back");
            cameras.put("back", backCamera);
            AppLog.d(TAG, "初始化后摄像头: ID=" + backId);
        }

        if (leftId != null) {
            SingleCamera leftCamera = new SingleCamera(context, leftId, leftView);
            leftCamera.setCameraPosition("left");
            cameras.put("left", leftCamera);
            AppLog.d(TAG, "初始化左摄像头: ID=" + leftId);
        }

        if (rightId != null) {
            SingleCamera rightCamera = new SingleCamera(context, rightId, rightView);
            rightCamera.setCameraPosition("right");
            cameras.put("right", rightCamera);
            AppLog.d(TAG, "初始化右摄像头: ID=" + rightId);
        }
        
        AppLog.d(TAG, "共初始化 " + cameras.size() + " 个摄像头");

        // 检测重复的cameraId，只让第一个实例成为主实例
        Set<String> primaryIds = new HashSet<>();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            SingleCamera camera = entry.getValue();
            camera.setMainPreviewEnabled(previewDemand.isRequired(entry.getKey()));
            String id = camera.getCameraId();
            
            if (primaryIds.add(id)) {
                // 第一次遇到这个ID，设为主实例
                camera.setPrimaryInstance(true);
                AppLog.d(TAG, "Camera " + id + " at position " + entry.getKey() + " set as PRIMARY");
            } else {
                // 重复的ID，设为从属实例
                camera.setPrimaryInstance(false);
                AppLog.d(TAG, "Camera " + id + " at position " + entry.getKey() + " set as SECONDARY (sharing with primary)");
            }
        }

        // 为每个摄像头设置回调
        CameraCallback callback = new CameraCallback() {
            @Override
            public void onCameraOpened(String cameraId) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " opened");
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, "已打开");
                }
            }

            @Override
            public void onCameraConfigured(String cameraId) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " configured");
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, "预览已启动");
                }

                // 检查是否有录制器正在等待会话重新配置（分段切换）或等待直连回退 GL 后启动
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        String key = entry.getKey();
                        VideoRecorder recorder = recorders.get(key);

                        if (recorder != null && recorder.isWaitingForSessionReconfiguration()) {
                            AppLog.d(TAG, "Camera " + cameraId + " session reconfigured, starting next segment recording");
                            recorder.clearWaitingForSessionReconfiguration();
                            recorder.startRecording();
                        }
                        if (pendingDirectFallbackStartKeys.remove(key)) {
                            final CodecVideoRecorder fallbackRec = codecRecorders.get(key);
                            if (fallbackRec != null && !fallbackRec.isRecording() && isRecording && !stoppingRecording) {
                                mainHandler.postDelayed(() -> {
                                    if (!isRecording || stoppingRecording || isReleased()) {
                                        return;
                                    }
                                    CodecVideoRecorder currentRec = codecRecorders.get(key);
                                    if (currentRec == fallbackRec && !currentRec.isRecording()) {
                                        boolean started = currentRec.startRecording();
                                        cameraRecordingActive.put(key, started || currentRec.isRecording());
                                        AppLog.i(TAG, "Started GL fallback codec recorder for " + key + ": " + started);
                                        if (!started && !currentRec.isRecording()) {
                                            handleDirectFallbackTerminalFailure(key, "gl_fallback_start_failed", false);
                                        }
                                    }
                                }, 150);
                            }
                        }
                        break;
                    }
                }

                // 检查是否所有会话都已配置完成（线程安全处理）
                synchronized (sessionLock) {
                    if (expectedSessionCount > 0) {
                        // 找到对应的摄像头 key 并标记为就绪
                        String cameraKey = null;
                        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                            if (entry.getValue().getCameraId().equals(cameraId)) {
                                cameraKey = entry.getKey();
                                break;
                            }
                        }
                        if (cameraKey != null) {
                            Boolean wasReady = cameraSessionReady.get(cameraKey);
                            if (wasReady != null && wasReady) {
                                AppLog.d(TAG, "Camera " + cameraKey + " (id=" + cameraId + ") session already marked ready, skipping count");
                            } else {
                                cameraSessionReady.put(cameraKey, true);
                                sessionConfiguredCount++;
                                AppLog.d(TAG, "Camera " + cameraKey + " (id=" + cameraId + ") session marked as ready");
                                AppLog.d(TAG, "Session configured: " + sessionConfiguredCount + "/" + expectedSessionCount);
                            }
                        }

                        if (sessionConfiguredCount >= expectedSessionCount) {
                            // 所有会话都已配置完成，执行待处理的录制启动
                            final Runnable recordingTask = pendingRecordingStart;
                            if (recordingTask != null) {
                                AppLog.d(TAG, "All sessions configured, starting recording...");
                                // 取消超时任务
                                if (sessionTimeoutRunnable != null) {
                                    mainHandler.removeCallbacks(sessionTimeoutRunnable);
                                    sessionTimeoutRunnable = null;
                                }
                                pendingRecordingStart = null;
                                sessionConfiguredCount = 0;
                                expectedSessionCount = 0;
                                // 延迟 300ms 再启动录制，让 Camera Session 稳定
                                // 某些车机设备需要这个延迟才能正确将帧发送到 MediaRecorder Surface
                                mainHandler.postDelayed(recordingTask, 300);
                            }
                        }
                    }
                }
            }

            @Override
            public void onCameraClosed(String cameraId) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " closed");
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, "已关闭");
                }
            }

            @Override
            public void onCameraError(String cameraId, int errorCode) {
                String errorMsg = getErrorMessage(errorCode);
                AppLog.e(TAG, "Callback: Camera " + cameraId + " error: " + errorCode + " - " + errorMsg);
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, "错误: " + errorMsg);
                }

                // 如果在等待会话配置期间发生错误，先检查是否触发直连->GL回退，否则减少期望计数
                boolean checkDirectFallback = false;
                boolean checkPostFallbackFailure = false;
                String errorCameraKey = null;
                synchronized (sessionLock) {
                    if (errorCode == -3) {
                        errorCameraKey = resolveRecordingKeyByCameraId(cameraId);
                        if (expectedSessionCount > 0) {
                            checkDirectFallback = true;
                        } else if (errorCameraKey != null && pendingDirectFallbackStartKeys.remove(errorCameraKey)) {
                            checkPostFallbackFailure = true;
                        }
                    }
                }
                if (checkPostFallbackFailure && errorCameraKey != null) {
                    handleDirectFallbackTerminalFailure(errorCameraKey, "gl_fallback_session_failed", false);
                    return;
                }
                if (checkDirectFallback && errorCameraKey != null
                        && triggerDirectToGlFallbackIfEligible(errorCameraKey, "session_configure_failed", true)) {
                    synchronized (sessionLock) {
                        if (expectedSessionCount > 0 && pendingRecordingStart != null) {
                            rescheduleSessionTimeoutLocked(3000L);
                        }
                    }
                    return;
                }

                synchronized (sessionLock) {
                    if (expectedSessionCount > 0 && errorCode == -3) {
                        expectedSessionCount--;
                        AppLog.d(TAG, "Session configuration failed, adjusted expected count: " + sessionConfiguredCount + "/" + expectedSessionCount);

                        // 检查是否所有剩余会话都已配置完成
                        if (sessionConfiguredCount >= expectedSessionCount && expectedSessionCount > 0) {
                            final Runnable recordingTask = pendingRecordingStart;
                            if (recordingTask != null) {
                                AppLog.d(TAG, "Remaining sessions configured, starting recording...");
                                // 取消超时任务
                                if (sessionTimeoutRunnable != null) {
                                    mainHandler.removeCallbacks(sessionTimeoutRunnable);
                                    sessionTimeoutRunnable = null;
                                }
                                pendingRecordingStart = null;
                                // 延迟 300ms 再启动录制，让 Camera Session 稳定
                                mainHandler.postDelayed(recordingTask, 300);
                            }
                            sessionConfiguredCount = 0;
                            expectedSessionCount = 0;
                        } else if (expectedSessionCount == 0) {
                            // 所有会话都失败了
                            AppLog.e(TAG, "All sessions failed to configure");
                            if (sessionTimeoutRunnable != null) {
                                mainHandler.removeCallbacks(sessionTimeoutRunnable);
                                sessionTimeoutRunnable = null;
                            }
                            sessionConfiguredCount = 0;
                            expectedSessionCount = 0;
                            pendingRecordingStart = null;
                        }
                    }
                }
            }

            @Override
            public void onPreviewSizeChosen(String cameraId, Size previewSize) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " preview size: " + previewSize);
                // 找到对应的 camera key
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        if (previewSizeCallback != null) {
                            previewSizeCallback.onPreviewSizeChosen(entry.getKey(), cameraId, previewSize);
                        }
                    }
                }
            }
        };

        // 为已初始化的摄像头设置回调
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            entry.getValue().setCallback(callback);
        }

        // 为已初始化的摄像头创建录制器实例
        recorders.clear();
        if (frontId != null && cameras.containsKey("front")) {
            VideoRecorder recorder = new VideoRecorder(frontId);
            recorder.setTimestampProvider(segmentTimestampProvider);  // 设置统一时间戳提供者
            recorders.put("front", recorder);
        }
        if (backId != null && cameras.containsKey("back")) {
            VideoRecorder recorder = new VideoRecorder(backId);
            recorder.setTimestampProvider(segmentTimestampProvider);  // 设置统一时间戳提供者
            recorders.put("back", recorder);
        }
        if (leftId != null && cameras.containsKey("left")) {
            VideoRecorder recorder = new VideoRecorder(leftId);
            recorder.setTimestampProvider(segmentTimestampProvider);  // 设置统一时间戳提供者
            recorders.put("left", recorder);
        }
        if (rightId != null && cameras.containsKey("right")) {
            VideoRecorder recorder = new VideoRecorder(rightId);
            recorder.setTimestampProvider(segmentTimestampProvider);  // 设置统一时间戳提供者
            recorders.put("right", recorder);
        }

        // 为每个录制器设置回调
        RecordCallback recordCallback = new RecordCallback() {
            @Override
            public void onRecordStart(String cameraId) {
                AppLog.d(TAG, "Recording started for camera " + cameraId);
            }

            @Override
            public void onRecordStop(String cameraId) {
                AppLog.d(TAG, "Recording stopped for camera " + cameraId);
            }

            @Override
            public void onRecordError(String cameraId, String error) {
                handleRecorderError("MediaRecorder", cameraId, error);
            }

            @Override
            public void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex) {
                AppLog.d(TAG, "Prepare segment switch for camera " + cameraId + " (current segment: " + currentSegmentIndex + ")");
                // 找到对应的 camera 并切换到仅预览模式
                // 使用优化的 switchToPreviewOnlyMode() 方法：预览继续流畅，只停止向录制 Surface 发送帧
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        SingleCamera camera = entry.getValue();
                        // 优先使用新的仅预览模式（保持预览不卡顿）
                        boolean success = camera.switchToPreviewOnlyMode();
                        AppLog.d(TAG, "Camera " + cameraId + " switched to preview-only mode: " + (success ? "success" : "fallback to pause"));
                        break;
                    }
                }
            }

            @Override
            public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {
                AppLog.d(TAG, "Segment switch for camera " + cameraId + " to segment " + newSegmentIndex);
                // 找到对应的 camera key 和 camera
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        String key = entry.getKey();
                        SingleCamera camera = entry.getValue();
                        VideoRecorder recorder = recorders.get(key);

                        if (camera != null && recorder != null) {
                            // 通知存储清理器记录写入速率并按需执行 O(1) 门控空间检查
                            if (newSegmentIndex > 0 && completedFilePath != null) {
                                StorageCleanupManager.notifySegmentCompleted(completedFilePath, currentSegmentDurationMs);
                            }
                            // 如果使用中转写入，将上一个分段的文件传输到最终目录
                            if (useRelayWrite && finalSaveDir != null && newSegmentIndex > 0 && completedFilePath != null) {
                                // 传输已完成的文件（由回调提供确切路径，避免传输正在录制的新文件）
                                scheduleRelayTransfer(completedFilePath);
                            }
                            
                            // 更新录制 Surface 并重新创建会话（MediaRecorder 模式）
                            camera.setRecordSurface(recorder.getSurface(), false);
                            camera.recreateSession();
                            AppLog.d(TAG, "Recreated session for camera " + cameraId + " after segment switch");
                        }
                        
                        // 通知分段切换回调（只通知一次，第一个触发的摄像头会通知）
                        if (segmentSwitchCallback != null && newSegmentIndex > lastNotifiedSegmentIndex) {
                            lastNotifiedSegmentIndex = newSegmentIndex;
                            segmentSwitchCallback.onSegmentSwitch(newSegmentIndex);
                        }
                        break;
                    }
                }
            }

            @Override
            public void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles) {
                if (deletedFiles != null && !deletedFiles.isEmpty()) {
                    AppLog.w(TAG, "Corrupted files deleted for camera " + cameraId + ": " + deletedFiles.size() + " file(s)");
                    for (String file : deletedFiles) {
                        AppLog.d(TAG, "  Deleted: " + file);
                    }
                    // 通知 MainActivity 显示弹窗
                    if (corruptedFilesCallback != null) {
                        mainHandler.post(() -> corruptedFilesCallback.onCorruptedFilesDeleted(deletedFiles));
                    }
                }
            }

            @Override
            public void onRecordingRebuildRequested(String cameraId, String reason) {
                AppLog.e(TAG, "Recording rebuild requested for camera " + cameraId + ", reason: " + reason);
                handleRecordingRebuildRequest(cameraId, reason);
            }

            @Override
            public void onFirstDataWritten(String cameraId) {
                AppLog.d(TAG, "First data written for camera " + cameraId);
                String key = resolveRecordingKeyByCameraId(cameraId);
                handleFirstDataWrittenForKey(key != null ? key : cameraId);
            }
        };

        // 为已创建的录制器设置回调
        for (Map.Entry<String, VideoRecorder> entry : recorders.entrySet()) {
            entry.getValue().setCallback(recordCallback);
        }

        AppLog.d(TAG, "Cameras initialized");
    }

    /**
     * 获取错误信息描述
     */
    private String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case 1: // ERROR_CAMERA_IN_USE
                return "摄像头正在被使用";
            case 2: // ERROR_MAX_CAMERAS_IN_USE
                return "已达到最大摄像头数量";
            case 3: // ERROR_CAMERA_DISABLED
                return "摄像头被禁用";
            case 4: // ERROR_CAMERA_DEVICE
                return "摄像头设备错误(资源不足?)";
            case 5: // ERROR_CAMERA_SERVICE
                return "摄像头服务错误";
            case -1:
                return "访问失败";
            case -2:
                return "权限不足";
            case -3:
                return "会话配置失败";
            case -4:
                return "摄像头断开连接(资源耗尽)";
            default:
                return "未知错误(" + errorCode + ")";
        }
    }

    /**
     * 打开所有摄像头
     */
    public void openAllCameras() {
        AppLog.d(TAG, "Opening all cameras...");
        repairSuppressed = false;

        activeCameraKeys.clear();
        int opened = 0;
        Set<String> openedIds = new HashSet<>();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            if (opened >= maxOpenCameras) {
                break;
            }
            SingleCamera camera = entry.getValue();
            String id = camera.getCameraId();
            if (!openedIds.add(id)) {
                continue;
            }
            activeCameraKeys.add(entry.getKey());
            if (previewDemand.isRequired(entry.getKey()) || camera.hasActiveOutputs()) {
                camera.openCamera();
            }
            opened++;
        }

        AppLog.d(TAG, "Requested open cameras: " + activeCameraKeys);
    }

    /**
     * 关闭所有摄像头（异步不阻塞调用线程）
     */
    public void closeAllCameras() {
        repairSuppressed = true;
        for (SingleCamera camera : cameras.values()) {
            camera.closeCamera();
        }
        AppLog.d(TAG, "All cameras closed (repair suppressed)");
    }

    /**
     * 并行关闭所有摄像头并等待释放完成（仅用于 release 收尾）
     */
    private void closeAllCamerasAndAwait(long totalTimeoutMs) {
        repairSuppressed = true;
        for (SingleCamera camera : cameras.values()) {
            camera.closeCamera();
        }
        long deadlineNs = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1L, totalTimeoutMs));
        for (SingleCamera camera : cameras.values()) {
            long remainingMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
            if (remainingMs > 0L) {
                camera.awaitPendingLifecycle(remainingMs);
            }
            camera.shutdownLifecycleExecutor(0L);
        }
        AppLog.d(TAG, "All cameras closed and awaited (repair suppressed)");
    }

    /**
     * 开始录制所有摄像头（自动生成时间戳）
     */
    public boolean startRecording() {
        // 生成统一的时间戳
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return startRecording(timestamp);
    }

    /**
     * 开始录制所有摄像头（使用指定的时间戳）
     * @param timestamp 统一的时间戳，用于所有摄像头的文件命名
     */
    public boolean startRecording(String timestamp) {
        if (isRecording || preparingRecording || stoppingRecording) {
            AppLog.w(TAG, "Recording is active, preparing, or still stopping");
            return false;
        }

        // 清除缓存的分段时间戳，开始新的录制周期
        clearCachedSegmentTimestamp();

        openRecordingCameras(null);

        // 根据模式选择录制方式
        if (useCodecRecording) {
            return startCodecRecording(timestamp, null);
        } else {
            return startMediaRecorderRecording(timestamp, null);
        }
    }

    /**
     * 开始录制指定的摄像头（使用指定的时间戳和摄像头列表）
     * @param timestamp 统一的时间戳，用于所有摄像头的文件命名
     * @param enabledCameras 要录制的摄像头位置集合（如 ["front", "back"]），为 null 时录制所有摄像头
     */
    public boolean startRecording(String timestamp, Set<String> enabledCameras) {
        if (isRecording || preparingRecording || stoppingRecording) {
            AppLog.w(TAG, "Recording is active, preparing, or still stopping");
            return false;
        }

        // 清除缓存的分段时间戳，开始新的录制周期
        clearCachedSegmentTimestamp();

        openRecordingCameras(enabledCameras);

        // 根据模式选择录制方式
        if (useCodecRecording) {
            return startCodecRecording(timestamp, enabledCameras);
        } else {
            return startMediaRecorderRecording(timestamp, enabledCameras);
        }
    }

    /** 打开本次任务所需的摄像头，包括后台已释放的前摄。 */
    private void openRecordingCameras(Set<String> enabledCameras) {
        repairSuppressed = false;
        for (String key : getActiveCameraKeys()) {
            if (enabledCameras == null || enabledCameras.contains(key)) {
                SingleCamera camera = cameras.get(key);
                if (camera != null && !camera.isConnected()) {
                    // openCamera 同步读取尺寸并清理旧 Surface；必须先打开，再准备新录制 Surface。
                    camera.openCamera();
                }
            }
        }
    }

    /** 使用 MediaRecorder 开始录制；null 摄像头集合表示全部摄像头。 */
    private boolean startMediaRecorderRecording(String timestamp, Set<String> enabledCameras) {
        AppLog.d(TAG, "Starting MediaRecorder recording with timestamp: " + timestamp);

        // 重置首次写入通知标志（每次录制只通知一次）
        hasNotifiedFirstDataWritten = false;
        startupFailureReported.set(false);
        lastStartupErrorMessage = null;

        // 记录当前录制参数（用于 Watchdog 重建）
        currentRecordingTimestamp = timestamp;
        currentEnabledCameras = enabledCameras;

        // 检查是否使用中转写入模式
        AppConfig appConfig = new AppConfig(context);
        useRelayWrite = appConfig.shouldUseRelayWrite();
        
        // 获取录制目录（可能是临时目录或最终目录）
        File saveDir = StorageHelper.getRecordingDir(context);
        
        // 如果不使用中转写入（直接写入U盘），检查U盘是否可用
        if (!useRelayWrite) {
            File sdCard = StorageHelper.getExternalSdCardRoot(context);
            if (sdCard == null || !sdCard.exists() || !sdCard.canWrite()) {
                AppLog.e(TAG, "U盘不可用，无法直接写入！U盘状态: exists=" + 
                        (sdCard != null ? sdCard.exists() : "null") + 
                        ", canWrite=" + (sdCard != null ? sdCard.canWrite() : "N/A"));
                // 提示用户并建议启用中转写入
                AppLog.w(TAG, "建议：在设置中启用「中转写入」功能，可以避免U盘问题导致的录制失败");
                // 不直接返回，因为可能只是检测问题，尝试继续录制
            } else {
                AppLog.i(TAG, "直接写入模式，U盘可用: " + sdCard.getAbsolutePath());
            }
        }
        
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }
        
        // 如果使用中转写入，记录最终目录
        if (useRelayWrite) {
            finalSaveDir = StorageHelper.getFinalVideoDir(context);
            if (!finalSaveDir.exists()) {
                finalSaveDir.mkdirs();
            }
            AppLog.d(TAG, "Relay write mode: recording to " + saveDir.getAbsolutePath() + 
                    ", will transfer to " + finalSaveDir.getAbsolutePath());
        } else {
            finalSaveDir = null;
        }

        List<String> allKeys = getActiveCameraKeys();
        if (allKeys.isEmpty()) {
            AppLog.e(TAG, "No active cameras for recording");
            return false;
        }

        // 如果指定了摄像头列表，过滤 keys
        final List<String> keys;
        if (enabledCameras != null && !enabledCameras.isEmpty()) {
            List<String> filteredKeys = new ArrayList<>();
            for (String key : allKeys) {
                if (enabledCameras.contains(key)) {
                    filteredKeys.add(key);
                }
            }
            keys = filteredKeys;
            AppLog.d(TAG, "Filtered recording cameras: " + keys);
        } else {
            keys = allKeys;
        }

        if (keys.isEmpty()) {
            AppLog.e(TAG, "No enabled cameras for recording after filtering");
            return false;
        }

        // 获取录制配置（使用上面已创建的 appConfig）
        // 如果有临时覆盖值（远程录制），使用覆盖值；否则使用配置值
        long segmentDurationMs = (overrideSegmentDurationMs > 0) 
                ? overrideSegmentDurationMs 
                : appConfig.getSegmentDurationMs();
        if (overrideSegmentDurationMs > 0) {
            AppLog.d(TAG, "Segment duration (override for remote recording): " + (segmentDurationMs / 1000) + " seconds");
        } else {
            AppLog.d(TAG, "Segment duration: " + (segmentDurationMs / 1000) + " seconds (" + appConfig.getSegmentDurationMinutes() + " minutes)");
        }
        
        // 获取帧率配置（根据帧率等级设置计算）
        int targetFrameRate = appConfig.getActualFrameRate(25);  // 使用25fps降低CPU占用
        AppLog.d(TAG, "Target frame rate: " + targetFrameRate + " fps (level: " + appConfig.getFramerateLevel() + ")");

        currentRecordingKeys = new ArrayList<>(keys);
        currentSegmentDurationMs = segmentDurationMs;
        startupTracker.reset(keys);

        // 第一步：准备所有 MediaRecorder（但不启动）
        // 使用每个摄像头的实际预览分辨率，而不是硬编码的值
        boolean prepareSuccess = true;
        startupInSyncPrepare = true;
        try {
            for (String key : keys) {
                SingleCamera camera = cameras.get(key);
                VideoRecorder recorder = recorders.get(key);
                if (camera == null || recorder == null) {
                    continue;
                }

                // 获取摄像头的实际预览分辨率
                Size previewSize = camera.getPreviewSize();
                if (previewSize == null) {
                    AppLog.e(TAG, "Camera " + key + " preview size not available, using fallback 1280x720");
                    previewSize = new Size(1280, 720);  // 回退到常见分辨率
                }

                // 计算码率（基于分辨率和帧率）
                int bitrate = appConfig.getActualBitrate(
                        previewSize.getWidth(),
                        previewSize.getHeight(),
                        targetFrameRate);

                // 设置录制参数
                recorder.setSegmentDuration(segmentDurationMs);
                recorder.setVideoBitrate(bitrate);
                recorder.setVideoFrameRate(targetFrameRate);
                // 注：最大编码分辨率限制使用 VideoRecorder 内部默认值（4096x4096）

                AppLog.d(TAG, "Recording params for " + key + ": " +
                        previewSize.getWidth() + "x" + previewSize.getHeight() +
                        " @ " + targetFrameRate + "fps, " + AppConfig.formatBitrate(bitrate));

                // 所有摄像头使用统一的时间戳：日期_时间_摄像头位置.mp4
                String path = new File(saveDir, timestamp + "_" + key + ".mp4").getAbsolutePath();
                // 只准备 MediaRecorder，获取 Surface，使用预览的实际分辨率
                AppLog.d(TAG, "Preparing recording for " + key + " with size: " + previewSize.getWidth() + "x" + previewSize.getHeight());
                if (!recorder.prepareRecording(path, previewSize.getWidth(), previewSize.getHeight())) {
                    prepareSuccess = false;
                    break;
                }
            }
        } finally {
            startupInSyncPrepare = false;
        }

        if (!prepareSuccess) {
            AppLog.e(TAG, "Failed to prepare recording");
            // 清理已准备的录制器
            for (String key : keys) {
                VideoRecorder recorder = recorders.get(key);
                if (recorder != null) {
                    recorder.release();
                }
            }
            return false;
        }

        // 第二步：将录制 Surface 添加到摄像头会话并重新创建会话
        synchronized (sessionLock) {
            sessionConfiguredCount = 0;
            expectedSessionCount = keys.size();
            // 初始化每个摄像头的配置状态跟踪
            cameraSessionReady.clear();
            cameraRecordingActive.clear();
        }

        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            VideoRecorder recorder = recorders.get(key);
            if (camera == null || recorder == null) {
                continue;
            }
            camera.setRecordSurface(recorder.getSurface(), false);  // MediaRecorder 模式
            camera.recreateSession();
        }

        // 第三步：设置待处理的录制启动任务（将被 executeRecordingStart 替代）
        final List<String> recordingKeys = new ArrayList<>(keys);
        pendingRecordingStart = () -> executeRecordingStart(recordingKeys, false);

        // 设置超时机制：如果 3 秒内没有所有会话配置完成，只启动已就绪的摄像头
        sessionTimeoutRunnable = () -> {
            AppLog.w(TAG, "Session configuration timeout after 3 seconds");
            synchronized (sessionLock) {
                // 标记未响应的摄像头为失败
                for (String key : recordingKeys) {
                    if (!cameraSessionReady.containsKey(key)) {
                        cameraSessionReady.put(key, false);
                        AppLog.w(TAG, "Camera " + key + " session not configured in time");
                    }
                }
                // 执行录制启动（仅已就绪的摄像头）
                executeRecordingStart(recordingKeys, true);
            }
        };
        mainHandler.postDelayed(sessionTimeoutRunnable, 3000);

        startupWatchdog.start();
        return true;
    }
    
    /**
     * 执行录制启动（仅启动已就绪的摄像头）
     * @param keys 要启动录制的摄像头 key 列表
     * @param fromTimeout 是否是从超时触发的
     */
    private void executeRecordingStart(List<String> keys, boolean fromTimeout) {
        executeRecordingStart(keys, fromTimeout, 0, false);
    }

    private void executeRecordingStart(List<String> keys, boolean fromTimeout, int stableAttempt, boolean forcedReopen) {
        if (!fromTimeout) {
            long now = System.currentTimeMillis();
            List<String> unstable = getUnstableCameras(keys, now);
            if (!unstable.isEmpty()) {
                if (stableAttempt < MAX_STABLE_WAIT_ATTEMPTS) {
                    AppLog.w(TAG, "Waiting for stable frames before recording, attempt " + (stableAttempt + 1) +
                            "/" + MAX_STABLE_WAIT_ATTEMPTS + ", unstable=" + unstable);
                    mainHandler.postDelayed(() -> executeRecordingStart(keys, false, stableAttempt + 1, forcedReopen), STABLE_WAIT_INTERVAL_MS);
                    return;
                }
                if (!forcedReopen) {
                    AppLog.w(TAG, "Frames still unstable after wait, forcing reopen all cameras once: " + unstable);
                    forceReopenAllCameras();
                    mainHandler.postDelayed(() -> executeRecordingStart(keys, false, 0, true), 500);
                    return;
                }
                AppLog.w(TAG, "Frames still unstable after force reopen, starting recording with stable subset: " + unstable);
                fromTimeout = true;
            }
        }

        Set<String> activeCameras = new HashSet<>();
        Set<String> failedCameras = new HashSet<>();
        
        AppLog.d(TAG, "Executing recording start for " + keys.size() + " cameras" + 
                (fromTimeout ? " (from timeout)" : ""));
        
        for (String key : keys) {
            // 检查摄像头会话是否已就绪
            Boolean ready = cameraSessionReady.get(key);
            if (ready == null || !ready) {
                // 会话未就绪
                failedCameras.add(key);
                AppLog.w(TAG, "Camera " + key + " session not ready, skipping");
                continue;
            }

            // 帧稳定性检查：如果是从超时触发的，检查帧稳定性
            // 但如果会话已经就绪，即使帧暂时不稳定也尝试启动录制（针对后台启动场景）
            if (fromTimeout && !isFrameStable(key, System.currentTimeMillis())) {
                AppLog.w(TAG, "Camera " + key + " frame not stable, but session is ready, will try to start anyway");
                // 不再跳过，而是继续尝试启动录制
            }
            
            VideoRecorder recorder = recorders.get(key);
            if (recorder != null) {
                if (recorder.startRecording()) {
                    cameraRecordingActive.put(key, true);
                    activeCameras.add(key);
                } else {
                    cameraRecordingActive.put(key, false);
                    failedCameras.add(key);
                    AppLog.e(TAG, "Failed to start recording for " + key);
                }
            } else {
                failedCameras.add(key);
            }
        }

        if (!failedCameras.isEmpty()) {
            for (String failedKey : failedCameras) {
                startupTracker.recordCameraFailure(failedKey, "mediarecorder_start_failed", false);
                if (!activeCameras.isEmpty()) {
                    cleanupFailedCameraRecorder(failedKey);
                }
            }
        }
        
        if (!activeCameras.isEmpty()) {
            isRecording = true;
            lastNotifiedSegmentIndex = -1;
            AppLog.d(TAG, activeCameras.size() + " camera(s) started recording successfully: " + activeCameras);
            
            // 如果有失败的摄像头，通知上层
            if (!failedCameras.isEmpty() && recordingStatusCallback != null) {
                AppLog.w(TAG, failedCameras.size() + " camera(s) failed to start: " + failedCameras);
                final Set<String> activeCopy = new java.util.LinkedHashSet<>(activeCameras);
                final Set<String> failedCopy = new java.util.LinkedHashSet<>(failedCameras);
                mainHandler.post(() -> recordingStatusCallback.onPartialRecordingStart(activeCopy, failedCopy));
            }
        } else {
            AppLog.e(TAG, "All cameras failed to start recording");
            startupWatchdog.cancel();
            startupTracker.reset(java.util.Collections.emptyList());
            isRecording = false;
            // 清理所有录制器
            for (String key : keys) {
                VideoRecorder recorder = recorders.get(key);
                if (recorder != null) {
                    recorder.reset();
                }
                SingleCamera camera = cameras.get(key);
                if (camera != null) {
                    camera.clearRecordSurface();
                    if (!camera.closeIfUnused()) {
                        camera.recreateSession();
                    }
                }
            }
            // 通知上层完全失败
            if (statusCallback != null) {
                statusCallback.onCameraStatusUpdate("all", "recording_failed");
            }
            // 同时通知 recordingStatusCallback（如果有设置）
            if (recordingStatusCallback != null) {
                final Set<String> activeCopy = new java.util.LinkedHashSet<>(activeCameras);
                final Set<String> failedCopy = new java.util.LinkedHashSet<>(failedCameras);
                mainHandler.post(() -> recordingStatusCallback.onPartialRecordingStart(activeCopy, failedCopy));
            }
        }
        
        // 清理状态
        pendingRecordingStart = null;
        sessionConfiguredCount = 0;
        expectedSessionCount = 0;
    }

    private boolean isFrameStable(String key, long nowMs) {
        SingleCamera camera = cameras.get(key);
        if (camera == null) {
            return false;
        }
        long last = camera.getLastFrameTimestampMs();
        return last > 0 && (nowMs - last) <= RECORDING_STABLE_FRAME_MAX_AGE_MS;
    }

    private List<String> getUnstableCameras(List<String> keys, long nowMs) {
        List<String> unstable = new ArrayList<>();
        for (String key : keys) {
            Boolean ready = cameraSessionReady.get(key);
            if (ready == null || !ready) {
                unstable.add(key);
                continue;
            }
            if (!isFrameStable(key, nowMs)) {
                unstable.add(key);
            }
        }
        return unstable;
    }

    /**
     * 使用软编码开始录制（L6/L7 模式）
     * 使用 OpenGL 渲染 + MediaCodec 编码 + MediaMuxer 写入
     * @param timestamp 时间戳
     * @param enabledCameras 要录制的摄像头位置集合，为 null 时录制所有摄像头
     */
    private boolean startCodecRecording(String timestamp, Set<String> enabledCameras) {
        AppLog.d(TAG, "Starting CODEC recording with timestamp: " + timestamp);

        // 重置首次写入通知标志（每次录制只通知一次）
        hasNotifiedFirstDataWritten = false;
        startupFailureReported.set(false);
        lastStartupErrorMessage = null;

        // 检查是否使用中转写入模式
        AppConfig appConfig = new AppConfig(context);
        useRelayWrite = appConfig.shouldUseRelayWrite();
        
        // 获取录制目录（可能是临时目录或最终目录）
        File saveDir = StorageHelper.getRecordingDir(context);
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }
        
        // 如果使用中转写入，记录最终目录
        if (useRelayWrite) {
            finalSaveDir = StorageHelper.getFinalVideoDir(context);
            if (!finalSaveDir.exists()) {
                finalSaveDir.mkdirs();
            }
            AppLog.d(TAG, "Codec relay write mode: recording to " + saveDir.getAbsolutePath() + 
                    ", will transfer to " + finalSaveDir.getAbsolutePath());
        } else {
            finalSaveDir = null;
        }

        List<String> allKeys = getActiveCameraKeys();
        if (allKeys.isEmpty()) {
            AppLog.e(TAG, "No active cameras for codec recording");
            return false;
        }

        // 如果指定了摄像头列表，过滤 keys
        final List<String> keys;
        if (enabledCameras != null && !enabledCameras.isEmpty()) {
            List<String> filteredKeys = new ArrayList<>();
            for (String key : allKeys) {
                if (enabledCameras.contains(key)) {
                    filteredKeys.add(key);
                }
            }
            keys = filteredKeys;
            AppLog.d(TAG, "Filtered codec recording cameras: " + keys);
        } else {
            keys = allKeys;
        }

        if (keys.isEmpty()) {
            AppLog.e(TAG, "No enabled cameras for codec recording after filtering");
            return false;
        }

        // 获取录制配置（使用上面已创建的 appConfig）
        // 如果有临时覆盖值（远程录制），使用覆盖值；否则使用配置值
        long segmentDurationMs = (overrideSegmentDurationMs > 0) 
                ? overrideSegmentDurationMs 
                : appConfig.getSegmentDurationMs();
        if (overrideSegmentDurationMs > 0) {
            AppLog.d(TAG, "Codec segment duration (override for remote recording): " + (segmentDurationMs / 1000) + " seconds");
        } else {
            AppLog.d(TAG, "Codec segment duration: " + (segmentDurationMs / 1000) + " seconds (" + appConfig.getSegmentDurationMinutes() + " minutes)");
        }
        
        // 获取帧率配置（根据帧率等级设置计算）
        int targetFrameRate = appConfig.getActualFrameRate(25);  // 使用25fps降低CPU占用
        AppLog.d(TAG, "Codec target frame rate: " + targetFrameRate + " fps (level: " + appConfig.getFramerateLevel() + ")");

        currentRecordingKeys = new ArrayList<>(keys);
        currentSegmentDurationMs = segmentDurationMs;
        List<String> expectedTrackerKeys = RecordingExperiment.compositeDaily(keys)
                ? java.util.Collections.singletonList("combined")
                : keys;
        startupTracker.reset(expectedTrackerKeys);

        cancelActivePrepareCoordinator("restartCodecRecording");
        // 异步清理之前的软编码录制器，避免阻塞主线程
        List<CodecVideoRecorder> staleRecorders = new ArrayList<>(codecRecorders.values());
        codecRecorders.clear();
        releaseCodecRecordersAsync(staleRecorders);

        if (RecordingExperiment.compositeDaily(keys)) {
            boolean prepareSuccess = true;
            startupInSyncPrepare = true;
            try {
                try {
                    Map<String,Size> sourceSizes = new LinkedHashMap<>();
                    for (String key : keys) {
                        SingleCamera camera = cameras.get(key);
                        if (camera == null || camera.getPreviewSize() == null) throw new IllegalStateException("Missing camera size: "+key);
                        sourceSizes.put(key,camera.getPreviewSize());
                    }
                    compositeRecording = CompositeRecording.prepare(sourceSizes,saveDir,timestamp,appConfig,
                            segmentDurationMs,compositeCallback(),segmentTimestampProvider);
                    registerCodecRecorder("combined",compositeRecording.recorder);
                    for (String key : keys) {
                        SingleCamera camera = cameras.get(key);
                        camera.setRecordingFrameRate(targetFrameRate);
                        camera.setRecordSurface(compositeRecording.surface(key),true);
                    }
                } catch (Exception e) {
                    AppLog.e(TAG,"Composite recording could not be prepared",e);
                    String detail = RecordingStorageAccess.describeThrowable(e);
                    if (!detail.isEmpty()) {
                        lastStartupErrorMessage = detail;
                    }
                    prepareSuccess=false;
                }
            } finally {
                startupInSyncPrepare = false;
            }

            if (!prepareSuccess) {
                closeCompositeInputs();
                for (String key : keys) {
                    SingleCamera camera = cameras.get(key);
                    if (camera != null) camera.clearRecordSurface();
                }
                AppLog.e(TAG, "Failed to prepare composite codec recording");
                List<CodecVideoRecorder> failedRecorders = new ArrayList<>(codecRecorders.values());
                codecRecorders.clear();
                releaseCodecRecordersAsync(failedRecorders);
                return false;
            }

            // 重新创建摄像头会话
            synchronized (sessionLock) {
                sessionConfiguredCount = 0;
                expectedSessionCount = keys.size();
                cameraSessionReady.clear();
                cameraRecordingActive.clear();
            }

            final List<String> recordingKeys = new ArrayList<>(keys);
            pendingRecordingStart = () -> executeCodecRecordingStart(recordingKeys, 0, false);
            for (String key : keys) {
                SingleCamera camera = cameras.get(key);
                if (camera != null) {
                    camera.recreateSession();
                }
            }

            // 设置超时机制
            sessionTimeoutRunnable = () -> {
                AppLog.w(TAG, "Session configuration timeout, starting codec recording with available cameras");
                synchronized (sessionLock) {
                    final Runnable recordingTask = pendingRecordingStart;
                    if (recordingTask != null) {
                        pendingRecordingStart = null;
                        recordingTask.run();
                    }
                    sessionConfiguredCount = 0;
                    expectedSessionCount = 0;
                }
            };
            mainHandler.postDelayed(sessionTimeoutRunnable, 3000);

            startupWatchdog.start();
            return true;
        }

        // 并发异步准备各路 CodecVideoRecorder，准备齐后再统一配置 Camera2 Session
        if (!launchAsyncCodecPrepare(keys, saveDir, timestamp, segmentDurationMs, targetFrameRate, false, false)) {
            return false;
        }

        startupWatchdog.start();
        return true;
    }

    private boolean launchAsyncCodecPrepare(
            final List<String> keys,
            final File saveDir,
            final String timestamp,
            final long segmentDurationMs,
            final int targetFrameRate,
            final boolean forcedReopen,
            final boolean fromReprepare) {
        if (!fromReprepare) {
            directFallbackGuard.reset();
            pendingDirectFallbackStartKeys.clear();
        }
        final AppConfig appConfig = new AppConfig(context);
        final Map<String, CodecVideoRecorder> plannedRecorders = new LinkedHashMap<>();
        final Map<String, String> targetPaths = new LinkedHashMap<>();

        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (camera == null) {
                continue;
            }
            CodecVideoRecorder codecRecorder = buildConfiguredCodecRecorder(
                    key, camera, appConfig, segmentDurationMs, targetFrameRate,
                    directFallbackGuard.isFallbackAttempted(key));

            String path = new File(saveDir, timestamp + "_" + key + ".mp4").getAbsolutePath();
            plannedRecorders.put(key, codecRecorder);
            targetPaths.put(key, path);
        }

        if (plannedRecorders.isEmpty()) {
            AppLog.e(TAG, "No valid cameras available for async codec prepare");
            return false;
        }

        final long generationId;
        final CodecPrepareCoordinator<CodecVideoRecorder, Surface> coordinator;
        synchronized (prepareLock) {
            generationId = prepareGeneration.incrementAndGet();
            preparingRecording = true;
            coordinator = new CodecPrepareCoordinator<>(
                    generationId,
                    plannedRecorders,
                    new CodecPrepareCoordinator.Hooks<CodecVideoRecorder, Surface>() {
                        @Override
                        public void requestStop(CodecVideoRecorder recorder) {
                            recorder.requestStop();
                        }

                        @Override
                        public void releaseRecorder(CodecVideoRecorder recorder) {
                            releaseCodecRecordersAsync(java.util.Collections.singletonList(recorder));
                        }

                        @Override
                        public void onBatchReady(CodecPrepareCoordinator.BatchResult<CodecVideoRecorder, Surface> result) {
                            Runnable applyOnMain = () -> handleAsyncCodecPrepareBatchReady(keys, result, forcedReopen, fromReprepare);
                            if (Looper.myLooper() == Looper.getMainLooper()) {
                                applyOnMain.run();
                            } else {
                                mainHandler.post(applyOnMain);
                            }
                        }
                    }
            );
            activePrepareCoordinator = coordinator;
        }

        for (Map.Entry<String, CodecVideoRecorder> entry : plannedRecorders.entrySet()) {
            final String key = entry.getKey();
            final CodecVideoRecorder codecRecorder = entry.getValue();
            final String path = targetPaths.get(key);
            AppLog.d(TAG, "Preparing codec recording asynchronously for " + key + " -> " + path);
            codecRecorder.prepareRecordingSurfaceAsync(path, (success, recordSurface, directPath, errorMessage) -> {
                String detail = errorMessage;
                if ((detail == null || detail.isEmpty()) && !success) {
                    detail = codecRecorder.getLastPrepareErrorMessage();
                }
                boolean fatal = !success && (codecRecorder.hasFatalWriteError()
                        || RecordingStorageAccess.isFatalWriteErrorMessage(detail));
                coordinator.onPrepareResult(key, success, recordSurface, detail, fatal);
            });
        }

        return true;
    }

    private void handleAsyncCodecPrepareBatchReady(
            List<String> plannedKeys,
            CodecPrepareCoordinator.BatchResult<CodecVideoRecorder, Surface> result,
            boolean forcedReopen,
            boolean fromReprepare) {
        boolean staleOrCancelled;
        synchronized (prepareLock) {
            staleOrCancelled = result.generationId != prepareGeneration.get()
                    || stoppingRecording
                    || isReleased()
                    || result.cancelled;
            if (!staleOrCancelled) {
                preparingRecording = false;
                activePrepareCoordinator = null;
            }
        }

        if (staleOrCancelled) {
            AppLog.w(TAG, "Discarding stale/cancelled async codec prepare result (gen=" + result.generationId + ")");
            if (!result.succeeded.isEmpty()) {
                List<CodecVideoRecorder> toRelease = new ArrayList<>(result.succeeded.size());
                for (CodecPrepareCoordinator.PreparedEntry<CodecVideoRecorder, Surface> entry : result.succeeded) {
                    if (entry.recorder != null) {
                        toRelease.add(entry.recorder);
                    }
                }
                releaseCodecRecordersAsync(toRelease);
            }
            return;
        }

        if (result.fatalStorageError || result.succeeded.isEmpty()) {
            for (String key : plannedKeys) {
                SingleCamera camera = cameras.get(key);
                if (camera != null) {
                    camera.clearRecordSurface();
                }
            }
            if (!result.succeeded.isEmpty()) {
                List<CodecVideoRecorder> toRelease = new ArrayList<>(result.succeeded.size());
                for (CodecPrepareCoordinator.PreparedEntry<CodecVideoRecorder, Surface> entry : result.succeeded) {
                    if (entry.recorder != null) {
                        toRelease.add(entry.recorder);
                    }
                }
                releaseCodecRecordersAsync(toRelease);
            }
            String detail = result.fatalErrorMessage;
            if (detail == null || detail.isEmpty()) {
                for (String err : result.failed.values()) {
                    if (err != null && !err.isEmpty()) {
                        detail = err;
                        break;
                    }
                }
            }
            if (detail == null || detail.isEmpty()) {
                detail = "Failed to prepare codec recording";
            }
            lastStartupErrorMessage = detail;
            boolean missingAllFiles = RecordingStorageAccess.isMissingAllFilesAccessOnAndroid11Plus(context);
            String userMessage = RecordingStorageAccess.formatUserStartupFailureMessage(detail, missingAllFiles);
            failRecordingStartupWithUserMessage(
                    (fromReprepare ? "Failed to re-prepare codec recording: " : "Failed to prepare codec recording: ") + detail,
                    userMessage);
            return;
        }

        if (!result.failed.isEmpty()) {
            for (Map.Entry<String, String> failedEntry : result.failed.entrySet()) {
                String failedKey = failedEntry.getKey();
                String failedReason = failedEntry.getValue();
                SingleCamera failedCamera = cameras.get(failedKey);
                if (failedCamera != null) {
                    failedCamera.clearRecordSurface();
                }
                AppLog.w(TAG, "Async codec prepare failed for camera " + failedKey + ": " + failedReason);
                startupTracker.recordCameraFailure(failedKey, failedReason, false);
            }
        }

        final List<String> readyKeys = new ArrayList<>(result.succeeded.size());
        for (CodecPrepareCoordinator.PreparedEntry<CodecVideoRecorder, Surface> entry : result.succeeded) {
            SingleCamera camera = cameras.get(entry.key);
            if (camera == null) {
                releaseCodecRecordersAsync(java.util.Collections.singletonList(entry.recorder));
                continue;
            }
            Surface recordSurface = entry.surfaceToken != null
                    ? entry.surfaceToken
                    : entry.recorder.getRecordSurface();
            if (recordSurface == null || !recordSurface.isValid()) {
                AppLog.w(TAG, "Prepared surface null or invalid for camera " + entry.key);
                startupTracker.recordCameraFailure(entry.key, "prepared_surface_null", false);
                releaseCodecRecordersAsync(java.util.Collections.singletonList(entry.recorder));
                continue;
            }
            camera.setRecordSurface(recordSurface, true);
            registerCodecRecorder(entry.key, entry.recorder);
            readyKeys.add(entry.key);
        }

        if (readyKeys.isEmpty()) {
            failRecordingStartupWithUserMessage(
                    "No prepared cameras left after async codec prepare",
                    "录像启动失败，请重试");
            return;
        }

        synchronized (sessionLock) {
            sessionConfiguredCount = 0;
            expectedSessionCount = readyKeys.size();
            cameraSessionReady.clear();
            cameraRecordingActive.clear();
            pendingRecordingStart = () -> executeCodecRecordingStart(readyKeys, 0, forcedReopen);
        }

        for (String key : readyKeys) {
            SingleCamera camera = cameras.get(key);
            if (camera != null) {
                camera.recreateSession();
            }
        }

        if (sessionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(sessionTimeoutRunnable);
        }
        sessionTimeoutRunnable = () -> {
            AppLog.w(TAG, "Session configuration timeout, starting codec recording with available cameras");
            synchronized (sessionLock) {
                final Runnable recordingTask = pendingRecordingStart;
                if (recordingTask != null) {
                    pendingRecordingStart = null;
                    recordingTask.run();
                }
                sessionConfiguredCount = 0;
                expectedSessionCount = 0;
            }
        };
        mainHandler.postDelayed(sessionTimeoutRunnable, 3000);
    }

    private void cancelActivePrepareCoordinator(String reason) {
        CodecPrepareCoordinator<CodecVideoRecorder, Surface> coordinatorToCancel;
        synchronized (prepareLock) {
            prepareGeneration.incrementAndGet();
            preparingRecording = false;
            coordinatorToCancel = activePrepareCoordinator;
            activePrepareCoordinator = null;
        }
        if (coordinatorToCancel != null) {
            AppLog.d(TAG, "Cancelling active codec prepare coordinator: " + reason);
            coordinatorToCancel.cancel();
        }
    }

    private void rescheduleSessionTimeoutLocked(long delayMs) {
        if (sessionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(sessionTimeoutRunnable);
            mainHandler.postDelayed(sessionTimeoutRunnable, delayMs);
        }
    }

    private boolean triggerDirectToGlFallbackIfEligible(
            String cameraKey,
            String reason,
            boolean duringInitialSessionWait) {
        if (cameraKey == null || cameraKey.isEmpty() || stoppingRecording || isReleased() || compositeRecording != null) {
            return false;
        }
        CodecVideoRecorder current = codecRecorders.get(cameraKey);
        SingleCamera camera = cameras.get(cameraKey);
        if (current == null || camera == null) {
            return false;
        }
        if (!directFallbackGuard.tryBeginFallback(cameraKey, current.isDirectInputActive(), current.hasFatalWriteError())) {
            return false;
        }

        AppLog.w(TAG, "Direct codec input failed for camera " + cameraKey + " (" + reason + "), falling back to GL pipeline");
        final long gen = prepareGeneration.get();
        final String oldPath = current.getCurrentFilePath();
        camera.pauseRecordSurfaceDelivery();
        codecRecorders.remove(cameraKey, current);
        releaseCodecRecordersAsync(java.util.Collections.singletonList(current));

        AppConfig appConfig = new AppConfig(context);
        long segMs = currentSegmentDurationMs > 0 ? currentSegmentDurationMs : appConfig.getSegmentDurationMs();
        int targetFps = appConfig.getActualFrameRate(25);
        CodecVideoRecorder fallbackRecorder = buildConfiguredCodecRecorder(
                cameraKey, camera, appConfig, segMs, targetFps, true);
        inFlightFallbackRecorders.put(cameraKey, fallbackRecorder);

        File saveDir = StorageHelper.getRecordingDir(context);
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }
        String fallbackPath = resolveDirectFallbackPath(saveDir, cameraKey, oldPath);
        fallbackRecorder.prepareRecordingSurfaceAsync(fallbackPath, mainHandler::post, (success, glSurface, directPath, errorMessage) -> {
            inFlightFallbackRecorders.remove(cameraKey, fallbackRecorder);
            directFallbackGuard.finishFallback(cameraKey);
            boolean stale = gen != prepareGeneration.get()
                    || stoppingRecording
                    || isReleased()
                    || (!isRecording && !duringInitialSessionWait && pendingRecordingStart == null);
            if (stale) {
                releaseCodecRecordersAsync(java.util.Collections.singletonList(fallbackRecorder));
                return;
            }
            if (!success || glSurface == null || !glSurface.isValid()) {
                String detail = (errorMessage != null && !errorMessage.isEmpty())
                        ? errorMessage
                        : fallbackRecorder.getLastPrepareErrorMessage();
                if (detail == null || detail.isEmpty()) {
                    detail = "Direct->GL fallback prepare failed";
                }
                AppLog.e(TAG, "Direct->GL fallback prepare failed for " + cameraKey + ": " + detail);
                releaseCodecRecordersAsync(java.util.Collections.singletonList(fallbackRecorder));
                handleDirectFallbackTerminalFailure(cameraKey, detail, duringInitialSessionWait);
                return;
            }

            registerCodecRecorder(cameraKey, fallbackRecorder);
            camera.setRecordSurface(glSurface, true);
            if (!duringInitialSessionWait) {
                pendingDirectFallbackStartKeys.add(cameraKey);
            }
            camera.recreateSession(true);
        });
        return true;
    }

    private String resolveDirectFallbackPath(File saveDir, String cameraKey, String oldPath) {
        long baseMs = System.currentTimeMillis();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        for (int offsetSec = 0; offsetSec < 10; offsetSec++) {
            String candidateTs = fmt.format(new Date(baseMs + offsetSec * 1000L));
            File candidate = new File(saveDir, candidateTs + "_" + cameraKey + ".mp4");
            File candidatePending = new File(candidate.getAbsolutePath() + ".pending");
            boolean sameAsOld = oldPath != null && candidate.getAbsolutePath().equals(oldPath);
            if (!sameAsOld && !candidate.exists() && !candidatePending.exists()
                    && !ActiveRecordingRegistry.isActive(candidatePending)) {
                return candidate.getAbsolutePath();
            }
        }
        return new File(saveDir, fmt.format(new Date(baseMs + 10_000L)) + "_" + cameraKey + ".mp4").getAbsolutePath();
    }

    private void handleDirectFallbackTerminalFailure(
            String cameraKey,
            String detail,
            boolean duringInitialSessionWait) {
        if (duringInitialSessionWait) {
            startupTracker.recordCameraFailure(cameraKey, detail, false);
            Runnable startReadyTask = null;
            boolean allFailed = false;
            synchronized (sessionLock) {
                if (expectedSessionCount > 0) {
                    expectedSessionCount--;
                    if (sessionConfiguredCount >= expectedSessionCount && expectedSessionCount > 0) {
                        startReadyTask = pendingRecordingStart;
                        pendingRecordingStart = null;
                        sessionConfiguredCount = 0;
                        expectedSessionCount = 0;
                    } else if (expectedSessionCount <= 0) {
                        sessionConfiguredCount = 0;
                        expectedSessionCount = 0;
                        pendingRecordingStart = null;
                        allFailed = true;
                    }
                }
            }
            if (startReadyTask != null) {
                if (sessionTimeoutRunnable != null) {
                    mainHandler.removeCallbacks(sessionTimeoutRunnable);
                    sessionTimeoutRunnable = null;
                }
                mainHandler.postDelayed(startReadyTask, 300);
            } else if (allFailed) {
                if (sessionTimeoutRunnable != null) {
                    mainHandler.removeCallbacks(sessionTimeoutRunnable);
                    sessionTimeoutRunnable = null;
                }
                boolean missingAllFiles = RecordingStorageAccess.isMissingAllFilesAccessOnAndroid11Plus(context);
                failRecordingStartupWithUserMessage(
                        "Direct->GL fallback failed for all cameras: " + detail,
                        RecordingStorageAccess.formatUserStartupFailureMessage(detail, missingAllFiles));
            }
            return;
        }

        if (startupTracker.isTracking()) {
            RecordingStartupTracker.Outcome outcome = startupTracker.recordCameraFailure(cameraKey, detail, false);
            if (outcome.shouldCancelWatchdog) {
                startupWatchdog.cancel();
            }
            if (outcome.allFailed) {
                boolean missingAllFiles = RecordingStorageAccess.isMissingAllFilesAccessOnAndroid11Plus(context);
                failRecordingStartupWithUserMessage(
                        "Direct->GL fallback failed on all cameras: " + detail,
                        RecordingStorageAccess.formatUserStartupFailureMessage(detail, missingAllFiles));
                return;
            }
            if (outcome.partialFailure) {
                cleanupFailedCameraRecorder(cameraKey);
                if (recordingStatusCallback != null) {
                    final Set<String> activeCopy = new java.util.LinkedHashSet<>(outcome.writtenKeys);
                    final Set<String> failedCopy = new java.util.LinkedHashSet<>(outcome.failedKeys);
                    mainHandler.post(() -> recordingStatusCallback.onPartialRecordingStart(activeCopy, failedCopy));
                }
            }
            return;
        }
        cleanupFailedCameraRecorder(cameraKey);
    }

    private void releaseCodecRecordersAsync(List<CodecVideoRecorder> recordersToRelease) {
        if (recordersToRelease == null || recordersToRelease.isEmpty()) {
            return;
        }
        for (CodecVideoRecorder recorder : recordersToRelease) {
            if (recorder != null) {
                recorder.requestStop();
            }
        }
        new Thread(() -> {
            for (CodecVideoRecorder recorder : recordersToRelease) {
                if (recorder != null) {
                    try {
                        recorder.release();
                    } catch (Exception e) {
                        AppLog.w(TAG, "Error releasing codec recorder asynchronously", e);
                    }
                }
            }
        }, "ReleaseCodecRecorders").start();
    }

    private RecordCallback createCodecRecordCallback(final String cameraKey) {
        return new RecordCallback() {
            @Override
            public void onRecordStart(String cameraId) {
                AppLog.d(TAG, "Codec recording started for camera " + cameraId);
            }

            @Override
            public void onRecordStop(String cameraId) {
                AppLog.d(TAG, "Codec recording stopped for camera " + cameraId);
            }

            @Override
            public void onRecordError(String cameraId, String error) {
                handleRecorderError("Codec", cameraId, error);
            }

            @Override
            public void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex) {
                AppLog.d(TAG, "Codec prepare segment switch for camera " + cameraId + " (current segment: " + currentSegmentIndex + ")");
            }

            @Override
            public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {
                AppLog.d(TAG, "Codec segment switch for camera " + cameraId + " to segment " + newSegmentIndex);

                if (newSegmentIndex > 0 && completedFilePath != null) {
                    StorageCleanupManager.notifySegmentCompleted(completedFilePath, currentSegmentDurationMs);
                }

                if (useRelayWrite && finalSaveDir != null && newSegmentIndex > 0 && completedFilePath != null) {
                    scheduleRelayTransfer(completedFilePath);
                }

                if (segmentSwitchCallback != null && newSegmentIndex > lastNotifiedSegmentIndex) {
                    lastNotifiedSegmentIndex = newSegmentIndex;
                    segmentSwitchCallback.onSegmentSwitch(newSegmentIndex);
                }
            }

            @Override
            public void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles) {
                if (deletedFiles != null && !deletedFiles.isEmpty()) {
                    AppLog.w(TAG, "Corrupted files deleted for codec camera " + cameraId + ": " + deletedFiles.size() + " file(s)");
                    for (String file : deletedFiles) {
                        AppLog.d(TAG, "  Deleted: " + file);
                    }
                    if (corruptedFilesCallback != null) {
                        mainHandler.post(() -> corruptedFilesCallback.onCorruptedFilesDeleted(deletedFiles));
                    }
                }
            }

            @Override
            public void onRecordingRebuildRequested(String cameraId, String reason) {
                AppLog.e(TAG, "Codec recording rebuild requested for camera " + cameraId + ", reason: " + reason);
                String targetKey = (cameraKey != null && !cameraKey.isEmpty())
                        ? cameraKey
                        : resolveRecordingKeyByCameraId(cameraId);
                if (targetKey != null && triggerDirectToGlFallbackIfEligible(targetKey, reason, false)) {
                    return;
                }
                if (targetKey != null && startupTracker.isTracking()) {
                    handleRecorderError("Codec", cameraId, "Direct recording rebuild failed: " + reason);
                }
            }

            @Override
            public void onFirstDataWritten(String cameraId) {
                AppLog.d(TAG, "Codec first data written for camera " + cameraId + " (key=" + cameraKey + ")");
                String resolvedKey = (cameraKey != null && !cameraKey.isEmpty())
                        ? cameraKey
                        : resolveRecordingKeyByCameraId(cameraId);
                handleFirstDataWrittenForKey(resolvedKey != null ? resolvedKey : cameraId);
            }
        };
    }

    private void executeCodecRecordingStart(List<String> keys, int stableAttempt, boolean forcedReopen) {
        AppLog.d(TAG, "Attempting to start codec recording...");
        if (startupFailureReported.get()) {
            AppLog.w(TAG, "Startup already aborted, skipping delayed executeCodecRecordingStart");
            return;
        }
        if (isRecording) {
            AppLog.w(TAG, "Codec recording already active, skipping duplicate start");
            synchronized (sessionLock) {
                pendingRecordingStart = null;
                sessionConfiguredCount = 0;
                expectedSessionCount = 0;
                cameraSessionReady.clear();
                cameraRecordingActive.clear();
            }
            if (sessionTimeoutRunnable != null) {
                mainHandler.removeCallbacks(sessionTimeoutRunnable);
                sessionTimeoutRunnable = null;
            }
            return;
        }

        long now = System.currentTimeMillis();
        List<String> unstable = getUnstableCameras(keys, now);
        if (!unstable.isEmpty()) {
            if (stableAttempt < MAX_STABLE_WAIT_ATTEMPTS) {
                AppLog.w(TAG, "Waiting for stable frames before codec recording, attempt " + (stableAttempt + 1) +
                        "/" + MAX_STABLE_WAIT_ATTEMPTS + ", unstable=" + unstable);
                mainHandler.postDelayed(() -> executeCodecRecordingStart(keys, stableAttempt + 1, forcedReopen), STABLE_WAIT_INTERVAL_MS);
                return;
            }
            if (!forcedReopen) {
                AppLog.w(TAG, "Codec frames still unstable after wait, forcing reopen all cameras once: " + unstable);
                forceReopenAllCameras();
                // 延迟等待摄像头重新打开和会话配置完成
                mainHandler.postDelayed(() -> {
                    // 检查哪些摄像头实际可用且会话已配置
                    List<String> availableKeys = new ArrayList<>();
                    for (String key : keys) {
                        SingleCamera camera = cameras.get(key);
                        Boolean ready = cameraSessionReady.get(key);
                        if (camera != null && camera.isCameraOpened() && ready != null && ready) {
                            availableKeys.add(key);
                        }
                    }
                    AppLog.d(TAG, "After force reopen, available cameras with ready session: " + availableKeys);
                    if (!availableKeys.isEmpty()) {
                        executeCodecRecordingStart(availableKeys, 0, true);
                    } else {
                        // 没有摄像头会话就绪，尝试只为实际打开的摄像头重新准备录制
                        AppLog.w(TAG, "No camera sessions ready after force reopen, re-preparing for available cameras only");
                        // 找出实际打开的摄像头
                        List<String> openedCameras = new ArrayList<>();
                        for (String key : keys) {
                            SingleCamera camera = cameras.get(key);
                            if (camera != null && camera.isCameraOpened()) {
                                openedCameras.add(key);
                            }
                        }
                        AppLog.d(TAG, "Opened cameras: " + openedCameras);
                        if (!openedCameras.isEmpty()) {
                            // 重新准备只为这些摄像头
                            reprepareCodecRecordingForCameras(openedCameras);
                        } else {
                            clearSessionPendingState();
                            failRecordingStartupWithUserMessage(
                                    "No cameras opened after force reopen",
                                    "录像启动失败，未检测到可用摄像头，请重试");
                        }
                    }
                }, 2000);  // 增加等待时间到2秒，确保会话配置完成
                return;
            }
            AppLog.w(TAG, "Codec frames still unstable after force reopen, will try to start anyway: " + unstable);
            // 强制重新打开后仍然不稳定，直接尝试启动录制（不再跳过）
            unstable.clear();
        }

        boolean anyActive = false;
        int activeCount = 0;
        Set<String> activeCameras = new java.util.LinkedHashSet<>();
        Set<String> failedCameras = new java.util.LinkedHashSet<>();
        List<String> expectedRecordingKeys = currentRecordingKeys;
        if (expectedRecordingKeys != null) {
            for (String expectedKey : expectedRecordingKeys) {
                if (!keys.contains(expectedKey)) {
                    failedCameras.add(expectedKey);
                }
            }
        }

        AppLog.d(TAG, "executeCodecRecordingStart: keys=" + keys + ", codecRecorders=" + codecRecorders.keySet() + ", cameraSessionReady=" + cameraSessionReady);

        for (String key : keys) {
            Boolean ready = cameraSessionReady.get(key);
            AppLog.d(TAG, "Checking camera " + key + ": ready=" + ready + ", codecRecorder=" + codecRecorders.get(key) + ", forcedReopen=" + forcedReopen);
            // 强制重新打开后，跳过 session ready 检查，直接尝试启动录制
            if (!forcedReopen && (ready == null || !ready)) {
                AppLog.w(TAG, "Camera " + key + " session not ready, skipping");
                failedCameras.add(key);
                continue;
            }
            // 帧稳定性检查：如果已经尝试了最大次数并强制重新打开过，就不再检查帧稳定性
            // 这样可以确保在后台启动录制时，即使帧暂时不稳定也能开始录制
            if (!unstable.isEmpty() && !forcedReopen && !isFrameStable(key, System.currentTimeMillis())) {
                AppLog.w(TAG, "Camera " + key + " frame not stable, skipping (forcedReopen=" + forcedReopen + ")");
                failedCameras.add(key);
                continue;
            }
            CodecVideoRecorder codecRecorder = codecRecorders.get(compositeRecording == null ? key : "combined");
            if (codecRecorder == null) {
                AppLog.e(TAG, "Camera " + key + " codecRecorder is null");
                failedCameras.add(key);
                continue;
            }
            if (codecRecorder.isRecording()) {
                anyActive = true;
                activeCount++;
                activeCameras.add(key);
                cameraRecordingActive.put(key, true);
                continue;
            }
            AppLog.d(TAG, "Starting codec recording for camera " + key);
            boolean started = codecRecorder.startRecording();
            AppLog.d(TAG, "Camera " + key + " startRecording returned: " + started + ", isRecording=" + codecRecorder.isRecording());
            if (started || codecRecorder.isRecording()) {
                anyActive = true;
                activeCount++;
                activeCameras.add(key);
                cameraRecordingActive.put(key, true);
                AppLog.d(TAG, "Camera " + key + " codec recording started successfully");
            } else {
                AppLog.e(TAG, "Failed to start codec recording for " + key);
                cameraRecordingActive.put(key, false);
                failedCameras.add(key);
            }
        }

        if (!failedCameras.isEmpty() && compositeRecording == null) {
            for (String failedKey : failedCameras) {
                startupTracker.recordCameraFailure(failedKey, "codec_start_failed", false);
                if (anyActive) {
                    cleanupFailedCameraRecorder(failedKey);
                }
            }
        }

        if (anyActive && !activeCameras.isEmpty()) {
            lastNotifiedSegmentIndex = -1;
            isRecording = true;
            AppLog.d(TAG, activeCount + " camera(s) started codec recording successfully: " + activeCameras);
            if (!failedCameras.isEmpty()) {
                AppLog.w(TAG, failedCameras.size() + " camera(s) failed to start codec recording: " + failedCameras);
                if (recordingStatusCallback != null) {
                    final Set<String> activeCopy = new java.util.LinkedHashSet<>(activeCameras);
                    final Set<String> failedCopy = new java.util.LinkedHashSet<>(failedCameras);
                    mainHandler.post(() -> recordingStatusCallback.onPartialRecordingStart(activeCopy, failedCopy));
                }
            }
        } else {
            failRecordingStartupWithUserMessage(
                    "Failed to start codec recording on all cameras",
                    "录像启动失败，请重试");
        }

        clearSessionPendingState();
    }

    private void clearSessionPendingState() {
        synchronized (sessionLock) {
            pendingRecordingStart = null;
            sessionConfiguredCount = 0;
            expectedSessionCount = 0;
            cameraSessionReady.clear();
            cameraRecordingActive.clear();
        }
        if (sessionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(sessionTimeoutRunnable);
            sessionTimeoutRunnable = null;
        }
    }

    /**
     * 重新准备 Codec 录制（只为指定的摄像头）
     * 用于强制重新打开后，只为实际可用的摄像头准备录制
     */
    private void reprepareCodecRecordingForCameras(List<String> cameraKeys) {
        if (compositeRecording != null) {
            synchronized (sessionLock) {
                sessionConfiguredCount=0; expectedSessionCount=cameraKeys.size(); cameraSessionReady.clear();
                pendingRecordingStart=() -> executeCodecRecordingStart(cameraKeys,0,true);
            }
            for (String key : cameraKeys) {
                SingleCamera camera=cameras.get(key);
                if (camera!=null) { camera.setRecordSurface(compositeRecording.surface(key),true); camera.recreateSession(); }
            }
            return;
        }
        AppLog.d(TAG, "Re-preparing codec recording for cameras: " + cameraKeys);

        List<String> expectedBeforeReopen = currentRecordingKeys;
        if (expectedBeforeReopen != null) {
            for (String expectedKey : expectedBeforeReopen) {
                if (!cameraKeys.contains(expectedKey)) {
                    startupTracker.recordCameraFailure(expectedKey, "codec_reopen_unavailable", false);
                    SingleCamera droppedCam = cameras.get(expectedKey);
                    if (droppedCam != null) {
                        droppedCam.clearRecordSurface();
                    }
                }
            }
        }

        cancelActivePrepareCoordinator("reprepareCodecRecording");
        // 清理之前的录制器（异步释放，防止新旧实例残留）
        List<CodecVideoRecorder> staleRecorders = new ArrayList<>(codecRecorders.values());
        codecRecorders.clear();
        releaseCodecRecordersAsync(staleRecorders);

        // 获取录制目录
        File saveDir = StorageHelper.getRecordingDir(context);
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }

        // 生成新的时间戳
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());

        AppConfig appConfig = new AppConfig(context);
        int fps = appConfig.getActualFrameRate(25);
        long segDurationMs = currentSegmentDurationMs > 0 ? currentSegmentDurationMs : appConfig.getSegmentDurationMs();

        if (!launchAsyncCodecPrepare(cameraKeys, saveDir, timestamp, segDurationMs, fps, true, true)) {
            isRecording = false;
            failRecordingStartupWithUserMessage(
                    "Failed to re-prepare codec recording",
                    "录像启动失败，请重试");
        }
    }

    /**
     * 停止录制所有摄像头
     */
    public void stopRecording() {
        stopRecording(false);
    }

    /**
     * 停止录制所有摄像头（异步执行，避免阻塞主线程）
     * @param skipRelayTransfer 是否跳过自动传输（用于远程录制，上传完成后再传输）
     */
    public void stopRecording(boolean skipRelayTransfer) {
        stopRecording(skipRelayTransfer, null);
    }

    public void stopRecording(boolean skipRelayTransfer, Runnable onStopped) {
        cancelActivePrepareCoordinator("stopRecording");
        startupWatchdog.cancel();
        startupTracker.reset(java.util.Collections.emptyList());
        currentRecordingKeys = java.util.Collections.emptyList();
        synchronized (this) {
            if (onStopped != null) recordingStoppedCallbacks.add(onStopped);
            if (stoppingRecording) return;
            stoppingRecording = true;
        }
        final boolean savedRelayWrite = useRelayWrite;
        final File savedFinalDir = finalSaveDir;
        final List<String> stoppingKeys = getActiveCameraKeys();
        AppLog.d(TAG, "stopRecording called, isRecording=" + isRecording + ", useCodecRecording=" + useCodecRecording + ", skipRelayTransfer=" + skipRelayTransfer);

        // 立即标记停止状态，防止新的录制请求
        final boolean wasRecording = isRecording;
        isRecording = false;
        pendingDirectFallbackStartKeys.clear();
        directFallbackGuard.reset();
        if (!inFlightFallbackRecorders.isEmpty()) {
            List<CodecVideoRecorder> inFlight = new ArrayList<>(inFlightFallbackRecorders.values());
            inFlightFallbackRecorders.clear();
            releaseCodecRecordersAsync(inFlight);
        }
        for (Map.Entry<String, CodecVideoRecorder> entry : codecRecorders.entrySet()) {
            CodecVideoRecorder recorder = entry.getValue();
            if (recorder == null) {
                continue;
            }
            recorder.requestStop();
            if (recorder.isDirectInputActive()) {
                SingleCamera camera = cameras.get(entry.getKey());
                if (camera != null) {
                    camera.pauseRecordSurfaceDelivery();
                }
            }
        }

        // 在后台线程执行停止操作，避免阻塞主线程
        new Thread(() -> {
            try {
                CompositeRecording composite = compositeRecording;
                if (composite != null) composite.pause();
                // 清理待处理的录制启动任务和会话计数器（线程安全处理）
                synchronized (sessionLock) {
                    if (pendingRecordingStart != null) {
                        AppLog.d(TAG, "Cancelling pending recording start");
                        pendingRecordingStart = null;
                    }

                    // 重置会话计数器
                    sessionConfiguredCount = 0;
                    expectedSessionCount = 0;
                }

                // 清理超时任务
                if (sessionTimeoutRunnable != null) {
                    mainHandler.removeCallbacks(sessionTimeoutRunnable);
                    sessionTimeoutRunnable = null;
                }

                List<String> keys = getActiveCameraKeys();

                if (!wasRecording) {
                    AppLog.w(TAG, "Not recording, but cleaning up anyway");
                    // 即使不在录制状态，也尝试清理录制器
                    for (String key : keys) {
                        try {
                            VideoRecorder recorder = recorders.get(key);
                            if (recorder != null) {
                                recorder.release();
                            }
                        } catch (Exception e) {
                            AppLog.e(TAG, "Error releasing recorder for " + key, e);
                        }
                        try {
                            CodecVideoRecorder codecRecorder = codecRecorders.get(key);
                            if (codecRecorder != null) {
                                codecRecorder.release();
                            }
                        } catch (Exception e) {
                            AppLog.e(TAG, "Error releasing codec recorder for " + key, e);
                        }
                    }
                    for (CodecVideoRecorder recorder : codecRecorders.values()) recorder.release();
                    codecRecorders.clear();
                    return;
                }

                // 停止软编码录制（带超时保护）
                if (!codecRecorders.isEmpty()) {
                    AppLog.d(TAG, "Stopping codec recorders...");
                    for (String key : keys) {
                        try {
                            CodecVideoRecorder codecRecorder = codecRecorders.get(key);
                            if (codecRecorder != null && codecRecorder.isRecording()) {
                                codecRecorder.stopRecording();
                            }
                        } catch (Exception e) {
                            AppLog.e(TAG, "Error stopping codec recorder for " + key, e);
                        }
                    }
                    // 释放软编码录制器
                    for (CodecVideoRecorder recorder : new ArrayList<>(codecRecorders.values())) {
                        try {
                            recorder.release();
                        } catch (Exception e) {
                            AppLog.e(TAG, "Error releasing codec recorder", e);
                        }
                    }
                    codecRecorders.clear();
                }

                // 停止 MediaRecorder 录制（带超时保护）
                for (String key : keys) {
                    try {
                        VideoRecorder recorder = recorders.get(key);
                        if (recorder != null && recorder.isRecording()) {
                            recorder.stopRecording();
                        }
                    } catch (Exception e) {
                        AppLog.e(TAG, "Error stopping recorder for " + key, e);
                    }
                }

                // 如果使用中转写入，将临时目录中的所有文件传输到最终目录
                if (savedRelayWrite && savedFinalDir != null && !skipRelayTransfer) {
                    AppLog.d(TAG, "Scheduling relay transfer for remaining files...");
                    
                    File tempDir = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
                    final File[] filesToTransfer;
                    if (tempDir.exists()) {
                        filesToTransfer = tempDir.listFiles((dir, name) -> name.endsWith(".mp4"));
                    } else {
                        filesToTransfer = null;
                    }
                    
                    mainHandler.postDelayed(() -> {
                        transferSpecificTempFiles(savedFinalDir, filesToTransfer);
                    }, 500);
                }

                useRelayWrite = false;
                AppLog.d(TAG, "stopRecording completed successfully");
            } catch (Exception e) {
                AppLog.e(TAG, "Error in stopRecording", e);
            } finally {
                closeCompositeInputs();
                mainHandler.post(() -> finishStoppingRecording(stoppingKeys));
            }
        }, "StopRecording-" + System.currentTimeMillis()).start();
        finalSaveDir = null;
        
        // 清理 Watchdog 回退状态
        currentRecordingTimestamp = null;
        currentEnabledCameras = null;
        rebuildAttemptCount = 0;
        isRebuildingRecording = false;  // 重置重建标志
        
        AppLog.d(TAG, "All cameras stopped recording");
    }

    private void finishStoppingRecording(List<String> keys) {
        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (camera == null) continue;
            try {
                camera.clearRecordSurface();
                if (!camera.closeIfUnused()) camera.recreateSession();
            } catch (Exception e) {
                AppLog.w(TAG, "Error clearing stopped recording output", e);
            }
        }
        closeCompositeInputs();
        List<Runnable> callbacks;
        synchronized (this) {
            stoppingRecording = false;
            callbacks = new ArrayList<>(recordingStoppedCallbacks);
            recordingStoppedCallbacks.clear();
        }
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                AppLog.e(TAG, "Recording stop callback failed", e);
            }
        }
    }

    /**
     * 处理录制重建请求（Watchdog 触发）
     * 
     * 重建策略：
     * 1. 第一次触发：尝试重建 MediaRecorder（不切换模式）
     * 2. 第二次触发：如果录制模式为"自动"，则切换到 Codec 模式
     * 3. 已在 Codec 模式或非自动模式：不再处理
     * 
     * 注意：多个摄像头可能同时触发此方法，需要防重入保护
     * 
     * @param cameraId 触发重建的相机ID
     * @param reason 重建原因
     */
    private void handleRecordingRebuildRequest(String cameraId, String reason) {
        // 【关键】防重入保护：多个摄像头可能同时触发 Watchdog
        // 只处理第一个触发的请求，忽略后续的
        synchronized (this) {
            if (isRebuildingRecording) {
                AppLog.w(TAG, "Recording rebuild already in progress, ignoring request from camera " + cameraId);
                return;
            }
            isRebuildingRecording = true;
        }
        
        rebuildAttemptCount++;
        AppLog.w(TAG, "Handling recording rebuild request from camera " + cameraId + 
                ", reason: " + reason + ", attempt: " + rebuildAttemptCount);
        
        // 如果已经在 Codec 模式，则不再处理
        if (useCodecRecording) {
            AppLog.w(TAG, "Already using Codec recording, no further fallback available");
            isRebuildingRecording = false;
            return;
        }
        
        // 保存当前录制参数
        final String savedTimestamp = currentRecordingTimestamp;
        final Set<String> savedEnabledCameras = currentEnabledCameras;
        
        if (savedTimestamp == null) {
            AppLog.w(TAG, "No recording timestamp saved, cannot rebuild");
            isRebuildingRecording = false;
            return;
        }
        
        // 停止当前录制（不清理状态）
        stopRecordingForRebuild();
        
        // 注意：不自动清除调试标志，让用户通过 UI 手动控制
        // 调试模式作为持久开关，直到用户手动关闭
        
        // 检查是否需要回退到 Codec
        if (rebuildAttemptCount >= CODEC_FALLBACK_THRESHOLD) {
            // 达到阈值，检查是否可以回退到 Codec
            AppConfig appConfig = new AppConfig(context);
            String recordingMode = appConfig.getRecordingMode();
            
            if (AppConfig.RECORDING_MODE_AUTO.equals(recordingMode)) {
                // 自动模式：切换到 Codec 录制
                AppLog.w(TAG, "Rebuild attempt " + rebuildAttemptCount + " failed, switching to Codec mode...");
                
                mainHandler.postDelayed(() -> {
                    try {
                        // 生成新的时间戳（避免文件名冲突）
                        String newTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                        
                        AppLog.d(TAG, "Restarting recording with Codec mode, new timestamp: " + newTimestamp);
                        useCodecRecording = true;  // 切换到 Codec 模式
                        if (!startCodecRecording(newTimestamp, savedEnabledCameras)) {
                            boolean missingAllFiles = RecordingStorageAccess.isMissingAllFilesAccessOnAndroid11Plus(context);
                            failRecordingStartupWithUserMessage(
                                    "Codec fallback prepare failed: " + lastStartupErrorMessage,
                                    RecordingStorageAccess.formatUserStartupFailureMessage(lastStartupErrorMessage, missingAllFiles));
                            return;
                        }
                        
                        // 通知外部时间戳已更新（用于远程录制查找文件）
                        if (timestampUpdateCallback != null) {
                            timestampUpdateCallback.onTimestampUpdated(newTimestamp);
                        }
                        
                        // 通知外部发生了 Codec 回退
                        if (codecFallbackCallback != null) {
                            codecFallbackCallback.onCodecFallback();
                        }
                    } finally {
                        isRebuildingRecording = false;  // 重建完成
                    }
                }, 500);
            } else {
                if (rebuildAttemptCount > MAX_NON_AUTO_REBUILD_ATTEMPTS) {
                    AppLog.e(TAG, "Rebuild attempt " + rebuildAttemptCount + " exceeded limit in mode=" + recordingMode);
                    failRecordingStartupWithUserMessage(
                            "MediaRecorder rebuild exhausted (" + rebuildAttemptCount + ") in mode=" + recordingMode,
                            "录像启动失败，请切换录像模式或在设置中保存日志");
                    isRebuildingRecording = false;
                    return;
                }
                // 非自动模式，只能有限次重试 MediaRecorder
                AppLog.w(TAG, "Recording mode is '" + recordingMode + "' (not auto), retrying MediaRecorder ("
                        + rebuildAttemptCount + "/" + MAX_NON_AUTO_REBUILD_ATTEMPTS + ")...");
                
                mainHandler.postDelayed(() -> {
                    try {
                        String newTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                        AppLog.d(TAG, "Retrying MediaRecorder recording, new timestamp: " + newTimestamp);
                        if (!startMediaRecorderRecording(newTimestamp, savedEnabledCameras)) {
                            boolean missingAllFiles = RecordingStorageAccess.isMissingAllFilesAccessOnAndroid11Plus(context);
                            failRecordingStartupWithUserMessage(
                                    "MediaRecorder rebuild prepare failed: " + lastStartupErrorMessage,
                                    RecordingStorageAccess.formatUserStartupFailureMessage(lastStartupErrorMessage, missingAllFiles));
                            return;
                        }
                        
                        // 通知外部时间戳已更新（用于远程录制查找文件）
                        if (timestampUpdateCallback != null) {
                            timestampUpdateCallback.onTimestampUpdated(newTimestamp);
                        }
                    } finally {
                        isRebuildingRecording = false;  // 重建完成
                    }
                }, 500);
            }
        } else {
            // 未达到阈值，先尝试重建 MediaRecorder
            AppLog.w(TAG, "Rebuild attempt " + rebuildAttemptCount + ", retrying MediaRecorder first...");
            
            mainHandler.postDelayed(() -> {
                try {
                    // 生成新的时间戳（避免文件名冲突）
                    String newTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                    
                    AppLog.d(TAG, "Restarting recording with MediaRecorder, new timestamp: " + newTimestamp);
                    if (!startMediaRecorderRecording(newTimestamp, savedEnabledCameras)) {
                        boolean missingAllFiles = RecordingStorageAccess.isMissingAllFilesAccessOnAndroid11Plus(context);
                        failRecordingStartupWithUserMessage(
                                "MediaRecorder rebuild prepare failed: " + lastStartupErrorMessage,
                                RecordingStorageAccess.formatUserStartupFailureMessage(lastStartupErrorMessage, missingAllFiles));
                        return;
                    }
                    
                    // 通知外部时间戳已更新（用于远程录制查找文件）
                    if (timestampUpdateCallback != null) {
                        timestampUpdateCallback.onTimestampUpdated(newTimestamp);
                    }
                } finally {
                    isRebuildingRecording = false;  // 重建完成
                }
            }, 500);
        }
    }
    
    /**
     * 为重建停止录制（不清理 Watchdog 状态）
     * 使用 reset() 而不是 release()，以便保留 Handler/Thread 供重建时使用
     */
    private void stopRecordingForRebuild() {
        AppLog.d(TAG, "Stopping recording for rebuild...");
        
        List<String> keys = getActiveCameraKeys();
        
        // 重置 MediaRecorder 录制器（保留 Handler/Thread）
        for (String key : keys) {
            VideoRecorder recorder = recorders.get(key);
            if (recorder != null) {
                recorder.reset();  // 重置而不是释放，保留 Handler/Thread
            }
        }
        
        // 清理摄像头会话
        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (camera != null) {
                camera.clearRecordSurface();
                camera.recreateSession();
            }
        }
        
        isRecording = false;
    }
    
    /**
     * 调度将指定的已完成文件传输到最终目录
     * @param completedFilePath 已完成录制的文件完整路径
     */
    private void scheduleRelayTransfer(String completedFilePath) {
        if (finalSaveDir == null || completedFilePath == null) {
            return;
        }
        
        File tempFile = new File(completedFilePath);
        if (!tempFile.exists()) {
            AppLog.w(TAG, "Completed file does not exist: " + completedFilePath);
            return;
        }
        
        // 检查文件大小，避免传输空文件或损坏文件
        if (tempFile.length() < 1024) {
            AppLog.w(TAG, "Completed file too small, skipping transfer: " + completedFilePath + " (" + tempFile.length() + " bytes)");
            return;
        }
        
        File targetFile = new File(finalSaveDir, tempFile.getName());
        
        AppLog.d(TAG, "Scheduling relay transfer: " + tempFile.getName() + 
                " -> " + targetFile.getAbsolutePath());
        
        FileTransferManager transferManager = FileTransferManager.getInstance(context);
        transferManager.addTransferTask(tempFile, targetFile, 
                new FileTransferManager.TransferCallback() {
            @Override
            public void onTransferComplete(File sourceFile, File targetFile) {
                AppLog.d(TAG, "Relay transfer complete: " + targetFile.getName());
            }
            
            @Override
            public void onTransferFailed(File sourceFile, File targetFile, String error) {
                AppLog.e(TAG, "Relay transfer failed: " + sourceFile.getName() + " - " + error);
            }
        });
    }
    
    /**
     * 将临时目录中的所有视频文件传输到最终目录
     * @param targetDir 目标目录
     */
    private void transferAllTempFiles(File targetDir) {
        if (targetDir == null) {
            AppLog.w(TAG, "Target directory is null, skipping transfer");
            return;
        }
        
        File tempDir = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
        if (!tempDir.exists()) {
            AppLog.d(TAG, "Temp directory does not exist");
            return;
        }
        
        File[] files = tempDir.listFiles((dir, name) -> name.endsWith(".mp4"));
        
        // 确保 FileTransferManager 已启动
        FileTransferManager.getInstance(context).start();
        
        transferSpecificTempFiles(targetDir, files);
    }
    
    /**
     * 将指定的临时视频文件传输到最终目录
     * 【重要】此方法只传输预先指定的文件列表，避免传输在调用后新创建的文件
     * @param targetDir 目标目录
     * @param files 要传输的文件列表（在调用前收集）
     */
    private void transferSpecificTempFiles(File targetDir, File[] files) {
        if (targetDir == null) {
            AppLog.w(TAG, "Target directory is null, skipping transfer");
            return;
        }
        
        if (files == null || files.length == 0) {
            AppLog.d(TAG, "No temp files to transfer");
            return;
        }
        
        AppLog.d(TAG, "Transferring " + files.length + " temp file(s) to " + targetDir.getAbsolutePath());
        
        FileTransferManager transferManager = FileTransferManager.getInstance(context);
        
        // 确保 FileTransferManager 已启动（如果从后台服务录制，可能未启动）
        transferManager.start();
        
        for (File tempFile : files) {
            // 检查文件是否仍然存在（可能已经被删除或移动）
            if (!tempFile.exists()) {
                AppLog.d(TAG, "Skipping non-existent file: " + tempFile.getName());
                continue;
            }
            
            // 跳过空文件（可能是正在被其他录制使用的新文件）
            if (tempFile.length() == 0) {
                AppLog.d(TAG, "Skipping empty file (may be in use): " + tempFile.getName());
                continue;
            }
            
            File targetFile = new File(targetDir, tempFile.getName());
            
            transferManager.addTransferTask(tempFile, targetFile, 
                    new FileTransferManager.TransferCallback() {
                @Override
                public void onTransferComplete(File sourceFile, File targetFile) {
                    AppLog.d(TAG, "Transfer complete: " + targetFile.getName());
                }
                
                @Override
                public void onTransferFailed(File sourceFile, File targetFile, String error) {
                    AppLog.e(TAG, "Transfer failed: " + sourceFile.getName() + " - " + error);
                }
            });
        }
    }

    /**
     * 释放所有资源
     * 添加完善的清理逻辑和异常保护
     */
    public void release() {
        cancelActivePrepareCoordinator("release");
        startupWatchdog.cancel();
        startupTracker.reset(java.util.Collections.emptyList());
        currentRecordingKeys = java.util.Collections.emptyList();
        AppLog.d(TAG, "Releasing MultiCameraManager resources");
        
        try {
            // 1. 首先清理所有待执行的 Handler 任务（防止内存泄漏）
            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
            }
            
            // 2. 清理超时 Runnable 引用
            if (sessionTimeoutRunnable != null) {
                sessionTimeoutRunnable = null;
            }
            pendingRecordingStart = null;
            
            // 3. 重置会话配置计数器
            synchronized (sessionLock) {
                sessionConfiguredCount = 0;
                expectedSessionCount = 0;
            }
            
            // 4. 停止录制
            try {
                stopRecording();
            } catch (Exception e) {
                AppLog.e(TAG, "Error stopping recording during release", e);
            }
            
            // 5. 关闭所有摄像头（并行关机并在有限时间内等待底层释放）
            try {
                closeAllCamerasAndAwait(2500L);
            } catch (Exception e) {
                AppLog.e(TAG, "Error closing cameras during release", e);
            }
            
            // 6. 释放 VideoRecorder
            for (VideoRecorder recorder : recorders.values()) {
                try {
                    recorder.release();
                } catch (Exception e) {
                    AppLog.e(TAG, "Error releasing VideoRecorder", e);
                }
            }
            
            // 7. 释放 CodecVideoRecorder
            pendingDirectFallbackStartKeys.clear();
            directFallbackGuard.reset();
            if (!inFlightFallbackRecorders.isEmpty()) {
                for (CodecVideoRecorder inFlight : inFlightFallbackRecorders.values()) {
                    try {
                        inFlight.requestStop();
                        inFlight.release();
                    } catch (Exception ignored) {
                    }
                }
                inFlightFallbackRecorders.clear();
            }
            for (CodecVideoRecorder codecRecorder : codecRecorders.values()) {
                try {
                    codecRecorder.release();
                } catch (Exception e) {
                    AppLog.e(TAG, "Error releasing CodecVideoRecorder", e);
                }
            }
            
        } catch (Exception e) {
            AppLog.e(TAG, "Unexpected error during release", e);
        } finally {
            closeCompositeInputs();
            // 8. 清理集合（确保执行）
            cameras.clear();
            recorders.clear();
            codecRecorders.clear();
            isRecording = false;
            isRebuildingRecording = false;
            currentRecordingTimestamp = null;
            currentEnabledCameras = null;
            AppLog.d(TAG, "All resources released");
        }
    }

    /**
     * release() 后 cameras map 为空，此方法用于外部判断实例是否已失效。
     */
    public boolean isReleased() {
        return cameras.isEmpty();
    }

    /**
     * 是否正在录制
     */
    public boolean isRecording() {
        return isRecording;
    }

    /** 是否正在后台并发准备编码器。 */
    public boolean isPreparingRecording() {
        return preparingRecording;
    }

    /** UI recreation must also wait for final frame draining and file finalization. */
    public boolean isStoppingRecording() {
        return stoppingRecording;
    }

    private void closeCompositeInputs() {
        CompositeRecording composite;
        synchronized (this) {
            composite = compositeRecording;
            compositeRecording = null;
        }
        if (composite != null) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                new Thread(composite::close,"CloseRecordingCompositor").start();
            } else composite.close();
        }
    }

    private RecordCallback compositeCallback() {
        return new RecordCallback() {
            @Override public void onRecordStart(String id) {}
            @Override public void onRecordStop(String id) {}
            @Override public void onRecordError(String id,String error) { handleRecorderError("Composite", id, error); }
            @Override public void onPrepareSegmentSwitch(String id,int index) {}
            @Override public void onSegmentSwitch(String id,int index,String path) {
                if (path != null) StorageCleanupManager.notifySegmentCompleted(path, currentSegmentDurationMs);
                if (useRelayWrite && finalSaveDir!=null && path!=null) scheduleRelayTransfer(path);
                if (segmentSwitchCallback!=null && index>lastNotifiedSegmentIndex) {
                    lastNotifiedSegmentIndex=index; segmentSwitchCallback.onSegmentSwitch(index);
                }
            }
            @Override public void onCorruptedFilesDeleted(String id,List<String> files) {
                if (corruptedFilesCallback!=null) mainHandler.post(() -> corruptedFilesCallback.onCorruptedFilesDeleted(files));
            }
            @Override public void onRecordingRebuildRequested(String id,String reason) { AppLog.w(TAG,"Composite recovery: "+reason); }
            @Override public void onFirstDataWritten(String id) {
                handleFirstDataWrittenForKey("combined");
            }
        };
    }

    /**
     * 拍照（所有活动的摄像头顺序拍照，避免资源耗尽）
     */
    /**
     * 拍照（所有摄像头，自动生成时间戳）
     */
    public void takePicture() {
        // 生成统一的时间戳
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        takePicture(timestamp);
    }

    public interface PhotoSurfaceProvider {
        void onPreparePhotoTextures(List<String> cameraKeys);
        void onReleasePhotoTextures(List<String> cameraKeys);
    }

    /**
     * 拍照（所有摄像头，使用指定的时间戳）
     * @param timestamp 统一的时间戳，用于所有摄像头的文件命名
     */
    public void takePicture(String timestamp) {
        List<String> keys = getActiveCameraKeys();
        if (keys.isEmpty()) return;
        List<String> missingTextureKeys = new ArrayList<>();
        for (String key : keys) {
            previewDemand.acquirePhoto(key);
            SingleCamera camera = cameras.get(key);
            if (camera != null && (camera.getTextureView() == null || !camera.getTextureView().isAvailable())) {
                missingTextureKeys.add(key);
            }
        }
        applyPreviewDemand();
        if (!missingTextureKeys.isEmpty() && photoSurfaceProvider != null) {
            photoSurfaceProvider.onPreparePhotoTextures(new ArrayList<>(missingTextureKeys));
        }
        long requestedAtMs = System.currentTimeMillis();
        waitForPhotoFrames(timestamp, keys, requestedAtMs);
    }

    private void waitForPhotoFrames(String timestamp, List<String> keys, long requestedAtMs) {
        if (isReleased()) {
            releasePhotoCaptureDemand(keys);
            return;
        }
        boolean allReady = true;
        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (!isPhotoReady(camera, requestedAtMs)) allReady = false;
        }
        if (!allReady && System.currentTimeMillis() - requestedAtMs < 3000) {
            mainHandler.postDelayed(() -> waitForPhotoFrames(timestamp, keys, requestedAtMs), 100);
            return;
        }
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(keys.size());
        Runnable photoCompleted = () -> mainHandler.post(() -> {
            if (remaining.decrementAndGet() == 0) {
                releasePhotoCaptureDemand(keys);
            }
        });
        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            final int saveDelay = i * 1000;
            mainHandler.postDelayed(() -> {
                SingleCamera camera = cameras.get(key);
                if (isPhotoReady(camera, requestedAtMs)) {
                    camera.takePicture(timestamp, saveDelay, photoCompleted);
                } else {
                    AppLog.w(TAG, "Camera " + key + " No fresh preview frame available for photo");
                    photoCompleted.run();
                }
            }, i * 300L);
        }
    }

    private void releasePhotoCaptureDemand(List<String> keys) {
        for (String key : keys) {
            previewDemand.releasePhoto(key);
        }
        if (previewDemand.hasActivePhotoLease()) {
            return;
        }
        List<String> toRelease = stagedPhotoCameraKeys.isEmpty()
                ? java.util.Collections.emptyList()
                : new ArrayList<>(stagedPhotoCameraKeys);
        for (String stagedKey : toRelease) {
            SingleCamera camera = cameras.get(stagedKey);
            TextureView original = savedPhotoTextureViews.remove(stagedKey);
            if (camera != null && !isReleased()) {
                camera.setTextureView(original);
            }
        }
        stagedPhotoCameraKeys.clear();
        savedPhotoTextureViews.clear();
        if (!isReleased()) {
            applyPreviewDemand();
        }
        if (photoSurfaceProvider != null && !toRelease.isEmpty()) {
            photoSurfaceProvider.onReleasePhotoTextures(toRelease);
        }
    }

    private boolean isPhotoReady(SingleCamera camera, long requestedAtMs) {
        return camera != null && camera.isConnected()
                && camera.getTextureView() != null && camera.getTextureView().isAvailable()
                && camera.getLastPreviewFrameTimestampMs() >= requestedAtMs;
    }

    /** Configured cameras may be closed intentionally while preview is disabled. */
    public boolean hasAvailableCameras() {
        return !cameras.isEmpty();
    }

    private List<String> getActiveCameraKeys() {
        if (!activeCameraKeys.isEmpty()) {
            return new ArrayList<>(activeCameraKeys);
        }
        List<String> keys = new ArrayList<>();
        int opened = 0;
        Set<String> openedIds = new HashSet<>();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            if (opened >= maxOpenCameras) {
                break;
            }
            SingleCamera camera = entry.getValue();
            String id = camera.getCameraId();
            if (!openedIds.add(id)) {
                continue;
            }
            keys.add(entry.getKey());
            opened++;
        }
        return keys;
    }

    /**
     * 检查是否有已连接的相机
     */
    public boolean hasConnectedCameras() {
        for (SingleCamera camera : cameras.values()) {
            if (camera.isConnected()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取已连接的相机数量
     */
    public int getConnectedCameraCount() {
        int count = 0;
        for (SingleCamera camera : cameras.values()) {
            if (camera.isConnected()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 生命周期：暂停所有摄像头（App退到后台时调用）
     * 注意：如果正在录制，不应该调用此方法
     */
    public void pauseAllCamerasByLifecycle() {
        AppLog.d(TAG, "Pausing all cameras by lifecycle");
        for (SingleCamera camera : cameras.values()) {
            camera.pauseByLifecycle();
        }
    }

    /**
     * 生命周期：恢复所有摄像头（App返回前台时调用）
     */
    public void resumeAllCamerasByLifecycle() {
        AppLog.d(TAG, "Resuming all cameras by lifecycle");
        for (SingleCamera camera : cameras.values()) {
            camera.resumeByLifecycle();
        }
    }

    /**
     * 检查并修复摄像头连接（返回前台时调用）
     * 如果发现摄像头断开，自动重新打开
     * @return 需要重新打开的摄像头数量
     */
    public int checkAndRepairCameras() {
        if (repairSuppressed) {
            return 0;
        }
        int disconnectedCount = 0;
        
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            SingleCamera camera = entry.getValue();
            if (camera.hasActiveOutputs() && !camera.isConnected()) {
                disconnectedCount++;
                AppLog.d(TAG, "Camera " + entry.getKey() + " reconnecting...");
                camera.forceReopen();
            }
        }
        
        if (disconnectedCount > 0) {
            AppLog.d(TAG, disconnectedCount + " camera(s) reconnecting");
        }
        
        return disconnectedCount;
    }

    /**
     * 检查是否存在有活跃输出但尚未连接的摄像头（只读查询，不主动重连）。
     */
    public boolean hasDisconnectedActiveCamera() {
        if (repairSuppressed) {
            return false;
        }
        for (SingleCamera camera : cameras.values()) {
            if (camera.hasActiveOutputs() && !camera.isConnected()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 强制重新打开所有摄像头（用于从后台返回前台时）
     */
    public void forceReopenAllCameras() {
        AppLog.d(TAG, "Force reopening all cameras...");
        repairSuppressed = false;
        for (SingleCamera camera : cameras.values()) {
            if (camera.hasActiveOutputs()) {
                camera.forceReopen();
            }
        }
    }

    /**
     * 获取所有摄像头当前使用的分辨率信息
     * @return 格式化的分辨率信息字符串
     */
    /**
     * 获取所有摄像头的实时调试信息（FPS + 分辨率）
     */
    public String getDebugStats() {
        StringBuilder sb = new StringBuilder();
        String[] order = {"front", "back", "left", "right"};
        String[] labels = {"前", "后", "左", "右"};
        for (int i = 0; i < order.length; i++) {
            SingleCamera camera = cameras.get(order[i]);
            if (camera == null) continue;
            if (sb.length() > 0) sb.append("\n");
            android.util.Size previewSize = camera.getPreviewSize();
            String res = previewSize != null
                    ? previewSize.getWidth() + "×" + previewSize.getHeight()
                    : "-";
            float fps = camera.getCurrentFps();
            sb.append(labels[i]).append("(").append(camera.getCameraId()).append(") ");
            sb.append(String.format(java.util.Locale.US, "%.1f fps  ", fps));
            sb.append(res);
        }
        return sb.toString();
    }

    public String getCameraResolutionsInfo() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            String key = entry.getKey();
            SingleCamera camera = entry.getValue();
            String cameraId = camera.getCameraId();
            Size previewSize = camera.getPreviewSize();
            
            if (sb.length() > 0) {
                sb.append("\n");
            }
            
            sb.append(key).append(" (摄像头").append(cameraId).append("): ");
            if (previewSize != null) {
                sb.append(previewSize.getWidth()).append("×").append(previewSize.getHeight());
            } else {
                sb.append("未初始化");
            }
        }
        return sb.toString();
    }

    public boolean hasActiveDirectCodecRecorders() {
        for (CodecVideoRecorder recorder : codecRecorders.values()) {
            if (recorder != null && recorder.isDirectInputActive()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasPendingGlConfigChange() {
        for (CodecVideoRecorder recorder : codecRecorders.values()) {
            if (recorder != null && recorder.isPendingGlConfigChange()) {
                return true;
            }
        }
        return false;
    }

    private CodecVideoRecorder buildConfiguredCodecRecorder(
            String key,
            SingleCamera camera,
            AppConfig appConfig,
            long segmentDurationMs,
            int targetFrameRate,
            boolean runtimeGlFallback) {
        Size previewSize = camera.getPreviewSize();
        if (previewSize == null) {
            AppLog.e(TAG, "Camera " + key + " preview size not available, using fallback 1280x800");
            previewSize = new Size(1280, 800);
        }

        final int MAX_ENCODE_SIZE = 4096;
        ImageAdjustmentConfig adjConfig = appConfig.getImageAdjustmentConfig(key);
        int encodeWidth = adjConfig.scaleWidth(previewSize.getWidth());
        int encodeHeight = adjConfig.scaleHeight(previewSize.getHeight());
        if (encodeWidth > MAX_ENCODE_SIZE || encodeHeight > MAX_ENCODE_SIZE) {
            float widthRatio = (float) MAX_ENCODE_SIZE / encodeWidth;
            float heightRatio = (float) MAX_ENCODE_SIZE / encodeHeight;
            float scaleFactor = Math.min(widthRatio, heightRatio);
            encodeWidth = ((int) (encodeWidth * scaleFactor) / 2) * 2;
            encodeHeight = ((int) (encodeHeight * scaleFactor) / 2) * 2;
            if (encodeWidth < 2) encodeWidth = 2;
            if (encodeHeight < 2) encodeHeight = 2;
            AppLog.w(TAG, "Camera " + key + " codec resolution adjusted: " +
                    previewSize.getWidth() + "x" + previewSize.getHeight() + " -> " +
                    encodeWidth + "x" + encodeHeight + " (max: " + MAX_ENCODE_SIZE + ")");
        }

        int bitrate = appConfig.getActualBitrate(encodeWidth, encodeHeight, targetFrameRate);
        CodecVideoRecorder codecRecorder = new CodecVideoRecorder(
                camera.getCameraId(),
                encodeWidth,
                encodeHeight
        );

        Size sourceSize = camera.getPreviewSize();
        int srcW = sourceSize != null ? sourceSize.getWidth() : encodeWidth;
        int srcH = sourceSize != null ? sourceSize.getHeight() : encodeHeight;
        boolean cameraSupportsDirect = camera.isDirectCodecInputSizeSupported(encodeWidth, encodeHeight);
        configureCodecRecorderCommon(
                key, camera, codecRecorder, segmentDurationMs,
                bitrate, targetFrameRate, srcW, srcH, encodeWidth, encodeHeight,
                appConfig, adjConfig, cameraSupportsDirect, runtimeGlFallback);
        codecRecorder.setCallback(createCodecRecordCallback(key));
        return codecRecorder;
    }

    private void configureCodecRecorderCommon(
            String key,
            SingleCamera camera,
            CodecVideoRecorder codecRecorder,
            long segmentDurationMs,
            int bitrate,
            int targetFrameRate,
            int sourceWidth,
            int sourceHeight,
            int encodeWidth,
            int encodeHeight,
            AppConfig appConfig,
            ImageAdjustmentConfig adjConfig,
            boolean cameraSupportsDirect,
            boolean runtimeGlFallback) {
        codecRecorder.setSourceSize(sourceWidth, sourceHeight);
        codecRecorder.setTimestampProvider(segmentTimestampProvider);
        codecRecorder.setSegmentDuration(segmentDurationMs);
        codecRecorder.setBitRate(bitrate);
        codecRecorder.setFrameRate(targetFrameRate);
        codecRecorder.setE5Compatibility(RecordingDeviceProfile.isE5(appConfig.getCarModel()));
        camera.setRecordingFrameRate(targetFrameRate);
        String bitrateLevel = appConfig.getBitrateLevel();
        int qualityLevel = AppConfig.BITRATE_LOW.equals(bitrateLevel) ? 0
                : (AppConfig.BITRATE_HIGH.equals(bitrateLevel) ? 2 : 1);
        codecRecorder.setQualityLevel(qualityLevel);
        codecRecorder.setForceH264(appConfig.isForceH264Encoding());
        codecRecorder.setImageAdjustmentConfig(adjConfig);
        codecRecorder.setWatermarkOptions(appConfig.getWatermarkOptions());
        codecRecorder.setDirectInputEnabled(appConfig.isDirectCodecInputEnabled());
        codecRecorder.setCameraSupportsDirectInput(cameraSupportsDirect);
        codecRecorder.setNativeSourceFrameRate(25);
        codecRecorder.setRuntimeGlFallback(runtimeGlFallback);

        AppLog.d(TAG, "Codec recording params for " + key + ": src=" +
                sourceWidth + "x" + sourceHeight + " -> encode=" +
                encodeWidth + "x" + encodeHeight +
                " @ " + targetFrameRate + "fps, " + AppConfig.formatBitrate(bitrate)
                + ", directSwitch=" + appConfig.isDirectCodecInputEnabled()
                + ", cameraDirectCap=" + cameraSupportsDirect
                + ", runtimeGlFallback=" + runtimeGlFallback);
    }
}
