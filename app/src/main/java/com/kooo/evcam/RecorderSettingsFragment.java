package com.kooo.evcam;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.ImageAdjustmentConfig;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.RecordingSoundPlayer;
import com.kooo.evcam.camera.VehicleStatusWatermark;
import com.kooo.evcam.stream.MjpegStreamManager;
import com.kooo.evcam.ui.SettingsCatalog;
import com.kooo.evcam.vehicle.VehicleAutomationCoordinator;
import com.kooo.evcam.vehicle.rule.ConditionLogic;
import com.kooo.evcam.vehicle.rule.RuleAction;
import com.kooo.evcam.vehicle.rule.RuleComparison;
import com.kooo.evcam.vehicle.rule.VehicleRuleCondition;
import com.kooo.evcam.vehicle.rule.VehicleRuleSignal;
import com.kooo.evcam.vehicle.rule.VehicleTriggerRule;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 3. 记录仪设置界面 Fragment
 */
public class RecorderSettingsFragment extends Fragment {
    private static final String TAG = "RecorderSettings";

    private SwitchMaterial autoStartRecordingSwitch;
    private SwitchMaterial screenOffRecordingSwitch;
    private LinearLayout screenOffRecordingLayout;
    private SwitchMaterial recordingStatsSwitch;
    private SwitchMaterial timestampWatermarkSwitch;
    private SwitchMaterial forceH264Switch;
    
    private AppConfig appConfig;
    
    // 悬浮窗相关
    private SwitchMaterial floatingWindowSwitch;
    private LinearLayout floatingWindowSettingsLayout;
    private Spinner floatingWindowSizeSpinner;
    private SeekBar floatingWindowAlphaSeekBar;
    private TextView floatingWindowAlphaText;
    private static final String[] FLOATING_SIZE_OPTIONS = {"超小", "特小", "小", "中", "大", "超大", "特大", "特特大", "PLUS大", "MAX大"};
    private boolean isInitializingFloatingSize = false;
    private int lastAppliedFloatingSize = -1;  // 记录上次应用的大小，避免重复触发
    
    // 车型配置相关
    private Spinner carModelSpinner;
    private Button customCameraConfigButton;
    private static final String[] CAR_MODEL_OPTIONS = {"银河E5", "银河A7", "银河E5-多按钮", "银河L6/L7", "银河L7-多按钮", "26款星舰7", "手机", "自定义车型", "多视角布局"};
    private boolean isInitializingCarModel = false;
    private String lastAppliedCarModel = null;
    
    // 录制模式配置相关
    private Spinner recordingModeSpinner;
    private TextView recordingModeDescText;
    private static final String[] RECORDING_MODE_OPTIONS = {"自动（推荐）", "MediaRecorder", "MediaCodec"};
    private boolean isInitializingRecordingMode = false;
    private String lastAppliedRecordingMode = null;
    
    // 分段时长配置相关
    private Spinner segmentDurationSpinner;
    private static final String[] SEGMENT_DURATION_OPTIONS = {"1分钟", "3分钟", "5分钟"};
    private boolean isInitializingSegmentDuration = false;
    private int lastAppliedSegmentDuration = -1;
    
    // 存储位置配置相关
    private Spinner storageLocationSpinner;
    private TextView storageLocationDescText;
    private Button storageDebugButton;
    private String[] storageLocationOptions;
    private boolean isInitializingStorageLocation = false;
    private String lastAppliedStorageLocation = null;
    private boolean hasExternalSdCard = false;
    
    // 中转写入配置相关
    private SwitchMaterial relayWriteSwitch;
    private TextView relayWriteDescText;
    private boolean isInitializingRelayWrite = false;
    
    // 存储清理配置相关
    private EditText videoStorageLimitEdit;
    private EditText photoStorageLimitEdit;
    private TextView videoUsedSizeText;
    private TextView photoUsedSizeText;
    private boolean isInitializingStorageCleanup = false;
    
    // 录制摄像头选择配置相关
    private android.widget.CheckBox cbRecordCameraFront;
    private android.widget.CheckBox cbRecordCameraBack;
    private android.widget.CheckBox cbRecordCameraLeft;
    private android.widget.CheckBox cbRecordCameraRight;
    private boolean isInitializingRecordingCameraSelection = false;

    // T05: 3.1 / 3.2 / 3.3 / 3.4 分区切换与新增控件
    private Button btnTabRecord31, btnTabImage32, btnTabDisplay33, btnTabSound34;
    private View sectionRecord31, sectionImage32, sectionDisplay33, sectionSound34;
    private android.widget.ScrollView scrollRecorderSettings;

    // T05: 3.1 方案 A（录像目标帧率 + 空闲预览限帧）
    private static final int[] TARGET_FPS_VALUES = {0, 15, 20, 25, 30};
    private static final String[] TARGET_FPS_LABELS = {"自动 (E5 25fps)", "15 fps", "20 fps", "25 fps", "30 fps"};
    private static final int[] PREVIEW_LIMIT_VALUES = {0, 25, 15};
    private static final String[] PREVIEW_LIMIT_LABELS = {"不限帧（流畅）", "标准 (25 fps)", "省电 (15 fps)"};

    // T05: 3.2 画面调节
    private static final String[] CAMERA_POS_KEYS = {"front", "back", "left", "right"};
    private static final String[] CAMERA_POS_LABELS = {"前", "后", "左", "右"};
    private String selectedImageCameraPos = "front";
    private SwitchMaterial switchImageAdjustEnabled;
    private View layoutImageGlobalAdjust;
    private SeekBar seekImageQuality, seekImageBrightness, seekImageContrast, seekImageSaturation, seekImageCrop;
    private TextView tvImageQualityValue, tvImageBrightnessValue, tvImageContrastValue, tvImageSaturationValue, tvImageCropValue;
    private SwitchMaterial switchImageMirror;
    private boolean isUpdatingImageUi = false;

    // T05/包D: 3.3 独立水印开关（时间 / 车速 / 档位 / 转向灯）+ 自定义文字 + 位置 + 样式
    private SwitchMaterial switchWatermarkSpeed, switchWatermarkGear, switchWatermarkTurnSignal;
    private SwitchMaterial switchWatermarkCustomText;
    private EditText etWatermarkCustomText;
    private boolean isUpdatingDisplayUi = false;

    // T05: 3.4 提示音与系统音频选取器
    private static final String[] SOUND_MODE_KEYS = {
            RecordingSoundPlayer.MODE_NONE,
            RecordingSoundPlayer.MODE_SYSTEM,
            RecordingSoundPlayer.MODE_CUSTOM
    };
    private static final String[] SOUND_MODE_LABELS = {"不播放", "系统提示音", "自定义音频 (<=20MB)"};
    private Spinner spinnerStartSoundMode, spinnerStopSoundMode;
    private TextView tvStartSoundStatus, tvStopSoundStatus;
    private Button btnPickStartSound, btnPickStopSound;
    private boolean isUpdatingSoundUi = false;
    private boolean pendingPickForStartSound = true;

