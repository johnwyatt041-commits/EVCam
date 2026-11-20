package com.kooo.evcam.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.SettingsFragment;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.surround.SurroundCalibrationManager;
import com.kooo.evcam.surround.SurroundCalibrationPreviewView;
import com.kooo.evcam.surround.SurroundCalibrationProfile;
import com.kooo.evcam.surround.SurroundCarModelRepository;
import com.kooo.evcam.surround.SurroundExtrinsicAdjustment;
import com.kooo.evcam.surround.SurroundExtrinsicAdjustment.CameraOffset;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;

/**
 * 4.3 标定与地面视野实时预览调校子页：
 * - 左侧按需启动 360 实时预览（默认鸟瞰大图，支持切换完整模式 A），右侧调整校准方案、地面范围、引导线与车模后下一帧生效；
 * - 退出本页或退后台时自动销毁 GL 预览并精确解绑四路 OES Surface，闲置相机自动关闭，不影响正在进行的录像。
 */
public class SurroundCalibrationLiveFragment extends Fragment implements UiThemeController.ThemeAware {

    private AppConfig appConfig;
    private boolean bindingUi;

    private SurroundCalibrationPreviewView previewView;
    private Button btnViewBirdOnly;
    private Button btnViewFullModeA;
    private Button btnDone;

    private TextView tvCalibrationStatus;
    private Button btnCalibBuiltin;
    private Button btnCalibImportLocal;

    private String selectedExtrinsicCam = "front";
    private boolean advancedExtrinsicsExpanded = false;
    private boolean assistShowGrid = true;
    private boolean assistHideCarModel = false;
    private boolean assistSoloCamera = false;
    private TextView tvExtrinsicSummary;
    private TextView tvExtrinsicCamTip;
    private Button btnAssistGrid;
    private Button btnAssistHideCar;
    private Button btnAssistSoloCam;
    private Button btnExtCamFront;
    private Button btnExtCamBack;
    private Button btnExtCamLeft;
    private Button btnExtCamRight;
    private TextView tvExtScaleValue;
    private SeekBar seekExtScale;
    private TextView tvExtPitchValue;
    private SeekBar seekExtPitch;
    private TextView tvExtZValue;
    private SeekBar seekExtZ;
    private TextView tvExtYValue;
    private SeekBar seekExtY;
    private Button btnExtToggleAdvanced;
    private View layoutExtAdvanced;
    private TextView tvExtYawValue;
    private SeekBar seekExtYaw;
    private TextView tvExtRollValue;
    private SeekBar seekExtRoll;
    private TextView tvExtXValue;
    private SeekBar seekExtX;
    private TextView tvExtCxValue;
    private SeekBar seekExtCx;
    private TextView tvExtCyValue;
    private SeekBar seekExtCy;
    private TextView tvExtK1Value;
    private SeekBar seekExtK1;
    private Button btnExtResetCamera;
    private Button btnExtResetAll;
    private Button btnCalibExportJson;

    private TextView tvGroundWidthValue;
    private SeekBar seekGroundWidth;
    private TextView tvGroundLengthValue;
    private SeekBar seekGroundLength;
    private TextView tvGroundExtValue;
    private SeekBar seekGroundExt;
    private Button btnResetGround;

    private SwitchMaterial switchGuidanceEnabled;
    private TextView tvCarModelStatus;
    private Button btnCarModelBuiltin;
    private Button btnCarModelImportPng;

