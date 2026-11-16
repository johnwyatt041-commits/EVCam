package com.kooo.evcam.update;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.GeneralSecurityException;

/**
 * 私有 GitHub Release 更新配置与 PAT 隔离存储管理：
 * - 普通仓库参数存入独立 github_update_config（不进 app_config / T09 配置广场白名单）；
 * - PAT 经 AndroidKeyStore AES-256-GCM 密文存入独立 github_update_secrets，绝不以明文存储。
 */
public final class GitHubUpdateConfig {

    public static final String CONFIG_PREF_NAME = "github_update_config";
    public static final String SECRETS_PREF_NAME = "github_update_secrets";

    public static final String SOURCE_GITHUB = "github";
    public static final String SOURCE_CUSTOM_HTTP = "custom_http";

    public static final String DEFAULT_OWNER = "johnwyatt041-commits";
    public static final String DEFAULT_REPO = "EVCam";
    public static final String DEFAULT_API_BASE_URL = "https://api.github.com";

    private static final String KEY_SOURCE_MODE = "source_mode";
    private static final String KEY_REPO_OWNER = "repo_owner";
    private static final String KEY_REPO_NAME = "repo_name";
    private static final String KEY_API_BASE_URL = "api_base_url";
    private static final String KEY_INCLUDE_PRERELEASE = "include_prerelease";
    private static final String KEY_ENCRYPTED_PAT = "pat_enc_v1";
    private static final String KEY_PAT_MASKED_HINT = "pat_masked_hint";

    private final SharedPreferences configPrefs;
    private final SharedPreferences secretsPrefs;
    private final PatCipher patCipher;

    public GitHubUpdateConfig(Context context) {
        this(context.getApplicationContext().getSharedPreferences(CONFIG_PREF_NAME, Context.MODE_PRIVATE),
                context.getApplicationContext().getSharedPreferences(SECRETS_PREF_NAME, Context.MODE_PRIVATE),
                new AndroidKeyStorePatCipher());
    }

    public GitHubUpdateConfig(SharedPreferences configPrefs,
                              SharedPreferences secretsPrefs,
                              PatCipher patCipher) {
        this.configPrefs = configPrefs;
        this.secretsPrefs = secretsPrefs;
        this.patCipher = patCipher;
    }

    public String getSourceMode() {
        String mode = configPrefs.getString(KEY_SOURCE_MODE, SOURCE_GITHUB);
        return SOURCE_CUSTOM_HTTP.equals(mode) ? SOURCE_CUSTOM_HTTP : SOURCE_GITHUB;
    }

    public void setSourceMode(String sourceMode) {
        String safe = SOURCE_CUSTOM_HTTP.equals(sourceMode) ? SOURCE_CUSTOM_HTTP : SOURCE_GITHUB;
        configPrefs.edit().putString(KEY_SOURCE_MODE, safe).apply();
    }

    public String getRepoOwner() {
        String owner = configPrefs.getString(KEY_REPO_OWNER, DEFAULT_OWNER);
        return (owner == null || owner.trim().isEmpty()) ? DEFAULT_OWNER : owner.trim();
    }

    public String getRepoName() {
        String repo = configPrefs.getString(KEY_REPO_NAME, DEFAULT_REPO);
        return (repo == null || repo.trim().isEmpty()) ? DEFAULT_REPO : repo.trim();
    }

    public String getRepoSlug() {
        return getRepoOwner() + "/" + getRepoName();
    }

    public boolean saveRepoSlug(String input) {
        String[] parsed = parseRepoSlug(input);
        if (parsed == null) {
            return false;
        }
        configPrefs.edit()
                .putString(KEY_REPO_OWNER, parsed[0])
                .putString(KEY_REPO_NAME, parsed[1])
                .apply();
        return true;
    }

