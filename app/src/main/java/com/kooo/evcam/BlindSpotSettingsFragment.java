package com.kooo.evcam;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.blindspot.BlindSpotCorrectionMath;
import com.kooo.evcam.blindspot.BlindSpotCropPreviewView;
import com.kooo.evcam.blindspot.BlindSpotDisplayUtils;
import com.kooo.evcam.blindspot.BlindSpotPlacement;
import com.kooo.evcam.blindspot.BlindSpotVisualEditorView;
import com.kooo.evcam.blindspot.CameraMirrorPolicy;
import com.kooo.evcam.blindspot.MirrorTarget;
import com.kooo.evcam.ui.UiOptionPillHelper;
import com.kooo.evcam.ui.UiThemeController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 补盲与 HUD 投屏设置界面。
 *
 * <p>顶部统一配置补盲总开关与输出屏幕模式（仅主屏 / 仅副屏 / 主副屏同时），
 * 下方「画面位置与画面调整」卡片内按「选择屏幕 → 选择摄像头」统一调整每屏每路的位置大小（支持四路同步）、
 * 旋转角度、四边裁切及物理/输出镜像，消除重复入口并自动按副屏方向校正拖动坐标系。</p>
 */
public class BlindSpotSettingsFragment extends Fragment implements UiThemeController.ThemeAware {

    private static final String[][] TURN_SIGNAL_PRESETS = {
            {"xinghan7", "left front turn signal:1", "right front turn signal:1"},
    };
    private static final String[] MAIN_FLOATING_CAMERAS = {"front", "back", "left", "right"};
    private static final String[] MAIN_FLOATING_CAMERA_LABELS = {"前", "后", "左", "右"};
    private static final long LIVE_UPDATE_THROTTLE_MS = 40L;

