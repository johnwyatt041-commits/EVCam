package com.kooo.evcam;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.content.Intent;
import android.graphics.Color;

import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

/**
 * 独立补盲悬浮窗视图
 * 支持拖动和边缘缩放
 */
public class BlindSpotFloatingWindowView extends FrameLayout {
    private static final String TAG = "BlindSpotFloatingWindowView";
    private static final int RESIZE_THRESHOLD = 50;

    private WindowManager windowManager;
    public WindowManager.LayoutParams params; // 改为public以便Service访问
    private com.kooo.evcam.ui.OverlayWindowParamsFactory.AttachedOverlay attachedOverlay;
    private AppConfig appConfig;
    private TextureView textureView;
    private Surface cachedSurface;
    private SingleCamera currentCamera;
    private String cameraPos = "right"; // 默认用右摄像头测试
    private boolean isSetupMode = false;
    private int currentRotation = 0;
    private boolean isAdjustPreviewMode = false;
    
    // 长按和拖拽相关
    private static final long LONG_PRESS_DURATION = 500; // 长按时间阈值
    private static final int TOUCH_SLOP = 20; // 触摸移动阈值
    private boolean isLongPress = false;
    private boolean isDragging = false;
    private float touchStartX, touchStartY;
    private long touchStartTime;
    private Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int retryBindCount = 0;
    private Runnable retryBindRunnable;
    private boolean pendingShowAnimation = false;
    private Runnable showAnimFallback;

    private float lastX, lastY;
    private float initialX, initialY;
    private int initialWidth, initialHeight;
    private boolean isResizing = false;
    private int resizeMode = 0;
    private boolean isCurrentlySwapped = false;
    private boolean hasUnsavedResize = false;

    private BlindSpotStatusBarView statusBar;
    private TurnSignalArrowView turnSignalArrowView;

    public BlindSpotFloatingWindowView(Context context, boolean isSetupMode) {
        super(context);
        this.isSetupMode = isSetupMode;
        appConfig = new AppConfig(context);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        init();
    }

