package com.kooo.evcam.ui;

import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import java.util.function.Consumer;

/** Editor controls are inflated on entry and removed on exit. */
public final class PreviewEditorController {
    private final AppCompatActivity activity;
    private final EditablePreviewLayout layout;
    private final ViewGroup host;
    private final Consumer<Boolean> changed;
    public PreviewEditorController(AppCompatActivity activity, Consumer<Boolean> changed) {
        this.activity = activity;
        layout = activity.findViewById(R.id.ui_preview_layout);
        host = activity.findViewById(R.id.ui_preview_editor_host);
        this.changed = changed;
    }
    public boolean isAvailable() { return layout != null && host != null; }
    public boolean isEditing() { return isAvailable() && layout.isEditing(); }
    public void begin() {
        if (!isAvailable() || isEditing()) return;
        layout.beginEditing();
        View controls = activity.getLayoutInflater().inflate(R.layout.ui_preview_editor, host, false);
        host.addView(controls); host.setVisibility(View.VISIBLE);
        controls.findViewById(R.id.ui_layout_presets).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(activity).setTitle("选择预览排版")
                        .setItems(new String[]{"两侧补盲 · 中间前后", "四宫格", "前方大画面"},
                                (dialog, preset) -> layout.applyPreset(preset)).show());
        controls.findViewById(R.id.ui_layout_reset).setOnClickListener(v -> layout.applyPreset(0));
        controls.findViewById(R.id.ui_layout_cancel).setOnClickListener(v -> finish(false));
        controls.findViewById(R.id.ui_layout_save).setOnClickListener(v -> finish(true));
        changed.accept(true);
    }
    public void finish(boolean save) {
        if (!isEditing()) return;
        layout.finishEditing(save); host.removeAllViews(); host.setVisibility(View.GONE); changed.accept(false);
    }
}
