package com.kooo.evcam;

import android.app.ProgressDialog;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

/**
 * 14. 软件更新设置子页（基于现有 VersionUpdateManager 保留版本显示与自定义服务器检查更新）。
 */
public class UpdateSettingsFragment extends Fragment {
    private static final String TAG = "UpdateSettingsFragment";

    private AppConfig appConfig;
    private VersionUpdateManager versionUpdateManager;
    private TextView currentVersionText;
    private TextView buildVariantText;
    private TextView updateServerStatusText;
    private Button checkUpdateButton;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_update_settings, container, false);

        View btnBack = view.findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                if (getActivity() != null) {
                    getActivity().getSupportFragmentManager().popBackStack();
                }
            });
        }

        currentVersionText = view.findViewById(R.id.tv_current_version);
        buildVariantText = view.findViewById(R.id.tv_build_variant);
        updateServerStatusText = view.findViewById(R.id.tv_update_server_status);
        Button configServerButton = view.findViewById(R.id.btn_config_update_server);
        checkUpdateButton = view.findViewById(R.id.btn_check_update);

        if (getContext() != null) {
            appConfig = new AppConfig(getContext());
            versionUpdateManager = new VersionUpdateManager(getContext());
            initVersionInfo();
            refreshServerStatus();
        }

        if (configServerButton != null) {
            configServerButton.setOnClickListener(v -> showUpdateServerConfigDialog());
        }
        if (checkUpdateButton != null) {
            checkUpdateButton.setOnClickListener(v -> {
                if (versionUpdateManager != null && !versionUpdateManager.isUpdateServerConfigured()) {
                    showUpdateServerConfigDialog();
                } else {
                    performCheckUpdate();
                }
            });
        }

        return view;
    }

    private void initVersionInfo() {
        if (getContext() == null) return;
        try {
            String version = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            currentVersionText.setText("当前版本：v" + version);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            AppLog.w(TAG, "Cannot read installed version", e);
        }
        if (buildVariantText != null) {
            buildVariantText.setText("构建类型：" + BuildConfig.FLAVOR + " / " + BuildConfig.BUILD_TYPE
                    + "（code " + BuildConfig.VERSION_CODE + "）");
        }
    }

    private void refreshServerStatus() {
        if (updateServerStatusText == null || appConfig == null) return;
        String url = appConfig.getUpdateServerUrl();
        if (url == null || url.isEmpty()) {
            updateServerStatusText.setText("更新服务器：未配置（点击「配置更新地址」设置自定义源）");
        } else {
            updateServerStatusText.setText("更新服务器：" + url);
        }
    }

    private void showUpdateServerConfigDialog() {
        if (getContext() == null || appConfig == null) return;

        EditText inputEditText = new EditText(getContext());
        inputEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        inputEditText.setHint("例如：https://example.com/update/");
        inputEditText.setPadding(48, 32, 48, 32);
        inputEditText.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        inputEditText.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        inputEditText.setBackgroundResource(R.drawable.edit_text_background);

        String currentUrl = appConfig.getUpdateServerUrl();
        if (currentUrl != null) {
            inputEditText.setText(currentUrl);
        }

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("配置更新服务器")
                .setMessage("请输入更新服务器地址。\n\n服务器目录应包含：\n• version.txt（版本号文件）\n• EVCam-v{版本号}-release.apk（安装包）")
                .setView(inputEditText)
                .setPositiveButton("保存", (dialog, which) -> {
                    String url = inputEditText.getText().toString().trim();
                    if (url.isEmpty()) {
                        appConfig.setUpdateServerUrl(null);
                        refreshServerStatus();
                        Toast.makeText(getContext(), "已清除更新服务器地址", Toast.LENGTH_SHORT).show();
                    } else {
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            Toast.makeText(getContext(), "请输入有效的 HTTP/HTTPS 地址", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        appConfig.setUpdateServerUrl(url);
                        refreshServerStatus();
                        Toast.makeText(getContext(), "更新服务器地址已保存", Toast.LENGTH_SHORT).show();
                        performCheckUpdate();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void performCheckUpdate() {
        if (getContext() == null || versionUpdateManager == null || checkUpdateButton == null) return;

        checkUpdateButton.setEnabled(false);
        checkUpdateButton.setText("检查中...");

        versionUpdateManager.checkUpdate(new VersionUpdateManager.UpdateCheckCallback() {
            @Override
            public void onUpdateAvailable(String newVersion) {
                if (!isAdded() || getContext() == null) return;
                checkUpdateButton.setEnabled(true);
                checkUpdateButton.setText("检查更新");
                showUpdateAvailableDialog(newVersion);
            }

            @Override
            public void onNoUpdate() {
                if (!isAdded() || getContext() == null) return;
                checkUpdateButton.setEnabled(true);
                checkUpdateButton.setText("检查更新");
                Toast.makeText(getContext(), "已是最新版本", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                if (!isAdded() || getContext() == null) return;
                checkUpdateButton.setEnabled(true);
                checkUpdateButton.setText("检查更新");
                Toast.makeText(getContext(), "检查更新失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showUpdateAvailableDialog(String newVersion) {
        if (getContext() == null || versionUpdateManager == null) return;

        String currentVersion = versionUpdateManager.getCurrentVersion();
        String message = "当前版本：v" + currentVersion + "\n最新版本：v" + newVersion + "\n\n是否下载新版本？";

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("发现新版本")
                .setMessage(message)
                .setPositiveButton("下载更新", (dialog, which) -> startDownload(newVersion))
                .setNegativeButton("稍后再说", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void startDownload(String newVersion) {
        if (getContext() == null || versionUpdateManager == null) return;

        ProgressDialog progressDialog = new ProgressDialog(getContext());
        progressDialog.setTitle("下载更新");
        progressDialog.setMessage("正在下载 EVCam v" + newVersion + "...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setProgress(0);
        progressDialog.setCancelable(false);
        progressDialog.setButton(ProgressDialog.BUTTON_NEGATIVE, "取消", (dialog, which) -> {
            versionUpdateManager.cancelDownload();
            dialog.dismiss();
        });
        progressDialog.show();

        versionUpdateManager.downloadApk(newVersion, new VersionUpdateManager.DownloadCallback() {
            @Override
            public void onProgress(int progress) {
                progressDialog.setProgress(progress);
            }

            @Override
            public void onComplete(File apkFile) {
                progressDialog.dismiss();
                if (isAdded()) {
                    showDownloadCompleteDialog(apkFile, newVersion);
                }
            }

            @Override
            public void onError(String error) {
                progressDialog.dismiss();
                if (isAdded() && getContext() != null && !"下载已取消".equals(error)) {
                    Toast.makeText(getContext(), "下载失败: " + error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void showDownloadCompleteDialog(File apkFile, String newVersion) {
        if (getContext() == null) return;

        String filePath = apkFile.getAbsolutePath();
        String displayPath = filePath;
        String internalRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (filePath.startsWith(internalRoot)) {
            displayPath = filePath.replace(internalRoot, "内部存储");
        }

        String message = "EVCam v" + newVersion + " 已下载完成！\n\n"
                + "文件位置：\n" + displayPath + "\n\n"
                + "请选择安装方式：";

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("下载完成")
                .setMessage(message)
                .setPositiveButton("ADB 安装", (dialog, which) -> startAdbInstall(apkFile))
                .setNeutralButton("手动安装", (dialog, which) -> Toast.makeText(
                        getContext(),
                        "请使用文件管理器打开 Download 文件夹安装更新",
                        Toast.LENGTH_LONG).show())
                .setCancelable(false)
                .show();
    }

    private void startAdbInstall(File apkFile) {
        if (getContext() == null) return;

        ScrollView scrollView = new ScrollView(getContext());
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollView.setMinimumHeight(300);

        TextView logView = new TextView(getContext());
        logView.setPadding(48, 24, 48, 24);
        logView.setTextSize(12);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        scrollView.addView(logView);

        AlertDialog dialog = new MaterialAlertDialogBuilder(
                getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("ADB 安装更新")
                .setView(scrollView)
                .setCancelable(false)
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);

        AdbPermissionHelper adbHelper = new AdbPermissionHelper(getContext());
        adbHelper.installApk(apkFile.getAbsolutePath(), new AdbPermissionHelper.Callback() {
            @Override
            public void onLog(String message) {
                if (getContext() == null) return;
                logView.append(message + "\n");
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }

            @Override
            public void onComplete(boolean allSuccess) {
                if (dialog.isShowing()) {
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                }
                if (!allSuccess && getContext() != null) {
                    Toast.makeText(getContext(), "ADB 安装失败，请尝试手动安装", Toast.LENGTH_LONG).show();
                }
            }
        });
    }
}
