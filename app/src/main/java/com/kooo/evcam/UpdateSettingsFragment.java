package com.kooo.evcam;

import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.update.GitHubReleaseParser;
import com.kooo.evcam.update.GitHubUpdateConfig;

import java.io.File;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * 14. 软件更新设置子页（私有 GitHub Releases 手动检查更新 + 加密 PAT 存储 + 历史版本与兼容自定义源）。
 */
public class UpdateSettingsFragment extends Fragment {
    private static final String TAG = "UpdateSettingsFragment";

    private AppConfig appConfig;
    private VersionUpdateManager versionUpdateManager;
    private GitHubUpdateConfig gitHubConfig;
    private TextView currentVersionText;
    private TextView buildVariantText;
    private TextView updateServerStatusText;
    private Button checkUpdateButton;
    private Button historyReleasesButton;

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
        historyReleasesButton = view.findViewById(R.id.btn_history_releases);

        if (getContext() != null) {
            appConfig = new AppConfig(getContext());
            versionUpdateManager = new VersionUpdateManager(getContext());
            gitHubConfig = versionUpdateManager.getGitHubConfig();
            initVersionInfo();
            refreshServerStatus();
        }

        if (configServerButton != null) {
            configServerButton.setOnClickListener(v -> showUpdateSourceConfigDialog());
        }
        if (checkUpdateButton != null) {
            checkUpdateButton.setOnClickListener(v -> {
                if (versionUpdateManager != null && !versionUpdateManager.isUpdateServerConfigured()) {
                    showUpdateSourceConfigDialog();
                } else {
                    performCheckUpdate();
                }
            });
        }
        if (historyReleasesButton != null) {
            historyReleasesButton.setOnClickListener(v -> performFetchHistory());
        }

