package com.kooo.evcam.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.BlindSpotService;
import com.kooo.evcam.R;
import com.kooo.evcam.SettingsFragment;
import com.kooo.evcam.surround.SurroundCalibrationManager;
import com.kooo.evcam.surround.SurroundCalibrationProfile;
import com.kooo.evcam.surround.SurroundCarModelRepository;
import com.kooo.evcam.surround.SurroundViewMode;
import com.kooo.evcam.vehicle.VehicleAutomationCoordinator;

import java.io.File;

/**
 * 4. 360 画面设置页（覆盖 T07 4.1–4.3 全部设置）：
 * - 4.1 预览模式（模式 A vs B/C）、上下分视角、暂停主屏补盲、前后视拉伸铺满、画面内切换按键显隐
 * - 4.2 R 档自动预览延时（0..5000ms）、车速返桌（15..50km/h）、P 档延时返桌（1..60s）
 * - 4.3 银河 E5 内置/本地校准导入、地面视野范围微调、动态转向引导线、离线 E5 车模管理
 */
public class SurroundSettingsFragment extends Fragment implements UiThemeController.ThemeAware {

    private AppConfig appConfig;
    private boolean bindingUi;

    private TextView tvModeStatus;
    private Button btnModeA;
    private Button btnModeB;
    private Button btnModeC;
    private Button btnModeD;

    private Button btnTopDrive;
    private Button btnTopFront;
    private Button btnTopRear;
    private Button btnTopFrontWheels;
    private Button btnTopRearWheels;

    private Button btnBottomFrontWheels;
    private Button btnBottomRearWheels;
    private Button btnBottomSides;

    private Button btnModeDLinkageTurnSignal;
    private Button btnModeDLinkageSteering;
    private Button btnModeDLinkageManual;

    private SwitchMaterial switchSuppressBlindSpot;
    private SwitchMaterial switchStretchRoad;
    private SwitchMaterial switchOverlayButtons;

    private SwitchMaterial switchReversePreviewEnabled;
    private TextView tvReverseDelayValue;
    private SwitchMaterial switchSpeedCloseEnabled;
    private TextView tvSpeedThresholdValue;
    private SwitchMaterial switchParkCloseEnabled;
    private TextView tvParkCloseDelayValue;

    private TextView tvCalibrationStatus;
    private Button btnCalibLivePreview;
    private Button btnCalibBuiltin;
    private Button btnCalibImportLocal;
    private TextView tvGroundWidthValue;
    private TextView tvGroundLengthValue;
    private TextView tvGroundFrontExtValue;

    private SwitchMaterial switchGuidanceEnabled;
    private Button btnGuidanceAutoRules;
    private ImageView ivCarModelPreview;
    private TextView tvCarModelStatus;
    private Button btnCarModelBuiltin;
    private Button btnCarModelImportPng;
    @Nullable
    private String loadedCarModelSignature;

