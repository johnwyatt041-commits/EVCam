package com.kooo.evcam.ui;

import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Host contract for multi-mode preview containers.
 * Mode B/C share EditablePreviewLayout; T07 will mount 360 GL views into getModeAContainer().
 */
public interface PreviewModeHost {

    @Nullable
    FrameLayout getModeAContainer();

    @Nullable
    EditablePreviewLayout getEditablePreviewLayout();

    void setPreviewMode(@NonNull PreviewMode mode);

    @NonNull
    PreviewMode getCurrentPreviewMode();
}
