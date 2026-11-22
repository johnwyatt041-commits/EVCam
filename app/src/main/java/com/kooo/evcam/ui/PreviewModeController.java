package com.kooo.evcam.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.AutoFitTextureView;
import com.kooo.evcam.BlindSpotService;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.surround.SurroundModeAView;
import com.kooo.evcam.vehicle.RecordingVehicleSource;
import com.kooo.evcam.vehicle.SurroundSteeringAngleSource;
import com.kooo.evcam.vehicle.VehicleSignalHub;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates top-bar preview modes (MODE_A 360 / MODE_B split / MODE_C triple / MODE_D dual-steering / MODE_E dual-sides),
 * fullscreen corner navigation, and background staging PhotoTexture providers.
 */
public final class PreviewModeController implements PreviewModeHost, MultiCameraManager.PhotoSurfaceProvider {

    public interface Host {
        void onDefaultPreviewModeChanged(@NonNull PreviewMode newDefaultMode);
        void onPreviewShortcutPreferencesChanged();
    }

    public interface Callbacks {
        void onBackShortcutClicked();
        void onSettingsShortcutClicked();
        void onToggleFullscreenClicked();
        void onApplyStagingTransform(@NonNull String cameraKey, @NonNull TextureView view,
                                     @Nullable Size previewSize, int width, int height);
        default void onSurroundSurfacesReady() {}
        default void onRebindVisibleTextureViews() {}
        default void onEnableMainPreviewClicked() {}
    }

    private static final String TAG = "PreviewModeController";
    public static final float MODE_D_STEER_DEADBAND_DEG = 1.2f;
    private static final long STAGING_RELEASE_DELAY_MS = 350L;

    private final Context context;
    private final AppConfig appConfig;
    private final EditablePreviewLayout previewLayout;
    @Nullable
    private final FrameLayout modeAContainer;
    @Nullable
    private final FrameLayout stagingContainer;
    @Nullable
    private final View shortcutsOverlay;
    @Nullable
    private final View cornerNavGroup;
    @Nullable
    private final TextView btnShortcutBack;
    @Nullable
    private final TextView btnShortcutSettings;
    @Nullable
    private final AppShellController shellController;
    private final Callbacks callbacks;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float density;

    @Nullable
    private MultiCameraManager cameraManager;
    @Nullable
    private SurroundModeAView surroundModeAView;
    @Nullable
    private View modeADisabledGuideView;
    private PreviewMode currentMode;
    private boolean surroundHostEligible = true;
    private boolean deferredRebindOnHostEligible = false;
    private boolean editorActive = false;
    private boolean opacityPreviewMode = false;
    private int modeCPrimaryIndex = PreviewLayoutModel.FRONT;
    private int modeDLeftPrimaryIndex = PreviewLayoutModel.FRONT;
    private int modeDRightSideIndex = PreviewLayoutModel.LEFT;
    @Nullable
    private SurroundSteeringAngleSource.Lease modeDSteeringLease;
    @Nullable
    private RecordingVehicleSource.Lease modeDVhalTurnLease;
    private boolean modeDTurnSignalRegistered = false;

    private final SurroundSteeringAngleSource.AngleListener modeDSteeringListener = angleDeg ->
            mainHandler.post(() -> applyModeDSteeringAngle(angleDeg));
    private final VehicleSignalHub.Listener modeDTurnSignalListener = snapshot ->
            mainHandler.post(() -> applyModeDTurnSignalSnapshot(snapshot));

    private final Map<String, TextureView> stagingViews = new LinkedHashMap<>();
    @Nullable
    private Runnable pendingStagingCleanup;

    public PreviewModeController(@NonNull Context context,
                                 @NonNull View rootView,
                                 @Nullable AppShellController shellController,
                                 @NonNull Callbacks callbacks) {
        this.context = context;
        this.appConfig = new AppConfig(context);
        this.shellController = shellController;
        this.callbacks = callbacks;
        this.density = context.getResources().getDisplayMetrics().density;

        this.previewLayout = rootView.findViewById(R.id.ui_preview_layout);
        this.modeAContainer = rootView.findViewById(R.id.ui_mode_a_surround_container);
        this.stagingContainer = rootView.findViewById(R.id.ui_photo_staging_container);
        this.shortcutsOverlay = rootView.findViewById(R.id.ui_preview_shortcuts_overlay);
        this.cornerNavGroup = rootView.findViewById(R.id.ui_shortcut_corner_nav_group);
        this.btnShortcutBack = rootView.findViewById(R.id.btn_shortcut_back);
        this.btnShortcutSettings = rootView.findViewById(R.id.btn_shortcut_settings);

        this.currentMode = PreviewMode.fromKey(appConfig.getPreviewMode());
        this.modeCPrimaryIndex = PreviewLayoutModel.FRONT;
        this.modeDLeftPrimaryIndex = PreviewLayoutModel.FRONT;
        this.modeDRightSideIndex = PreviewLayoutModel.LEFT;

        if (previewLayout != null) {
            previewLayout.setOnStateChangeListener(mask -> applyVisibilityMaskToCameras(false));
            previewLayout.setOnManualPrimaryToggleListener(this::toggleManualPrimary);
            previewLayout.setOnManualSideToggleListener(this::toggleModeDSideManual);
            previewLayout.setOnModeDSideLinkageCycleListener(this::cycleModeDSideLinkageMode);
            previewLayout.setModeDSideLinkageMode(appConfig.getModeDSideLinkageMode());
            previewLayout.setRoundedClipEnabled(appConfig.isPreviewRoundedClipEnabled());
        }
        if (modeAContainer != null) {
            modeAContainer.setVisibility(View.GONE);
        }

        wireShortcuts();
        applyStateToLayoutAndShell();
    }