    private final ActivityResultLauncher<String[]> calibrationJsonPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                Context ctx = getContext();
                if (uri == null || ctx == null) return;
                File internalDir = new File(ctx.getFilesDir(), "surround");
                SurroundCalibrationManager.ImportResult result =
                        SurroundCalibrationManager.importCalibrationUri(ctx, uri, internalDir);
                applyCalibrationImportResult(ctx, result);
            });

    private final ActivityResultLauncher<android.net.Uri> calibrationFolderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                Context ctx = getContext();
                if (uri == null || ctx == null) return;
                File internalDir = new File(ctx.getFilesDir(), "surround");
                SurroundCalibrationManager.ImportResult result =
                        SurroundCalibrationManager.importCalibrationTreeUri(ctx, uri, internalDir);
                applyCalibrationImportResult(ctx, result);
            });

    private final ActivityResultLauncher<String[]> carModelPngPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                Context ctx = getContext();
                if (uri == null || ctx == null) return;
                File internalDir = new File(ctx.getFilesDir(), "surround");
                SurroundCarModelRepository.ImportResult result =
                        SurroundCarModelRepository.importCustomCarModelPng(ctx, uri, internalDir);
                applyCarModelImportResult(ctx, result);
            });

    private final ActivityResultLauncher<String> calibrationExportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                Context ctx = getContext();
                if (uri == null || ctx == null) return;
                SurroundCalibrationProfile activeProfile = SurroundCalibrationManager.resolveActiveProfile(appConfig);
                SurroundCalibrationManager.ExportResult result =
                        SurroundCalibrationManager.exportCalibrationToUri(ctx, uri, activeProfile);
                showExportResult(ctx, result);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_surround_calibration_live, container, false);
        appConfig = new AppConfig(inflater.getContext());
        bindViews(view);
        wireListeners(view);
        refreshAllUi(inflater.getContext());
        SettingsFragment.configureSubPageToolbar(this, view);
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        startLivePreview();
    }

    @Override
    public void onStop() {
        stopLivePreview();
        notifyHostSurroundChanged();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        stopLivePreview();
        super.onDestroyView();
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        if (themedContext == null || getView() == null) return;
        refreshAllUi(themedContext);
    }

    private void bindViews(@NonNull View view) {
        previewView = view.findViewById(R.id.surround_calibration_preview);
        btnViewBirdOnly = view.findViewById(R.id.btn_calib_view_bird_only);
        btnViewFullModeA = view.findViewById(R.id.btn_calib_view_full_mode_a);
        btnDone = view.findViewById(R.id.btn_calib_done);

        tvCalibrationStatus = view.findViewById(R.id.tv_live_calibration_status);
        btnCalibBuiltin = view.findViewById(R.id.btn_live_calib_builtin);
        btnCalibImportLocal = view.findViewById(R.id.btn_live_calib_import_local);

        tvExtrinsicSummary = view.findViewById(R.id.tv_live_extrinsic_summary);
        tvExtrinsicCamTip = view.findViewById(R.id.tv_live_ext_cam_tip);
        btnAssistGrid = view.findViewById(R.id.btn_live_assist_grid);
        btnAssistHideCar = view.findViewById(R.id.btn_live_assist_hide_car);
        btnAssistSoloCam = view.findViewById(R.id.btn_live_assist_solo_cam);
        btnExtCamFront = view.findViewById(R.id.btn_live_ext_cam_front);
        btnExtCamBack = view.findViewById(R.id.btn_live_ext_cam_back);
        btnExtCamLeft = view.findViewById(R.id.btn_live_ext_cam_left);
        btnExtCamRight = view.findViewById(R.id.btn_live_ext_cam_right);
        tvExtScaleValue = view.findViewById(R.id.tv_live_ext_scale_value);
        seekExtScale = view.findViewById(R.id.seekbar_live_ext_scale);
        tvExtPitchValue = view.findViewById(R.id.tv_live_ext_pitch_value);
        seekExtPitch = view.findViewById(R.id.seekbar_live_ext_pitch);
        tvExtZValue = view.findViewById(R.id.tv_live_ext_z_value);
        seekExtZ = view.findViewById(R.id.seekbar_live_ext_z);
        tvExtYValue = view.findViewById(R.id.tv_live_ext_y_value);
        seekExtY = view.findViewById(R.id.seekbar_live_ext_y);
        btnExtToggleAdvanced = view.findViewById(R.id.btn_live_ext_toggle_advanced);
        layoutExtAdvanced = view.findViewById(R.id.layout_live_ext_advanced);
        tvExtYawValue = view.findViewById(R.id.tv_live_ext_yaw_value);
        seekExtYaw = view.findViewById(R.id.seekbar_live_ext_yaw);
        tvExtRollValue = view.findViewById(R.id.tv_live_ext_roll_value);
        seekExtRoll = view.findViewById(R.id.seekbar_live_ext_roll);
        tvExtXValue = view.findViewById(R.id.tv_live_ext_x_value);
        seekExtX = view.findViewById(R.id.seekbar_live_ext_x);
        tvExtCxValue = view.findViewById(R.id.tv_live_ext_cx_value);
        seekExtCx = view.findViewById(R.id.seekbar_live_ext_cx);
        tvExtCyValue = view.findViewById(R.id.tv_live_ext_cy_value);
        seekExtCy = view.findViewById(R.id.seekbar_live_ext_cy);
        tvExtK1Value = view.findViewById(R.id.tv_live_ext_k1_value);
        seekExtK1 = view.findViewById(R.id.seekbar_live_ext_k1);
        btnExtResetCamera = view.findViewById(R.id.btn_live_ext_reset_camera);
        btnExtResetAll = view.findViewById(R.id.btn_live_ext_reset_all);
        btnCalibExportJson = view.findViewById(R.id.btn_live_calib_export_json);

        tvGroundWidthValue = view.findViewById(R.id.tv_live_ground_width_value);
        seekGroundWidth = view.findViewById(R.id.seekbar_live_ground_width);
        tvGroundLengthValue = view.findViewById(R.id.tv_live_ground_length_value);
        seekGroundLength = view.findViewById(R.id.seekbar_live_ground_length);
        tvGroundExtValue = view.findViewById(R.id.tv_live_ground_ext_value);
        seekGroundExt = view.findViewById(R.id.seekbar_live_ground_ext);
        btnResetGround = view.findViewById(R.id.btn_live_ground_reset_default);

        switchGuidanceEnabled = view.findViewById(R.id.switch_live_guidance_enabled);
        tvCarModelStatus = view.findViewById(R.id.tv_live_car_model_status);
        btnCarModelBuiltin = view.findViewById(R.id.btn_live_car_model_builtin);
        btnCarModelImportPng = view.findViewById(R.id.btn_live_car_model_import_png);
    }

    private void wireListeners(@NonNull View root) {
        View btnBack = root.findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> SettingsFragment.popSubPage(this));
        }
        if (btnDone != null) {
            btnDone.setOnClickListener(v -> SettingsFragment.popSubPage(this));
        }
        if (btnViewBirdOnly != null) {
            btnViewBirdOnly.setOnClickListener(v -> setPreviewBirdOnly(true));
        }
        if (btnViewFullModeA != null) {
            btnViewFullModeA.setOnClickListener(v -> setPreviewBirdOnly(false));
        }

        btnCalibBuiltin.setOnClickListener(v -> {
            appConfig.setSurroundCalibrationSource(AppConfig.SURROUND_CALIBRATION_E5_BUILTIN);
            refreshPreviewAndUi(requireContext(), true);
        });
        btnCalibImportLocal.setOnClickListener(v -> SurroundAssetImportHelper.showLocalCalibrationPicker(
                this, calibrationJsonPickerLauncher, calibrationFolderPickerLauncher,
                result -> applyCalibrationImportResult(requireContext(), result)));

        wireExtrinsicControls(root);

        wireGroundDimensionControl(
                root,
                R.id.btn_live_ground_width_minus,
                R.id.btn_live_ground_width_plus,
                seekGroundWidth,
                AppConfig.MIN_SURROUND_GROUND_WIDTH_CM,
                5,
                () -> appConfig.getSurroundGroundWidthCm(),
                value -> appConfig.setSurroundGroundWidthCm(value));

        wireGroundDimensionControl(
                root,
                R.id.btn_live_ground_length_minus,
                R.id.btn_live_ground_length_plus,
                seekGroundLength,
                AppConfig.MIN_SURROUND_GROUND_LENGTH_CM,
                10,
                () -> appConfig.getSurroundGroundLengthCm(),
                value -> appConfig.setSurroundGroundLengthCm(value));

        wireGroundDimensionControl(
                root,
                R.id.btn_live_ground_ext_minus,
                R.id.btn_live_ground_ext_plus,
                seekGroundExt,
                AppConfig.MIN_SURROUND_GROUND_FRONT_EXT_CM,
                10,
                () -> appConfig.getSurroundGroundFrontExtensionCm(),
                value -> appConfig.setSurroundGroundFrontExtensionCm(value));

        if (btnResetGround != null) {
            btnResetGround.setOnClickListener(v -> {
                appConfig.resetSurroundGroundDimensions();
                refreshGroundControls(false);
                if (previewView != null) {
                    previewView.refreshFromPreferences(false);
                }
                Toast.makeText(getContext(), "已恢复银河 E5 默认拼接视野参数", Toast.LENGTH_SHORT).show();
            });
        }

        switchGuidanceEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingUi) return;
            appConfig.setPreviewGuidanceEnabled(isChecked);
            if (previewView != null) {
                previewView.refreshFromPreferences(false);
            }
        });

        btnCarModelBuiltin.setOnClickListener(v -> {
            appConfig.setSurroundCarModelSource(AppConfig.SURROUND_CAR_MODEL_E5_BUILTIN);
            refreshPreviewAndUi(requireContext(), true);
        });
        btnCarModelImportPng.setOnClickListener(v -> SurroundAssetImportHelper.showLocalCarModelPicker(
                this, carModelPngPickerLauncher, result -> applyCarModelImportResult(requireContext(), result)));
    }

    private void wireGroundDimensionControl(@NonNull View root,
                                            int minusId,
                                            int plusId,
                                            @Nullable SeekBar seekBar,
                                            int minValue,
                                            int step,
                                            @NonNull java.util.function.IntSupplier getter,
                                            @NonNull IntConsumer setter) {
        View minus = root.findViewById(minusId);
        View plus = root.findViewById(plusId);
        if (minus != null) {
            minus.setOnClickListener(v -> {
                setter.accept(getter.getAsInt() - step);
                refreshGroundControls(false);
                if (previewView != null) {
                    previewView.refreshFromPreferences(false);
                }
            });
        }
        if (plus != null) {
            plus.setOnClickListener(v -> {
                setter.accept(getter.getAsInt() + step);
                refreshGroundControls(false);
                if (previewView != null) {
                    previewView.refreshFromPreferences(false);
                }
            });
        }
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser || bindingUi) return;
                    setter.accept(minValue + progress);
                    refreshGroundControls(true);
                    if (previewView != null) {
                        previewView.refreshFromPreferences(false);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                }
            });
        }
    }

    private void setPreviewBirdOnly(boolean birdOnly) {
        if (previewView != null) {
            previewView.setBirdOnlyMode(birdOnly);
        }
        if (getContext() != null) {
            updatePreviewModeButtons(getContext());
        }
    }

    private void startLivePreview() {
        if (previewView == null || getContext() == null) return;
        boolean hasCameraPerm = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!hasCameraPerm) {
            previewView.startPreview(null, "尚未授予相机权限，暂时无法显示实时 360 画面");
            return;
        }
        MultiCameraManager manager = null;
        if (getActivity() instanceof SurroundLivePreviewHost) {
            manager = ((SurroundLivePreviewHost) getActivity()).acquireCameraManagerForSurroundCalibration();
        }
        previewView.startPreview(manager, "相机尚未就绪，暂时无法显示实时 360 画面");
        syncAssistOptionsToPreview();
    }

    private void stopLivePreview() {
        if (previewView == null) return;
        boolean immediate = getActivity() != null && (getActivity().isFinishing() || getActivity().isDestroyed());
        previewView.stopPreview(immediate);
    }

    private void refreshPreviewAndUi(@NonNull Context ctx, boolean forceReloadAssets) {
        refreshAllUi(ctx);
        if (previewView != null) {
            previewView.refreshFromPreferences(forceReloadAssets);
        }
    }

    private void refreshAllUi(@NonNull Context ctx) {
        bindingUi = true;
        try {
            updatePreviewModeButtons(ctx);
            UiOptionPillHelper.apply(btnDone, ctx, true, true);
            UiOptionPillHelper.apply(btnResetGround, ctx, false);

            SurroundCalibrationProfile activeProfile = SurroundCalibrationManager.resolveActiveProfile(appConfig);
            boolean customCalib = AppConfig.SURROUND_CALIBRATION_E5_CUSTOM.equals(appConfig.getSurroundCalibrationSource())
                    && !appConfig.getSurroundCustomCalibrationPath().isEmpty()
                    && activeProfile.isCustomCalibration;
            String tunedSuffix = activeProfile.hasExtrinsicAdjustments ? " · 已微调" : "";
            tvCalibrationStatus.setText((customCalib
                    ? "当前方案：" + activeProfile.profileName + "（本地校准"
                    : "当前方案：银河 E5 内置校准") + (customCalib ? "）" : "") + tunedSuffix);
            UiOptionPillHelper.apply(btnCalibBuiltin, ctx, !customCalib);
            UiOptionPillHelper.apply(btnCalibImportLocal, ctx, customCalib);

            refreshExtrinsicUi(ctx, false);
            refreshGroundControls(false);
            switchGuidanceEnabled.setChecked(appConfig.isPreviewGuidanceEnabled());

            boolean customCar = AppConfig.SURROUND_CAR_MODEL_CUSTOM_PNG.equals(appConfig.getSurroundCarModelSource())
                    && !appConfig.getSurroundCustomCarModelPath().isEmpty();
            tvCarModelStatus.setText(customCar
                    ? "当前车模：本地导入 PNG 车模"
                    : "当前车模：银河 E5 内置车模");
            UiOptionPillHelper.apply(btnCarModelBuiltin, ctx, !customCar);
            UiOptionPillHelper.apply(btnCarModelImportPng, ctx, customCar);
        } finally {
            bindingUi = false;
        }
    }

    private void updatePreviewModeButtons(@NonNull Context ctx) {
        boolean birdOnly = previewView == null || previewView.isBirdOnlyMode();
        UiOptionPillHelper.apply(btnViewBirdOnly, ctx, birdOnly);
        UiOptionPillHelper.apply(btnViewFullModeA, ctx, !birdOnly);
    }

    private void refreshGroundControls(boolean skipSeekBars) {
        int width = appConfig.getSurroundGroundWidthCm();
        int length = appConfig.getSurroundGroundLengthCm();
        int ext = appConfig.getSurroundGroundFrontExtensionCm();

        tvGroundWidthValue.setText("地面宽度：" + width + " cm（160–260）");
        tvGroundLengthValue.setText("地面长度：" + length + " cm（420–600）");
        tvGroundExtValue.setText("车头视野延伸：" + ext + " cm（0–100）");

        if (skipSeekBars) return;
        boolean prevBinding = bindingUi;
        bindingUi = true;
        try {
            if (seekGroundWidth != null) {
                seekGroundWidth.setProgress(width - AppConfig.MIN_SURROUND_GROUND_WIDTH_CM);
            }
            if (seekGroundLength != null) {
                seekGroundLength.setProgress(length - AppConfig.MIN_SURROUND_GROUND_LENGTH_CM);
            }
            if (seekGroundExt != null) {
                seekGroundExt.setProgress(ext - AppConfig.MIN_SURROUND_GROUND_FRONT_EXT_CM);
            }
        } finally {
            bindingUi = prevBinding;
        }
    }

    private void applyCalibrationImportResult(@NonNull Context ctx,
                                              @NonNull SurroundCalibrationManager.ImportResult result) {
        SurroundAssetImportHelper.applyCalibrationImportResult(ctx, appConfig, result, () -> {
            refreshPreviewAndUi(ctx, true);
        });
    }

    private void applyCarModelImportResult(@NonNull Context ctx,
                                           @NonNull SurroundCarModelRepository.ImportResult result) {
        SurroundAssetImportHelper.applyCarModelImportResult(ctx, appConfig, result, () -> {
            refreshPreviewAndUi(ctx, true);
        });
    }

    private void notifyHostSurroundChanged() {
        if (getActivity() instanceof PreviewModeController.Host) {
            ((PreviewModeController.Host) getActivity()).onPreviewShortcutPreferencesChanged();
        }
    }

    private void wireExtrinsicControls(@NonNull View root) {
        if (btnAssistGrid != null) {
            btnAssistGrid.setOnClickListener(v -> {
                assistShowGrid = !assistShowGrid;
                syncAssistOptionsToPreview();
                if (getContext() != null) refreshExtrinsicUi(getContext(), true);
            });
        }
        if (btnAssistHideCar != null) {
            btnAssistHideCar.setOnClickListener(v -> {
                assistHideCarModel = !assistHideCarModel;
                syncAssistOptionsToPreview();
                if (getContext() != null) refreshExtrinsicUi(getContext(), true);
            });
        }
        if (btnAssistSoloCam != null) {
            btnAssistSoloCam.setOnClickListener(v -> {
                assistSoloCamera = !assistSoloCamera;
                syncAssistOptionsToPreview();
                if (getContext() != null) refreshExtrinsicUi(getContext(), true);
            });
        }

        if (btnExtCamFront != null) btnExtCamFront.setOnClickListener(v -> selectExtrinsicCamera("front"));
        if (btnExtCamBack != null) btnExtCamBack.setOnClickListener(v -> selectExtrinsicCamera("back"));
        if (btnExtCamLeft != null) btnExtCamLeft.setOnClickListener(v -> selectExtrinsicCamera("left"));
        if (btnExtCamRight != null) btnExtCamRight.setOnClickListener(v -> selectExtrinsicCamera("right"));

        wireFloatExtrinsicControl(
                root,
                R.id.btn_live_ext_scale_minus,
                R.id.btn_live_ext_scale_plus,
                seekExtScale,
                SurroundExtrinsicAdjustment.MIN_SCALE,
                0.01f,
                0.01f,
                off -> off.scale,
                CameraOffset::withScale);
        wireFloatExtrinsicControl(
                root,
                R.id.btn_live_ext_pitch_minus,
                R.id.btn_live_ext_pitch_plus,
                seekExtPitch,
                SurroundExtrinsicAdjustment.MIN_PITCH_DEG,
                0.5f,
                0.5f,
                off -> off.pitchDeg,
                CameraOffset::withPitchDeg);
        wireIntExtrinsicControl(
                root,
                R.id.btn_live_ext_z_minus,
                R.id.btn_live_ext_z_plus,
                seekExtZ,
                SurroundExtrinsicAdjustment.MIN_POS_Z_CM,
                2,
                off -> off.posZCm,
                CameraOffset::withPosZCm);
        wireIntExtrinsicControl(
                root,
                R.id.btn_live_ext_y_minus,
                R.id.btn_live_ext_y_plus,
                seekExtY,
                SurroundExtrinsicAdjustment.MIN_POS_Y_CM,
                2,
                off -> off.posYCm,
                CameraOffset::withPosYCm);
        wireIntExtrinsicControl(
                root,
                R.id.btn_live_ext_x_minus,
                R.id.btn_live_ext_x_plus,
                seekExtX,
                SurroundExtrinsicAdjustment.MIN_POS_X_CM,
                2,
                off -> off.posXCm,
                CameraOffset::withPosXCm);

        wireFloatExtrinsicControl(
                root,
                R.id.btn_live_ext_yaw_minus,
                R.id.btn_live_ext_yaw_plus,
                seekExtYaw,
                SurroundExtrinsicAdjustment.MIN_YAW_DEG,
                0.5f,
                0.5f,
                off -> off.yawDeg,
                CameraOffset::withYawDeg);
        wireFloatExtrinsicControl(
                root,
                R.id.btn_live_ext_roll_minus,
                R.id.btn_live_ext_roll_plus,
                seekExtRoll,
                SurroundExtrinsicAdjustment.MIN_ROLL_DEG,
                0.5f,
                0.5f,
                off -> off.rollDeg,
                CameraOffset::withRollDeg);
        wireIntExtrinsicControl(
                root,
                R.id.btn_live_ext_cx_minus,
                R.id.btn_live_ext_cx_plus,
                seekExtCx,
                SurroundExtrinsicAdjustment.MIN_CX_OFFSET_PX,
                2,
                off -> off.cxOffsetPx,
                CameraOffset::withCxOffsetPx);
        wireIntExtrinsicControl(
                root,
                R.id.btn_live_ext_cy_minus,
                R.id.btn_live_ext_cy_plus,
                seekExtCy,
                SurroundExtrinsicAdjustment.MIN_CY_OFFSET_PX,
                2,
                off -> off.cyOffsetPx,
                CameraOffset::withCyOffsetPx);
        wireFloatExtrinsicControl(
                root,
                R.id.btn_live_ext_k1_minus,
                R.id.btn_live_ext_k1_plus,
                seekExtK1,
                SurroundExtrinsicAdjustment.MIN_K1_DELTA,
                0.005f,
                0.005f,
                off -> off.k1Delta,
                CameraOffset::withK1Delta);

        if (btnExtToggleAdvanced != null) {
            btnExtToggleAdvanced.setOnClickListener(v -> {
                advancedExtrinsicsExpanded = !advancedExtrinsicsExpanded;
                if (getContext() != null) {
                    refreshExtrinsicUi(getContext(), false);
                }
            });
        }
        if (btnExtResetCamera != null) {
            btnExtResetCamera.setOnClickListener(v -> {
                Context ctx = getContext();
                if (ctx == null) return;
                String slot = resolveEffectiveCalibrationSlot();
                SurroundExtrinsicAdjustment next = appConfig.getSurroundExtrinsicAdjustmentForSlot(slot)
                        .resetCamera(selectedExtrinsicCam);
                appConfig.setSurroundExtrinsicAdjustmentForSlot(slot, next);
                refreshAllUi(ctx);
                if (previewView != null) {
                    previewView.refreshFromPreferences(false);
                }
                Toast.makeText(ctx, "已重置" + cameraLabel(selectedExtrinsicCam) + "微调参数", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnExtResetAll != null) {
            btnExtResetAll.setOnClickListener(v -> {
                Context ctx = getContext();
                if (ctx == null) return;
                String slot = resolveEffectiveCalibrationSlot();
                appConfig.resetSurroundExtrinsicAdjustmentForSlot(slot);
                refreshAllUi(ctx);
                if (previewView != null) {
                    previewView.refreshFromPreferences(false);
                }
                Toast.makeText(ctx, "已清空当前方案全部外参微调，恢复原始标定", Toast.LENGTH_SHORT).show();
            });
        }
        if (btnCalibExportJson != null) {
            btnCalibExportJson.setOnClickListener(v -> triggerCalibrationExport());
        }
    }

    private interface FloatOffsetUpdater {
        CameraOffset update(CameraOffset current, float nextVal);
    }

    private interface IntOffsetUpdater {
        CameraOffset update(CameraOffset current, int nextVal);
    }

    private void wireFloatExtrinsicControl(@NonNull View root,
                                           int minusId,
                                           int plusId,
                                           @Nullable SeekBar seekBar,
                                           float minVal,
                                           float buttonStep,
                                           float barStep,
                                           @NonNull Function<CameraOffset, Float> getter,
                                           @NonNull FloatOffsetUpdater updater) {
        View minus = root.findViewById(minusId);
        View plus = root.findViewById(plusId);
        if (minus != null) {
            minus.setOnClickListener(v -> applyExtrinsicStep(off -> updater.update(off, getter.apply(off) - buttonStep)));
        }
        if (plus != null) {
            plus.setOnClickListener(v -> applyExtrinsicStep(off -> updater.update(off, getter.apply(off) + buttonStep)));
        }
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser || bindingUi) return;
                    float nextVal = minVal + progress * barStep;
                    applyExtrinsicDrag(off -> updater.update(off, nextVal));
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {}

                @Override
                public void onStopTrackingTouch(SeekBar bar) {}
            });
        }
    }

    private void wireIntExtrinsicControl(@NonNull View root,
                                         int minusId,
                                         int plusId,
                                         @Nullable SeekBar seekBar,
                                         int minCm,
                                         int stepCm,
                                         @NonNull Function<CameraOffset, Integer> getter,
                                         @NonNull IntOffsetUpdater updater) {
        View minus = root.findViewById(minusId);
        View plus = root.findViewById(plusId);
        if (minus != null) {
            minus.setOnClickListener(v -> applyExtrinsicStep(off -> updater.update(off, getter.apply(off) - stepCm)));
        }
        if (plus != null) {
            plus.setOnClickListener(v -> applyExtrinsicStep(off -> updater.update(off, getter.apply(off) + stepCm)));
        }
        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser || bindingUi) return;
                    int nextCm = minCm + progress;
                    applyExtrinsicDrag(off -> updater.update(off, nextCm));
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {}

                @Override
                public void onStopTrackingTouch(SeekBar bar) {}
            });
        }
    }

    private void applyExtrinsicStep(@NonNull UnaryOperator<CameraOffset> mutator) {
        Context ctx = getContext();
        if (ctx == null) return;
        mutateActiveCameraOffset(mutator);
        refreshExtrinsicUi(ctx, false);
        if (previewView != null) {
            previewView.refreshFromPreferences(false);
        }
    }

    private void applyExtrinsicDrag(@NonNull UnaryOperator<CameraOffset> mutator) {
        Context ctx = getContext();
        if (ctx == null) return;
        mutateActiveCameraOffset(mutator);
        refreshExtrinsicUi(ctx, true);
        if (previewView != null) {
            previewView.refreshFromPreferences(false);
        }
    }

    private void mutateActiveCameraOffset(@NonNull UnaryOperator<CameraOffset> mutator) {
        String slot = resolveEffectiveCalibrationSlot();
        SurroundExtrinsicAdjustment current = appConfig.getSurroundExtrinsicAdjustmentForSlot(slot);
        CameraOffset nextOffset = mutator.apply(current.getCamera(selectedExtrinsicCam));
        appConfig.setSurroundExtrinsicAdjustmentForSlot(slot, current.withCamera(selectedExtrinsicCam, nextOffset));
    }

    private void selectExtrinsicCamera(@NonNull String camKey) {
        this.selectedExtrinsicCam = SurroundExtrinsicAdjustment.normalizeCameraKey(camKey);
        if (assistSoloCamera) {
            syncAssistOptionsToPreview();
        }
        if (getContext() != null) {
            refreshExtrinsicUi(getContext(), false);
        }
    }

    private void syncAssistOptionsToPreview() {
        if (previewView == null) return;
        int soloIdx = assistSoloCamera ? cameraKeyToIndex(selectedExtrinsicCam) : -1;
        previewView.setCalibrationAssistOptions(assistShowGrid, assistHideCarModel, soloIdx);
    }

    private static int cameraKeyToIndex(@NonNull String camKey) {
        if ("back".equals(camKey)) return com.kooo.evcam.surround.SurroundMeshBuilder.CAM_BACK;
        if ("left".equals(camKey)) return com.kooo.evcam.surround.SurroundMeshBuilder.CAM_LEFT;
        if ("right".equals(camKey)) return com.kooo.evcam.surround.SurroundMeshBuilder.CAM_RIGHT;
        return com.kooo.evcam.surround.SurroundMeshBuilder.CAM_FRONT;
    }

    @NonNull
    private String resolveEffectiveCalibrationSlot() {
        SurroundCalibrationProfile base = SurroundCalibrationManager.resolveActiveBaseProfile(appConfig);
        return base.isCustomCalibration
                ? AppConfig.SURROUND_CALIBRATION_E5_CUSTOM
                : AppConfig.SURROUND_CALIBRATION_E5_BUILTIN;
    }

    private void refreshExtrinsicUi(@NonNull Context ctx, boolean skipSeekBars) {
        String slot = resolveEffectiveCalibrationSlot();
        SurroundExtrinsicAdjustment adj = appConfig.getSurroundExtrinsicAdjustmentForSlot(slot);
        CameraOffset offset = adj.getCamera(selectedExtrinsicCam);

        if (tvExtrinsicSummary != null) {
            String slotTitle = AppConfig.SURROUND_CALIBRATION_E5_CUSTOM.equals(slot) ? "本地导入方案" : "内置方案（星舰7）";
            int tunedCount = adj.countNonZeroCameras();
            tvExtrinsicSummary.setText(tunedCount > 0
                    ? String.format(Locale.US, "当前编辑槽：%s（已微调 %d 路镜头；切换方案自动保留）", slotTitle, tunedCount)
                    : String.format(Locale.US, "当前编辑槽：%s（未微调，使用原始网格；切换方案独立记忆）", slotTitle));
        }

        UiOptionPillHelper.apply(btnAssistGrid, ctx, assistShowGrid);
        UiOptionPillHelper.apply(btnAssistHideCar, ctx, assistHideCarModel);
        UiOptionPillHelper.apply(btnAssistSoloCam, ctx, assistSoloCamera);

        updateCameraTabPill(btnExtCamFront, ctx, "front", "前摄", adj);
        updateCameraTabPill(btnExtCamBack, ctx, "back", "后摄", adj);
        updateCameraTabPill(btnExtCamLeft, ctx, "left", "左摄", adj);
        updateCameraTabPill(btnExtCamRight, ctx, "right", "右摄", adj);

        if (tvExtrinsicCamTip != null) {
            if ("left".equals(selectedExtrinsicCam) || "right".equals(selectedExtrinsicCam)) {
                tvExtrinsicCamTip.setText("侧摄调法：先开「参考网格」，用滚转/偏航对齐竖线，再调画面缩放和 ΔX 对齐 250cm 车位宽。");
            } else {
                tvExtrinsicCamTip.setText("前后摄调法：先调俯仰压低远景，再用「画面缩放」把车头/车尾宽窄拉到与两侧车位线接上。");
            }
        }

        if (tvExtScaleValue != null) {
            int scalePct = Math.round(offset.scale * 100.0f);
            tvExtScaleValue.setText(String.format(Locale.US,
                    "画面缩放：%d%%（+放大撑宽 / -缩小收紧）", scalePct));
        }
        if (tvExtPitchValue != null) {
            tvExtPitchValue.setText(String.format(Locale.US,
                    "俯仰角 ΔPitch：%+.1f°（正=压低远景 / 负=抬高）", offset.pitchDeg));
        }
        if (tvExtZValue != null) {
            tvExtZValue.setText(String.format(Locale.US,
                    "安装高度 ΔZ：%d cm（负=更低 / 正=更高）", offset.posZCm));
        }
        if (tvExtYValue != null) {
            tvExtYValue.setText(String.format(Locale.US,
                    "前后偏移 ΔY：%d cm（正=靠前 / 负=靠后）", offset.posYCm));
        }
        if (tvExtXValue != null) {
            tvExtXValue.setText(String.format(Locale.US,
                    "左右偏移 ΔX：%d cm（负=靠左 / 正=靠右）", offset.posXCm));
        }
        if (tvExtYawValue != null) {
            tvExtYawValue.setText(String.format(Locale.US,
                    "偏航角 ΔYaw：%+.1f°（负=向左 / 正=向右）", offset.yawDeg));
        }
        if (tvExtRollValue != null) {
            tvExtRollValue.setText(String.format(Locale.US,
                    "滚转角 ΔRoll：%+.1f°（负=逆时针 / 正=顺时针）", offset.rollDeg));
        }
        if (tvExtCxValue != null) {
            tvExtCxValue.setText(String.format(Locale.US,
                    "光心水平 Δcx：%d px（负=左移 / 正=右移）", offset.cxOffsetPx));
        }
        if (tvExtCyValue != null) {
            tvExtCyValue.setText(String.format(Locale.US,
                    "光心垂直 Δcy：%d px（负=上移 / 正=下移）", offset.cyOffsetPx));
        }
        if (tvExtK1Value != null) {
            tvExtK1Value.setText(String.format(Locale.US,
                    "鱼眼畸变 Δk1：%+.3f（调边角直线弯曲度）", offset.k1Delta));
        }

        if (layoutExtAdvanced != null) {
            layoutExtAdvanced.setVisibility(advancedExtrinsicsExpanded ? View.VISIBLE : View.GONE);
        }
        if (btnExtToggleAdvanced != null) {
            btnExtToggleAdvanced.setText(advancedExtrinsicsExpanded
                    ? "收起高级：偏航 / 滚转 / 光心 / 畸变 ▴"
                    : "展开高级：偏航 / 滚转 / 光心 / 畸变 ▾");
            UiOptionPillHelper.apply(btnExtToggleAdvanced, ctx, advancedExtrinsicsExpanded);
        }
        UiOptionPillHelper.apply(btnExtResetCamera, ctx, false);
        UiOptionPillHelper.apply(btnExtResetAll, ctx, false);
        UiOptionPillHelper.apply(btnCalibExportJson, ctx, false);

        if (skipSeekBars) return;
        boolean prevBinding = bindingUi;
        bindingUi = true;
        try {
            if (seekExtScale != null) {
                seekExtScale.setProgress(Math.round((offset.scale - SurroundExtrinsicAdjustment.MIN_SCALE) * 100.0f));
            }
            if (seekExtPitch != null) {
                seekExtPitch.setProgress(Math.round((offset.pitchDeg - SurroundExtrinsicAdjustment.MIN_PITCH_DEG) * 2.0f));
            }
            if (seekExtZ != null) {
                seekExtZ.setProgress(offset.posZCm - SurroundExtrinsicAdjustment.MIN_POS_Z_CM);
            }
            if (seekExtY != null) {
                seekExtY.setProgress(offset.posYCm - SurroundExtrinsicAdjustment.MIN_POS_Y_CM);
            }
            if (seekExtX != null) {
                seekExtX.setProgress(offset.posXCm - SurroundExtrinsicAdjustment.MIN_POS_X_CM);
            }
            if (seekExtYaw != null) {
                seekExtYaw.setProgress(Math.round((offset.yawDeg - SurroundExtrinsicAdjustment.MIN_YAW_DEG) * 2.0f));
            }
            if (seekExtRoll != null) {
                seekExtRoll.setProgress(Math.round((offset.rollDeg - SurroundExtrinsicAdjustment.MIN_ROLL_DEG) * 2.0f));
            }
            if (seekExtCx != null) {
                seekExtCx.setProgress(offset.cxOffsetPx - SurroundExtrinsicAdjustment.MIN_CX_OFFSET_PX);
            }
            if (seekExtCy != null) {
                seekExtCy.setProgress(offset.cyOffsetPx - SurroundExtrinsicAdjustment.MIN_CY_OFFSET_PX);
            }
            if (seekExtK1 != null) {
                seekExtK1.setProgress(Math.round((offset.k1Delta - SurroundExtrinsicAdjustment.MIN_K1_DELTA) * 200.0f));
            }
        } finally {
            bindingUi = prevBinding;
        }
    }

    private void updateCameraTabPill(@Nullable Button btn,
                                     @NonNull Context ctx,
                                     @NonNull String camKey,
                                     @NonNull String label,
                                     @NonNull SurroundExtrinsicAdjustment adj) {
        if (btn == null) return;
        boolean tuned = !adj.getCamera(camKey).isZero();
        btn.setText(tuned ? label + " •" : label);
        UiOptionPillHelper.apply(btn, ctx, camKey.equals(selectedExtrinsicCam));
    }

    @NonNull
    private static String cameraLabel(@NonNull String camKey) {
        if ("back".equals(camKey)) return "后摄";
        if ("left".equals(camKey)) return "左摄";
        if ("right".equals(camKey)) return "右摄";
        return "前摄";
    }

    private void triggerCalibrationExport() {
        Context ctx = getContext();
        if (ctx == null) return;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String suggestedName = "evcam_calibration_" + stamp + ".json";
        try {
            calibrationExportLauncher.launch(suggestedName);
        } catch (ActivityNotFoundException | SecurityException e) {
            exportCalibrationFallbackToFile(ctx, suggestedName);
        }
    }

    private void exportCalibrationFallbackToFile(@NonNull Context ctx, @NonNull String fileName) {
        SurroundCalibrationProfile activeProfile = SurroundCalibrationManager.resolveActiveProfile(appConfig);
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        SurroundCalibrationManager.ExportResult result =
                SurroundCalibrationManager.exportCalibrationToFile(new File(downloadDir, fileName), activeProfile);
        if (!result.success) {
            File appDocs = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (appDocs != null) {
                result = SurroundCalibrationManager.exportCalibrationToFile(new File(appDocs, fileName), activeProfile);
            }
        }
        showExportResult(ctx, result);
    }

    private void showExportResult(@NonNull Context ctx, @NonNull SurroundCalibrationManager.ExportResult result) {
        if (result.success) {
            new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle("已导出 360 校准档案")
                    .setMessage(result.message + "\n\n后续可通过「导入本地校准」直接重新载入使用。")
                    .setPositiveButton("确定", null)
                    .show();
        } else {
            Toast.makeText(ctx, result.message, Toast.LENGTH_LONG).show();
        }
    }
}
