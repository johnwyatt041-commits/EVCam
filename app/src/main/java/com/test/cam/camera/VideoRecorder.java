package com.test.cam.camera;

import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 视频录制管理类
 */
public class VideoRecorder {
    private static final String TAG = "VideoRecorder";

    private final String cameraId;
    private MediaRecorder mediaRecorder;
    private RecordCallback callback;
    private boolean isRecording = false;
    private boolean waitingForSessionReconfiguration = false;  // 等待会话重新配置
    private String currentFilePath;

    // 分段录制相关
    private static final long SEGMENT_DURATION_MS = 60000;  // 1分钟
    private android.os.Handler segmentHandler;
    private Runnable segmentRunnable;
    private int segmentIndex = 0;
    private String saveDirectory;  // 保存目录
    private String cameraPosition;  // 摄像头位置（front/back/left/right）
    private int recordWidth;
    private int recordHeight;

    public VideoRecorder(String cameraId) {
        this.cameraId = cameraId;
        this.segmentHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    }

    public void setCallback(RecordCallback callback) {
        this.callback = callback;
    }

    public boolean isRecording() {
        return isRecording;
    }

    public Surface getSurface() {
        if (mediaRecorder != null) {
            return mediaRecorder.getSurface();
        }
        return null;
    }

    /**
     * 获取当前段索引
     */
    public int getCurrentSegmentIndex() {
        return segmentIndex;
    }

    /**
     * 获取当前文件路径
     */
    public String getCurrentFilePath() {
        return currentFilePath;
    }

    /**
     * 检查是否正在等待会话重新配置
     */
    public boolean isWaitingForSessionReconfiguration() {
        return waitingForSessionReconfiguration;
    }

    /**
     * 清除等待会话重新配置的标志
     */
    public void clearWaitingForSessionReconfiguration() {
        waitingForSessionReconfiguration = false;
    }

    /**
     * 准备录制器
     */
    private void prepareMediaRecorder(String filePath, int width, int height) throws IOException {
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(filePath);
        mediaRecorder.setVideoEncodingBitRate(1000000); // 降低到1Mbps以减少资源消耗
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(width, height);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.prepare();
    }

    /**
     * 准备录制器（不启动）
     */
    public boolean prepareRecording(String filePath, int width, int height) {
        if (isRecording) {
            Log.w(TAG, "Camera " + cameraId + " is already recording");
            return false;
        }

        // 先释放旧的 MediaRecorder（如果存在）
        releaseMediaRecorder();

        try {
            // 保存录制参数用于分段
            this.recordWidth = width;
            this.recordHeight = height;
            this.segmentIndex = 0;

            // 从文件路径中提取保存目录和摄像头位置
            File file = new File(filePath);
            this.saveDirectory = file.getParent();
            String fileName = file.getName();
            // 文件名格式：日期_时间_摄像头位置.mp4
            // 提取摄像头位置（最后一个下划线后的部分，去掉.mp4）
            int lastUnderscoreIndex = fileName.lastIndexOf('_');
            if (lastUnderscoreIndex > 0 && fileName.endsWith(".mp4")) {
                this.cameraPosition = fileName.substring(lastUnderscoreIndex + 1, fileName.length() - 4);
            } else {
                this.cameraPosition = "unknown";
            }

            // 使用传入的文件路径作为第一段
            prepareMediaRecorder(filePath, width, height);
            currentFilePath = filePath;
            Log.d(TAG, "Camera " + cameraId + " prepared recording to: " + filePath);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to prepare recording for camera " + cameraId, e);
            releaseMediaRecorder();
            // 确保状态被重置
            isRecording = false;
            waitingForSessionReconfiguration = false;
            currentFilePath = null;
            segmentIndex = 0;
            if (callback != null) {
                callback.onRecordError(cameraId, e.getMessage());
            }
            return false;
        }
    }

