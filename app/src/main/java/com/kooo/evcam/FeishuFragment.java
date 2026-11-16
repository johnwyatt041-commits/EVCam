package com.kooo.evcam;

import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.feishu.FeishuConfig;

/**
 * 11. 飞书 Bot 配置界面（支持总开关懒加载与 P 档门控管理）。
 */
public class FeishuFragment extends Fragment {
    private static final String TAG = "FeishuFragment";

    private EditText etAppId, etAppSecret, etAllowedUserIds;
    private Button btnSaveConfig, btnStartService, btnStopService, btnMenu, btnHome;
    private ImageButton btnToggleSecretVisibility;
    private TextView tvConnectionStatus;
    private SwitchCompat switchFeishuEnabled;
    private SwitchCompat switchPGearOnly;
    private boolean isSecretVisible = false;
    private boolean updatingUi = false;

    private FeishuConfig config;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_feishu, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        loadConfig();
        setupListeners();
        SettingsFragment.configureEmbeddedToolbar(this, view, "11. 飞书远程");
    }

    private void initViews(View view) {
        btnMenu = view.findViewById(R.id.btn_menu);
        btnHome = view.findViewById(R.id.btn_home);
        btnToggleSecretVisibility = view.findViewById(R.id.btn_toggle_secret_visibility);
        etAppId = view.findViewById(R.id.et_app_id);
        etAppSecret = view.findViewById(R.id.et_app_secret);
        etAllowedUserIds = view.findViewById(R.id.et_allowed_user_ids);
        btnSaveConfig = view.findViewById(R.id.btn_save_config);
        btnStartService = view.findViewById(R.id.btn_start_service);
        btnStopService = view.findViewById(R.id.btn_stop_service);
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status);
        switchFeishuEnabled = view.findViewById(R.id.switch_auto_start);
        switchPGearOnly = view.findViewById(R.id.switch_p_gear_only);
        config = new FeishuConfig(requireContext());
    }

    private void loadConfig() {
        updatingUi = true;
        try {
            if (config.isConfigured()) {
                etAppId.setText(config.getAppId());
                etAppSecret.setText(config.getAppSecret());
                etAllowedUserIds.setText(config.getAllowedUserIds());
            }
            if (switchFeishuEnabled != null) {
                switchFeishuEnabled.setChecked(config.isEnabled());
            }
            if (switchPGearOnly != null) {
                switchPGearOnly.setChecked(config.isPGearOnly());
            }
        } finally {
            updatingUi = false;
        }
    }

    private void setupListeners() {
        if (btnMenu != null) {
            btnMenu.setVisibility(View.GONE);
        }

        btnHome.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });

        btnToggleSecretVisibility.setOnClickListener(v -> {
            isSecretVisible = !isSecretVisible;
            if (isSecretVisible) {
                etAppSecret.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
                btnToggleSecretVisibility.setImageResource(R.drawable.ic_visibility_off);
            } else {
                etAppSecret.setTransformationMethod(PasswordTransformationMethod.getInstance());
                btnToggleSecretVisibility.setImageResource(R.drawable.ic_visibility);
            }
            etAppSecret.setSelection(etAppSecret.getText().length());
        });

        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnStartService.setOnClickListener(v -> startService());
        btnStopService.setOnClickListener(v -> stopService());

        if (switchFeishuEnabled != null) {
            switchFeishuEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (updatingUi) {
                    return;
                }
                if (isChecked && !config.isConfigured()) {
                    updatingUi = true;
                    switchFeishuEnabled.setChecked(false);
                    updatingUi = false;
                    Toast.makeText(requireContext(), "请先填写并保存 App ID 与 App Secret", Toast.LENGTH_SHORT).show();
                    return;
                }
                config.setEnabled(isChecked);
                notifyConfigChanged();
                Toast.makeText(requireContext(),
                        isChecked
                                ? (config.isPGearOnly() ? "已开启飞书远程（仅 P 档按需连接）" : "已开启飞书远程")
                                : "已关闭飞书远程（彻底断连且零后台初始化）",
                        Toast.LENGTH_SHORT).show();
            });
        }

        if (switchPGearOnly != null) {
            switchPGearOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (updatingUi) {
                    return;
                }
                config.setPGearOnly(isChecked);
                notifyConfigChanged();
                Toast.makeText(requireContext(),
                        isChecked ? "已启用 P 档门控：切离 P 档将自动彻底断连" : "已关闭 P 档限制：将保持常驻连接",
                        Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void notifyConfigChanged() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).notifyFeishuConfigChanged();
        }
        updateServiceStatus();
    }

    private void saveConfig() {
        String appId = etAppId.getText().toString().trim();
        String appSecret = etAppSecret.getText().toString().trim();
        String allowedUserIds = etAllowedUserIds.getText().toString().trim();

        if (appId.isEmpty()) {
            Toast.makeText(requireContext(), "请填写 App ID", Toast.LENGTH_SHORT).show();
            return;
        }
        if (appSecret.isEmpty()) {
            Toast.makeText(requireContext(), "请填写 App Secret", Toast.LENGTH_SHORT).show();
            return;
        }

        config.saveConfig(appId, appSecret, allowedUserIds);
        Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show();
        notifyConfigChanged();
    }

    private void startService() {
        if (!config.isConfigured()) {
            Toast.makeText(requireContext(), "请先保存配置", Toast.LENGTH_SHORT).show();
            return;
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).startFeishuService();
        }
        syncSwitchesFromConfig();
        updateServiceStatus();
    }

    private void stopService() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).stopFeishuService();
        }
        syncSwitchesFromConfig();
        updateServiceStatus();
    }

    private void syncSwitchesFromConfig() {
        updatingUi = true;
        try {
            if (switchFeishuEnabled != null) {
                switchFeishuEnabled.setChecked(config.isEnabled());
            }
            if (switchPGearOnly != null) {
                switchPGearOnly.setChecked(config.isPGearOnly());
            }
        } finally {
            updatingUi = false;
        }
    }

    /**
     * 更新服务与 P 档门控状态显示（由 MainActivity 或自身生命周期调用，无副作用）。
     */
    public void updateServiceStatus() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        syncSwitchesFromConfig();
        boolean isRunning = RemoteServiceManager.hasInitializedInstance()
                && RemoteServiceManager.getInstance().isFeishuRunning();
        boolean isStarting = RemoteServiceManager.hasInitializedInstance()
                && RemoteServiceManager.getInstance().isFeishuStartingOrRunning();

        String statusLabel = RemoteServiceManager.describeCurrentFeishuStatus(requireContext());
        tvConnectionStatus.setText(statusLabel);
        if (isRunning) {
            tvConnectionStatus.setTextColor(0xFF66FF66);
        } else if (isStarting || (config.isEnabled() && config.isConfigured())) {
            tvConnectionStatus.setTextColor(0xFFFFCC66);
        } else {
            tvConnectionStatus.setTextColor(0xFFFF6666);
        }

        btnStartService.setEnabled(config.isConfigured() && !isRunning);
        btnStopService.setEnabled(config.isEnabled() || isRunning || isStarting);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateServiceStatus();
    }
}