    private void wireShortcuts() {
        if (btnShortcutBack != null) {
            btnShortcutBack.setShadowLayer(2f * density, 0f, 1f * density, 0xA0000000);
            btnShortcutBack.setOnClickListener(v -> callbacks.onBackShortcutClicked());
        }
        if (btnShortcutSettings != null) {
            btnShortcutSettings.setShadowLayer(2f * density, 0f, 1f * density, 0xA0000000);
            btnShortcutSettings.setOnClickListener(v -> callbacks.onSettingsShortcutClicked());
        }
        applyCornerNavBackground(appConfig.getPreviewOverlayBgAlphaPercent());
    }

    public void attachCameraManager(@Nullable MultiCameraManager manager) {
        if (this.cameraManager != null && this.cameraManager != manager) {
            this.cameraManager.setPhotoSurfaceProvider(null);
        }
        this.cameraManager = manager;
        if (manager != null) {
            manager.setPhotoSurfaceProvider(this);
            if (surroundModeAView != null) {
                surroundModeAView.bindCameraManagerIfActive(manager);
            }
            syncCameraAvailabilityToLayout();
            applyVisibilityMaskToCameras(true);
        }
    }

    /** 相机设备表建立完成后调用（确保冷启动处于模式 A 时绑定 4 路 OES Surface，同步未接入提示）。 */
    public void onCamerasInitialized() {
        syncCameraAvailabilityToLayout();
        if (surroundModeAView != null && cameraManager != null) {
            surroundModeAView.bindCameraManagerIfActive(cameraManager);
        }
        applyVisibilityMaskToCameras(true);
    }

    private void syncCameraAvailabilityToLayout() {
        if (previewLayout == null || cameraManager == null) return;
        String[] keys = {"front", "back", "left", "right"};
        for (String key : keys) {
            previewLayout.setCameraAvailable(key, cameraManager.getCamera(key) != null);
        }
    }

    /** 控制宿主是否满足前台且位于记录仪页；离开记录仪页或退后台立即销毁模式 A GL 上下文并释放模式 D 转向/转向灯租约。 */
    public void setSurroundHostEligible(boolean eligible) {
        if (this.surroundHostEligible == eligible) {
            if (eligible) {
                syncSurroundPreferences();
            } else {
                syncModeDSideSubscriptions();
            }
            return;
        }
        this.surroundHostEligible = eligible;
        applyStateToLayoutAndShell();
        syncModeDSideSubscriptions();
        applyVisibilityMaskToCameras(eligible);
        if (eligible && deferredRebindOnHostEligible) {
            deferredRebindOnHostEligible = false;
            if (currentMode != PreviewMode.MODE_A && appConfig.isMainPreviewEnabled()) {
                mainHandler.post(callbacks::onRebindVisibleTextureViews);
            }
        }
    }

    public boolean isReadyForCameraInit() {
        if (shouldActivateSurroundGl(currentMode, surroundHostEligible, appConfig.isMainPreviewEnabled())) {
            return surroundModeAView != null && surroundModeAView.isSurfacesReady();
        }
        return true;
    }

    public void start() {
        start(true);
    }

    public void start(boolean hostEligible) {
        surroundHostEligible = hostEligible;
        applyStateToLayoutAndShell();
        syncModeDSideSubscriptions();
        applyVisibilityMaskToCameras(hostEligible);
        refreshShortcutsOverlay();
    }

    public void stop() {
        surroundHostEligible = false;
        syncModeDSideSubscriptions();
        applyStateToLayoutAndShell();
        if (pendingStagingCleanup != null) {
            mainHandler.removeCallbacks(pendingStagingCleanup);
            pendingStagingCleanup = null;
        }
        clearStagingViewsNow();
    }

    @Override
    @Nullable
    public FrameLayout getModeAContainer() {
        return modeAContainer;
    }

    @Override
    @Nullable
    public EditablePreviewLayout getEditablePreviewLayout() {
        return previewLayout;
    }

    @Override
    @NonNull
    public PreviewMode getCurrentPreviewMode() {
        return currentMode;
    }

    @Override
    public void setPreviewMode(@NonNull PreviewMode mode) {
        PreviewMode normalized = normalizeSupportedMode(mode);
        boolean wasModeA = currentMode == PreviewMode.MODE_A;
        if (currentMode == normalized) {
            applyStateToLayoutAndShell();
            syncModeDSideSubscriptions();
            refreshShortcutsOverlay();
            return;
        }
        currentMode = normalized;
        appConfig.setPreviewMode(normalized.key);
        if (normalized == PreviewMode.MODE_C) {
            modeCPrimaryIndex = PreviewLayoutModel.FRONT;
        } else if (normalized == PreviewMode.MODE_D) {
            modeDLeftPrimaryIndex = PreviewLayoutModel.FRONT;
        }
        applyStateToLayoutAndShell();
        syncModeDSideSubscriptions();
        applyVisibilityMaskToCameras(false);
        if (wasModeA && normalized != PreviewMode.MODE_A) {
            if (surroundHostEligible) {
                deferredRebindOnHostEligible = false;
                mainHandler.post(callbacks::onRebindVisibleTextureViews);
            } else {
                deferredRebindOnHostEligible = true;
            }
        }
    }

