package com.kooo.evcam.playback;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.bumptech.glide.Glide;

/** Releases only UI image requests and Glide caches, safely skipping destroyed hosts. */
public final class PlaybackImageMemory {

    private static final int MAX_CONTEXT_UNWRAP_DEPTH = 16;

    interface GlideTargetClearer {
        void clear(@NonNull ImageView imageView);
    }

    private static final GlideTargetClearer DEFAULT_GLIDE_CLEARER =
            imageView -> Glide.with(imageView).clear(imageView);

    public static void clearViews(@Nullable View view) {
        clearViews(view, DEFAULT_GLIDE_CLEARER);
    }

    @VisibleForTesting
    static void clearViews(@Nullable View view, @NonNull GlideTargetClearer clearer) {
        if (view == null) {
            return;
        }
        if (view instanceof ImageView) {
            clearImageView((ImageView) view, clearer);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                clearViews(group.getChildAt(i), clearer);
            }
        }
    }

    public static void clearImageView(@Nullable ImageView imageView) {
        clearImageView(imageView, DEFAULT_GLIDE_CLEARER);
    }

    @VisibleForTesting
    static void clearImageView(@Nullable ImageView imageView, @NonNull GlideTargetClearer clearer) {
        if (imageView == null) {
            return;
        }
        if (canClear(imageView.getContext())) {
            try {
                clearer.clear(imageView);
            } catch (IllegalArgumentException ignored) {
                // Host activity/fragment may become destroyed mid-teardown; Glide clears targets via Lifecycle.
            }
        }
        imageView.setImageDrawable(null);
    }

    public static boolean canClear(@Nullable Context context) {
        if (context == null) {
            return false;
        }
        Activity activity = findActivity(context);
        return activity == null || !activity.isDestroyed();
    }

    public static boolean canLoad(@Nullable Context context) {
        if (context == null) {
            return false;
        }
        Activity activity = findActivity(context);
        return activity == null || (!activity.isDestroyed() && !activity.isFinishing());
    }

    @Nullable
    static Activity findActivity(@Nullable Context context) {
        Context current = context;
        for (int depth = 0; current != null && depth < MAX_CONTEXT_UNWRAP_DEPTH; depth++) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (!(current instanceof ContextWrapper)) {
                return null;
            }
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) {
                return null;
            }
            current = next;
        }
        return null;
    }

    public static void clear(@Nullable Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            Glide.get(appContext).clearMemory();
        }
    }

    private PlaybackImageMemory() {}
}
