package com.kooo.evcam.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.BlindSpotService;
import com.kooo.evcam.KeepAliveAccessibilityService;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.service.OverlayAvoidancePolicy;
import com.kooo.evcam.vehicle.VehicleSignalHub;

/**
 * 4.2 独立预览悬浮窗（PreviewPresentation.WINDOW）。
 *
 * 特点：
 * 1. 使用 OverlayWindowParamsFactory 非抢焦、非模态参数，支持 TYPE_ACCESSIBILITY_OVERLAY（2032）
 *    并能在无障碍解绑时安全降级到 TYPE_APPLICATION_OVERLAY（2038）；
 * 2. 复用现有 SingleCamera.setFullscreenPreviewSurface 零额外编码链路输出画面，不与四路录制抢占流；
 * 3. 支持前/后/左/右快捷切角、顶部拖移、右下角拉伸改尺寸及参数持久化，关闭时不拉起 MainActivity。
 */
public final class StandalonePreviewWindow {

    private static final String TAG = "StandalonePreviewWindow";
    private static final int MIN_WIDTH_PX = 360;
    private static final int MIN_HEIGHT_PX = 240;

    public interface Callbacks {
        void onReturnToApp();
        void onClosed();
    }

    private static StandalonePreviewWindow activeInstance;

    private final Context appContext;
    private final AppConfig appConfig;
    private final WindowManager fallbackWm;
    private final Callbacks callbacks;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final VehicleSignalHub.Listener vehicleSignalListener = snapshot -> {
        if (OverlayAvoidancePolicy.isReverseGear(snapshot)) {
            dismissForAvoidanceOnMainThread();
        }
    };
    private final KeepAliveAccessibilityService.ForegroundWindowListener foregroundWindowListener =
            this::onForegroundWindowChanged;

    private void onForegroundWindowChanged(String pkg, String cls) {
        if (OverlayAvoidancePolicy.matchesAvmTarget(appConfig.getAvmAvoidanceActivity(), pkg, cls)) {
            dismissForAvoidanceOnMainThread();
        }
    }

    private View rootView;
    private TextureView textureView;
    private TextView titleView;
    private Button btnFront;
    private Button btnBack;
    private Button btnLeft;
    private Button btnRight;
    private OverlayWindowParamsFactory.AttachedOverlay attachedOverlay;

    private String currentCameraPos = "front";
    private SingleCamera boundCamera;
    private SurfaceTexture activeSurfaceTexture;
    private Surface activeSurface;
    private boolean showing = false;

    private StandalonePreviewWindow(Context context, Callbacks callbacks) {
        this.appContext = context.getApplicationContext();
        this.appConfig = new AppConfig(this.appContext);
        this.fallbackWm = (WindowManager) this.appContext.getSystemService(Context.WINDOW_SERVICE);
        this.callbacks = callbacks;
    }

    public static synchronized boolean isShowing() {
        return activeInstance != null && activeInstance.showing;
    }

    public static synchronized void show(Context context, String cameraPosition, Callbacks callbacks) {
        if (context == null) {
            return;
        }
        AppConfig config = new AppConfig(context.getApplicationContext());
        if (!WakeUpHelper.hasOverlayPermission(context)
                && !OverlayWindowParamsFactory.shouldUseAccessibilityOverlay(context)) {
            Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            WakeUpHelper.requestOverlayPermission(context);
            return;
        }
        if (OverlayAvoidancePolicy.isReverseGear(VehicleSignalHub.getInstance().getSnapshot())) {
            AppLog.i(TAG, "当前处于倒挡(R)，跳过打开独立预览悬浮窗以避免与原车 360 争抢显示");
            return;
        }
        String resolvedPos = normalizeCamera(cameraPosition, config.getStandaloneWindowCamera());
        if (activeInstance != null && activeInstance.showing) {
            activeInstance.switchCamera(resolvedPos);
            return;
        }
        StandalonePreviewWindow window = new StandalonePreviewWindow(context, callbacks);
        activeInstance = window;
        window.openInternal(resolvedPos);
    }

    public static synchronized void dismiss(boolean notifyClosed) {
        if (activeInstance != null) {
            StandalonePreviewWindow target = activeInstance;
            activeInstance = null;
            target.closeInternal(notifyClosed);
        }
    }

    public static synchronized void refreshTransformIfShowing() {
        if (activeInstance != null && activeInstance.showing) {
            activeInstance.applyPreviewTransform();
        }
    }

    private static String normalizeCamera(String candidate, String saved) {
        if ("front".equals(candidate) || "back".equals(candidate)
                || "left".equals(candidate) || "right".equals(candidate)) {
            return candidate;
        }
        if ("front".equals(saved) || "back".equals(saved)
                || "left".equals(saved) || "right".equals(saved)) {
            return saved;
        }
        return "front";
    }

