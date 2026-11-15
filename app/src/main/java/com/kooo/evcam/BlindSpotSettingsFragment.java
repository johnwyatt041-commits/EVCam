package com.kooo.evcam;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * 补盲选项设置界面
 */
public class BlindSpotSettingsFragment extends Fragment {
    private static final String TAG = "BlindSpotSettingsFragment";

    private SwitchMaterial turnSignalLinkageSwitch;
    private SeekBar turnSignalTimeoutSeekBar;
    private TextView tvTurnSignalTimeout;
    private RadioGroup turnSignalPresetGroup;

    private EditText turnSignalLeftLogEditText;
    private EditText turnSignalRightLogEditText;
    private boolean isUpdatingFromPreset = false; // 防止 TextWatcher 在预设填充时触发

    // 全景影像避让UI控件
    private SwitchMaterial avmAvoidanceSwitch;
    private LinearLayout avmAvoidanceDetailLayout;
    private EditText avmAvoidanceActivityEditText;

    // 转向灯触发log预设方案
    private static final String[][] TURN_SIGNAL_PRESETS = {
        // { presetId, leftKeyword, rightKeyword }
        { "xinghan7", "left front turn signal:1", "right front turn signal:1" },
    };

    private TextView carApiStatusText;

    private SwitchMaterial blindSpotGlobalSwitch;
    private android.widget.LinearLayout subFeaturesContainer;
    private SwitchMaterial secondaryBlindSpotSwitch;
    private Button adjustSecondaryBlindSpotWindowButton;
    private Button adjustMjpegStreamButton;

    private RadioGroup statusBarStyleGroup;
    private View statusBarColorPreview;
    private Button pickStatusBarColorButton;
    private SeekBar statusBarOpacitySeekBar;
    private TextView tvStatusBarOpacityValue;
    private SwitchMaterial blindSpotCorrectionSwitch;
    private Button adjustBlindSpotCorrectionButton;
    private SwitchMaterial mainFloatingAspectRatioLockSwitch;
    private SwitchMaterial mainFloatingLongPressDragSwitch;
    private Button resetMainFloatingButton;
    private Button logcatDebugButton;
    private android.widget.EditText logFilterEditText;
    private Button menuButton;
    private Button homeButton;

