package com.kooo.evcam.surround;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.vehicle.SurroundSteeringAngleSource;
import com.kooo.evcam.vehicle.VehicleSignalHub;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot.Gear;

import java.util.Locale;

/**
 * 4.3 标定校准专用的按需 360 实时预览容器：
 * - 仅在进入调校页时创建 GLSurfaceView 与 4 路 OES Surface，默认展示“鸟瞰大图”以清晰观察地面拼接变化，支持切到“完整模式 A”；
 * - 任何退出/退后台途径触发 stopPreview() 时，按自身持有的 Surface 引用精确解绑相机并销毁 GL 与订阅资源，不误伤主界面模式 A。
 */
public final class SurroundCalibrationPreviewView extends FrameLayout {

    private static final String TAG = "SurroundCalibPreview";
    private static final long TEARDOWN_SURFACE_DELAY_MS = 120L;

    private final AppConfig appConfig;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float density;
    private final int gapPx;

    @Nullable
    private MultiCameraManager boundCameraManager;
    @Nullable
    private GLSurfaceView glSurfaceView;
    @Nullable
    private SurroundGlRenderer glRenderer;

    private volatile boolean active = false;
    private volatile boolean surfacesReady = false;
    private boolean birdOnlyMode = true;
    private boolean inReverseGear = false;
    private volatile float currentWheelAngleDeg = 0f;

    @Nullable private Surface cachedFrontSurface;
    @Nullable private Surface cachedBackSurface;
    @Nullable private Surface cachedLeftSurface;
    @Nullable private Surface cachedRightSurface;
    @Nullable private SurfaceTexture cachedFrontSt;
    @Nullable private SurfaceTexture cachedBackSt;
    @Nullable private SurfaceTexture cachedLeftSt;
    @Nullable private SurfaceTexture cachedRightSt;

    @Nullable
    private SurroundSteeringAngleSource.Lease steeringAngleLease;
    private final SurroundSteeringAngleSource.AngleListener steeringAngleListener = angleDeg -> {
        float clamped = Math.max(-45f, Math.min(45f, angleDeg));
        currentWheelAngleDeg = clamped;
        SurroundGlRenderer renderer = this.glRenderer;
        if (active && renderer != null) {
            renderer.updateGuidanceAngle(clamped);
        }
    };

    private final VehicleSignalHub.Listener signalListener = snapshot -> mainHandler.post(() -> {
        if (!active || snapshot == null) return;
        boolean reverseNow = snapshot.gear == Gear.REVERSE;
        if (reverseNow != inReverseGear) {
            inReverseGear = reverseNow;
            pushRenderState();
        }
    });

    private final TextView tvLiveBadge;
    private final TextView tvHintBadge;
    private final TextView tvEmptyStatus;

    public SurroundCalibrationPreviewView(@NonNull Context context) {
        this(context, null);
    }

    public SurroundCalibrationPreviewView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.appConfig = new AppConfig(context);
        this.density = getResources().getDisplayMetrics().density;
        this.gapPx = Math.max(6, dp(10));
        setBackgroundColor(Color.parseColor("#0B0F17"));

        this.tvLiveBadge = createBadge(context, 12, true);
        LayoutParams topBadgeLp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        topBadgeLp.setMargins(dp(14), dp(12), dp(14), dp(12));
        addView(tvLiveBadge, topBadgeLp);

        this.tvHintBadge = createBadge(context, 11, false);
        this.tvHintBadge.setText("实时调节立即生效 · 退出本页自动销毁预览");
        LayoutParams hintLp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLp.setMargins(dp(14), dp(12), dp(14), dp(12));
        addView(tvHintBadge, hintLp);

        this.tvEmptyStatus = new TextView(context);
        this.tvEmptyStatus.setTextColor(0xFFD0D7E2);
        this.tvEmptyStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        this.tvEmptyStatus.setGravity(Gravity.CENTER);
        this.tvEmptyStatus.setPadding(dp(24), dp(16), dp(24), dp(16));
        this.tvEmptyStatus.setVisibility(View.GONE);
        LayoutParams emptyLp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(tvEmptyStatus, emptyLp);