    private final ActivityResultLauncher<String[]> carModelPngPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                Context ctx = getContext();
                if (uri == null || ctx == null) {
                    return;
                }
                File internalDir = new File(ctx.getFilesDir(), "surround");
                SurroundCarModelRepository.ImportResult result =
                        SurroundCarModelRepository.importCustomCarModelPng(ctx, uri, internalDir);
                applyCarModelImportResult(ctx, result);
            });

    private final ActivityResultLauncher<String[]> calibrationJsonPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                Context ctx = getContext();
                if (uri == null || ctx == null) {
                    return;
                }
                File internalDir = new File(ctx.getFilesDir(), "surround");
                SurroundCalibrationManager.ImportResult result =
                        SurroundCalibrationManager.importCalibrationUri(ctx, uri, internalDir);
                applyCalibrationImportResult(ctx, result);
            });

    private final ActivityResultLauncher<android.net.Uri> calibrationFolderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                Context ctx = getContext();
                if (uri == null || ctx == null) {
                    return;
                }
                File internalDir = new File(ctx.getFilesDir(), "surround");
                SurroundCalibrationManager.ImportResult result =
                        SurroundCalibrationManager.importCalibrationTreeUri(ctx, uri, internalDir);
                applyCalibrationImportResult(ctx, result);
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_surround_settings, container, false);
        appConfig = new AppConfig(inflater.getContext());

        View btnBack = view.findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> SettingsFragment.popSubPage(this));
        }

        bindViews(view);
        wireListeners();
        refreshAllUi(inflater.getContext());
        SettingsFragment.configureEmbeddedToolbar(this, view, "360 画面");
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getContext() != null && getView() != null) {
            refreshAllUi(getContext());
        }
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        if (themedContext == null || getView() == null) return;
        refreshAllUi(themedContext);
    }

    private void bindViews(@NonNull View view) {
        tvModeStatus = view.findViewById(R.id.tv_surround_mode_status);
        btnModeA = view.findViewById(R.id.btn_surround_mode_a);
        btnModeB = view.findViewById(R.id.btn_surround_mode_b);
        btnModeC = view.findViewById(R.id.btn_surround_mode_c);
        btnModeD = view.findViewById(R.id.btn_surround_mode_d);

        btnTopDrive = view.findViewById(R.id.btn_top_view_drive);
        btnTopFront = view.findViewById(R.id.btn_top_view_front);
        btnTopRear = view.findViewById(R.id.btn_top_view_rear);
        btnTopFrontWheels = view.findViewById(R.id.btn_top_view_front_wheels);
        btnTopRearWheels = view.findViewById(R.id.btn_top_view_rear_wheels);

        btnBottomFrontWheels = view.findViewById(R.id.btn_bottom_view_front_wheels);
        btnBottomRearWheels = view.findViewById(R.id.btn_bottom_view_rear_wheels);
        btnBottomSides = view.findViewById(R.id.btn_bottom_view_sides);

        btnModeDLinkageTurnSignal = view.findViewById(R.id.btn_mode_d_linkage_turn_signal);
        btnModeDLinkageSteering = view.findViewById(R.id.btn_mode_d_linkage_steering);
        btnModeDLinkageManual = view.findViewById(R.id.btn_mode_d_linkage_manual);

        switchSuppressBlindSpot = view.findViewById(R.id.switch_surround_suppress_blindspot);
        switchStretchRoad = view.findViewById(R.id.switch_surround_stretch_road);
        switchOverlayButtons = view.findViewById(R.id.switch_surround_overlay_buttons);

        switchReversePreviewEnabled = view.findViewById(R.id.switch_reverse_preview_enabled);
        tvReverseDelayValue = view.findViewById(R.id.tv_reverse_delay_value);
        switchSpeedCloseEnabled = view.findViewById(R.id.switch_speed_close_enabled);
        tvSpeedThresholdValue = view.findViewById(R.id.tv_speed_threshold_value);
        switchParkCloseEnabled = view.findViewById(R.id.switch_park_close_enabled);
        tvParkCloseDelayValue = view.findViewById(R.id.tv_park_close_delay_value);

        tvCalibrationStatus = view.findViewById(R.id.tv_surround_calibration_status);
        btnCalibLivePreview = view.findViewById(R.id.btn_calibration_live_preview);
        btnCalibBuiltin = view.findViewById(R.id.btn_calibration_e5_builtin);
        btnCalibImportLocal = view.findViewById(R.id.btn_calibration_import_local);
        tvGroundWidthValue = view.findViewById(R.id.tv_ground_width_value);
        tvGroundLengthValue = view.findViewById(R.id.tv_ground_length_value);
        tvGroundFrontExtValue = view.findViewById(R.id.tv_ground_front_ext_value);

        switchGuidanceEnabled = view.findViewById(R.id.switch_surround_guidance_enabled);
        btnGuidanceAutoRules = view.findViewById(R.id.btn_guidance_auto_rules);
        ivCarModelPreview = view.findViewById(R.id.iv_car_model_preview);
        tvCarModelStatus = view.findViewById(R.id.tv_car_model_status);
        btnCarModelBuiltin = view.findViewById(R.id.btn_car_model_builtin);
        btnCarModelImportPng = view.findViewById(R.id.btn_car_model_import_png);
    }

    private void wireListeners() {
        View root = null;
        if (btnModeA != null) root = btnModeA.getRootView();

        btnModeA.setOnClickListener(v -> selectPreviewMode(AppConfig.PREVIEW_MODE_A));
        btnModeB.setOnClickListener(v -> selectPreviewMode(AppConfig.PREVIEW_MODE_B));
        btnModeC.setOnClickListener(v -> selectPreviewMode(AppConfig.PREVIEW_MODE_C));
        if (btnModeD != null) {
            btnModeD.setOnClickListener(v -> selectPreviewMode(AppConfig.PREVIEW_MODE_D));
        }

        btnTopDrive.setOnClickListener(v -> selectTopView(SurroundViewMode.DRIVE));
        btnTopFront.setOnClickListener(v -> selectTopView(SurroundViewMode.FRONT));
        btnTopRear.setOnClickListener(v -> selectTopView(SurroundViewMode.REAR));
        btnTopFrontWheels.setOnClickListener(v -> selectTopView(SurroundViewMode.FRONT_WHEELS));
        btnTopRearWheels.setOnClickListener(v -> selectTopView(SurroundViewMode.REAR_WHEELS));

        btnBottomFrontWheels.setOnClickListener(v -> selectBottomView(SurroundViewMode.FRONT_WHEELS));
        btnBottomRearWheels.setOnClickListener(v -> selectBottomView(SurroundViewMode.REAR_WHEELS));
        btnBottomSides.setOnClickListener(v -> selectBottomView(SurroundViewMode.SIDES));

        if (btnModeDLinkageTurnSignal != null) {
            btnModeDLinkageTurnSignal.setOnClickListener(
                    v -> selectModeDSideLinkage(AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL));
        }
        if (btnModeDLinkageSteering != null) {
            btnModeDLinkageSteering.setOnClickListener(
                    v -> selectModeDSideLinkage(AppConfig.MODE_D_SIDE_LINKAGE_STEERING));
        }
        if (btnModeDLinkageManual != null) {
            btnModeDLinkageManual.setOnClickListener(
                    v -> selectModeDSideLinkage(AppConfig.MODE_D_SIDE_LINKAGE_MANUAL));
        }

        switchSuppressBlindSpot.setOnCheckedChangeListener((b, isChecked) -> {
            if (bindingUi) return;
            appConfig.setBlindSpotSuppressOn360(isChecked);
            if (getContext() != null) {
                BlindSpotService.update(getContext());
            }
        });
        switchStretchRoad.setOnCheckedChangeListener((b, isChecked) -> {
            if (bindingUi) return;
            appConfig.setSurroundStretchRoadViews(isChecked);
            notifyHostSurroundChanged();
        });
        switchOverlayButtons.setOnCheckedChangeListener((b, isChecked) -> {
            if (bindingUi) return;
            appConfig.setSurroundOverlayButtonsVisible(isChecked);
            appConfig.setPreviewViewShortcutsEnabled(isChecked);
            notifyHostSurroundChanged();
        });

        switchReversePreviewEnabled.setOnCheckedChangeListener((b, isChecked) -> {
            if (bindingUi) return;
            appConfig.setReversePreviewEnabled(isChecked);
            reloadAutomation();
        });
        wireStepPair(root, R.id.btn_reverse_delay_minus, R.id.btn_reverse_delay_plus, () -> {
            appConfig.setReversePreviewDelayMs(appConfig.getReversePreviewDelayMs() - AppConfig.STEP_REVERSE_PREVIEW_DELAY_MS);
            reloadAutomation();
            refreshAutoCloseUi();
        }, () -> {
            appConfig.setReversePreviewDelayMs(appConfig.getReversePreviewDelayMs() + AppConfig.STEP_REVERSE_PREVIEW_DELAY_MS);
            reloadAutomation();
            refreshAutoCloseUi();
        });

        switchSpeedCloseEnabled.setOnCheckedChangeListener((b, isChecked) -> {
            if (bindingUi) return;
            appConfig.setPreviewSpeedCloseEnabled(isChecked);
            reloadAutomation();
        });
        wireStepPair(root, R.id.btn_speed_threshold_minus, R.id.btn_speed_threshold_plus, () -> {
            appConfig.setPreviewSpeedThresholdKmh(appConfig.getPreviewSpeedThresholdKmh() - 1);
            reloadAutomation();
            refreshAutoCloseUi();
        }, () -> {
            appConfig.setPreviewSpeedThresholdKmh(appConfig.getPreviewSpeedThresholdKmh() + 1);
            reloadAutomation();
            refreshAutoCloseUi();
        });

        switchParkCloseEnabled.setOnCheckedChangeListener((b, isChecked) -> {
            if (bindingUi) return;
            appConfig.setParkPreviewCloseEnabled(isChecked);
            reloadAutomation();
        });
        wireStepPair(root, R.id.btn_park_delay_minus, R.id.btn_park_delay_plus, () -> {
            appConfig.setParkPreviewCloseDelaySeconds(appConfig.getParkPreviewCloseDelaySeconds() - 1);
            reloadAutomation();
            refreshAutoCloseUi();
        }, () -> {
            appConfig.setParkPreviewCloseDelaySeconds(appConfig.getParkPreviewCloseDelaySeconds() + 1);
            reloadAutomation();
            refreshAutoCloseUi();
        });

        if (btnCalibLivePreview != null) {
            btnCalibLivePreview.setOnClickListener(v ->
                    SettingsFragment.openSubPage(this, new SurroundCalibrationLiveFragment()));
        }
        btnCalibBuiltin.setOnClickListener(v -> {
            appConfig.setSurroundCalibrationSource(AppConfig.SURROUND_CALIBRATION_E5_BUILTIN);
            notifyHostSurroundChanged();
            refreshCalibrationAndModelUi(requireContext(), false);
        });
        btnCalibImportLocal.setOnClickListener(v -> SurroundAssetImportHelper.showLocalCalibrationPicker(
                this, calibrationJsonPickerLauncher, calibrationFolderPickerLauncher,
                result -> applyCalibrationImportResult(requireContext(), result)));

        wireStepPair(root, R.id.btn_ground_width_minus, R.id.btn_ground_width_plus, () -> {
            appConfig.setSurroundGroundWidthCm(appConfig.getSurroundGroundWidthCm() - 5);
            notifyHostSurroundChanged();
            refreshGroundDimensionTexts();
        }, () -> {
            appConfig.setSurroundGroundWidthCm(appConfig.getSurroundGroundWidthCm() + 5);
            notifyHostSurroundChanged();
            refreshGroundDimensionTexts();
        });

        wireStepPair(root, R.id.btn_ground_length_minus, R.id.btn_ground_length_plus, () -> {
            appConfig.setSurroundGroundLengthCm(appConfig.getSurroundGroundLengthCm() - 10);
            notifyHostSurroundChanged();
            refreshGroundDimensionTexts();
        }, () -> {
            appConfig.setSurroundGroundLengthCm(appConfig.getSurroundGroundLengthCm() + 10);
            notifyHostSurroundChanged();
            refreshGroundDimensionTexts();
        });

        wireStepPair(root, R.id.btn_ground_ext_minus, R.id.btn_ground_ext_plus, () -> {
            appConfig.setSurroundGroundFrontExtensionCm(appConfig.getSurroundGroundFrontExtensionCm() - 10);
            notifyHostSurroundChanged();
            refreshGroundDimensionTexts();
        }, () -> {
            appConfig.setSurroundGroundFrontExtensionCm(appConfig.getSurroundGroundFrontExtensionCm() + 10);
            notifyHostSurroundChanged();
            refreshGroundDimensionTexts();
        });

        View btnResetGround = root != null ? root.findViewById(R.id.btn_ground_reset_default) : null;
        if (btnResetGround != null) {
            btnResetGround.setOnClickListener(v -> {
                appConfig.resetSurroundGroundDimensions();
                notifyHostSurroundChanged();
                refreshGroundDimensionTexts();
                Toast.makeText(getContext(), "已恢复银河 E5 默认拼接视野参数", Toast.LENGTH_SHORT).show();
            });
        }

        switchGuidanceEnabled.setOnCheckedChangeListener((b, isChecked) -> {
            if (bindingUi) return;
            appConfig.setPreviewGuidanceEnabled(isChecked);
            notifyHostSurroundChanged();
        });
        if (btnGuidanceAutoRules != null) {
            btnGuidanceAutoRules.setOnClickListener(v -> {
                if (!SettingsFragment.navigateToCategory(this, SettingsCatalog.ID_RECORDER)
                        && getContext() != null) {
                    Toast.makeText(getContext(), "请前往「录影设置 → 车辆状态自动触发规则」设置", Toast.LENGTH_SHORT).show();
                }
            });
        }

        btnCarModelBuiltin.setOnClickListener(v -> {
            appConfig.setSurroundCarModelSource(AppConfig.SURROUND_CAR_MODEL_E5_BUILTIN);
            notifyHostSurroundChanged();
            refreshCalibrationAndModelUi(requireContext(), true);
        });
        btnCarModelImportPng.setOnClickListener(v -> SurroundAssetImportHelper.showLocalCarModelPicker(
                this, carModelPngPickerLauncher, result -> applyCarModelImportResult(requireContext(), result)));
    }

    private void wireStepPair(@Nullable View root, int minusId, int plusId,
                              @NonNull Runnable onMinus, @NonNull Runnable onPlus) {
        if (root == null) return;
        View minus = root.findViewById(minusId);
        View plus = root.findViewById(plusId);
        if (minus != null) minus.setOnClickListener(v -> onMinus.run());
        if (plus != null) plus.setOnClickListener(v -> onPlus.run());
    }

    private void selectPreviewMode(@NonNull String previewMode) {
        appConfig.setPreviewMode(previewMode);
        if (getActivity() instanceof PreviewModeController.Host) {
            ((PreviewModeController.Host) getActivity()).onDefaultPreviewModeChanged(
                    PreviewMode.fromKey(appConfig.getPreviewMode()));
        }
        if (getContext() != null) {
            refreshAllUi(getContext());
        }
    }

    private void selectTopView(@NonNull SurroundViewMode topMode) {
        appConfig.setSurroundDetailViews(topMode, appConfig.getSurroundBottomView());
        notifyHostSurroundChanged();
        if (getContext() != null) {
            refreshAllUi(getContext());
        }
    }

    private void selectBottomView(@NonNull SurroundViewMode bottomMode) {
        appConfig.setSurroundDetailViews(appConfig.getSurroundTopView(), bottomMode);
        notifyHostSurroundChanged();
        if (getContext() != null) {
            refreshAllUi(getContext());
        }
    }

    private void selectModeDSideLinkage(@NonNull String linkageMode) {
        appConfig.setModeDSideLinkageMode(linkageMode);
        notifyHostSurroundChanged();
        if (getContext() != null) {
            refreshAllUi(getContext());
        }
    }

    private void notifyHostSurroundChanged() {
        if (getActivity() instanceof PreviewModeController.Host) {
            ((PreviewModeController.Host) getActivity()).onPreviewShortcutPreferencesChanged();
        }
    }

    private void reloadAutomation() {
        Context ctx = getContext();
        if (ctx != null) {
            com.kooo.evcam.lifecycle.BackgroundCapabilityController.syncBackgroundKeepAlive(ctx);
            VehicleAutomationCoordinator.getInstance().startOrReload(ctx);
            if (com.kooo.evcam.lifecycle.BackgroundCapabilityController.hasActiveBackgroundCapabilities(ctx)
                    && com.kooo.evcam.lifecycle.AppLifecycleState.canRunBackgroundServices(ctx)) {
                com.kooo.evcam.CameraForegroundService.start(ctx, "EVCam", "车辆信号与倒车触发运行中");
            }
        }
    }

    private void refreshAllUi(@NonNull Context ctx) {
        bindingUi = true;
        try {
            String mode = appConfig.getPreviewMode();
            tvModeStatus.setText("当前主预览模式：" + AppearanceSettingsFragment.formatPreviewModeLabel(mode));
            applyOptionButtonState(btnModeA, ctx, AppConfig.PREVIEW_MODE_A.equals(mode));
            applyOptionButtonState(btnModeB, ctx, AppConfig.PREVIEW_MODE_B.equals(mode));
            applyOptionButtonState(btnModeC, ctx, AppConfig.PREVIEW_MODE_C.equals(mode));
            applyOptionButtonState(btnModeD, ctx, AppConfig.PREVIEW_MODE_D.equals(mode));

            SurroundViewMode top = appConfig.getSurroundTopView();
            SurroundViewMode bottom = appConfig.getSurroundBottomView();
            applyOptionButtonState(btnTopDrive, ctx, top == SurroundViewMode.DRIVE);
            applyOptionButtonState(btnTopFront, ctx, top == SurroundViewMode.FRONT);
            applyOptionButtonState(btnTopRear, ctx, top == SurroundViewMode.REAR);
            applyOptionButtonState(btnTopFrontWheels, ctx, top == SurroundViewMode.FRONT_WHEELS);
            applyOptionButtonState(btnTopRearWheels, ctx, top == SurroundViewMode.REAR_WHEELS);

            applyOptionButtonState(btnBottomFrontWheels, ctx, bottom == SurroundViewMode.FRONT_WHEELS);
            applyOptionButtonState(btnBottomRearWheels, ctx, bottom == SurroundViewMode.REAR_WHEELS);
            applyOptionButtonState(btnBottomSides, ctx, bottom == SurroundViewMode.SIDES);

            String linkageMode = appConfig.getModeDSideLinkageMode();
            applyOptionButtonState(btnModeDLinkageTurnSignal, ctx,
                    AppConfig.MODE_D_SIDE_LINKAGE_TURN_SIGNAL.equals(linkageMode));
            applyOptionButtonState(btnModeDLinkageSteering, ctx,
                    AppConfig.MODE_D_SIDE_LINKAGE_STEERING.equals(linkageMode));
            applyOptionButtonState(btnModeDLinkageManual, ctx,
                    AppConfig.MODE_D_SIDE_LINKAGE_MANUAL.equals(linkageMode));

            switchSuppressBlindSpot.setChecked(appConfig.isBlindSpotSuppressOn360());
            switchStretchRoad.setChecked(appConfig.isSurroundStretchRoadViews());
            switchOverlayButtons.setChecked(
                    appConfig.isSurroundOverlayButtonsVisible() && appConfig.isPreviewViewShortcutsEnabled());

            switchReversePreviewEnabled.setChecked(appConfig.isReversePreviewEnabled());
            switchSpeedCloseEnabled.setChecked(appConfig.isPreviewSpeedCloseEnabled());
            switchParkCloseEnabled.setChecked(appConfig.isParkPreviewCloseEnabled());
            refreshAutoCloseUi();

            refreshGroundDimensionTexts();
            switchGuidanceEnabled.setChecked(appConfig.isPreviewGuidanceEnabled());
            applyOptionButtonState(btnGuidanceAutoRules, ctx, false);
            refreshCalibrationAndModelUi(ctx, false);
        } finally {
            bindingUi = false;
        }
    }

    private void refreshAutoCloseUi() {
        tvReverseDelayValue.setText("触发延迟：" + appConfig.getReversePreviewDelayMs() + " ms（范围 0–5000 ms）");
        tvSpeedThresholdValue.setText("返桌速度阈值：" + appConfig.getPreviewSpeedThresholdKmh() + " km/h（范围 15–50）");
        tvParkCloseDelayValue.setText("等待时长：" + appConfig.getParkPreviewCloseDelaySeconds() + " 秒（范围 1–60 秒）");
    }

    private void refreshGroundDimensionTexts() {
        tvGroundWidthValue.setText("地面宽度：" + appConfig.getSurroundGroundWidthCm() + " cm（160–260）");
        tvGroundLengthValue.setText("地面长度：" + appConfig.getSurroundGroundLengthCm() + " cm（420–600）");
        tvGroundFrontExtValue.setText("车头视野延伸：" + appConfig.getSurroundGroundFrontExtensionCm() + " cm（0–100）");
    }

    private void refreshCalibrationAndModelUi(@NonNull Context ctx, boolean forceReloadCarBitmap) {
        SurroundCalibrationProfile activeProfile = SurroundCalibrationManager.resolveActiveProfile(appConfig);
        boolean customCalib = AppConfig.SURROUND_CALIBRATION_E5_CUSTOM.equals(appConfig.getSurroundCalibrationSource())
                && !appConfig.getSurroundCustomCalibrationPath().isEmpty()
                && activeProfile.isCustomCalibration;
        String baseStatus = customCalib
                ? "当前方案：本地导入 E5 校准（已校验）"
                : "当前方案：银河 E5 内置校准";
        tvCalibrationStatus.setText(activeProfile.hasExtrinsicAdjustments
                ? baseStatus + "（已启用外参微调）"
                : baseStatus);
        UiOptionPillHelper.apply(btnCalibLivePreview, ctx, true, true);
        UiOptionPillHelper.apply(btnCalibBuiltin, ctx, !customCalib);
        UiOptionPillHelper.apply(btnCalibImportLocal, ctx, customCalib);

        String carSource = appConfig.getSurroundCarModelSource();
        String customCarPath = appConfig.getSurroundCustomCarModelPath();
        boolean customCar = AppConfig.SURROUND_CAR_MODEL_CUSTOM_PNG.equals(carSource)
                && !customCarPath.isEmpty();
        tvCarModelStatus.setText(customCar
                ? "当前车模：本地导入 PNG 车模"
                : "当前车模：银河 E5 内置车模");
        UiOptionPillHelper.apply(btnCarModelBuiltin, ctx, !customCar);
        UiOptionPillHelper.apply(btnCarModelImportPng, ctx, customCar);

        String signature = carSource + "|" + customCarPath;
        if (forceReloadCarBitmap
                || !signature.equals(loadedCarModelSignature)
                || ivCarModelPreview.getDrawable() == null) {
            Bitmap previewBmp = SurroundCarModelRepository.loadActiveCarModelBitmap(ctx, appConfig);
            ivCarModelPreview.setImageBitmap(previewBmp);
            loadedCarModelSignature = signature;
        }
    }

    private void applyCalibrationImportResult(@NonNull Context ctx,
                                              @NonNull SurroundCalibrationManager.ImportResult result) {
        SurroundAssetImportHelper.applyCalibrationImportResult(ctx, appConfig, result, () -> {
            notifyHostSurroundChanged();
            refreshCalibrationAndModelUi(ctx, false);
        });
    }

    private void applyCarModelImportResult(@NonNull Context ctx,
                                           @NonNull SurroundCarModelRepository.ImportResult result) {
        SurroundAssetImportHelper.applyCarModelImportResult(ctx, appConfig, result, () -> {
            notifyHostSurroundChanged();
            refreshCalibrationAndModelUi(ctx, true);
        });
    }

    private void applyOptionButtonState(@Nullable Button button, @NonNull Context context, boolean selected) {
        UiOptionPillHelper.apply(button, context, selected);
    }
}
