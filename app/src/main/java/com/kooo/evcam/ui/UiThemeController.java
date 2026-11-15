package com.kooo.evcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.R;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 三态主题（跟随系统 / 深色 / 浅色）与零重建原地切换控制器。
 * 通过 Manifest 接管 uiMode configChanges，结合目标 Configuration 解析色表原地重涂视图与系统栏，
 * 确保录制中切换外观不销毁/重建 MainActivity，不断录。
 */
public final class UiThemeController {

    public static final String PREFS_NAME = "ui_preferences";
    public static final String KEY_THEME_MODE = "theme_mode";
    public static final String KEY_LEGACY_LIGHT_THEME = "light_theme";

    public enum ThemeMode {
        FOLLOW_SYSTEM("follow_system", "跟随系统", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        DARK("dark", "深色", AppCompatDelegate.MODE_NIGHT_YES),
        LIGHT("light", "浅色", AppCompatDelegate.MODE_NIGHT_NO);

        public final String key;
        public final String label;
        public final int delegateMode;

        ThemeMode(String key, String label, int delegateMode) {
            this.key = key;
            this.label = label;
            this.delegateMode = delegateMode;
        }

        public static ThemeMode fromKey(@Nullable String key) {
            if (DARK.key.equals(key)) return DARK;
            if (LIGHT.key.equals(key)) return LIGHT;
            if (FOLLOW_SYSTEM.key.equals(key)) return FOLLOW_SYSTEM;
            return null;
        }
    }

    /** 支持原地主题刷新的界面或自定义视图回调。 */
    public interface ThemeAware {
        void onThemeApplied(Context themedContext, boolean isNight);
    }

    /** 宿主 Activity 提供当前 UiThemeController 实例，避免子页强耦合具体 Activity 类。 */
    public interface Host {
        @Nullable
        UiThemeController getUiThemeController();
    }

    private final AppCompatActivity activity;
    private final BooleanSupplier recording;
    private final BooleanSupplier layoutEditing;
    @Nullable
    private AppShellController shellController;
    private boolean applyingInPlace;

    public UiThemeController(AppCompatActivity activity, BooleanSupplier recording) {
        this(activity, recording, () -> false);
    }

    public UiThemeController(AppCompatActivity activity, BooleanSupplier recording, BooleanSupplier layoutEditing) {
        this.activity = activity;
        this.recording = recording != null ? recording : () -> false;
        this.layoutEditing = layoutEditing != null ? layoutEditing : () -> false;
    }

    public void setShellController(@Nullable AppShellController shellController) {
        this.shellController = shellController;
    }

    /**
     * 读取保存的主题模式：
     * 1. 优先读取新版三态 key；
     * 2. 未设置时向下兼容旧版 light_theme（true -> LIGHT, false -> DARK）；
     * 3. 全新安装默认 FOLLOW_SYSTEM。
     */
    @NonNull
    public static ThemeMode savedThemeMode(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String storedKey = prefs.getString(KEY_THEME_MODE, null);
        ThemeMode parsed = ThemeMode.fromKey(storedKey);
        if (parsed != null) {
            return parsed;
        }
        if (prefs.contains(KEY_LEGACY_LIGHT_THEME)) {
            return prefs.getBoolean(KEY_LEGACY_LIGHT_THEME, false) ? ThemeMode.LIGHT : ThemeMode.DARK;
        }
        return ThemeMode.FOLLOW_SYSTEM;
    }

