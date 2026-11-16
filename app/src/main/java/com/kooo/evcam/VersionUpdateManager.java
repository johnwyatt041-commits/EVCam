package com.kooo.evcam;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.update.AppVersionComparator;
import com.kooo.evcam.update.GitHubHostPolicy;
import com.kooo.evcam.update.GitHubReleaseClient;
import com.kooo.evcam.update.GitHubReleaseParser;
import com.kooo.evcam.update.GitHubUpdateConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 14. 版本更新管理器（仅手动触发，无任何后台自动轮询）：
 * - 主源：私有/公有 GitHub Releases API（支持 Encrypted PAT、Pre-release、A/B 风味精确匹配与历史版本列表）；
 * - 兼容源：可选自定义 HTTP 目录源（version.txt + EVCam-v{版本号}-release.apk）。
 */
public class VersionUpdateManager {
    private static final String TAG = "VersionUpdateManager";

    private static final String VERSION_FILE = "version.txt";
    private static final String APK_FILE_PATTERN = "EVCam-v%s-release.apk";

    private final Context context;
    private final AppConfig appConfig;
    private final GitHubUpdateConfig gitHubConfig;
    private final GitHubReleaseClient gitHubClient;
    private final OkHttpClient httpClient;
    private final Handler mainHandler;
    private final ExecutorService executor;

    private volatile Call currentDownloadCall;
    private volatile boolean downloadCancelledByUser = false;

    public interface UpdateCheckCallback {
        void onUpdateAvailable(String newVersion);

        default void onUpdateCandidateAvailable(GitHubReleaseParser.UpdateCandidate candidate) {
            if (candidate != null) {
                onUpdateAvailable(candidate.release.version);
            }
        }

        void onNoUpdate();

        void onError(String error);
    }

    public interface ReleaseListCallback {
        void onLoaded(List<GitHubReleaseParser.ReleaseInfo> releases);

        void onError(String error);
    }

    public interface DownloadCallback {
        void onProgress(int progress);

        void onComplete(File apkFile);

        void onError(String error);
    }