        updateBadgeText();
    }

    public boolean isPreviewActive() {
        return active;
    }

    public boolean isBirdOnlyMode() {
        return birdOnlyMode;
    }

    public void setBirdOnlyMode(boolean birdOnly) {
        if (this.birdOnlyMode == birdOnly) {
            return;
        }
        this.birdOnlyMode = birdOnly;
        SurroundGlRenderer renderer = this.glRenderer;
        if (renderer != null) {
            renderer.setBirdOnlyPreview(birdOnly);
        }
        updateBadgeText();
    }

    /** 按需启动实时预览；若无可用 CameraManager 则展示清晰状态说明且不创建 GL 资源。 */
    public void startPreview(@Nullable MultiCameraManager cameraManager, @Nullable String unavailableReason) {
        this.boundCameraManager = cameraManager;
        if (cameraManager == null) {
            stopPreview(true);
            tvEmptyStatus.setText(unavailableReason != null && !unavailableReason.isEmpty()
                    ? unavailableReason
                    : "未检测到可用相机或尚未授予相机权限，暂时无法显示实时画面");
            tvEmptyStatus.setVisibility(View.VISIBLE);
            tvLiveBadge.setVisibility(View.GONE);
            return;
        }
        tvEmptyStatus.setVisibility(View.GONE);
        tvLiveBadge.setVisibility(View.VISIBLE);

        if (active) {
            bindSurfacesIfReady(cameraManager);
            refreshFromPreferences(false);
            return;
        }

        active = true;
        surfacesReady = false;

        VehicleSignalSnapshot current = VehicleSignalHub.getInstance().getSnapshot();
        inReverseGear = current != null && current.gear == Gear.REVERSE;
        currentWheelAngleDeg = appConfig.getPreviewGuidanceLastAngleDeg();
        VehicleSignalHub.getInstance().addListener(signalListener);
        syncSteeringAngleSubscription();

        GLSurfaceView glView = new GLSurfaceView(getContext());
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(false);

        final SurroundGlRenderer[] createdHolder = new SurroundGlRenderer[1];
        SurroundGlRenderer renderer = new SurroundGlRenderer(getContext(), glView,
                (front, back, left, right, stFront, stBack, stLeft, stRight) -> mainHandler.post(() -> {
                    if (!active || glRenderer != createdHolder[0]) {
                        return;
                    }
                    cachedFrontSurface = front;
                    cachedBackSurface = back;
                    cachedLeftSurface = left;
                    cachedRightSurface = right;
                    cachedFrontSt = stFront;
                    cachedBackSt = stBack;
                    cachedLeftSt = stLeft;
                    cachedRightSt = stRight;
                    surfacesReady = true;
                    if (boundCameraManager != null) {
                        boundCameraManager.setSurroundPreviewSurfaces(
                                front, stFront,
                                back, stBack,
                                left, stLeft,
                                right, stRight
                        );
                    }
                }));
        createdHolder[0] = renderer;
        renderer.setBirdOnlyPreview(birdOnlyMode);
        glView.setRenderer(renderer);
        renderer.markRendererAttached();
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        this.glSurfaceView = glView;
        this.glRenderer = renderer;
        addView(glView, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        refreshFromPreferences(false);
        AppLog.i(TAG, "4.3 live calibration GL preview started (birdOnly=" + birdOnlyMode + ")");
    }

    /** 刷新配置到 GL 渲染器；调节尺寸滑块时传 false 可复用缓存的车模和校准矩阵，避免卡顿。 */
    public void refreshFromPreferences(boolean forceReloadAssets) {
        syncSteeringAngleSubscription();
        SurroundGlRenderer renderer = this.glRenderer;
        if (renderer != null) {
            renderer.reloadConfigurationFromPrefs(forceReloadAssets);
        }
        pushRenderState();
        updateBadgeText();
    }

    /** 立即解绑自身持有的 4 路 OES Surface 并销毁 GL 上下文与信号订阅。 */
    public void stopPreview(boolean immediateSurfaceRelease) {
        final Surface front = cachedFrontSurface;
        final Surface back = cachedBackSurface;
        final Surface left = cachedLeftSurface;
        final Surface right = cachedRightSurface;
        cachedFrontSurface = null;
        cachedBackSurface = null;
        cachedLeftSurface = null;
        cachedRightSurface = null;
        cachedFrontSt = null;
        cachedBackSt = null;
        cachedLeftSt = null;
        cachedRightSt = null;

        if (boundCameraManager != null) {
            boundCameraManager.clearSurroundPreviewSurfacesIfMatching(front, back, left, right);
        }

        boolean hadResources = active || glSurfaceView != null || glRenderer != null;
        active = false;
        surfacesReady = false;
        VehicleSignalHub.getInstance().removeListener(signalListener);
        releaseSteeringAngleSubscription();

        final GLSurfaceView viewToRelease = this.glSurfaceView;
        final SurroundGlRenderer rendererToRelease = this.glRenderer;
        this.glSurfaceView = null;
        this.glRenderer = null;

        if (rendererToRelease != null) {
            rendererToRelease.markStopping();
        }
        if (viewToRelease != null) {
            viewToRelease.onPause();
            removeView(viewToRelease);
        }
        if (rendererToRelease != null) {
            if (immediateSurfaceRelease) {
                rendererToRelease.releaseSurfacesFallback();
            } else {
                // 等待 120ms 确保 SingleCamera backgroundHandler 完成动态摘除后再释放 Surface/SurfaceTexture
                mainHandler.postDelayed(rendererToRelease::releaseSurfacesFallback, TEARDOWN_SURFACE_DELAY_MS);
            }
        }
        if (hadResources) {
            AppLog.i(TAG, "4.3 live calibration GL preview stopped and released");
        }
    }

    private void bindSurfacesIfReady(@NonNull MultiCameraManager manager) {
        if (surfacesReady && cachedFrontSurface != null && cachedFrontSurface.isValid()) {
            manager.setSurroundPreviewSurfaces(
                    cachedFrontSurface, cachedFrontSt,
                    cachedBackSurface, cachedBackSt,
                    cachedLeftSurface, cachedLeftSt,
                    cachedRightSurface, cachedRightSt
            );
        }
    }

    private void pushRenderState() {
        SurroundGlRenderer renderer = this.glRenderer;
        if (renderer == null) return;
        SurroundViewMode effTop = SurroundViewMode.resolveEffectiveTopView(
                appConfig.getSurroundTopView(), inReverseGear, null);
        SurroundViewMode effBottom = SurroundViewMode.resolveEffectiveBottomView(
                appConfig.getSurroundTopView(), appConfig.getSurroundBottomView(), inReverseGear, null);
        renderer.setBirdOnlyPreview(birdOnlyMode);
        renderer.updateRenderState(
                effTop,
                effBottom,
                appConfig.isSurroundWheelsCollapsed(),
                appConfig.isSurroundStretchRoadViews(),
                appConfig.isPreviewGuidanceEnabled(),
                currentWheelAngleDeg,
                inReverseGear,
                gapPx);
    }

    private void updateBadgeText() {
        SurroundCalibrationProfile profile = SurroundCalibrationManager.resolveActiveProfile(appConfig);
        String modeLabel = birdOnlyMode ? "鸟瞰大图" : "完整模式 A";
        String calibLabel = profile.isCustomCalibration ? "本地校准" : "E5 内置";
        String tunedSuffix = profile.hasExtrinsicAdjustments ? " · 已微调" : "";
        tvLiveBadge.setText(String.format(
                Locale.US,
                "%s · %s%s · %d×%d (+%d) cm",
                modeLabel,
                calibLabel,
                tunedSuffix,
                appConfig.getSurroundGroundWidthCm(),
                appConfig.getSurroundGroundLengthCm(),
                appConfig.getSurroundGroundFrontExtensionCm()));
    }

    private void syncSteeringAngleSubscription() {
        boolean wantSteering = active && appConfig.isPreviewGuidanceEnabled();
        if (wantSteering) {
            if (steeringAngleLease == null) {
                steeringAngleLease = SurroundSteeringAngleSource.acquire(steeringAngleListener);
            }
        } else {
            releaseSteeringAngleSubscription();
        }
    }

    private void releaseSteeringAngleSubscription() {
        if (steeringAngleLease != null) {
            steeringAngleLease.close();
            steeringAngleLease = null;
            appConfig.setPreviewGuidanceLastAngleDeg(currentWheelAngleDeg);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (active || glSurfaceView != null || glRenderer != null) {
            stopPreview(true);
        } else {
            VehicleSignalHub.getInstance().removeListener(signalListener);
            releaseSteeringAngleSubscription();
        }
        super.onDetachedFromWindow();
    }

    private TextView createBadge(@NonNull Context context, int textSp, boolean bold) {
        TextView tv = new TextView(context);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        tv.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(10));
        bg.setColor(0xBF0F141E);
        bg.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        tv.setBackground(bg);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