    public static void saveThemeMode(@NonNull Context context, @NonNull ThemeMode mode) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_THEME_MODE, mode.key);
        if (mode == ThemeMode.LIGHT) {
            editor.putBoolean(KEY_LEGACY_LIGHT_THEME, true);
        } else if (mode == ThemeMode.DARK) {
            editor.putBoolean(KEY_LEGACY_LIGHT_THEME, false);
        } else {
            editor.remove(KEY_LEGACY_LIGHT_THEME);
        }
        editor.apply();
    }

    public static int savedMode(@NonNull Context context) {
        return savedThemeMode(context).delegateMode;
    }

    /** 根据三态策略与系统 uiMode 解析最终夜间掩码。 */
    public static int resolveNightMask(@NonNull ThemeMode mode, int systemUiMode) {
        if (mode == ThemeMode.DARK) return Configuration.UI_MODE_NIGHT_YES;
        if (mode == ThemeMode.LIGHT) return Configuration.UI_MODE_NIGHT_NO;
        return (systemUiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
    }

    public static boolean isTargetNight(@NonNull Context context, @Nullable Configuration systemConfig) {
        ThemeMode mode = savedThemeMode(context);
        int sysUiMode = systemConfig != null ? systemConfig.uiMode : resolveSystemUiMode(context);
        return resolveNightMask(mode, sysUiMode) == Configuration.UI_MODE_NIGHT_YES;
    }

    public static boolean shouldRecreateActivityOnThemeChange() {
        return false;
    }

    public static void applySavedMode(@NonNull AppCompatActivity activity) {
        AppCompatDelegate delegate = activity.getDelegate();
        if (delegate != null) {
            applyLocalNightMode(delegate, savedThemeMode(activity));
        }
    }

    private static void applyLocalNightMode(@NonNull AppCompatDelegate delegate, @NonNull ThemeMode mode) {
        switch (mode) {
            case DARK:
                delegate.setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case LIGHT:
                delegate.setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case FOLLOW_SYSTEM:
            default:
                delegate.setLocalNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    /**
     * 运行期切换主题模式：持久化并原地重涂界面与系统栏，不重建 MainActivity。
     * 录像期间允许直接切换且不断录；仅在布局编辑态时提示先保存或取消布局编辑。
     */
    public boolean selectThemeMode(@NonNull ThemeMode mode) {
        if (layoutEditing.getAsBoolean()) {
            Toast toast = Toast.makeText(activity, "请先保存或取消布局编辑", Toast.LENGTH_SHORT);
            if (toast != null) {
                toast.show();
            }
            return false;
        }
        saveThemeMode(activity, mode);
        applyCurrentModeInPlace(null);
        return true;
    }

    public void onConfigurationChanged(@Nullable Configuration newConfig) {
        if (applyingInPlace) return;
        applyCurrentModeInPlace(newConfig);
    }

    public void syncSystemBarsAndTree() {
        if (applyingInPlace || activity.isFinishing() || activity.isDestroyed()) return;
        applyCurrentModeInPlace(null);
    }

    public void applyIfIdle() {
        syncSystemBarsAndTree();
    }

    public boolean isCurrentThemeNight() {
        return isTargetNight(activity, null);
    }

    public boolean isSystemCurrentlyNight() {
        return (resolveSystemUiMode(activity) & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyCurrentModeInPlace(@Nullable Configuration incomingConfig) {
        if (applyingInPlace || activity.isFinishing() || activity.isDestroyed()) return;
        applyingInPlace = true;
        try {
            ThemeMode mode = savedThemeMode(activity);
            int systemUiMode = (mode == ThemeMode.FOLLOW_SYSTEM && incomingConfig != null)
                    ? incomingConfig.uiMode
                    : resolveSystemUiMode(activity);
            int targetMask = resolveNightMask(mode, systemUiMode);
            boolean targetNight = targetMask == Configuration.UI_MODE_NIGHT_YES;

            AppCompatDelegate delegate = activity.getDelegate();
            if (delegate != null && delegate.getLocalNightMode() != mode.delegateMode) {
                applyLocalNightMode(delegate, mode);
            }

            syncActivityResourcesNightMask(targetMask);
            repaintWindowAndViews(targetMask, targetNight);
        } finally {
            applyingInPlace = false;
        }
    }

    private static int resolveSystemUiMode(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        Resources appResources = appContext != null ? appContext.getResources() : context.getResources();
        if (appResources != null && appResources.getConfiguration() != null) {
            return appResources.getConfiguration().uiMode;
        }
        Resources res = context.getResources();
        return res != null && res.getConfiguration() != null
                ? res.getConfiguration().uiMode
                : Configuration.UI_MODE_NIGHT_NO;
    }

    @SuppressWarnings("deprecation")
    private void syncActivityResourcesNightMask(int targetMask) {
        Resources resources = activity.getResources();
        if (resources == null || resources.getConfiguration() == null) return;
        Configuration current = resources.getConfiguration();
        if ((current.uiMode & Configuration.UI_MODE_NIGHT_MASK) != targetMask) {
            Configuration updated = new Configuration(current);
            updated.uiMode = (updated.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | targetMask;
            resources.updateConfiguration(updated, resources.getDisplayMetrics());
        }
    }

    private void repaintWindowAndViews(int targetMask, boolean targetNight) {
        Window window = activity.getWindow();
        if (window == null) return;
        Context themedContext = UiThemeRepainter.createNightContext(activity, targetMask);

        int pageBg = ContextCompat.getColor(themedContext, R.color.page_background);
        window.setBackgroundDrawable(new ColorDrawable(pageBg));
        window.setStatusBarColor(pageBg);
        window.setNavigationBarColor(pageBg);

        View decorView = window.getDecorView();
        if (decorView != null) {
            WindowInsetsControllerCompat bars = new WindowInsetsControllerCompat(window, decorView);
            bars.setAppearanceLightStatusBars(!targetNight);
            bars.setAppearanceLightNavigationBars(!targetNight);
            UiThemeRepainter.repaintHierarchy(decorView, activity, targetNight);
        }

        if (shellController != null) {
            shellController.refreshTheme(themedContext, targetNight);
        }

        if (activity instanceof ThemeAware) {
            ((ThemeAware) activity).onThemeApplied(themedContext, targetNight);
        }

        notifyThemeAwareFragments(activity.getSupportFragmentManager().getFragments(), themedContext, targetNight);
    }

    private void notifyThemeAwareFragments(@Nullable List<Fragment> fragments, Context themedContext, boolean isNight) {
        if (fragments == null) return;
        for (Fragment fragment : fragments) {
            if (fragment == null) continue;
            if (fragment instanceof ThemeAware) {
                ((ThemeAware) fragment).onThemeApplied(themedContext, isNight);
            }
            if (fragment.isAdded()) {
                notifyThemeAwareFragments(fragment.getChildFragmentManager().getFragments(), themedContext, isNight);
            }
        }
    }
}