        SettingsFragment.configureEmbeddedToolbar(this, view, "14. 软件更新");
        return view;
    }

    private void initVersionInfo() {
        if (getContext() == null) {
            return;
        }
        try {
            String version = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            currentVersionText.setText("当前版本：v" + version);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            AppLog.w(TAG, "Cannot read installed version", e);
        }
        if (buildVariantText != null) {
            String flavorLabel = BuildConfig.SMARTCAM_COMPOSITE ? "B 版合流 (composite)" : "A 版独立 (independent)";
            buildVariantText.setText("构建变体：" + flavorLabel + " / " + BuildConfig.BUILD_TYPE
                    + "（code " + BuildConfig.VERSION_CODE + "）");
        }
    }

    private void refreshServerStatus() {
        if (updateServerStatusText == null || appConfig == null || gitHubConfig == null) {
            return;
        }
        if (GitHubUpdateConfig.SOURCE_CUSTOM_HTTP.equals(gitHubConfig.getSourceMode())) {
            String url = appConfig.getUpdateServerUrl();
            updateServerStatusText.setText(url == null || url.isEmpty()
                    ? "更新源：自定义 HTTP 目录（未配置地址）"
                    : "更新源：自定义 HTTP (" + url + ")");
            if (historyReleasesButton != null) {
                historyReleasesButton.setVisibility(View.GONE);
            }
        } else {
            String patStatus = gitHubConfig.hasPat()
                    ? "PAT 已 Keystore 加密存储 (" + gitHubConfig.getMaskedPatHint() + ")"
                    : "未配置 PAT（公开库可读，私有库需填写）";
            String preStatus = gitHubConfig.isIncludePrerelease() ? "包含 Pre-release" : "仅正式版 Release";
            updateServerStatusText.setText("更新源：GitHub " + gitHubConfig.getRepoSlug()
                    + "\n凭证：" + patStatus + " · " + preStatus);
            if (historyReleasesButton != null) {
                historyReleasesButton.setVisibility(View.VISIBLE);
            }
        }
    }

    private void showUpdateSourceConfigDialog() {
        if (getContext() == null || appConfig == null || gitHubConfig == null) {
            return;
        }

        ScrollView scrollView = new ScrollView(getContext());
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 24, 48, 8);
        scrollView.addView(container);

        RadioGroup modeGroup = new RadioGroup(getContext());
        modeGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbGitHub = new RadioButton(getContext());
        rbGitHub.setId(View.generateViewId());
        rbGitHub.setText("GitHub Release（推荐）");
        rbGitHub.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        RadioButton rbCustom = new RadioButton(getContext());
        rbCustom.setId(View.generateViewId());
        rbCustom.setText("自定义 HTTP 目录");
        rbCustom.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        modeGroup.addView(rbGitHub);
        modeGroup.addView(rbCustom);
        boolean isCustomMode = GitHubUpdateConfig.SOURCE_CUSTOM_HTTP.equals(gitHubConfig.getSourceMode());
        modeGroup.check(isCustomMode ? rbCustom.getId() : rbGitHub.getId());
        container.addView(modeGroup);

        // GitHub 配置面板
        LinearLayout githubPanel = new LinearLayout(getContext());
        githubPanel.setOrientation(LinearLayout.VERTICAL);
        githubPanel.setPadding(0, 16, 0, 0);

        TextView repoLabel = new TextView(getContext());
        repoLabel.setText("GitHub 仓库（owner/repo）：");
        repoLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        githubPanel.addView(repoLabel);

        EditText etRepo = new EditText(getContext());
        etRepo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etRepo.setHint("johnwyatt041-commits/EVCam");
        etRepo.setText(gitHubConfig.getRepoSlug());
        etRepo.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        etRepo.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        etRepo.setBackgroundResource(R.drawable.edit_text_background);
        etRepo.setPadding(24, 18, 24, 18);
        githubPanel.addView(etRepo);

        TextView patLabel = new TextView(getContext());
        patLabel.setText("私有仓库 PAT Token（留空表示保留原 PAT，不修改）：");
        patLabel.setPadding(0, 16, 0, 4);
        patLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        githubPanel.addView(patLabel);

        EditText etPat = new EditText(getContext());
        etPat.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPat.setHint(gitHubConfig.hasPat() ? "当前已加密保存: " + gitHubConfig.getMaskedPatHint() : "github_pat_... 或 ghp_...");
        etPat.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        etPat.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        etPat.setBackgroundResource(R.drawable.edit_text_background);
        etPat.setPadding(24, 18, 24, 18);
        githubPanel.addView(etPat);

        CheckBox cbIncludePre = new CheckBox(getContext());
        cbIncludePre.setText("包含 Pre-release 测试版（手动检查）");
        cbIncludePre.setChecked(gitHubConfig.isIncludePrerelease());
        cbIncludePre.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        githubPanel.addView(cbIncludePre);

        CheckBox cbClearPat = new CheckBox(getContext());
        cbClearPat.setText("清除已保存的加密 PAT");
        cbClearPat.setVisibility(gitHubConfig.hasPat() ? View.VISIBLE : View.GONE);
        cbClearPat.setTextColor(ContextCompat.getColor(getContext(), R.color.ui_danger));
        githubPanel.addView(cbClearPat);

        container.addView(githubPanel);

        // 自定义 HTTP 目录面板
        LinearLayout customPanel = new LinearLayout(getContext());
        customPanel.setOrientation(LinearLayout.VERTICAL);
        customPanel.setPadding(0, 16, 0, 0);

        TextView urlLabel = new TextView(getContext());
        urlLabel.setText("HTTP 更新目录（需含 version.txt 与 APK）：");
        urlLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        customPanel.addView(urlLabel);

        EditText etCustomUrl = new EditText(getContext());
        etCustomUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etCustomUrl.setHint("https://example.com/update/");
        String currentUrl = appConfig.getUpdateServerUrl();
        if (currentUrl != null) {
            etCustomUrl.setText(currentUrl);
        }
        etCustomUrl.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        etCustomUrl.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        etCustomUrl.setBackgroundResource(R.drawable.edit_text_background);
        etCustomUrl.setPadding(24, 18, 24, 18);
        customPanel.addView(etCustomUrl);
        container.addView(customPanel);

        githubPanel.setVisibility(isCustomMode ? View.GONE : View.VISIBLE);
        customPanel.setVisibility(isCustomMode ? View.VISIBLE : View.GONE);
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean customSelected = checkedId == rbCustom.getId();
            githubPanel.setVisibility(customSelected ? View.GONE : View.VISIBLE);
            customPanel.setVisibility(customSelected ? View.VISIBLE : View.GONE);
        });

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("配置更新源与私有仓库 PAT")
                .setView(scrollView)
                .setPositiveButton("保存", (dialog, which) -> {
                    if (modeGroup.getCheckedRadioButtonId() == rbCustom.getId()) {
                        String url = etCustomUrl.getText().toString().trim();
                        if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                            Toast.makeText(getContext(), "请输入有效的 HTTP/HTTPS 地址", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        appConfig.setUpdateServerUrl(url.isEmpty() ? null : url);
                        gitHubConfig.setSourceMode(GitHubUpdateConfig.SOURCE_CUSTOM_HTTP);
                        refreshServerStatus();
                        Toast.makeText(getContext(), "已保存自定义更新源", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String repoInput = etRepo.getText().toString().trim();
                    if (!gitHubConfig.saveRepoSlug(repoInput)) {
                        Toast.makeText(getContext(), "仓库格式错误，请使用 owner/repo", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    gitHubConfig.setIncludePrerelease(cbIncludePre.isChecked());
                    gitHubConfig.setSourceMode(GitHubUpdateConfig.SOURCE_GITHUB);

                    String newPat = etPat.getText().toString().trim();
                    if (cbClearPat.isChecked()) {
                        gitHubConfig.clearPat();
                    } else if (!newPat.isEmpty()) {
                        try {
                            gitHubConfig.savePat(newPat);
                        } catch (GeneralSecurityException e) {
                            AppLog.e(TAG, "AndroidKeyStore 加密 PAT 失败: " + e.getMessage());
                            Toast.makeText(getContext(), "系统密钥库加密 PAT 失败，拒绝以明文保存", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }

                    refreshServerStatus();
                    Toast.makeText(getContext(), "GitHub 更新配置已保存（PAT 已硬件加密隔离）", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void performCheckUpdate() {
        if (getContext() == null || versionUpdateManager == null || checkUpdateButton == null) {
            return;
        }
        checkUpdateButton.setEnabled(false);
        checkUpdateButton.setText("检查中...");

        versionUpdateManager.checkUpdate(new VersionUpdateManager.UpdateCheckCallback() {
            @Override
            public void onUpdateAvailable(String newVersion) {
                if (!isAdded() || getContext() == null) return;
                resetCheckButton();
                showCustomUpdateAvailableDialog(newVersion);
            }

            @Override
            public void onUpdateCandidateAvailable(GitHubReleaseParser.UpdateCandidate candidate) {
                if (!isAdded() || getContext() == null) return;
                resetCheckButton();
                showGitHubUpdateAvailableDialog(candidate);
            }

            @Override
            public void onNoUpdate() {
                if (!isAdded() || getContext() == null) return;
                resetCheckButton();
                Toast.makeText(getContext(), "当前已是最新版本", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                if (!isAdded() || getContext() == null) return;
                resetCheckButton();
                Toast.makeText(getContext(), "检查更新失败: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void performFetchHistory() {
        if (getContext() == null || versionUpdateManager == null || historyReleasesButton == null) {
            return;
        }
        historyReleasesButton.setEnabled(false);
        historyReleasesButton.setText("加载中...");

        versionUpdateManager.fetchReleases(new VersionUpdateManager.ReleaseListCallback() {
            @Override
            public void onLoaded(List<GitHubReleaseParser.ReleaseInfo> releases) {
                if (!isAdded() || getContext() == null) return;
                historyReleasesButton.setEnabled(true);
                historyReleasesButton.setText("历史版本");
                showHistoryReleasesDialog(releases);
            }

            @Override
            public void onError(String error) {
                if (!isAdded() || getContext() == null) return;
                historyReleasesButton.setEnabled(true);
                historyReleasesButton.setText("历史版本");
                Toast.makeText(getContext(), "获取历史版本失败: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showHistoryReleasesDialog(List<GitHubReleaseParser.ReleaseInfo> releases) {
        if (getContext() == null) return;
        if (releases == null || releases.isEmpty()) {
            Toast.makeText(getContext(), "暂无可下载的版本记录", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean isComposite = BuildConfig.SMARTCAM_COMPOSITE;
        String[] labels = new String[releases.size()];
        for (int i = 0; i < releases.size(); i++) {
            GitHubReleaseParser.ReleaseInfo r = releases.get(i);
            GitHubReleaseParser.ReleaseAsset matched = GitHubReleaseParser.selectAssetForFlavor(r.assets, isComposite);
            String tagLabel = r.tagName + (r.prerelease ? " [测试版]" : " [正式版]");
            String assetLabel = matched != null ? " · " + matched.name : " · (无匹配当前风味 APK)";
            labels[i] = tagLabel + assetLabel;
        }
        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("仓库发行版本列表")
                .setItems(labels, (dialog, which) -> {
                    GitHubReleaseParser.ReleaseInfo picked = releases.get(which);
                    GitHubReleaseParser.ReleaseAsset asset =
                            GitHubReleaseParser.selectAssetForFlavor(picked.assets, isComposite);
                    if (asset == null) {
                        Toast.makeText(getContext(), "该版本未附带匹配当前构建变体的 APK", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showGitHubUpdateAvailableDialog(new GitHubReleaseParser.UpdateCandidate(picked, asset));
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void resetCheckButton() {
        checkUpdateButton.setEnabled(true);
        checkUpdateButton.setText("检查更新");
    }

    private void showGitHubUpdateAvailableDialog(GitHubReleaseParser.UpdateCandidate candidate) {
        if (getContext() == null || versionUpdateManager == null || candidate == null) return;

        String currentVersion = versionUpdateManager.getCurrentVersion();
        String sizeMb = candidate.asset.sizeBytes > 0
                ? String.format(java.util.Locale.US, "%.2f MB", candidate.asset.sizeBytes / (1024.0 * 1024.0))
                : "未知大小";
        String bodyPreview = candidate.release.body.isEmpty()
                ? "（无更新说明）"
                : candidate.release.body;

        String message = "当前版本：v" + currentVersion
                + "\n目标版本：" + candidate.release.tagName
                + "\n匹配资产：" + candidate.asset.name + " (" + sizeMb + ")"
                + "\n\n更新说明：\n" + bodyPreview;

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认下载更新")
                .setMessage(message)
                .setPositiveButton("下载更新", (dialog, which) -> startGitHubDownload(candidate))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCustomUpdateAvailableDialog(String newVersion) {
        if (getContext() == null || versionUpdateManager == null) return;
        String currentVersion = versionUpdateManager.getCurrentVersion();
        String message = "当前版本：v" + currentVersion + "\n最新版本：v" + newVersion + "\n\n是否下载新版本？";
        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("发现新版本")
                .setMessage(message)
                .setPositiveButton("下载更新", (dialog, which) -> startCustomDownload(newVersion))
                .setNegativeButton("稍后再说", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void startGitHubDownload(GitHubReleaseParser.UpdateCandidate candidate) {
        if (getContext() == null || versionUpdateManager == null) return;
        ProgressDialog progressDialog = createDownloadProgressDialog(candidate.release.tagName);
        progressDialog.show();

        versionUpdateManager.downloadGitHubAsset(candidate.release, candidate.asset,
                new VersionUpdateManager.DownloadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        progressDialog.setProgress(progress);
                    }

                    @Override
                    public void onComplete(File apkFile) {
                        progressDialog.dismiss();
                        if (isAdded()) {
                            showDownloadCompleteDialog(apkFile, candidate.release.version);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        progressDialog.dismiss();
                        if (isAdded() && getContext() != null && !"下载已取消".equals(error)) {
                            Toast.makeText(getContext(), "下载失败: " + error, Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    @SuppressWarnings("deprecation")
    private void startCustomDownload(String newVersion) {
        if (getContext() == null || versionUpdateManager == null) return;
        ProgressDialog progressDialog = createDownloadProgressDialog("v" + newVersion);
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
                    Toast.makeText(getContext(), "下载失败: " + error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    @SuppressWarnings("deprecation")
    private ProgressDialog createDownloadProgressDialog(String versionLabel) {
        ProgressDialog progressDialog = new ProgressDialog(getContext());
        progressDialog.setTitle("下载更新");
        progressDialog.setMessage("正在下载 EVCam " + versionLabel + "...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setProgress(0);
        progressDialog.setCancelable(false);
        progressDialog.setButton(ProgressDialog.BUTTON_NEGATIVE, "取消", (dialog, which) -> {
            versionUpdateManager.cancelDownload();
            dialog.dismiss();
        });
        return progressDialog;
    }

    private void showDownloadCompleteDialog(File apkFile, String newVersion) {
        if (getContext() == null) return;

        String filePath = apkFile.getAbsolutePath();
        String displayPath = filePath;
        String internalRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (filePath.startsWith(internalRoot)) {
            displayPath = filePath.replace(internalRoot, "内部存储");
        }

        String message = "EVCam v" + newVersion + " 已下载并校验完成！\n\n"
                + "文件位置：\n" + displayPath + "\n\n请选择安装方式：";

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("下载完成")
                .setMessage(message)
                .setPositiveButton("ADB 安装", (dialog, which) -> startAdbInstall(apkFile))
                .setNeutralButton("系统安装", (dialog, which) -> launchSystemApkInstaller(apkFile))
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .show();
    }

    private void launchSystemApkInstaller(File apkFile) {
        if (getContext() == null) return;
        try {
            if (!requireContext().getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(getContext(), "请先授予「安装未知应用」权限后再安装", Toast.LENGTH_LONG).show();
                Intent permIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + requireContext().getPackageName()));
                permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(permIntent);
                return;
            }
            Uri apkUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    apkFile);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(installIntent);
        } catch (Exception e) {
            AppLog.w(TAG, "调用系统安装器失败: " + e.getMessage());
            Toast.makeText(getContext(), "无法拉起系统安装器，请打开 Download 文件夹手动安装", Toast.LENGTH_LONG).show();
        }
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
                    Toast.makeText(getContext(), "ADB 安装失败，请尝试系统安装", Toast.LENGTH_LONG).show();
                }
            }
        });
    }
}