    public void syncSurroundPreferences() {
        applyStateToLayoutAndShell();
        syncModeDSideSubscriptions();
        applyVisibilityMaskToCameras(false);
        if (previewLayout != null) {
            previewLayout.setOverlayBgAlphaPercent(appConfig.getPreviewOverlayBgAlphaPercent());
            previewLayout.setRoundedClipEnabled(appConfig.isPreviewRoundedClipEnabled());
        }
        if (surroundModeAView != null) {
            surroundModeAView.refreshFromPreferences();
        }
        refreshShortcutsOverlay();
    }

    /** 实时调整 360 模式 A 右上前/后视引导线宽度缩放比（无需销毁重开 GL）。 */
    public void updateSurroundRoadGuidanceWidthLive(float widthScale) {
        if (surroundModeAView != null) {
            surroundModeAView.updateRoadGuidanceWidthScale(widthScale);
        }
    }

    /** 实时刷新四个分栏/360 画面内切换按键及标签底色透明度。 */
    public void updateOverlayBgAlphaPercentLive(int alphaPercent) {
        int normalized = AppConfig.normalizePreviewOverlayBgAlphaPercent(alphaPercent);
        if (previewLayout != null) {
            previewLayout.setOverlayBgAlphaPercent(normalized);
        }
        if (surroundModeAView != null) {
            surroundModeAView.updateOverlayBgAlphaPercent(normalized);
        }
        applyCornerNavBackground(normalized);
    }

    /** 控制「按键透明度」标签页下的实时画面内按键预览显示。 */
    public void setOpacityPreviewMode(boolean enabled) {
        if (this.opacityPreviewMode == enabled) return;
        this.opacityPreviewMode = enabled;
        if (previewLayout != null) {
            previewLayout.setOpacityPreviewMode(enabled);
        }
        refreshShortcutsOverlay();
    }

    /** 1.1 & 1.7: 仅由画内按钮触发 Mode C / Mode D 左侧主屏前/后视角切换（不再随档位自动切换）。 */
    public void toggleManualPrimary() {
        if (currentMode == PreviewMode.MODE_C) {
            modeCPrimaryIndex = (modeCPrimaryIndex == PreviewLayoutModel.BACK)
                    ? PreviewLayoutModel.FRONT : PreviewLayoutModel.BACK;
        } else if (currentMode == PreviewMode.MODE_D) {
            modeDLeftPrimaryIndex = (modeDLeftPrimaryIndex == PreviewLayoutModel.BACK)
                    ? PreviewLayoutModel.FRONT : PreviewLayoutModel.BACK;
        } else {
            return;
        }
        applyStateToLayoutAndShell();
        applyVisibilityMaskToCameras(false);
    }

    /** 手动切换 Mode D 右侧副屏左/右视角（无论当前为手动或自动联动，均允许即时点按查看另一侧）。 */
    public void toggleModeDSideManual() {
        if (currentMode != PreviewMode.MODE_D) {
            return;
        }
        modeDRightSideIndex = (modeDRightSideIndex == PreviewLayoutModel.RIGHT)
                ? PreviewLayoutModel.LEFT : PreviewLayoutModel.RIGHT;
        applyStateToLayoutAndShell();
        applyVisibilityMaskToCameras(false);
        if (surroundHostEligible) {
            callbacks.onRebindVisibleTextureViews();
        }
    }

    /** 在 Mode D 右侧画面内循环切换联动方式：随方向盘 -> 随转向灯 -> 手动切换 -> 随方向盘。 */
    public void cycleModeDSideLinkageMode() {
        String current = appConfig.getModeDSideLinkageMode();
        String next;
        if (AppConfig.MODE_D_SIDE_LINKAGE_STEERING.equals(current)) {
            next = AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL;
        } else if (AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL.equals(current)) {
            next = AppConfig.MODE_D_SIDE_LINKAGE_MANUAL;
        } else {
            next = AppConfig.MODE_D_SIDE_LINKAGE_STEERING;
        }
        appConfig.setModeDSideLinkageMode(next);
        if (previewLayout != null) {
            previewLayout.setModeDSideLinkageMode(next);
        }
        syncModeDSideSubscriptions();
        String label = AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL.equals(next)
                ? "随转向灯"
                : (AppConfig.MODE_D_SIDE_LINKAGE_MANUAL.equals(next) ? "手动切换" : "随方向盘");
        Toast.makeText(context, "模式 D 右侧视角已切为：" + label, Toast.LENGTH_SHORT).show();
    }

    public void toggleModeCPrimaryManual() {
        toggleManualPrimary();
    }

    public void syncFromPreferences() {
        PreviewMode savedMode = PreviewMode.fromKey(appConfig.getPreviewMode());
        if (savedMode != currentMode) {
            setPreviewMode(savedMode);
        } else {
            syncModeDSideSubscriptions();
            refreshShortcutsOverlay();
        }
    }

    public void onEditingStateChanged(boolean editing) {
        this.editorActive = editing;
        if (!editing) {
            this.opacityPreviewMode = false;
            if (previewLayout != null) {
                previewLayout.setOpacityPreviewMode(false);
            }
        }
        refreshShortcutsOverlay();
    }

