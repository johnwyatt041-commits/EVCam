package com.kooo.evcam.playback;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.kooo.evcam.R;

import java.io.File;

/**
 * Shared ViewHolders and bounded thumbnail loaders for {@link ExpandableVideoGroupAdapter}
 * and {@link ExpandablePhotoGroupAdapter}.
 */
final class PlaybackListViewHolders {

    private static final int THUMB_DECODE_WIDTH = 320;
    private static final int THUMB_DECODE_HEIGHT = 200;

    private PlaybackListViewHolders() {}

    static final class DateHeaderViewHolder extends RecyclerView.ViewHolder {
        final ImageView expandIcon;
        final TextView dateText;
        final TextView itemCount;

        DateHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            expandIcon = itemView.findViewById(R.id.expand_icon);
            dateText = itemView.findViewById(R.id.date_text);
            itemCount = itemView.findViewById(R.id.item_count);
        }
    }

    static final class HourHeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView hourText;

        HourHeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            hourText = itemView.findViewById(R.id.hour_header_text);
        }
    }

    static final class ActiveRecordingViewHolder extends RecyclerView.ViewHolder {
        final TextView activeTime;
        final TextView activeDesc;

        ActiveRecordingViewHolder(@NonNull View itemView) {
            super(itemView);
            activeTime = itemView.findViewById(R.id.active_time);
            activeDesc = itemView.findViewById(R.id.active_desc);
        }
    }

    static <T> void bindDateHeader(@NonNull DateHeaderViewHolder holder,
                                   @NonNull Context context,
                                   @NonNull DateSection<T> section,
                                   @NonNull String unitLabel,
                                   @NonNull View.OnClickListener clickListener) {
        holder.dateText.setText(section.getFullDateDisplay());
        holder.dateText.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        holder.itemCount.setText(section.getItemCount() + unitLabel);
        holder.itemCount.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        int iconRes = section.isExpanded() ? R.drawable.ic_expand_less : R.drawable.ic_expand_more;
        holder.expandIcon.setImageResource(iconRes);
        holder.expandIcon.setColorFilter(ContextCompat.getColor(context, R.color.text_secondary));
        holder.itemView.setOnClickListener(clickListener);
    }

    static void bindHourHeader(@NonNull HourHeaderViewHolder holder,
                               @NonNull Context context,
                               @NonNull PlaybackTimelineBuilder.HourHeader header) {
        holder.hourText.setText(header.getDisplayText());
        holder.hourText.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
    }

    static void bindActiveBanner(@NonNull ActiveRecordingViewHolder holder,
                                 @NonNull Context context,
                                 @NonNull PlaybackTimelineBuilder.ActiveRecordingBanner banner,
                                 @NonNull View.OnClickListener clickListener) {
        holder.activeTime.setText(banner.formattedTime);
        holder.activeTime.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        holder.activeDesc.setText("当前 " + banner.activeFileCount + " 路正在录制写入（完成后可回看）");
        holder.activeDesc.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        holder.itemView.setOnClickListener(clickListener);
    }

    static void loadThumbnail(@NonNull Context context,
                              @Nullable File file,
                              @NonNull ImageView imageView,
                              boolean isVideo) {
        int emptyBg = ContextCompat.getColor(context, R.color.preview_frame_background);
        if (file == null || !file.exists() || file.length() <= 0L) {
            PlaybackImageMemory.clearImageView(imageView);
            imageView.setBackgroundColor(emptyBg);
            return;
        }
        if (!PlaybackImageMemory.canLoad(context)) {
            return;
        }
        RequestOptions options = new RequestOptions()
                .override(THUMB_DECODE_WIDTH, THUMB_DECODE_HEIGHT)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(new ObjectKey(file.lastModified()))
                .placeholder(android.R.color.black)
                .error(android.R.color.black);
        if (isVideo) {
            options = options.frame(0);
            Glide.with(context)
                    .asBitmap()
                    .load(file)
                    .apply(options)
                    .into(imageView);
        } else {
            Glide.with(context)
                    .load(file)
                    .apply(options)
                    .into(imageView);
        }
    }

    /**
     * 受限流与负缓存保护的单张视频代表缩略图加载：签名绑定长度与修改时间，并回调成功/失败以释放并发槽位。
     *
     * @return {@code true} 已发起 Glide 请求；{@code false} 未发起（调用方应立即释放并发槽位）
     */
    static boolean loadVideoThumbnailGuarded(@NonNull Context context,
                                             @Nullable File file,
                                             @NonNull ImageView imageView,
                                             @NonNull Runnable onSuccess,
                                             @NonNull Runnable onFailure) {
        int emptyBg = ContextCompat.getColor(context, R.color.preview_frame_background);
        if (file == null || !file.isFile() || file.length() <= 0L) {
            PlaybackImageMemory.clearImageView(imageView);
            imageView.setBackgroundColor(emptyBg);
            return false;
        }
        if (!PlaybackImageMemory.canLoad(context)) {
            return false;
        }
        RequestOptions options = new RequestOptions()
                .override(THUMB_DECODE_WIDTH, THUMB_DECODE_HEIGHT)
                .centerCrop()
                .frame(0)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(new ObjectKey(file.length() + ":" + file.lastModified()))
                .placeholder(android.R.color.black)
                .error(android.R.color.black);
        Glide.with(context)
                .asBitmap()
                .load(file)
                .apply(options)
                .listener(new RequestListener<Bitmap>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e,
                                                @Nullable Object model,
                                                @NonNull Target<Bitmap> target,
                                                boolean isFirstResource) {
                        onFailure.run();
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(@NonNull Bitmap resource,
                                                   @NonNull Object model,
                                                   Target<Bitmap> target,
                                                   @NonNull DataSource dataSource,
                                                   boolean isFirstResource) {
                        onSuccess.run();
                        return false;
                    }
                })
                .into(imageView);
        return true;
    }
}