    private final ActivityResultLauncher<String[]> audioPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || getContext() == null || appConfig == null) return;
                boolean forStart = pendingPickForStartSound;
                try {
                    File imported = RecordingSoundPlayer.importCustomSoundUri(getContext(), uri, forStart);
                    if (forStart) {
                        appConfig.setRecordStartSoundPath(imported.getAbsolutePath());
                        appConfig.setRecordStartSoundMode(RecordingSoundPlayer.MODE_CUSTOM);
                    } else {
                        appConfig.setRecordStopSoundPath(imported.getAbsolutePath());
                        appConfig.setRecordStopSoundMode(RecordingSoundPlayer.MODE_CUSTOM);
                    }
                    refreshSoundSectionUi();
                    Toast.makeText(getContext(), "已导入音频：" + imported.getName(), Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(getContext(), "导入音频失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_recorder_settings, container, false);

        Button btnBack = view.findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().getSupportFragmentManager().popBackStack();
            }
        });

        // 初始化应用配置
        if (getContext() != null) {
            appConfig = new AppConfig(getContext());

            // 初始化顶部分区切换（3.1 录制 / 3.2 画面 / 3.3 显示 / 3.4 提示音）
            initRecorderSubTabs(view);

            // 初始化车型配置
            initCarModelConfig(view);

            // 初始化录制模式配置
            initRecordingModeConfig(view);

            // 初始化分段时长配置
            initSegmentDurationConfig(view);

            // 初始化方案 A 帧率与预览限帧配置
            initFrameRatePolicyConfig(view);

            // 初始化录制摄像头选择配置
            initRecordingCameraSelectionConfig(view);

            // 初始化存储位置配置
            initStorageLocationConfig(view);

            // 初始化存储清理配置
            initStorageCleanupConfig(view);

            // 初始化 3.2 画面调节与总开关旁路
            initImageAdjustSection(view);
        }

        // 初始化分辨率设置入口
        Button btnResolutionSettings = view.findViewById(R.id.btn_resolution_settings);
        btnResolutionSettings.setOnClickListener(v -> openResolutionSettings());

        // 初始化录制状态显示开关
        recordingStatsSwitch = view.findViewById(R.id.switch_recording_stats);
        if (getContext() != null && appConfig != null) {
            recordingStatsSwitch.setChecked(appConfig.isRecordingStatsEnabled());
        }

        // 设置录制状态显示开关监听器
        recordingStatsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setRecordingStatsEnabled(isChecked);
                String message = isChecked ? "录制状态显示已开启" : "录制状态显示已关闭";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
                
                // 通知 MainActivity 刷新设置
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).refreshRecordingStatsSettings();
                }
            }
        });

        // 初始化时间角标开关与 3.3 显示/水印设置
        timestampWatermarkSwitch = view.findViewById(R.id.switch_timestamp_watermark);
        if (getContext() != null && appConfig != null) {
            timestampWatermarkSwitch.setChecked(appConfig.isTimestampWatermarkEnabled());
        }

        // 设置时间角标开关监听器
        timestampWatermarkSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setTimestampWatermarkEnabled(isChecked);
                notifyActiveRecorders();
                if (!isUpdatingDisplayUi) {
                    String message = isChecked ? "时间水印已开启" : "时间水印已关闭";
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                    AppLog.d("SettingsFragment", message);
                }
            }
        });

        TextView recordingExperiment = view.findViewById(R.id.recording_experiment_description);
        recordingExperiment.setText(com.kooo.evcam.camera.RecordingExperiment.description());

        // 初始化 3.3 扩展水印配置与 3.4 提示音配置
        initDisplayWatermarkExtensions(view);
        initSoundSection(view);
        initVehicleAutomationSection(view);

        // 初始化强制 H.264 编码开关
        forceH264Switch = view.findViewById(R.id.switch_force_h264);
        if (getContext() != null && appConfig != null) {
            forceH264Switch.setChecked(appConfig.isForceH264Encoding());
        }
        forceH264Switch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setForceH264Encoding(isChecked);
                String message = isChecked ? "已切换为 H.264 兼容编码，下一段录制生效" : "已切换为 H.265/HEVC 编码，下一段录制生效";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
            }
        });

        // 初始化启动自动录制开关
        autoStartRecordingSwitch = view.findViewById(R.id.switch_auto_start_recording);
        if (getContext() != null && appConfig != null) {
            autoStartRecordingSwitch.setChecked(appConfig.isAutoStartRecording());
        }

        // 初始化息屏录制开关
        screenOffRecordingSwitch = view.findViewById(R.id.switch_screen_off_recording);
        screenOffRecordingLayout = view.findViewById(R.id.layout_screen_off_recording);
        if (getContext() != null && appConfig != null) {
            screenOffRecordingSwitch.setChecked(appConfig.isScreenOffRecordingEnabled());
            // 根据启动自动录制的状态决定是否显示息屏录制开关
            updateScreenOffRecordingVisibility(appConfig.isAutoStartRecording());
        }

        // 设置启动自动录制开关监听器
        autoStartRecordingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setAutoStartRecording(isChecked);
                String message = isChecked ? "启动自动录制已启用，下次启动生效" : "启动自动录制已禁用";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
                
                // 更新息屏录制开关的可见性
                updateScreenOffRecordingVisibility(isChecked);
            }
        });

        // 设置息屏录制开关监听器
        screenOffRecordingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setScreenOffRecordingEnabled(isChecked);
                String message = isChecked ? "息屏录制已启用，息屏时将继续录制" : "息屏录制已禁用，息屏10秒后将自动停止录制";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
            }
        });

        // 根据全局生命周期状态同步定时保活任务
        if (getContext() != null) {
            com.kooo.evcam.lifecycle.BackgroundCapabilityController.syncBackgroundKeepAlive(getContext());
        }

        // 初始化悬浮窗设置
        initFloatingWindowSettings(view);

        // 初始化录制悬浮按钮设置
        initRecordingFloatingSettings(view);

        // 沉浸式状态栏兼容
        View toolbar = view.findViewById(R.id.toolbar);
        if (toolbar != null) {
            final int originalPaddingTop = toolbar.getPaddingTop();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
                int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(v.getPaddingLeft(), statusBarHeight + originalPaddingTop, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            androidx.core.view.ViewCompat.requestApplyInsets(toolbar);
        }

        SettingsFragment.configureEmbeddedToolbar(this, view, "记录仪");

        return view;
    }
    
    /**
     * 初始化悬浮窗设置
     */
    private void initFloatingWindowSettings(View view) {
        floatingWindowSwitch = view.findViewById(R.id.switch_floating_window);
        floatingWindowSettingsLayout = view.findViewById(R.id.layout_floating_window_settings);
        floatingWindowSizeSpinner = view.findViewById(R.id.spinner_floating_window_size);
        floatingWindowAlphaSeekBar = view.findViewById(R.id.seekbar_floating_window_alpha);
        floatingWindowAlphaText = view.findViewById(R.id.tv_floating_window_alpha_value);
        
        if (floatingWindowSwitch == null || getContext() == null || appConfig == null) {
            return;
        }
        
        // 初始化悬浮窗开关状态
        boolean floatingEnabled = appConfig.isFloatingWindowEnabled();
        floatingWindowSwitch.setChecked(floatingEnabled);
        updateFloatingWindowSettingsVisibility(floatingEnabled);
        
        // 设置悬浮窗开关监听器
        floatingWindowSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() == null || appConfig == null) {
                return;
            }
            
            // 检查悬浮窗权限
            if (isChecked && !WakeUpHelper.hasOverlayPermission(getContext())) {
                Toast.makeText(getContext(), "请先在权限设置中授权悬浮窗权限", Toast.LENGTH_SHORT).show();
                buttonView.setChecked(false);
                WakeUpHelper.requestOverlayPermission(getContext());
                return;
            }
            
            appConfig.setFloatingWindowEnabled(isChecked);
            updateFloatingWindowSettingsVisibility(isChecked);
            
            if (isChecked) {
                FloatingWindowService.start(getContext());
                Toast.makeText(getContext(), "悬浮窗已开启", Toast.LENGTH_SHORT).show();
                
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).broadcastCurrentRecordingState();
                }
            } else {
                FloatingWindowService.stop(getContext());
                Toast.makeText(getContext(), "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
            }
        });
        
        // 初始化悬浮窗大小选择器
        initFloatingWindowSizeSpinner();
        
        // 初始化悬浮窗透明度滑块
        initFloatingWindowAlphaSeekBar();
    }
    
    /**
     * 初始化悬浮窗大小选择器
     */
    private void initFloatingWindowSizeSpinner() {
        if (floatingWindowSizeSpinner == null || getContext() == null) {
            return;
        }
        
        isInitializingFloatingSize = true;
        
        // 记录当前保存的大小值
        lastAppliedFloatingSize = appConfig.getFloatingWindowSize();
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                FLOATING_SIZE_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        floatingWindowSizeSpinner.setAdapter(adapter);
        
        floatingWindowSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int sizeDp;
                String sizeName;
                switch (position) {
                    case 0:
                        sizeDp = AppConfig.FLOATING_SIZE_TINY;
                        sizeName = "超小";
                        break;
                    case 1:
                        sizeDp = AppConfig.FLOATING_SIZE_EXTRA_SMALL;
                        sizeName = "特小";
                        break;
                    case 2:
                        sizeDp = AppConfig.FLOATING_SIZE_SMALL;
                        sizeName = "小";
                        break;
                    case 3:
                        sizeDp = AppConfig.FLOATING_SIZE_MEDIUM;
                        sizeName = "中";
                        break;
                    case 4:
                        sizeDp = AppConfig.FLOATING_SIZE_LARGE;
                        sizeName = "大";
                        break;
                    case 5:
                        sizeDp = AppConfig.FLOATING_SIZE_EXTRA_LARGE;
                        sizeName = "超大";
                        break;
                    case 6:
                        sizeDp = AppConfig.FLOATING_SIZE_HUGE;
                        sizeName = "特大";
                        break;
                    case 7:
                        sizeDp = AppConfig.FLOATING_SIZE_GIANT;
                        sizeName = "特特大";
                        break;
                    case 8:
                        sizeDp = AppConfig.FLOATING_SIZE_PLUS;
                        sizeName = "PLUS大";
                        break;
                    default:
                        sizeDp = AppConfig.FLOATING_SIZE_MAX;
                        sizeName = "MAX大";
                        break;
                }
                
                // 初始化阶段不处理
                if (isInitializingFloatingSize) {
                    return;
                }
                
                // 与上次应用的值相同，不重复处理
                if (sizeDp == lastAppliedFloatingSize) {
                    return;
                }
                
                lastAppliedFloatingSize = sizeDp;
                appConfig.setFloatingWindowSize(sizeDp);
                
                if (getContext() != null && appConfig.isFloatingWindowEnabled()) {
                    FloatingWindowService.sendUpdateFloatingWindow(getContext());
                    Toast.makeText(getContext(), "悬浮窗大小已设置为「" + sizeName + "」", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        // 根据当前保存的尺寸确定选中项
        int currentSize = appConfig.getFloatingWindowSize();
        int selectedIndex;
        if (currentSize <= AppConfig.FLOATING_SIZE_TINY) {
            selectedIndex = 0;  // 超小
        } else if (currentSize <= AppConfig.FLOATING_SIZE_EXTRA_SMALL) {
            selectedIndex = 1;  // 特小
        } else if (currentSize <= AppConfig.FLOATING_SIZE_SMALL) {
            selectedIndex = 2;  // 小
        } else if (currentSize <= AppConfig.FLOATING_SIZE_MEDIUM) {
            selectedIndex = 3;  // 中
        } else if (currentSize <= AppConfig.FLOATING_SIZE_LARGE) {
            selectedIndex = 4;  // 大
        } else if (currentSize <= AppConfig.FLOATING_SIZE_EXTRA_LARGE) {
            selectedIndex = 5;  // 超大
        } else if (currentSize <= AppConfig.FLOATING_SIZE_HUGE) {
            selectedIndex = 6;  // 特大
        } else if (currentSize <= AppConfig.FLOATING_SIZE_GIANT) {
            selectedIndex = 7;  // 特特大
        } else if (currentSize <= AppConfig.FLOATING_SIZE_PLUS) {
            selectedIndex = 8;  // PLUS大
        } else {
            selectedIndex = 9;  // MAX大
        }
        floatingWindowSizeSpinner.setSelection(selectedIndex);
        
        floatingWindowSizeSpinner.post(() -> {
            isInitializingFloatingSize = false;
        });
    }
    
    /**
     * 初始化悬浮窗透明度滑块
     */
    private void initFloatingWindowAlphaSeekBar() {
        if (floatingWindowAlphaSeekBar == null || floatingWindowAlphaText == null || getContext() == null) {
            return;
        }
        
        floatingWindowAlphaSeekBar.setMax(80);
        
        int currentAlpha = appConfig.getFloatingWindowAlpha();
        floatingWindowAlphaSeekBar.setProgress(currentAlpha - 20);
        floatingWindowAlphaText.setText(currentAlpha + "%");
        
        floatingWindowAlphaSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int alpha = progress + 20;
                floatingWindowAlphaText.setText(alpha + "%");
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int alpha = seekBar.getProgress() + 20;
                appConfig.setFloatingWindowAlpha(alpha);
                
                if (getContext() != null && appConfig.isFloatingWindowEnabled()) {
                    FloatingWindowService.sendUpdateFloatingWindow(getContext());
                }
            }
        });
    }
    
    /**
     * 更新悬浮窗设置区域的可见性
     */
    private void updateFloatingWindowSettingsVisibility(boolean visible) {
        if (floatingWindowSettingsLayout != null) {
            floatingWindowSettingsLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 初始化录制悬浮按钮设置
     */
    private void initRecordingFloatingSettings(View view) {
        SwitchMaterial recordingFloatingSwitch = view.findViewById(R.id.switch_recording_floating);
        LinearLayout sizeSettingsLayout = view.findViewById(R.id.recording_floating_size_settings);
        SeekBar buttonSizeSeekBar = view.findViewById(R.id.seekbar_button_size);
        SeekBar textSizeSeekBar = view.findViewById(R.id.seekbar_text_size);
        TextView buttonSizeValueText = view.findViewById(R.id.text_button_size_value);
        TextView textSizeValueText = view.findViewById(R.id.text_time_size_value);

        if (recordingFloatingSwitch == null || getContext() == null || appConfig == null) {
            return;
        }

        // 初始化开关状态
        boolean isEnabled = appConfig.isRecordingFloatingEnabled();
        recordingFloatingSwitch.setChecked(isEnabled);
        if (sizeSettingsLayout != null) {
            sizeSettingsLayout.setVisibility(isEnabled ? View.VISIBLE : View.GONE);
        }

        // 初始化大小设置
        if (buttonSizeSeekBar != null && textSizeSeekBar != null) {
            // 设置当前值
            int currentButtonSize = appConfig.getRecordingFloatingButtonSizeDp();
            int currentTextSize = appConfig.getRecordingFloatingTimeTextSizeSp();

            buttonSizeSeekBar.setProgress(currentButtonSize);
            textSizeSeekBar.setProgress(currentTextSize);

            buttonSizeValueText.setText(currentButtonSize + "dp");
            textSizeValueText.setText(currentTextSize + "sp");

            // 按钮大小监听器
            buttonSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int size = Math.max(32, progress); // 最小32dp
                    buttonSizeValueText.setText(size + "dp");

                    // 实时发送广播更新悬浮按钮大小
                    Context ctx = getContext();
                    if (ctx != null) {
                        sendRecordingFloatingSizeBroadcast(ctx, size, -1);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    int size = Math.max(32, seekBar.getProgress());
                    appConfig.setRecordingFloatingButtonSizeDp(size);
                }
            });

            // 文字大小监听器
            textSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int size = Math.max(8, progress); // 最小8sp
                    textSizeValueText.setText(size + "sp");

                    // 实时发送广播更新文字大小
                    Context ctx = getContext();
                    if (ctx != null) {
                        sendRecordingFloatingSizeBroadcast(ctx, -1, size);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    int size = Math.max(8, seekBar.getProgress());
                    appConfig.setRecordingFloatingTimeTextSizeSp(size);
                }
            });
        }

        // 设置开关监听器
        recordingFloatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() == null) {
                return;
            }

            // 保存开关状态
            appConfig.setRecordingFloatingEnabled(isChecked);

            // 显示/隐藏大小设置
            if (sizeSettingsLayout != null) {
                sizeSettingsLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }

            // 检查悬浮窗权限
            if (isChecked && !WakeUpHelper.hasOverlayPermission(getContext())) {
                Toast.makeText(getContext(), "请先在权限设置中授权悬浮窗权限", Toast.LENGTH_SHORT).show();
                buttonView.setChecked(false);
                appConfig.setRecordingFloatingEnabled(false);
                WakeUpHelper.requestOverlayPermission(getContext());
                return;
            }

            // 在后台线程启动或停止服务，避免ANR
            new Thread(() -> {
                try {
                    Intent intent = new Intent(getContext(), com.kooo.evcam.service.RecordingFloatingService.class);
                    if (isChecked) {
                        intent.setAction(com.kooo.evcam.service.RecordingFloatingService.ACTION_SHOW);
                        getContext().startService(intent);
                    } else {
                        intent.setAction(com.kooo.evcam.service.RecordingFloatingService.ACTION_HIDE);
                        getContext().startService(intent);
                    }
                } catch (Exception e) {
                    AppLog.e(TAG, "启动/停止录制悬浮服务失败", e);
                }
            }).start();

            Toast.makeText(getContext(), isChecked ? "录制悬浮按钮已开启" : "录制悬浮按钮已关闭", Toast.LENGTH_SHORT).show();
        });
    }

    static void sendRecordingFloatingSizeBroadcast(@NonNull Context context, int buttonSize, int textSize) {
        Intent intent = new Intent(com.kooo.evcam.service.RecordingFloatingService.ACTION_UPDATE_SIZE);
        intent.setPackage(context.getPackageName());
        intent.putExtra(com.kooo.evcam.service.RecordingFloatingService.EXTRA_BUTTON_SIZE, buttonSize);
        intent.putExtra(com.kooo.evcam.service.RecordingFloatingService.EXTRA_TEXT_SIZE, textSize);
        context.sendBroadcast(intent);
    }

    /**
     * 更新息屏录制开关的可见性
     * 仅当启动自动录制开启时才显示
     */
    private void updateScreenOffRecordingVisibility(boolean autoStartRecordingEnabled) {
        if (screenOffRecordingLayout != null) {
            screenOffRecordingLayout.setVisibility(autoStartRecordingEnabled ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        
        // 重新检测 U盘（可能在授权后返回或U盘插拔）- 异步执行避免卡顿
        if (getContext() != null) {
            final Context context = getContext();
            final String currentLocation = appConfig != null ? appConfig.getStorageLocation() : AppConfig.STORAGE_INTERNAL;
            
            // 异步检测 U盘
            new Thread(() -> {
                boolean newHasSdCard = StorageHelper.hasExternalSdCard(context);
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (getContext() == null) return;
                        
                        if (newHasSdCard != hasExternalSdCard) {
                            hasExternalSdCard = newHasSdCard;
                            if (storageDebugButton != null) {
                                storageDebugButton.setVisibility(hasExternalSdCard ? View.GONE : View.VISIBLE);
                            }
                            
                            // 更新 Spinner 选项文字
                            if (storageLocationSpinner != null) {
                                if (hasExternalSdCard) {
                                    storageLocationOptions = new String[] {"内部存储", "U盘"};
                                } else {
                                    storageLocationOptions = new String[] {"内部存储", "U盘（未检测到）"};
                                }
                                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                        getContext(),
                                        R.layout.spinner_item,
                                        storageLocationOptions
                                );
                                adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                                
                                isInitializingStorageLocation = true;
                                storageLocationSpinner.setAdapter(adapter);
                                
                                // 恢复用户之前的选择
                                int selectedIndex = AppConfig.STORAGE_EXTERNAL_SD.equals(currentLocation) ? 1 : 0;
                                storageLocationSpinner.setSelection(selectedIndex);
                                storageLocationSpinner.post(() -> isInitializingStorageLocation = false);
                                // 注意：这里不弹 Toast，因为 onResume 不代表 U 盘刚插入
                                // 只是界面切换后重新检测状态，避免每次打开设置都提示"检测到U盘"
                            }
                        }
                        
                        // 始终更新描述文字（可能U盘状态变化或空间变化）
                        updateStorageLocationDescriptionAsync(currentLocation);
                    });
                }
            }).start();
            
            // 更新存储占用大小显示（已经是异步的）
            updateStorageUsedSizeDisplay();
        }
        
        // 更新悬浮窗开关状态
        if (floatingWindowSwitch != null && getContext() != null && appConfig != null) {
            boolean hasPermission = WakeUpHelper.hasOverlayPermission(getContext());
            boolean isEnabled = appConfig.isFloatingWindowEnabled();
            
            if (isEnabled && hasPermission) {
                FloatingWindowService.start(getContext());
            }
        }
    }
    
    /**
     * 初始化车型配置
     */
    private void initCarModelConfig(View view) {
        carModelSpinner = view.findViewById(R.id.spinner_car_model);
        customCameraConfigButton = view.findViewById(R.id.btn_custom_camera_config);
        
        if (carModelSpinner == null || customCameraConfigButton == null || getContext() == null) {
            return;
        }

        isInitializingCarModel = true;
        lastAppliedCarModel = (appConfig != null) ? appConfig.getCarModel() : null;
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                CAR_MODEL_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        carModelSpinner.setAdapter(adapter);
        
        carModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newModel;
                String modelName;
                
                if (position == 0) {
                    newModel = AppConfig.CAR_MODEL_GALAXY_E5;
                    modelName = "银河E5";
                } else if (position == 1) {
                    newModel = AppConfig.CAR_MODEL_GALAXY_A7;
                    modelName = "银河A7";
                } else if (position == 2) {
                    newModel = AppConfig.CAR_MODEL_E5_MULTI;
                    modelName = "银河E5-多按钮";
                } else if (position == 3) {
                    newModel = AppConfig.CAR_MODEL_L7;
                    modelName = "银河L6/L7";
                } else if (position == 4) {
                    newModel = AppConfig.CAR_MODEL_L7_MULTI;
                    modelName = "银河L7-多按钮";
                } else if (position == 5) {
                    newModel = AppConfig.CAR_MODEL_XINGHAN_7;
                    modelName = "26款星舰7";
                } else if (position == 6) {
                    newModel = AppConfig.CAR_MODEL_PHONE;
                    modelName = "手机";
                } else if (position == 8) {
                    newModel = AppConfig.CAR_MODEL_MULTIVIEW;
                    modelName = "多视角布局";
                } else {
                    newModel = AppConfig.CAR_MODEL_CUSTOM;
                    modelName = "自定义车型";
                }

                // 自定义车型和多视角布局显示配置按钮
                updateCustomConfigButtonVisibility(position == 7 || position == 8);

                if (isInitializingCarModel) {
                    return;
                }

                if (newModel.equals(lastAppliedCarModel)) {
                    return;
                }

                lastAppliedCarModel = newModel;
                appConfig.setCarModel(newModel);
                
                // 切换车型时重置录制摄像头选择为全选（避免之前的设置导致无法录制）
                appConfig.resetRecordingCameraSelection();
                
                // 更新录制摄像头选择的 UI（摄像头数量由 AppConfig.getCameraCount() 自动根据车型返回）
                updateRecordingCameraSelectionUI();
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "已切换为「" + modelName + "」，重启应用后生效", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        String currentModel = appConfig.getCarModel();
        int selectedIndex = 0;
        if (AppConfig.CAR_MODEL_GALAXY_A7.equals(currentModel)) {
            selectedIndex = 1;
        } else if (AppConfig.CAR_MODEL_E5_MULTI.equals(currentModel)) {
            selectedIndex = 2;
        } else if (AppConfig.CAR_MODEL_L7.equals(currentModel)) {
            selectedIndex = 3;
        } else if (AppConfig.CAR_MODEL_L7_MULTI.equals(currentModel)) {
            selectedIndex = 4;
        } else if (AppConfig.CAR_MODEL_XINGHAN_7.equals(currentModel)) {
            selectedIndex = 5;
        } else if (AppConfig.CAR_MODEL_PHONE.equals(currentModel)) {
            selectedIndex = 6;
        } else if (AppConfig.CAR_MODEL_CUSTOM.equals(currentModel)) {
            selectedIndex = 7;
        } else if (AppConfig.CAR_MODEL_MULTIVIEW.equals(currentModel)) {
            selectedIndex = 8;
        }
        carModelSpinner.setSelection(selectedIndex);
        
        carModelSpinner.post(() -> {
            isInitializingCarModel = false;
        });
        
        customCameraConfigButton.setOnClickListener(v -> {
            openCustomCameraConfig();
        });
    }
    
    /**
     * 更新自定义配置按钮的可见性
     */
    private void updateCustomConfigButtonVisibility(boolean visible) {
        if (customCameraConfigButton != null) {
            customCameraConfigButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
    
    /**
     * 初始化录制模式配置
     */
    private void initRecordingModeConfig(View view) {
        recordingModeSpinner = view.findViewById(R.id.spinner_recording_mode);
        recordingModeDescText = view.findViewById(R.id.tv_recording_mode_desc);
        if (BuildConfig.RECORDING_COMPARISON && recordingModeSpinner != null && getContext() != null) {
            recordingModeSpinner.setAdapter(new ArrayAdapter<>(getContext(), R.layout.spinner_item,
                    new String[]{"录像方式对比测试"}));
            recordingModeSpinner.setEnabled(false);
            if (recordingModeDescText != null) recordingModeDescText.setText(
                    com.kooo.evcam.camera.RecordingExperiment.description());
            return;
        }
        
        if (recordingModeSpinner == null || getContext() == null) {
            return;
        }
        
        isInitializingRecordingMode = true;
        lastAppliedRecordingMode = (appConfig != null) ? appConfig.getRecordingMode() : null;
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                RECORDING_MODE_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        recordingModeSpinner.setAdapter(adapter);
        
        recordingModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newMode;
                String modeName;
                String modeDesc;
                
                if (position == 0) {
                    newMode = AppConfig.RECORDING_MODE_AUTO;
                    modeName = "自动";
                    // 显示当前实际使用的模式
                    String actualMode = appConfig.shouldUseCodecRecording() ? "MediaCodec" : "MediaRecorder";
                    modeDesc = "MediaRecorder编码更稳定，MediaCodec兼容性更好，如果无法存储视频，尝试修改\n当前自动选择：" + actualMode;
                } else if (position == 1) {
                    newMode = AppConfig.RECORDING_MODE_MEDIA_RECORDER;
                    modeName = "MediaRecorder";
                    modeDesc = "使用系统硬件编码器，兼容性好";
                } else {
                    newMode = AppConfig.RECORDING_MODE_CODEC;
                    modeName = "MediaCodec";
                    modeDesc = "软编码方案，解决部分设备兼容问题";
                }
                
                updateRecordingModeDescription(modeDesc);
                
                if (isInitializingRecordingMode) {
                    return;
                }
                
                if (newMode.equals(lastAppliedRecordingMode)) {
                    return;
                }
                
                lastAppliedRecordingMode = newMode;
                appConfig.setRecordingMode(newMode);
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "已切换为「" + modeName + "」模式，下次录制生效", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        String currentMode = appConfig.getRecordingMode();
        int selectedIndex = 0;
        if (AppConfig.RECORDING_MODE_MEDIA_RECORDER.equals(currentMode)) {
            selectedIndex = 1;
        } else if (AppConfig.RECORDING_MODE_CODEC.equals(currentMode)) {
            selectedIndex = 2;
        }
        recordingModeSpinner.setSelection(selectedIndex);
        
        recordingModeSpinner.post(() -> {
            isInitializingRecordingMode = false;
        });
    }
    
    /**
     * 更新录制模式描述文字
     */
    private void updateRecordingModeDescription(String desc) {
        if (recordingModeDescText != null) {
            recordingModeDescText.setText(desc);
        }
    }
    
    /**
     * 初始化分段时长配置
     */
    private void initSegmentDurationConfig(View view) {
        segmentDurationSpinner = view.findViewById(R.id.spinner_segment_duration);
        
        if (segmentDurationSpinner == null || getContext() == null) {
            return;
        }
        
        isInitializingSegmentDuration = true;
        lastAppliedSegmentDuration = (appConfig != null) ? appConfig.getSegmentDurationMinutes() : AppConfig.SEGMENT_DURATION_1_MIN;
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                SEGMENT_DURATION_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        segmentDurationSpinner.setAdapter(adapter);
        
        segmentDurationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int newDuration;
                String durationName;
                
                if (position == 0) {
                    newDuration = AppConfig.SEGMENT_DURATION_1_MIN;
                    durationName = "1分钟";
                } else if (position == 1) {
                    newDuration = AppConfig.SEGMENT_DURATION_3_MIN;
                    durationName = "3分钟";
                } else {
                    newDuration = AppConfig.SEGMENT_DURATION_5_MIN;
                    durationName = "5分钟";
                }
                
                if (isInitializingSegmentDuration) {
                    return;
                }
                
                if (newDuration == lastAppliedSegmentDuration) {
                    return;
                }
                
                lastAppliedSegmentDuration = newDuration;
                appConfig.setSegmentDurationMinutes(newDuration);
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "分段时长已设置为「" + durationName + "」，下次录制生效", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        // 根据当前配置设置选中项
        int currentDuration = appConfig.getSegmentDurationMinutes();
        int selectedIndex = 0;  // 默认1分钟
        if (currentDuration == AppConfig.SEGMENT_DURATION_3_MIN) {
            selectedIndex = 1;
        } else if (currentDuration == AppConfig.SEGMENT_DURATION_5_MIN) {
            selectedIndex = 2;
        }
        segmentDurationSpinner.setSelection(selectedIndex);
        
        segmentDurationSpinner.post(() -> {
            isInitializingSegmentDuration = false;
        });
    }
    
    /**
     * 初始化录制摄像头选择配置
     */
    private void initRecordingCameraSelectionConfig(View view) {
        cbRecordCameraFront = view.findViewById(R.id.cb_record_camera_front);
        cbRecordCameraBack = view.findViewById(R.id.cb_record_camera_back);
        cbRecordCameraLeft = view.findViewById(R.id.cb_record_camera_left);
        cbRecordCameraRight = view.findViewById(R.id.cb_record_camera_right);
        
        if (cbRecordCameraFront == null || getContext() == null || appConfig == null) {
            return;
        }
        
        isInitializingRecordingCameraSelection = true;
        
        // 根据摄像头数量显示/隐藏对应的 CheckBox
        int cameraCount = appConfig.getCameraCount();
        
        // 前摄像头（1摄及以上都有）
        cbRecordCameraFront.setVisibility(cameraCount >= 1 ? View.VISIBLE : View.GONE);
        cbRecordCameraFront.setText(appConfig.getRecordingCameraDisplayName("front", 1));
        cbRecordCameraFront.setChecked(appConfig.isRecordingCameraEnabled("front"));
        
        // 后摄像头（2摄及以上才有）
        cbRecordCameraBack.setVisibility(cameraCount >= 2 ? View.VISIBLE : View.GONE);
        cbRecordCameraBack.setText(appConfig.getRecordingCameraDisplayName("back", 2));
        cbRecordCameraBack.setChecked(appConfig.isRecordingCameraEnabled("back"));
        
        // 左摄像头（4摄才有）
        cbRecordCameraLeft.setVisibility(cameraCount >= 4 ? View.VISIBLE : View.GONE);
        cbRecordCameraLeft.setText(appConfig.getRecordingCameraDisplayName("left", 3));
        cbRecordCameraLeft.setChecked(appConfig.isRecordingCameraEnabled("left"));
        
        // 右摄像头（4摄才有）
        cbRecordCameraRight.setVisibility(cameraCount >= 4 ? View.VISIBLE : View.GONE);
        cbRecordCameraRight.setText(appConfig.getRecordingCameraDisplayName("right", 4));
        cbRecordCameraRight.setChecked(appConfig.isRecordingCameraEnabled("right"));
        
        // 设置监听器
        android.widget.CompoundButton.OnCheckedChangeListener listener = (buttonView, isChecked) -> {
            if (isInitializingRecordingCameraSelection) {
                return;
            }
            
            // 检查是否至少有一个勾选
            if (!isChecked && !hasAtLeastOneRecordingCameraEnabled(buttonView)) {
                // 恢复勾选状态
                buttonView.setChecked(true);
                Toast.makeText(getContext(), "至少需要选择一个摄像头", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 保存设置
            String position = getPositionFromCheckBox(buttonView);
            if (position != null) {
                appConfig.setRecordingCameraEnabled(position, isChecked);
                String cameraName = ((android.widget.CheckBox) buttonView).getText().toString();
                String message = isChecked ? "已启用「" + cameraName + "」录制" : "已禁用「" + cameraName + "」录制";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        };
        
        cbRecordCameraFront.setOnCheckedChangeListener(listener);
        cbRecordCameraBack.setOnCheckedChangeListener(listener);
        cbRecordCameraLeft.setOnCheckedChangeListener(listener);
        cbRecordCameraRight.setOnCheckedChangeListener(listener);
        
        // 延迟结束初始化标记
        cbRecordCameraFront.post(() -> {
            isInitializingRecordingCameraSelection = false;
        });
    }
    
    /**
     * 更新录制摄像头选择的 UI（车型切换时调用）
     */
    private void updateRecordingCameraSelectionUI() {
        if (cbRecordCameraFront == null || getContext() == null || appConfig == null) {
            return;
        }
        
        isInitializingRecordingCameraSelection = true;
        
        // 根据摄像头数量显示/隐藏对应的 CheckBox
        int cameraCount = appConfig.getCameraCount();
        
        // 前摄像头（1摄及以上都有）
        cbRecordCameraFront.setVisibility(cameraCount >= 1 ? View.VISIBLE : View.GONE);
        cbRecordCameraFront.setText(appConfig.getRecordingCameraDisplayName("front", 1));
        cbRecordCameraFront.setChecked(appConfig.isRecordingCameraEnabled("front"));
        
        // 后摄像头（2摄及以上才有）
        cbRecordCameraBack.setVisibility(cameraCount >= 2 ? View.VISIBLE : View.GONE);
        cbRecordCameraBack.setText(appConfig.getRecordingCameraDisplayName("back", 2));
        cbRecordCameraBack.setChecked(appConfig.isRecordingCameraEnabled("back"));
        
        // 左摄像头（4摄才有）
        cbRecordCameraLeft.setVisibility(cameraCount >= 4 ? View.VISIBLE : View.GONE);
        cbRecordCameraLeft.setText(appConfig.getRecordingCameraDisplayName("left", 3));
        cbRecordCameraLeft.setChecked(appConfig.isRecordingCameraEnabled("left"));
        
        // 右摄像头（4摄才有）
        cbRecordCameraRight.setVisibility(cameraCount >= 4 ? View.VISIBLE : View.GONE);
        cbRecordCameraRight.setText(appConfig.getRecordingCameraDisplayName("right", 4));
        cbRecordCameraRight.setChecked(appConfig.isRecordingCameraEnabled("right"));
        
        // 延迟结束初始化标记
        cbRecordCameraFront.post(() -> {
            isInitializingRecordingCameraSelection = false;
        });
    }
    
    /**
     * 检查除了当前按钮外，是否还有至少一个摄像头被勾选
     */
    private boolean hasAtLeastOneRecordingCameraEnabled(View excludeButton) {
        if (cbRecordCameraFront != excludeButton && cbRecordCameraFront.getVisibility() == View.VISIBLE && cbRecordCameraFront.isChecked()) {
            return true;
        }
        if (cbRecordCameraBack != excludeButton && cbRecordCameraBack.getVisibility() == View.VISIBLE && cbRecordCameraBack.isChecked()) {
            return true;
        }
        if (cbRecordCameraLeft != excludeButton && cbRecordCameraLeft.getVisibility() == View.VISIBLE && cbRecordCameraLeft.isChecked()) {
            return true;
        }
        if (cbRecordCameraRight != excludeButton && cbRecordCameraRight.getVisibility() == View.VISIBLE && cbRecordCameraRight.isChecked()) {
            return true;
        }
        return false;
    }
    
    /**
     * 根据 CheckBox 获取对应的摄像头位置
     */
    private String getPositionFromCheckBox(View checkBox) {
        if (checkBox == cbRecordCameraFront) {
            return "front";
        } else if (checkBox == cbRecordCameraBack) {
            return "back";
        } else if (checkBox == cbRecordCameraLeft) {
            return "left";
        } else if (checkBox == cbRecordCameraRight) {
            return "right";
        }
        return null;
    }
    
    /**
     * 初始化存储位置配置
     * 注意：U盘检测涉及文件系统操作，需要异步执行避免卡顿
     */
    private void initStorageLocationConfig(View view) {
        storageLocationSpinner = view.findViewById(R.id.spinner_storage_location);
        storageLocationDescText = view.findViewById(R.id.tv_storage_location_desc);
        storageDebugButton = view.findViewById(R.id.btn_storage_debug);
        
        if (storageLocationSpinner == null || getContext() == null) {
            return;
        }
        
        isInitializingStorageLocation = true;
        lastAppliedStorageLocation = (appConfig != null) ? appConfig.getStorageLocation() : null;
        
        // 先使用默认状态初始化 UI（假设没有U盘，避免主线程阻塞）
        hasExternalSdCard = false;
        
        // 设置调试按钮点击事件（先显示，检测完后可能隐藏）
        if (storageDebugButton != null) {
            storageDebugButton.setVisibility(View.VISIBLE);
            storageDebugButton.setOnClickListener(v -> showStorageDebugInfo());
        }
        
        // 初始化 Spinner（使用默认选项，后续异步更新）
        storageLocationOptions = new String[] {"内部存储", "U盘（检测中...）"};
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                storageLocationOptions
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        storageLocationSpinner.setAdapter(adapter);
        
        storageLocationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newLocation;
                String locationName;
                
                if (position == 0) {
                    newLocation = AppConfig.STORAGE_INTERNAL;
                    locationName = "内部存储";
                } else {
                    newLocation = AppConfig.STORAGE_EXTERNAL_SD;
                    locationName = "U盘";
                    // 如果U盘不可用，显示警告但仍然允许用户选择
                    if (!hasExternalSdCard && !isInitializingStorageLocation && getContext() != null) {
                        Toast.makeText(getContext(), "当前未检测到U盘，录制将临时使用内部存储", Toast.LENGTH_LONG).show();
                    }
                }
                
                updateStorageLocationDescriptionAsync(newLocation);
                
                if (isInitializingStorageLocation) {
                    return;
                }
                
                if (newLocation.equals(lastAppliedStorageLocation)) {
                    return;
                }
                
                lastAppliedStorageLocation = newLocation;
                appConfig.setStorageLocation(newLocation);
                
                // 更新中转写入开关的可见性
                updateRelayWriteVisibility();
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "存储位置已切换为「" + locationName + "」", Toast.LENGTH_SHORT).show();
                    // 异步获取路径描述
                    new Thread(() -> {
                        String pathDesc = StorageHelper.getCurrentStoragePathDesc(getContext());
                        AppLog.d("SettingsFragment", "存储位置已切换为: " + newLocation + "，路径: " + pathDesc);
                    }).start();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        String currentLocation = appConfig.getStorageLocation();
        int selectedIndex = 0;
        // 保持用户选择的存储位置，即使U盘不可用也显示选中状态
        if (AppConfig.STORAGE_EXTERNAL_SD.equals(currentLocation)) {
            selectedIndex = 1;
        }
        storageLocationSpinner.setSelection(selectedIndex);
        
        // 显示加载中状态
        if (storageLocationDescText != null) {
            storageLocationDescText.setText("正在检测存储设备...");
        }
        
        // 异步检测 U盘并更新 UI
        final String finalCurrentLocation = currentLocation;
        final int finalSelectedIndex = selectedIndex;
        new Thread(() -> {
            // 在后台线程执行耗时的 I/O 操作
            boolean detected = StorageHelper.hasExternalSdCard(getContext());
            
            // 回到主线程更新 UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (getContext() == null || storageLocationSpinner == null) {
                        return;
                    }
                    
                    hasExternalSdCard = detected;
                    
                    // 更新调试按钮可见性
                    if (storageDebugButton != null) {
                        storageDebugButton.setVisibility(hasExternalSdCard ? View.GONE : View.VISIBLE);
                    }
                    
                    // 更新 Spinner 选项文字
                    if (hasExternalSdCard) {
                        storageLocationOptions = new String[] {"内部存储", "U盘"};
                    } else {
                        storageLocationOptions = new String[] {"内部存储", "U盘（未检测到）"};
                    }
                    
                    ArrayAdapter<String> newAdapter = new ArrayAdapter<>(
                            getContext(),
                            R.layout.spinner_item,
                            storageLocationOptions
                    );
                    newAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                    storageLocationSpinner.setAdapter(newAdapter);
                    
                    // 恢复用户选择
                    storageLocationSpinner.setSelection(finalSelectedIndex);
                    
                    // 异步更新描述文字
                    updateStorageLocationDescriptionAsync(finalCurrentLocation);
                    
                    storageLocationSpinner.post(() -> {
                        isInitializingStorageLocation = false;
                    });
                });
            }
        }).start();
        
        // 初始化中转写入开关
        initRelayWriteConfig(view);
    }
    
    /**
     * 初始化中转写入配置
     */
    private void initRelayWriteConfig(View view) {
        relayWriteSwitch = view.findViewById(R.id.switch_relay_write);
        relayWriteDescText = view.findViewById(R.id.tv_relay_write_desc);
        
        if (relayWriteSwitch == null || getContext() == null) {
            return;
        }
        
        isInitializingRelayWrite = true;
        
        // 加载当前设置
        boolean relayWriteEnabled = appConfig.isRelayWriteEnabled();
        relayWriteSwitch.setChecked(relayWriteEnabled);
        updateRelayWriteDescription(relayWriteEnabled);
        
        // 根据存储位置显示/隐藏中转写入选项
        updateRelayWriteVisibility();
        
        relayWriteSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isInitializingRelayWrite) {
                return;
            }
            
            appConfig.setRelayWriteEnabled(isChecked);
            updateRelayWriteDescription(isChecked);
            
            String message = isChecked ? 
                    "中转写入已开启：视频先写入内部存储再传输到U盘，避免录制卡顿" : 
                    "中转写入已关闭：视频直接写入U盘，可能因U盘速度慢导致录制卡顿";
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        });
        
        isInitializingRelayWrite = false;
    }
    
    /**
     * 更新中转写入开关的可见性
     * 仅在U盘存储时显示
     */
    private void updateRelayWriteVisibility() {
        if (relayWriteSwitch == null || relayWriteDescText == null) {
            return;
        }
        
        ViewGroup parent = (ViewGroup) relayWriteSwitch.getParent();
        if (parent != null) {
            boolean useExternalSd = appConfig.isUsingExternalSdCard();
            parent.setVisibility(useExternalSd ? View.VISIBLE : View.GONE);
        }
    }
    
    /**
     * 更新中转写入描述文字
     */
    private void updateRelayWriteDescription(boolean enabled) {
        if (relayWriteDescText == null) {
            return;
        }
        
        if (enabled) {
            relayWriteDescText.setText("已开启：视频先写入内部存储再传输到U盘，避免录制卡顿");
            relayWriteDescText.setTextColor(ContextCompat.getColor(getContext(), R.color.button_accent));
        } else {
            relayWriteDescText.setText("已关闭：视频直接写入U盘，可能因U盘速度慢导致录制卡顿");
            relayWriteDescText.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        }
    }
    
    /**
     * 更新存储位置描述文字（同步版本，仅在已有数据时使用）
     * @deprecated 请使用 {@link #updateStorageLocationDescriptionAsync(String)} 避免主线程阻塞
     */
    @Deprecated
    private void updateStorageLocationDescription(String location) {
        // 直接调用异步版本
        updateStorageLocationDescriptionAsync(location);
    }
    
    /**
     * 异步更新存储位置描述文字
     * 避免在主线程执行文件系统 I/O 操作导致卡顿
     */
    private void updateStorageLocationDescriptionAsync(String location) {
        if (storageLocationDescText == null || getContext() == null) {
            return;
        }
        
        // 先显示加载状态
        storageLocationDescText.setText("正在获取存储信息...");
        
        final Context context = getContext();
        final boolean useExternal = AppConfig.STORAGE_EXTERNAL_SD.equals(location);
        final boolean isFallback = useExternal && !hasExternalSdCard;
        
        new Thread(() -> {
            // 在后台线程执行耗时的 I/O 操作
            java.io.File videoDir = useExternal ? 
                    StorageHelper.getVideoDir(context, true) :
                    StorageHelper.getVideoDir(context, false);
            String path = videoDir.getAbsolutePath();
            
            // 获取内部存储根路径用于判断
            String internalRoot = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            
            // 简化路径显示
            String displayPath;
            if (path.startsWith(internalRoot + "/")) {
                // 是内部存储
                displayPath = path.replace(internalRoot + "/", "内部存储/");
            } else if (path.startsWith("/storage/emulated/")) {
                // 其他 emulated 路径也是内部存储
                displayPath = "内部存储" + path.substring(path.indexOf("/", "/storage/emulated/".length()));
            } else if (path.matches("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}/.*")) {
                // XXXX-XXXX 格式是 SD 卡
                int dcimIndex = path.indexOf("/DCIM/");
                if (dcimIndex > 0) {
                    displayPath = "U盘" + path.substring(dcimIndex);
                } else {
                    displayPath = "U盘/" + path.substring(path.lastIndexOf("/") + 1);
                }
            } else {
                // 其他路径原样显示
                displayPath = path;
            }
            
            // 获取容量信息
            long availableSpace = StorageHelper.getAvailableSpace(videoDir);
            long totalSpace = StorageHelper.getTotalSpace(videoDir);
            String availableStr = StorageHelper.formatSize(availableSpace);
            String totalStr = StorageHelper.formatSize(totalSpace);
            
            // 构建最终显示文字
            final String finalText;
            if (isFallback) {
                finalText = "⚠ U盘不可用，临时使用内部存储\n" + displayPath + "\n可用: " + availableStr + " / 共: " + totalStr;
            } else {
                finalText = displayPath + "\n可用: " + availableStr + " / 共: " + totalStr;
            }
            
            // 回到主线程更新 UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (storageLocationDescText != null) {
                        storageLocationDescText.setText(finalText);
                    }
                });
            }
        }).start();
    }
    
    /**
     * 显示存储设备调试信息
     */
    private void showStorageDebugInfo() {
        if (getContext() == null) {
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        
        // 首先检测存储权限状态
        sb.append("=== 存储权限状态 ===\n");
        
        // 检查所有文件访问权限（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean hasAllFilesAccess = android.os.Environment.isExternalStorageManager();
            sb.append("所有文件访问权限 (Android 11+): ");
            if (hasAllFilesAccess) {
                sb.append("已授权 ✓\n");
            } else {
                sb.append("未授权 ✗\n");
                sb.append("⚠️ 提示: Android 11+ 写入公共录像目录与 U 盘需要此权限！\n");
                sb.append("   请前往「13. 系统与权限」授予「所有文件访问权限」\n");
            }
        } else {
            sb.append("Android 版本低于 11，无需「所有文件访问权限」\n");
        }
        
        // 检查基础存储权限
        boolean hasStoragePermission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasStoragePermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    getContext(), android.Manifest.permission.READ_MEDIA_VIDEO) 
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            sb.append("媒体文件权限 (Android 13+): ");
        } else {
            hasStoragePermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    getContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            sb.append("存储读写权限: ");
        }
        sb.append(hasStoragePermission ? "已授权 ✓\n" : "未授权 ✗\n");
        
        // 显示当前自定义路径
        String customPath = appConfig.getCustomSdCardPath();
        sb.append("\n=== 自定义U盘路径 ===\n");
        if (customPath != null) {
            sb.append("当前设置: " + customPath + "\n");
            java.io.File customDir = new java.io.File(customPath);
            sb.append("路径状态: " + (customDir.exists() ? "存在" : "不存在") + 
                    ", " + (customDir.canWrite() ? "可写" : "不可写") + "\n");
        } else {
            sb.append("未设置（使用自动检测）\n");
        }
        
        sb.append("\n");
        
        // 然后显示存储设备检测信息
        List<String> debugInfo = StorageHelper.getStorageDebugInfo(getContext());
        for (String line : debugInfo) {
            sb.append(line).append("\n");
        }
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("存储设备检测信息")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .setNeutralButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = 
                            (android.content.ClipboardManager) getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("存储调试信息", sb.toString());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(getContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("手动设置路径", (dialog, which) -> {
                    showManualSdCardPathDialog();
                })
                .show();
    }
    
    /**
     * 显示手动设置U盘路径对话框
     */
    private void showManualSdCardPathDialog() {
        if (getContext() == null) return;
        
        android.widget.EditText input = new android.widget.EditText(getContext());
        input.setHint("例如: /storage/ABCD-1234");
        input.setSingleLine(true);
        // 适配夜间模式
        input.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        input.setBackgroundResource(R.drawable.edit_text_background);
        
        // 显示当前设置的路径
        String currentPath = appConfig.getCustomSdCardPath();
        if (currentPath != null) {
            input.setText(currentPath);
        }
        
        // 设置边距
        android.widget.FrameLayout container = new android.widget.FrameLayout(getContext());
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = 48;
        params.rightMargin = 48;
        input.setLayoutParams(params);
        container.addView(input);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("手动设置U盘路径")
                .setMessage("如果自动检测失败，你可以手动输入U盘的挂载路径。\n\n" +
                        "常见格式：/storage/XXXX-XXXX（十六进制ID）\n\n" +
                        "留空表示使用自动检测。")
                .setView(container)
                .setPositiveButton("保存", (dialog, which) -> {
                    String path = input.getText().toString().trim();
                    if (path.isEmpty()) {
                        appConfig.setCustomSdCardPath(null);
                        Toast.makeText(getContext(), "已清除自定义路径，使用自动检测", Toast.LENGTH_SHORT).show();
                    } else {
                        java.io.File testDir = new java.io.File(path);
                        if (!testDir.exists()) {
                            Toast.makeText(getContext(), "警告：路径不存在，但已保存", Toast.LENGTH_LONG).show();
                        } else if (!testDir.isDirectory()) {
                            Toast.makeText(getContext(), "警告：路径不是目录，但已保存", Toast.LENGTH_LONG).show();
                        } else if (!testDir.canWrite()) {
                            Toast.makeText(getContext(), "警告：路径不可写，但已保存", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(getContext(), "U盘路径已设置", Toast.LENGTH_SHORT).show();
                        }
                        appConfig.setCustomSdCardPath(path);
                    }
                    
                    // 重新检测并更新UI
                    hasExternalSdCard = StorageHelper.hasExternalSdCard(getContext());
                    if (storageDebugButton != null) {
                        storageDebugButton.setVisibility(hasExternalSdCard ? View.GONE : View.VISIBLE);
                    }
                    if (hasExternalSdCard && storageLocationSpinner != null) {
                        storageLocationOptions = new String[] {"内部存储", "U盘"};
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                getContext(),
                                R.layout.spinner_item,
                                storageLocationOptions
                        );
                        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                        storageLocationSpinner.setAdapter(adapter);
                    }
                    String currentLocation = appConfig.getStorageLocation();
                    updateStorageLocationDescriptionAsync(currentLocation);
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 初始化存储清理配置
     */
    private void initStorageCleanupConfig(View view) {
        videoStorageLimitEdit = view.findViewById(R.id.et_video_storage_limit);
        photoStorageLimitEdit = view.findViewById(R.id.et_photo_storage_limit);
        videoUsedSizeText = view.findViewById(R.id.tv_video_used_size);
        photoUsedSizeText = view.findViewById(R.id.tv_photo_used_size);
        
        if (videoStorageLimitEdit == null || photoStorageLimitEdit == null || getContext() == null) {
            return;
        }
        
        isInitializingStorageCleanup = true;
        
        // 加载当前设置
        int videoLimit = appConfig.getVideoStorageLimitGb();
        int photoLimit = appConfig.getPhotoStorageLimitGb();
        
        // 设置初始值（0显示为空）
        if (videoLimit > 0) {
            videoStorageLimitEdit.setText(String.valueOf(videoLimit));
        } else {
            videoStorageLimitEdit.setText("");
        }
        
        if (photoLimit > 0) {
            photoStorageLimitEdit.setText(String.valueOf(photoLimit));
        } else {
            photoStorageLimitEdit.setText("");
        }
        
        // 更新当前占用大小显示
        updateStorageUsedSizeDisplay();
        
        // 添加文本变化监听器 - 视频
        videoStorageLimitEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (isInitializingStorageCleanup) {
                    return;
                }
                
                int limit = 0;
                String text = s.toString().trim();
                if (!text.isEmpty()) {
                    try {
                        limit = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        // 忽略无效输入
                    }
                }
                
                appConfig.setVideoStorageLimitGb(limit);
                AppLog.d("SettingsFragment", "视频存储限制已设置为: " + limit + " GB");
                
                // 通知 MainActivity 重启清理任务
                notifyStorageCleanupConfigChanged();
            }
        });
        
        // 添加文本变化监听器 - 图片
        photoStorageLimitEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (isInitializingStorageCleanup) {
                    return;
                }
                
                int limit = 0;
                String text = s.toString().trim();
                if (!text.isEmpty()) {
                    try {
                        limit = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        // 忽略无效输入
                    }
                }
                
                appConfig.setPhotoStorageLimitGb(limit);
                AppLog.d("SettingsFragment", "图片存储限制已设置为: " + limit + " GB");
                
                // 通知 MainActivity 重启清理任务
                notifyStorageCleanupConfigChanged();
            }
        });
        
        // 延迟结束初始化标记
        videoStorageLimitEdit.post(() -> {
            isInitializingStorageCleanup = false;
        });
    }
    
    /**
     * 更新存储占用大小显示
     */
    private void updateStorageUsedSizeDisplay() {
        if (getContext() == null) {
            return;
        }
        
        // 在后台线程计算大小，避免阻塞UI
        new Thread(() -> {
            StorageCleanupManager cleanupManager = new StorageCleanupManager(getContext());
            long videoSize = cleanupManager.getVideoUsedSize();
            long photoSize = cleanupManager.getPhotoUsedSize();
            
            String videoSizeStr = "已用: " + StorageHelper.formatSize(videoSize);
            String photoSizeStr = "已用: " + StorageHelper.formatSize(photoSize);
            
            // 回到主线程更新UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (videoUsedSizeText != null) {
                        videoUsedSizeText.setText(videoSizeStr);
                    }
                    if (photoUsedSizeText != null) {
                        photoUsedSizeText.setText(photoSizeStr);
                    }
                });
            }
        }).start();
    }
    
    /**
     * 通知 MainActivity 存储清理配置已更改
     */
    private void notifyStorageCleanupConfigChanged() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).restartStorageCleanupTask();
        }
    }
    
    /**
     * 打开自定义摄像头配置界面
     */
    private void openCustomCameraConfig() {
        SettingsFragment.openSubPage(this, new CustomCameraConfigFragment());
    }

    /**
     * 打开分辨率设置界面
     */
    private void openResolutionSettings() {
        SettingsFragment.openSubPage(this, new ResolutionSettingsFragment());
    }

    // ==================== T05: 3.1–3.4 分区切换、方案A帧率、画面、水印与提示音 ====================

    private void initRecorderSubTabs(View root) {
        btnTabRecord31 = root.findViewById(R.id.btn_tab_record_31);
        btnTabImage32 = root.findViewById(R.id.btn_tab_image_32);
        btnTabDisplay33 = root.findViewById(R.id.btn_tab_display_33);
        btnTabSound34 = root.findViewById(R.id.btn_tab_sound_34);

        sectionRecord31 = root.findViewById(R.id.section_record_31);
        sectionImage32 = root.findViewById(R.id.section_image_32);
        sectionDisplay33 = root.findViewById(R.id.section_display_33);
        sectionSound34 = root.findViewById(R.id.section_sound_34);
        scrollRecorderSettings = root.findViewById(R.id.scroll_recorder_settings);

        if (btnTabRecord31 != null) btnTabRecord31.setOnClickListener(v -> selectRecorderSubTab(0));
        if (btnTabImage32 != null) btnTabImage32.setOnClickListener(v -> selectRecorderSubTab(1));
        if (btnTabDisplay33 != null) btnTabDisplay33.setOnClickListener(v -> selectRecorderSubTab(2));
        if (btnTabSound34 != null) btnTabSound34.setOnClickListener(v -> selectRecorderSubTab(3));

        selectRecorderSubTab(0);
    }

    private void selectRecorderSubTab(int tabIndex) {
        if (sectionRecord31 != null) sectionRecord31.setVisibility(tabIndex == 0 ? View.VISIBLE : View.GONE);
        if (sectionImage32 != null) sectionImage32.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);
        if (sectionDisplay33 != null) sectionDisplay33.setVisibility(tabIndex == 2 ? View.VISIBLE : View.GONE);
        if (sectionSound34 != null) sectionSound34.setVisibility(tabIndex == 3 ? View.VISIBLE : View.GONE);

        styleTabPill(btnTabRecord31, tabIndex == 0);
        styleTabPill(btnTabImage32, tabIndex == 1);
        styleTabPill(btnTabDisplay33, tabIndex == 2);
        styleTabPill(btnTabSound34, tabIndex == 3);

        if (scrollRecorderSettings != null) {
            scrollRecorderSettings.scrollTo(0, 0);
        }
    }

    private void styleTabPill(@Nullable Button button, boolean selected) {
        Context ctx = getContext();
        if (button == null || ctx == null) return;
        int bgColor = ContextCompat.getColor(ctx, selected ? R.color.ui_accent_soft : R.color.button_background);
        int textColor = ContextCompat.getColor(ctx, selected ? R.color.ui_option_pill_selected_text : R.color.button_text);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bgColor));
        button.setTextColor(textColor);
    }

    private void initFrameRatePolicyConfig(View view) {
        Spinner targetFpsSpinner = view.findViewById(R.id.spinner_target_recording_fps);
        Spinner previewLimitSpinner = view.findViewById(R.id.spinner_preview_fps_limit);
        Context ctx = getContext();
        if (ctx == null || appConfig == null) return;

        if (targetFpsSpinner != null) {
            ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(ctx, R.layout.spinner_item, TARGET_FPS_LABELS);
            fpsAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            targetFpsSpinner.setAdapter(fpsAdapter);

            int savedFps = appConfig.getTargetRecordingFps();
            int initialIdx = 0;
            for (int i = 0; i < TARGET_FPS_VALUES.length; i++) {
                if (TARGET_FPS_VALUES[i] == savedFps) {
                    initialIdx = i;
                    break;
                }
            }
            targetFpsSpinner.setSelection(initialIdx);
            final boolean[] init = {true};
            targetFpsSpinner.post(() -> init[0] = false);
            targetFpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                    if (init[0]) return;
                    int newFps = TARGET_FPS_VALUES[position];
                    if (newFps != appConfig.getTargetRecordingFps()) {
                        appConfig.setTargetRecordingFps(newFps);
                        Toast.makeText(ctx, "录像目标帧率已设为「" + TARGET_FPS_LABELS[position] + "」，下次录制生效", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        if (previewLimitSpinner != null) {
            ArrayAdapter<String> limitAdapter = new ArrayAdapter<>(ctx, R.layout.spinner_item, PREVIEW_LIMIT_LABELS);
            limitAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            previewLimitSpinner.setAdapter(limitAdapter);

            int savedLimit = appConfig.getPreviewFpsLimit();
            int initialIdx = 0;
            for (int i = 0; i < PREVIEW_LIMIT_VALUES.length; i++) {
                if (PREVIEW_LIMIT_VALUES[i] == savedLimit) {
                    initialIdx = i;
                    break;
                }
            }
            previewLimitSpinner.setSelection(initialIdx);
            final boolean[] init = {true};
            previewLimitSpinner.post(() -> init[0] = false);
            previewLimitSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                    if (init[0]) return;
                    int newLimit = PREVIEW_LIMIT_VALUES[position];
                    if (newLimit != appConfig.getPreviewFpsLimit()) {
                        appConfig.setPreviewFpsLimit(newLimit);
                        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
                        if (manager != null) {
                            manager.refreshPreviewFrameLimit();
                        }
                        Toast.makeText(ctx, "预览限帧已设为「" + PREVIEW_LIMIT_LABELS[position] + "」", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
    }

    private void initImageAdjustSection(View view) {
        Context ctx = getContext();
        if (ctx == null || appConfig == null) return;

        switchImageAdjustEnabled = view.findViewById(R.id.switch_image_adjust_enabled);
        layoutImageGlobalAdjust = view.findViewById(R.id.layout_image_global_adjust);
        seekImageQuality = view.findViewById(R.id.seekbar_image_quality);
        seekImageBrightness = view.findViewById(R.id.seekbar_image_brightness);
        seekImageContrast = view.findViewById(R.id.seekbar_image_contrast);
        seekImageSaturation = view.findViewById(R.id.seekbar_image_saturation);
        seekImageCrop = view.findViewById(R.id.seekbar_image_crop);

        tvImageQualityValue = view.findViewById(R.id.tv_image_quality_value);
        tvImageBrightnessValue = view.findViewById(R.id.tv_image_brightness_value);
        tvImageContrastValue = view.findViewById(R.id.tv_image_contrast_value);
        tvImageSaturationValue = view.findViewById(R.id.tv_image_saturation_value);
        tvImageCropValue = view.findViewById(R.id.tv_image_crop_value);

        Spinner cameraPosSpinner = view.findViewById(R.id.spinner_image_camera_pos);
        switchImageMirror = view.findViewById(R.id.switch_image_mirror);
        Button btnResetImage = view.findViewById(R.id.btn_reset_image_adjust);

        if (cameraPosSpinner != null) {
            ArrayAdapter<String> posAdapter = new ArrayAdapter<>(ctx, R.layout.spinner_item, CAMERA_POS_LABELS);
            posAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            cameraPosSpinner.setAdapter(posAdapter);
            cameraPosSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                    selectedImageCameraPos = CAMERA_POS_KEYS[position];
                    refreshPerCameraImageUi();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        if (switchImageAdjustEnabled != null) {
            switchImageAdjustEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isUpdatingImageUi) return;
                appConfig.setVideoImageAdjustEnabled(isChecked);
                refreshImageAdjustSectionUi();
                notifyActiveRecorders();
                Toast.makeText(ctx,
                        isChecked ? "画面调节已启用（实时单遍处理，零 FBO 拷贝）"
                                : "画面调节已关闭，已切回零开销直通旁路",
                        Toast.LENGTH_SHORT).show();
            });
        }

        if (seekImageQuality != null) {
            seekImageQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int val = ImageAdjustmentConfig.MIN_QUALITY_PERCENT + progress;
                    if (tvImageQualityValue != null) tvImageQualityValue.setText(formatPercent(val));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    appConfig.setVideoImageQualityPercent(ImageAdjustmentConfig.MIN_QUALITY_PERCENT + seekBar.getProgress());
                    Toast.makeText(ctx, "录像画质缩放将在下次/下一段录制生效", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (seekImageBrightness != null) {
            seekImageBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int val = ImageAdjustmentConfig.MIN_BRIGHTNESS_PERCENT + progress;
                    if (tvImageBrightnessValue != null) tvImageBrightnessValue.setText(formatPercent(val));
                    if (fromUser) {
                        appConfig.setVideoImageBrightnessPercent(val);
                        notifyActiveRecorders();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    appConfig.setVideoImageBrightnessPercent(ImageAdjustmentConfig.MIN_BRIGHTNESS_PERCENT + seekBar.getProgress());
                    notifyActiveRecorders();
                }
            });
        }

        if (seekImageContrast != null) {
            seekImageContrast.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int val = ImageAdjustmentConfig.MIN_CONTRAST_PERCENT + progress;
                    if (tvImageContrastValue != null) tvImageContrastValue.setText(formatPercent(val));
                    if (fromUser) {
                        appConfig.setVideoImageContrastPercent(val);
                        notifyActiveRecorders();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    appConfig.setVideoImageContrastPercent(ImageAdjustmentConfig.MIN_CONTRAST_PERCENT + seekBar.getProgress());
                    notifyActiveRecorders();
                }
            });
        }

        if (seekImageSaturation != null) {
            seekImageSaturation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (tvImageSaturationValue != null) tvImageSaturationValue.setText(formatPercent(progress));
                    if (fromUser) {
                        appConfig.setVideoImageSaturationPercent(progress);
                        notifyActiveRecorders();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    appConfig.setVideoImageSaturationPercent(seekBar.getProgress());
                    notifyActiveRecorders();
                }
            });
        }

        if (seekImageCrop != null) {
            seekImageCrop.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (tvImageCropValue != null) tvImageCropValue.setText(formatPercent(progress));
                    if (fromUser) {
                        appConfig.setVideoImageCropPercent(selectedImageCameraPos, progress);
                        notifyActiveRecorders();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    appConfig.setVideoImageCropPercent(selectedImageCameraPos, seekBar.getProgress());
                    notifyActiveRecorders();
                }
            });
        }

        if (switchImageMirror != null) {
            switchImageMirror.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isUpdatingImageUi) return;
                appConfig.setPhysicalMirrorH(selectedImageCameraPos, isChecked);
                notifyAllMirrorAndRecorderConsumers();
            });
        }

        if (btnResetImage != null) {
            btnResetImage.setOnClickListener(v -> {
                appConfig.resetVideoImageAdjustments();
                refreshImageAdjustSectionUi();
                notifyActiveRecorders();
                Toast.makeText(ctx, "已恢复默认画质与色彩参数", Toast.LENGTH_SHORT).show();
            });
        }

        refreshImageAdjustSectionUi();
    }

    private void refreshImageAdjustSectionUi() {
        if (appConfig == null) return;
        isUpdatingImageUi = true;
        try {
            boolean enabled = appConfig.isVideoImageAdjustEnabled();
            if (switchImageAdjustEnabled != null) switchImageAdjustEnabled.setChecked(enabled);
            int q = appConfig.getVideoImageQualityPercent();
            int b = appConfig.getVideoImageBrightnessPercent();
            int c = appConfig.getVideoImageContrastPercent();
            int s = appConfig.getVideoImageSaturationPercent();

            if (seekImageQuality != null) {
                seekImageQuality.setProgress(q - ImageAdjustmentConfig.MIN_QUALITY_PERCENT);
                seekImageQuality.setEnabled(enabled);
            }
            if (tvImageQualityValue != null) tvImageQualityValue.setText(formatPercent(q));

            if (seekImageBrightness != null) {
                seekImageBrightness.setProgress(b - ImageAdjustmentConfig.MIN_BRIGHTNESS_PERCENT);
                seekImageBrightness.setEnabled(enabled);
            }
            if (tvImageBrightnessValue != null) tvImageBrightnessValue.setText(formatPercent(b));

            if (seekImageContrast != null) {
                seekImageContrast.setProgress(c - ImageAdjustmentConfig.MIN_CONTRAST_PERCENT);
                seekImageContrast.setEnabled(enabled);
            }
            if (tvImageContrastValue != null) tvImageContrastValue.setText(formatPercent(c));

            if (seekImageSaturation != null) {
                seekImageSaturation.setProgress(s);
                seekImageSaturation.setEnabled(enabled);
            }
            if (tvImageSaturationValue != null) tvImageSaturationValue.setText(formatPercent(s));

            if (layoutImageGlobalAdjust != null) {
                layoutImageGlobalAdjust.setAlpha(enabled ? 1.0f : 0.55f);
            }
            refreshPerCameraImageUi();
        } finally {
            isUpdatingImageUi = false;
        }
    }

    private void refreshPerCameraImageUi() {
        if (appConfig == null) return;
        boolean prevFlag = isUpdatingImageUi;
        isUpdatingImageUi = true;
        try {
            boolean adjustEnabled = appConfig.isVideoImageAdjustEnabled();
            int crop = appConfig.getVideoImageCropPercent(selectedImageCameraPos);
            if (seekImageCrop != null) {
                seekImageCrop.setProgress(crop);
                seekImageCrop.setEnabled(adjustEnabled);
            }
            if (tvImageCropValue != null) tvImageCropValue.setText(formatPercent(crop));
            if (switchImageMirror != null) {
                switchImageMirror.setChecked(appConfig.isPhysicalMirrorH(selectedImageCameraPos));
            }
        } finally {
            isUpdatingImageUi = prevFlag;
        }
    }

    private void initDisplayWatermarkExtensions(View view) {
        Context ctx = getContext();
        if (ctx == null || appConfig == null) return;

        switchWatermarkCustomText = view.findViewById(R.id.switch_watermark_custom_text);
        etWatermarkCustomText = view.findViewById(R.id.et_watermark_custom_text);
        switchWatermarkSpeed = view.findViewById(R.id.switch_watermark_speed);
        switchWatermarkGear = view.findViewById(R.id.switch_watermark_gear);
        switchWatermarkTurnSignal = view.findViewById(R.id.switch_watermark_turn_signal);
        Spinner posSpinner = view.findViewById(R.id.spinner_watermark_position);
        Spinner styleSpinner = view.findViewById(R.id.spinner_watermark_style);

        isUpdatingDisplayUi = true;
        try {
            if (switchWatermarkCustomText != null) {
                switchWatermarkCustomText.setChecked(appConfig.isWatermarkCustomTextEnabled());
                switchWatermarkCustomText.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isUpdatingDisplayUi) return;
                    appConfig.setWatermarkCustomTextEnabled(isChecked);
                    if (etWatermarkCustomText != null) {
                        etWatermarkCustomText.setEnabled(isChecked);
                    }
                    notifyActiveRecorders();
                });
            }
            if (etWatermarkCustomText != null) {
                etWatermarkCustomText.setText(appConfig.getWatermarkCustomText());
                etWatermarkCustomText.setEnabled(appConfig.isWatermarkCustomTextEnabled());
                etWatermarkCustomText.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override public void afterTextChanged(Editable s) {
                        if (isUpdatingDisplayUi) return;
                        appConfig.setWatermarkCustomText(s != null ? s.toString() : "");
                        notifyActiveRecorders();
                    }
                });
            }
            if (switchWatermarkSpeed != null) {
                switchWatermarkSpeed.setChecked(appConfig.isWatermarkSpeedEnabled());
                switchWatermarkSpeed.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isUpdatingDisplayUi) return;
                    appConfig.setWatermarkSpeedEnabled(isChecked);
                    notifyActiveRecorders();
                    Toast.makeText(ctx, isChecked ? "车速水印已开启，未收到信号时显示 --" : "车速水印已关闭", Toast.LENGTH_SHORT).show();
                });
            }
            if (switchWatermarkGear != null) {
                switchWatermarkGear.setChecked(appConfig.isWatermarkGearEnabled());
                switchWatermarkGear.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isUpdatingDisplayUi) return;
                    appConfig.setWatermarkGearEnabled(isChecked);
                    notifyActiveRecorders();
                    Toast.makeText(ctx, isChecked ? "档位水印已开启，未收到信号时显示未知" : "档位水印已关闭", Toast.LENGTH_SHORT).show();
                });
            }
            if (switchWatermarkTurnSignal != null) {
                switchWatermarkTurnSignal.setChecked(appConfig.isWatermarkTurnSignalEnabled());
                switchWatermarkTurnSignal.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isUpdatingDisplayUi) return;
                    appConfig.setWatermarkTurnSignalEnabled(isChecked);
                    notifyActiveRecorders();
                    Toast.makeText(ctx, isChecked ? "转向灯水印已开启，未收到信号时显示未知" : "转向灯水印已关闭", Toast.LENGTH_SHORT).show();
                });
            }

            if (posSpinner != null) {
                final String[] posKeys = {"BOTTOM_RIGHT", "BOTTOM_LEFT", "TOP_RIGHT", "TOP_LEFT"};
                final String[] posLabels = {"右下角", "左下角", "右上角", "左上角"};
                ArrayAdapter<String> posAdapter = new ArrayAdapter<>(ctx, R.layout.spinner_item, posLabels);
                posAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                posSpinner.setAdapter(posAdapter);
                String savedPos = appConfig.getWatermarkPosition();
                int posIdx = 0;
                for (int i = 0; i < posKeys.length; i++) {
                    if (posKeys[i].equals(savedPos)) {
                        posIdx = i;
                        break;
                    }
                }
                posSpinner.setSelection(posIdx);
                final boolean[] init = {true};
                posSpinner.post(() -> init[0] = false);
                posSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                        if (init[0]) return;
                        appConfig.setWatermarkPosition(posKeys[position]);
                        notifyActiveRecorders();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });
            }

            if (styleSpinner != null) {
                final String[] styleKeys = {"CARD", "TEXT"};
                final String[] styleLabels = {"半透明圆角卡片", "纯文字阴影"};
                ArrayAdapter<String> styleAdapter = new ArrayAdapter<>(ctx, R.layout.spinner_item, styleLabels);
                styleAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                styleSpinner.setAdapter(styleAdapter);
                String savedStyle = appConfig.getWatermarkStyle();
                int styleIdx = "TEXT".equals(savedStyle) ? 1 : 0;
                styleSpinner.setSelection(styleIdx);
                final boolean[] init = {true};
                styleSpinner.post(() -> init[0] = false);
                styleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                        if (init[0]) return;
                        appConfig.setWatermarkStyle(styleKeys[position]);
                        notifyActiveRecorders();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });
            }
        } finally {
            isUpdatingDisplayUi = false;
        }
    }

    private void initSoundSection(View view) {
        Context ctx = getContext();
        if (ctx == null || appConfig == null) return;

        spinnerStartSoundMode = view.findViewById(R.id.spinner_start_sound_mode);
        spinnerStopSoundMode = view.findViewById(R.id.spinner_stop_sound_mode);
        tvStartSoundStatus = view.findViewById(R.id.tv_start_sound_status);
        tvStopSoundStatus = view.findViewById(R.id.tv_stop_sound_status);
        btnPickStartSound = view.findViewById(R.id.btn_pick_start_sound);
        btnPickStopSound = view.findViewById(R.id.btn_pick_stop_sound);
        Button btnTestStart = view.findViewById(R.id.btn_test_start_sound);
        Button btnTestStop = view.findViewById(R.id.btn_test_stop_sound);

        bindSoundModeSpinner(spinnerStartSoundMode, true);
        bindSoundModeSpinner(spinnerStopSoundMode, false);

        if (btnPickStartSound != null) {
            btnPickStartSound.setOnClickListener(v -> showPickSoundDialog(true));
        }
        if (btnPickStopSound != null) {
            btnPickStopSound.setOnClickListener(v -> showPickSoundDialog(false));
        }
        if (btnTestStart != null) {
            btnTestStart.setOnClickListener(v -> {
                if (RecordingSoundPlayer.MODE_NONE.equals(appConfig.getRecordStartSoundMode())) {
                    Toast.makeText(ctx, "请先选择系统提示音或自定义音频", Toast.LENGTH_SHORT).show();
                    return;
                }
                RecordingSoundPlayer.playStartSound(ctx, appConfig);
            });
        }
        if (btnTestStop != null) {
            btnTestStop.setOnClickListener(v -> {
                if (RecordingSoundPlayer.MODE_NONE.equals(appConfig.getRecordStopSoundMode())) {
                    Toast.makeText(ctx, "请先选择系统提示音或自定义音频", Toast.LENGTH_SHORT).show();
                    return;
                }
                RecordingSoundPlayer.playStopSound(ctx, appConfig);
            });
        }

        refreshSoundSectionUi();
    }

    private void bindSoundModeSpinner(@Nullable Spinner spinner, boolean isStartSound) {
        Context ctx = getContext();
        if (spinner == null || ctx == null) return;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx, R.layout.spinner_item, SOUND_MODE_LABELS);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                if (isUpdatingSoundUi) return;
                String newMode = SOUND_MODE_KEYS[position];
                if (isStartSound) {
                    appConfig.setRecordStartSoundMode(newMode);
                } else {
                    appConfig.setRecordStopSoundMode(newMode);
                }
                refreshSoundSectionUi();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void refreshSoundSectionUi() {
        if (appConfig == null) return;
        isUpdatingSoundUi = true;
        try {
            String startMode = appConfig.getRecordStartSoundMode();
            String stopMode = appConfig.getRecordStopSoundMode();
            if (spinnerStartSoundMode != null) spinnerStartSoundMode.setSelection(soundModeIndex(startMode));
            if (spinnerStopSoundMode != null) spinnerStopSoundMode.setSelection(soundModeIndex(stopMode));
            if (tvStartSoundStatus != null) {
                tvStartSoundStatus.setText(formatSoundStatus(startMode, appConfig.getRecordStartSoundPath()));
            }
            if (tvStopSoundStatus != null) {
                tvStopSoundStatus.setText(formatSoundStatus(stopMode, appConfig.getRecordStopSoundPath()));
            }
            if (btnPickStartSound != null) {
                btnPickStartSound.setEnabled(RecordingSoundPlayer.MODE_CUSTOM.equals(startMode));
            }
            if (btnPickStopSound != null) {
                btnPickStopSound.setEnabled(RecordingSoundPlayer.MODE_CUSTOM.equals(stopMode));
            }
        } finally {
            isUpdatingSoundUi = false;
        }
    }

    private int soundModeIndex(String mode) {
        for (int i = 0; i < SOUND_MODE_KEYS.length; i++) {
            if (SOUND_MODE_KEYS[i].equals(mode)) return i;
        }
        return 0;
    }

    private String formatSoundStatus(String mode, String path) {
        if (RecordingSoundPlayer.MODE_SYSTEM.equals(mode)) {
            return "当前：系统提示音";
        }
        if (RecordingSoundPlayer.MODE_CUSTOM.equals(mode)) {
            if (path == null || path.isEmpty()) {
                return "当前：自定义音频（未选择文件，将回退系统提示音）";
            }
            File file = new File(path);
            if (!file.exists()) {
                return "当前：文件已失效（" + file.getName() + "）";
            }
            return "当前：自定义音频 · " + file.getName();
        }
        return "当前：不播放";
    }

    private void showPickSoundDialog(boolean isStartSound) {
        Context ctx = getContext();
        if (ctx == null || appConfig == null) return;
        List<File> files = RecordingSoundPlayer.listDownloadAudioFiles();
        if (files.isEmpty()) {
            pendingPickForStartSound = isStartSound;
            new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(isStartSound ? "选择开始录制音频" : "选择结束录制音频")
                    .setMessage("Download 目录未找到 20MB 以内的音频文件（mp3 / wav / ogg / m4a / aac / flac），是否打开系统文件选择器？")
                    .setPositiveButton("打开文件选择器", (d, w) -> audioPickerLauncher.launch(new String[]{"audio/*"}))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        String[] labels = new String[files.size() + 1];
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            long kb = Math.max(1L, f.length() / 1024L);
            labels[i] = f.getName() + " (" + kb + " KB)";
        }
        labels[files.size()] = "从系统文件选择器浏览...";

        new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(isStartSound ? "选择开始录制音频（<=20MB）" : "选择结束录制音频（<=20MB）")
                .setItems(labels, (dialog, which) -> {
                    if (which == files.size()) {
                        pendingPickForStartSound = isStartSound;
                        audioPickerLauncher.launch(new String[]{"audio/*"});
                        return;
                    }
                    File chosen = files.get(which);
                    try {
                        File imported = RecordingSoundPlayer.importCustomSoundFile(ctx, chosen, isStartSound);
                        if (isStartSound) {
                            appConfig.setRecordStartSoundPath(imported.getAbsolutePath());
                            appConfig.setRecordStartSoundMode(RecordingSoundPlayer.MODE_CUSTOM);
                        } else {
                            appConfig.setRecordStopSoundPath(imported.getAbsolutePath());
                            appConfig.setRecordStopSoundMode(RecordingSoundPlayer.MODE_CUSTOM);
                        }
                        refreshSoundSectionUi();
                        Toast.makeText(ctx, "已导入音频：" + imported.getName(), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(ctx, "导入音频失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void notifyActiveRecorders() {
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager != null) {
            manager.refreshActiveRecorderSettings();
        }
    }

    private void notifyAllMirrorAndRecorderConsumers() {
        if (getContext() != null) {
            BlindSpotService.update(requireContext());
        }
        MainActivity main = MainActivity.getInstance();
        if (main != null) {
            main.refreshMainPreviewTransforms();
        }
        MjpegStreamManager streamManager = MjpegStreamManager.getInstance();
        if (streamManager != null) {
            streamManager.applyParams();
        }
        notifyActiveRecorders();
    }

    // ==================== T06/包D: 3.1 保摄像头恢复 / 前进档（D 档）自动弹出 / 车辆触发规则 ====================

    private static final int[] RD_POPUP_SECONDS_VALUES = {3, 5, 10, 15, 30};
    private static final String[] RD_POPUP_SECONDS_LABELS = {"3 秒", "5 秒（默认）", "10 秒", "15 秒", "30 秒"};

    private LinearLayout rulesListContainer;

    private void initVehicleAutomationSection(@NonNull View root) {
        Context ctx = getContext();
        if (ctx == null || appConfig == null) return;

        SwitchMaterial keepCameraSwitch = root.findViewById(R.id.switch_keep_camera_connected);
        SwitchMaterial cameraRecoverySwitch = root.findViewById(R.id.switch_camera_recovery_enabled);
        SwitchMaterial rdPopupSwitch = root.findViewById(R.id.switch_rd_shift_auto_popup);
        LinearLayout rdSecondsLayout = root.findViewById(R.id.layout_rd_popup_seconds);
        Spinner rdSecondsSpinner = root.findViewById(R.id.spinner_rd_popup_seconds);
        rulesListContainer = root.findViewById(R.id.container_vehicle_rules);

        if (keepCameraSwitch != null) {
            keepCameraSwitch.setChecked(appConfig.isKeepCameraConnectedEnabled());
            keepCameraSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                appConfig.setKeepCameraConnectedEnabled(isChecked);
                syncAutomationAfterSettingsChange(ctx);
                Toast.makeText(ctx, isChecked ? "已开启后台保持摄像头连接" : "已关闭后台保持摄像头连接", Toast.LENGTH_SHORT).show();
            });
        }

        if (cameraRecoverySwitch != null) {
            cameraRecoverySwitch.setChecked(appConfig.isCameraRecoveryEnabled());
            cameraRecoverySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                appConfig.setCameraRecoveryEnabled(isChecked);
                syncAutomationAfterSettingsChange(ctx);
                Toast.makeText(ctx, isChecked ? "已开启摄像头掉线有门槛前台恢复" : "已关闭摄像头掉线前台拉起恢复", Toast.LENGTH_SHORT).show();
            });
        }

        if (rdPopupSwitch != null) {
            boolean enabled = appConfig.isRdShiftAutoPopupEnabled();
            rdPopupSwitch.setChecked(enabled);
            if (rdSecondsLayout != null) {
                rdSecondsLayout.setVisibility(enabled ? View.VISIBLE : View.GONE);
            }
            rdPopupSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                appConfig.setRdShiftAutoPopupEnabled(isChecked);
                if (rdSecondsLayout != null) {
                    rdSecondsLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
                syncAutomationAfterSettingsChange(ctx);
                Toast.makeText(ctx, isChecked ? "已开启前进档（D 档）自动弹出记录仪" : "已关闭前进档（D 档）自动弹出", Toast.LENGTH_SHORT).show();
            });
        }

        if (rdSecondsSpinner != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx, R.layout.spinner_item, RD_POPUP_SECONDS_LABELS);
            adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
            rdSecondsSpinner.setAdapter(adapter);
            int savedSeconds = appConfig.getRdShiftPopupSeconds();
            int initialIdx = 1;
            for (int i = 0; i < RD_POPUP_SECONDS_VALUES.length; i++) {
                if (RD_POPUP_SECONDS_VALUES[i] == savedSeconds) {
                    initialIdx = i;
                    break;
                }
            }
            rdSecondsSpinner.setSelection(initialIdx);
            final boolean[] init = {true};
            rdSecondsSpinner.post(() -> init[0] = false);
            rdSecondsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (init[0]) return;
                    int nextSeconds = RD_POPUP_SECONDS_VALUES[position];
                    if (nextSeconds != appConfig.getRdShiftPopupSeconds()) {
                        appConfig.setRdShiftPopupSeconds(nextSeconds);
                        syncAutomationAfterSettingsChange(ctx);
                        Toast.makeText(ctx, "前进档（D 档）弹出自动退后台时间已设为 " + nextSeconds + " 秒", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        Button btnAddRule = root.findViewById(R.id.btn_add_vehicle_rule);
        if (btnAddRule != null) {
            btnAddRule.setOnClickListener(v -> showVehicleRuleEditorDialog(null));
        }

        Button btnResetDefault = root.findViewById(R.id.btn_reset_default_rules);
        if (btnResetDefault != null) {
            btnResetDefault.setOnClickListener(v -> {
                appConfig.resetVehicleTriggerRulesToDefault();
                refreshVehicleRulesList();
                syncAutomationAfterSettingsChange(ctx);
                Toast.makeText(ctx, "已恢复 SC 默认 2 条触发规则", Toast.LENGTH_SHORT).show();
            });
        }

        Button btnOpenPreset = root.findViewById(R.id.btn_open_signal_preset);
        if (btnOpenPreset != null) {
            btnOpenPreset.setOnClickListener(v ->
                    SettingsFragment.navigateToCategory(this, SettingsCatalog.ID_SIGNAL_PRESET));
        }

        refreshVehicleRulesList();
    }

    private void syncAutomationAfterSettingsChange(@NonNull Context ctx) {
        com.kooo.evcam.lifecycle.BackgroundCapabilityController.syncBackgroundKeepAlive(ctx);
        VehicleAutomationCoordinator.getInstance().startOrReload(ctx);
        if (com.kooo.evcam.lifecycle.BackgroundCapabilityController.hasActiveBackgroundCapabilities(ctx)
                && com.kooo.evcam.lifecycle.AppLifecycleState.canRunBackgroundServices(ctx)) {
            CameraForegroundService.start(ctx, "EVCam", "车辆信号与录制保护运行中");
        }
    }

    private void refreshVehicleRulesList() {
        Context ctx = getContext();
        if (ctx == null || rulesListContainer == null || appConfig == null) return;
        rulesListContainer.removeAllViews();

        List<VehicleTriggerRule> rules = appConfig.getVehicleTriggerRules();
        if (rules.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("当前暂无触发规则，点击「+ 添加规则」或「恢复默认 2 条」添加。");
            empty.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
            empty.setTextSize(14f);
            rulesListContainer.addView(empty);
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);
        int gap = (int) (10 * density);

        for (int i = 0; i < rules.size(); i++) {
            final int index = i;
            final VehicleTriggerRule rule = rules.get(i);

            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.ui_card);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < rules.size() - 1) {
                cardLp.bottomMargin = gap;
            }
            card.setLayoutParams(cardLp);

            LinearLayout headerRow = new LinearLayout(ctx);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView title = new TextView(ctx);
            title.setText(rule.getDisplayName());
            title.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
            title.setTextSize(16f);
            title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            headerRow.addView(title);

            SwitchMaterial enableSwitch = new SwitchMaterial(ctx);
            enableSwitch.setChecked(rule.enabled);
            enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                List<VehicleTriggerRule> updated = new ArrayList<>(appConfig.getVehicleTriggerRules());
                if (index < updated.size()) {
                    updated.set(index, rule.withEnabled(isChecked));
                    appConfig.setVehicleTriggerRules(updated);
                    syncAutomationAfterSettingsChange(ctx);
                }
            });
            headerRow.addView(enableSwitch);
            card.addView(headerRow);

            TextView detail = new TextView(ctx);
            detail.setText("条件：" + rule.getConditionsSummary() + "\n动作：" + rule.action.label);
            detail.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
            detail.setTextSize(13.5f);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            detailLp.topMargin = (int) (4 * density);
            detail.setLayoutParams(detailLp);
            card.addView(detail);

            LinearLayout actionRow = new LinearLayout(ctx);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionLp.topMargin = (int) (8 * density);
            actionRow.setLayoutParams(actionLp);

            int btnBg = ContextCompat.getColor(ctx, R.color.button_background);
            int btnText = ContextCompat.getColor(ctx, R.color.button_text);

            Button btnEdit = new Button(ctx);
            btnEdit.setText("编辑");
            btnEdit.setAllCaps(false);
            btnEdit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(btnBg));
            btnEdit.setTextColor(btnText);
            btnEdit.setOnClickListener(v -> showVehicleRuleEditorDialog(rule));
            actionRow.addView(btnEdit);

            Button btnDelete = new Button(ctx);
            btnDelete.setText("删除");
            btnDelete.setAllCaps(false);
            btnDelete.setBackgroundTintList(android.content.res.ColorStateList.valueOf(btnBg));
            btnDelete.setTextColor(btnText);
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            delLp.setMarginStart((int) (8 * density));
            btnDelete.setLayoutParams(delLp);
            btnDelete.setOnClickListener(v -> {
                List<VehicleTriggerRule> updated = new ArrayList<>(appConfig.getVehicleTriggerRules());
                if (index < updated.size()) {
                    updated.remove(index);
                    appConfig.setVehicleTriggerRules(updated);
                    refreshVehicleRulesList();
                    syncAutomationAfterSettingsChange(ctx);
                }
            });
            actionRow.addView(btnDelete);

            card.addView(actionRow);
            rulesListContainer.addView(card);
        }
    }

    private void showVehicleRuleEditorDialog(@Nullable VehicleTriggerRule existing) {
        Context ctx = getContext();
        if (ctx == null) return;
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        LinearLayout form = new LinearLayout(ctx);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);
        scroll.addView(form);

        EditText etName = addRuleField(ctx, form, "规则名称（选填）", existing != null ? existing.name : "", android.text.InputType.TYPE_CLASS_TEXT);

        VehicleRuleSignal[] signals = VehicleRuleSignal.values();
        RuleComparison[] comps = RuleComparison.values();
        RuleAction[] actions = RuleAction.values();
        ConditionLogic[] logics = ConditionLogic.values();

        String[] signalLabels = new String[signals.length];
        for (int i = 0; i < signals.length; i++) signalLabels[i] = signals[i].label;
        String[] compLabels = new String[comps.length];
        for (int i = 0; i < comps.length; i++) compLabels[i] = comps[i].symbol + " (" + comps[i].label + ")";
        String[] actionLabels = new String[actions.length];
        for (int i = 0; i < actions.length; i++) actionLabels[i] = actions[i].label;
        String[] logicLabels = new String[logics.length];
        for (int i = 0; i < logics.length; i++) logicLabels[i] = logics[i].label;

        Spinner spSignal1 = addRuleSpinner(ctx, form, "主条件信号", signalLabels);
        TextView tvHint1 = new TextView(ctx);
        tvHint1.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        tvHint1.setTextSize(12.5f);
        form.addView(tvHint1);
        Spinner spComp1 = addRuleSpinner(ctx, form, "主条件运算符", compLabels);
        EditText etVal1 = addRuleField(ctx, form, "主条件目标值（整数）",
                existing != null ? String.valueOf(existing.primaryCondition.expectedValue) : "4",
                android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);

        if (existing != null) {
            spSignal1.setSelection(existing.primaryCondition.signal.ordinal());
            spComp1.setSelection(existing.primaryCondition.comparison.ordinal());
        }
        tvHint1.setText("取值说明：" + signals[spSignal1.getSelectedItemPosition()].valueHint);
        spSignal1.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                tvHint1.setText("取值说明：" + signals[position].valueHint);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        android.widget.CheckBox cbSecond = new android.widget.CheckBox(ctx);
        cbSecond.setText("启用第二个组合条件");
        cbSecond.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        boolean hasSecond = existing != null && !existing.additionalConditions.isEmpty();
        cbSecond.setChecked(hasSecond);
        form.addView(cbSecond);

        LinearLayout secondContainer = new LinearLayout(ctx);
        secondContainer.setOrientation(LinearLayout.VERTICAL);
        secondContainer.setVisibility(hasSecond ? View.VISIBLE : View.GONE);
        form.addView(secondContainer);

        Spinner spLogic = addRuleSpinner(ctx, secondContainer, "组合逻辑", logicLabels);
        Spinner spSignal2 = addRuleSpinner(ctx, secondContainer, "第二条件信号", signalLabels);
        TextView tvHint2 = new TextView(ctx);
        tvHint2.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        tvHint2.setTextSize(12.5f);
        secondContainer.addView(tvHint2);
        Spinner spComp2 = addRuleSpinner(ctx, secondContainer, "第二条件运算符", compLabels);
        EditText etVal2 = addRuleField(ctx, secondContainer, "第二条件目标值（整数）",
                hasSecond ? String.valueOf(existing.additionalConditions.get(0).expectedValue) : "0",
                android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);

        if (existing != null) {
            spLogic.setSelection(existing.conditionLogic.ordinal());
        }
        if (hasSecond) {
            VehicleRuleCondition second = existing.additionalConditions.get(0);
            spSignal2.setSelection(second.signal.ordinal());
            spComp2.setSelection(second.comparison.ordinal());
        }
        tvHint2.setText("取值说明：" + signals[spSignal2.getSelectedItemPosition()].valueHint);
        spSignal2.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                tvHint2.setText("取值说明：" + signals[position].valueHint);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        cbSecond.setOnCheckedChangeListener((buttonView, isChecked) ->
                secondContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE));

        EditText etDelay = addRuleField(ctx, form, "持续满足延迟秒数（0 表示立即触发）",
                existing != null ? String.valueOf(existing.delaySeconds) : "0",
                android.text.InputType.TYPE_CLASS_NUMBER);
        Spinner spAction = addRuleSpinner(ctx, form, "触发动作", actionLabels);
        if (existing != null) {
            spAction.setSelection(existing.action.ordinal());
        }

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(existing == null ? "添加车辆触发规则" : "编辑车辆触发规则")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();

        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            int val1;
            int val2 = 0;
            int delay;
            try {
                val1 = Integer.parseInt(etVal1.getText().toString().trim());
                if (cbSecond.isChecked()) {
                    val2 = Integer.parseInt(etVal2.getText().toString().trim());
                }
                delay = Math.max(0, Integer.parseInt(etDelay.getText().toString().trim()));
            } catch (NumberFormatException e) {
                Toast.makeText(ctx, "目标值与延迟秒数必须为有效整数", Toast.LENGTH_SHORT).show();
                return;
            }

            VehicleRuleCondition primary = new VehicleRuleCondition(
                    signals[spSignal1.getSelectedItemPosition()],
                    comps[spComp1.getSelectedItemPosition()],
                    val1
            );
            List<VehicleRuleCondition> extras = new ArrayList<>();
            if (cbSecond.isChecked()) {
                extras.add(new VehicleRuleCondition(
                        signals[spSignal2.getSelectedItemPosition()],
                        comps[spComp2.getSelectedItemPosition()],
                        val2
                ));
            }
            VehicleTriggerRule nextRule = new VehicleTriggerRule(
                    existing != null ? existing.id : VehicleTriggerRule.generateId(),
                    etName.getText().toString().trim(),
                    existing == null || existing.enabled,
                    primary,
                    extras,
                    logics[spLogic.getSelectedItemPosition()],
                    delay,
                    actions[spAction.getSelectedItemPosition()]
            );

            List<VehicleTriggerRule> all = new ArrayList<>(appConfig.getVehicleTriggerRules());
            if (existing == null) {
                all.add(nextRule);
            } else {
                boolean replaced = false;
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).id.equals(existing.id)) {
                        all.set(i, nextRule);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) all.add(nextRule);
            }
            appConfig.setVehicleTriggerRules(all);
            refreshVehicleRulesList();
            syncAutomationAfterSettingsChange(ctx);
            dialog.dismiss();
        });
    }

    private EditText addRuleField(@NonNull Context ctx, @NonNull LinearLayout parent,
                                  @NonNull String label, @NonNull String initialValue, int inputType) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        tv.setTextSize(13f);
        parent.addView(tv);

        EditText et = new EditText(ctx);
        et.setInputType(inputType);
        et.setText(initialValue);
        et.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        parent.addView(et);
        return et;
    }

    private Spinner addRuleSpinner(@NonNull Context ctx, @NonNull LinearLayout parent,
                                   @NonNull String label, @NonNull String[] items) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        tv.setTextSize(13f);
        parent.addView(tv);

        Spinner spinner = new Spinner(ctx);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx, R.layout.spinner_item, items);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);
        parent.addView(spinner);
        return spinner;
    }

    private String formatPercent(int value) {
        return java.util.Locale.getDefault() != null
                ? String.format(java.util.Locale.getDefault(), "%d%%", value)
                : value + "%";
    }
}
