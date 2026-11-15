package com.kooo.evcam.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.UiThemeController.ThemeMode;

/**
 * 1. 外观与交互设置子页：
 * - 三态主题切换（跟随系统 / 深色 / 浅色，零重建原地重涂）
 * - 默认启动页选择（记录仪 / 回看 / 设置）
 * - confirm_door_exit 开关持久化（车门信号自动触发留待 T06 接入 VehicleSignalHub）
 */
public class AppearanceSettingsFragment extends Fragment implements UiThemeController.ThemeAware {

    private AppConfig appConfig;
    private TextView themeStatusText;
    private TextView startPageStatusText;
    private Button btnFollowSystem;
    private Button btnDark;
    private Button btnLight;
    private Button btnStartRecording;
    private Button btnStartPlayback;
    private Button btnStartSettings;
    private SwitchMaterial confirmDoorExitSwitch;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_appearance_settings, container, false);

        View btnBack = view.findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                if (getActivity() != null) {
                    getActivity().getSupportFragmentManager().popBackStack();
                }
            });
        }

        themeStatusText = view.findViewById(R.id.tv_theme_status);
        startPageStatusText = view.findViewById(R.id.tv_start_page_status);
        btnFollowSystem = view.findViewById(R.id.btn_theme_follow_system);
        btnDark = view.findViewById(R.id.btn_theme_dark);
        btnLight = view.findViewById(R.id.btn_theme_light);
        btnStartRecording = view.findViewById(R.id.btn_start_recording);
        btnStartPlayback = view.findViewById(R.id.btn_start_playback);
        btnStartSettings = view.findViewById(R.id.btn_start_settings);
        confirmDoorExitSwitch = view.findViewById(R.id.switch_confirm_door_exit);

        if (getContext() != null) {
            appConfig = new AppConfig(getContext());
        }

        btnFollowSystem.setOnClickListener(v -> selectTheme(ThemeMode.FOLLOW_SYSTEM));
        btnDark.setOnClickListener(v -> selectTheme(ThemeMode.DARK));
        btnLight.setOnClickListener(v -> selectTheme(ThemeMode.LIGHT));

        btnStartRecording.setOnClickListener(v -> selectStartPage(AppConfig.START_PAGE_RECORDING));
        btnStartPlayback.setOnClickListener(v -> selectStartPage(AppConfig.START_PAGE_PLAYBACK));
        btnStartSettings.setOnClickListener(v -> selectStartPage(AppConfig.START_PAGE_SETTINGS));

        if (appConfig != null && confirmDoorExitSwitch != null) {
            confirmDoorExitSwitch.setChecked(appConfig.isConfirmDoorExitEnabled());
            confirmDoorExitSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (appConfig != null && getContext() != null) {
                    appConfig.setConfirmDoorExitEnabled(isChecked);
                    Toast.makeText(getContext(),
                            isChecked ? "已开启开门退出二次确认（配置已保存，待 T06 接入信号）"
                                    : "已关闭开门退出二次确认",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (getContext() != null) {
            UiThemeController controller = resolveThemeController();
            boolean isNight = controller != null ? controller.isCurrentThemeNight()
                    : UiThemeController.isTargetNight(getContext(), null);
            refreshThemeUi(getContext(), isNight);
            refreshStartPageUi(getContext());
        }
        return view;
    }

    @Nullable
    private UiThemeController resolveThemeController() {
        if (getActivity() instanceof UiThemeController.Host) {
            return ((UiThemeController.Host) getActivity()).getUiThemeController();
        }
        return null;
    }

    private void selectTheme(@NonNull ThemeMode mode) {
        UiThemeController controller = resolveThemeController();
        if (controller != null) {
            controller.selectThemeMode(mode);
        } else if (getContext() != null) {
            UiThemeController.saveThemeMode(getContext(), mode);
            refreshThemeUi(getContext(), UiThemeController.isTargetNight(getContext(), null));
        }
    }

    private void selectStartPage(@NonNull String startPage) {
        if (appConfig == null || getContext() == null) return;
        appConfig.setDefaultStartPage(startPage);
        refreshStartPageUi(getContext());
        Toast.makeText(getContext(), "默认启动页已设为「" + formatStartPageLabel(startPage) + "」",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        if (themedContext == null || getView() == null) return;
        refreshThemeUi(themedContext, isNight);
        refreshStartPageUi(themedContext);
    }

    private void refreshThemeUi(@NonNull Context context, boolean isNight) {
        ThemeMode mode = UiThemeController.savedThemeMode(context);
        if (themeStatusText != null) {
            if (mode == ThemeMode.FOLLOW_SYSTEM) {
                UiThemeController controller = resolveThemeController();
                boolean systemNight = controller != null ? controller.isSystemCurrentlyNight() : isNight;
                themeStatusText.setText("当前：跟随系统（系统当前为" + (systemNight ? "深色）" : "浅色）"));
            } else {
                themeStatusText.setText("当前：固定为" + mode.label + "外观");
            }
        }
        applyOptionButtonState(btnFollowSystem, context, mode == ThemeMode.FOLLOW_SYSTEM);
        applyOptionButtonState(btnDark, context, mode == ThemeMode.DARK);
        applyOptionButtonState(btnLight, context, mode == ThemeMode.LIGHT);
    }

    private void refreshStartPageUi(@NonNull Context context) {
        String startPage = appConfig != null
                ? appConfig.getDefaultStartPage()
                : AppConfig.START_PAGE_RECORDING;
        if (startPageStatusText != null) {
            startPageStatusText.setText("当前默认页面：" + formatStartPageLabel(startPage));
        }
        applyOptionButtonState(btnStartRecording, context, AppConfig.START_PAGE_RECORDING.equals(startPage));
        applyOptionButtonState(btnStartPlayback, context, AppConfig.START_PAGE_PLAYBACK.equals(startPage));
        applyOptionButtonState(btnStartSettings, context, AppConfig.START_PAGE_SETTINGS.equals(startPage));
    }

    private void applyOptionButtonState(@Nullable Button button, @NonNull Context context, boolean selected) {
        if (button == null) return;
        int bgColor = ContextCompat.getColor(context, selected ? R.color.button_accent : R.color.button_background);
        int textColor = ContextCompat.getColor(context, selected ? R.color.ui_on_accent : R.color.button_text);
        ViewCompat.setBackgroundTintList(button, ColorStateList.valueOf(bgColor));
        button.setTextColor(textColor);
        button.setTag(R.id.ui_theme_bg_role, selected ? "accent" : null);
        button.setTag(R.id.ui_theme_text_role, selected ? "on_accent" : null);
        button.setSelected(selected);
    }

    @NonNull
    public static String formatStartPageLabel(@Nullable String startPage) {
        if (AppConfig.START_PAGE_PLAYBACK.equals(startPage)) return "回看";
        if (AppConfig.START_PAGE_SETTINGS.equals(startPage)) return "设置";
        return "记录仪";
    }
}
