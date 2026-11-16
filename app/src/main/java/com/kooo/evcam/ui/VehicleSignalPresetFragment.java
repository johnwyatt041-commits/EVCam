package com.kooo.evcam.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.BlindSpotService;
import com.kooo.evcam.CarSignalManagerObserver;
import com.kooo.evcam.R;
import com.kooo.evcam.SettingsFragment;
import com.kooo.evcam.VhalSignalObserver;
import com.kooo.evcam.vehicle.RecordingVehicleSource;
import com.kooo.evcam.vehicle.VehicleAutomationCoordinator;
import com.kooo.evcam.vehicle.VehicleGearStatePublisher;
import com.kooo.evcam.vehicle.VehicleSignalCategory;
import com.kooo.evcam.vehicle.VehicleSignalHub;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 10. 信号预设与匹配：本地化 E5 四类信号预设、连接测试、实时快照匹配诊断与属性 ID 校准。
 */
public class VehicleSignalPresetFragment extends Fragment {
    private AppConfig appConfig;

    private RadioGroup sourceGroup;
    private TextView tvConnectionStatus;
    private TextView tvLiveGear;
    private TextView tvLiveSpeed;
    private TextView tvLiveDoors;
    private TextView tvLiveTurn;
    private TextView tvLiveUpdated;

    private EditText etGearSelection;
    private EditText etCurrentGear;
    private EditText etSpeed;
    private EditText etDoorPos;
    private EditText etTurnSignal;

    private boolean isUpdatingUi = false;
    @Nullable
    private RecordingVehicleSource.Lease diagnosticLease;

    private final VehicleSignalHub.Listener hubListener = snapshot -> {
        if (getActivity() != null && isAdded()) {
            getActivity().runOnUiThread(() -> renderSnapshot(snapshot));
        }
    };

    private final VehicleGearStatePublisher.GearStateListener gearStateListener = state -> {
        if (getActivity() != null && isAdded()) {
            getActivity().runOnUiThread(() -> renderSnapshot(VehicleSignalHub.getInstance().getSnapshot()));
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_vehicle_signal_preset, container, false);
        Context ctx = requireContext();
        appConfig = new AppConfig(ctx);

        initViews(view);
        loadSettingsIntoViews();
        bindListeners(view);
        SettingsFragment.configureEmbeddedToolbar(this, view, "信号预设与匹配");
        return view;
    }

    private void initViews(@NonNull View view) {
        sourceGroup = view.findViewById(R.id.rg_signal_preset_source);
        tvConnectionStatus = view.findViewById(R.id.tv_signal_connection_status);
        tvLiveGear = view.findViewById(R.id.tv_live_signal_gear);
        tvLiveSpeed = view.findViewById(R.id.tv_live_signal_speed);
        tvLiveDoors = view.findViewById(R.id.tv_live_signal_doors);
        tvLiveTurn = view.findViewById(R.id.tv_live_signal_turn);
        tvLiveUpdated = view.findViewById(R.id.tv_live_signal_updated);

        etGearSelection = view.findViewById(R.id.et_prop_gear_selection);
        etCurrentGear = view.findViewById(R.id.et_prop_current_gear);
        etSpeed = view.findViewById(R.id.et_prop_speed);
        etDoorPos = view.findViewById(R.id.et_prop_door_pos);
        etTurnSignal = view.findViewById(R.id.et_prop_turn_signal);
    }

    private void loadSettingsIntoViews() {
        isUpdatingUi = true;
        try {
            if (appConfig.isVhalGrpcTriggerMode()) {
                sourceGroup.check(R.id.rb_signal_source_vhal);
            } else if (appConfig.isCarSignalManagerTriggerMode()) {
                sourceGroup.check(R.id.rb_signal_source_car_signal);
            } else {
                sourceGroup.check(R.id.rb_signal_source_logcat);
            }
            fillPropertyIdFields();
        } finally {
            isUpdatingUi = false;
        }
    }

