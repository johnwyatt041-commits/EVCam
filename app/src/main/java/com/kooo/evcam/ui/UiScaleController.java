package com.kooo.evcam.ui;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 原地“菜单与按钮缩放”（标准自适应 / 车机大号）控制器。
 * 与 {@link UiThemeController} 同构：持久化到 {@link AppConfig}，并在不重建 MainActivity、不断录的前提下，
 * 同步刷新壳层（左侧导航栏、顶栏、顶栏按钮、主预览录制按钮与角标快捷键）及各设置/回看页按钮尺寸。
 */
public final class UiScaleController {

    /** 支持原地缩放刷新的界面回调。 */
    public interface ScaleAware {
        void onUiScaleApplied(float scaleFactor);
    }

    /** 宿主 Activity 提供当前 UiScaleController 实例。 */
    public interface Host {
        @Nullable
        UiScaleController getUiScaleController();
    }

    private final AppCompatActivity activity;
    private final BooleanSupplier layoutEditing;
    @Nullable
    private AppShellController shellController;
    private boolean applyingInPlace;

    public UiScaleController(@NonNull AppCompatActivity activity, @Nullable BooleanSupplier layoutEditing) {
        this.activity = activity;
        this.layoutEditing = layoutEditing != null ? layoutEditing : () -> false;
    }

    public void setShellController(@Nullable AppShellController shellController) {
        this.shellController = shellController;
    }

    public static float getSavedScaleFactor(@NonNull Context context) {
        return UiScaleHelper.normalizeScale(new AppConfig(context).getUiScaleMultiplier());
    }

    @NonNull
    public String getSavedScaleMode() {
        return new AppConfig(activity).getUiScaleMode();
    }

    /**
     * 切换缩放档位：布局编辑态提示先保存或取消，其余状态（含录像中）原地生效，不重建 Activity。
     */
    public boolean selectUiScaleMode(@Nullable String mode) {
        if (layoutEditing.getAsBoolean()) {
            Toast toast = Toast.makeText(activity, "请先保存或取消布局编辑", Toast.LENGTH_SHORT);
            if (toast != null) {
                toast.show();
            }
            return false;
        }
        AppConfig config = new AppConfig(activity);
        config.setUiScaleMode(mode);
        applySavedScaleInPlace();
        return true;
    }

    public void applySavedScaleInPlace() {
        if (applyingInPlace || activity.isFinishing() || activity.isDestroyed()) return;
        applyingInPlace = true;
        try {
            float scale = getSavedScaleFactor(activity);
            if (shellController != null) {
                shellController.applyUiScale(scale);
            }
            notifyScaleAwareFragments(activity.getSupportFragmentManager().getFragments(), scale);
            UiScaleHelper.applyScaleToViewTree(activity.findViewById(R.id.ui_preview_editor_host), scale);
        } finally {
            applyingInPlace = false;
        }
    }

    private void notifyScaleAwareFragments(@Nullable List<Fragment> fragments, float scaleFactor) {
        if (fragments == null) return;
        for (Fragment fragment : fragments) {
            if (fragment == null) continue;
            View root = fragment.getView();
            if (root != null) {
                UiScaleHelper.applyScaleToViewTree(root, scaleFactor);
            }
            if (fragment instanceof ScaleAware) {
                ((ScaleAware) fragment).onUiScaleApplied(scaleFactor);
            }
            if (fragment.isAdded()) {
                notifyScaleAwareFragments(fragment.getChildFragmentManager().getFragments(), scaleFactor);
            }
        }
    }
}
