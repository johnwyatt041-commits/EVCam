package com.kooo.evcam.playback;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import com.bumptech.glide.Glide;

/** Releases only UI image requests and Glide caches. */
public final class PlaybackImageMemory {
    public static void clearViews(View view) {
        if (view instanceof ImageView) {
            Glide.with(view).clear((ImageView) view);
            ((ImageView) view).setImageDrawable(null);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clearViews(group.getChildAt(i));
        }
    }

    public static void clear(Context context) {
        Glide.get(context.getApplicationContext()).clearMemory();
    }

    private PlaybackImageMemory() {}
}