    private void fillPropertyIdFields() {
        etGearSelection.setText(VehicleSignalCategory.formatPropertyId(appConfig.getCustomGearSelectionPropId()));
        etCurrentGear.setText(VehicleSignalCategory.formatPropertyId(appConfig.getCustomCurrentGearPropId()));
        etSpeed.setText(VehicleSignalCategory.formatPropertyId(appConfig.getCustomSpeedPropId()));
        etDoorPos.setText(VehicleSignalCategory.formatPropertyId(appConfig.getCustomDoorPosPropId()));
        etTurnSignal.setText(VehicleSignalCategory.formatPropertyId(appConfig.getCustomTurnSignalPropId()));
    }

    private void bindListeners(@NonNull View view) {
        sourceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (isUpdatingUi || getContext() == null) return;
            String nextMode;
            if (checkedId == R.id.rb_signal_source_car_signal) {
                nextMode = AppConfig.TRIGGER_MODE_CAR_SIGNAL_MANAGER;
            } else if (checkedId == R.id.rb_signal_source_logcat) {
                nextMode = AppConfig.TRIGGER_MODE_LOGCAT;
            } else {
                nextMode = AppConfig.TRIGGER_MODE_VHAL_GRPC;
            }
            appConfig.setTurnSignalTriggerMode(nextMode);
            syncDiagnosticLease();
            VehicleAutomationCoordinator.getInstance().startOrReload(getContext());
            BlindSpotService.update(getContext());
            testSignalConnectionAsync();
        });

        Button btnTest = view.findViewById(R.id.btn_test_signal_connection);
        btnTest.setOnClickListener(v -> testSignalConnectionAsync());

        Button btnGotoRules = view.findViewById(R.id.btn_goto_recorder_rules);
        btnGotoRules.setOnClickListener(v ->
                SettingsFragment.navigateToCategory(this, SettingsCatalog.ID_RECORDER));

        Button btnSaveMapping = view.findViewById(R.id.btn_save_signal_mapping);
        btnSaveMapping.setOnClickListener(v -> savePropertyMapping());

        Button btnResetMapping = view.findViewById(R.id.btn_reset_signal_mapping);
        btnResetMapping.setOnClickListener(v -> {
            appConfig.resetCustomVhalPropertyIds();
            fillPropertyIdFields();
            if (getContext() != null) {
                VehicleAutomationCoordinator.getInstance().startOrReload(getContext());
                Toast.makeText(getContext(), "已恢复银河 E5 默认 4 类信号映射", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        syncDiagnosticLease();
        VehicleSignalHub.getInstance().addListener(hubListener);
        VehicleSignalHub.getInstance().getGearStatePublisher().addListener(gearStateListener);
        renderSnapshot(VehicleSignalHub.getInstance().getSnapshot());
        testSignalConnectionAsync();
    }

    @Override
    public void onPause() {
        super.onPause();
        VehicleSignalHub.getInstance().removeListener(hubListener);
        VehicleSignalHub.getInstance().getGearStatePublisher().removeListener(gearStateListener);
        releaseDiagnosticLease();
    }

    private void syncDiagnosticLease() {
        if (appConfig != null && appConfig.isVhalGrpcTriggerMode()) {
            if (diagnosticLease == null) {
                diagnosticLease = RecordingVehicleSource.acquire();
            }
        } else {
            releaseDiagnosticLease();
        }
    }

    private void releaseDiagnosticLease() {
        if (diagnosticLease != null) {
            diagnosticLease.close();
            diagnosticLease = null;
        }
    }

    private void savePropertyMapping() {
        Context ctx = getContext();
        if (ctx == null) return;
        Integer gearSelection = VehicleSignalCategory.parsePropertyId(etGearSelection.getText().toString());
        Integer currentGear = VehicleSignalCategory.parsePropertyId(etCurrentGear.getText().toString());
        Integer speed = VehicleSignalCategory.parsePropertyId(etSpeed.getText().toString());
        Integer doorPos = VehicleSignalCategory.parsePropertyId(etDoorPos.getText().toString());
        Integer turnSignal = VehicleSignalCategory.parsePropertyId(etTurnSignal.getText().toString());

        if (gearSelection == null || currentGear == null || speed == null
                || doorPos == null || turnSignal == null) {
            Toast.makeText(ctx, "属性 ID 格式无效，请填写十六进制（如 0x11400400）或正整数", Toast.LENGTH_LONG).show();
            return;
        }
        appConfig.setCustomVhalPropertyIds(gearSelection, currentGear, speed, doorPos, turnSignal);
        fillPropertyIdFields();
        VehicleAutomationCoordinator.getInstance().startOrReload(ctx);
        VhalSignalObserver running = VhalSignalObserver.findRunningObserverExcept(null);
        if (running != null) {
            running.requestPropertySnapshot();
        }
        Toast.makeText(ctx, "4 类信号属性 ID 映射已保存并应用", Toast.LENGTH_SHORT).show();
    }

    private void testSignalConnectionAsync() {
        Context ctx = getContext();
        if (ctx == null || tvConnectionStatus == null) return;
        tvConnectionStatus.setText("连接状态：检测中...");
        tvConnectionStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));

        new Thread(() -> {
            String text;
            boolean ok;
            if (appConfig.isVhalGrpcTriggerMode()) {
                ok = VhalSignalObserver.testConnection();
                if (ok) {
                    VhalSignalObserver running = VhalSignalObserver.findRunningObserverExcept(null);
                    if (running != null) {
                        running.requestPropertySnapshot();
                    }
                    text = "连接状态：✓ E5 VHAL gRPC 服务可达";
                } else {
                    text = "连接状态：✗ VHAL gRPC 服务不可达（离线或模拟环境）";
                }
            } else if (appConfig.isCarSignalManagerTriggerMode()) {
                ok = CarSignalManagerObserver.testConnection(ctx);
                text = ok ? "连接状态：✓ CarSignalManager 服务可达" : "连接状态：✗ CarSignalManager 不可达";
            } else {
                ok = true;
                text = "连接状态：✓ Logcat 日志匹配模式已就绪（仅转向灯）";
            }

            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    if (tvConnectionStatus == null) return;
                    tvConnectionStatus.setText(text);
                    tvConnectionStatus.setTextColor(ok ? 0xFF4CAF50 : 0xFFF44336);
                    renderSnapshot(VehicleSignalHub.getInstance().getSnapshot());
                });
            }
        }).start();
    }

    private void renderSnapshot(@Nullable VehicleSignalSnapshot snapshot) {
        if (snapshot == null || tvLiveGear == null) return;
        VehicleGearStatePublisher.GearState gearState =
                VehicleSignalHub.getInstance().getGearStatePublisher().getCurrentState();

        String rawGearLabel = formatGear(snapshot.gear);
        String confirmedTag = gearState.confirmed
                ? "已去抖确认: " + formatGear(gearState.stableGear)
                : "待确认";
        tvLiveGear.setText("当前档位：" + rawGearLabel + "（" + confirmedTag + "）");
        tvLiveSpeed.setText("实时车速：" + (snapshot.speedKmh >= 0 ? snapshot.speedKmh + " km/h" : "未知"));
        tvLiveDoors.setText("四车门状态：左前 " + formatDoor(snapshot.doorFl)
                + " | 右前 " + formatDoor(snapshot.doorFr)
                + " | 左后 " + formatDoor(snapshot.doorRl)
                + " | 右后 " + formatDoor(snapshot.doorRr));
        tvLiveTurn.setText("转向灯状态：" + formatTurn(snapshot.leftTurn, snapshot.rightTurn));
        if (snapshot.updatedAtMs > 0L) {
            String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(snapshot.updatedAtMs));
            tvLiveUpdated.setText("最近信号帧：" + time);
        } else {
            tvLiveUpdated.setText("最近信号帧：尚未收到");
        }
    }

    private static String formatGear(@NonNull VehicleSignalSnapshot.Gear gear) {
        switch (gear) {
            case PARK:
                return "P 挡 (PARK)";
            case REVERSE:
                return "R 挡 (REVERSE)";
            case NEUTRAL:
                return "N 挡 (NEUTRAL)";
            case DRIVE:
                return "D 挡 (DRIVE)";
            case OTHER:
                return "其他挡位";
            case UNKNOWN:
            default:
                return "未知";
        }
    }

    private static String formatDoor(int state) {
        if (state == 1) return "开";
        if (state == 0) return "关";
        return "未知";
    }

    private static String formatTurn(int left, int right) {
        if (left < 0 && right < 0) return "未知";
        if (left == 1 && right == 1) return "双闪开启";
        if (left == 1) return "左转向灯";
        if (right == 1) return "右转向灯";
        return "熄灭";
    }
}