    private void openInternal(String cameraPos) {
        this.currentCameraPos = cameraPos;
        appConfig.setStandaloneWindowCamera(cameraPos);

        LayoutInflater inflater = LayoutInflater.from(appContext);
        rootView = inflater.inflate(R.layout.view_standalone_preview_window, null);
        textureView = rootView.findViewById(R.id.standalone_texture);
        titleView = rootView.findViewById(R.id.tv_standalone_title);
        View headerView = rootView.findViewById(R.id.standalone_header);
        View resizeHandle = rootView.findViewById(R.id.standalone_resize_handle);

        btnFront = rootView.findViewById(R.id.btn_standalone_cam_front);
        btnBack = rootView.findViewById(R.id.btn_standalone_cam_back);
        btnLeft = rootView.findViewById(R.id.btn_standalone_cam_left);
        btnRight = rootView.findViewById(R.id.btn_standalone_cam_right);

        Button btnReturn = rootView.findViewById(R.id.btn_standalone_return);
        Button btnClose = rootView.findViewById(R.id.btn_standalone_close);

        rootView.setClipToOutline(true);

        btnFront.setOnClickListener(v -> switchCamera("front"));
        btnBack.setOnClickListener(v -> switchCamera("back"));
        btnLeft.setOnClickListener(v -> switchCamera("left"));
        btnRight.setOnClickListener(v -> switchCamera("right"));
        btnReturn.setOnClickListener(v -> returnToMainApp());
        btnClose.setOnClickListener(v -> dismiss(true));

        DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
        int screenW = Math.max(dm.widthPixels, MIN_WIDTH_PX);
        int screenH = Math.max(dm.heightPixels, MIN_HEIGHT_PX);

        int width = Math.max(MIN_WIDTH_PX, Math.min(appConfig.getStandaloneWindowWidth(), screenW - 32));
        int height = Math.max(MIN_HEIGHT_PX, Math.min(appConfig.getStandaloneWindowHeight(), screenH - 32));
        int x = Math.max(0, Math.min(appConfig.getStandaloneWindowX(), screenW - width));
        int y = Math.max(0, Math.min(appConfig.getStandaloneWindowY(), screenH - height));

        WindowManager.LayoutParams params = OverlayWindowParamsFactory.createMainParams(
                appContext, width, height, x, y, true);

        attachedOverlay = OverlayWindowParamsFactory.attachMainOverlay(
                appContext,
                fallbackWm,
                rootView,
                params,
                () -> closeInternal(true)
        );
        if (attachedOverlay == null || !attachedOverlay.isAttached()) {
            showing = false;
            Toast.makeText(appContext, "无法打开独立预览悬浮窗", Toast.LENGTH_SHORT).show();
            return;
        }
        showing = true;
        VehicleSignalHub.getInstance().addListener(vehicleSignalListener);
        KeepAliveAccessibilityService.registerForegroundWindowListener(foregroundWindowListener);
        updateCameraButtonsUi();

        setupDrag(headerView, screenW, screenH);
        setupResize(resizeHandle, screenW, screenH);
        setupSurfaceListener();
        AppLog.d(TAG, "独立预览悬浮窗已开启, camera=" + currentCameraPos);
    }

