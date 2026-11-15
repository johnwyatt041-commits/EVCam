package com.kooo.evcam.ui;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.AutoFitTextureView;
import com.kooo.evcam.BlindSpotService;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.vehicle.VehicleSignalHub;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot.Gear;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coordinates T03 multi-mode preview (Mode B 3-column & Mode C 3-channel gear-linked),
 * dynamic per-camera stream unbind/rebind, off-screen staging capture for View.GONE feeds,
 * and top-left overlay shortcuts synchronized with AppShellController.
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
    }

    private static final String TAG = "PreviewModeController";
    static final long GEAR_DEBOUNCE_MS = 200L;
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
    private final View viewSwitchGroup;
    @Nullable
    private final TextView btnShortcutBack;
    @Nullable
    private final TextView btnShortcutSettings;
    @Nullable
    private final TextView btnShortcutModeB;
    @Nullable
    private final TextView btnShortcutModeC;
    @Nullable
    private final TextView btnShortcutModeCSwap;
    @Nullable
    private final TextView btnShortcutFullscreen;
    @Nullable
    private final AppShellController shellController;
    private final Callbacks callbacks;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float density;

    @Nullable
    private MultiCameraManager cameraManager;
    private PreviewMode currentMode;
    private int modeCPrimaryIndex = PreviewLayoutModel.FRONT;
    @Nullable
    private Gear manualOverrideGear;
    @Nullable
    private Integer manualOverridePrimary;
    @NonNull
    private Gear latestGear = Gear.UNKNOWN;
    @Nullable
    private Gear pendingDebouncedGear;
    private boolean signalListening;

    private final Map<String, TextureView> stagingViews = new LinkedHashMap<>();
    @Nullable
    private Runnable pendingStagingCleanup;

    private final Runnable applyDebouncedGearRunnable = () -> {
        if (pendingDebouncedGear == null) return;
        applyGearSelection(pendingDebouncedGear);
        pendingDebouncedGear = null;
    };

    private final VehicleSignalHub.Listener signalListener = snapshot -> {
        Gear incoming = (snapshot != null && snapshot.gear != null) ? snapshot.gear : Gear.UNKNOWN;
        mainHandler.post(() -> onGearSignalReceived(incoming));
    };

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
        this.viewSwitchGroup = rootView.findViewById(R.id.ui_shortcut_view_switch_group);
        this.btnShortcutBack = rootView.findViewById(R.id.btn_shortcut_back);
        this.btnShortcutSettings = rootView.findViewById(R.id.btn_shortcut_settings);
        this.btnShortcutModeB = rootView.findViewById(R.id.btn_shortcut_mode_b);
        this.btnShortcutModeC = rootView.findViewById(R.id.btn_shortcut_mode_c);
        this.btnShortcutModeCSwap = rootView.findViewById(R.id.btn_shortcut_mode_c_swap);
        this.btnShortcutFullscreen = rootView.findViewById(R.id.btn_shortcut_fullscreen);

        this.currentMode = PreviewMode.fromKey(appConfig.getPreviewMode());
        VehicleSignalSnapshot currentSnapshot = VehicleSignalHub.getInstance().getSnapshot();
        this.latestGear = currentSnapshot != null && currentSnapshot.gear != null
                ? currentSnapshot.gear : Gear.UNKNOWN;
        this.modeCPrimaryIndex = resolveDefaultPrimaryForGear(this.latestGear);

        if (previewLayout != null) {
            previewLayout.setOnStateChangeListener(mask -> applyVisibilityMaskToCameras());
        }
        if (modeAContainer != null) {
            modeAContainer.setVisibility(View.GONE);
        }

        wireShortcuts();
        applyStateToLayoutAndShell();
    }

    private void wireShortcuts() {
        if (btnShortcutBack != null) {
            btnShortcutBack.setOnClickListener(v -> callbacks.onBackShortcutClicked());
        }
        if (btnShortcutSettings != null) {
            btnShortcutSettings.setOnClickListener(v -> callbacks.onSettingsShortcutClicked());
        }
        if (btnShortcutModeB != null) {
            btnShortcutModeB.setOnClickListener(v -> setPreviewMode(PreviewMode.MODE_B));
        }
        if (btnShortcutModeC != null) {
            btnShortcutModeC.setOnClickListener(v -> setPreviewMode(PreviewMode.MODE_C));
        }
        if (btnShortcutModeCSwap != null) {
            btnShortcutModeCSwap.setOnClickListener(v -> toggleModeCPrimaryManual());
        }
        if (btnShortcutFullscreen != null) {
            btnShortcutFullscreen.setOnClickListener(v -> callbacks.onToggleFullscreenClicked());
        }
    }

    public void attachCameraManager(@Nullable MultiCameraManager manager) {
        if (this.cameraManager != null && this.cameraManager != manager) {
            this.cameraManager.setPhotoSurfaceProvider(null);
        }
        this.cameraManager = manager;
        if (manager != null) {
            manager.setPhotoSurfaceProvider(this);
            applyVisibilityMaskToCameras();
        }
    }

    public void start() {
        if (!signalListening) {
            VehicleSignalHub.getInstance().addListener(signalListener);
            signalListening = true;
        }
        VehicleSignalSnapshot current = VehicleSignalHub.getInstance().getSnapshot();
        if (current != null && current.gear != null) {
            latestGear = current.gear;
            if (currentMode == PreviewMode.MODE_C && manualOverridePrimary == null) {
                modeCPrimaryIndex = resolveDefaultPrimaryForGear(latestGear);
                applyStateToLayoutAndShell();
            }
        }
        refreshShortcutsOverlay();
    }

    public void stop() {
        if (signalListening) {
            VehicleSignalHub.getInstance().removeListener(signalListener);
            signalListening = false;
        }
        mainHandler.removeCallbacks(applyDebouncedGearRunnable);
        pendingDebouncedGear = null;
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
        PreviewMode normalized = (mode == PreviewMode.MODE_C) ? PreviewMode.MODE_C : PreviewMode.MODE_B;
        if (currentMode == normalized) {
            refreshShortcutsOverlay();
            return;
        }
        currentMode = normalized;
        appConfig.setPreviewMode(normalized.key);
        if (normalized == PreviewMode.MODE_C) {
            VehicleSignalSnapshot current = VehicleSignalHub.getInstance().getSnapshot();
            latestGear = current != null && current.gear != null ? current.gear : Gear.UNKNOWN;
            if (manualOverridePrimary == null || manualOverrideGear != latestGear) {
                manualOverrideGear = null;
                manualOverridePrimary = null;
                modeCPrimaryIndex = resolveDefaultPrimaryForGear(latestGear);
            }
        }
        applyStateToLayoutAndShell();
        applyVisibilityMaskToCameras();
    }

    public void toggleModeCPrimaryManual() {
        if (currentMode != PreviewMode.MODE_C) return;
        modeCPrimaryIndex = (modeCPrimaryIndex == PreviewLayoutModel.BACK)
                ? PreviewLayoutModel.FRONT : PreviewLayoutModel.BACK;
        manualOverrideGear = latestGear;
        manualOverridePrimary = modeCPrimaryIndex;
        applyStateToLayoutAndShell();
        applyVisibilityMaskToCameras();
    }

    public void syncFromPreferences() {
        PreviewMode savedMode = PreviewMode.fromKey(appConfig.getPreviewMode());
        if (savedMode != currentMode) {
            setPreviewMode(savedMode);
        } else {
            refreshShortcutsOverlay();
        }
    }

    public void onEditingStateChanged(boolean editing) {
        if (!editing && currentMode == PreviewMode.MODE_C && manualOverridePrimary == null) {
            int gearTarget = resolveDefaultPrimaryForGear(latestGear);
            if (modeCPrimaryIndex != gearTarget) {
                modeCPrimaryIndex = gearTarget;
                applyStateToLayoutAndShell();
                applyVisibilityMaskToCameras();
            }
        }
        refreshShortcutsOverlay();
    }

    public void onFullscreenStateChanged() {
        refreshShortcutsOverlay();
    }

    public void refreshTheme(@NonNull Context themedContext) {
        stylePillButtons(themedContext);
    }

    public boolean isCameraVisible(@NonNull String cameraKey) {
        if (previewLayout == null) return true;
        int idx = cameraKeyToIndex(cameraKey);
        return idx < 0 || previewLayout.isPaneVisibleInMode(idx);
    }

    public int countExpectedVisibleTextures(int configuredCameraCount) {
        if (previewLayout == null) return configuredCameraCount;
        boolean[] mask = previewLayout.getEffectiveVisibilityMask();
        int count = 0;
        if (configuredCameraCount >= 1 && mask[PreviewLayoutModel.FRONT]) count++;
        if (configuredCameraCount >= 2 && mask[PreviewLayoutModel.BACK]) count++;
        if (configuredCameraCount >= 4 && mask[PreviewLayoutModel.LEFT]) count++;
        if (configuredCameraCount >= 4 && mask[PreviewLayoutModel.RIGHT]) count++;
        return Math.max(1, count);
    }

    @NonNull
    public Map<String, Boolean> getEffectiveCameraVisibilityMap() {
        if (previewLayout == null) {
            return toCameraVisibilityMap(new boolean[]{true, true, true, true});
        }
        return toCameraVisibilityMap(previewLayout.getEffectiveVisibilityMask());
    }

    private void onGearSignalReceived(@NonNull Gear incomingGear) {
        if (incomingGear == latestGear) return;
        pendingDebouncedGear = incomingGear;
        mainHandler.removeCallbacks(applyDebouncedGearRunnable);
        mainHandler.postDelayed(applyDebouncedGearRunnable, GEAR_DEBOUNCE_MS);
    }

    /** Applies an incoming debounced gear update; clears manual swap override when gear genuinely transitions. */
    void applyGearSelection(@NonNull Gear gear) {
        if (gear == latestGear) return;
        latestGear = gear;
        if (manualOverrideGear != null && manualOverrideGear != gear) {
            manualOverrideGear = null;
            manualOverridePrimary = null;
        }
        if (currentMode != PreviewMode.MODE_C) return;
        if (previewLayout != null && previewLayout.isEditing()) return;
        int targetPrimary = resolveEffectiveModeCPrimary(gear, manualOverrideGear, manualOverridePrimary);
        if (targetPrimary != modeCPrimaryIndex) {
            modeCPrimaryIndex = targetPrimary;
            applyStateToLayoutAndShell();
            applyVisibilityMaskToCameras();
            AppLog.d(TAG, "Mode C gear switched: gear=" + gear
                    + ", primary=" + (targetPrimary == PreviewLayoutModel.BACK ? "back" : "front"));
        }
    }

    @NonNull
    public String getActivePrimaryCameraKey() {
        if (currentMode == PreviewMode.MODE_C && modeCPrimaryIndex == PreviewLayoutModel.BACK) {
            return "back";
        }
        return "front";
    }

    private void applyStateToLayoutAndShell() {
        boolean internal360Visible = modeAContainer != null && modeAContainer.getVisibility() == View.VISIBLE;
        if (modeAContainer != null && internal360Visible) {
            modeAContainer.setVisibility(View.GONE);
            internal360Visible = false;
        }
        BlindSpotService.setInternal360Active(context, internal360Visible);
        if (previewLayout != null) {
            previewLayout.setPreviewState(currentMode, modeCPrimaryIndex);
        }
        if (shellController != null) {
            shellController.setPreviewModeUi(currentMode);
        }
        refreshShortcutsOverlay();
    }

    private void applyVisibilityMaskToCameras() {
        if (cameraManager == null || previewLayout == null) return;
        cameraManager.setCameraPreviewVisibilities(getEffectiveCameraVisibilityMap());
    }

    public void refreshShortcutsOverlay() {
        if (shortcutsOverlay == null) return;
        boolean editing = previewLayout != null && previewLayout.isEditing();
        boolean fullscreen = shellController != null && shellController.isFullscreenPreview();
        boolean showCornerNav = shouldShowCornerNav(
                appConfig.getPreviewCornerNavMode(), fullscreen, editing);
        boolean showViewSwitch = shouldShowViewShortcuts(
                appConfig.isPreviewViewShortcutsEnabled(), editing);

        if (cornerNavGroup != null) {
            cornerNavGroup.setVisibility(showCornerNav ? View.VISIBLE : View.GONE);
        }
        if (viewSwitchGroup != null) {
            viewSwitchGroup.setVisibility(showViewSwitch ? View.VISIBLE : View.GONE);
        }
        if (btnShortcutModeCSwap != null) {
            btnShortcutModeCSwap.setVisibility(
                    currentMode == PreviewMode.MODE_C ? View.VISIBLE : View.GONE);
            btnShortcutModeCSwap.setText(
                    modeCPrimaryIndex == PreviewLayoutModel.BACK ? "主：后 ⇄" : "主：前 ⇄");
        }
        if (btnShortcutFullscreen != null) {
            btnShortcutFullscreen.setText(fullscreen ? "退出全屏" : "全屏");
        }
        shortcutsOverlay.setVisibility((showCornerNav || showViewSwitch) ? View.VISIBLE : View.GONE);
        stylePillButtons(context);
    }

    private void stylePillButtons(@NonNull Context themedContext) {
        boolean fullscreen = shellController != null && shellController.isFullscreenPreview();
        applyPillItemStyle(btnShortcutModeB, themedContext, currentMode == PreviewMode.MODE_B);
        applyPillItemStyle(btnShortcutModeC, themedContext, currentMode == PreviewMode.MODE_C);
        applyPillItemStyle(btnShortcutModeCSwap, themedContext, false);
        applyPillItemStyle(btnShortcutFullscreen, themedContext, fullscreen);
        applyPillItemStyle(btnShortcutBack, themedContext, false);
        applyPillItemStyle(btnShortcutSettings, themedContext, false);
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
                    stagingViews.remove(key);
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                }
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
        // Keep SurfaceTexture alive briefly until Camera2 recreateSession/closeCamera detaches it.
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

    public static int resolveDefaultPrimaryForGear(@Nullable Gear gear) {
        return gear == Gear.REVERSE ? PreviewLayoutModel.BACK : PreviewLayoutModel.FRONT;
    }

    public static int resolveEffectiveModeCPrimary(@Nullable Gear gear,
                                                   @Nullable Gear manualOverrideGear,
                                                   @Nullable Integer manualOverridePrimary) {
        if (manualOverridePrimary != null && manualOverrideGear != null && manualOverrideGear == gear) {
            return manualOverridePrimary == PreviewLayoutModel.BACK
                    ? PreviewLayoutModel.BACK : PreviewLayoutModel.FRONT;
        }
        return resolveDefaultPrimaryForGear(gear);
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
