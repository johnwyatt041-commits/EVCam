package com.kooo.evcam;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.blindspot.BlindSpotCorrectionMath;
import com.kooo.evcam.blindspot.BlindSpotCropPreviewView;
import com.kooo.evcam.blindspot.CameraMirrorPolicy;
import com.kooo.evcam.blindspot.MirrorTarget;
import com.kooo.evcam.ui.UiOptionPillHelper;
import com.kooo.evcam.ui.UiThemeController;

import java.util.Locale;

/**
 * 补盲分方向旋转角度与四边裁切调整界面（独立作用于补盲画面）。
 *
 * <p>每一路（前/后/左/右）独立配置旋转角度 (0°–359°) 与上/下/左/右四边裁切比例，
 * 仅作用于补盲显示（主屏补盲弹窗与副屏 HUD），不影响记录仪主预览和录像，也不引入鱼眼畸变。</p>
 */
public class BlindSpotCorrectionFragment extends Fragment implements UiThemeController.ThemeAware {

    private AppConfig appConfig;
    private String currentCameraPos = "right";
    private MirrorTarget currentMirrorTarget = MirrorTarget.BLIND_SPOT;
    private boolean isFloatingPreviewActive = false;
    private boolean isUpdatingUi = false;

    private Button btnToggleFloatingPreview;
    private Button btnCamFront;
    private Button btnCamBack;
    private Button btnCamLeft;
    private Button btnCamRight;

    private BlindSpotCropPreviewView cropPreviewView;
    private TextView tvRotationValue;
    private Button btnRot0;
    private Button btnRot90;
    private Button btnRot180;
    private Button btnRot270;
    private SeekBar seekbarRotation;

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
    private Button btnTargetBlindSpot;
    private Button btnTargetSecondaryHud;
    private Button btnTargetNetworkStream;
    private Button btnOutputMirrorH;
    private Button btnOutputMirrorV;
    private TextView tvMirrorEffectiveState;

