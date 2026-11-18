package com.kooo.evcam;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import com.kooo.evcam.ui.UiScaleController;
import com.kooo.evcam.ui.UiScaleHelper;
import com.kooo.evcam.ui.UiThemeController;

import java.util.List;
import java.util.Locale;

/**
 * 13. 系统与权限设置页：
 * 集中展示核心/保障/可选权限诊断清单、开机自启动开关、使用指南、本地 ADB 一键授权与车机系统后台白名单。
 */
public class PermissionSettingsFragment extends Fragment implements UiThemeController.ThemeAware {

    private AppConfig appConfig;
    private AdbPermissionHelper adbHelper;
    private SystemWhitelistHelper whitelistHelper;

    private TextView tvSummaryHeadline;
    private TextView tvSummaryDetail;
    private Button btnRequestMissingRuntime;
    private Button btnRefreshPermissions;
    private SwitchMaterial switchAutoStart;
    private Button btnUsageGuide;
    private LinearLayout layoutRuntimePermissions;
    private LinearLayout layoutSpecialPermissions;

    private Button btnAdbGrantAll;
    private ScrollView scrollAdbLog;
    private TextView tvAdbLog;

    private TextView tvWhitelistStatus;
    private Button btnSystemWhitelist;
    private ScrollView scrollWhitelistLog;
    private TextView tvWhitelistLog;
    private Button btnRestoreWhitelist;
    private ScrollView scrollRestoreLog;
    private TextView tvRestoreLog;

    private final StringBuilder adbLogBuffer = new StringBuilder();
    private final StringBuilder whitelistLogBuffer = new StringBuilder();
    private final StringBuilder restoreLogBuffer = new StringBuilder();

    private boolean isUpdatingAutoStartSwitch = false;
    private boolean isAdbRunning = false;
    private boolean isWhitelistRunning = false;

    @Nullable
    private PermissionDiagnostics.DiagnosticReport latestReport;

    private final ActivityResultLauncher<String[]> runtimePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (!isAdded() || getContext() == null) {
                    return;
                }
                refreshDiagnostics(getContext());
                PermissionDiagnostics.DiagnosticReport report = latestReport;
                if (report != null && report.missingRuntimePermissions.isEmpty()) {
                    Toast.makeText(getContext(), "常规运行权限已就绪", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getContext(), "已更新常规权限状态", Toast.LENGTH_SHORT).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_permission_settings, container, false);
        Context ctx = inflater.getContext();
        appConfig = new AppConfig(ctx);
        adbHelper = new AdbPermissionHelper(ctx);
        whitelistHelper = new SystemWhitelistHelper(ctx);

