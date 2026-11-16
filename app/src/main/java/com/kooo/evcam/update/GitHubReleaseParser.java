package com.kooo.evcam.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * GitHub Releases REST API JSON 解析器与 A/B 风味包资产匹配器（基于 Gson 手动节点解析，R8 安全且纯 JVM 可测）。
 */
public final class GitHubReleaseParser {

    private GitHubReleaseParser() {
    }

    public static final class ReleaseAsset {
        public final long id;
        public final String name;
        public final String apiUrl;
        public final String browserDownloadUrl;
        public final long sizeBytes;
        public final String sha256Digest;

        public ReleaseAsset(long id, String name, String apiUrl, String browserDownloadUrl,
                            long sizeBytes, String sha256Digest) {
            this.id = id;
            this.name = name != null ? name : "";
            this.apiUrl = apiUrl != null ? apiUrl : "";
            this.browserDownloadUrl = browserDownloadUrl != null ? browserDownloadUrl : "";
            this.sizeBytes = Math.max(0L, sizeBytes);
            this.sha256Digest = normalizeSha256(sha256Digest);
        }

        public boolean isApk() {
            return name.toLowerCase(Locale.US).endsWith(".apk");
        }
    }

    public static final class ReleaseInfo {
        public final String tagName;
        public final String version;
        public final String title;
        public final String body;
        public final boolean draft;
        public final boolean prerelease;
        public final String publishedAt;
        public final List<ReleaseAsset> assets;

        public ReleaseInfo(String tagName, String title, String body, boolean draft,
                           boolean prerelease, String publishedAt, List<ReleaseAsset> assets) {
            this.tagName = tagName != null ? tagName.trim() : "";
            this.version = AppVersionComparator.normalizeVersion(this.tagName);
            this.title = title != null ? title.trim() : "";
            this.body = body != null ? body.trim() : "";
            this.draft = draft;
            this.prerelease = prerelease;
            this.publishedAt = publishedAt != null ? publishedAt.trim() : "";
            this.assets = assets != null
                    ? Collections.unmodifiableList(new ArrayList<>(assets))
                    : Collections.emptyList();
        }
    }

    public static final class UpdateCandidate {
        public final ReleaseInfo release;
        public final ReleaseAsset asset;

        public UpdateCandidate(ReleaseInfo release, ReleaseAsset asset) {
            this.release = release;
            this.asset = asset;
        }
    }

    public static List<ReleaseInfo> parseReleases(String jsonBody, boolean includePrerelease) {
        List<ReleaseInfo> result = new ArrayList<>();
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            return result;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(jsonBody.trim());
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("GitHub Release JSON 语法错误: " + e.getMessage(), e);
        }