    public String getApiBaseUrl() {
        String base = configPrefs.getString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL);
        if (base == null || base.trim().isEmpty()) {
            return DEFAULT_API_BASE_URL;
        }
        String trimmed = base.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.trim().isEmpty()) {
            configPrefs.edit().remove(KEY_API_BASE_URL).apply();
        } else {
            configPrefs.edit().putString(KEY_API_BASE_URL, apiBaseUrl.trim()).apply();
        }
    }

    public boolean isIncludePrerelease() {
        return configPrefs.getBoolean(KEY_INCLUDE_PRERELEASE, true);
    }

    public void setIncludePrerelease(boolean includePrerelease) {
        configPrefs.edit().putBoolean(KEY_INCLUDE_PRERELEASE, includePrerelease).apply();
    }

    public String buildReleasesListUrl(int perPage) {
        int safeCount = Math.max(1, Math.min(30, perPage));
        return getApiBaseUrl() + "/repos/" + getRepoOwner() + "/" + getRepoName() + "/releases?per_page=" + safeCount;
    }

    /**
     * 加密持久化 PAT；若硬件密钥库加密失败抛出安全异常，绝不降级保存明文。
     */
    public void savePat(String rawPat) throws GeneralSecurityException {
        String trimmed = rawPat != null ? rawPat.trim() : "";
        if (trimmed.isEmpty()) {
            clearPat();
            return;
        }
        String encrypted = patCipher.encrypt(trimmed);
        if (encrypted == null || encrypted.isEmpty() || encrypted.equals(trimmed)) {
            throw new GeneralSecurityException("Refusing unencrypted PAT storage");
        }
        secretsPrefs.edit()
                .putString(KEY_ENCRYPTED_PAT, encrypted)
                .putString(KEY_PAT_MASKED_HINT, maskPat(trimmed))
                .apply();
    }

    public String loadPat() {
        String enc = secretsPrefs.getString(KEY_ENCRYPTED_PAT, null);
        if (enc == null || enc.isEmpty()) {
            return null;
        }
        try {
            String plain = patCipher.decrypt(enc);
            return (plain != null && !plain.trim().isEmpty()) ? plain.trim() : null;
        } catch (GeneralSecurityException e) {
            // KeyStore 密钥失效或密文损坏：清除损坏条目，提示用户重新输入
            clearPat();
            return null;
        }
    }

    public void clearPat() {
        secretsPrefs.edit()
                .remove(KEY_ENCRYPTED_PAT)
                .remove(KEY_PAT_MASKED_HINT)
                .apply();
    }

    public boolean hasPat() {
        String enc = secretsPrefs.getString(KEY_ENCRYPTED_PAT, null);
        return enc != null && !enc.isEmpty();
    }

    public String getMaskedPatHint() {
        if (!hasPat()) {
            return "未配置";
        }
        String hint = secretsPrefs.getString(KEY_PAT_MASKED_HINT, null);
        return (hint != null && !hint.isEmpty()) ? hint : "已加密保存";
    }

    public static String maskPat(String pat) {
        if (pat == null || pat.trim().isEmpty()) {
            return "未配置";
        }
        String trimmed = pat.trim();
        if (trimmed.length() <= 8) {
            return "****" + trimmed.substring(Math.max(0, trimmed.length() - 2));
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * 支持输入 "owner/repo" 或 "https://github.com/owner/repo(.git)"。
     */
    public static String[] parseRepoSlug(String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String s = rawInput.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.startsWith("https://github.com/")) {
            s = s.substring("https://github.com/".length());
        } else if (s.startsWith("http://github.com/")) {
            s = s.substring("http://github.com/".length());
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        String[] parts = s.split("/");
        if (parts.length != 2) {
            return null;
        }
        String owner = parts[0].trim();
        String repo = parts[1].trim();
        if (!isValidGitHubSegment(owner) || !isValidGitHubSegment(repo)) {
            return null;
        }
        return new String[]{owner, repo};
    }

    private static boolean isValidGitHubSegment(String seg) {
        if (seg == null || seg.isEmpty()) {
            return false;
        }
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) {
                return false;
            }
        }
        return !".".equals(seg) && !"..".equals(seg);
    }
}