    public void onFullscreenStateChanged() {
        refreshShortcutsOverlay();
    }

    public void refreshTheme(@NonNull Context themedContext) {
        stylePillButtons(themedContext);
        if (modeADisabledGuideView != null) {
            styleModeADisabledGuide(themedContext);
        }
    }

    public boolean isCameraVisible(@NonNull String cameraKey) {
        if (shouldHideOrdinaryTextureViewsForModeA(currentMode, appConfig.isMainPreviewEnabled())) {
            return false;
        }
        if (currentMode == PreviewMode.MODE_A && !appConfig.isMainPreviewEnabled()) {
            return false;
        }
        if (previewLayout == null) return true;
        int idx = cameraKeyToIndex(cameraKey);
        return idx < 0 || previewLayout.isPaneVisibleInMode(idx);
    }

    public int countExpectedVisibleTextures(int configuredCameraCount) {
        if (shouldHideOrdinaryTextureViewsForModeA(currentMode, appConfig.isMainPreviewEnabled())) {
            return 0;
        }
        if (currentMode == PreviewMode.MODE_A && !appConfig.isMainPreviewEnabled()) {
            return 0;
        }
        if (previewLayout == null) return configuredCameraCount;
        boolean[] mask = previewLayout.getEffectiveVisibilityMask();
        int count = 0;
        if (configuredCameraCount >= 1 && mask[PreviewLayoutModel.FRONT]) count++;
        if (configuredCameraCount >= 2 && mask[PreviewLayoutModel.BACK]) count++;
        if (configuredCameraCount >= 4 && mask[PreviewLayoutModel.LEFT]) count++;
        if (configuredCameraCount >= 4 && mask[PreviewLayoutModel.RIGHT]) count++;
        if (count == 0 && configuredCameraCount < 4) {
            return 0;
        }
        return Math.max(1, count);
    }

    @NonNull
    public Map<String, Boolean> getEffectiveCameraVisibilityMap() {
        if (shouldHideOrdinaryTextureViewsForModeA(currentMode, appConfig.isMainPreviewEnabled())
                || (currentMode == PreviewMode.MODE_A && !appConfig.isMainPreviewEnabled())) {
            return toCameraVisibilityMap(new boolean[]{false, false, false, false});
        }
        if (previewLayout == null) {
            return toCameraVisibilityMap(new boolean[]{true, true, true, true});
        }
        return toCameraVisibilityMap(previewLayout.getEffectiveVisibilityMask());
    }

    @NonNull
    public String getActivePrimaryCameraKey() {
        int active = currentMode == PreviewMode.MODE_D ? modeDLeftPrimaryIndex : modeCPrimaryIndex;
        if ((currentMode == PreviewMode.MODE_C || currentMode == PreviewMode.MODE_D)
                && active == PreviewLayoutModel.BACK) {
            return "back";
        }
        return "front";
    }

    private void syncModeDSideSubscriptions() {
        String linkageMode = appConfig.getModeDSideLinkageMode();
        boolean mainEnabled = appConfig.isMainPreviewEnabled();
        if (previewLayout != null) {
            previewLayout.setModeDSideLinkageMode(linkageMode);
        }

        boolean wantSteering = shouldSubscribeSteeringForModeD(
                currentMode, surroundHostEligible, mainEnabled, linkageMode);
        if (wantSteering) {
            if (modeDSteeringLease == null) {
                modeDSteeringLease = SurroundSteeringAngleSource.acquire(modeDSteeringListener);
                AppLog.d(TAG, "Mode D steering wheel lease acquired");
            }
        } else if (modeDSteeringLease != null) {
            modeDSteeringLease.close();
            modeDSteeringLease = null;
            AppLog.d(TAG, "Mode D steering wheel lease released");
        }

        boolean wantTurnSignal = shouldSubscribeTurnSignalForModeD(
                currentMode, surroundHostEligible, mainEnabled, linkageMode);
        if (wantTurnSignal) {
            if (!modeDTurnSignalRegistered) {
                VehicleSignalHub.getInstance().addListener(modeDTurnSignalListener);
                modeDTurnSignalRegistered = true;
                AppLog.d(TAG, "Mode D turn-signal hub listener registered");
            }
            if (!appConfig.isCarSignalManagerTriggerMode()) {
                if (modeDVhalTurnLease == null) {
                    modeDVhalTurnLease = RecordingVehicleSource.acquire();
                    AppLog.d(TAG, "Mode D VHAL turn-signal lease acquired");
                }
            } else if (modeDVhalTurnLease != null) {
                modeDVhalTurnLease.close();
                modeDVhalTurnLease = null;
            }
            applyModeDTurnSignalSnapshot(VehicleSignalHub.getInstance().getSnapshot());
        } else {
            if (modeDTurnSignalRegistered) {
                VehicleSignalHub.getInstance().removeListener(modeDTurnSignalListener);
                modeDTurnSignalRegistered = false;
                AppLog.d(TAG, "Mode D turn-signal hub listener removed");
            }
            if (modeDVhalTurnLease != null) {
                modeDVhalTurnLease.close();
                modeDVhalTurnLease = null;
                AppLog.d(TAG, "Mode D VHAL turn-signal lease released");
            }
        }
    }

