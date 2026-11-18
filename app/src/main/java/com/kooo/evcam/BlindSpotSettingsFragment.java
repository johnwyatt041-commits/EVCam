package com.kooo.evcam;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
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
import com.kooo.evcam.blindspot.BlindSpotDisplayUtils;
import com.kooo.evcam.blindspot.BlindSpotPlacement;
import com.kooo.evcam.blindspot.BlindSpotVisualEditorView;
import com.kooo.evcam.ui.UiOptionPillHelper;
import com.kooo.evcam.ui.UiThemeController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 补盲与 HUD 投屏设置界面（扁平化重构版）。
 *
 * <p>顶部统一展示补盲总开关与显示屏幕选择（主屏 / 副屏 / EVCC 仪表），
 * 紧跟触发方案、2560×1600 可视化拖拽与多分辨率数值微调、分方向画面矫正与 ESP32 MJPEG 投屏入口。</p>
 */
public class BlindSpotSettingsFragment extends Fragment implements UiThemeController.ThemeAware {

    private static final String[][] TURN_SIGNAL_PRESETS = {
            {"xinghan7", "left front turn signal:1", "right front turn signal:1"},
    };
    private static final String[] MAIN_FLOATING_CAMERAS = {"front", "back", "left", "right"};
    private static final String[] MAIN_FLOATING_CAMERA_LABELS = {"前", "后", "左", "右"};

    private AppConfig appConfig;

    // Top Card: Master + Display Screen
    private SwitchMaterial blindSpotGlobalSwitch;
    private TextView tvBlindSpotGlobalHint;
    private Spinner displaySelectionSpinner;
    private Button refreshDisplaysButton;
    private TextView tvDisplayResolutionInfo;
    private LinearLayout mainDisplayControlsLayout;
    private SwitchMaterial turnSignalReuseMainSwitch;
    private SwitchMaterial mainFloatingEnabledSwitch;
    private Spinner mainFloatingCameraSpinner;
    private LinearLayout secondaryDisplayControlsLayout;
    private SwitchMaterial secondaryBlindSpotSwitch;
    private SwitchMaterial secondaryBorderSwitch;
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

    // Position & Size Card
    private TextView tvPositionScopeBadge;
    private View layoutSlotTabs;
    private Button btnSlotFront;
    private Button btnSlotBack;
    private Button btnSlotLeft;
    private Button btnSlotRight;
    private Button btnSlotRainLeft;
    private Button btnSlotRainRight;
    private TextView tvVisualEditorHint;
    private Button btnToggleVisualEdit;
    private BlindSpotVisualEditorView visualEditorView;
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
    private Button btnWinRot0;
    private Button btnWinRot90;
    private Button btnWinRot180;
    private Button btnWinRot270;
    private LinearLayout mainExtraOptionsLayout;
    private SwitchMaterial mainFloatingAspectLockSwitch;
    private SwitchMaterial mainFloatingLongPressDragSwitch;
    private LinearLayout secondaryExtraOptionsLayout;
    private Spinner secondaryOrientationSpinner;
    private SeekBar seekbarSecondaryAlpha;
    private TextView tvSecondaryAlphaValue;
    private Button btnPreviewCurrentPosition;
    private Button btnResetCurrentPosition;

    // Correction + MJPEG Card
    private SwitchMaterial blindSpotCorrectionSwitch;
    private Button adjustBlindSpotCorrectionButton;
    private View dividerMjpegStream;
    private Button adjustMjpegStreamButton;

    // Avoidance + Debug Card
    private SwitchMaterial blindSpotSuppressOn360Switch;
    private SwitchMaterial avmAvoidanceSwitch;
    private LinearLayout avmAvoidanceDetailLayout;
    private EditText avmAvoidanceActivityEditText;
    private EditText logFilterEditText;
    private Button logcatDebugButton;
    private Button resetStandaloneWindowButton;

    private Button menuButton;
    private Button homeButton;

