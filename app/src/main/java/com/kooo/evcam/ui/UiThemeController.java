package com.kooo.evcam.ui;

import android.content.Context;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import java.util.function.BooleanSupplier;

/** Color resources only; no polling, downloaded fonts or duplicate view trees. */
public final class UiThemeController {
    private final AppCompatActivity activity;
    private final BooleanSupplier recording;
    public UiThemeController(AppCompatActivity activity, BooleanSupplier recording) {
        this.activity = activity;
        this.recording = recording;
    }
    public static int savedMode(Context context) {
        return context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE).getBoolean("light_theme", false)
                ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
    }
    public static void applySavedMode(AppCompatActivity activity) {
        activity.getDelegate().setLocalNightMode(savedMode(activity));
    }
    public void choose() {
        int checked = savedMode(activity) == AppCompatDelegate.MODE_NIGHT_YES ? 0 : 1;
        new MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("界面外观")
                .setSingleChoiceItems(new String[]{"深色", "浅色"}, checked, (dialog, selected) -> {
                    activity.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE).edit()
                            .putBoolean("light_theme", selected == 1).apply();
                    dialog.dismiss();
                    if (recording.getAsBoolean()) {
                        Toast.makeText(activity, "外观已保存，停止录制或重新打开界面后应用", Toast.LENGTH_LONG).show();
                    } else applyIfIdle();
                }).setNegativeButton("取消", null).show();
    }
    public void applyIfIdle() {
        if (!recording.getAsBoolean() && !activity.isFinishing() && !activity.isDestroyed()) applySavedMode(activity);
    }
}