    private void applyModeDSteeringAngle(float equivalentWheelAngleDeg) {
        if (!shouldSubscribeSteeringForModeD(
                currentMode, surroundHostEligible, appConfig.isMainPreviewEnabled(),
                appConfig.getModeDSideLinkageMode())) {
            return;
        }
        if (previewLayout != null && previewLayout.isEditing()) {
            return;
        }
        int nextSide = resolveModeDSideCamera(equivalentWheelAngleDeg, modeDRightSideIndex, MODE_D_STEER_DEADBAND_DEG);
        if (nextSide != modeDRightSideIndex) {
            switchModeDSideTo(nextSide, "steering angle=" + equivalentWheelAngleDeg);
        }
    }

    private void applyModeDTurnSignalSnapshot(@Nullable VehicleSignalSnapshot snapshot) {
        if (snapshot == null || !shouldSubscribeTurnSignalForModeD(
                currentMode, surroundHostEligible, appConfig.isMainPreviewEnabled(),
                appConfig.getModeDSideLinkageMode())) {
            return;
        }
        if (previewLayout != null && previewLayout.isEditing()) {
            return;
        }
        int nextSide = resolveModeDSideCameraFromTurnSignal(
                snapshot.leftTurn, snapshot.rightTurn, modeDRightSideIndex);
        if (nextSide != modeDRightSideIndex) {
            switchModeDSideTo(nextSide,
                    "turnSignal L=" + snapshot.leftTurn + ", R=" + snapshot.rightTurn);
        }
    }

    private void switchModeDSideTo(int nextSide, @NonNull String reason) {
        modeDRightSideIndex = nextSide;
        applyStateToLayoutAndShell();
        applyVisibilityMaskToCameras(false);
        if (surroundHostEligible) {
            callbacks.onRebindVisibleTextureViews();
        }
        AppLog.d(TAG, "Mode D side switched (" + reason + "): side="
                + (nextSide == PreviewLayoutModel.RIGHT ? "right" : "left"));
    }

    private void applyStateToLayoutAndShell() {
        boolean mainEnabled = appConfig.isMainPreviewEnabled();
        if (previewLayout != null) {
            previewLayout.setMainPreviewEnabled(mainEnabled);
        }
        boolean wantSurroundGl = shouldActivateSurroundGl(currentMode, surroundHostEligible, mainEnabled);
        boolean showModeADisabledGuide = shouldShowModeAPreviewDisabledGuide(currentMode, mainEnabled);

        if (wantSurroundGl && modeAContainer != null) {
            hideModeADisabledGuide();
            if (previewLayout != null) {
                previewLayout.setVisibility(View.GONE);
            }
            modeAContainer.setVisibility(View.VISIBLE);
            if (surroundModeAView == null) {
                SurroundModeAView created = new SurroundModeAView(context);
                created.setHostCallbacks(callbacks::onSurroundSurfacesReady);
                modeAContainer.addView(created, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                surroundModeAView = created;
                created.activateModeA(cameraManager);
            } else {
                surroundModeAView.refreshFromPreferences();
            }
        } else if (showModeADisabledGuide && modeAContainer != null) {
            detachSurroundModeAView(false);
            if (previewLayout != null) {
                previewLayout.setVisibility(View.GONE);
            }
            modeAContainer.setVisibility(View.VISIBLE);
            showModeADisabledGuide();
        } else {
            hideModeADisabledGuide();
            if (modeAContainer != null) {
                modeAContainer.setVisibility(View.GONE);
            }
            if (previewLayout != null) {
                previewLayout.setVisibility(View.VISIBLE);
                if (currentMode != PreviewMode.MODE_A) {
                    int primary = currentMode == PreviewMode.MODE_D ? modeDLeftPrimaryIndex : modeCPrimaryIndex;
                    previewLayout.setPreviewState(currentMode, primary, modeDRightSideIndex);
                }
            }
            boolean deferClearForSurfaceAttach = surroundModeAView != null
                    && mainEnabled && surroundHostEligible
                    && currentMode != PreviewMode.MODE_A
                    && previewLayout != null;
            detachSurroundModeAView(deferClearForSurfaceAttach);
        }

        BlindSpotService.setInternal360Active(context, wantSurroundGl);
        if (shellController != null) {
            shellController.setPreviewModeUi(currentMode);
        }
        refreshShortcutsOverlay();
    }

    private void detachSurroundModeAView(boolean waitForOrdinaryTextureAttach) {
        if (surroundModeAView == null) return;
        final SurroundModeAView viewToDetach = surroundModeAView;
        surroundModeAView = null;

        Runnable teardownAction = () -> {
            if (cameraManager != null && previewLayout != null && currentMode != PreviewMode.MODE_A) {
                cameraManager.setCameraPreviewVisibilities(getEffectiveCameraVisibilityMap());
            }
            if (appConfig.isMainPreviewEnabled() && currentMode != PreviewMode.MODE_A) {
                callbacks.onRebindVisibleTextureViews();
            }
            viewToDetach.deactivateModeA(cameraManager, () -> {
                if (modeAContainer != null) {
                    modeAContainer.removeView(viewToDetach);
                }
            });
        };

        if (waitForOrdinaryTextureAttach && previewLayout != null) {
            // Keep cameras open until TextureView creates its SurfaceTexture during the next layout traversal
            // so exiting 360 A never falls into an unused close/reopen race.
            if (cameraManager != null) {
                cameraManager.setCameraPreviewVisibilities(getEffectiveCameraVisibilityMap());
            }
            previewLayout.post(teardownAction);
        } else {
            teardownAction.run();
        }
    }

    private void showModeADisabledGuide() {
        if (modeAContainer == null) return;
        if (modeADisabledGuideView == null) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(Math.round(28 * density), Math.round(24 * density),
                    Math.round(28 * density), Math.round(24 * density));
            card.setBackgroundResource(R.drawable.ui_card);

            TextView title = new TextView(context);
            title.setText("360 全景预览已关闭");
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
            title.setGravity(Gravity.CENTER);
            card.addView(title);

            TextView subtitle = new TextView(context);
            subtitle.setText("点击右上角“开启预览”或下方按钮，恢复 360 环视与四路全景");
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            subtitle.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
            subtitle.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = Math.round(8 * density);
            subLp.bottomMargin = Math.round(16 * density);
            card.addView(subtitle, subLp);

            Button enableBtn = new Button(context);
            enableBtn.setText("开启主预览");
            enableBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            enableBtn.setPadding(Math.round(24 * density), Math.round(8 * density),
                    Math.round(24 * density), Math.round(8 * density));
            ViewCompat.setBackgroundTintList(enableBtn,
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.button_accent)));
            enableBtn.setTextColor(ContextCompat.getColor(context, R.color.ui_on_accent));
            enableBtn.setOnClickListener(v -> callbacks.onEnableMainPreviewClicked());
            card.addView(enableBtn);

