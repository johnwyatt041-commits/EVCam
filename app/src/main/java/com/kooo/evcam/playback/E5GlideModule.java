package com.kooo.evcam.playback;

import android.content.Context;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool;
import com.bumptech.glide.load.engine.cache.LruResourceCache;
import com.bumptech.glide.module.AppGlideModule;

@GlideModule
public final class E5GlideModule extends AppGlideModule {
    @Override public void applyOptions(Context context, GlideBuilder builder) {
        builder.setMemoryCache(new LruResourceCache(8L * 1024 * 1024));
        builder.setBitmapPool(new LruBitmapPool(4L * 1024 * 1024));
    }
    @Override public boolean isManifestParsingEnabled() { return false; }
}