    private void init() {
        int layoutRes = appConfig.isMultiviewCarModel()
                ? R.layout.view_blind_spot_floating_multiview
                : R.layout.view_blind_spot_floating;
        LayoutInflater.from(getContext()).inflate(layoutRes, this);
        textureView = findViewById(R.id.blind_spot_texture_view);
        View saveLayout = findViewById(R.id.layout_save_config);
        View saveButton = findViewById(R.id.btn_save_blind_spot_config);
        View rotateButton = findViewById(R.id.btn_rotate_blind_spot);

        statusBar = findViewById(R.id.blind_spot_status_bar);
        turnSignalArrowView = findViewById(R.id.turn_signal_arrow_view);
        
        applyStatusBarStyle();

        // 统一使用转向灯补盲的旋转设置，与转向灯触发时保持一致
        currentRotation = appConfig.getTurnSignalFloatingRotation();
        applyTransformNow();

        if (isSetupMode) {
            saveLayout.setVisibility(View.VISIBLE);
            saveButton.setOnClickListener(v -> {
                hasUnsavedResize = false;
                // 若宽高因矫正旋转而交换过，保存前还原为基础值
                int saveW = params.width;
                int saveH = params.height;
                if (isCurrentlySwapped) {
                    saveW = params.height;
                    saveH = params.width;
                }
                appConfig.setTurnSignalFloatingBounds(params.x, params.y, saveW, saveH);
                appConfig.setTurnSignalFloatingRotation(currentRotation);
                dismiss();
            });
            rotateButton.setOnClickListener(v -> {
                currentRotation = (currentRotation + 90) % 360;
                applyTransformNow();
            });
        }

        // 根据摄像头位置选择配置
        int initialWidth, initialHeight, initialX, initialY;
        initialWidth = appConfig.getTurnSignalFloatingWidth();
        initialHeight = appConfig.getTurnSignalFloatingHeight();
        initialX = appConfig.getTurnSignalFloatingX();
        initialY = appConfig.getTurnSignalFloatingY();

        params = com.kooo.evcam.ui.OverlayWindowParamsFactory.createMainParams(
                getContext(),
                initialWidth,
                initialHeight,
                initialX,
                initialY,
                true
        );

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                MultiCameraManager cm = CameraManagerHolder.getInstance().getCameraManager();
                if (cm != null) {
                    SingleCamera camera = cm.getCamera(cameraPos);
                    if (camera != null) {
                        Size previewSize = camera.getPreviewSize();
                        if (previewSize != null) {
                            surface.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                        }
                    }
                }
                if (cachedSurface != null) cachedSurface.release();
                cachedSurface = new Surface(surface);
                startCameraPreview(cachedSurface);
                applyTransformNow();
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                applyTransformNow();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                cancelRetryBind();
                stopCameraPreview();
                if (cachedSurface != null) {
                    cachedSurface.release();
                    cachedSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
                if (pendingShowAnimation) {
                    pendingShowAnimation = false;
                    if (showAnimFallback != null) {
                        mainHandler.removeCallbacks(showAnimFallback);
                        showAnimFallback = null;
                    }
                    playShowAnimation();
                }
            }
        });
    }

    /**
     * 同步调整大小到配对窗口
     * 等比例缩放，保持两个窗口大小一致
     */
    private void syncSizeToPartner(int newWidth, int newHeight) {

    }

    /**
     * 同步位置到配对窗口（保持Y坐标一致）
     */
    private void syncPositionToPartner(int newY) {

    }

    /**
     * 保存超视模式窗口的位置和大小
     */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        
        if (!isSetupMode && !isAdjustPreviewMode) return super.onTouchEvent(event);

        float x = event.getRawX();
        float y = event.getRawY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = x;
                lastY = y;
                initialX = params.x;
                initialY = params.y;
                initialWidth = params.width;
                initialHeight = params.height;

                if (isSetupMode) {
                    float localX = event.getX();
                    float localY = event.getY();
                    int w = getWidth();
                    int h = getHeight();
                    resizeMode = 0;
                    if (localX < RESIZE_THRESHOLD) resizeMode |= 1;
                    if (localX > w - RESIZE_THRESHOLD) resizeMode |= 2;
                    if (localY < RESIZE_THRESHOLD) resizeMode |= 4;
                    if (localY > h - RESIZE_THRESHOLD) resizeMode |= 8;
                    isResizing = resizeMode != 0;
                } else {
                    isResizing = false;
                    resizeMode = 0;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX;
                float dy = y - lastY;

                if (isSetupMode && isResizing) {
                    if ((resizeMode & 1) != 0) {
                        int newWidth = (int) (initialWidth - dx);
                        if (newWidth > 200) {
                            params.width = newWidth;
                            params.x = (int) (initialX + dx);
                        }
                    }
                    if ((resizeMode & 2) != 0) {
                        int newWidth = (int) (initialWidth + dx);
                        if (newWidth > 200) params.width = newWidth;
                    }
                    if ((resizeMode & 4) != 0) {
                        int newHeight = (int) (initialHeight - dy);
                        if (newHeight > 150) {
                            params.height = newHeight;
                            params.y = (int) (initialY + dy);
                        }
                    }
                    if ((resizeMode & 8) != 0) {
                        int newHeight = (int) (initialHeight + dy);
                        if (newHeight > 150) params.height = newHeight;
                    }
                    updateWindowLayoutSafely();
                } else if (isSetupMode || isAdjustPreviewMode) {
                    params.x = (int) (initialX + dx);
                    params.y = (int) (initialY + dy);
                    updateWindowLayoutSafely();
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (isResizing) {
                    hasUnsavedResize = true;
                }
                isResizing = false;
                return true;
        }
        return super.onTouchEvent(event);
    }
    
    /**
     * 仅设置摄像头位置（不触发预览切换）。
     * 用于 show() 前设定初始摄像头，避免 onSurfaceTextureAvailable 使用默认值。
     */
    public void setCameraPos(String cameraPos) {
        this.cameraPos = cameraPos;
        // 重新加载对应摄像头的配置（因为init()时cameraPos可能还是默认值）

        // 统一使用转向灯补盲的旋转设置，与转向灯触发时保持一致
        currentRotation = appConfig.getTurnSignalFloatingRotation();
        AppLog.d(TAG, "🎯 setCameraPos更新旋转角度: camera=" + cameraPos + ", rotation=" + currentRotation);
        // 重新应用画面变换，确保使用正确的摄像头配置
        applyTransformNow();
    }

    /**
     * 根据cameraPos重新加载对应的配置
     * 用于在setCameraPos后更新窗口位置和大小
     * 使用独立保存的尺寸和位置
     */

    public void setCamera(String cameraPos) {
        this.cameraPos = cameraPos;
        stopCameraPreview(true); // 切换摄像头时使用紧急模式清除旧surface
        applyTransformNow();

        // 更新 SurfaceTexture 的 buffer size 以匹配新摄像头的预览分辨率
        // 避免 Surface 尺寸与摄像头配置不匹配导致 session 创建失败
        if (textureView.isAvailable()) {
            android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {
                MultiCameraManager cm = CameraManagerHolder.getInstance().getCameraManager();
                if (cm != null) {
                    SingleCamera camera = cm.getCamera(cameraPos);
                    if (camera != null) {
                        Size previewSize = camera.getPreviewSize();
                        if (previewSize != null) {
                            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                            AppLog.d(TAG, "Updated buffer size to " + previewSize.getWidth() + "x" + previewSize.getHeight() + " for " + cameraPos);
                        }
                    }
                }
            }
        }

        if (textureView.isAvailable() && cachedSurface != null && cachedSurface.isValid()) {
            startCameraPreview(cachedSurface, true);
        } else {
            scheduleRetryBind();
        }
    }

    private void startCameraPreview(Surface surface) {
        startCameraPreview(surface, false);
    }

    private void startCameraPreview(Surface surface, boolean urgent) {
        MultiCameraManager cameraManager = CameraManagerHolder.getInstance().getCameraManager();
        if (cameraManager == null) {
            cameraManager = CameraManagerHolder.getInstance().getOrInit(getContext());
            if (cameraManager == null) {
                scheduleRetryBind();
                return;
            }
        }

        currentCamera = cameraManager.getCamera(cameraPos);
        if (currentCamera == null) {
            scheduleRetryBind();
            return;
        }
        // 传递 SurfaceTexture 引用，便于 createCameraPreviewSession 统一设置 buffer 尺寸
        android.graphics.SurfaceTexture st = (textureView != null && textureView.isAvailable()) ? textureView.getSurfaceTexture() : null;
        currentCamera.setMainFloatingSurface(surface, st);

        // 如果摄像头硬件还未打开（后台初始化时不打开），先打开
        if (!currentCamera.isCameraOpened()) {
            AppLog.d(TAG, "Camera not opened yet, opening now for " + cameraPos);
            // 确保前台服务就绪后再打开相机（避免冷启动时 CAMERA_DISABLED）
            final SingleCamera cam = currentCamera;
            CameraForegroundService.whenReady(getContext(), cam::openCamera);
        } else {
            currentCamera.recreateSession(urgent);
        }
        cancelRetryBind();
    }

    private void stopCameraPreview() {
        stopCameraPreview(false);
    }

    private void stopCameraPreview(boolean urgent) {
        if (currentCamera != null) {
            // 立即停止推帧，防止 Surface 销毁后 queueBuffer abandoned 刷屏
            currentCamera.stopRepeatingNow();
            currentCamera.setMainFloatingSurface(null);
            currentCamera.recreateSession(urgent);
            currentCamera = null;
        }
    }

    public void show() {
        try {
            if (this.getParent() == null) {
                // 等待首帧画面到达后再直接显示，避免黑屏闪烁
                params.alpha = 0f;
                pendingShowAnimation = true;

                attachedOverlay = com.kooo.evcam.ui.OverlayWindowParamsFactory.attachMainOverlay(
                        getContext(), windowManager, this, params, null);
                if (isAdjustPreviewMode) {
                    moveToAdjustPreviewDefaultPosition();
                }
                applyTransformNow();

                // 安全超时：如果摄像头迟迟没有推帧，最多等 150ms 后也直接显示
                showAnimFallback = () -> {
                    if (pendingShowAnimation) {
                        pendingShowAnimation = false;
                        playShowAnimation();
                    }
                };
                mainHandler.postDelayed(showAnimFallback, 150);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Error showing blind spot floating window: " + e.getMessage());
        }
    }

    private WindowManager getActiveWindowManager() {
        return attachedOverlay != null ? attachedOverlay.getActiveWindowManager() : windowManager;
    }

    private void updateWindowLayoutSafely() {
        if (attachedOverlay != null) {
            attachedOverlay.updateLayout();
        } else if (windowManager != null) {
            updateWindowLayoutSafely();
        }
    }

    private void playShowAnimation() {
        FloatingWindowDisplayHelper.revealNow(this, getActiveWindowManager(), params);
    }

    public void applyTransformNow() {
        // 矫正旋转更接近竖屏时，悬浮窗宽高互换，让画面自然填满不裁切
        int correctionRotation = 0;
        if (appConfig.isBlindSpotCorrectionEnabled() && cameraPos != null) {
            correctionRotation = appConfig.getBlindSpotCorrectionRotation(cameraPos);
        }

        // 根据模式选择尺寸配置
        int baseW, baseH;
        {
            // 转向灯模式使用补盲画面尺寸
            baseW = appConfig.getTurnSignalFloatingWidth();
            baseH = appConfig.getTurnSignalFloatingHeight();
        }

        boolean shouldSwap = BlindSpotCorrection.isCloserToPortrait(correctionRotation);
        isCurrentlySwapped = shouldSwap;
        int targetW = shouldSwap ? baseH : baseW;
        int targetH = shouldSwap ? baseW : baseH;

        // 用户正在拖动缩放或有未保存的缩放时，不覆盖 params，以免打断手势或丢失调整
        if (!isResizing && !hasUnsavedResize
                && params != null && (params.width != targetW || params.height != targetH)) {
            params.width = targetW;
            params.height = targetH;
            try {
                if (getParent() != null) {
                    updateWindowLayoutSafely();
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        BlindSpotCorrection.apply(textureView, appConfig,
                com.kooo.evcam.blindspot.MirrorTarget.BLIND_SPOT, cameraPos, currentRotation, isCurrentlySwapped);
    }

    public void enableAdjustPreviewMode() {
        isAdjustPreviewMode = true;
        if (params != null) {
            int targetW = dpToPx(320);
            int targetH = dpToPx(180);
            if (targetW > 0 && targetH > 0) {
                params.width = targetW;
                params.height = targetH;
            }
        }
        moveToAdjustPreviewDefaultPosition();
    }

    private void moveToAdjustPreviewDefaultPosition() {
        if (params == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        } catch (Exception e) {
            metrics = getResources().getDisplayMetrics();
        }
        int margin = dpToPx(16);
        int w = params.width > 0 ? params.width : dpToPx(320);
        int h = params.height > 0 ? params.height : dpToPx(180);
        int x = metrics.widthPixels - w - margin;
        int y = metrics.heightPixels - h - margin;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        setPosition(x, y);
    }

    public void setPosition(int x, int y) {
        if (params == null) return;
        params.x = x;
        params.y = y;
        if (getParent() != null) {
            try {
                updateWindowLayoutSafely();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    public void dismiss() {
        cancelRetryBind();
        stopCameraPreview();
        pendingShowAnimation = false;
        if (showAnimFallback != null) {
            mainHandler.removeCallbacks(showAnimFallback);
            showAnimFallback = null;
        }

        FloatingWindowDisplayHelper.dismissNow(this, getActiveWindowManager(), params);
        if (attachedOverlay != null) {
            attachedOverlay.markDetached();
            attachedOverlay = null;
        }
    }

    private void scheduleRetryBind() {
        cancelRetryBind();
        retryBindCount++;
        long delayMs;
        if (retryBindCount <= 10) {
            delayMs = 500;
        } else if (retryBindCount <= 30) {
            delayMs = 1000;
        } else {
            delayMs = 3000;
        }
        retryBindRunnable = () -> {
            if (getParent() == null) return;
            if (textureView == null || !textureView.isAvailable()) {
                scheduleRetryBind();
                return;
            }
            if (cachedSurface == null || !cachedSurface.isValid()) {
                android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
                if (st == null) {
                    scheduleRetryBind();
                    return;
                }
                if (cachedSurface != null) {
                    try { cachedSurface.release(); } catch (Exception e) {}
                }
                cachedSurface = new Surface(st);
            }
            startCameraPreview(cachedSurface);
        };
        mainHandler.postDelayed(retryBindRunnable, delayMs);
    }

    private void cancelRetryBind() {
        if (retryBindRunnable != null) {
            mainHandler.removeCallbacks(retryBindRunnable);
            retryBindRunnable = null;
        }
        retryBindCount = 0;
    }

    private void applyStatusBarStyle() {
        if (statusBar == null) return;
        int style = appConfig.getBlindSpotStatusBarStyle();
        if (style == BlindSpotStatusBarView.STYLE_OFF) {
            statusBar.setVisibility(View.GONE);
        } else {
            statusBar.setVisibility(View.VISIBLE);
            statusBar.setAnimationStyle(style);
            statusBar.setEffectColor(appConfig.getBlindSpotStatusBarColor());
            int alpha = (int) (appConfig.getBlindSpotStatusBarBgOpacity() / 100f * 255);
            statusBar.setBackgroundColor(android.graphics.Color.argb(alpha, 0x1A, 0x1A, 0x1A));
        }
    }

    public void updateStatusLabel(String cameraPos) {
        if (statusBar != null) {
            applyStatusBarStyle();
            statusBar.setDirection(cameraPos);
        }
    }

    /**
     * 显示转向箭头
     * @param direction "left" 表示左转，"right" 表示右转
     */
    public void showTurnSignalArrow(String direction) {
        if (turnSignalArrowView != null) {
            turnSignalArrowView.showArrow(direction);
        }
    }

    /**
     * 隐藏转向箭头
     */
    public void hideTurnSignalArrow() {
        if (turnSignalArrowView != null) {
            turnSignalArrowView.hideArrow();
        }
    }

    /**
     * 更新悬浮窗位置和大小
     * @param x X坐标
     * @param y Y坐标
     * @param width 宽度
     * @param height 高度
     */
    public void updateBounds(int x, int y, int width, int height) {
        if (params != null && windowManager != null) {
            params.x = x;
            params.y = y;
            params.width = width;
            params.height = height;
            try {
                updateWindowLayoutSafely();
            } catch (Exception e) {
                AppLog.e(TAG, "更新悬浮窗位置失败: " + e.getMessage());
            }
        }
    }

    /**
     * 重新启动摄像头预览（用于应用从后台返回前台时）
     */
    public void restartCameraPreview() {
        AppLog.i(TAG, "重新启动摄像头预览: " + cameraPos);
        if (textureView != null && textureView.isAvailable()) {
            if (cachedSurface != null) {
                cachedSurface.release();
                cachedSurface = null;
            }
            cachedSurface = new Surface(textureView.getSurfaceTexture());
            startCameraPreview(cachedSurface);
        } else {
            AppLog.w(TAG, "TextureView 不可用，无法重新启动预览");
        }
    }
}
