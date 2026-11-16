package com.kooo.evcam.feishu;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 飞书配置存储工具类（独立于 T09 配置广场白名单）。
 */
public class FeishuConfig {
    public static final String PREF_NAME = "feishu_config";
    private static final String KEY_APP_ID = "app_id";
    private static final String KEY_APP_SECRET = "app_secret";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_TOKEN_EXPIRE_TIME = "token_expire_time";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_ALLOWED_USER_IDS = "allowed_user_ids";
    private static final String KEY_FEISHU_ENABLED = "feishu_enabled";
    private static final String KEY_P_GEAR_ONLY = "p_gear_only";

    private final SharedPreferences prefs;

    public FeishuConfig(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE));
    }

    public FeishuConfig(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * 轻量静态判断：仅读取本地配置标志，不创建任何服务单例、客户端或车况监听租约。
     * 用于确保未配置或未启用飞书时纯后台录像零远程初始化。
     */
    public static boolean isFeatureActive(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return isFeatureActive(prefs);
    }

    public static boolean isFeatureActive(SharedPreferences prefs) {
        return isConfigured(prefs) && isEnabled(prefs);
    }

    public static boolean isConfigured(SharedPreferences prefs) {
        if (prefs == null) {
            return false;
        }
        String appId = prefs.getString(KEY_APP_ID, "");
        String appSecret = prefs.getString(KEY_APP_SECRET, "");
        return appId != null && !appId.isEmpty() && appSecret != null && !appSecret.isEmpty();
    }

    /**
     * 读取飞书总开关：
     * - 若用户曾显式保存过 feishu_enabled，严格按其值返回；
     * - 若为 build11 及更早老用户升级（未保存过 feishu_enabled），当且仅当已配置凭据且原 auto_start=true 时映射保留为开启。
     */
    public static boolean isEnabled(SharedPreferences prefs) {
        if (prefs == null) {
            return false;
        }
        if (prefs.contains(KEY_FEISHU_ENABLED)) {
            return prefs.getBoolean(KEY_FEISHU_ENABLED, false);
        }
        return isConfigured(prefs) && prefs.getBoolean(KEY_AUTO_START, false);
    }

    public void saveConfig(String appId, String appSecret) {
        saveConfig(appId, appSecret, getAllowedUserIds());
    }

    public void saveConfig(String appId, String appSecret, String allowedUserIds) {
        String safeAppId = appId != null ? appId.trim() : "";
        String safeSecret = appSecret != null ? appSecret.trim() : "";
        String safeUsers = allowedUserIds != null ? allowedUserIds.trim() : "";

        boolean credentialsChanged = !safeAppId.equals(getAppId()) || !safeSecret.equals(getAppSecret());
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_APP_ID, safeAppId)
                .putString(KEY_APP_SECRET, safeSecret)
                .putString(KEY_ALLOWED_USER_IDS, safeUsers);
        if (credentialsChanged) {
            editor.remove(KEY_ACCESS_TOKEN).remove(KEY_TOKEN_EXPIRE_TIME);
        }
        editor.apply();
    }

    public String getAppId() {
        String value = prefs.getString(KEY_APP_ID, "");
        return value != null ? value : "";
    }

    public String getAppSecret() {
        String value = prefs.getString(KEY_APP_SECRET, "");
        return value != null ? value : "";
    }

    public String getAllowedUserIds() {
        String value = prefs.getString(KEY_ALLOWED_USER_IDS, "");
        return value != null ? value : "";
    }

    public boolean isUserIdAllowed(String userId) {
        String allowedIds = getAllowedUserIds();
        if (allowedIds.isEmpty()) {
            return true;
        }
        String[] ids = allowedIds.split(",");
        for (String id : ids) {
            if (id.trim().equals(userId)) {
                return true;
            }
        }
        return false;
    }

    public boolean isConfigured() {
        return isConfigured(prefs);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit()
                .putBoolean(KEY_FEISHU_ENABLED, enabled)
                .putBoolean(KEY_AUTO_START, enabled)
                .apply();
    }

    public boolean isEnabled() {
        return isEnabled(prefs);
    }

    public void setPGearOnly(boolean pGearOnly) {
        prefs.edit().putBoolean(KEY_P_GEAR_ONLY, pGearOnly).apply();
    }

    /** 默认开启仅在 P 档连接，配合总开关完成懒加载与非 P 档彻底断连。 */
    public boolean isPGearOnly() {
        return prefs.getBoolean(KEY_P_GEAR_ONLY, true);
    }

    public void saveAccessToken(String token, long expireTime) {
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, token)
                .putLong(KEY_TOKEN_EXPIRE_TIME, expireTime)
                .apply();
    }

    public String getAccessToken() {
        String value = prefs.getString(KEY_ACCESS_TOKEN, "");
        return value != null ? value : "";
    }

    public boolean isTokenValid() {
        long expireTime = prefs.getLong(KEY_TOKEN_EXPIRE_TIME, 0);
        return System.currentTimeMillis() < expireTime;
    }

    public void clearAccessToken() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_TOKEN_EXPIRE_TIME)
                .apply();
    }

    public void setAutoStart(boolean autoStart) {
        setEnabled(autoStart);
    }

    public boolean isAutoStart() {
        return isEnabled();
    }

    public void clearConfig() {
        prefs.edit().clear().apply();
    }
}