        View btnBack = view.findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> SettingsFragment.popSubPage(this));
        }

        bindViews(view);
        wireListeners(ctx);
        loadAutoStartSwitch();
        refreshDiagnostics(ctx);
        SettingsFragment.configureEmbeddedToolbar(this, view, "系统与权限");
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isAdded() && getContext() != null && getView() != null) {
            loadAutoStartSwitch();
            refreshDiagnostics(getContext());
        }
    }

    @Override
    public void onDestroyView() {
        if (adbHelper != null) {
            adbHelper.cancel();
        }
        isAdbRunning = false;
        isWhitelistRunning = false;
        super.onDestroyView();
    }

    @Override
    public void onThemeApplied(Context themedContext, boolean isNight) {
        if (!isAdded() || themedContext == null || getView() == null) {
            return;
        }
        refreshDiagnostics(themedContext);
    }

    private void bindViews(@NonNull View root) {
        tvSummaryHeadline = root.findViewById(R.id.tv_permission_summary_headline);
        tvSummaryDetail = root.findViewById(R.id.tv_permission_summary_detail);
        btnRequestMissingRuntime = root.findViewById(R.id.btn_request_missing_runtime);
        btnRefreshPermissions = root.findViewById(R.id.btn_refresh_permissions);
        switchAutoStart = root.findViewById(R.id.switch_auto_start);
        btnUsageGuide = root.findViewById(R.id.btn_usage_guide);
        layoutRuntimePermissions = root.findViewById(R.id.layout_runtime_permissions);
        layoutSpecialPermissions = root.findViewById(R.id.layout_special_permissions);

        btnAdbGrantAll = root.findViewById(R.id.btn_adb_grant_all);
        scrollAdbLog = root.findViewById(R.id.scroll_adb_log);
        tvAdbLog = root.findViewById(R.id.tv_adb_log);

        tvWhitelistStatus = root.findViewById(R.id.tv_whitelist_status);
        btnSystemWhitelist = root.findViewById(R.id.btn_system_whitelist);
        scrollWhitelistLog = root.findViewById(R.id.scroll_whitelist_log);
        tvWhitelistLog = root.findViewById(R.id.tv_whitelist_log);
        btnRestoreWhitelist = root.findViewById(R.id.btn_restore_whitelist);
        scrollRestoreLog = root.findViewById(R.id.scroll_restore_log);
        tvRestoreLog = root.findViewById(R.id.tv_restore_log);
    }

    private void wireListeners(@NonNull Context ctx) {
        btnRequestMissingRuntime.setOnClickListener(v -> {
            PermissionDiagnostics.DiagnosticReport report = latestReport;
            if (report == null || report.missingRuntimePermissions.isEmpty()) {
                Toast.makeText(requireContext(), "所有常规运行权限均已授予", Toast.LENGTH_SHORT).show();
                return;
            }
            runtimePermissionLauncher.launch(
                    report.missingRuntimePermissions.toArray(new String[0]));
        });

        btnRefreshPermissions.setOnClickListener(v -> {
            if (getContext() != null) {
                refreshDiagnostics(getContext());
                Toast.makeText(getContext(), "已重新检测当前系统权限状态", Toast.LENGTH_SHORT).show();
            }
        });

        switchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingAutoStartSwitch || appConfig == null) {
                return;
            }
            appConfig.setAutoStartOnBoot(isChecked);
            Toast.makeText(buttonView.getContext(),
                    isChecked ? "已开启开机自启动" : "已关闭开机自启动",
                    Toast.LENGTH_SHORT).show();
        });

        btnUsageGuide.setOnClickListener(v -> showUsageGuideDialog());
        btnAdbGrantAll.setOnClickListener(v -> confirmAndRunAdbGrant());
        btnSystemWhitelist.setOnClickListener(v -> confirmAndRunWhitelistSetup());
        btnRestoreWhitelist.setOnClickListener(v -> confirmAndRunWhitelistRestore());
    }

    private void loadAutoStartSwitch() {
        if (switchAutoStart == null || appConfig == null) {
            return;
        }
        isUpdatingAutoStartSwitch = true;
        switchAutoStart.setChecked(appConfig.isAutoStartOnBoot());
        isUpdatingAutoStartSwitch = false;
    }

    private void refreshDiagnostics(@NonNull Context ctx) {
        if (getView() == null) {
            return;
        }
        PermissionDiagnostics.EnvironmentSnapshot env = PermissionDiagnostics.collectEnvironment(ctx);
        PermissionDiagnostics.DiagnosticReport report = PermissionDiagnostics.evaluate(env);
        latestReport = report;

        tvSummaryHeadline.setText(report.buildHeadlineText());
        tvSummaryHeadline.setTextColor(ContextCompat.getColor(ctx,
                report.coreReady < report.coreTotal
                        ? R.color.ui_danger
                        : (report.recommendedReady < report.recommendedTotal
                        ? R.color.ui_warning
                        : R.color.ui_success)));

        tvSummaryDetail.setText(String.format(
                Locale.CHINA,
                "Android API %d · 核心必需 %d/%d · 推荐保障 %d/%d · 按需可选 %d/%d",
                env.sdkInt,
                report.coreReady, report.coreTotal,
                report.recommendedReady, report.recommendedTotal,
                report.optionalReady, report.optionalTotal));

        boolean hasMissingRuntime = !report.missingRuntimePermissions.isEmpty();
        btnRequestMissingRuntime.setEnabled(hasMissingRuntime);
        btnRequestMissingRuntime.setAlpha(hasMissingRuntime ? 1.0f : 0.55f);
        btnRequestMissingRuntime.setText(hasMissingRuntime
                ? "一键申请缺失常规权限 (" + report.missingRuntimePermissions.size() + ")"
                : "常规运行权限已齐");

        layoutRuntimePermissions.removeAllViews();
        layoutSpecialPermissions.removeAllViews();

        List<PermissionDiagnostics.DiagnosticItem> items = report.items;
        for (int i = 0; i < items.size(); i++) {
            PermissionDiagnostics.DiagnosticItem item = items.get(i);
            View row = buildDiagnosticRow(ctx, item);
            if (item.category == PermissionDiagnostics.Category.RUNTIME) {
                layoutRuntimePermissions.addView(row);
            } else {
                layoutSpecialPermissions.addView(row);
            }
        }
    }

    @NonNull
    private View buildDiagnosticRow(@NonNull Context ctx,
                                    @NonNull PermissionDiagnostics.DiagnosticItem item) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 10));
        bg.setColor(ContextCompat.getColor(ctx, R.color.input_background));
        bg.setStroke(1, ContextCompat.getColor(ctx, R.color.ui_border));
        row.setBackground(bg);

        int padH = dp(ctx, 12);
        int padV = dp(ctx, 10);
        row.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 8);
        row.setLayoutParams(lp);

        // 第一行：标题 + 优先级徽章 + 状态标签
        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(item.title);
        tvTitle.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(tvTitle);

        TextView tvPriority = new TextView(ctx);
        tvPriority.setText(item.priority.label);
        tvPriority.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvPriority.setTextColor(ContextCompat.getColor(ctx,
                item.priority == PermissionDiagnostics.Priority.CORE
                        ? R.color.ui_option_pill_selected_text
                        : R.color.text_secondary));
        GradientDrawable prioBg = new GradientDrawable();
        prioBg.setCornerRadius(dp(ctx, 6));
        prioBg.setColor(ContextCompat.getColor(ctx,
                item.priority == PermissionDiagnostics.Priority.CORE
                        ? R.color.ui_accent_soft
                        : R.color.page_background));
        prioBg.setStroke(1, ContextCompat.getColor(ctx, R.color.ui_border));
        tvPriority.setBackground(prioBg);
        tvPriority.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
        LinearLayout.LayoutParams prioLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prioLp.setMarginEnd(dp(ctx, 6));
        tvPriority.setLayoutParams(prioLp);
        topRow.addView(tvPriority);

        int statusColorRes;
        if (item.status == PermissionDiagnostics.Status.GRANTED
                || item.status == PermissionDiagnostics.Status.NOT_REQUIRED) {
            statusColorRes = R.color.ui_success;
        } else if (item.status == PermissionDiagnostics.Status.PARTIAL) {
            statusColorRes = R.color.ui_warning;
        } else {
            statusColorRes = R.color.ui_danger;
        }
        TextView tvStatus = new TextView(ctx);
        tvStatus.setText(item.status == PermissionDiagnostics.Status.GRANTED
                ? "已就绪"
                : (item.status == PermissionDiagnostics.Status.PARTIAL ? "部分就绪" : "待处理"));
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvStatus.setTextColor(ContextCompat.getColor(ctx, statusColorRes));
        tvStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        topRow.addView(tvStatus);

        row.addView(topRow);

        // 第二行：说明 + 动作按钮
        LinearLayout bottomRow = new LinearLayout(ctx);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomLp.topMargin = dp(ctx, 6);
        bottomRow.setLayoutParams(bottomLp);

        LinearLayout descCol = new LinearLayout(ctx);
        descCol.setOrientation(LinearLayout.VERTICAL);
        descCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvDetail = new TextView(ctx);
        tvDetail.setText(item.statusDetail);
        tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvDetail.setTextColor(ContextCompat.getColor(ctx, statusColorRes));
        descCol.addView(tvDetail);

        TextView tvPurpose = new TextView(ctx);
        tvPurpose.setText(item.purpose);
        tvPurpose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvPurpose.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        LinearLayout.LayoutParams purposeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        purposeLp.topMargin = dp(ctx, 2);
        tvPurpose.setLayoutParams(purposeLp);
        descCol.addView(tvPurpose);

        bottomRow.addView(descCol);

        Button btnAction = new Button(ctx);
        btnAction.setText(item.actionLabel);
        btnAction.setAllCaps(false);
        btnAction.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                ctx.getResources().getDimension(R.dimen.ui_pill_text_size));
        int minWidthPx = Math.max(dp(ctx, 78),
                ctx.getResources().getDimensionPixelSize(R.dimen.ui_button_min_width));
        int minHeightPx = ctx.getResources().getDimensionPixelSize(R.dimen.ui_pill_compact_height);
        btnAction.setMinWidth(minWidthPx);
        btnAction.setMinHeight(minHeightPx);
        btnAction.setMinimumHeight(minHeightPx);
        boolean ready = item.isReady();
        btnAction.setEnabled(!ready);
        btnAction.setAlpha(ready ? 0.55f : 1.0f);
        btnAction.setBackgroundTintList(ContextCompat.getColorStateList(ctx,
                ready ? R.color.button_background : R.color.button_accent));
        btnAction.setTextColor(ContextCompat.getColor(ctx,
                ready ? R.color.button_text : R.color.ui_on_accent));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMarginStart(dp(ctx, 10));
        btnAction.setLayoutParams(btnLp);
        UiScaleHelper.applyScaleToButton(btnAction, UiScaleController.getSavedScaleFactor(ctx));
        if (!ready) {
            btnAction.setOnClickListener(v -> handleDiagnosticAction(item));
        }
        bottomRow.addView(btnAction);

        row.addView(bottomRow);
        return row;
    }

    private void handleDiagnosticAction(@NonNull PermissionDiagnostics.DiagnosticItem item) {
        if (!isAdded() || getContext() == null) {
            return;
        }
        Context ctx = getContext();
        switch (item.actionKind) {
            case RUNTIME_REQUEST:
                if (!item.runtimePermissions.isEmpty()) {
                    runtimePermissionLauncher.launch(item.runtimePermissions.toArray(new String[0]));
                }
                break;
            case NOTIFICATION_SETTINGS:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
                    launchSettingsIntentWithFallback(ctx, intent);
                } else {
                    openAppDetailsSettings(ctx);
                }
                break;
            case OVERLAY_SETTINGS:
                try {
                    WakeUpHelper.requestOverlayPermission(ctx);
                } catch (Exception e) {
                    openAppDetailsSettings(ctx);
                }
                break;
            case ALL_FILES_SETTINGS:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + ctx.getPackageName()));
                    launchSettingsIntentWithFallback(ctx, intent);
                } else {
                    openAppDetailsSettings(ctx);
                }
                break;
            case ACCESSIBILITY_SETTINGS:
                launchSettingsIntentWithFallback(ctx, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                break;
            case BATTERY_SETTINGS:
                try {
                    WakeUpHelper.requestIgnoreBatteryOptimizations(ctx);
                } catch (Exception e) {
                    try {
                        WakeUpHelper.openBatteryOptimizationSettings(ctx);
                    } catch (Exception ignored) {
                        openAppDetailsSettings(ctx);
                    }
                }
                break;
            case USAGE_STATS_SETTINGS:
                launchSettingsIntentWithFallback(ctx, new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                break;
            case ADB_REQUIRED:
                confirmAndRunAdbGrant();
                break;
            case APP_SETTINGS:
            default:
                openAppDetailsSettings(ctx);
                break;
        }
    }

    private void launchSettingsIntentWithFallback(@NonNull Context ctx, @NonNull Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            openAppDetailsSettings(ctx);
        }
    }

    private void openAppDetailsSettings(@NonNull Context ctx) {
        try {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(details);
        } catch (Exception e) {
            Toast.makeText(ctx, "无法打开系统设置页，请尝试使用 ADB 一键授权", Toast.LENGTH_LONG).show();
        }
    }

    private void showUsageGuideDialog() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        Context ctx = getContext();
        View dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_first_launch_guide, null);
        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                        .setView(dialogView)
                        .create();
        View btnConfirm = dialogView.findViewById(R.id.btn_confirm);
        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> dialog.dismiss());
        }
        dialog.show();
    }

    private void confirmAndRunAdbGrant() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        if (isAdbRunning) {
            Toast.makeText(getContext(), "ADB 授权任务正在执行中…", Toast.LENGTH_SHORT).show();
            return;
        }
        Context ctx = getContext();
        new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("ADB 一键授权")
                .setMessage("将连接本机 ADB（localhost:5555）为 EVCam 授予相机、存储/全盘访问、悬浮窗、通知、使用情况、电池白名单、READ_LOGS 与无障碍服务。\n\n"
                        + "请确认车机已开启无线/网络 ADB 调试，并在弹窗时点击允许。")
                .setPositiveButton("开始授权", (d, w) -> startAdbGrant())
                .setNegativeButton("取消", null)
                .show();
    }

    private void startAdbGrant() {
        if (!isAdded() || getView() == null || adbHelper == null) {
            return;
        }
        isAdbRunning = true;
        btnAdbGrantAll.setEnabled(false);
        btnAdbGrantAll.setText("正在执行 ADB 授权…");
        adbLogBuffer.setLength(0);
        tvAdbLog.setText("");
        scrollAdbLog.setVisibility(View.VISIBLE);

        adbHelper.grantAllPermissions(new AdbPermissionHelper.Callback() {
            @Override
            public void onLog(String message) {
                if (!isAdded() || getView() == null) {
                    return;
                }
                appendLogLine(adbLogBuffer, tvAdbLog, scrollAdbLog, message);
            }

            @Override
            public void onComplete(boolean allSuccess) {
                if (!isAdded() || getView() == null) {
                    return;
                }
                isAdbRunning = false;
                btnAdbGrantAll.setEnabled(true);
                btnAdbGrantAll.setText("ADB 一键授予全部权限");
                if (getContext() != null) {
                    refreshDiagnostics(getContext());
                    Toast.makeText(getContext(),
                            allSuccess ? "ADB 一键授权完成" : "ADB 授权部分完成，请查看下方日志",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void confirmAndRunWhitelistSetup() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        if (isWhitelistRunning) {
            Toast.makeText(getContext(), "后台白名单任务正在执行中…", Toast.LENGTH_SHORT).show();
            return;
        }
        Context ctx = getContext();
        new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("⚠️ 高风险系统配置")
                .setMessage("加入车机系统后台白名单会通过 ADB 修改系统级后台进程限制策略并自动备份原配置。\n\n"
                        + "若执行异常可能导致车机策略变动，如需撤销可使用下方「还原白名单系统备份」。是否继续？")
                .setPositiveButton("确认加入", (d, w) -> startWhitelistSetup())
                .setNegativeButton("取消", null)
                .show();
    }

    private void startWhitelistSetup() {
        if (!isAdded() || getView() == null || whitelistHelper == null) {
            return;
        }
        isWhitelistRunning = true;
        setWhitelistButtonsEnabled(false);
        whitelistLogBuffer.setLength(0);
        tvWhitelistLog.setText("");
        scrollWhitelistLog.setVisibility(View.VISIBLE);
        tvWhitelistStatus.setText("正在写入车机后台白名单并备份原配置…");

        whitelistHelper.executeWhitelistSetup(new SystemWhitelistHelper.Callback() {
            @Override
            public void onLog(String message) {
                if (!isAdded() || getView() == null) {
                    return;
                }
                appendLogLine(whitelistLogBuffer, tvWhitelistLog, scrollWhitelistLog, message);
            }

            @Override
            public void onComplete(boolean success) {
                if (!isAdded() || getView() == null) {
                    return;
                }
                isWhitelistRunning = false;
                setWhitelistButtonsEnabled(true);
                tvWhitelistStatus.setText(success
                        ? "已执行系统后台白名单配置（建议关注车机休眠实测效果）"
                        : "白名单写入未完全成功，请核对下方日志");
            }
        });
    }

    private void confirmAndRunWhitelistRestore() {
        if (!isAdded() || getContext() == null) {
            return;
        }
        if (isWhitelistRunning) {
            Toast.makeText(getContext(), "后台白名单任务正在执行中…", Toast.LENGTH_SHORT).show();
            return;
        }
        Context ctx = getContext();
        new MaterialAlertDialogBuilder(ctx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("还原白名单系统备份")
                .setMessage("将从系统备份恢复写入白名单之前的车机后台限制配置。确认继续？")
                .setPositiveButton("确认还原", (d, w) -> startWhitelistRestore())
                .setNegativeButton("取消", null)
                .show();
    }

    private void startWhitelistRestore() {
        if (!isAdded() || getView() == null || whitelistHelper == null) {
            return;
        }
        isWhitelistRunning = true;
        setWhitelistButtonsEnabled(false);
        restoreLogBuffer.setLength(0);
        tvRestoreLog.setText("");
        scrollRestoreLog.setVisibility(View.VISIBLE);
        tvWhitelistStatus.setText("正在从系统备份还原后台策略…");

        whitelistHelper.executeWhitelistRestore(new SystemWhitelistHelper.Callback() {
            @Override
            public void onLog(String message) {
                if (!isAdded() || getView() == null) {
                    return;
                }
                appendLogLine(restoreLogBuffer, tvRestoreLog, scrollRestoreLog, message);
            }

            @Override
            public void onComplete(boolean success) {
                if (!isAdded() || getView() == null) {
                    return;
                }
                isWhitelistRunning = false;
                setWhitelistButtonsEnabled(true);
                tvWhitelistStatus.setText(success
                        ? "已完成系统白名单备份还原"
                        : "系统白名单还原未完全成功，请查看还原日志");
            }
        });
    }

    private void setWhitelistButtonsEnabled(boolean enabled) {
        btnSystemWhitelist.setEnabled(enabled);
        btnRestoreWhitelist.setEnabled(enabled);
    }

    private static void appendLogLine(@NonNull StringBuilder buffer,
                                      @NonNull TextView tv,
                                      @NonNull ScrollView scroll,
                                      @Nullable String message) {
        if (message == null) {
            return;
        }
        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(message);
        tv.setText(buffer.toString());
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private static int dp(@NonNull Context ctx, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics()));
    }
}
