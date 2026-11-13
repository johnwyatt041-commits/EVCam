package com.kooo.evcam;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/** Removes optional settings cards while keeping the shared layouts usable. */
final class OptionalFeatureViews {
    private OptionalFeatureViews() {}

    static void hideRow(View root, int controlId) {
        View control = root.findViewById(controlId);
        if (control != null && control.getParent() instanceof View) {
            ((View) control.getParent()).setVisibility(View.GONE);
        }
    }

    static void hideCard(View root, int controlId) {
        View control = root.findViewById(controlId);
        if (control == null) return;
        ViewParent parent = control.getParent();
        for (int i = 0; i < 3 && parent instanceof ViewGroup && parent != root; i++) {
            ViewGroup group = (ViewGroup) parent;
            if (group.getBackground() != null) {
                group.setVisibility(View.GONE);
                return;
            }
            parent = group.getParent();
        }
        control.setVisibility(View.GONE);
    }
}