    public VersionUpdateManager(Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(this.context);
        this.gitHubConfig = new GitHubUpdateConfig(this.context);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.gitHubClient = new GitHubReleaseClient(this.httpClient, false);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "EVCam-Updater");
            t.setDaemon(true);
            return t;
        });
    }

    public GitHubUpdateConfig getGitHubConfig() {
        return gitHubConfig;
    }

    public String getCurrentVersion() {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            AppLog.e(TAG, "获取版本号失败: " + e.getMessage());
            return "unknown";
        }
    }

    public boolean isUpdateServerConfigured() {
        if (GitHubUpdateConfig.SOURCE_CUSTOM_HTTP.equals(gitHubConfig.getSourceMode())) {
            String url = appConfig.getUpdateServerUrl();
            return url != null && !url.isEmpty();
        }
        return !gitHubConfig.getRepoOwner().isEmpty() && !gitHubConfig.getRepoName().isEmpty();
    }

    /** 手动检查更新（仅由 UI 按钮显式触发）。 */
    public void checkUpdate(UpdateCheckCallback callback) {
        if (GitHubUpdateConfig.SOURCE_CUSTOM_HTTP.equals(gitHubConfig.getSourceMode())) {
            checkCustomHttpUpdate(callback);
            return;
        }
        checkGitHubUpdate(callback);
    }

    /** 手动拉取 GitHub 仓库最近发行版本列表（供选择历史版本或查看更新日志）。 */
    public void fetchReleases(ReleaseListCallback callback) {
        final String owner = gitHubConfig.getRepoOwner();
        final String repo = gitHubConfig.getRepoName();
        final String apiBase = gitHubConfig.getApiBaseUrl();
        final boolean includePrerelease = gitHubConfig.isIncludePrerelease();
        final String pat = gitHubConfig.loadPat();

        AppLog.i(TAG, "手动获取 GitHub Releases 列表: " + owner + "/" + repo
                + ", includePrerelease=" + includePrerelease + ", hasPat=" + (pat != null));

        executor.execute(() -> {
            try {
                List<GitHubReleaseParser.ReleaseInfo> releases =
                        gitHubClient.fetchReleases(apiBase, owner, repo, pat, includePrerelease);
                mainHandler.post(() -> callback.onLoaded(releases));
            } catch (Exception e) {
                String safeMsg = GitHubHostPolicy.redactSecret(e.getMessage(), pat);
                AppLog.e(TAG, "拉取 GitHub Releases 失败: " + safeMsg);
                mainHandler.post(() -> callback.onError(safeMsg != null ? safeMsg : "检查更新失败"));
            }
        });
    }

    private void checkGitHubUpdate(UpdateCheckCallback callback) {
        final String owner = gitHubConfig.getRepoOwner();
        final String repo = gitHubConfig.getRepoName();
        final String apiBase = gitHubConfig.getApiBaseUrl();
        final boolean includePrerelease = gitHubConfig.isIncludePrerelease();
        final String pat = gitHubConfig.loadPat();
        final String currentVersion = getCurrentVersion();
        final boolean isComposite = BuildConfig.SMARTCAM_COMPOSITE;

        AppLog.i(TAG, "手动检查 GitHub 更新: " + owner + "/" + repo
                + ", current=" + currentVersion + ", composite=" + isComposite);

        executor.execute(() -> {
            try {
                List<GitHubReleaseParser.ReleaseInfo> releases =
                        gitHubClient.fetchReleases(apiBase, owner, repo, pat, includePrerelease);
                if (releases.isEmpty()) {
                    mainHandler.post(callback::onNoUpdate);
                    return;
                }
                GitHubReleaseParser.UpdateCandidate candidate =
                        GitHubReleaseParser.findLatestUpdate(releases, currentVersion, isComposite);
                if (candidate != null) {
                    AppLog.i(TAG, "发现匹配的更新版本: " + candidate.release.tagName
                            + ", asset=" + candidate.asset.name);
                    mainHandler.post(() -> callback.onUpdateCandidateAvailable(candidate));
                    return;
                }

                // 若存在更高版本号的 Release 但缺少对应风味包，给予明确提示而不是静默报“已是最新”
                for (GitHubReleaseParser.ReleaseInfo info : releases) {
                    if (AppVersionComparator.isNewerVersion(info.version, currentVersion)
                            && GitHubReleaseParser.selectAssetForFlavor(info.assets, isComposite) == null) {
                        String flavorLabel = isComposite ? "composite(B)" : "independent(A)";
                        mainHandler.post(() -> callback.onError(
                                "发现新版本 " + info.tagName + "，但未找到匹配 " + flavorLabel + " 的 APK 资产"));
                        return;
                    }
                }
                mainHandler.post(callback::onNoUpdate);
            } catch (Exception e) {
                String safeMsg = GitHubHostPolicy.redactSecret(e.getMessage(), pat);
                AppLog.e(TAG, "检查 GitHub 更新失败: " + safeMsg);
                mainHandler.post(() -> callback.onError(safeMsg != null ? safeMsg : "检查更新失败"));
            }
        });
    }

    /** 下载指定 GitHub Release 的匹配 APK 资产（支持私有仓 API 重定向下载与校验）。 */
    public void downloadGitHubAsset(GitHubReleaseParser.ReleaseInfo release,
                                    GitHubReleaseParser.ReleaseAsset asset,
                                    DownloadCallback callback) {
        if (release == null || asset == null) {
            mainHandler.post(() -> callback.onError("无效的 Release 资产"));
            return;
        }
        final String apiBase = gitHubConfig.getApiBaseUrl();
        final String pat = gitHubConfig.loadPat();
        final String flavorTag = BuildConfig.SMARTCAM_COMPOSITE ? "composite" : "independent";
        final String fileName = "EVCam_" + flavorTag + "_" + release.version + ".apk";

        downloadCancelledByUser = false;
        executor.execute(() -> {
            try {
                File downloadDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                File targetFile = new File(downloadDir, fileName);
                Call call = gitHubClient.prepareDownloadAsset(asset, apiBase, pat);
                currentDownloadCall = call;
                File downloaded = gitHubClient.executeAssetDownload(
                        asset, apiBase, pat, targetFile, call,
                        percent -> mainHandler.post(() -> callback.onProgress(percent)));
                currentDownloadCall = null;
                AppLog.i(TAG, "GitHub APK 下载与校验完成: " + downloaded.getAbsolutePath());
                mainHandler.post(() -> callback.onComplete(downloaded));
            } catch (Exception e) {
                currentDownloadCall = null;
                if (downloadCancelledByUser) {
                    AppLog.d(TAG, "用户取消 GitHub APK 下载");
                    mainHandler.post(() -> callback.onError("下载已取消"));
                } else {
                    String safeMsg = GitHubHostPolicy.redactSecret(e.getMessage(), pat);
                    AppLog.e(TAG, "GitHub APK 下载失败: " + safeMsg);
                    mainHandler.post(() -> callback.onError(safeMsg != null ? safeMsg : "下载失败"));
                }
            }
        });
    }

    // ==================== 兼容旧自定义 HTTP 目录源 ====================

    private String getCustomBaseUrl() {
        String url = appConfig.getUpdateServerUrl();
        if (url == null || url.isEmpty()) {
            return null;
        }
        return url.endsWith("/") ? url : (url + "/");
    }

    private void checkCustomHttpUpdate(UpdateCheckCallback callback) {
        String baseUrl = getCustomBaseUrl();
        if (baseUrl == null) {
            mainHandler.post(() -> callback.onError("未配置自定义更新服务器地址"));
            return;
        }
        String versionUrl = baseUrl + VERSION_FILE;
        AppLog.d(TAG, "检查自定义服务器更新: " + versionUrl);

        Request request = new Request.Builder().url(versionUrl).get().build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("网络错误: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        mainHandler.post(() -> callback.onError("服务器错误: " + response.code()));
                        return;
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        mainHandler.post(() -> callback.onError("服务器返回空响应"));
                        return;
                    }
                    String remoteVersion = AppVersionComparator.normalizeVersion(body.string().trim());
                    if (!AppVersionComparator.isValidVersionFormat(remoteVersion)) {
                        mainHandler.post(() -> callback.onError("无效的版本号格式: " + remoteVersion));
                        return;
                    }
                    if (AppVersionComparator.isNewerVersion(remoteVersion, getCurrentVersion())) {
                        mainHandler.post(() -> callback.onUpdateAvailable(remoteVersion));
                    } else {
                        mainHandler.post(callback::onNoUpdate);
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    public void downloadApk(String newVersion, DownloadCallback callback) {
        String baseUrl = getCustomBaseUrl();
        if (baseUrl == null) {
            mainHandler.post(() -> callback.onError("未配置自定义更新服务器地址"));
            return;
        }
        String normalized = AppVersionComparator.normalizeVersion(newVersion);
        String apkUrl = baseUrl + String.format(APK_FILE_PATTERN, normalized);
        AppLog.d(TAG, "开始从自定义源下载 APK: " + apkUrl);

        downloadCancelledByUser = false;
        Request request = new Request.Builder().url(apkUrl).get().build();
        currentDownloadCall = httpClient.newCall(request);
        currentDownloadCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                currentDownloadCall = null;
                if (downloadCancelledByUser || call.isCanceled()) {
                    mainHandler.post(() -> callback.onError("下载已取消"));
                } else {
                    mainHandler.post(() -> callback.onError("下载失败: " + e.getMessage()));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                currentDownloadCall = null;
                try {
                    if (!response.isSuccessful()) {
                        mainHandler.post(() -> callback.onError("服务器错误: " + response.code()));
                        return;
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        mainHandler.post(() -> callback.onError("服务器返回空响应"));
                        return;
                    }
                    File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }
                    File apkFile = new File(downloadDir, "EVCam_" + normalized + ".apk");
                    if (apkFile.exists()) {
                        apkFile.delete();
                    }
                    long contentLength = body.contentLength();
                    long downloadedBytes = 0;
                    int lastProgress = -1;
                    try (InputStream inputStream = body.byteStream();
                         FileOutputStream outputStream = new FileOutputStream(apkFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            downloadedBytes += bytesRead;
                            if (contentLength > 0) {
                                int progress = (int) (downloadedBytes * 100 / contentLength);
                                if (progress != lastProgress) {
                                    lastProgress = progress;
                                    final int finalProgress = progress;
                                    mainHandler.post(() -> callback.onProgress(finalProgress));
                                }
                            }
                        }
                        outputStream.flush();
                    }
                    mainHandler.post(() -> callback.onComplete(apkFile));
                } catch (IOException e) {
                    mainHandler.post(() -> callback.onError("保存文件失败: " + e.getMessage()));
                } finally {
                    response.close();
                }
            }
        });
    }

    public void cancelDownload() {
        downloadCancelledByUser = true;
        Call call = currentDownloadCall;
        if (call != null && !call.isCanceled()) {
            call.cancel();
            AppLog.d(TAG, "取消下载");
        }
    }
}
