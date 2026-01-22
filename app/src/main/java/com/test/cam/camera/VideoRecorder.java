package com.test.cam.camera;

import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;

/**
 * 视频录制管理类
 */
public class VideoRecorder {
    private static final String TAG = "VideoRecorder";

    private final String cameraId;
    private MediaRecorder mediaRecorder;
    private RecordCallback callback;
    private boolean isRecording = false;
    private String currentFilePath;

    // 分段录制相关
    private static final long SEGMENT_DURATION_MS = 60000;  // 1分钟
    private android.os.Handler segmentHandler;
    private Runnable segmentRunnable;
    private int segmentIndex = 0;
    private String baseFilePath;  // 基础文件路径（不含序号）
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

        try {
            // 保存录制参数用于分段
            this.recordWidth = width;
            this.recordHeight = height;
            this.segmentIndex = 0;

            // 生成基础文件路径（移除扩展名）
            if (filePath.endsWith(".mp4")) {
                this.baseFilePath = filePath.substring(0, filePath.length() - 4);
            } else {
                this.baseFilePath = filePath;
            }

            // 生成第一段的文件路径
            String segmentPath = generateSegmentPath(segmentIndex);
            prepareMediaRecorder(segmentPath, width, height);
            currentFilePath = segmentPath;
            Log.d(TAG, "Camera " + cameraId + " prepared recording to: " + segmentPath);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to prepare recording for camera " + cameraId, e);
            releaseMediaRecorder();
            if (callback != null) {
                callback.onRecordError(cameraId, e.getMessage());
            }
            return false;
        }
    }

    /**
     * 生成分段文件路径
     */
    private String generateSegmentPath(int index) {
        if (index == 0) {
            return baseFilePath + ".mp4";
        } else {
            return baseFilePath + "_part" + index + ".mp4";
        }
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
            if (callback != null) {
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
     */
    private void switchToNextSegment() {
        try {
            // 停止当前录制
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                    Log.d(TAG, "Camera " + cameraId + " stopped segment " + segmentIndex + ": " + currentFilePath);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Error stopping segment for camera " + cameraId, e);
                }
                releaseMediaRecorder();
            }

            // 准备下一段
            segmentIndex++;
            String nextSegmentPath = generateSegmentPath(segmentIndex);
            prepareMediaRecorder(nextSegmentPath, recordWidth, recordHeight);
            currentFilePath = nextSegmentPath;

            // 启动下一段录制
            mediaRecorder.start();
            Log.d(TAG, "Camera " + cameraId + " started segment " + segmentIndex + ": " + nextSegmentPath);

            // 调度再下一段
            scheduleNextSegment();

        } catch (Exception e) {
            Log.e(TAG, "Failed to switch segment for camera " + cameraId, e);
            isRecording = false;
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
        if (!isRecording) {
            Log.w(TAG, "Camera " + cameraId + " is not recording");
            return;
        }

        // 取消分段定时器
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }

        try {
            mediaRecorder.stop();
            isRecording = false;

            Log.d(TAG, "Camera " + cameraId + " stopped recording: " + currentFilePath + " (total segments: " + (segmentIndex + 1) + ")");
            if (callback != null) {
                callback.onRecordStop(cameraId);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to stop recording for camera " + cameraId, e);
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

        if (isRecording) {
            stopRecording();
        }
        releaseMediaRecorder();
    }
}
