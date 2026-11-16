package com.kooo.evcam.update;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 私有 GitHub Release 网络客户端（纯 Java，支持手动查新、历史版本列表、跨跳脱敏下载与 SHA-256 校验）。
 */
public final class GitHubReleaseClient {

    public static final long MAX_APK_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_REDIRECTS = 5;

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private final OkHttpClient apiClient;
    private final OkHttpClient noRedirectClient;
    private final boolean allowLoopbackHttp;

    public GitHubReleaseClient() {
        this(false);
    }

    public GitHubReleaseClient(boolean allowLoopbackHttp) {
        this(new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build(), allowLoopbackHttp);
    }

    public GitHubReleaseClient(OkHttpClient baseClient, boolean allowLoopbackHttp) {
        this.apiClient = baseClient;
        this.noRedirectClient = baseClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    public List<GitHubReleaseParser.ReleaseInfo> fetchReleases(
            String apiBaseUrl,
            String owner,
            String repo,
            String patOrNull,
            boolean includePrerelease) throws IOException {
        String base = normalizeBaseUrl(apiBaseUrl);
        String url = base + "/repos/" + owner + "/" + repo + "/releases?per_page=15";
        if (!GitHubHostPolicy.isAllowedRequestUrl(url, base, allowLoopbackHttp)) {
            throw new IOException("更新 API 地址不受信任（仅支持 HTTPS）");
        }

        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "EVCam-Updater")
                .get();
        if (patOrNull != null && !patOrNull.isEmpty()
                && GitHubHostPolicy.shouldAttachPatHeader(url, base, allowLoopbackHttp)) {
            reqBuilder.header("Authorization", "Bearer " + patOrNull);
        }

        try (Response response = apiClient.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException(translateHttpError(response.code(), patOrNull != null && !patOrNull.isEmpty()));
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("GitHub 返回空响应");
            }
            String json = body.string();
            try {
                return GitHubReleaseParser.parseReleases(json, includePrerelease);
            } catch (IllegalArgumentException e) {
                throw new IOException("解析 GitHub Release JSON 失败: " + e.getMessage(), e);
            }
        }
    }

    public Call prepareDownloadAsset(
            GitHubReleaseParser.ReleaseAsset asset,
            String configuredApiBaseUrl,
            String patOrNull) throws IOException {
        if (asset == null) {
            throw new IOException("APK 资产为空");
        }
        String initialUrl = resolveAssetDownloadUrl(asset, patOrNull != null && !patOrNull.isEmpty());
        if (!GitHubHostPolicy.isAllowedRequestUrl(initialUrl, configuredApiBaseUrl, allowLoopbackHttp)) {
            throw new IOException("资产下载地址不在可信 GitHub 域名白名单内");
        }

        Request.Builder reqBuilder = new Request.Builder()
                .url(initialUrl)
                .header("User-Agent", "EVCam-Updater")
                .get();
        if (patOrNull != null && !patOrNull.isEmpty()
                && GitHubHostPolicy.shouldAttachPatHeader(initialUrl, configuredApiBaseUrl, allowLoopbackHttp)) {
            reqBuilder.header("Accept", "application/octet-stream");
            reqBuilder.header("Authorization", "Bearer " + patOrNull);
        }
        return noRedirectClient.newCall(reqBuilder.build());
    }

    /**
     * 执行带受控重定向跟踪的资产下载：
     * - 首跳请求 API asset URL 时携带 Authorization 与 Accept: application/octet-stream；
     * - 发生 302 等跨主机跳转（如 objects.githubusercontent.com）时严禁继续携带 PAT 请求头；
     * - 校验大小上限、APK ZIP 头魔数及可选 SHA-256 摘要，校验通过后再原子重命名到目标文件。
     */
    public File executeAssetDownload(
            GitHubReleaseParser.ReleaseAsset asset,
            String configuredApiBaseUrl,
            String patOrNull,
            File targetFile,
            Call initialCall,
            ProgressListener progressListener) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".part");
        if (tempFile.exists()) {
            tempFile.delete();
        }

        Response currentResponse = initialCall.execute();
        String currentUrl = initialCall.request().url().toString();
        try {
            int hops = 0;
            while (isRedirectCode(currentResponse.code())) {
                if (++hops > MAX_REDIRECTS) {
                    throw new IOException("下载重定向次数过多");
                }
                String location = currentResponse.header("Location");
                currentResponse.close();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("重定向缺少 Location 头");
                }
                String nextUrl = resolveRedirectUrl(currentUrl, location.trim());
                if (!GitHubHostPolicy.isAllowedRequestUrl(nextUrl, configuredApiBaseUrl, allowLoopbackHttp)) {
                    throw new IOException("重定向目标不属于受信任的 GitHub HTTPS 域名");
                }

                Request.Builder hopBuilder = new Request.Builder()
                        .url(nextUrl)
                        .header("User-Agent", "EVCam-Updater")
                        .get();
                if (patOrNull != null && !patOrNull.isEmpty()
                        && GitHubHostPolicy.shouldAttachPatHeader(nextUrl, configuredApiBaseUrl, allowLoopbackHttp)) {
                    hopBuilder.header("Authorization", "Bearer " + patOrNull);
                    hopBuilder.header("Accept", "application/octet-stream");
                }
                currentUrl = nextUrl;
                currentResponse = noRedirectClient.newCall(hopBuilder.build()).execute();
            }

            if (!currentResponse.isSuccessful()) {
                throw new IOException(translateHttpError(currentResponse.code(), patOrNull != null && !patOrNull.isEmpty()));
            }
            ResponseBody body = currentResponse.body();
            if (body == null) {
                throw new IOException("下载响应体为空");
            }

            long expectedLen = body.contentLength();
            if (expectedLen > MAX_APK_BYTES) {
                throw new IOException("APK 文件体积超过安全限制（256MB）");
            }

            MessageDigest sha256;
            try {
                sha256 = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 不可用", e);
            }

            long totalRead = 0L;
            int lastProgress = -1;
            try (InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(tempFile, false)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    totalRead += n;
                    if (totalRead > MAX_APK_BYTES) {
                        throw new IOException("APK 下载体积超过安全限制（256MB）");
                    }
                    sha256.update(buffer, 0, n);
                    out.write(buffer, 0, n);

                    long denom = expectedLen > 0L ? expectedLen : asset.sizeBytes;
                    if (denom > 0L && progressListener != null) {
                        int percent = (int) Math.min(100L, (totalRead * 100L) / denom);
                        if (percent != lastProgress) {
                            lastProgress = percent;
                            progressListener.onProgress(percent);
                        }
                    }
                }
                out.flush();
            }

            if (totalRead < 4L || !hasZipMagicHeader(tempFile)) {
                throw new IOException("下载文件校验失败：不是有效的 APK 安装包");
            }

            String expectedDigest = asset.sha256Digest;
            if (expectedDigest != null && !expectedDigest.isEmpty()) {
                String actualDigest = bytesToHex(sha256.digest());
                if (!expectedDigest.equalsIgnoreCase(actualDigest)) {
                    throw new IOException("APK SHA-256 校验不匹配");
                }
            }

            if (targetFile.exists() && !targetFile.delete()) {
                throw new IOException("无法覆盖已有安装包文件");
            }
            if (!tempFile.renameTo(targetFile)) {
                throw new IOException("保存下载安装包失败");
            }
            return targetFile;
        } catch (IOException e) {
            if (tempFile.exists()) {
                tempFile.delete();
            }
            throw e;
        } finally {
            currentResponse.close();
        }
    }

    private static String resolveAssetDownloadUrl(GitHubReleaseParser.ReleaseAsset asset, boolean hasPat) {
        if (hasPat && asset.apiUrl != null && !asset.apiUrl.isEmpty()) {
            return asset.apiUrl;
        }
        if (asset.browserDownloadUrl != null && !asset.browserDownloadUrl.isEmpty()) {
            return asset.browserDownloadUrl;
        }
        return asset.apiUrl;
    }

    private static boolean hasZipMagicHeader(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            if (raf.read(magic) < 4) {
                return false;
            }
            return magic[0] == 0x50 && magic[1] == 0x4B;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isRedirectCode(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static String resolveRedirectUrl(String baseUrl, String location) throws IOException {
        okhttp3.HttpUrl parsedBase = okhttp3.HttpUrl.parse(baseUrl);
        if (parsedBase == null) {
            return location;
        }
        okhttp3.HttpUrl resolved = parsedBase.resolve(location);
        if (resolved == null) {
            throw new IOException("无法解析重定向地址");
        }
        return resolved.toString();
    }

    private static String normalizeBaseUrl(String raw) {
        String s = (raw == null || raw.trim().isEmpty())
                ? GitHubUpdateConfig.DEFAULT_API_BASE_URL
                : raw.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public static String translateHttpError(int httpCode, boolean hasPat) {
        switch (httpCode) {
            case 401:
                return "GitHub 鉴权失败 (401)：PAT 无效或已过期，请重新配置";
            case 403:
                return hasPat
                        ? "GitHub 权限不足或限流 (403)：请确认 PAT 含 repo 只读权限"
                        : "GitHub 限流或拒绝访问 (403)：建议配置 PAT 后重试";
            case 404:
                return hasPat
                        ? "未找到仓库或 Release (404)：请检查 owner/repo 与 PAT repo 权限"
                        : "仓库不存在或为私有仓库 (404)：私有仓库请先配置 GitHub PAT";
            default:
                return "GitHub 请求失败: HTTP " + httpCode;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }
}
