package com.kooo.evcam.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
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

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.SettingsFragment;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.surround.SurroundCalibrationManager;
import com.kooo.evcam.surround.SurroundCalibrationPreviewView;
import com.kooo.evcam.surround.SurroundCalibrationProfile;
import com.kooo.evcam.surround.SurroundCarModelRepository;

import java.io.File;
import java.util.function.IntConsumer;

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

    private final ActivityResultLauncher<String[]> carModelPngPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                Context ctx = getContext();
                if (uri == null || ctx == null) return;
                File internalDir = new File(ctx.getFilesDir(), "surround");
                SurroundCarModelRepository.ImportResult result =
                        SurroundCarModelRepository.importCustomCarModelPng(ctx, uri, internalDir);
                applyCarModelImportResult(ctx, result);
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
                this, calibrationJsonPickerLauncher, result -> applyCalibrationImportResult(requireContext(), result)));

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
            tvCalibrationStatus.setText(customCalib
                    ? "当前方案：" + activeProfile.profileName + "（本地校准）"
                    : "当前方案：银河 E5 内置校准");
            UiOptionPillHelper.apply(btnCalibBuiltin, ctx, !customCalib);
            UiOptionPillHelper.apply(btnCalibImportLocal, ctx, customCalib);

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
}