    private AppConfig appConfig;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable throttledServiceUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            pendingServiceUpdateScheduled = false;
            Context ctx = getContext();
            if (ctx != null) {
                BlindSpotService.update(ctx);
            }
        }
    };
    private boolean pendingServiceUpdateScheduled = false;

    // Top Card: Master + Output Mode
    private SwitchMaterial blindSpotGlobalSwitch;
    private TextView tvBlindSpotGlobalHint;
    private TextView tvOutputModeHint;
    private Button btnOutputMainOnly;
    private Button btnOutputSecondaryOnly;
    private Button btnOutputBoth;
    private LinearLayout mainDisplayControlsLayout;
    private SwitchMaterial turnSignalReuseMainSwitch;
    private SwitchMaterial mainFloatingEnabledSwitch;
    private Spinner mainFloatingCameraSpinner;
    private LinearLayout secondaryDisplayControlsLayout;
    private Spinner secondaryOutputDisplaySpinner;
    private Button refreshDisplaysButton;
    private TextView tvSecondaryOutputInfo;
    private Button toggleSecondaryAdjustButton;
    private LinearLayout subFeaturesContainer;

    // Trigger Card
    private SwitchMaterial turnSignalLinkageSwitch;
    private TextView carApiStatusText;
    private Button btnTriggerVhal;
    private Button btnTriggerCarL6L7;
    private Button btnTriggerCarBoyueL;
    private Button btnTriggerLogcatXinghan7;
    private Button btnTriggerLogcatCustom;
    private LinearLayout logcatKeywordsLayout;
    private EditText turnSignalLeftLogEditText;
    private EditText turnSignalRightLogEditText;
    private SeekBar turnSignalTimeoutSeekBar;
    private TextView tvTurnSignalTimeout;
    private SwitchMaterial rainNightBlindSpotSwitch;

    // Unified Position & Camera Adjust Card
    private TextView tvPositionScopeBadge;
    private Spinner editorScreenSpinner;
    private Button refreshEditorDisplaysButton;
    private TextView tvDisplayResolutionInfo;
    private Button btnSlotFront;
    private Button btnSlotBack;
    private Button btnSlotLeft;
    private Button btnSlotRight;
    private Button btnSlotRainLeft;
    private Button btnSlotRainRight;
    private TextView tvCameraScopeHint;
    private TextView tvVisualEditorHint;
    private Button btnToggleVisualEdit;
    private BlindSpotVisualEditorView visualEditorView;
    private CheckBox cbSyncAllFourPositions;
    private View layoutSameSideRow;
    private SwitchMaterial sameSidePositionSwitch;
    private TextView tvNumericBoundsDesc;
    private SeekBar seekbarPosX;
    private SeekBar seekbarPosY;
    private SeekBar seekbarPosW;
    private SeekBar seekbarPosH;
    private EditText etPosX;
    private EditText etPosY;
    private EditText etPosW;
    private EditText etPosH;
    private TextView tvPosXPct;
    private TextView tvPosYPct;
    private TextView tvPosWPct;
    private TextView tvPosHPct;

    // Per-screen per-camera rotation, crop & mirror
    private TextView tvCameraRotationValue;
    private BlindSpotCropPreviewView cropPreviewView;
    private Button btnCamRot0;
    private Button btnCamRot90;
    private Button btnCamRot180;
    private Button btnCamRot270;
    private SeekBar seekbarCameraRotation;
    private TextView tvCropSummary;
    private SeekBar seekbarCropTop;
    private SeekBar seekbarCropBottom;
    private SeekBar seekbarCropLeft;
    private SeekBar seekbarCropRight;
    private TextView tvCropTopValue;
    private TextView tvCropBottomValue;
    private TextView tvCropLeftValue;
    private TextView tvCropRightValue;
    private Button btnPhysicalMirrorH;
    private Button btnPhysicalMirrorV;
    private Button btnOutputMirrorH;
    private Button btnOutputMirrorV;
    private TextView tvMirrorEffectiveState;

    // Screen-specific extra options
    private LinearLayout mainExtraOptionsLayout;
    private SwitchMaterial mainFloatingAspectLockSwitch;
    private SwitchMaterial mainFloatingLongPressDragSwitch;
    private LinearLayout secondaryExtraOptionsLayout;
    private Spinner secondaryOrientationSpinner;
    private SwitchMaterial secondaryBorderSwitch;
    private SeekBar seekbarSecondaryAlpha;
    private TextView tvSecondaryAlphaValue;
    private Button btnPreviewCurrentPosition;
    private Button btnResetCurrentPosition;

    // MJPEG Card (Card 4)
    private View cardMjpegStream;
    private Button adjustMjpegStreamButton;

    // Avoidance + Debug Card (Card 5)
    private SwitchMaterial blindSpotSuppressOn360Switch;
    private SwitchMaterial avmAvoidanceSwitch;
    private LinearLayout avmAvoidanceDetailLayout;
    private EditText avmAvoidanceActivityEditText;
    private EditText logFilterEditText;
    private Button logcatDebugButton;
    private Button resetStandaloneWindowButton;

    private Button menuButton;
    private Button homeButton;

    private final List<Integer> editorDisplayIds = new ArrayList<>();
    private final List<String> editorDisplayLabels = new ArrayList<>();
    private final List<Integer> secondaryOutputDisplayIds = new ArrayList<>();
    private final List<String> secondaryOutputDisplayLabels = new ArrayList<>();

    private int editorDisplayId = AppConfig.BLIND_SPOT_DISPLAY_MAIN;
    private String activeSlot = BlindSpotPlacement.SLOT_RIGHT;
    private int activeTriggerPresetIndex = 0; // 0=vhal,1=l6l7,2=boyue_l,3=xinghan7,4=custom
    private boolean isUpdatingPositionUi = false;
    private boolean isUpdatingTransformUi = false;
    private boolean isUpdatingDisplaySpinners = false;
    private boolean isSecondaryAdjustMode = false;
    private boolean isPreviewActive = false;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_secondary_display_settings, container, false);
        appConfig = new AppConfig(requireContext());
        bindViews(view);
        initSpinners();
        SettingsFragment.configureEmbeddedToolbar(this, view, "补盲");
        return view;
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        View view = getView();
        if (view != null) {
            loadSettings();
            initListeners(view);
        }
    }

    private void bindViews(View view) {
        blindSpotGlobalSwitch = view.findViewById(R.id.switch_blind_spot_global);
        tvBlindSpotGlobalHint = view.findViewById(R.id.tv_blind_spot_global_hint);
        tvOutputModeHint = view.findViewById(R.id.tv_output_mode_hint);
        btnOutputMainOnly = view.findViewById(R.id.btn_output_mode_main_only);
        btnOutputSecondaryOnly = view.findViewById(R.id.btn_output_mode_secondary_only);
        btnOutputBoth = view.findViewById(R.id.btn_output_mode_both);
        mainDisplayControlsLayout = view.findViewById(R.id.layout_main_display_controls);
        turnSignalReuseMainSwitch = view.findViewById(R.id.switch_turn_signal_reuse_main);
        mainFloatingEnabledSwitch = view.findViewById(R.id.switch_main_floating_enabled);
        mainFloatingCameraSpinner = view.findViewById(R.id.spinner_main_floating_camera);
        secondaryDisplayControlsLayout = view.findViewById(R.id.layout_secondary_display_controls);
        secondaryOutputDisplaySpinner = view.findViewById(R.id.spinner_secondary_output_display);
        refreshDisplaysButton = view.findViewById(R.id.btn_refresh_displays);
        tvSecondaryOutputInfo = view.findViewById(R.id.tv_secondary_output_info);
        toggleSecondaryAdjustButton = view.findViewById(R.id.btn_toggle_secondary_adjust_mode);
        subFeaturesContainer = view.findViewById(R.id.blind_spot_sub_features_container);

        turnSignalLinkageSwitch = view.findViewById(R.id.switch_turn_signal_linkage);
        carApiStatusText = view.findViewById(R.id.tv_car_api_status);
        btnTriggerVhal = view.findViewById(R.id.btn_trigger_vhal);
        btnTriggerCarL6L7 = view.findViewById(R.id.btn_trigger_car_l6l7);
        btnTriggerCarBoyueL = view.findViewById(R.id.btn_trigger_car_boyuel);
        btnTriggerLogcatXinghan7 = view.findViewById(R.id.btn_trigger_logcat_xinghan7);
        btnTriggerLogcatCustom = view.findViewById(R.id.btn_trigger_logcat_custom);
        logcatKeywordsLayout = view.findViewById(R.id.layout_logcat_keywords);
        turnSignalLeftLogEditText = view.findViewById(R.id.et_turn_signal_left_log);
        turnSignalRightLogEditText = view.findViewById(R.id.et_turn_signal_right_log);
        turnSignalTimeoutSeekBar = view.findViewById(R.id.seekbar_turn_signal_timeout);
        tvTurnSignalTimeout = view.findViewById(R.id.tv_turn_signal_timeout_value);
        rainNightBlindSpotSwitch = view.findViewById(R.id.switch_rain_night_blind_spot);

        tvPositionScopeBadge = view.findViewById(R.id.tv_position_scope_badge);
        editorScreenSpinner = view.findViewById(R.id.spinner_editor_screen);
        refreshEditorDisplaysButton = view.findViewById(R.id.btn_refresh_editor_displays);
        tvDisplayResolutionInfo = view.findViewById(R.id.tv_display_resolution_info);
        btnSlotFront = view.findViewById(R.id.btn_slot_front);
        btnSlotBack = view.findViewById(R.id.btn_slot_back);
        btnSlotLeft = view.findViewById(R.id.btn_slot_left);
        btnSlotRight = view.findViewById(R.id.btn_slot_right);
        btnSlotRainLeft = view.findViewById(R.id.btn_slot_rain_left);
        btnSlotRainRight = view.findViewById(R.id.btn_slot_rain_right);
        tvCameraScopeHint = view.findViewById(R.id.tv_camera_scope_hint);
        tvVisualEditorHint = view.findViewById(R.id.tv_visual_editor_hint);
        btnToggleVisualEdit = view.findViewById(R.id.btn_toggle_visual_edit);
        visualEditorView = view.findViewById(R.id.blind_spot_visual_editor);
        cbSyncAllFourPositions = view.findViewById(R.id.cb_sync_all_four_positions);
        layoutSameSideRow = view.findViewById(R.id.layout_same_side_row);
        sameSidePositionSwitch = view.findViewById(R.id.switch_same_side_position);
        tvNumericBoundsDesc = view.findViewById(R.id.tv_numeric_bounds_desc);
        seekbarPosX = view.findViewById(R.id.seekbar_pos_x);
        seekbarPosY = view.findViewById(R.id.seekbar_pos_y);
        seekbarPosW = view.findViewById(R.id.seekbar_pos_w);
        seekbarPosH = view.findViewById(R.id.seekbar_pos_h);
        etPosX = view.findViewById(R.id.et_pos_x);
        etPosY = view.findViewById(R.id.et_pos_y);
        etPosW = view.findViewById(R.id.et_pos_w);
        etPosH = view.findViewById(R.id.et_pos_h);
        tvPosXPct = view.findViewById(R.id.tv_pos_x_pct);
        tvPosYPct = view.findViewById(R.id.tv_pos_y_pct);
        tvPosWPct = view.findViewById(R.id.tv_pos_w_pct);
        tvPosHPct = view.findViewById(R.id.tv_pos_h_pct);

        tvCameraRotationValue = view.findViewById(R.id.tv_camera_rotation_value);
        cropPreviewView = view.findViewById(R.id.blind_spot_crop_preview);
        btnCamRot0 = view.findViewById(R.id.btn_cam_rot_0);
        btnCamRot90 = view.findViewById(R.id.btn_cam_rot_90);
        btnCamRot180 = view.findViewById(R.id.btn_cam_rot_180);
        btnCamRot270 = view.findViewById(R.id.btn_cam_rot_270);
        seekbarCameraRotation = view.findViewById(R.id.seekbar_camera_rotation);
        tvCropSummary = view.findViewById(R.id.tv_crop_summary);
        seekbarCropTop = view.findViewById(R.id.seekbar_crop_top);
        seekbarCropBottom = view.findViewById(R.id.seekbar_crop_bottom);
        seekbarCropLeft = view.findViewById(R.id.seekbar_crop_left);
        seekbarCropRight = view.findViewById(R.id.seekbar_crop_right);
        tvCropTopValue = view.findViewById(R.id.tv_crop_top_value);
        tvCropBottomValue = view.findViewById(R.id.tv_crop_bottom_value);
        tvCropLeftValue = view.findViewById(R.id.tv_crop_left_value);
        tvCropRightValue = view.findViewById(R.id.tv_crop_right_value);
        btnPhysicalMirrorH = view.findViewById(R.id.btn_physical_mirror_h);
        btnPhysicalMirrorV = view.findViewById(R.id.btn_physical_mirror_v);
        btnOutputMirrorH = view.findViewById(R.id.btn_output_mirror_h);
        btnOutputMirrorV = view.findViewById(R.id.btn_output_mirror_v);
        tvMirrorEffectiveState = view.findViewById(R.id.tv_mirror_effective_state);

        mainExtraOptionsLayout = view.findViewById(R.id.layout_main_extra_options);
        mainFloatingAspectLockSwitch = view.findViewById(R.id.switch_main_floating_aspect_ratio_lock);
        mainFloatingLongPressDragSwitch = view.findViewById(R.id.switch_main_floating_long_press_drag);
        secondaryExtraOptionsLayout = view.findViewById(R.id.layout_secondary_extra_options);
        secondaryOrientationSpinner = view.findViewById(R.id.spinner_secondary_orientation);
        secondaryBorderSwitch = view.findViewById(R.id.switch_secondary_border);
        seekbarSecondaryAlpha = view.findViewById(R.id.seekbar_secondary_alpha);
        tvSecondaryAlphaValue = view.findViewById(R.id.tv_secondary_alpha_value);
        btnPreviewCurrentPosition = view.findViewById(R.id.btn_preview_current_position);
        btnResetCurrentPosition = view.findViewById(R.id.btn_reset_current_position);

        cardMjpegStream = view.findViewById(R.id.card_mjpeg_stream);
        adjustMjpegStreamButton = view.findViewById(R.id.btn_adjust_mjpeg_stream);
        if (cardMjpegStream != null) {
            cardMjpegStream.setVisibility(AppFeatures.NETWORK_STREAMING ? View.VISIBLE : View.GONE);
        }

        if (!AppFeatures.SECONDARY_DISPLAY) {
            if (btnOutputSecondaryOnly != null) btnOutputSecondaryOnly.setVisibility(View.GONE);
            if (btnOutputBoth != null) btnOutputBoth.setVisibility(View.GONE);
        }

        blindSpotSuppressOn360Switch = view.findViewById(R.id.switch_blind_spot_suppress_on_360);
        avmAvoidanceSwitch = view.findViewById(R.id.switch_avm_avoidance);
        avmAvoidanceDetailLayout = view.findViewById(R.id.layout_avm_avoidance_detail);
        avmAvoidanceActivityEditText = view.findViewById(R.id.et_avm_avoidance_activity);
        logFilterEditText = view.findViewById(R.id.et_log_filter);
        logcatDebugButton = view.findViewById(R.id.btn_logcat_debug);
        resetStandaloneWindowButton = view.findViewById(R.id.btn_reset_standalone_window);
        menuButton = view.findViewById(R.id.btn_menu);
        homeButton = view.findViewById(R.id.btn_home);

        bindDisclosure(view, R.id.ui_debug_toggle, R.id.ui_debug_section, "高级调试");
    }

    private void bindDisclosure(View root, int buttonId, int panelId, String label) {
        Button button = root.findViewById(buttonId);
        View panel = root.findViewById(panelId);
        if (button == null || panel == null) return;
        button.setText(label + (panel.getVisibility() == View.VISIBLE ? "  ⌄" : "  ›"));
        UiOptionPillHelper.apply(button, root.getContext(), false);
        button.setOnClickListener(v -> {
            boolean show = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(show ? View.VISIBLE : View.GONE);
            button.setText(label + (show ? "  ⌄" : "  ›"));
        });
    }

    private void initSpinners() {
        ArrayAdapter<String> camAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, MAIN_FLOATING_CAMERA_LABELS);
        camAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mainFloatingCameraSpinner.setAdapter(camAdapter);

        String[] orientations = {
                "0° (正常横屏)",
                "90° (顺时针 90°)",
                "180° (倒置屏 · 自动校正方向)",
                "270° (逆时针 90°)"
        };
        ArrayAdapter<String> orientAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, orientations);
        orientAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        secondaryOrientationSpinner.setAdapter(orientAdapter);

        populateDisplaySpinners();
    }

    private void populateDisplaySpinners() {
        Context ctx = getContext();
        if (ctx == null) return;
        editorDisplayIds.clear();
        editorDisplayLabels.clear();
        secondaryOutputDisplayIds.clear();
        secondaryOutputDisplayLabels.clear();

        int[] mainSize = BlindSpotDisplayUtils.getMainScreenSizePx(ctx);
        editorDisplayIds.add(AppConfig.BLIND_SPOT_DISPLAY_MAIN);
        editorDisplayLabels.add(String.format(Locale.US, "主屏（车机主屏幕 · %d×%d）", mainSize[0], mainSize[1]));

        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            Display[] displays = dm.getDisplays();
            for (Display d : displays) {
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    DisplayMetrics out = new DisplayMetrics();
                    d.getRealMetrics(out);
                    String name = d.getName();
                    String suffix = (name != null && !name.isEmpty()) ? (" - " + name) : "";
                    String label = String.format(
                            Locale.US, "副屏 Display #%d (%d×%d)%s",
                            d.getDisplayId(), out.widthPixels, out.heightPixels, suffix);
                    editorDisplayIds.add(d.getDisplayId());
                    editorDisplayLabels.add(label);
                    secondaryOutputDisplayIds.add(d.getDisplayId());
                    secondaryOutputDisplayLabels.add(label);
                }
            }
        }

        EvccDashcastDisplayResolver.Info evccInfo = EvccDashcastDisplayResolver.queryPrimary(ctx);
        String evccLabel = evccInfo != null
                ? String.format(Locale.US, "EVCC 仪表投屏（已连接 Display #%d · %d×%d）",
                        evccInfo.displayId, evccInfo.widthPx, evccInfo.heightPx)
                : "EVCC 仪表投屏（占位 · 投屏后自动适配）";
        editorDisplayIds.add(EvccDashcastDisplayResolver.PICK_ID);
        editorDisplayLabels.add(evccLabel);
        secondaryOutputDisplayIds.add(EvccDashcastDisplayResolver.PICK_ID);
        secondaryOutputDisplayLabels.add(evccLabel);

        isUpdatingDisplaySpinners = true;
        try {
            ArrayAdapter<String> editorAdapter = new ArrayAdapter<>(
                    ctx, android.R.layout.simple_spinner_item, editorDisplayLabels);
            editorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            editorScreenSpinner.setAdapter(editorAdapter);

            int savedEditorTarget = appConfig.getBlindSpotEditorDisplayTarget();
            int editorIdx = editorDisplayIds.indexOf(savedEditorTarget);
            if (editorIdx < 0) editorIdx = 0;
            editorScreenSpinner.setSelection(editorIdx, false);
            editorDisplayId = editorDisplayIds.get(editorIdx);

            ArrayAdapter<String> secOutAdapter = new ArrayAdapter<>(
                    ctx, android.R.layout.simple_spinner_item, secondaryOutputDisplayLabels);
            secOutAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            secondaryOutputDisplaySpinner.setAdapter(secOutAdapter);

            int savedSecId = appConfig.getSecondaryDisplayId();
            int secIdx = secondaryOutputDisplayIds.indexOf(savedSecId);
            if (secIdx < 0) {
                secIdx = 0;
                if (appConfig.isSecondaryDisplayEnabled() && !secondaryOutputDisplayIds.isEmpty()) {
                    appConfig.setSecondaryDisplayId(secondaryOutputDisplayIds.get(0));
                }
            }
            if (!secondaryOutputDisplayIds.isEmpty()) {
                secondaryOutputDisplaySpinner.setSelection(secIdx, false);
            }
        } finally {
            isUpdatingDisplaySpinners = false;
        }
    }

    private void loadSettings() {
        boolean globalEnabled = appConfig.isBlindSpotGlobalEnabled();
        blindSpotGlobalSwitch.setChecked(globalEnabled);
        updateGlobalHintUi(globalEnabled);

        turnSignalReuseMainSwitch.setChecked(appConfig.isTurnSignalReuseMainFloating());
        mainFloatingEnabledSwitch.setChecked(appConfig.isMainFloatingEnabled());
        int camIndex = Arrays.asList(MAIN_FLOATING_CAMERAS).indexOf(appConfig.getMainFloatingCamera());
        mainFloatingCameraSpinner.setSelection(Math.max(0, camIndex), false);
        mainFloatingAspectLockSwitch.setChecked(appConfig.isMainFloatingAspectRatioLocked());
        mainFloatingLongPressDragSwitch.setChecked(appConfig.isMainFloatingLongPressDragEnabled());

        turnSignalLinkageSwitch.setChecked(appConfig.isTurnSignalLinkageEnabled());
        int timeout = appConfig.getTurnSignalTimeout();
        turnSignalTimeoutSeekBar.setProgress(timeout);
        updateTimeoutLabel(timeout);
        rainNightBlindSpotSwitch.setChecked(appConfig.isRainNightBlindSpotEnabled());

        turnSignalLeftLogEditText.setText(appConfig.getTurnSignalLeftTriggerLog());
        turnSignalRightLogEditText.setText(appConfig.getTurnSignalRightTriggerLog());
        syncTriggerSchemeFromConfig();

        sameSidePositionSwitch.setChecked(appConfig.isBlindSpotUseSameSidePosition());
        blindSpotSuppressOn360Switch.setChecked(appConfig.isBlindSpotSuppressOn360());

        boolean avmEnabled = appConfig.isAvmAvoidanceEnabled();
        avmAvoidanceSwitch.setChecked(avmEnabled);
        avmAvoidanceDetailLayout.setVisibility(avmEnabled ? View.VISIBLE : View.GONE);
        avmAvoidanceActivityEditText.setText(appConfig.getAvmAvoidanceActivity());

        updateOutputModeUi();
        updateEditorTargetUi();
        updateEditToggleUi();
        updateSlotTabsUi();
        styleStaticActionPills(requireContext());
    }

    private void styleStaticActionPills(Context ctx) {
        if (ctx == null) return;
        UiOptionPillHelper.apply(toggleSecondaryAdjustButton, ctx, isSecondaryAdjustMode, true);
        UiOptionPillHelper.apply(btnPreviewCurrentPosition, ctx, isPreviewActive, true);
        UiOptionPillHelper.apply(refreshDisplaysButton, ctx, false);
        UiOptionPillHelper.apply(refreshEditorDisplaysButton, ctx, false);
        UiOptionPillHelper.apply(btnResetCurrentPosition, ctx, false);
        UiOptionPillHelper.apply(adjustMjpegStreamButton, ctx, false);
        UiOptionPillHelper.apply(logcatDebugButton, ctx, false);
        UiOptionPillHelper.apply(resetStandaloneWindowButton, ctx, false);
    }

    private void updateGlobalHintUi(boolean globalEnabled) {
        tvBlindSpotGlobalHint.setText(globalEnabled
                ? "已开启：按下方选定的显示位置（主屏 / 副屏 / 同时）触发补盲"
                : "总开关已关闭（下方参数仍可提前配置与预览）");
        subFeaturesContainer.setAlpha(globalEnabled ? 1.0f : 0.72f);
    }

    private void updateOutputModeUi() {
        Context ctx = getContext();
        String mode = appConfig.getBlindSpotOutputMode();
        boolean showMain = AppConfig.BLIND_SPOT_OUTPUT_MAIN_ONLY.equals(mode)
                || AppConfig.BLIND_SPOT_OUTPUT_BOTH.equals(mode);
        boolean showSec = AppConfig.BLIND_SPOT_OUTPUT_SECONDARY_ONLY.equals(mode)
                || AppConfig.BLIND_SPOT_OUTPUT_BOTH.equals(mode);

        mainDisplayControlsLayout.setVisibility(showMain ? View.VISIBLE : View.GONE);
        secondaryDisplayControlsLayout.setVisibility(showSec ? View.VISIBLE : View.GONE);

        if (AppConfig.BLIND_SPOT_OUTPUT_SECONDARY_ONLY.equals(mode)) {
            tvOutputModeHint.setText("仅在副屏 / EVCC 显示（主屏不弹出）");
        } else if (AppConfig.BLIND_SPOT_OUTPUT_BOTH.equals(mode)) {
            tvOutputModeHint.setText("主屏与副屏同时显示");
        } else {
            tvOutputModeHint.setText("仅在主屏显示");
        }

        if (ctx != null) {
            UiOptionPillHelper.apply(btnOutputMainOnly, ctx, AppConfig.BLIND_SPOT_OUTPUT_MAIN_ONLY.equals(mode), true);
            UiOptionPillHelper.apply(btnOutputSecondaryOnly, ctx, AppConfig.BLIND_SPOT_OUTPUT_SECONDARY_ONLY.equals(mode), true);
            UiOptionPillHelper.apply(btnOutputBoth, ctx, AppConfig.BLIND_SPOT_OUTPUT_BOTH.equals(mode), true);
        }

        updateSecondaryOutputInfo();
    }

    private void updateSecondaryOutputInfo() {
        Context ctx = getContext();
        if (ctx == null || tvSecondaryOutputInfo == null) return;
        int secId = appConfig.getSecondaryDisplayId();
        if (secId == Display.DEFAULT_DISPLAY) {
            tvSecondaryOutputInfo.setText("尚未指定副屏，请选择外接副屏或 EVCC 仪表投屏");
            return;
        }
        if (secId == EvccDashcastDisplayResolver.PICK_ID) {
            EvccDashcastDisplayResolver.Info info = EvccDashcastDisplayResolver.queryPrimary(ctx);
            if (info != null) {
                tvSecondaryOutputInfo.setText(String.format(
                        Locale.US, "EVCC 仪表投屏已连接（Display #%d · %d×%d）",
                        info.displayId, info.widthPx, info.heightPx));
            } else {
                tvSecondaryOutputInfo.setText("EVCC 仪表投屏未启动；投屏运行后将自动匹配");
            }
            return;
        }
        int[] dims = BlindSpotDisplayUtils.getDisplaySizePx(ctx, secId);
        int orient = appConfig.getBlindSpotScreenOrientation(BlindSpotPlacement.screenKeyForDisplay(secId));
        tvSecondaryOutputInfo.setText(String.format(
                Locale.US, "副屏 Display #%d · 物理分辨率 %d×%d · 屏幕方向 %d°",
                secId, dims[0], dims[1], orient));
    }

    private void syncTriggerSchemeFromConfig() {
        if (appConfig.isVhalGrpcTriggerMode()) {
            activeTriggerPresetIndex = 0;
            checkVhalGrpcConnection();
        } else if (appConfig.isCarSignalManagerTriggerMode()) {
            activeTriggerPresetIndex = "boyue_l".equals(appConfig.getTurnSignalPresetSelection()) ? 2 : 1;
            checkCarSignalManagerConnection();
        } else {
            String left = appConfig.getTurnSignalLeftTriggerLog();
            String right = appConfig.getTurnSignalRightTriggerLog();
            if (TURN_SIGNAL_PRESETS[0][1].equals(left) && TURN_SIGNAL_PRESETS[0][2].equals(right)) {
                activeTriggerPresetIndex = 3;
            } else {
                activeTriggerPresetIndex = 4;
            }
            carApiStatusText.setText(activeTriggerPresetIndex == 3
                    ? "触发状态: Logcat 监听中（星舰7预设）"
                    : "触发状态: Logcat 监听中（自定义关键字）");
        }
        updateTriggerPresetPillsUi();
    }

    private void updateTriggerPresetPillsUi() {
        Context ctx = getContext();
        if (ctx == null) return;
        UiOptionPillHelper.apply(btnTriggerVhal, ctx, activeTriggerPresetIndex == 0);
        UiOptionPillHelper.apply(btnTriggerCarL6L7, ctx, activeTriggerPresetIndex == 1);
        UiOptionPillHelper.apply(btnTriggerCarBoyueL, ctx, activeTriggerPresetIndex == 2);
        UiOptionPillHelper.apply(btnTriggerLogcatXinghan7, ctx, activeTriggerPresetIndex == 3);
        UiOptionPillHelper.apply(btnTriggerLogcatCustom, ctx, activeTriggerPresetIndex == 4);
        boolean isLogcatMode = activeTriggerPresetIndex >= 3;
        logcatKeywordsLayout.setVisibility(isLogcatMode ? View.VISIBLE : View.GONE);
    }

    private boolean isMainEditorSelected() {
        return editorDisplayId == AppConfig.BLIND_SPOT_DISPLAY_MAIN;
    }

    @NonNull
    private String getActiveScreenKey() {
        return BlindSpotPlacement.screenKeyForDisplay(editorDisplayId);
    }

    @NonNull
    private String getActiveCameraPos() {
        return BlindSpotPlacement.slotToCameraPos(activeSlot);
    }

    @NonNull
    private int[] resolveEditorRawScreenDimensions() {
        Context ctx = requireContext();
        if (isMainEditorSelected()) {
            return BlindSpotDisplayUtils.getMainScreenSizePx(ctx);
        }
        if (editorDisplayId == EvccDashcastDisplayResolver.PICK_ID) {
            EvccDashcastDisplayResolver.Info info = EvccDashcastDisplayResolver.queryPrimary(ctx);
            if (info != null && info.widthPx > 0 && info.heightPx > 0) {
                return new int[]{info.widthPx, info.heightPx};
            }
            return new int[]{1920, 720};
        }
        return BlindSpotDisplayUtils.getDisplaySizePx(ctx, editorDisplayId);
    }

    @NonNull
    private int[] resolveEditorVisualDimensions() {
        int[] raw = resolveEditorRawScreenDimensions();
        int orient = isMainEditorSelected()
                ? 0
                : appConfig.getBlindSpotScreenOrientation(getActiveScreenKey());
        return BlindSpotPlacement.visualScreenDimensions(raw[0], raw[1], orient);
    }

    private void updateEditorTargetUi() {
        boolean isMain = isMainEditorSelected();
        mainExtraOptionsLayout.setVisibility(isMain ? View.VISIBLE : View.GONE);
        secondaryExtraOptionsLayout.setVisibility(isMain ? View.GONE : View.VISIBLE);

        String screenKey = getActiveScreenKey();
        int[] rawDims = resolveEditorRawScreenDimensions();
        int orient = isMain ? 0 : appConfig.getBlindSpotScreenOrientation(screenKey);
        int[] visDims = BlindSpotPlacement.visualScreenDimensions(rawDims[0], rawDims[1], orient);

        if (isMain) {
            tvDisplayResolutionInfo.setText(String.format(
                    Locale.US,
                    "正在调整【主屏】(%d×%d)：此屏幕的各路位置、旋转、裁切与镜像均独立保存",
                    rawDims[0], rawDims[1]));
            tvPositionScopeBadge.setText(String.format(Locale.US, "主屏 %d×%d", visDims[0], visDims[1]));
            tvNumericBoundsDesc.setText(String.format(
                    Locale.US,
                    "数值坐标按主屏可视尺寸 (%d×%d) 换算，与上方画布实时联动",
                    visDims[0], visDims[1]));
        } else if (editorDisplayId == EvccDashcastDisplayResolver.PICK_ID) {
            EvccDashcastDisplayResolver.Info info = EvccDashcastDisplayResolver.queryPrimary(requireContext());
            if (info != null) {
                tvDisplayResolutionInfo.setText(String.format(
                        Locale.US,
                        "正在调整【EVCC 仪表投屏】Display #%d (%d×%d · 方向 %d°)：参数与主屏及其他副屏独立",
                        info.displayId, rawDims[0], rawDims[1], orient));
            } else {
                tvDisplayResolutionInfo.setText(String.format(
                        Locale.US,
                        "正在调整【EVCC 仪表投屏】（未连接，暂按 %d×%d 预设 · 方向 %d°）",
                        rawDims[0], rawDims[1], orient));
            }
            tvPositionScopeBadge.setText(String.format(Locale.US, "EVCC %d×%d (%d°)", visDims[0], visDims[1], orient));
            tvNumericBoundsDesc.setText(String.format(
                    Locale.US,
                    "视觉坐标系 (%d×%d)：右移增大 X、下移增大 Y，旋转朝向由系统自动映射",
                    visDims[0], visDims[1]));
        } else {
            tvDisplayResolutionInfo.setText(String.format(
                    Locale.US,
                    "正在调整【副屏 #%d】(%d×%d · 方向 %d°)：参数与主屏及其他副屏完全独立",
                    editorDisplayId, rawDims[0], rawDims[1], orient));
            tvPositionScopeBadge.setText(String.format(
                    Locale.US, "副屏 #%d · %d×%d (%d°)", editorDisplayId, visDims[0], visDims[1], orient));
            tvNumericBoundsDesc.setText(String.format(
                    Locale.US,
                    "视觉坐标系 (%d×%d)：往右拖/增大 X 即往右移、往下拖/增大 Y 即往下移（自动适配 %d° 屏幕方向）",
                    visDims[0], visDims[1], orient));
        }

        if (!isMain) {
            isUpdatingPositionUi = true;
            try {
                secondaryOrientationSpinner.setSelection(
                        Math.max(0, Math.min(3, orient / 90)), false);
                secondaryBorderSwitch.setChecked(appConfig.isBlindSpotScreenBorderEnabled(screenKey));
                int alpha = appConfig.getBlindSpotScreenAlpha(screenKey);
                seekbarSecondaryAlpha.setProgress(alpha);
                tvSecondaryAlphaValue.setText(alpha + "%");
            } finally {
                isUpdatingPositionUi = false;
            }
        }

        updateCameraScopeHint();
        updateSameSideRowVisibility();
        syncPositionControlsFromStorage();
        syncCameraTransformControls();
    }

    private void updateCameraScopeHint() {
        if (tvCameraScopeHint == null) return;
        String screenLabel = getActiveScreenShortLabel();
        String slotLabel = BlindSpotPlacement.getSlotDisplayName(activeSlot);
        if (BlindSpotPlacement.SLOT_RAIN_LEFT.equals(activeSlot)
                || BlindSpotPlacement.SLOT_RAIN_RIGHT.equals(activeSlot)) {
            String camLabel = BlindSpotPlacement.SLOT_RAIN_LEFT.equals(activeSlot) ? "左" : "右";
            tvCameraScopeHint.setText(String.format(
                    Locale.US,
                    "当前调整：【%s】· %s（位置独立保存；旋转、裁切与镜像跟随「%s路」配置）",
                    screenLabel, slotLabel, camLabel));
        } else {
            tvCameraScopeHint.setText(String.format(
                    Locale.US,
                    "当前调整：【%s】· %s路摄像头（旋转/裁切/输出镜像按屏幕+摄像头独立生效）",
                    screenLabel, slotLabel));
        }
    }

    @NonNull
    private String getActiveScreenShortLabel() {
        if (isMainEditorSelected()) {
            return "主屏";
        }
        if (editorDisplayId == EvccDashcastDisplayResolver.PICK_ID) {
            return "EVCC 仪表";
        }
        return "副屏 #" + editorDisplayId;
    }

    private void updateSameSideRowVisibility() {
        if (layoutSameSideRow == null) return;
        boolean syncAllFour = appConfig.isBlindSpotSyncAllFourPositions(getActiveScreenKey());
        boolean showSameSide = isMainEditorSelected() && !syncAllFour;
        layoutSameSideRow.setVisibility(showSameSide ? View.VISIBLE : View.GONE);
    }

    private void syncPositionControlsFromStorage() {
        isUpdatingPositionUi = true;
        try {
            String screenKey = getActiveScreenKey();
            int[] rawDims = resolveEditorRawScreenDimensions();
            int[] visDims = resolveEditorVisualDimensions();
            int visW = Math.max(1, visDims[0]);
            int visH = Math.max(1, visDims[1]);

            visualEditorView.setCanvasAspectRatio(visW, visH);
            seekbarPosX.setMax(visW);
            seekbarPosY.setMax(visH);
            seekbarPosW.setMax(visW);
            seekbarPosH.setMax(visH);

            boolean syncAllFour = appConfig.isBlindSpotSyncAllFourPositions(screenKey);
            cbSyncAllFourPositions.setChecked(syncAllFour);
            sameSidePositionSwitch.setChecked(appConfig.isBlindSpotUseSameSidePosition());
            updateSameSideRowVisibility();

            BlindSpotPlacement.RectRatio rect = appConfig.getBlindSpotScreenPlacement(
                    screenKey, activeSlot, rawDims[0], rawDims[1]);
            int[] px = rect.toPixelBounds(visW, visH);

            visualEditorView.setPlacement(activeSlot, rect);
            applyPixelNumbersToFields(px[0], px[1], px[2], px[3], rect);
        } finally {
            isUpdatingPositionUi = false;
        }
    }

    private void syncCameraTransformControls() {
        isUpdatingTransformUi = true;
        try {
            String screenKey = getActiveScreenKey();
            String camPos = getActiveCameraPos();

            int rotation = appConfig.getBlindSpotScreenCameraRotation(screenKey, camPos);
            seekbarCameraRotation.setProgress(rotation);

            BlindSpotCorrectionMath.EdgeCrop crop =
                    appConfig.getBlindSpotScreenCameraEdgeCrop(screenKey, camPos);
            int topPct = Math.round(crop.top * 100f);
            int bottomPct = Math.round(crop.bottom * 100f);
            int leftPct = Math.round(crop.left * 100f);
            int rightPct = Math.round(crop.right * 100f);

            seekbarCropTop.setProgress(topPct);
            seekbarCropBottom.setProgress(bottomPct);
            seekbarCropLeft.setProgress(leftPct);
            seekbarCropRight.setProgress(rightPct);
            tvCropTopValue.setText(topPct + "%");
            tvCropBottomValue.setText(bottomPct + "%");
            tvCropLeftValue.setText(leftPct + "%");
            tvCropRightValue.setText(rightPct + "%");

            updateRotationAndCropVisuals();
            updateMirrorUi();
        } finally {
            isUpdatingTransformUi = false;
        }
    }

    private void applyPixelNumbersToFields(
            int pxX, int pxY, int pxW, int pxH, BlindSpotPlacement.RectRatio rect) {
        seekbarPosX.setProgress(Math.min(seekbarPosX.getMax(), Math.max(0, pxX)));
        seekbarPosY.setProgress(Math.min(seekbarPosY.getMax(), Math.max(0, pxY)));
        seekbarPosW.setProgress(Math.min(seekbarPosW.getMax(), Math.max(1, pxW)));
        seekbarPosH.setProgress(Math.min(seekbarPosH.getMax(), Math.max(1, pxH)));

        setTextIfDiff(etPosX, String.valueOf(pxX));
        setTextIfDiff(etPosY, String.valueOf(pxY));
        setTextIfDiff(etPosW, String.valueOf(pxW));
        setTextIfDiff(etPosH, String.valueOf(pxH));

        tvPosXPct.setText(rect.xPercent() + "%");
        tvPosYPct.setText(rect.yPercent() + "%");
        tvPosWPct.setText(rect.widthPercent() + "%");
        tvPosHPct.setText(rect.heightPercent() + "%");
    }

    private void setTextIfDiff(EditText et, String value) {
        if (et == null) return;
        if (!value.equals(et.getText().toString()) && !et.hasFocus()) {
            et.setText(value);
        }
    }

    private void updateSlotTabsUi() {
        Context ctx = getContext();
        if (ctx == null) return;
        UiOptionPillHelper.apply(btnSlotFront, ctx, BlindSpotPlacement.SLOT_FRONT.equals(activeSlot));
        UiOptionPillHelper.apply(btnSlotBack, ctx, BlindSpotPlacement.SLOT_BACK.equals(activeSlot));
        UiOptionPillHelper.apply(btnSlotLeft, ctx, BlindSpotPlacement.SLOT_LEFT.equals(activeSlot));
        UiOptionPillHelper.apply(btnSlotRight, ctx, BlindSpotPlacement.SLOT_RIGHT.equals(activeSlot));
        UiOptionPillHelper.apply(btnSlotRainLeft, ctx, BlindSpotPlacement.SLOT_RAIN_LEFT.equals(activeSlot));
        UiOptionPillHelper.apply(btnSlotRainRight, ctx, BlindSpotPlacement.SLOT_RAIN_RIGHT.equals(activeSlot));
    }

    private void updateEditToggleUi() {
        Context ctx = getContext();
        if (ctx == null) return;
        boolean editing = visualEditorView.isEditingEnabled();
        btnToggleVisualEdit.setText(editing ? "完成编辑" : "进入编辑");
        tvVisualEditorHint.setText(editing
                ? "拖动画面移动，拖右下角缩放（所有屏幕方向均已按正面视觉校正）"
                : "点击进入编辑后可直接调整画面");
        UiOptionPillHelper.apply(btnToggleVisualEdit, ctx, editing, true);
    }

    private void updateRotationAndCropVisuals() {
        Context ctx = getContext();
        String screenKey = getActiveScreenKey();
        String camPos = getActiveCameraPos();
        int rotation = appConfig.getBlindSpotScreenCameraRotation(screenKey, camPos);
        BlindSpotCorrectionMath.EdgeCrop crop =
                appConfig.getBlindSpotScreenCameraEdgeCrop(screenKey, camPos);

        tvCameraRotationValue.setText(rotation + "°");
        tvCropSummary.setText(String.format(
                Locale.US,
                "保留 %d%% × %d%%",
                Math.round(crop.visibleWidth() * 100f),
                Math.round(crop.visibleHeight() * 100f)));
        cropPreviewView.setPreviewState(
                getActiveScreenShortLabel() + " · " + getCameraShortLabel(camPos),
                rotation,
                crop);

        if (ctx != null) {
            UiOptionPillHelper.apply(btnCamRot0, ctx, rotation == 0);
            UiOptionPillHelper.apply(btnCamRot90, ctx, rotation == 90);
            UiOptionPillHelper.apply(btnCamRot180, ctx, rotation == 180);
            UiOptionPillHelper.apply(btnCamRot270, ctx, rotation == 270);
        }
    }

    private void updateMirrorUi() {
        Context ctx = getContext();
        String camPos = getActiveCameraPos();
        MirrorTarget mirrorTarget = isMainEditorSelected()
                ? MirrorTarget.BLIND_SPOT
                : MirrorTarget.SECONDARY_HUD;

        boolean physH = appConfig.isPhysicalMirrorH(camPos);
        boolean physV = appConfig.isPhysicalMirrorV(camPos);
        boolean outH = appConfig.isOutputMirrorH(mirrorTarget, camPos);
        boolean outV = appConfig.isOutputMirrorV(mirrorTarget, camPos);

        if (ctx != null) {
            UiOptionPillHelper.apply(btnPhysicalMirrorH, ctx, physH);
            UiOptionPillHelper.apply(btnPhysicalMirrorV, ctx, physV);
            UiOptionPillHelper.apply(btnOutputMirrorH, ctx, outH);
            UiOptionPillHelper.apply(btnOutputMirrorV, ctx, outV);
        }

        CameraMirrorPolicy.ResolvedMirror resolved =
                CameraMirrorPolicy.resolve(appConfig, mirrorTarget, camPos);
        String targetLabel = isMainEditorSelected() ? "主屏补盲" : "副屏输出";
        tvMirrorEffectiveState.setText(String.format(
                Locale.US,
                "物理(%s/%s) + %s(%s/%s) → 实际生效(水平:%s, 垂直:%s)",
                physH ? "反" : "正",
                physV ? "倒" : "正",
                targetLabel,
                outH ? "反" : "正",
                outV ? "倒" : "正",
                resolved.effectiveH ? "镜像" : "正常",
                resolved.effectiveV ? "翻转" : "正常"));
    }

    private void initListeners(View view) {
        view.findViewById(R.id.btn_back).setOnClickListener(v -> SettingsFragment.popSubPage(this));
        if (menuButton != null) {
            menuButton.setVisibility(View.GONE);
        }
        if (homeButton != null) {
            homeButton.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).goToRecordingInterface();
                }
            });
        }

        blindSpotGlobalSwitch.setOnCheckedChangeListener((btn, checked) -> {
            appConfig.setBlindSpotGlobalEnabled(checked);
            updateGlobalHintUi(checked);
            BlindSpotService.update(requireContext());
        });

        btnOutputMainOnly.setOnClickListener(v -> selectOutputMode(AppConfig.BLIND_SPOT_OUTPUT_MAIN_ONLY));
        btnOutputSecondaryOnly.setOnClickListener(v -> selectOutputMode(AppConfig.BLIND_SPOT_OUTPUT_SECONDARY_ONLY));
        btnOutputBoth.setOnClickListener(v -> selectOutputMode(AppConfig.BLIND_SPOT_OUTPUT_BOTH));

        View.OnClickListener refreshClickListener = v -> {
            populateDisplaySpinners();
            updateOutputModeUi();
            updateEditorTargetUi();
            Toast.makeText(requireContext(), "已刷新显示屏幕列表", Toast.LENGTH_SHORT).show();
        };
        refreshDisplaysButton.setOnClickListener(refreshClickListener);
        refreshEditorDisplaysButton.setOnClickListener(refreshClickListener);

        secondaryOutputDisplaySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (isUpdatingDisplaySpinners
                        || position < 0
                        || position >= secondaryOutputDisplayIds.size()) {
                    return;
                }
                int chosenId = secondaryOutputDisplayIds.get(position);
                if (chosenId != appConfig.getSecondaryDisplayId()) {
                    appConfig.setSecondaryDisplayId(chosenId);
                    updateSecondaryOutputInfo();
                    if (isSecondaryAdjustMode) {
                        sendSecondaryAdjustAction(true);
                    } else {
                        BlindSpotService.update(requireContext());
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        editorScreenSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (isUpdatingDisplaySpinners || position < 0 || position >= editorDisplayIds.size()) {
                    return;
                }
                int chosenId = editorDisplayIds.get(position);
                editorDisplayId = chosenId;
                appConfig.setBlindSpotEditorDisplayTarget(chosenId);
                updateEditorTargetUi();
                if (isPreviewActive) {
                    startPreviewForEditorTarget();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        turnSignalReuseMainSwitch.setOnCheckedChangeListener((btn, checked) -> {
            appConfig.setTurnSignalReuseMainFloating(checked);
            BlindSpotService.update(requireContext());
        });
        mainFloatingEnabledSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                btn.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                return;
            }
            appConfig.setMainFloatingEnabled(checked);
            BlindSpotService.update(requireContext());
        });
        mainFloatingCameraSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (position >= 0 && position < MAIN_FLOATING_CAMERAS.length) {
                    String cam = MAIN_FLOATING_CAMERAS[position];
                    if (!cam.equals(appConfig.getMainFloatingCamera())) {
                        appConfig.setMainFloatingCamera(cam);
                        BlindSpotService.update(requireContext());
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        toggleSecondaryAdjustButton.setOnClickListener(v -> {
            isSecondaryAdjustMode = !isSecondaryAdjustMode;
            sendSecondaryAdjustAction(isSecondaryAdjustMode);
            toggleSecondaryAdjustButton.setText(isSecondaryAdjustMode
                    ? "退出副屏边界调整模式"
                    : "在副屏显示边界框以辅助调整");
            UiOptionPillHelper.apply(toggleSecondaryAdjustButton, requireContext(), isSecondaryAdjustMode, true);
        });

        // 触发方案
        turnSignalLinkageSwitch.setOnCheckedChangeListener((btn, checked) -> {
            appConfig.setTurnSignalLinkageEnabled(checked);
            BlindSpotService.update(requireContext());
        });
        btnTriggerVhal.setOnClickListener(v -> selectTriggerScheme(0));
        btnTriggerCarL6L7.setOnClickListener(v -> selectTriggerScheme(1));
        btnTriggerCarBoyueL.setOnClickListener(v -> selectTriggerScheme(2));
        btnTriggerLogcatXinghan7.setOnClickListener(v -> selectTriggerScheme(3));
        btnTriggerLogcatCustom.setOnClickListener(v -> selectTriggerScheme(4));

        TextWatcher logWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (AppConfig.TRIGGER_MODE_LOGCAT.equals(appConfig.getTurnSignalTriggerMode())
                        && activeTriggerPresetIndex == 4) {
                    if (s == turnSignalLeftLogEditText.getText()) {
                        appConfig.setTurnSignalCustomLeftTriggerLog(s.toString());
                    } else if (s == turnSignalRightLogEditText.getText()) {
                        appConfig.setTurnSignalCustomRightTriggerLog(s.toString());
                    }
                    BlindSpotService.update(requireContext());
                }
            }
        };
        turnSignalLeftLogEditText.addTextChangedListener(logWatcher);
        turnSignalRightLogEditText.addTextChangedListener(logWatcher);

        turnSignalTimeoutSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTimeoutLabel(progress);
                if (fromUser) {
                    appConfig.setTurnSignalTimeout(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                appConfig.setTurnSignalTimeout(seekBar.getProgress());
            }
        });

        rainNightBlindSpotSwitch.setOnCheckedChangeListener((btn, checked) -> {
            appConfig.setRainNightBlindSpotEnabled(checked);
            BlindSpotService.update(requireContext());
        });

        // 摄像头/槽位切换
        btnSlotFront.setOnClickListener(v -> selectVisualSlot(BlindSpotPlacement.SLOT_FRONT));
        btnSlotBack.setOnClickListener(v -> selectVisualSlot(BlindSpotPlacement.SLOT_BACK));
        btnSlotLeft.setOnClickListener(v -> selectVisualSlot(BlindSpotPlacement.SLOT_LEFT));
        btnSlotRight.setOnClickListener(v -> selectVisualSlot(BlindSpotPlacement.SLOT_RIGHT));
        btnSlotRainLeft.setOnClickListener(v -> selectVisualSlot(BlindSpotPlacement.SLOT_RAIN_LEFT));
        btnSlotRainRight.setOnClickListener(v -> selectVisualSlot(BlindSpotPlacement.SLOT_RAIN_RIGHT));

        btnToggleVisualEdit.setOnClickListener(v -> {
            visualEditorView.setEditingEnabled(!visualEditorView.isEditingEnabled());
            updateEditToggleUi();
        });

        visualEditorView.setOnPlacementChangeListener(new BlindSpotVisualEditorView.OnPlacementChangeListener() {
            @Override
            public void onPlacementChanged(
                    @NonNull String slot,
                    @NonNull BlindSpotPlacement.RectRatio rect,
                    boolean fromUser) {
                if (isUpdatingPositionUi || !fromUser) return;
                applyRectChangeFromVisualEditor(rect, false);
            }

            @Override
            public void onPlacementCommitted(
                    @NonNull String slot,
                    @NonNull BlindSpotPlacement.RectRatio rect) {
                applyRectChangeFromVisualEditor(rect, true);
            }
        });

        cbSyncAllFourPositions.setOnCheckedChangeListener((btn, checked) -> {
            if (isUpdatingPositionUi) return;
            int[] rawDims = resolveEditorRawScreenDimensions();
            appConfig.setBlindSpotSyncAllFourPositions(
                    getActiveScreenKey(), checked, activeSlot, rawDims[0], rawDims[1]);
            updateSameSideRowVisibility();
            syncPositionControlsFromStorage();
            BlindSpotService.update(requireContext());
        });

        sameSidePositionSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (isUpdatingPositionUi) return;
            appConfig.setBlindSpotUseSameSidePosition(checked, activeSlot);
            syncPositionControlsFromStorage();
            BlindSpotService.update(requireContext());
        });

        bindCoordinateSeekBar(seekbarPosX, etPosX);
        bindCoordinateSeekBar(seekbarPosY, etPosY);
        bindCoordinateSeekBar(seekbarPosW, etPosW);
        bindCoordinateSeekBar(seekbarPosH, etPosH);
        bindCoordinateEditText(etPosX, seekbarPosX);
        bindCoordinateEditText(etPosY, seekbarPosY);
        bindCoordinateEditText(etPosW, seekbarPosW);
        bindCoordinateEditText(etPosH, seekbarPosH);

        // 分路旋转与四边裁切（按当前屏幕 + 当前摄像头独立保存）
        btnCamRot0.setOnClickListener(v -> setChannelRotation(0));
        btnCamRot90.setOnClickListener(v -> setChannelRotation(90));
        btnCamRot180.setOnClickListener(v -> setChannelRotation(180));
        btnCamRot270.setOnClickListener(v -> setChannelRotation(270));
        seekbarCameraRotation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (isUpdatingTransformUi || !fromUser) return;
                setChannelRotation(progress, false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                commitLiveServiceUpdateNow();
            }
        });

        bindEdgeCropSeekBar(seekbarCropTop, tvCropTopValue, Edge.TOP);
        bindEdgeCropSeekBar(seekbarCropBottom, tvCropBottomValue, Edge.BOTTOM);
        bindEdgeCropSeekBar(seekbarCropLeft, tvCropLeftValue, Edge.LEFT);
        bindEdgeCropSeekBar(seekbarCropRight, tvCropRightValue, Edge.RIGHT);

        // 物理镜像 & 当前屏幕输出镜像
        btnPhysicalMirrorH.setOnClickListener(v -> {
            String camPos = getActiveCameraPos();
            appConfig.setPhysicalMirrorH(camPos, !appConfig.isPhysicalMirrorH(camPos));
            updateMirrorUi();
            commitLiveServiceUpdateNow();
        });
        btnPhysicalMirrorV.setOnClickListener(v -> {
            String camPos = getActiveCameraPos();
            appConfig.setPhysicalMirrorV(camPos, !appConfig.isPhysicalMirrorV(camPos));
            updateMirrorUi();
            commitLiveServiceUpdateNow();
        });
        btnOutputMirrorH.setOnClickListener(v -> {
            String camPos = getActiveCameraPos();
            MirrorTarget target = isMainEditorSelected()
                    ? MirrorTarget.BLIND_SPOT
                    : MirrorTarget.SECONDARY_HUD;
            appConfig.setOutputMirrorH(target, camPos, !appConfig.isOutputMirrorH(target, camPos));
            updateMirrorUi();
            commitLiveServiceUpdateNow();
        });
        btnOutputMirrorV.setOnClickListener(v -> {
            String camPos = getActiveCameraPos();
            MirrorTarget target = isMainEditorSelected()
                    ? MirrorTarget.BLIND_SPOT
                    : MirrorTarget.SECONDARY_HUD;
            appConfig.setOutputMirrorV(target, camPos, !appConfig.isOutputMirrorV(target, camPos));
            updateMirrorUi();
            commitLiveServiceUpdateNow();
        });

        mainFloatingAspectLockSwitch.setOnCheckedChangeListener(
                (btn, checked) -> appConfig.setMainFloatingAspectRatioLocked(checked));
        mainFloatingLongPressDragSwitch.setOnCheckedChangeListener(
                (btn, checked) -> appConfig.setMainFloatingLongPressDragEnabled(checked));

        secondaryOrientationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (isUpdatingPositionUi || isMainEditorSelected()) {
                    return;
                }
                int orient = position * 90;
                String screenKey = getActiveScreenKey();
                if (orient != appConfig.getBlindSpotScreenOrientation(screenKey)) {
                    int[] rawDims = resolveEditorRawScreenDimensions();
                    BlindSpotPlacement.RectRatio currentVisual = appConfig.getBlindSpotScreenPlacement(
                            screenKey, activeSlot, rawDims[0], rawDims[1]);
                    appConfig.setBlindSpotScreenOrientation(screenKey, orient);
                    appConfig.setBlindSpotScreenPlacement(
                            screenKey, activeSlot, currentVisual, rawDims[0], rawDims[1]);
                    updateEditorTargetUi();
                    updateSecondaryOutputInfo();
                    commitLiveServiceUpdateNow();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        secondaryBorderSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (isUpdatingPositionUi || isMainEditorSelected()) return;
            appConfig.setBlindSpotScreenBorderEnabled(getActiveScreenKey(), checked);
            commitLiveServiceUpdateNow();
        });

        seekbarSecondaryAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSecondaryAlphaValue.setText(progress + "%");
                if (isUpdatingPositionUi || !fromUser || isMainEditorSelected()) return;
                appConfig.setBlindSpotScreenAlpha(getActiveScreenKey(), progress);
                scheduleThrottledServiceUpdate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (!isMainEditorSelected()) {
                    commitLiveServiceUpdateNow();
                }
            }
        });

        btnPreviewCurrentPosition.setOnClickListener(v -> toggleCurrentPositionPreview());
        btnResetCurrentPosition.setOnClickListener(v -> resetCurrentTargetPosition());

        // ESP32 / 浏览器 MJPEG 投屏入口
        adjustMjpegStreamButton.setOnClickListener(v ->
                SettingsFragment.openSubPage(this, new MjpegStreamAdjustFragment()));

        // 规避与高级调试
        blindSpotSuppressOn360Switch.setOnCheckedChangeListener((btn, checked) -> {
            appConfig.setBlindSpotSuppressOn360(checked);
            BlindSpotService.update(requireContext());
        });
        avmAvoidanceSwitch.setOnCheckedChangeListener((btn, checked) -> {
            appConfig.setAvmAvoidanceEnabled(checked);
            avmAvoidanceDetailLayout.setVisibility(checked ? View.VISIBLE : View.GONE);
            BlindSpotService.update(requireContext());
        });
        avmAvoidanceActivityEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String activity = s.toString().trim();
                if (!activity.isEmpty()) {
                    appConfig.setAvmAvoidanceActivity(activity);
                    BlindSpotService.update(requireContext());
                }
            }
        });

        logcatDebugButton.setOnClickListener(v -> openLogcatDebug());
        if (resetStandaloneWindowButton != null) {
            resetStandaloneWindowButton.setOnClickListener(v -> {
                appConfig.resetStandaloneWindowBounds();
                Toast.makeText(requireContext(), "独立预览小窗位置和尺寸已重置", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void selectOutputMode(String mode) {
        if (!AppFeatures.SECONDARY_DISPLAY) {
            mode = AppConfig.BLIND_SPOT_OUTPUT_MAIN_ONLY;
        }
        boolean includesSecondary = AppConfig.BLIND_SPOT_OUTPUT_SECONDARY_ONLY.equals(mode)
                || AppConfig.BLIND_SPOT_OUTPUT_BOTH.equals(mode);
        if (includesSecondary) {
            int secId = appConfig.getSecondaryDisplayId();
            if (secId == Display.DEFAULT_DISPLAY || !secondaryOutputDisplayIds.contains(secId)) {
                if (!secondaryOutputDisplayIds.isEmpty()) {
                    int fallbackId = secondaryOutputDisplayIds.get(0);
                    appConfig.setSecondaryDisplayId(fallbackId);
                    isUpdatingDisplaySpinners = true;
                    secondaryOutputDisplaySpinner.setSelection(0, false);
                    isUpdatingDisplaySpinners = false;
                }
            }
        }
        appConfig.setBlindSpotOutputMode(mode);
        updateOutputModeUi();
        BlindSpotService.update(requireContext());
    }

    private void selectTriggerScheme(int presetIdx) {
        activeTriggerPresetIndex = presetIdx;
        if (presetIdx == 0) {
            appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_VHAL_GRPC);
            carApiStatusText.setText("触发状态: 检测 VHAL 服务...");
            checkVhalGrpcConnection();
        } else if (presetIdx == 1 || presetIdx == 2) {
            appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_CAR_SIGNAL_MANAGER);
            appConfig.setTurnSignalPresetSelection(presetIdx == 2 ? "boyue_l" : "l6l7");
            carApiStatusText.setText("触发状态: 检测 CarSignalManager 服务...");
            checkCarSignalManagerConnection();
        } else if (presetIdx == 3) {
            appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_LOGCAT);
            appConfig.setTurnSignalPresetSelection("xinghan7");
            turnSignalLeftLogEditText.setText(TURN_SIGNAL_PRESETS[0][1]);
            turnSignalRightLogEditText.setText(TURN_SIGNAL_PRESETS[0][2]);
            appConfig.setTurnSignalCustomLeftTriggerLog(TURN_SIGNAL_PRESETS[0][1]);
            appConfig.setTurnSignalCustomRightTriggerLog(TURN_SIGNAL_PRESETS[0][2]);
            carApiStatusText.setText("触发状态: Logcat 监听中（星舰7预设）");
        } else {
            appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_LOGCAT);
            appConfig.setTurnSignalPresetSelection("custom");
            turnSignalLeftLogEditText.setText(appConfig.getTurnSignalCustomLeftTriggerLog());
            turnSignalRightLogEditText.setText(appConfig.getTurnSignalCustomRightTriggerLog());
            carApiStatusText.setText("触发状态: Logcat 监听中（自定义关键字）");
        }
        updateTriggerPresetPillsUi();
        BlindSpotService.update(requireContext());
    }

    private void selectVisualSlot(String slot) {
        activeSlot = BlindSpotPlacement.normalizeSlot(slot);
        updateSlotTabsUi();
        updateCameraScopeHint();
        syncPositionControlsFromStorage();
        syncCameraTransformControls();
        if (isPreviewActive) {
            startPreviewForEditorTarget();
        } else if (isSecondaryAdjustMode && !isMainEditorSelected()) {
            sendSecondaryAdjustAction(true);
        }
    }

    private void setChannelRotation(int degrees) {
        setChannelRotation(degrees, true);
    }

    private void setChannelRotation(int degrees, boolean commitNow) {
        int safeDeg = BlindSpotCorrectionMath.normalizeAngle(degrees);
        String screenKey = getActiveScreenKey();
        String camPos = getActiveCameraPos();
        appConfig.setBlindSpotScreenCameraRotation(screenKey, camPos, safeDeg);
        isUpdatingTransformUi = true;
        seekbarCameraRotation.setProgress(safeDeg);
        isUpdatingTransformUi = false;
        updateRotationAndCropVisuals();
        if (commitNow) {
            commitLiveServiceUpdateNow();
        } else {
            scheduleThrottledServiceUpdate();
        }
    }

    private enum Edge {
        TOP, BOTTOM, LEFT, RIGHT
    }

    private void bindEdgeCropSeekBar(SeekBar seekBar, TextView valueView, Edge edge) {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (isUpdatingTransformUi || !fromUser) return;
                String screenKey = getActiveScreenKey();
                String camPos = getActiveCameraPos();
                BlindSpotCorrectionMath.EdgeCrop cur =
                        appConfig.getBlindSpotScreenCameraEdgeCrop(screenKey, camPos);
                int topPct = Math.round(cur.top * 100f);
                int bottomPct = Math.round(cur.bottom * 100f);
                int leftPct = Math.round(cur.left * 100f);
                int rightPct = Math.round(cur.right * 100f);

                int clamped = progress;
                switch (edge) {
                    case TOP:
                        clamped = BlindSpotCorrectionMath.clampNewEdgePercent(progress, bottomPct);
                        topPct = clamped;
                        break;
                    case BOTTOM:
                        clamped = BlindSpotCorrectionMath.clampNewEdgePercent(progress, topPct);
                        bottomPct = clamped;
                        break;
                    case LEFT:
                        clamped = BlindSpotCorrectionMath.clampNewEdgePercent(progress, rightPct);
                        leftPct = clamped;
                        break;
                    case RIGHT:
                        clamped = BlindSpotCorrectionMath.clampNewEdgePercent(progress, leftPct);
                        rightPct = clamped;
                        break;
                }
                if (clamped != progress) {
                    isUpdatingTransformUi = true;
                    sb.setProgress(clamped);
                    isUpdatingTransformUi = false;
                }
                valueView.setText(clamped + "%");

                BlindSpotCorrectionMath.EdgeCrop next =
                        BlindSpotCorrectionMath.EdgeCrop.fromPercents(leftPct, topPct, rightPct, bottomPct);
                appConfig.setBlindSpotScreenCameraEdgeCrop(screenKey, camPos, next);
                updateRotationAndCropVisuals();
                scheduleThrottledServiceUpdate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                commitLiveServiceUpdateNow();
            }
        });
    }

    private void applyRectChangeFromVisualEditor(BlindSpotPlacement.RectRatio rect, boolean commitService) {
        isUpdatingPositionUi = true;
        try {
            String screenKey = getActiveScreenKey();
            int[] rawDims = resolveEditorRawScreenDimensions();
            int[] visDims = resolveEditorVisualDimensions();
            int[] visPx = rect.toPixelBounds(visDims[0], visDims[1]);
            applyPixelNumbersToFields(visPx[0], visPx[1], visPx[2], visPx[3], rect);
            appConfig.setBlindSpotScreenPlacement(screenKey, activeSlot, rect, rawDims[0], rawDims[1]);
            if (isMainEditorSelected() && BlindSpotPlacement.isStandardCameraSlot(activeSlot)) {
                appConfig.setTurnSignalFloatingBounds(visPx[0], visPx[1], visPx[2], visPx[3]);
            }
        } finally {
            isUpdatingPositionUi = false;
        }
        if (commitService) {
            commitLiveServiceUpdateNow();
        } else if (isPreviewActive || isSecondaryAdjustMode) {
            scheduleThrottledServiceUpdate();
        }
    }

    private void bindCoordinateSeekBar(SeekBar seekBar, EditText editText) {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (isUpdatingPositionUi || !fromUser) return;
                String str = String.valueOf(progress);
                if (!str.equals(editText.getText().toString())) {
                    editText.setText(str);
                }
                persistPixelBoundsFromControls(false);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                persistPixelBoundsFromControls(true);
            }
        });
    }

    private void bindCoordinateEditText(EditText editText, SeekBar seekBar) {
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (isUpdatingPositionUi || !editText.hasFocus()) return;
                try {
                    int val = Integer.parseInt(s.toString().trim());
                    int clamped = Math.max(0, Math.min(seekBar.getMax(), val));
                    if (seekBar.getProgress() != clamped) {
                        seekBar.setProgress(clamped);
                    }
                    persistPixelBoundsFromControls(true);
                } catch (NumberFormatException ignored) {
                }
            }
        });
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && !isUpdatingPositionUi) {
                syncPositionControlsFromStorage();
            }
        });
    }

    private void persistPixelBoundsFromControls(boolean commitService) {
        isUpdatingPositionUi = true;
        try {
            String screenKey = getActiveScreenKey();
            int[] rawDims = resolveEditorRawScreenDimensions();
            int[] visDims = resolveEditorVisualDimensions();
            int pxX = seekbarPosX.getProgress();
            int pxY = seekbarPosY.getProgress();
            int pxW = Math.max(1, seekbarPosW.getProgress());
            int pxH = Math.max(1, seekbarPosH.getProgress());

            BlindSpotPlacement.RectRatio rect =
                    BlindSpotPlacement.fromPixels(pxX, pxY, pxW, pxH, visDims[0], visDims[1]);
            tvPosXPct.setText(rect.xPercent() + "%");
            tvPosYPct.setText(rect.yPercent() + "%");
            tvPosWPct.setText(rect.widthPercent() + "%");
            tvPosHPct.setText(rect.heightPercent() + "%");

            visualEditorView.setPlacement(activeSlot, rect);
            appConfig.setBlindSpotScreenPlacement(screenKey, activeSlot, rect, rawDims[0], rawDims[1]);
            if (isMainEditorSelected() && BlindSpotPlacement.isStandardCameraSlot(activeSlot)) {
                int[] normalizedPx = rect.toPixelBounds(visDims[0], visDims[1]);
                appConfig.setTurnSignalFloatingBounds(
                        normalizedPx[0], normalizedPx[1], normalizedPx[2], normalizedPx[3]);
            }
        } finally {
            isUpdatingPositionUi = false;
        }
        if (commitService) {
            commitLiveServiceUpdateNow();
        } else if (isPreviewActive || isSecondaryAdjustMode) {
            scheduleThrottledServiceUpdate();
        }
    }

    private void scheduleThrottledServiceUpdate() {
        if (pendingServiceUpdateScheduled) {
            return;
        }
        pendingServiceUpdateScheduled = true;
        uiHandler.postDelayed(throttledServiceUpdateRunnable, LIVE_UPDATE_THROTTLE_MS);
    }

    private void commitLiveServiceUpdateNow() {
        uiHandler.removeCallbacks(throttledServiceUpdateRunnable);
        pendingServiceUpdateScheduled = false;
        Context ctx = getContext();
        if (ctx != null) {
            BlindSpotService.update(ctx);
        }
    }

    private void toggleCurrentPositionPreview() {
        Context ctx = getContext();
        if (ctx == null) return;
        if (isPreviewActive) {
            isPreviewActive = false;
            sendBlindSpotServiceAction("stop_preview_blind_spot");
            btnPreviewCurrentPosition.setText("预览当前画面");
            UiOptionPillHelper.apply(btnPreviewCurrentPosition, ctx, false);
            return;
        }
        if (isMainEditorSelected() && !WakeUpHelper.hasOverlayPermission(ctx)) {
            Toast.makeText(ctx, "请先授予悬浮窗权限以显示主屏位置预览", Toast.LENGTH_SHORT).show();
            return;
        }
        if (editorDisplayId == EvccDashcastDisplayResolver.PICK_ID
                && EvccDashcastDisplayResolver.queryPrimary(ctx) == null) {
            Toast.makeText(ctx, "EVCC 仪表投屏当前未启动，暂无法在仪表屏实时预览", Toast.LENGTH_SHORT).show();
            return;
        }
        isPreviewActive = true;
        startPreviewForEditorTarget();
        btnPreviewCurrentPosition.setText("停止预览");
        UiOptionPillHelper.apply(btnPreviewCurrentPosition, ctx, true, true);
    }

    private void startPreviewForEditorTarget() {
        Context ctx = getContext();
        if (ctx == null) return;
        String cameraPos = getActiveCameraPos();
        Intent intent = new Intent(ctx, BlindSpotService.class);
        intent.putExtra("action", "preview_blind_spot");
        intent.putExtra("camera_pos", cameraPos);
        intent.putExtra("placement_slot", activeSlot);
        intent.putExtra("target_display_id", editorDisplayId);
        ctx.startService(intent);
    }

    private void sendSecondaryAdjustAction(boolean enter) {
        Context ctx = getContext();
        if (ctx == null) return;
        Intent intent = new Intent(ctx, BlindSpotService.class);
        intent.putExtra("action", enter ? "enter_secondary_display_adjust" : "exit_secondary_display_adjust");
        if (enter) {
            int targetId = !isMainEditorSelected()
                    ? editorDisplayId
                    : appConfig.getSecondaryDisplayId();
            if (targetId == Display.DEFAULT_DISPLAY && !secondaryOutputDisplayIds.isEmpty()) {
                targetId = secondaryOutputDisplayIds.get(0);
            }
            intent.putExtra("target_display_id", targetId);
            intent.putExtra("placement_slot", activeSlot);
        }
        ctx.startService(intent);
    }

    private void resetCurrentTargetPosition() {
        String screenKey = getActiveScreenKey();
        String camPos = getActiveCameraPos();
        int[] rawDims = resolveEditorRawScreenDimensions();

        if (!isMainEditorSelected()) {
            int defaultOrient = BlindSpotPlacement.SCREEN_EVCC.equals(screenKey) ? 0 : 180;
            appConfig.setBlindSpotScreenOrientation(screenKey, defaultOrient);
            appConfig.setBlindSpotScreenAlpha(screenKey, 100);
            appConfig.setBlindSpotScreenBorderEnabled(screenKey, false);
        } else {
            appConfig.resetTurnSignalFloatingBounds();
            appConfig.resetMainFloatingBounds();
        }
        appConfig.resetBlindSpotScreenPlacement(screenKey, activeSlot, rawDims[0], rawDims[1]);
        appConfig.resetBlindSpotScreenCameraTransform(screenKey, camPos);

        updateEditorTargetUi();
        updateSecondaryOutputInfo();
        commitLiveServiceUpdateNow();
        Toast.makeText(
                requireContext(),
                String.format(Locale.US, "已恢复【%s】·「%s」默认位置与画面参数",
                        getActiveScreenShortLabel(), BlindSpotPlacement.getSlotDisplayName(activeSlot)),
                Toast.LENGTH_SHORT).show();
    }

    private void updateTimeoutLabel(int seconds) {
        tvTurnSignalTimeout.setText(seconds == 0 ? "0 秒（松杆立即消失）" : seconds + " 秒");
    }

    private void sendBlindSpotServiceAction(String action) {
        Context ctx = getContext();
        if (ctx == null) return;
        Intent intent = new Intent(ctx, BlindSpotService.class);
        intent.putExtra("action", action);
        ctx.startService(intent);
    }

    private void openLogcatDebug() {
        String keyword = logFilterEditText.getText().toString().trim();
        if (keyword.isEmpty()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                    requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle("提示")
                    .setMessage("未输入过滤关键字，日志量可能很大。\n建议输入关键字进行过滤，是否继续？")
                    .setPositiveButton("继续打开", (dialog, which) -> launchLogcatActivity(""))
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            launchLogcatActivity(keyword);
        }
    }

    private void launchLogcatActivity(String keyword) {
        Intent intent = new Intent(requireContext(), LogcatViewerActivity.class);
        if (!keyword.isEmpty()) {
            intent.putExtra("filter_keyword", keyword);
        }
        startActivity(intent);
    }

    private void checkVhalGrpcConnection() {
        carApiStatusText.setText("VHAL 服务状态: 检测中...");
        new Thread(() -> {
            boolean reachable = VhalSignalObserver.testConnection();
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded() || carApiStatusText == null) return;
                    if (activeTriggerPresetIndex != 0) return;
                    carApiStatusText.setText(reachable
                            ? "VHAL 服务状态: ✓ 已连接"
                            : "VHAL 服务状态: ✗ 未检测到服务端（实车环境生效）");
                });
            }
        }).start();
    }

    private void checkCarSignalManagerConnection() {
        carApiStatusText.setText("CarSignalManager 状态: 检测中...");
        new Thread(() -> {
            Context ctx = getContext();
            if (ctx == null) return;
            boolean reachable = CarSignalManagerObserver.testConnection(ctx);
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    if (!isAdded() || carApiStatusText == null) return;
                    if (activeTriggerPresetIndex != 1 && activeTriggerPresetIndex != 2) return;
                    carApiStatusText.setText(reachable
                            ? "CarSignalManager 状态: ✓ 已连接"
                            : "CarSignalManager 状态: ✗ 服务不可达");
                });
            }
        }).start();
    }

    private String getCameraShortLabel(String camPos) {
        if ("front".equals(camPos)) return "前";
        if ("back".equals(camPos)) return "后";
        if ("left".equals(camPos)) return "左";
        if ("right".equals(camPos)) return "右";
        return camPos;
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        updateOutputModeUi();
        updateSlotTabsUi();
        updateTriggerPresetPillsUi();
        updateEditToggleUi();
        updateRotationAndCropVisuals();
        updateMirrorUi();
        styleStaticActionPills(themedContext != null ? themedContext : getContext());
    }

    @Override
    public void onDestroyView() {
        uiHandler.removeCallbacks(throttledServiceUpdateRunnable);
        pendingServiceUpdateScheduled = false;
        if (isPreviewActive) {
            isPreviewActive = false;
            sendBlindSpotServiceAction("stop_preview_blind_spot");
        }
        if (isSecondaryAdjustMode) {
            isSecondaryAdjustMode = false;
            sendBlindSpotServiceAction("exit_secondary_display_adjust");
        }
        super.onDestroyView();
    }
}
