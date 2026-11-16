package com.kooo.evcam.surround;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
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
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.surround.SurroundLayoutCalculator.SurroundLayoutSpec;
import com.kooo.evcam.surround.SurroundLayoutCalculator.ViewportBox;
import com.kooo.evcam.vehicle.SurroundSteeringAngleSource;
import com.kooo.evcam.vehicle.VehicleSignalHub;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot.Gear;

/**
 * 360 模式 A 复合容器视图：
 * - 底层仅在 activateModeA() 时按需创建 GLSurfaceView（EGL/GLES20 上下文），退出时立即销毁并解绑四路相机 OES Surface；
 * - 顶层对齐 Smartcam 模式 A 分视角叠加样式（左 360 鸟瞰、右上道路角标+可隐现切换按键、右下左轮/轮位切换卡/右轮）；
 * - 彻底移除“请留意周围环境”及全部前雷达提示文字与故障字样。
 */
public final class SurroundModeAView extends FrameLayout {

    public interface HostCallbacks {
        void onSurroundSurfacesReady();
    }

    private static final String TAG = "SurroundModeAView";
    private static final long TEARDOWN_SURFACE_DELAY_MS = 100L;

    private final AppConfig appConfig;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float density;
    private final int gapPx;

    @Nullable
    private HostCallbacks hostCallbacks;
    @Nullable
    private MultiCameraManager boundCameraManager;
    @Nullable
    private GLSurfaceView glSurfaceView;
    @Nullable
    private SurroundGlRenderer glRenderer;

    private volatile boolean active = false;
    private volatile boolean surfacesReady = false;
    @Nullable private Surface cachedFrontSurface;
    @Nullable private Surface cachedBackSurface;
    @Nullable private Surface cachedLeftSurface;
    @Nullable private Surface cachedRightSurface;
    @Nullable private SurfaceTexture cachedFrontSt;
    @Nullable private SurfaceTexture cachedBackSt;
    @Nullable private SurfaceTexture cachedLeftSt;
    @Nullable private SurfaceTexture cachedRightSt;

    @Nullable
    private SurroundViewMode manualTopOverride;
    @Nullable
    private SurroundViewMode manualBottomOverride;
    private boolean inReverseGear = false;
    private volatile float currentWheelAngleDeg = 0f;
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

    // 分视窗交互 UI 叠加层（仅在激活时可见，不含任何环境或前雷达文字）
    private final FrameLayout overlayRoot;
    private final FrameLayout topDetailOverlay;
    private final TextView tvTopBadge;
    private final LinearLayout topButtonsGroup;
    private final TextView btnTopFront;
    private final TextView btnTopRear;
    private final TextView btnTopDrive;

    private final FrameLayout bottomLeftOverlay;
    private final TextView tvBottomLeftBadge;
    private final FrameLayout bottomRightOverlay;
    private final TextView tvBottomRightBadge;
    private final LinearLayout bottomCenterCard;
    private final TextView btnWheelFront;
    private final TextView btnWheelSides;
    private final TextView btnWheelRear;
    private final TextView btnWheelCollapse;
    private final TextView btnWheelExpandFloating;

    private final VehicleSignalHub.Listener signalListener = snapshot -> mainHandler.post(() -> {
        if (snapshot == null) return;
        boolean reverseNow = snapshot.gear == Gear.REVERSE;
        if (reverseNow != inReverseGear) {
            inReverseGear = reverseNow;
            refreshLayoutAndRenderState();
        }
    });

    public SurroundModeAView(@NonNull Context context) {
        this(context, null);
    }

    public SurroundModeAView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        this.appConfig = new AppConfig(context);
        this.density = getResources().getDisplayMetrics().density;
        this.gapPx = Math.max(6, dp(10));
        setBackgroundColor(Color.parseColor("#0D1017"));

        this.overlayRoot = new FrameLayout(context);
        this.overlayRoot.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // ---- 右上详情叠加层 ----
        this.topDetailOverlay = createPaneContainer(context);
        this.tvTopBadge = createBadgeText(context, "前视 · 随档位");
        LayoutParams topBadgeLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        topBadgeLp.setMargins(dp(12), dp(12), dp(12), dp(12));
        topDetailOverlay.addView(tvTopBadge, topBadgeLp);