    private final List<Integer> availableDisplayIds = new ArrayList<>();
    private final List<String> availableDisplayLabels = new ArrayList<>();
    private int selectedDisplayId = AppConfig.BLIND_SPOT_DISPLAY_MAIN;
    private String activeSlot = BlindSpotPlacement.SLOT_RIGHT;
    private int activeTriggerPresetIndex = 0; // 0=vhal,1=l6l7,2=boyue_l,3=xinghan7,4=custom
    private boolean isUpdatingPositionUi = false;
    private boolean isUpdatingDisplaySpinner = false;
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
        displaySelectionSpinner = view.findViewById(R.id.spinner_display_selection);
        refreshDisplaysButton = view.findViewById(R.id.btn_refresh_displays);
        tvDisplayResolutionInfo = view.findViewById(R.id.tv_display_resolution_info);
        mainDisplayControlsLayout = view.findViewById(R.id.layout_main_display_controls);
        turnSignalReuseMainSwitch = view.findViewById(R.id.switch_turn_signal_reuse_main);
        mainFloatingEnabledSwitch = view.findViewById(R.id.switch_main_floating_enabled);
        mainFloatingCameraSpinner = view.findViewById(R.id.spinner_main_floating_camera);
        secondaryDisplayControlsLayout = view.findViewById(R.id.layout_secondary_display_controls);
        secondaryBlindSpotSwitch = view.findViewById(R.id.switch_secondary_blind_spot_display);
        secondaryBorderSwitch = view.findViewById(R.id.switch_secondary_border);
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
        layoutSlotTabs = view.findViewById(R.id.layout_slot_tabs);
        btnSlotFront = view.findViewById(R.id.btn_slot_front);
        btnSlotBack = view.findViewById(R.id.btn_slot_back);
        btnSlotLeft = view.findViewById(R.id.btn_slot_left);
        btnSlotRight = view.findViewById(R.id.btn_slot_right);
        btnSlotRainLeft = view.findViewById(R.id.btn_slot_rain_left);
        btnSlotRainRight = view.findViewById(R.id.btn_slot_rain_right);
        tvVisualEditorHint = view.findViewById(R.id.tv_visual_editor_hint);
        btnToggleVisualEdit = view.findViewById(R.id.btn_toggle_visual_edit);
        visualEditorView = view.findViewById(R.id.blind_spot_visual_editor);
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
        btnWinRot0 = view.findViewById(R.id.btn_win_rot_0);
        btnWinRot90 = view.findViewById(R.id.btn_win_rot_90);
        btnWinRot180 = view.findViewById(R.id.btn_win_rot_180);
        btnWinRot270 = view.findViewById(R.id.btn_win_rot_270);
        mainExtraOptionsLayout = view.findViewById(R.id.layout_main_extra_options);
        mainFloatingAspectLockSwitch = view.findViewById(R.id.switch_main_floating_aspect_ratio_lock);
        mainFloatingLongPressDragSwitch = view.findViewById(R.id.switch_main_floating_long_press_drag);
        secondaryExtraOptionsLayout = view.findViewById(R.id.layout_secondary_extra_options);
        secondaryOrientationSpinner = view.findViewById(R.id.spinner_secondary_orientation);
        seekbarSecondaryAlpha = view.findViewById(R.id.seekbar_secondary_alpha);
        tvSecondaryAlphaValue = view.findViewById(R.id.tv_secondary_alpha_value);
        btnPreviewCurrentPosition = view.findViewById(R.id.btn_preview_current_position);
        btnResetCurrentPosition = view.findViewById(R.id.btn_reset_current_position);

        blindSpotCorrectionSwitch = view.findViewById(R.id.switch_blind_spot_correction);
        adjustBlindSpotCorrectionButton = view.findViewById(R.id.btn_adjust_blind_spot_correction);
        dividerMjpegStream = view.findViewById(R.id.divider_mjpeg_stream);
        adjustMjpegStreamButton = view.findViewById(R.id.btn_adjust_mjpeg_stream);
        boolean mjpegVisible = AppFeatures.NETWORK_STREAMING;
        if (dividerMjpegStream != null) {
            dividerMjpegStream.setVisibility(mjpegVisible ? View.VISIBLE : View.GONE);
        }
        if (adjustMjpegStreamButton != null) {
            adjustMjpegStreamButton.setVisibility(mjpegVisible ? View.VISIBLE : View.GONE);
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

        String[] orientations = {"0° (横屏)", "90° (竖屏)", "180° (反向横屏)", "270° (反向竖屏)"};
        ArrayAdapter<String> orientAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, orientations);
        orientAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        secondaryOrientationSpinner.setAdapter(orientAdapter);