    /**
     * 生成新的分段文件路径（使用当前时间戳）
     */
    private String generateSegmentPath() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = timestamp + "_" + cameraPosition + ".mp4";
        return new File(saveDirectory, fileName).getAbsolutePath();
    }

    /**
     * 启动录制（必须先调用 prepareRecording）
     */
    public boolean startRecording() {
        if (mediaRecorder == null) {
            Log.e(TAG, "Camera " + cameraId + " MediaRecorder not prepared");
            return false;
        }

        if (isRecording) {
            Log.w(TAG, "Camera " + cameraId + " is already recording");
            return false;
        }

        try {
            mediaRecorder.start();
            isRecording = true;
            Log.d(TAG, "Camera " + cameraId + " started recording segment " + segmentIndex);
            if (callback != null && segmentIndex == 0) {
                // 只在第一段时通知开始录制
                callback.onRecordStart(cameraId);
            }

            // 启动分段定时器
            scheduleNextSegment();

            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to start recording for camera " + cameraId, e);
            releaseMediaRecorder();
            if (callback != null) {
                callback.onRecordError(cameraId, e.getMessage());
            }
            return false;
        }
    }

    /**
     * 调度下一段录制
     */
    private void scheduleNextSegment() {
        // 取消之前的定时器
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
        }

        // 创建新的分段任务
        segmentRunnable = () -> {
            if (isRecording) {
                Log.d(TAG, "Camera " + cameraId + " switching to next segment");
                switchToNextSegment();
            }
        };

        // 延迟执行（1分钟后）
        segmentHandler.postDelayed(segmentRunnable, SEGMENT_DURATION_MS);
        Log.d(TAG, "Camera " + cameraId + " scheduled next segment in " + (SEGMENT_DURATION_MS / 1000) + " seconds");
    }

    /**
     * 切换到下一段
     * 注意：这个方法需要通过回调通知外部重新配置相机会话
     */
    private void switchToNextSegment() {
        try {
            // 停止当前录制
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                    isRecording = false;  // 立即更新状态
                    Log.d(TAG, "Camera " + cameraId + " stopped segment " + segmentIndex + ": " + currentFilePath);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Error stopping segment for camera " + cameraId, e);
                    isRecording = false;  // 即使失败也更新状态
                    // 即使停止失败，也继续尝试下一段
                }
                releaseMediaRecorder();
            }

            // 准备下一段（使用新的时间戳）
            segmentIndex++;
            String nextSegmentPath = generateSegmentPath();
            prepareMediaRecorder(nextSegmentPath, recordWidth, recordHeight);
            currentFilePath = nextSegmentPath;

            // 设置等待会话重新配置的标志
            waitingForSessionReconfiguration = true;

            // 通知外部需要重新配置相机会话（因为 MediaRecorder 的 Surface 已经改变）
            // 外部需要调用 startRecording() 来启动新段的录制
            if (callback != null) {
                callback.onSegmentSwitch(cameraId, segmentIndex);
            }

            // 注意：不在这里调用 start()，而是等待外部重新配置相机会话后调用 startRecording()
            // 这样可以确保新的 Surface 已经添加到 CaptureSession 中
            Log.d(TAG, "Camera " + cameraId + " prepared segment " + segmentIndex + ": " + nextSegmentPath + ", waiting for session reconfiguration");

        } catch (Exception e) {
            Log.e(TAG, "Failed to switch segment for camera " + cameraId, e);
            isRecording = false;
            waitingForSessionReconfiguration = false;
            if (callback != null) {
                callback.onRecordError(cameraId, "Failed to switch segment: " + e.getMessage());
            }
        }
    }

    /**
     * 开始录制（旧方法，保持兼容性）
     */
    public boolean startRecording(String filePath, int width, int height) {
        if (prepareRecording(filePath, width, height)) {
            return startRecording();
        }
        return false;
    }

    /**
     * 停止录制
     */
    public void stopRecording() {
        // 取消分段定时器
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }

        // 如果正在等待会话重新配置，说明MediaRecorder已经stop过了，只需要清理状态
        if (waitingForSessionReconfiguration) {
            Log.d(TAG, "Camera " + cameraId + " is waiting for session reconfiguration, skipping stop");
            isRecording = false;
            waitingForSessionReconfiguration = false;
            releaseMediaRecorder();
            currentFilePath = null;
            segmentIndex = 0;
            if (callback != null) {
                callback.onRecordStop(cameraId);
            }
            return;
        }

        if (!isRecording) {
            Log.w(TAG, "Camera " + cameraId + " is not recording");
            return;
        }

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                Log.d(TAG, "Camera " + cameraId + " stopped recording: " + currentFilePath + " (total segments: " + (segmentIndex + 1) + ")");
            }
            isRecording = false;

            if (callback != null) {
                callback.onRecordStop(cameraId);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to stop recording for camera " + cameraId, e);
            isRecording = false;
        } finally {
            releaseMediaRecorder();
            currentFilePath = null;
            segmentIndex = 0;
        }
    }

    /**
     * 释放录制器
     */
    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        // 取消分段定时器
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }

        // 只有在真正录制中且mediaRecorder不为null时才调用stopRecording
        if (isRecording && mediaRecorder != null) {
            stopRecording();
        } else {
            // 直接清理状态
            isRecording = false;
            waitingForSessionReconfiguration = false;
            releaseMediaRecorder();
            currentFilePath = null;
            segmentIndex = 0;
        }
    }
}