        this.topButtonsGroup = new LinearLayout(context);
        topButtonsGroup.setOrientation(LinearLayout.HORIZONTAL);
        topButtonsGroup.setPadding(dp(4), dp(4), dp(4), dp(4));
        topButtonsGroup.setBackground(createCapsuleBg(0xB8111622, dp(16), 0x4DFFFFFF));
        LayoutParams topBtnsLp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(34), Gravity.TOP | Gravity.END);
        topBtnsLp.setMargins(dp(12), dp(12), dp(12), dp(12));

        this.btnTopFront = createSwitchPill(context, "前视", v -> {
            manualTopOverride = SurroundViewMode.FRONT;
            refreshLayoutAndRenderState();
        });
        this.btnTopRear = createSwitchPill(context, "后视", v -> {
            manualTopOverride = SurroundViewMode.REAR;
            refreshLayoutAndRenderState();
        });
        this.btnTopDrive = createSwitchPill(context, "随档位", v -> {
            manualTopOverride = null;
            refreshLayoutAndRenderState();
        });
        topButtonsGroup.addView(btnTopFront);
        topButtonsGroup.addView(btnTopRear);
        topButtonsGroup.addView(btnTopDrive);
        topDetailOverlay.addView(topButtonsGroup, topBtnsLp);

        this.btnWheelExpandFloating = createSwitchPill(context, "展开轮位", v -> {
            appConfig.setSurroundWheelsCollapsed(false);
            refreshLayoutAndRenderState();
        });
        btnWheelExpandFloating.setBackground(createCapsuleBg(0xCC111622, dp(14), 0x4DFFFFFF));
        LayoutParams expandLp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(30), Gravity.BOTTOM | Gravity.END);
        expandLp.setMargins(dp(12), dp(12), dp(12), dp(12));
        topDetailOverlay.addView(btnWheelExpandFloating, expandLp);

        // ---- 右下左轮/左侧视叠加层 ----
        this.bottomLeftOverlay = createPaneContainer(context);
        this.tvBottomLeftBadge = createBadgeText(context, "左侧 · 前轮");
        LayoutParams blLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        blLp.setMargins(dp(10), dp(10), dp(10), dp(10));
        bottomLeftOverlay.addView(tvBottomLeftBadge, blLp);

        // ---- 右下右轮/右侧视叠加层 ----
        this.bottomRightOverlay = createPaneContainer(context);
        this.tvBottomRightBadge = createBadgeText(context, "右侧 · 前轮");
        LayoutParams brLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        brLp.setMargins(dp(10), dp(10), dp(10), dp(10));
        bottomRightOverlay.addView(tvBottomRightBadge, brLp);

        // ---- 右下中央轮位切换卡 ----
        this.bottomCenterCard = new LinearLayout(context);
        bottomCenterCard.setOrientation(LinearLayout.VERTICAL);
        bottomCenterCard.setGravity(Gravity.CENTER);
        bottomCenterCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        bottomCenterCard.setBackground(createCapsuleBg(0xE6141923, dp(14), 0x33FFFFFF));

        this.btnWheelFront = createCenterWheelBtn(context, "前轮", v -> {
            manualBottomOverride = SurroundViewMode.FRONT_WHEELS;
            appConfig.setSurroundDetailViews(appConfig.getSurroundTopView(), SurroundViewMode.FRONT_WHEELS);
            refreshLayoutAndRenderState();
        });
        this.btnWheelRear = createCenterWheelBtn(context, "后轮", v -> {
            manualBottomOverride = SurroundViewMode.REAR_WHEELS;
            appConfig.setSurroundDetailViews(appConfig.getSurroundTopView(), SurroundViewMode.REAR_WHEELS);
            refreshLayoutAndRenderState();
        });
        this.btnWheelSides = createCenterWheelBtn(context, "双侧", v -> {
            manualBottomOverride = SurroundViewMode.SIDES;
            appConfig.setSurroundDetailViews(appConfig.getSurroundTopView(), SurroundViewMode.SIDES);
            refreshLayoutAndRenderState();
        });
        this.btnWheelCollapse = createCenterWheelBtn(context, "收起轮位", v -> {
            appConfig.setSurroundWheelsCollapsed(true);
            refreshLayoutAndRenderState();
        });
        bottomCenterCard.addView(btnWheelFront);
        bottomCenterCard.addView(btnWheelRear);
        bottomCenterCard.addView(btnWheelSides);
        bottomCenterCard.addView(btnWheelCollapse);

        overlayRoot.addView(topDetailOverlay);
        overlayRoot.addView(bottomLeftOverlay);
        overlayRoot.addView(bottomRightOverlay);
        overlayRoot.addView(bottomCenterCard);
        addView(overlayRoot);
    }

    public void setHostCallbacks(@Nullable HostCallbacks callbacks) {
        this.hostCallbacks = callbacks;
    }

    public boolean isSurfacesReady() {
        return active && surfacesReady;
    }

    /** 仅当模式 A 在前台激活时创建 360 GL 上下文，绝不在模式 B/C/后台驻留。 */
    public void activateModeA(@Nullable MultiCameraManager cameraManager) {
        this.boundCameraManager = cameraManager;
        if (active) {
            refreshFromPreferences();
            return;
        }
        active = true;
        surfacesReady = false;

        VehicleSignalSnapshot current = VehicleSignalHub.getInstance().getSnapshot();
        inReverseGear = current != null && current.gear == Gear.REVERSE;
        currentWheelAngleDeg = appConfig.getPreviewGuidanceLastAngleDeg();
        VehicleSignalHub.getInstance().addListener(signalListener);

        GLSurfaceView glView = new GLSurfaceView(getContext());
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(false);

        SurroundGlRenderer renderer = new SurroundGlRenderer(getContext(), glView,
                (front, back, left, right, stFront, stBack, stLeft, stRight) -> mainHandler.post(() -> {
                    if (!active) {
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
                    if (hostCallbacks != null) {
                        hostCallbacks.onSurroundSurfacesReady();
                    }
                }));
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        this.glSurfaceView = glView;
        this.glRenderer = renderer;
        addView(glView, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        refreshFromPreferences();
        AppLog.i(TAG, "Mode A 360 GL context created on demand");
    }

    /** 更新绑定的 MultiCameraManager 引用（用于相机延迟初始化完成后挂载 4 路 OES Surface）。 */
    public void bindCameraManagerIfActive(@Nullable MultiCameraManager cameraManager) {
        this.boundCameraManager = cameraManager;
        if (!active || cameraManager == null) {
            return;
        }
        if (surfacesReady && cachedFrontSurface != null && cachedFrontSurface.isValid()) {
            cameraManager.setSurroundPreviewSurfaces(
                    cachedFrontSurface, cachedFrontSt,
                    cachedBackSurface, cachedBackSt,
                    cachedLeftSurface, cachedLeftSt,
                    cachedRightSurface, cachedRightSt
            );
        }
        if (glSurfaceView != null) {
            glSurfaceView.queueEvent(() -> {
                if (active && glRenderer != null) {
                    glRenderer.requestRenderSafe();
                }
            });
        }
    }

    /** 退出模式 A、打开全屏他页或退后台时立即解绑相机 Surface 并销毁 GL 上下文。 */
    public void deactivateModeA(@Nullable MultiCameraManager cameraManager) {
        MultiCameraManager targetManager = cameraManager != null ? cameraManager : this.boundCameraManager;
        if (targetManager != null) {
            targetManager.clearSurroundPreviewSurfaces();
        }
        cachedFrontSurface = null;
        cachedBackSurface = null;
        cachedLeftSurface = null;
        cachedRightSurface = null;
        cachedFrontSt = null;
        cachedBackSt = null;
        cachedLeftSt = null;
        cachedRightSt = null;
        if (!active && glSurfaceView == null) {
            return;
        }
        active = false;
        surfacesReady = false;
        VehicleSignalHub.getInstance().removeListener(signalListener);
        releaseSteeringAngleSubscription();

        final GLSurfaceView viewToRelease = this.glSurfaceView;
        final SurroundGlRenderer rendererToRelease = this.glRenderer;
        this.glSurfaceView = null;
        this.glRenderer = null;

        if (viewToRelease != null) {
            mainHandler.postDelayed(() -> {
                if (rendererToRelease != null) {
                    viewToRelease.queueEvent(rendererToRelease::releaseGlResources);
                }
                viewToRelease.onPause();
                removeView(viewToRelease);
                AppLog.i(TAG, "Mode A 360 GL context destroyed cleanly");
            }, TEARDOWN_SURFACE_DELAY_MS);
        }
    }

    public void refreshFromPreferences() {
        syncSteeringAngleSubscription();
        if (glRenderer != null) {
            glRenderer.reloadConfigurationFromPrefs();
        }
        refreshLayoutAndRenderState();
    }

    public void updateSteeringWheelAngle(float angleDeg) {
        float clamped = Math.max(-45f, Math.min(45f, angleDeg));
        this.currentWheelAngleDeg = clamped;
        SurroundGlRenderer renderer = this.glRenderer;
        if (active && renderer != null) {
            renderer.updateGuidanceAngle(clamped);
        } else {
            refreshLayoutAndRenderState();
        }
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
        releaseSteeringAngleSubscription();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            post(this::refreshLayoutAndRenderState);
        }
    }

    private void refreshLayoutAndRenderState() {
        int w = getWidth();
        int h = getHeight();
        SurroundViewMode configuredTop = appConfig.getSurroundTopView();
        SurroundViewMode configuredBottom = appConfig.getSurroundBottomView();
        boolean wheelsCollapsed = appConfig.isSurroundWheelsCollapsed();
        boolean stretchRoad = appConfig.isSurroundStretchRoadViews();
        boolean guidanceEnabled = appConfig.isPreviewGuidanceEnabled();
        boolean showOverlayButtons = appConfig.isSurroundOverlayButtonsVisible();

        SurroundViewMode effTop = SurroundViewMode.resolveEffectiveTopView(
                configuredTop, inReverseGear, manualTopOverride);
        SurroundViewMode effBottom = SurroundViewMode.resolveEffectiveBottomView(
                configuredTop, configuredBottom, inReverseGear, manualBottomOverride);

        boolean followingGear = (manualTopOverride == null && configuredTop == SurroundViewMode.DRIVE)
                || manualTopOverride == SurroundViewMode.DRIVE;
        tvTopBadge.setText(SurroundViewMode.formatTopBadgeText(effTop, followingGear, inReverseGear));

        topButtonsGroup.setVisibility(showOverlayButtons ? View.VISIBLE : View.GONE);
        styleSwitchPillSelection(btnTopFront, manualTopOverride == SurroundViewMode.FRONT
                || (manualTopOverride == null && configuredTop == SurroundViewMode.FRONT));
        styleSwitchPillSelection(btnTopRear, manualTopOverride == SurroundViewMode.REAR
                || (manualTopOverride == null && configuredTop == SurroundViewMode.REAR));
        styleSwitchPillSelection(btnTopDrive, followingGear);

        btnWheelExpandFloating.setVisibility(
                (wheelsCollapsed && showOverlayButtons) ? View.VISIBLE : View.GONE);

        if (effBottom == SurroundViewMode.REAR_WHEELS) {
            tvBottomLeftBadge.setText("左侧 · 后轮");
            tvBottomRightBadge.setText("右侧 · 后轮");
        } else if (effBottom == SurroundViewMode.SIDES) {
            tvBottomLeftBadge.setText("左侧视");
            tvBottomRightBadge.setText("右侧视");
        } else {
            tvBottomLeftBadge.setText("左侧 · 前轮");
            tvBottomRightBadge.setText("右侧 · 前轮");
        }
        styleCenterWheelBtn(btnWheelFront, effBottom == SurroundViewMode.FRONT_WHEELS);
        styleCenterWheelBtn(btnWheelRear, effBottom == SurroundViewMode.REAR_WHEELS);
        styleCenterWheelBtn(btnWheelSides, effBottom == SurroundViewMode.SIDES);

        if (glRenderer != null) {
            glRenderer.updateRenderState(
                    effTop, effBottom, wheelsCollapsed, stretchRoad,
                    guidanceEnabled, currentWheelAngleDeg, inReverseGear, gapPx);
        }

        if (w <= 0 || h <= 0) {
            return;
        }
        SurroundLayoutSpec layout = SurroundLayoutCalculator.calculate(
                w, h,
                effBottom,
                wheelsCollapsed,
                appConfig.getSurroundGroundWidthCm(),
                appConfig.getSurroundGroundLengthCm(),
                appConfig.getSurroundGroundFrontExtensionCm(),
                gapPx
        );

        applyBox(topDetailOverlay, layout.topDetailBox, true);
        if (wheelsCollapsed) {
            bottomLeftOverlay.setVisibility(View.GONE);
            bottomRightOverlay.setVisibility(View.GONE);
            bottomCenterCard.setVisibility(View.GONE);
        } else {
            applyBox(bottomLeftOverlay, layout.bottomLeftBox, true);
            applyBox(bottomRightOverlay, layout.bottomRightBox, true);
            boolean showCenter = showOverlayButtons && !layout.bottomCenterCardBox.isEmpty();
            applyBox(bottomCenterCard, layout.bottomCenterCardBox, showCenter);
        }
    }

    private void applyBox(@NonNull View child, @NonNull ViewportBox box, boolean visible) {
        if (!visible || box.isEmpty()) {
            child.setVisibility(View.GONE);
            return;
        }
        child.setVisibility(View.VISIBLE);
        LayoutParams lp = (LayoutParams) child.getLayoutParams();
        if (lp == null) {
            lp = new LayoutParams(box.width, box.height);
        }
        lp.width = box.width;
        lp.height = box.height;
        lp.leftMargin = box.left;
        lp.topMargin = box.top;
        lp.gravity = Gravity.TOP | Gravity.START;
        child.setLayoutParams(lp);
    }

    private FrameLayout createPaneContainer(@NonNull Context context) {
        FrameLayout frame = new FrameLayout(context);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                if (view.getWidth() > 0 && view.getHeight() > 0) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            }
        });
        frame.setClipToOutline(true);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setCornerRadius(dp(12));
        border.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        frame.setBackground(border);
        return frame;
    }

    private TextView createBadgeText(@NonNull Context context, @NonNull String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        tv.setBackground(createCapsuleBg(0xB00F141E, dp(10), 0x30FFFFFF));
        return tv;
    }

    private TextView createSwitchPill(@NonNull Context context,
                                    @NonNull String label,
                                    @NonNull OnClickListener clickListener) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(clickListener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMarginEnd(dp(2));
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView createCenterWheelBtn(@NonNull Context context,
                                          @NonNull String label,
                                          @NonNull OnClickListener clickListener) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(clickListener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void styleSwitchPillSelection(@NonNull TextView tv, boolean selected) {
        if (selected) {
            tv.setBackground(createCapsuleBg(ContextCompat.getColor(getContext(), R.color.button_accent), dp(12), 0));
            tv.setTextColor(ContextCompat.getColor(getContext(), R.color.ui_on_accent));
            tv.setTypeface(null, Typeface.BOLD);
        } else {
            tv.setBackground(null);
            tv.setTextColor(0xFFE2E8F0);
            tv.setTypeface(null, Typeface.NORMAL);
        }
    }

    private void styleCenterWheelBtn(@NonNull TextView tv, boolean selected) {
        if (selected) {
            tv.setBackground(createCapsuleBg(ContextCompat.getColor(getContext(), R.color.button_accent), dp(10), 0));
            tv.setTextColor(ContextCompat.getColor(getContext(), R.color.ui_on_accent));
            tv.setTypeface(null, Typeface.BOLD);
        } else {
            tv.setBackground(createCapsuleBg(0xFF1F2735, dp(10), 0x26FFFFFF));
            tv.setTextColor(0xFFE2E8F0);
            tv.setTypeface(null, Typeface.NORMAL);
        }
    }

    private GradientDrawable createCapsuleBg(int fillColor, int radiusPx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(fillColor);
        if (strokeColor != 0) {
            d.setStroke(Math.max(1, dp(1)), strokeColor);
        }
        return d;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