    private Button btnResetCurrent;
    private Button btnSaveCorrection;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_blind_spot_correction, container, false);
        appConfig = new AppConfig(requireContext());
        bindViews(view);
        initListeners(view);
        loadCameraValues(currentCameraPos);
        SettingsFragment.configureSubPageToolbar(this, view);
        return view;
    }

    private void bindViews(View view) {
        btnToggleFloatingPreview = view.findViewById(R.id.btn_toggle_floating_preview);
        btnCamFront = view.findViewById(R.id.btn_cam_front);
        btnCamBack = view.findViewById(R.id.btn_cam_back);
        btnCamLeft = view.findViewById(R.id.btn_cam_left);
        btnCamRight = view.findViewById(R.id.btn_cam_right);

        cropPreviewView = view.findViewById(R.id.blind_spot_crop_preview);
        tvRotationValue = view.findViewById(R.id.tv_rotation_value);
        btnRot0 = view.findViewById(R.id.btn_rot_0);
        btnRot90 = view.findViewById(R.id.btn_rot_90);
        btnRot180 = view.findViewById(R.id.btn_rot_180);
        btnRot270 = view.findViewById(R.id.btn_rot_270);
        seekbarRotation = view.findViewById(R.id.seekbar_rotation);

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
        btnTargetBlindSpot = view.findViewById(R.id.btn_target_blind_spot);
        btnTargetSecondaryHud = view.findViewById(R.id.btn_target_secondary_hud);
        btnTargetNetworkStream = view.findViewById(R.id.btn_target_network_stream);
        btnTargetNetworkStream.setVisibility(AppFeatures.NETWORK_STREAMING ? View.VISIBLE : View.GONE);
        btnOutputMirrorH = view.findViewById(R.id.btn_output_mirror_h);
        btnOutputMirrorV = view.findViewById(R.id.btn_output_mirror_v);
        tvMirrorEffectiveState = view.findViewById(R.id.tv_mirror_effective_state);

        btnResetCurrent = view.findViewById(R.id.btn_reset_current);
        btnSaveCorrection = view.findViewById(R.id.btn_save_correction);
    }

    private void initListeners(View view) {
        view.findViewById(R.id.btn_back).setOnClickListener(v -> SettingsFragment.popSubPage(this));
        View btnMenu = view.findViewById(R.id.btn_menu);
        if (btnMenu != null) {
            btnMenu.setVisibility(View.GONE);
        }
        View btnHome = view.findViewById(R.id.btn_home);
        if (btnHome != null) {
            btnHome.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).goToRecordingInterface();
                }
            });
        }

        btnToggleFloatingPreview.setOnClickListener(v -> toggleFloatingPreview());

        btnCamFront.setOnClickListener(v -> switchCamera("front"));
        btnCamBack.setOnClickListener(v -> switchCamera("back"));
        btnCamLeft.setOnClickListener(v -> switchCamera("left"));
        btnCamRight.setOnClickListener(v -> switchCamera("right"));

        btnRot0.setOnClickListener(v -> setChannelRotation(0));
        btnRot90.setOnClickListener(v -> setChannelRotation(90));
        btnRot180.setOnClickListener(v -> setChannelRotation(180));
        btnRot270.setOnClickListener(v -> setChannelRotation(270));

        seekbarRotation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (isUpdatingUi || !fromUser) return;
                setChannelRotation(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                notifyServiceUpdate();
            }
        });

        bindEdgeCropSeekBar(seekbarCropTop, tvCropTopValue, Edge.TOP);
        bindEdgeCropSeekBar(seekbarCropBottom, tvCropBottomValue, Edge.BOTTOM);
        bindEdgeCropSeekBar(seekbarCropLeft, tvCropLeftValue, Edge.LEFT);
        bindEdgeCropSeekBar(seekbarCropRight, tvCropRightValue, Edge.RIGHT);

        btnPhysicalMirrorH.setOnClickListener(v -> {
            appConfig.setPhysicalMirrorH(currentCameraPos, !appConfig.isPhysicalMirrorH(currentCameraPos));
            updateMirrorUi();
            notifyServiceUpdate();
        });
        btnPhysicalMirrorV.setOnClickListener(v -> {
            appConfig.setPhysicalMirrorV(currentCameraPos, !appConfig.isPhysicalMirrorV(currentCameraPos));
            updateMirrorUi();
            notifyServiceUpdate();
        });

        btnTargetBlindSpot.setOnClickListener(v -> {
            currentMirrorTarget = MirrorTarget.BLIND_SPOT;
            updateMirrorUi();
        });
        btnTargetSecondaryHud.setOnClickListener(v -> {
            currentMirrorTarget = MirrorTarget.SECONDARY_HUD;
            updateMirrorUi();
        });
        btnTargetNetworkStream.setOnClickListener(v -> {
            currentMirrorTarget = MirrorTarget.NETWORK_STREAM;
            updateMirrorUi();
        });

        btnOutputMirrorH.setOnClickListener(v -> {
            appConfig.setOutputMirrorH(
                    currentMirrorTarget,
                    currentCameraPos,
                    !appConfig.isOutputMirrorH(currentMirrorTarget, currentCameraPos));
            updateMirrorUi();
            notifyServiceUpdate();
        });
        btnOutputMirrorV.setOnClickListener(v -> {
            appConfig.setOutputMirrorV(
                    currentMirrorTarget,
                    currentCameraPos,
                    !appConfig.isOutputMirrorV(currentMirrorTarget, currentCameraPos));
            updateMirrorUi();
            notifyServiceUpdate();
        });

        btnResetCurrent.setOnClickListener(v -> {
            appConfig.resetBlindSpotCorrection(currentCameraPos);
            appConfig.setPhysicalMirrorH(currentCameraPos, false);
            appConfig.setPhysicalMirrorV(currentCameraPos, false);
            appConfig.resetMirrorForTarget(currentMirrorTarget, currentCameraPos);
            loadCameraValues(currentCameraPos);
            notifyServiceUpdate();
            Toast.makeText(requireContext(),
                    "已恢复「" + getCameraLabel(currentCameraPos) + "」默认角度、裁切与镜像",
                    Toast.LENGTH_SHORT).show();
        });

        btnSaveCorrection.setOnClickListener(v -> {
            if (!appConfig.isBlindSpotCorrectionEnabled() && hasActiveCorrectionForCurrentCamera()) {
                appConfig.setBlindSpotCorrectionEnabled(true);
            }
            notifyServiceUpdate();
            Toast.makeText(requireContext(), "分方向补盲矫正已保存并应用", Toast.LENGTH_SHORT).show();
            SettingsFragment.popSubPage(this);
        });
    }

    private enum Edge {
        TOP, BOTTOM, LEFT, RIGHT
    }

    private void bindEdgeCropSeekBar(SeekBar seekBar, TextView valueView, Edge edge) {
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (isUpdatingUi || !fromUser) return;
                BlindSpotCorrectionMath.EdgeCrop cur = appConfig.getBlindSpotCorrectionEdgeCrop(currentCameraPos);
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
                    isUpdatingUi = true;
                    sb.setProgress(clamped);
                    isUpdatingUi = false;
                }
                valueView.setText(clamped + "%");

                BlindSpotCorrectionMath.EdgeCrop next =
                        BlindSpotCorrectionMath.EdgeCrop.fromPercents(leftPct, topPct, rightPct, bottomPct);
                appConfig.setBlindSpotCorrectionEdgeCrop(currentCameraPos, next);
                ensureCorrectionEnabledWhenModified();
                updateCropAndRotationVisuals();
                notifyServiceUpdate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                notifyServiceUpdate();
            }
        });
    }

    private void switchCamera(String cameraPos) {
        currentCameraPos = cameraPos;
        loadCameraValues(cameraPos);
        if (isFloatingPreviewActive) {
            sendPreviewAction(cameraPos);
        }
    }

    private void setChannelRotation(int degrees) {
        int safeDeg = BlindSpotCorrectionMath.normalizeAngle(degrees);
        appConfig.setBlindSpotCorrectionRotation(currentCameraPos, safeDeg);
        ensureCorrectionEnabledWhenModified();
        isUpdatingUi = true;
        seekbarRotation.setProgress(safeDeg);
        isUpdatingUi = false;
        updateCropAndRotationVisuals();
        notifyServiceUpdate();
    }

    private void ensureCorrectionEnabledWhenModified() {
        if (!appConfig.isBlindSpotCorrectionEnabled() && hasActiveCorrectionForCurrentCamera()) {
            appConfig.setBlindSpotCorrectionEnabled(true);
        }
    }

    private boolean hasActiveCorrectionForCurrentCamera() {
        return appConfig.getBlindSpotCorrectionRotation(currentCameraPos) != 0
                || !appConfig.getBlindSpotCorrectionEdgeCrop(currentCameraPos).isZero();
    }

    private void loadCameraValues(String cameraPos) {
        isUpdatingUi = true;
        try {
            int rotation = BlindSpotCorrectionMath.normalizeAngle(
                    appConfig.getBlindSpotCorrectionRotation(cameraPos));
            seekbarRotation.setProgress(rotation);

            BlindSpotCorrectionMath.EdgeCrop crop = appConfig.getBlindSpotCorrectionEdgeCrop(cameraPos);
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
        } finally {
            isUpdatingUi = false;
        }
        updateCameraButtonsUi();
        updateCropAndRotationVisuals();
        updateMirrorUi();
        updatePreviewButtonUi();
    }

    private void updateCameraButtonsUi() {
        Context ctx = getContext();
        if (ctx == null) return;
        UiOptionPillHelper.apply(btnCamFront, ctx, "front".equals(currentCameraPos));
        UiOptionPillHelper.apply(btnCamBack, ctx, "back".equals(currentCameraPos));
        UiOptionPillHelper.apply(btnCamLeft, ctx, "left".equals(currentCameraPos));
        UiOptionPillHelper.apply(btnCamRight, ctx, "right".equals(currentCameraPos));
    }

    private void updateCropAndRotationVisuals() {
        Context ctx = getContext();
        int rotation = BlindSpotCorrectionMath.normalizeAngle(
                appConfig.getBlindSpotCorrectionRotation(currentCameraPos));
        BlindSpotCorrectionMath.EdgeCrop crop =
                appConfig.getBlindSpotCorrectionEdgeCrop(currentCameraPos);

        tvRotationValue.setText(rotation + "°");
        tvCropSummary.setText(String.format(
                Locale.US,
                "保留 %d%% × %d%%",
                Math.round(crop.visibleWidth() * 100f),
                Math.round(crop.visibleHeight() * 100f)));
        cropPreviewView.setPreviewState(getCameraLabel(currentCameraPos), rotation, crop);

        if (ctx != null) {
            UiOptionPillHelper.apply(btnRot0, ctx, rotation == 0);
            UiOptionPillHelper.apply(btnRot90, ctx, rotation == 90);
            UiOptionPillHelper.apply(btnRot180, ctx, rotation == 180);
            UiOptionPillHelper.apply(btnRot270, ctx, rotation == 270);
        }
    }

    private void updateMirrorUi() {
        Context ctx = getContext();
        boolean physH = appConfig.isPhysicalMirrorH(currentCameraPos);
        boolean physV = appConfig.isPhysicalMirrorV(currentCameraPos);
        boolean outH = appConfig.isOutputMirrorH(currentMirrorTarget, currentCameraPos);
        boolean outV = appConfig.isOutputMirrorV(currentMirrorTarget, currentCameraPos);

        if (ctx != null) {
            UiOptionPillHelper.apply(btnPhysicalMirrorH, ctx, physH);
            UiOptionPillHelper.apply(btnPhysicalMirrorV, ctx, physV);
            UiOptionPillHelper.apply(btnTargetBlindSpot, ctx, currentMirrorTarget == MirrorTarget.BLIND_SPOT);
            UiOptionPillHelper.apply(btnTargetSecondaryHud, ctx, currentMirrorTarget == MirrorTarget.SECONDARY_HUD);
            UiOptionPillHelper.apply(btnTargetNetworkStream, ctx, currentMirrorTarget == MirrorTarget.NETWORK_STREAM);
            UiOptionPillHelper.apply(btnOutputMirrorH, ctx, outH);
            UiOptionPillHelper.apply(btnOutputMirrorV, ctx, outV);
            UiOptionPillHelper.apply(btnResetCurrent, ctx, false);
            UiOptionPillHelper.apply(btnSaveCorrection, ctx, true, true);
        }

        CameraMirrorPolicy.ResolvedMirror resolved =
                CameraMirrorPolicy.resolve(appConfig, currentMirrorTarget, currentCameraPos);
        tvMirrorEffectiveState.setText(String.format(
                Locale.US,
                "物理(%s/%s) + %s输出(%s/%s) → 实际生效(水平:%s, 垂直:%s)",
                physH ? "反" : "正",
                physV ? "倒" : "正",
                getTargetLabel(currentMirrorTarget),
                outH ? "反" : "正",
                outV ? "倒" : "正",
                resolved.effectiveH ? "镜像" : "正常",
                resolved.effectiveV ? "翻转" : "正常"));
    }

    private void updatePreviewButtonUi() {
        Context ctx = getContext();
        if (ctx == null || btnToggleFloatingPreview == null) return;
        btnToggleFloatingPreview.setText(isFloatingPreviewActive ? "关闭浮窗预览" : "开启浮窗实景预览");
        UiOptionPillHelper.apply(btnToggleFloatingPreview, ctx, isFloatingPreviewActive, true);
    }

    private void toggleFloatingPreview() {
        Context ctx = getContext();
        if (ctx == null) return;
        if (isFloatingPreviewActive) {
            isFloatingPreviewActive = false;
            stopPreview();
            updatePreviewButtonUi();
            return;
        }
        if (!WakeUpHelper.hasOverlayPermission(ctx)) {
            Toast.makeText(ctx, "请先授予悬浮窗权限，当前可通过预览图确认四边裁切范围", Toast.LENGTH_SHORT).show();
            return;
        }
        isFloatingPreviewActive = true;
        sendPreviewAction(currentCameraPos);
        updatePreviewButtonUi();
    }

    private void sendPreviewAction(String cameraPos) {
        Context ctx = getContext();
        if (ctx == null) return;
        Intent intent = new Intent(ctx, BlindSpotService.class);
        intent.putExtra("action", "preview_blind_spot");
        intent.putExtra("camera_pos", cameraPos);
        ctx.startService(intent);
    }

    private void stopPreview() {
        Context ctx = getContext();
        if (ctx == null) return;
        Intent intent = new Intent(ctx, BlindSpotService.class);
        intent.putExtra("action", "stop_preview_blind_spot");
        ctx.startService(intent);
    }

    private void notifyServiceUpdate() {
        Context ctx = getContext();
        if (ctx != null) {
            BlindSpotService.update(ctx);
        }
    }

    private String getCameraLabel(String pos) {
        if ("front".equals(pos)) return "前";
        if ("back".equals(pos)) return "后";
        if ("left".equals(pos)) return "左";
        if ("right".equals(pos)) return "右";
        return pos;
    }

    private String getTargetLabel(MirrorTarget target) {
        if (target == MirrorTarget.SECONDARY_HUD) return "副屏";
        if (target == MirrorTarget.NETWORK_STREAM) return "投屏";
        return "补盲窗";
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        updateCameraButtonsUi();
        updateCropAndRotationVisuals();
        updateMirrorUi();
        updatePreviewButtonUi();
    }

    @Override
    public void onDestroyView() {
        if (isFloatingPreviewActive) {
            isFloatingPreviewActive = false;
            stopPreview();
        }
        super.onDestroyView();
    }
}