            FrameLayout.LayoutParams containerLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            modeAContainer.addView(card, containerLp);
            modeADisabledGuideView = card;
        }
        modeADisabledGuideView.setVisibility(View.VISIBLE);
        styleModeADisabledGuide(context);
    }

    private void hideModeADisabledGuide() {
        if (modeADisabledGuideView != null) {
            modeADisabledGuideView.setVisibility(View.GONE);
        }
    }

    private void styleModeADisabledGuide(@NonNull Context themedContext) {
        if (!(modeADisabledGuideView instanceof LinearLayout)) return;
        LinearLayout card = (LinearLayout) modeADisabledGuideView;
        if (card.getChildCount() >= 3) {
            if (card.getChildAt(0) instanceof TextView) {
                ((TextView) card.getChildAt(0)).setTextColor(
                        ContextCompat.getColor(themedContext, R.color.text_primary));
            }
            if (card.getChildAt(1) instanceof TextView) {
                ((TextView) card.getChildAt(1)).setTextColor(
                        ContextCompat.getColor(themedContext, R.color.text_secondary));
            }
            if (card.getChildAt(2) instanceof Button) {
                Button btn = (Button) card.getChildAt(2);
                ViewCompat.setBackgroundTintList(btn, ColorStateList.valueOf(
                        ContextCompat.getColor(themedContext, R.color.button_accent)));
                btn.setTextColor(ContextCompat.getColor(themedContext, R.color.ui_on_accent));
            }
        }
    }

    private void applyVisibilityMaskToCameras(boolean forceApply) {
        if (cameraManager == null || previewLayout == null) return;
        if (!forceApply && !shouldApplyVisibilityMaskImmediately(surroundHostEligible)) {
            return;
        }
        boolean wantSurroundGl = shouldActivateSurroundGl(
                currentMode, surroundHostEligible, appConfig.isMainPreviewEnabled());
        boolean surfacesReady = surroundModeAView != null && surroundModeAView.isSurfacesReady();
        if (shouldDeferCameraVisibilityMaskForModeA(wantSurroundGl, surfacesReady)) {
            return;
        }
        cameraManager.setCameraPreviewVisibilities(getEffectiveCameraVisibilityMap());
    }

    public void refreshShortcutsOverlay() {
        boolean layoutEditing = previewLayout != null && previewLayout.isEditing();
        boolean hideOverlayForEditing = (layoutEditing || editorActive)
                && !opacityPreviewMode
                && currentMode != PreviewMode.MODE_A;
        boolean fullscreen = shellController != null && shellController.isFullscreenPreview();
        boolean showInFrameButtons = shouldShowViewShortcuts(
                isUnifiedOverlayButtonsEnabled(appConfig), hideOverlayForEditing);

        if (previewLayout != null) {
            previewLayout.setOpacityPreviewMode(opacityPreviewMode && layoutEditing);
            previewLayout.setInFrameControlsVisible(showInFrameButtons);
        }

        if (shortcutsOverlay == null) return;
        boolean showCornerNav = shouldShowCornerNav(
                appConfig.getPreviewCornerNavMode(), fullscreen, hideOverlayForEditing);
        if (cornerNavGroup != null) {
            cornerNavGroup.setVisibility(showCornerNav ? View.VISIBLE : View.GONE);
        }
        shortcutsOverlay.setVisibility(showCornerNav ? View.VISIBLE : View.GONE);
        stylePillButtons(context);
    }

    private static boolean isUnifiedOverlayButtonsEnabled(@NonNull AppConfig config) {
        return config.isPreviewViewShortcutsEnabled() && config.isSurroundOverlayButtonsVisible();
    }

    private void stylePillButtons(@NonNull Context themedContext) {
        applyPillItemStyle(btnShortcutBack, themedContext, false);
        applyPillItemStyle(btnShortcutSettings, themedContext, false);
        applyCornerNavBackground(appConfig.getPreviewOverlayBgAlphaPercent());
    }

    private void applyCornerNavBackground(int alphaPercent) {
        if (cornerNavGroup == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(18f * density);
        bg.setColor(AppConfig.applyColorAlphaPercent(0xBF101722, alphaPercent));
        bg.setStroke(Math.max(1, Math.round(density)),
                AppConfig.applyColorAlphaPercent(0x46FFFFFF, alphaPercent));
        cornerNavGroup.setBackground(bg);
    }

    private void applyPillItemStyle(@Nullable TextView view, @NonNull Context themedContext, boolean selected) {
        if (view == null) return;
        if (selected) {
            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setShape(GradientDrawable.RECTANGLE);
            pillBg.setCornerRadius(14f * density);
            pillBg.setColor(ContextCompat.getColor(themedContext, R.color.button_accent));
            view.setBackground(pillBg);
            view.setTextColor(ContextCompat.getColor(themedContext, R.color.ui_on_accent));
        } else {
            view.setBackground(null);
            view.setTextColor(0xFFFFFFFF);
        }
    }

    @Override
    public void onPreparePhotoTextures(List<String> cameraKeys) {
        if (cameraKeys == null || cameraKeys.isEmpty()) return;
        Runnable action = () -> preparePhotoTexturesOnMain(cameraKeys);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    @Override
    public void onReleasePhotoTextures(List<String> cameraKeys) {
        Runnable action = this::scheduleStagingReleaseOnMain;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private void preparePhotoTexturesOnMain(@NonNull List<String> cameraKeys) {
        if (stagingContainer == null || cameraManager == null) return;
        if (pendingStagingCleanup != null) {
            mainHandler.removeCallbacks(pendingStagingCleanup);
            pendingStagingCleanup = null;
        }
        stagingContainer.setVisibility(View.VISIBLE);
        for (String key : cameraKeys) {
            TextureView existing = stagingViews.get(key);
            if (existing != null && existing.isAvailable()) {
                cameraManager.attachPhotoTextureView(key, existing);
                continue;
            }
            if (existing != null) {
                stagingContainer.removeView(existing);
                stagingViews.remove(key);
            }
            AutoFitTextureView stagingView = new AutoFitTextureView(context);
            stagingView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            stagingView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    if (cameraManager != null) {
                        Size size = cameraManager.getCameraPreviewSize(key);
                        callbacks.onApplyStagingTransform(key, stagingView, size, width, height);
                        cameraManager.attachPhotoTextureView(key, stagingView);
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    if (cameraManager != null) {
                        Size size = cameraManager.getCameraPreviewSize(key);
                        callbacks.onApplyStagingTransform(key, stagingView, size, width, height);
                    }
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });
            stagingViews.put(key, stagingView);
            stagingContainer.addView(stagingView);
        }
    }

    private void scheduleStagingReleaseOnMain() {
        if (stagingContainer == null) return;
        if (pendingStagingCleanup != null) {
            mainHandler.removeCallbacks(pendingStagingCleanup);
        }
        pendingStagingCleanup = () -> {
            pendingStagingCleanup = null;
            clearStagingViewsNow();
        };
        mainHandler.postDelayed(pendingStagingCleanup, STAGING_RELEASE_DELAY_MS);
    }

    private void clearStagingViewsNow() {
        stagingViews.clear();
        if (stagingContainer != null) {
            stagingContainer.removeAllViews();
            stagingContainer.setVisibility(View.GONE);
        }
    }

    // ==================== Pure Static Decision Helpers (Testable) ====================

    @NonNull
    public static PreviewMode normalizeSupportedMode(@Nullable PreviewMode requested) {
        return requested != null ? requested : PreviewMode.MODE_B;
    }

    public static boolean shouldActivateSurroundGl(@Nullable PreviewMode mode,
                                                   boolean hostEligible,
                                                   boolean mainPreviewEnabled) {
        return mode == PreviewMode.MODE_A && hostEligible && mainPreviewEnabled;
    }

    public static boolean shouldStartSurroundHostEligible(boolean isInBackground,
                                                        boolean onRecordingSection) {
        return !isInBackground && onRecordingSection;
    }

    public static boolean shouldShowModeAPreviewDisabledGuide(@Nullable PreviewMode mode,
                                                              boolean mainPreviewEnabled) {
        return mode == PreviewMode.MODE_A && !mainPreviewEnabled;
    }

    public static boolean shouldSubscribeSteeringForModeD(@Nullable PreviewMode mode,
                                                        boolean hostEligible,
                                                        boolean mainPreviewEnabled) {
        return shouldSubscribeSteeringForModeD(
                mode, hostEligible, mainPreviewEnabled, AppConfig.MODE_D_SIDE_LINKAGE_STEERING);
    }

    public static boolean shouldSubscribeSteeringForModeD(@Nullable PreviewMode mode,
                                                        boolean hostEligible,
                                                        boolean mainPreviewEnabled,
                                                        @Nullable String linkageMode) {
        return mode == PreviewMode.MODE_D
                && hostEligible
                && mainPreviewEnabled
                && AppConfig.MODE_D_SIDE_LINKAGE_STEERING.equals(AppConfig.normalizeModeDSideLinkageMode(linkageMode));
    }

    public static boolean shouldSubscribeTurnSignalForModeD(@Nullable PreviewMode mode,
                                                          boolean hostEligible,
                                                          boolean mainPreviewEnabled,
                                                          @Nullable String linkageMode) {
        return mode == PreviewMode.MODE_D
                && hostEligible
                && mainPreviewEnabled
                && AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL.equals(AppConfig.normalizeModeDSideLinkageMode(linkageMode));
    }

    /**
     * Resolves right-pane side camera for Mode D from turn signal states:
     * left signal on only -> LEFT; right signal on only -> RIGHT;
     * hazard/off/unknown preserves currentSide without flicker.
     */
    public static int resolveModeDSideCameraFromTurnSignal(int leftTurnState,
                                                           int rightTurnState,
                                                           int currentSide) {
        if (leftTurnState == 1 && rightTurnState != 1) {
            return PreviewLayoutModel.LEFT;
        }
        if (rightTurnState == 1 && leftTurnState != 1) {
            return PreviewLayoutModel.RIGHT;
        }
        return currentSide == PreviewLayoutModel.RIGHT ? PreviewLayoutModel.RIGHT : PreviewLayoutModel.LEFT;
    }

    public static boolean shouldHideOrdinaryTextureViewsForModeA(@Nullable PreviewMode mode,
                                                                 boolean mainPreviewEnabled) {
        return mode == PreviewMode.MODE_A && mainPreviewEnabled;
    }

    public static boolean shouldDeferCameraVisibilityMaskForModeA(boolean wantSurroundGl,
                                                                  boolean surroundSurfacesReady) {
        return wantSurroundGl && !surroundSurfacesReady;
    }

    public static boolean shouldApplyVisibilityMaskImmediately(boolean hostEligible) {
        return hostEligible;
    }

    /**
     * Resolves right-pane side camera for Mode D given equivalent road steering wheel angle (deg):
     * negative = left turn -> LEFT camera; positive = right turn -> RIGHT camera;
     * holds currentSide inside deadband to prevent jitter.
     */
    public static int resolveModeDSideCamera(float equivalentWheelAngleDeg,
                                             int currentSide,
                                             float deadbandDeg) {
        float safeDeadband = Math.max(0f, deadbandDeg);
        if (Float.isNaN(equivalentWheelAngleDeg) || Float.isInfinite(equivalentWheelAngleDeg)) {
            return currentSide == PreviewLayoutModel.RIGHT ? PreviewLayoutModel.RIGHT : PreviewLayoutModel.LEFT;
        }
        if (equivalentWheelAngleDeg <= -safeDeadband) {
            return PreviewLayoutModel.LEFT;
        }
        if (equivalentWheelAngleDeg >= safeDeadband) {
            return PreviewLayoutModel.RIGHT;
        }
        return currentSide == PreviewLayoutModel.RIGHT ? PreviewLayoutModel.RIGHT : PreviewLayoutModel.LEFT;
    }

    public static boolean shouldShowCornerNav(@Nullable String cornerNavMode,
                                              boolean fullscreen,
                                              boolean isEditing) {
        if (isEditing) return false;
        String normalized = AppConfig.normalizePreviewCornerNavMode(cornerNavMode);
        if (AppConfig.CORNER_NAV_ALWAYS.equals(normalized)) return true;
        if (AppConfig.CORNER_NAV_HIDDEN.equals(normalized)) return false;
        return fullscreen;
    }

    public static boolean shouldShowViewShortcuts(boolean shortcutsEnabled, boolean isEditing) {
        return shortcutsEnabled && !isEditing;
    }

    @NonNull
    public static Map<String, Boolean> toCameraVisibilityMap(@NonNull boolean[] mask) {
        Map<String, Boolean> map = new LinkedHashMap<>(4);
        map.put("left", mask.length > PreviewLayoutModel.LEFT && mask[PreviewLayoutModel.LEFT]);
        map.put("front", mask.length <= PreviewLayoutModel.FRONT || mask[PreviewLayoutModel.FRONT]);
        map.put("back", mask.length <= PreviewLayoutModel.BACK || mask[PreviewLayoutModel.BACK]);
        map.put("right", mask.length <= PreviewLayoutModel.RIGHT || mask[PreviewLayoutModel.RIGHT]);
        return map;
    }

    public static int cameraKeyToIndex(@Nullable String cameraKey) {
        if ("left".equals(cameraKey)) return PreviewLayoutModel.LEFT;
        if ("front".equals(cameraKey)) return PreviewLayoutModel.FRONT;
        if ("back".equals(cameraKey)) return PreviewLayoutModel.BACK;
        if ("right".equals(cameraKey)) return PreviewLayoutModel.RIGHT;
        return -1;
    }

    @NonNull
    public static List<String> hiddenKeysFromMask(@NonNull boolean[] mask) {
        List<String> hidden = new ArrayList<>();
        if (mask.length > PreviewLayoutModel.FRONT && !mask[PreviewLayoutModel.FRONT]) hidden.add("front");
        if (mask.length > PreviewLayoutModel.BACK && !mask[PreviewLayoutModel.BACK]) hidden.add("back");
        if (mask.length > PreviewLayoutModel.LEFT && !mask[PreviewLayoutModel.LEFT]) hidden.add("left");
        if (mask.length > PreviewLayoutModel.RIGHT && !mask[PreviewLayoutModel.RIGHT]) hidden.add("right");
        return hidden;
    }
}
