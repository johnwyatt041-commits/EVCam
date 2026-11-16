package com.kooo.evcam.playback;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
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
            Glide.with(context).clear(imageView);
            imageView.setImageDrawable(null);
            imageView.setBackgroundColor(emptyBg);
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
}
