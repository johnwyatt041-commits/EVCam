package com.kooo.evcam;

import android.graphics.Color;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Covers inactive previews without destroying the surfaces needed for remote photos. */
final class PreviewVisibilityController {
    private final List<TextView> placeholders = new ArrayList<>();

    PreviewVisibilityController(Runnable enablePreview, TextureView... textures) {
        for (TextureView texture : textures) {
            if (texture == null || !(texture.getParent() instanceof ViewGroup)) continue;
            ViewGroup parent = (ViewGroup) texture.getParent();
            TextView placeholder = new TextView(texture.getContext());
            placeholder.setText("实时预览已关闭\n点按开启预览");
            placeholder.setTextColor(Color.LTGRAY);
            placeholder.setTextSize(16);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackgroundColor(Color.BLACK);
            placeholder.setOnClickListener(v -> enablePreview.run());
            parent.addView(placeholder, parent.indexOfChild(texture) + 1,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            placeholders.add(placeholder);
        }
    }

    void setPreviewEnabled(boolean enabled) {
        for (TextView placeholder : placeholders) {
            placeholder.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
    }
}