        populateDisplaySelectionSpinner();
    }

    private void populateDisplaySelectionSpinner() {
        Context ctx = getContext();
        if (ctx == null) return;
        availableDisplayIds.clear();
        availableDisplayLabels.clear();

        int[] mainSize = BlindSpotDisplayUtils.getMainScreenSizePx(ctx);
        availableDisplayIds.add(AppConfig.BLIND_SPOT_DISPLAY_MAIN);
        availableDisplayLabels.add(String.format(Locale.US, "主屏（车机主屏幕 · %d×%d）", mainSize[0], mainSize[1]));

        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            Display[] displays = dm.getDisplays();
            for (Display d : displays) {
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    DisplayMetrics out = new DisplayMetrics();
                    d.getRealMetrics(out);
                    availableDisplayIds.add(d.getDisplayId());
                    String name = d.getName();
                    String suffix = (name != null && !name.isEmpty()) ? (" - " + name) : "";
                    availableDisplayLabels.add(String.format(
                            Locale.US, "副屏 Display #%d (%d×%d)%s",
                            d.getDisplayId(), out.widthPixels, out.heightPixels, suffix));
                }
            }
        }

        availableDisplayIds.add(EvccDashcastDisplayResolver.PICK_ID);
        EvccDashcastDisplayResolver.Info evccInfo = EvccDashcastDisplayResolver.queryPrimary(ctx);
        if (evccInfo != null) {
            availableDisplayLabels.add(String.format(
                    Locale.US, "EVCC 仪表投屏（已连接 Display #%d · %d×%d）",
                    evccInfo.displayId, evccInfo.widthPx, evccInfo.heightPx));
        } else {
            availableDisplayLabels.add("EVCC 仪表投屏（占位 · 投屏后自动适配）");
        }

        isUpdatingDisplaySpinner = true;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                ctx, android.R.layout.simple_spinner_item, availableDisplayLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        displaySelectionSpinner.setAdapter(adapter);

        int savedTarget = appConfig.getBlindSpotEditorDisplayTarget();
        int idx = availableDisplayIds.indexOf(savedTarget);
        if (idx < 0 && savedTarget != AppConfig.BLIND_SPOT_DISPLAY_MAIN) {
            idx = availableDisplayIds.indexOf(appConfig.getSecondaryDisplayId());
        }
        if (idx < 0) idx = 0;
        displaySelectionSpinner.setSelection(idx, false);
        selectedDisplayId = availableDisplayIds.get(idx);
        isUpdatingDisplaySpinner = false;
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

        secondaryBlindSpotSwitch.setChecked(appConfig.isSecondaryDisplayEnabled());
        secondaryBorderSwitch.setChecked(appConfig.isSecondaryDisplayBorderEnabled());
        secondaryOrientationSpinner.setSelection(
                Math.max(0, Math.min(3, appConfig.getSecondaryDisplayOrientation() / 90)), false);
        int alpha = appConfig.getSecondaryDisplayAlpha();
        seekbarSecondaryAlpha.setProgress(alpha);
        tvSecondaryAlphaValue.setText(alpha + "%");

        turnSignalLinkageSwitch.setChecked(appConfig.isTurnSignalLinkageEnabled());
        int timeout = appConfig.getTurnSignalTimeout();
        turnSignalTimeoutSeekBar.setProgress(timeout);
        updateTimeoutLabel(timeout);
        rainNightBlindSpotSwitch.setChecked(appConfig.isRainNightBlindSpotEnabled());

        turnSignalLeftLogEditText.setText(appConfig.getTurnSignalLeftTriggerLog());
        turnSignalRightLogEditText.setText(appConfig.getTurnSignalRightTriggerLog());
        syncTriggerSchemeFromConfig();

        sameSidePositionSwitch.setChecked(appConfig.isBlindSpotUseSameSidePosition());
        blindSpotCorrectionSwitch.setChecked(appConfig.isBlindSpotCorrectionEnabled());
        blindSpotSuppressOn360Switch.setChecked(appConfig.isBlindSpotSuppressOn360());

        boolean avmEnabled = appConfig.isAvmAvoidanceEnabled();
        avmAvoidanceSwitch.setChecked(avmEnabled);
        avmAvoidanceDetailLayout.setVisibility(avmEnabled ? View.VISIBLE : View.GONE);
        avmAvoidanceActivityEditText.setText(appConfig.getAvmAvoidanceActivity());

        updateDisplayTargetUi();
        updateEditToggleUi();
        updateSlotTabsUi();
        styleStaticActionPills(requireContext());
    }

    private void styleStaticActionPills(Context ctx) {
        if (ctx == null) return;
        UiOptionPillHelper.apply(toggleSecondaryAdjustButton, ctx, isSecondaryAdjustMode, true);
        UiOptionPillHelper.apply(btnPreviewCurrentPosition, ctx, isPreviewActive, true);
        UiOptionPillHelper.apply(refreshDisplaysButton, ctx, false);
        UiOptionPillHelper.apply(btnResetCurrentPosition, ctx, false);
        UiOptionPillHelper.apply(adjustBlindSpotCorrectionButton, ctx, false);
        UiOptionPillHelper.apply(adjustMjpegStreamButton, ctx, false);
        UiOptionPillHelper.apply(logcatDebugButton, ctx, false);
        UiOptionPillHelper.apply(resetStandaloneWindowButton, ctx, false);
    }

    private void updateGlobalHintUi(boolean globalEnabled) {
        tvBlindSpotGlobalHint.setText(globalEnabled
                ? "已开启：支持主屏弹窗与副屏 / EVCC 仪表投屏"
                : "总开关已关闭（下方参数仍可提前配置与预览）");
        subFeaturesContainer.setAlpha(globalEnabled ? 1.0f : 0.72f);
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

    private boolean isMainTargetSelected() {
        return selectedDisplayId == AppConfig.BLIND_SPOT_DISPLAY_MAIN;
    }

    private int[] resolveActiveScreenDimensions() {
        Context ctx = requireContext();
        if (isMainTargetSelected()) {
            return BlindSpotDisplayUtils.getMainScreenSizePx(ctx);
        }
        if (selectedDisplayId == EvccDashcastDisplayResolver.PICK_ID) {
            EvccDashcastDisplayResolver.Info info = EvccDashcastDisplayResolver.queryPrimary(ctx);
            if (info != null && info.widthPx > 0 && info.heightPx > 0) {
                return new int[]{info.widthPx, info.heightPx};
            }
            return new int[]{1920, 720};
        }
        return BlindSpotDisplayUtils.getDisplaySizePx(ctx, selectedDisplayId);
    }

    private void updateDisplayTargetUi() {
        boolean isMain = isMainTargetSelected();
        mainDisplayControlsLayout.setVisibility(isMain ? View.VISIBLE : View.GONE);
        secondaryDisplayControlsLayout.setVisibility(isMain ? View.GONE : View.VISIBLE);
        layoutSlotTabs.setVisibility(isMain ? View.VISIBLE : View.GONE);
        layoutSameSideRow.setVisibility(isMain ? View.VISIBLE : View.GONE);
        mainExtraOptionsLayout.setVisibility(isMain ? View.VISIBLE : View.GONE);
        secondaryExtraOptionsLayout.setVisibility(isMain ? View.GONE : View.VISIBLE);

        int[] dims = resolveActiveScreenDimensions();
        if (isMain) {
            tvDisplayResolutionInfo.setText(String.format(
                    Locale.US,
                    "当前主屏检测分辨率：%d×%d · 可视化画布按 2560×1600 (16:10) 等比同步",
                    dims[0], dims[1]));
            tvPositionScopeBadge.setText("2560×1600 (16:10)");
            tvNumericBoundsDesc.setText(String.format(
                    Locale.US,
                    "数值坐标按当前主屏 (%d×%d) 换算，与上方 2560×1600 拖拽框实时联动",
                    dims[0], dims[1]));
        } else if (selectedDisplayId == EvccDashcastDisplayResolver.PICK_ID) {
            EvccDashcastDisplayResolver.Info info = EvccDashcastDisplayResolver.queryPrimary(requireContext());
            if (info != null) {
                tvDisplayResolutionInfo.setText(String.format(
                        Locale.US, "EVCC 仪表投屏已连接：Display #%d (%d×%d)", info.displayId, info.widthPx, info.heightPx));
            } else {
                tvDisplayResolutionInfo.setText("EVCC 仪表投屏当前未连接；此处按 1920×720 预设并将在连接后自动套用");
            }
            tvPositionScopeBadge.setText("EVCC 仪表位置微调");
            tvNumericBoundsDesc.setText(String.format(
                    Locale.US,
                    "副屏与主屏分辨率不同：请在下方直接调整精确像素坐标（上限 %d×%d）",
                    dims[0], dims[1]));
        } else {
            tvDisplayResolutionInfo.setText(String.format(
                    Locale.US, "已选外接副屏 Display #%d · 检测分辨率：%d×%d", selectedDisplayId, dims[0], dims[1]));
            tvPositionScopeBadge.setText(String.format(Locale.US, "副屏 #%d (%d×%d)", selectedDisplayId, dims[0], dims[1]));
            tvNumericBoundsDesc.setText(String.format(
                    Locale.US,
                    "副屏与主屏分辨率不同：请在下方直接调整精确像素坐标（上限 %d×%d）",
                    dims[0], dims[1]));
        }

        syncPositionControlsFromStorage();
        updateRotationPillsUi();
    }

    private void syncPositionControlsFromStorage() {
        isUpdatingPositionUi = true;
        try {
            int[] dims = resolveActiveScreenDimensions();
            int maxW = Math.max(1, dims[0]);
            int maxH = Math.max(1, dims[1]);
            seekbarPosX.setMax(maxW);
            seekbarPosY.setMax(maxH);
            seekbarPosW.setMax(maxW);
            seekbarPosH.setMax(maxH);

            BlindSpotPlacement.RectRatio rect;
            int pxX;
            int pxY;
            int pxW;
            int pxH;
            String canvasLabelSlot;

            if (isMainTargetSelected()) {
                rect = appConfig.getBlindSpotPlacement(activeSlot);
                int[] px = rect.toPixelBounds(maxW, maxH);
                pxX = px[0];
                pxY = px[1];
                pxW = px[2];
                pxH = px[3];
                canvasLabelSlot = activeSlot;
            } else {
                pxX = Math.max(0, Math.min(maxW, appConfig.getSecondaryDisplayX()));
                pxY = Math.max(0, Math.min(maxH, appConfig.getSecondaryDisplayY()));
                pxW = Math.max(1, Math.min(maxW, appConfig.getSecondaryDisplayWidth()));
                pxH = Math.max(1, Math.min(maxH, appConfig.getSecondaryDisplayHeight()));
                rect = BlindSpotPlacement.fromPixels(pxX, pxY, pxW, pxH, maxW, maxH);
                canvasLabelSlot = BlindSpotPlacement.SLOT_FRONT;
            }

            visualEditorView.setPlacement(canvasLabelSlot, rect);
            applyPixelNumbersToFields(pxX, pxY, pxW, pxH, rect);
        } finally {
            isUpdatingPositionUi = false;
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
                ? "拖动画面移动，拖右下角缩放"
                : "点击进入编辑后可直接调整画面");
        UiOptionPillHelper.apply(btnToggleVisualEdit, ctx, editing, true);
    }

    private void updateRotationPillsUi() {
        Context ctx = getContext();
        if (ctx == null) return;
        int rot = isMainTargetSelected()
                ? appConfig.getTurnSignalFloatingRotation()
                : appConfig.getSecondaryDisplayRotation();
        rot = ((rot % 360) + 360) % 360;
        UiOptionPillHelper.apply(btnWinRot0, ctx, rot == 0);
        UiOptionPillHelper.apply(btnWinRot90, ctx, rot == 90);
        UiOptionPillHelper.apply(btnWinRot180, ctx, rot == 180);
        UiOptionPillHelper.apply(btnWinRot270, ctx, rot == 270);
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

        refreshDisplaysButton.setOnClickListener(v -> {
            populateDisplaySelectionSpinner();
            updateDisplayTargetUi();
            Toast.makeText(requireContext(), "已刷新显示屏幕列表", Toast.LENGTH_SHORT).show();
        });

        displaySelectionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (isUpdatingDisplaySpinner || position < 0 || position >= availableDisplayIds.size()) {
                    return;
                }
                int chosenId = availableDisplayIds.get(position);
                selectedDisplayId = chosenId;
                appConfig.setBlindSpotEditorDisplayTarget(chosenId);
                if (chosenId != AppConfig.BLIND_SPOT_DISPLAY_MAIN) {
                    appConfig.setSecondaryDisplayId(chosenId);
                    BlindSpotService.update(requireContext());
                }
                updateDisplayTargetUi();
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

        secondaryBlindSpotSwitch.setOnCheckedChangeListener((btn, checked) -> {
            appConfig.setSecondaryDisplayEnabled(checked);
            BlindSpotService.update(requireContext());
        });
        secondaryBorderSwitch.setOnCheckedChangeListener((btn, checked) -> {
            appConfig.setSecondaryDisplayBorderEnabled(checked);
            BlindSpotService.update(requireContext());
        });
        toggleSecondaryAdjustButton.setOnClickListener(v -> {
            isSecondaryAdjustMode = !isSecondaryAdjustMode;
            sendBlindSpotServiceAction(isSecondaryAdjustMode
                    ? "enter_secondary_display_adjust"
                    : "exit_secondary_display_adjust");
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

        // 图 4 槽位 tabs & 编辑态
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

        sameSidePositionSwitch.setOnCheckedChangeListener((btn, checked) -> {
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

        btnWinRot0.setOnClickListener(v -> selectWindowRotation(0));
        btnWinRot90.setOnClickListener(v -> selectWindowRotation(90));
        btnWinRot180.setOnClickListener(v -> selectWindowRotation(180));
        btnWinRot270.setOnClickListener(v -> selectWindowRotation(270));

        mainFloatingAspectLockSwitch.setOnCheckedChangeListener(
                (btn, checked) -> appConfig.setMainFloatingAspectRatioLocked(checked));
        mainFloatingLongPressDragSwitch.setOnCheckedChangeListener(
                (btn, checked) -> appConfig.setMainFloatingLongPressDragEnabled(checked));

        secondaryOrientationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                int orient = position * 90;
                if (orient != appConfig.getSecondaryDisplayOrientation()) {
                    appConfig.setSecondaryDisplayOrientation(orient);
                    BlindSpotService.update(requireContext());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        seekbarSecondaryAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSecondaryAlphaValue.setText(progress + "%");
                if (fromUser) {
                    appConfig.setSecondaryDisplayAlpha(progress);
                    BlindSpotService.update(requireContext());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                BlindSpotService.update(requireContext());
            }
        });

        btnPreviewCurrentPosition.setOnClickListener(v -> toggleCurrentPositionPreview());
        btnResetCurrentPosition.setOnClickListener(v -> resetCurrentTargetPosition());

        // 分方向旋转与裁切矫正
        blindSpotCorrectionSwitch.setOnCheckedChangeListener((btn, checked) -> {
            appConfig.setBlindSpotCorrectionEnabled(checked);
            BlindSpotService.update(requireContext());
        });
        adjustBlindSpotCorrectionButton.setOnClickListener(v ->
                SettingsFragment.openSubPage(this, new BlindSpotCorrectionFragment()));

        // 保留 ESP32 / 浏览器 MJPEG 投屏入口
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
        syncPositionControlsFromStorage();
        if (isPreviewActive && isMainTargetSelected()) {
            startMainPreviewForSlot();
        }
    }

    private void selectWindowRotation(int degrees) {
        if (isMainTargetSelected()) {
            appConfig.setTurnSignalFloatingRotation(degrees);
        } else {
            appConfig.setSecondaryDisplayRotation(degrees);
        }
        updateRotationPillsUi();
        BlindSpotService.update(requireContext());
    }

    private void applyRectChangeFromVisualEditor(BlindSpotPlacement.RectRatio rect, boolean commitService) {
        isUpdatingPositionUi = true;
        try {
            int[] dims = resolveActiveScreenDimensions();
            int[] px = rect.toPixelBounds(dims[0], dims[1]);
            applyPixelNumbersToFields(px[0], px[1], px[2], px[3], rect);
            if (isMainTargetSelected()) {
                appConfig.setBlindSpotPlacement(activeSlot, rect);
                if (!BlindSpotPlacement.SLOT_RAIN_LEFT.equals(activeSlot)
                        && !BlindSpotPlacement.SLOT_RAIN_RIGHT.equals(activeSlot)) {
                    appConfig.setTurnSignalFloatingBounds(px[0], px[1], px[2], px[3]);
                }
            } else {
                appConfig.setSecondaryDisplayBounds(px[0], px[1], px[2], px[3]);
            }
        } finally {
            isUpdatingPositionUi = false;
        }
        if (commitService && getContext() != null) {
            BlindSpotService.update(getContext());
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
    }

    private void persistPixelBoundsFromControls(boolean commitService) {
        isUpdatingPositionUi = true;
        try {
            int[] dims = resolveActiveScreenDimensions();
            int pxX = seekbarPosX.getProgress();
            int pxY = seekbarPosY.getProgress();
            int pxW = Math.max(1, seekbarPosW.getProgress());
            int pxH = Math.max(1, seekbarPosH.getProgress());

            BlindSpotPlacement.RectRatio rect =
                    BlindSpotPlacement.fromPixels(pxX, pxY, pxW, pxH, dims[0], dims[1]);
            tvPosXPct.setText(rect.xPercent() + "%");
            tvPosYPct.setText(rect.yPercent() + "%");
            tvPosWPct.setText(rect.widthPercent() + "%");
            tvPosHPct.setText(rect.heightPercent() + "%");

            if (isMainTargetSelected()) {
                appConfig.setBlindSpotPlacement(activeSlot, rect);
                visualEditorView.setPlacement(activeSlot, rect);
                int[] normalizedPx = rect.toPixelBounds(dims[0], dims[1]);
                if (!BlindSpotPlacement.SLOT_RAIN_LEFT.equals(activeSlot)
                        && !BlindSpotPlacement.SLOT_RAIN_RIGHT.equals(activeSlot)) {
                    appConfig.setTurnSignalFloatingBounds(
                            normalizedPx[0], normalizedPx[1], normalizedPx[2], normalizedPx[3]);
                }
            } else {
                appConfig.setSecondaryDisplayBounds(pxX, pxY, pxW, pxH);
                visualEditorView.setPlacement(BlindSpotPlacement.SLOT_FRONT, rect);
            }
        } finally {
            isUpdatingPositionUi = false;
        }
        if (commitService && getContext() != null) {
            BlindSpotService.update(getContext());
        }
    }

    private void toggleCurrentPositionPreview() {
        Context ctx = getContext();
        if (ctx == null) return;
        if (isPreviewActive) {
            isPreviewActive = false;
            sendBlindSpotServiceAction("stop_preview_blind_spot");
            btnPreviewCurrentPosition.setText("预览当前位置");
            UiOptionPillHelper.apply(btnPreviewCurrentPosition, ctx, false);
            return;
        }
        if (!WakeUpHelper.hasOverlayPermission(ctx)) {
            Toast.makeText(ctx, "请先授予悬浮窗权限以显示位置预览", Toast.LENGTH_SHORT).show();
            return;
        }
        isPreviewActive = true;
        startMainPreviewForSlot();
        btnPreviewCurrentPosition.setText("停止位置预览");
        UiOptionPillHelper.apply(btnPreviewCurrentPosition, ctx, true, true);
    }

    private void startMainPreviewForSlot() {
        Context ctx = getContext();
        if (ctx == null) return;
        String cameraPos = mapSlotToCameraPos(activeSlot);
        Intent intent = new Intent(ctx, BlindSpotService.class);
        intent.putExtra("action", "preview_blind_spot");
        intent.putExtra("camera_pos", cameraPos);
        intent.putExtra("placement_slot", activeSlot);
        ctx.startService(intent);
    }

    private String mapSlotToCameraPos(String slot) {
        if (BlindSpotPlacement.SLOT_RAIN_LEFT.equals(slot)) return "left";
        if (BlindSpotPlacement.SLOT_RAIN_RIGHT.equals(slot)) return "right";
        return slot;
    }

    private void resetCurrentTargetPosition() {
        if (isMainTargetSelected()) {
            appConfig.resetBlindSpotPlacement(activeSlot);
            appConfig.resetTurnSignalFloatingBounds();
            appConfig.resetMainFloatingBounds();
            Toast.makeText(requireContext(),
                    "已恢复「" + BlindSpotPlacement.getSlotDisplayName(activeSlot) + "」默认位置",
                    Toast.LENGTH_SHORT).show();
        } else {
            appConfig.setSecondaryDisplayBounds(0, 0, 720, 480);
            appConfig.setSecondaryDisplayRotation(0);
            appConfig.setSecondaryDisplayOrientation(0);
            appConfig.setSecondaryDisplayAlpha(100);
            seekbarSecondaryAlpha.setProgress(100);
            tvSecondaryAlphaValue.setText("100%");
            secondaryOrientationSpinner.setSelection(0, false);
            Toast.makeText(requireContext(), "已恢复副屏默认位置与尺寸", Toast.LENGTH_SHORT).show();
        }
        syncPositionControlsFromStorage();
        updateRotationPillsUi();
        BlindSpotService.update(requireContext());
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

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        updateSlotTabsUi();
        updateTriggerPresetPillsUi();
        updateEditToggleUi();
        updateRotationPillsUi();
        styleStaticActionPills(themedContext != null ? themedContext : getContext());
    }

    @Override
    public void onDestroyView() {
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