        if (root.isJsonArray()) {
            JsonArray array = root.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement el = array.get(i);
                if (el != null && el.isJsonObject()) {
                    ReleaseInfo info = parseSingleReleaseObject(el.getAsJsonObject());
                    if (isEligibleRelease(info, includePrerelease)) {
                        result.add(info);
                    }
                }
            }
        } else if (root.isJsonObject()) {
            ReleaseInfo info = parseSingleReleaseObject(root.getAsJsonObject());
            if (isEligibleRelease(info, includePrerelease)) {
                result.add(info);
            }
        }
        return result;
    }

    private static boolean isEligibleRelease(ReleaseInfo info, boolean includePrerelease) {
        if (info == null || info.draft) {
            return false;
        }
        if (info.prerelease && !includePrerelease) {
            return false;
        }
        return AppVersionComparator.isValidVersionFormat(info.version);
    }

    private static ReleaseInfo parseSingleReleaseObject(JsonObject obj) {
        String tagName = optString(obj, "tag_name", "");
        String name = optString(obj, "name", tagName);
        String body = optString(obj, "body", "");
        boolean draft = optBoolean(obj, "draft", false);
        boolean prerelease = optBoolean(obj, "prerelease", false);
        String publishedAt = optString(obj, "published_at", "");

        List<ReleaseAsset> assets = new ArrayList<>();
        JsonElement assetsEl = obj.get("assets");
        if (assetsEl != null && assetsEl.isJsonArray()) {
            JsonArray assetsArr = assetsEl.getAsJsonArray();
            for (int i = 0; i < assetsArr.size(); i++) {
                JsonElement item = assetsArr.get(i);
                if (item == null || !item.isJsonObject()) {
                    continue;
                }
                JsonObject assetObj = item.getAsJsonObject();
                assets.add(new ReleaseAsset(
                        optLong(assetObj, "id", 0L),
                        optString(assetObj, "name", ""),
                        optString(assetObj, "url", ""),
                        optString(assetObj, "browser_download_url", ""),
                        optLong(assetObj, "size", 0L),
                        optString(assetObj, "digest", "")
                ));
            }
        }
        return new ReleaseInfo(tagName, name, body, draft, prerelease, publishedAt, assets);
    }

    /**
     * 按当前风味（independent A 版 / composite B 版）精确挑选 APK 资产。
     * 若 Release 只上传了另一风味的 APK，严格返回 null，防止 A/B 混装覆写。
     */
    public static ReleaseAsset selectAssetForFlavor(List<ReleaseAsset> assets, boolean isCompositeFlavor) {
        if (assets == null || assets.isEmpty()) {
            return null;
        }
        List<ReleaseAsset> apkAssets = new ArrayList<>();
        for (ReleaseAsset asset : assets) {
            if (asset != null && asset.isApk()) {
                apkAssets.add(asset);
            }
        }
        if (apkAssets.isEmpty()) {
            return null;
        }

        String targetKeyword = isCompositeFlavor ? "composite" : "independent";
        String oppositeKeyword = isCompositeFlavor ? "independent" : "composite";

        for (ReleaseAsset apk : apkAssets) {
            String lower = apk.name.toLowerCase(Locale.US);
            if (lower.contains(targetKeyword) && lower.contains("release")) {
                return apk;
            }
        }
        for (ReleaseAsset apk : apkAssets) {
            if (apk.name.toLowerCase(Locale.US).contains(targetKeyword)) {
                return apk;
            }
        }

        // 若唯一 APK 且不带相反风味标签，允许通用单包兜底
        if (apkAssets.size() == 1) {
            String lower = apkAssets.get(0).name.toLowerCase(Locale.US);
            if (!lower.contains(oppositeKeyword)) {
                return apkAssets.get(0);
            }
        }
        return null;
    }

    /**
     * 在候选 Release 列表中找到版本号严格高于 currentVersion 且包含匹配当前风味 APK 的最高版本。
     */
    public static UpdateCandidate findLatestUpdate(List<ReleaseInfo> releases,
                                                   String currentVersion,
                                                   boolean isCompositeFlavor) {
        if (releases == null || releases.isEmpty()) {
            return null;
        }
        ReleaseInfo bestRelease = null;
        ReleaseAsset bestAsset = null;
        for (ReleaseInfo candidate : releases) {
            if (candidate == null) {
                continue;
            }
            if (!AppVersionComparator.isNewerVersion(candidate.version, currentVersion)) {
                continue;
            }
            ReleaseAsset asset = selectAssetForFlavor(candidate.assets, isCompositeFlavor);
            if (asset == null) {
                continue;
            }
            if (bestRelease == null || AppVersionComparator.isNewerVersion(candidate.version, bestRelease.version)) {
                bestRelease = candidate;
                bestAsset = asset;
            }
        }
        return bestRelease != null ? new UpdateCandidate(bestRelease, bestAsset) : null;
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        try {
            return el.getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean optBoolean(JsonObject obj, String key, boolean fallback) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        try {
            return el.getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long optLong(JsonObject obj, String key, long fallback) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return fallback;
        }
        try {
            return el.getAsLong();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String normalizeSha256(String digest) {
        if (digest == null) {
            return "";
        }
        String trimmed = digest.trim().toLowerCase(Locale.US);
        if (trimmed.startsWith("sha256:")) {
            trimmed = trimmed.substring("sha256:".length()).trim();
        }
        return trimmed.length() == 64 ? trimmed : "";
    }
}