    private AppConfig appConfig;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_secondary_display_settings, container, false);
        appConfig = new AppConfig(requireContext());
        initViews(view);
        loadSettings();
        setupListeners();

        return view;
    }

    private void initViews(View view) {
        // 全局开关
        blindSpotGlobalSwitch = view.findViewById(R.id.switch_blind_spot_global);
        subFeaturesContainer = view.findViewById(R.id.blind_spot_sub_features_container);

        // 转向灯联动
        turnSignalLinkageSwitch = view.findViewById(R.id.switch_turn_signal_linkage);
        turnSignalTimeoutSeekBar = view.findViewById(R.id.seekbar_turn_signal_timeout);
        tvTurnSignalTimeout = view.findViewById(R.id.tv_turn_signal_timeout_value);
        turnSignalPresetGroup = view.findViewById(R.id.rg_turn_signal_preset);

        turnSignalLeftLogEditText = view.findViewById(R.id.et_turn_signal_left_log);
        turnSignalRightLogEditText = view.findViewById(R.id.et_turn_signal_right_log);

        secondaryBlindSpotSwitch = view.findViewById(R.id.switch_secondary_blind_spot_display);
        adjustSecondaryBlindSpotWindowButton = view.findViewById(R.id.btn_adjust_secondary_blind_spot_window);
        adjustMjpegStreamButton = view.findViewById(R.id.btn_adjust_mjpeg_stream);
        adjustMjpegStreamButton.setVisibility(AppFeatures.NETWORK_STREAMING ? View.VISIBLE : View.GONE);
        
        // 全景影像避让UI初始化
        avmAvoidanceSwitch = view.findViewById(R.id.switch_avm_avoidance);
        avmAvoidanceDetailLayout = view.findViewById(R.id.layout_avm_avoidance_detail);
        avmAvoidanceActivityEditText = view.findViewById(R.id.et_avm_avoidance_activity);

        statusBarStyleGroup = view.findViewById(R.id.rg_status_bar_style);
        statusBarColorPreview = view.findViewById(R.id.view_status_bar_color_preview);
        pickStatusBarColorButton = view.findViewById(R.id.btn_pick_status_bar_color);
        statusBarOpacitySeekBar = view.findViewById(R.id.seekbar_status_bar_opacity);
        tvStatusBarOpacityValue = view.findViewById(R.id.tv_status_bar_opacity_value);

        blindSpotCorrectionSwitch = view.findViewById(R.id.switch_blind_spot_correction);
        adjustBlindSpotCorrectionButton = view.findViewById(R.id.btn_adjust_blind_spot_correction);

        mainFloatingAspectRatioLockSwitch = view.findViewById(R.id.switch_main_floating_aspect_ratio_lock);
        mainFloatingLongPressDragSwitch = view.findViewById(R.id.switch_main_floating_long_press_drag);
        resetMainFloatingButton = view.findViewById(R.id.btn_reset_main_floating);

        carApiStatusText = view.findViewById(R.id.tv_car_api_status);

        logcatDebugButton = view.findViewById(R.id.btn_logcat_debug);
        logFilterEditText = view.findViewById(R.id.et_log_filter);
        menuButton = view.findViewById(R.id.btn_menu);
        homeButton = view.findViewById(R.id.btn_home);

        bindDisclosure(view, R.id.ui_debug_toggle, R.id.ui_debug_section, "高级调试", null);

    }

    private void bindDisclosure(View root, int buttonId, int panelId, String title, Runnable onExpand) {
        Button button = root.findViewById(buttonId);
        View panel = root.findViewById(panelId);
        button.setOnClickListener(v -> {
            boolean expanded = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
            button.setText(title + (expanded ? " · 收起" : " · 展开"));
            if (expanded && onExpand != null) onExpand.run();

        });
    }

    private void loadSettings() {
        // 全局开关
        boolean globalEnabled = appConfig.isBlindSpotGlobalEnabled();
        blindSpotGlobalSwitch.setChecked(globalEnabled);
        updateSubFeaturesVisibility(globalEnabled);

        // 转向灯联动
        turnSignalLinkageSwitch.setChecked(appConfig.isTurnSignalLinkageEnabled());
        int timeout = appConfig.getTurnSignalTimeout();
        turnSignalTimeoutSeekBar.setProgress(timeout);
        tvTurnSignalTimeout.setText(timeout + "s");
        String currentLeft = appConfig.getTurnSignalLeftTriggerLog();
        String currentRight = appConfig.getTurnSignalRightTriggerLog();
        turnSignalLeftLogEditText.setText(currentLeft);
        turnSignalRightLogEditText.setText(currentRight);
        
        // 根据触发模式和当前关键词匹配预设
        if (appConfig.isCarSignalManagerTriggerMode()) {
            // CarSignalManager 模式：根据保存的预设选择恢复 RadioButton
            String presetSelection = appConfig.getTurnSignalPresetSelection();
            if ("boyue_l".equals(presetSelection)) {
                turnSignalPresetGroup.check(R.id.rb_preset_boyue_l);
            } else {
                // 默认选中 L6/L7
                turnSignalPresetGroup.check(R.id.rb_preset_l6l7);
            }

            carApiStatusText.setVisibility(View.VISIBLE);
            carApiStatusText.setText("CarSignalManager 服务状态: 检测中...");
            checkCarSignalManagerConnection();
        } else if (appConfig.isVhalGrpcTriggerMode()) {
            turnSignalPresetGroup.check(R.id.rb_preset_car_api);

            carApiStatusText.setVisibility(View.VISIBLE);
            carApiStatusText.setText("车辆API 服务状态: 检测中...");
            checkVhalGrpcConnection();
        } else {
            int matchedPreset = findMatchingPreset(currentLeft, currentRight);
            if (matchedPreset == 0) {
                turnSignalPresetGroup.check(R.id.rb_preset_xinghan7);

            } else {
                turnSignalPresetGroup.check(R.id.rb_preset_custom);

            }
            carApiStatusText.setVisibility(View.GONE);
        }

        secondaryBlindSpotSwitch.setChecked(appConfig.isSecondaryDisplayEnabled());

        int statusBarStyle = appConfig.getBlindSpotStatusBarStyle();
        switch (statusBarStyle) {
            case BlindSpotStatusBarView.STYLE_OFF:           statusBarStyleGroup.check(R.id.rb_style_off); break;
            case BlindSpotStatusBarView.STYLE_COMET:         statusBarStyleGroup.check(R.id.rb_style_comet); break;
            case BlindSpotStatusBarView.STYLE_RIPPLE:        statusBarStyleGroup.check(R.id.rb_style_ripple); break;
            case BlindSpotStatusBarView.STYLE_GRADIENT_FILL: statusBarStyleGroup.check(R.id.rb_style_gradient_fill); break;
            case BlindSpotStatusBarView.STYLE_ARROW_RIPPLE:  statusBarStyleGroup.check(R.id.rb_style_arrow_ripple); break;
            default:                                         statusBarStyleGroup.check(R.id.rb_style_sequential); break;
        }

        updateColorPreview(appConfig.getBlindSpotStatusBarColor());

        int opacity = appConfig.getBlindSpotStatusBarBgOpacity();
        statusBarOpacitySeekBar.setProgress(opacity);
        tvStatusBarOpacityValue.setText(opacity + "%");

        blindSpotCorrectionSwitch.setChecked(appConfig.isBlindSpotCorrectionEnabled());

        mainFloatingAspectRatioLockSwitch.setChecked(appConfig.isMainFloatingAspectRatioLocked());
        mainFloatingLongPressDragSwitch.setChecked(appConfig.isMainFloatingLongPressDragEnabled());
        
        // 全景影像避让配置加载
        boolean avmEnabled = appConfig.isAvmAvoidanceEnabled();
        avmAvoidanceSwitch.setChecked(avmEnabled);
        avmAvoidanceDetailLayout.setVisibility(avmEnabled ? View.VISIBLE : View.GONE);
        avmAvoidanceActivityEditText.setText(appConfig.getAvmAvoidanceActivity());

    }

    private void updateSubFeaturesVisibility(boolean globalEnabled) {
        // 全局开关关闭时，隐藏所有子功能区域
        subFeaturesContainer.setVisibility(globalEnabled ? View.VISIBLE : View.GONE);
    }
    
    /**
     * 根据转向联动的车型选择，更新车门联动区域的可见性
     * 选择了银河L6/L7、博越L或车载API(E5/星舰7)时，显示车门联动开关
     */

    private void setupListeners() {
        // 全局开关
        blindSpotGlobalSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setBlindSpotGlobalEnabled(isChecked);
            updateSubFeaturesVisibility(isChecked);
            if (!isChecked) {
                // 关闭时，停止补盲服务
                requireContext().stopService(new android.content.Intent(requireContext(), BlindSpotService.class));
            } else {
                // 开启时，如果有子功能已配置，启动服务
                BlindSpotService.update(requireContext());
            }
        });

        turnSignalLinkageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                turnSignalLinkageSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            appConfig.setTurnSignalLinkageEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        turnSignalTimeoutSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvTurnSignalTimeout.setText(progress + "s");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                appConfig.setTurnSignalTimeout(seekBar.getProgress());
                BlindSpotService.update(requireContext());
            }
        });

        // 预设方案选择
        turnSignalPresetGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_preset_l6l7 || checkedId == R.id.rb_preset_boyue_l) {
                // CarSignalManager API 模式

                carApiStatusText.setVisibility(View.VISIBLE);
                carApiStatusText.setText("CarSignalManager 服务状态: 检测中...");
                appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_CAR_SIGNAL_MANAGER);
                
                // 保存具体选择的预设（博越L 或 L6/L7）
                if (checkedId == R.id.rb_preset_boyue_l) {
                    appConfig.setTurnSignalPresetSelection("boyue_l");
                } else {
                    appConfig.setTurnSignalPresetSelection("l6l7");
                }
                
                checkCarSignalManagerConnection();
                BlindSpotService.update(requireContext());
            } else if (checkedId == R.id.rb_preset_car_api) {
                // 车辆API 模式

                carApiStatusText.setVisibility(View.VISIBLE);
                carApiStatusText.setText("车辆API 服务状态: 检测中...");
                appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_VHAL_GRPC);
                appConfig.setTurnSignalPresetSelection("car_api");
                
                checkVhalGrpcConnection();
                BlindSpotService.update(requireContext());
            } else {
                // Logcat 模式
                carApiStatusText.setVisibility(View.GONE);
                appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_LOGCAT);
                
                // 保存具体选择的预设
                if (checkedId == R.id.rb_preset_custom) {
                    appConfig.setTurnSignalPresetSelection("custom");

                } else if (checkedId == R.id.rb_preset_xinghan7) {
                    appConfig.setTurnSignalPresetSelection("xinghan7");

                    applyPreset(0);
                } else {
                    appConfig.setTurnSignalPresetSelection("e5");

                    applyPreset(1);
                }
                
                BlindSpotService.update(requireContext());
            }
        });

        android.text.TextWatcher turnSignalLogWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (isUpdatingFromPreset) return; // 预设填充时不触发保存
                if (turnSignalLeftLogEditText.getEditableText() == s) {
                    appConfig.setTurnSignalCustomLeftTriggerLog(s.toString());
                } else if (turnSignalRightLogEditText.getEditableText() == s) {
                    appConfig.setTurnSignalCustomRightTriggerLog(s.toString());
                } else {
                    return;
                }
                BlindSpotService.update(requireContext());
            }
        };
        turnSignalLeftLogEditText.addTextChangedListener(turnSignalLogWatcher);
        turnSignalRightLogEditText.addTextChangedListener(turnSignalLogWatcher);

        secondaryBlindSpotSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                secondaryBlindSpotSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            appConfig.setSecondaryDisplayEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        statusBarStyleGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int style;
            if (checkedId == R.id.rb_style_off)            style = BlindSpotStatusBarView.STYLE_OFF;
            else if (checkedId == R.id.rb_style_comet)         style = BlindSpotStatusBarView.STYLE_COMET;
            else if (checkedId == R.id.rb_style_ripple)        style = BlindSpotStatusBarView.STYLE_RIPPLE;
            else if (checkedId == R.id.rb_style_gradient_fill) style = BlindSpotStatusBarView.STYLE_GRADIENT_FILL;
            else if (checkedId == R.id.rb_style_arrow_ripple)  style = BlindSpotStatusBarView.STYLE_ARROW_RIPPLE;
            else                                               style = BlindSpotStatusBarView.STYLE_SEQUENTIAL;
            appConfig.setBlindSpotStatusBarStyle(style);
            BlindSpotService.update(requireContext());
        });

        pickStatusBarColorButton.setOnClickListener(v -> showColorPickerDialog());

        statusBarOpacitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvStatusBarOpacityValue.setText(progress + "%");
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                appConfig.setBlindSpotStatusBarBgOpacity(seekBar.getProgress());
                BlindSpotService.update(requireContext());
            }
        });

        blindSpotCorrectionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setBlindSpotCorrectionEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        mainFloatingAspectRatioLockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setMainFloatingAspectRatioLocked(isChecked);
        });

        mainFloatingLongPressDragSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setMainFloatingLongPressDragEnabled(isChecked);
        });

        resetMainFloatingButton.setOnClickListener(v -> {
            appConfig.resetMainFloatingBounds();
            BlindSpotService.update(requireContext());
            Toast.makeText(requireContext(), "主屏悬浮窗已重置", Toast.LENGTH_SHORT).show();
        });

        adjustBlindSpotCorrectionButton.setOnClickListener(v -> {
            if (!WakeUpHelper.hasOverlayPermission(requireContext())) {
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            if (getActivity() == null) return;
            androidx.fragment.app.FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, new BlindSpotCorrectionFragment());
            transaction.addToBackStack(null);
            transaction.commit();
        });

        adjustSecondaryBlindSpotWindowButton.setOnClickListener(v -> {
            if (!WakeUpHelper.hasOverlayPermission(requireContext())) {
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            if (getActivity() == null) return;
            androidx.fragment.app.FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, new SecondaryBlindSpotAdjustFragment());
            transaction.addToBackStack(null);
            transaction.commit();
        });

        adjustMjpegStreamButton.setOnClickListener(v -> {
            if (getActivity() == null) return;
            androidx.fragment.app.FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, new MjpegStreamAdjustFragment());
            transaction.addToBackStack(null);
            transaction.commit();
        });

        logcatDebugButton.setOnClickListener(v -> {
            String keyword = logFilterEditText.getText().toString().trim();
            if (keyword.isEmpty()) {
                // 没有输入关键词时弹窗提示
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle("提示")
                    .setMessage("未输入过滤关键字，日志量可能很大，可能导致界面卡顿。\n\n建议输入关键字进行过滤，是否继续？")
                    .setPositiveButton("继续打开", (dialog, which) -> {
                        android.content.Intent intent = new android.content.Intent(requireContext(), LogcatViewerActivity.class);
                        intent.putExtra("filter_keyword", "");
                        startActivity(intent);
                    })
                    .setNegativeButton("返回输入", null)
                    .show();
            } else {
                android.content.Intent intent = new android.content.Intent(requireContext(), LogcatViewerActivity.class);
                intent.putExtra("filter_keyword", keyword);
                startActivity(intent);
            }
        });

        menuButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).toggleDrawer();
            }
        });

        homeButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });

        // ==================== 全景影像避让监听器 ====================

        avmAvoidanceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setAvmAvoidanceEnabled(isChecked);
            avmAvoidanceDetailLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            BlindSpotService.update(requireContext());
        });

        avmAvoidanceActivityEditText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String activity = s.toString().trim();
                if (!activity.isEmpty()) {
                    appConfig.setAvmAvoidanceActivity(activity);
                    BlindSpotService.update(requireContext());
                }
            }
        });

    }

    /**
     * 根据当前关键词匹配预设方案
     * @return 预设索引（0=星舰7），-1 表示自定义
     */
    private int findMatchingPreset(String leftKeyword, String rightKeyword) {
        if (leftKeyword == null || rightKeyword == null) return -1;
        for (int i = 0; i < TURN_SIGNAL_PRESETS.length; i++) {
            if (TURN_SIGNAL_PRESETS[i][1].equals(leftKeyword) && TURN_SIGNAL_PRESETS[i][2].equals(rightKeyword)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 应用预设方案：填充关键词并保存配置
     */
    private void applyPreset(int presetIndex) {
        if (presetIndex < 0 || presetIndex >= TURN_SIGNAL_PRESETS.length) return;
        String leftKeyword = TURN_SIGNAL_PRESETS[presetIndex][1];
        String rightKeyword = TURN_SIGNAL_PRESETS[presetIndex][2];

        isUpdatingFromPreset = true;
        turnSignalLeftLogEditText.setText(leftKeyword);
        turnSignalRightLogEditText.setText(rightKeyword);
        isUpdatingFromPreset = false;

        appConfig.setTurnSignalCustomLeftTriggerLog(leftKeyword);
        appConfig.setTurnSignalCustomRightTriggerLog(rightKeyword);
        BlindSpotService.update(requireContext());
    }

    /**
     * 异步检查车辆API 服务连接状态并更新 UI
     */
    private void checkVhalGrpcConnection() {
        if (carApiStatusText == null) return;
        carApiStatusText.setText("车辆API 服务状态: 检测中...");
        carApiStatusText.setTextColor(getResources().getColor(R.color.text_secondary, null));

        new Thread(() -> {
            boolean reachable = VhalSignalObserver.testConnection();
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    if (carApiStatusText == null) return;
                    if (reachable) {
                        carApiStatusText.setText("车辆API 服务状态: ✓ 已连接");
                        carApiStatusText.setTextColor(0xFF4CAF50); // green
                    } else {
                        carApiStatusText.setText("车辆API 服务状态: ✗ 服务不可达");
                        carApiStatusText.setTextColor(0xFFF44336); // red
                    }
                });
            }
        }).start();
    }

    /**
     * 异步检查 CarSignalManager 服务连接状态并更新 UI
     */
    private void checkCarSignalManagerConnection() {
        if (carApiStatusText == null) return;
        carApiStatusText.setText("CarSignalManager 服务状态: 检测中...");
        carApiStatusText.setTextColor(getResources().getColor(R.color.text_secondary, null));

        new Thread(() -> {
            boolean reachable = CarSignalManagerObserver.testConnection(requireContext());
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    if (carApiStatusText == null) return;
                    if (reachable) {
                        carApiStatusText.setText("CarSignalManager 服务状态: ✓ 已连接");
                        carApiStatusText.setTextColor(0xFF4CAF50); // green
                    } else {
                        carApiStatusText.setText("CarSignalManager 服务状态: ✗ 服务不可达");
                        carApiStatusText.setTextColor(0xFFF44336); // red
                    }
                });
            }
        }).start();
    }

    /**
     * 检查车门API连接状态
     */

    private void updateColorPreview(int color) {
        if (statusBarColorPreview == null) return;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        gd.setStroke((int) (1.5f * getResources().getDisplayMetrics().density), 0x40FFFFFF);
        statusBarColorPreview.setBackground(gd);
    }

    private void showColorPickerDialog() {
        ColorPickerView picker = new ColorPickerView(requireContext());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        picker.setPadding(pad, pad, pad, pad);
        picker.setColor(appConfig.getBlindSpotStatusBarColor());

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("选择动效颜色")
                .setView(picker)
                .setPositiveButton("确定", (dialog, which) -> {
                    int color = picker.getColor();
                    appConfig.setBlindSpotStatusBarColor(color);
                    updateColorPreview(color);
                    BlindSpotService.update(requireContext());
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", (dialog, which) -> {
                    int defaultColor = 0xFFFFBF40;
                    appConfig.setBlindSpotStatusBarColor(defaultColor);
                    updateColorPreview(defaultColor);
                    BlindSpotService.update(requireContext());
                })
                .show();
    }
    
    }