    private void setupDrag(View dragView, int screenW, int screenH) {
        dragView.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchStartX, touchStartY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (attachedOverlay == null) {
                    return false;
                }
                WindowManager.LayoutParams p = attachedOverlay.getParams();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = p.x;
                        startY = p.y;
                        touchStartX = event.getRawX();
                        touchStartY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int nx = startX + (int) (event.getRawX() - touchStartX);
                        int ny = startY + (int) (event.getRawY() - touchStartY);
                        p.x = Math.max(0, Math.min(nx, Math.max(0, screenW - p.width)));
                        p.y = Math.max(0, Math.min(ny, Math.max(0, screenH - p.height)));
                        attachedOverlay.updateLayout();
                        return true;
                    case MotionEvent.ACTION_UP:
                        appConfig.setStandaloneWindowPosition(p.x, p.y);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void setupResize(View handleView, int screenW, int screenH) {
        handleView.setOnTouchListener(new View.OnTouchListener() {
            private int startW, startH;
            private float touchStartX, touchStartY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (attachedOverlay == null) {
                    return false;
                }
                WindowManager.LayoutParams p = attachedOverlay.getParams();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startW = p.width;
                        startH = p.height;
                        touchStartX = event.getRawX();
                        touchStartY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int maxW = Math.max(MIN_WIDTH_PX, screenW - p.x);
                        int maxH = Math.max(MIN_HEIGHT_PX, screenH - p.y);
                        p.width = Math.max(MIN_WIDTH_PX, Math.min(startW + (int) (event.getRawX() - touchStartX), maxW));
                        p.height = Math.max(MIN_HEIGHT_PX, Math.min(startH + (int) (event.getRawY() - touchStartY), maxH));
                        attachedOverlay.updateLayout();
                        applyPreviewTransform();
                        return true;
                    case MotionEvent.ACTION_UP:
                        appConfig.setStandaloneWindowSize(p.width, p.height);
                        applyPreviewTransform();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void setupSurfaceListener() {
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                activeSurfaceTexture = surface;
                attachCameraSurface(surface);
                applyPreviewTransform();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                applyPreviewTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                clearBoundCameraSurface();
                activeSurfaceTexture = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
    }

    public void switchCamera(String cameraPos) {
        String normalized = normalizeCamera(cameraPos, currentCameraPos);
        if (normalized.equals(currentCameraPos) && boundCamera != null) {
            applyPreviewTransform();
            return;
        }
        currentCameraPos = normalized;
        appConfig.setStandaloneWindowCamera(normalized);
        updateCameraButtonsUi();
        if (activeSurfaceTexture != null) {
            attachCameraSurface(activeSurfaceTexture);
        }
        applyPreviewTransform();
    }

    private void attachCameraSurface(SurfaceTexture surfaceTexture) {
        clearBoundCameraSurface();
        MultiCameraManager cm = CameraManagerHolder.getInstance().getCameraManager();
        if (cm == null) {
            AppLog.w(TAG, "CameraManager 尚未初始化，无法绑定独立预览画面");
            return;
        }
        SingleCamera camera = cm.getCamera(currentCameraPos);
        if (camera == null) {
            AppLog.w(TAG, "未找到目标摄像头: " + currentCameraPos);
            return;
        }
        Size previewSize = camera.getPreviewSize();
        if (previewSize != null) {
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        }
        activeSurface = new Surface(surfaceTexture);
        boundCamera = camera;
        camera.setFullscreenPreviewSurface(activeSurface, surfaceTexture);
        if (!camera.isCameraOpened()) {
            camera.openCameraDeferred();
        }
    }

    private void clearBoundCameraSurface() {
        if (boundCamera != null) {
            try {
                boundCamera.clearFullscreenPreviewSurface();
            } catch (Exception e) {
                AppLog.w(TAG, "释放独立预览 Surface 异常: " + e.getMessage());
            }
            boundCamera = null;
        }
        if (activeSurface != null) {
            try {
                activeSurface.release();
            } catch (Exception ignored) {
            }
            activeSurface = null;
        }
    }

    private void applyPreviewTransform() {
        if (textureView == null) {
            return;
        }
        int rotation = 0;
        if (appConfig.needsCustomLayoutManager()) {
            rotation = appConfig.getCameraRotation(currentCameraPos);
        } else if (!AppConfig.CAR_MODEL_PHONE.equals(appConfig.getCarModel())) {
            if ("left".equals(currentCameraPos)) {
                rotation = 270;
            } else if ("right".equals(currentCameraPos)) {
                rotation = 90;
            }
        }
        PreviewTransformHelper.applyMainPreviewTransform(textureView, appContext, currentCameraPos, rotation);
    }

    private void updateCameraButtonsUi() {
        if (titleView != null) {
            titleView.setText("独立预览 · " + labelForCamera(currentCameraPos));
        }
        int camCount = appConfig.getCameraCount();
        if (btnBack != null) btnBack.setVisibility(camCount >= 2 ? View.VISIBLE : View.GONE);
        if (btnLeft != null) btnLeft.setVisibility(camCount >= 3 ? View.VISIBLE : View.GONE);
        if (btnRight != null) btnRight.setVisibility(camCount >= 4 ? View.VISIBLE : View.GONE);

        styleCameraBtn(btnFront, "front".equals(currentCameraPos));
        styleCameraBtn(btnBack, "back".equals(currentCameraPos));
        styleCameraBtn(btnLeft, "left".equals(currentCameraPos));
        styleCameraBtn(btnRight, "right".equals(currentCameraPos));
    }

    private static void styleCameraBtn(Button btn, boolean active) {
        if (btn == null) {
            return;
        }
        btn.setBackgroundColor(active ? Color.parseColor("#FF1E88E5") : Color.parseColor("#44FFFFFF"));
    }

    private static String labelForCamera(String pos) {
        if ("back".equals(pos)) return "后摄";
        if ("left".equals(pos)) return "左摄";
        if ("right".equals(pos)) return "右摄";
        return "前摄";
    }

    private void returnToMainApp() {
        Callbacks cb = this.callbacks;
        dismiss(false);
        if (cb != null) {
            cb.onReturnToApp();
            return;
        }
        Intent intent = new Intent(appContext, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        appContext.startActivity(intent);
    }

    private void dismissForAvoidanceOnMainThread() {
        mainHandler.post(() -> {
            if (showing) {
                AppLog.i(TAG, "检测到倒挡/原车 360，关闭独立预览悬浮窗以避免闪屏");
                dismiss(true);
            }
        });
    }

    private void closeInternal(boolean notifyClosed) {
        showing = false;
        mainHandler.removeCallbacksAndMessages(null);
        VehicleSignalHub.getInstance().removeListener(vehicleSignalListener);
        KeepAliveAccessibilityService.unregisterForegroundWindowListener(foregroundWindowListener);
        clearBoundCameraSurface();
        OverlayWindowParamsFactory.detachSafely(attachedOverlay);
        attachedOverlay = null;
        rootView = null;
        textureView = null;
        titleView = null;
        if (notifyClosed && callbacks != null) {
            callbacks.onClosed();
        }
        BlindSpotService.notifyExternalCameraWindowClosed(appContext);
    }
}
