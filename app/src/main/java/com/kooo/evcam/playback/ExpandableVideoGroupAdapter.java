package com.kooo.evcam.playback;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.kooo.evcam.R;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Expandable date + hourly timeline adapter for video playback groups.
 * Lazily probes segment durations on a single background worker and enforces {@code 320x200} Glide thumbs.
 */
public class ExpandableVideoGroupAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_DATE_HEADER = 0;
    public static final int VIEW_TYPE_VIDEO_GROUP = 1;
    public static final int VIEW_TYPE_HOUR_HEADER = 2;
    public static final int VIEW_TYPE_ACTIVE_RECORDING = 3;

    private final Context context;
    private final List<DateSection<VideoGroup>> dateSections;
    private final List<Object> flattenedItems = new ArrayList<>();
    private final ConcurrentHashMap<String, String> durationCache = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService durationExecutor;

    @Nullable
    private PlaybackTimelineBuilder.ActiveRecordingBanner activeRecordingBanner;
    private Set<VideoGroup> selectedGroups = new HashSet<>();
    private int selectedPosition = -1;
    private boolean isMultiSelectMode = false;

    private OnItemClickListener itemClickListener;
    private OnItemSelectedListener itemSelectedListener;
    private OnDateHeaderClickListener dateHeaderClickListener;
    private OnItemLongClickListener itemLongClickListener;

    public interface OnItemClickListener {
        void onItemClick(VideoGroup group, int position);
    }

    public interface OnItemSelectedListener {
        void onItemSelected(VideoGroup group);
    }

    public interface OnDateHeaderClickListener {
        void onDateHeaderClick(DateSection<VideoGroup> section, int position);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(VideoGroup group, int position);
    }

    public ExpandableVideoGroupAdapter(Context context, List<DateSection<VideoGroup>> dateSections) {
        this.context = context;
        this.dateSections = dateSections;
        buildFlattenedList();
    }

    public void setActiveRecordingBanner(@Nullable PlaybackTimelineBuilder.ActiveRecordingBanner banner) {
        this.activeRecordingBanner = banner;
    }

    public void buildFlattenedList() {
        flattenedItems.clear();
        flattenedItems.addAll(PlaybackTimelineBuilder.flattenVideoSections(dateSections, activeRecordingBanner));
    }

    public boolean isFullSpanPosition(int position) {
        if (position < 0 || position >= flattenedItems.size()) {
            return true;
        }
        return getItemViewType(position) != VIEW_TYPE_VIDEO_GROUP;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.itemClickListener = listener;
    }

    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
        this.itemSelectedListener = listener;
    }

    public void setOnDateHeaderClickListener(OnDateHeaderClickListener listener) {
        this.dateHeaderClickListener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.itemLongClickListener = listener;
    }

    public void setMultiSelectMode(boolean multiSelectMode) {
        this.isMultiSelectMode = multiSelectMode;
        if (!multiSelectMode) {
            selectedGroups.clear();
        }
    }

    public boolean isMultiSelectMode() {
        return isMultiSelectMode;
    }

    public Set<VideoGroup> getSelectedGroups() {
        return selectedGroups;
    }

    public void setSelectedGroups(Set<VideoGroup> groups) {
        this.selectedGroups = groups;
    }

    public int getSelectedCount() {
        return selectedGroups.size();
    }

    public void clearSelection() {
        selectedGroups.clear();
    }

    public void selectAll() {
        selectedGroups.clear();
        for (DateSection<VideoGroup> section : dateSections) {
            selectedGroups.addAll(section.getItems());
        }
    }

    public int getTotalGroupCount() {
        int count = 0;
        for (DateSection<VideoGroup> section : dateSections) {
            count += section.getItemCount();
        }
        return count;
    }

    public void syncSelectionByGroup(@Nullable VideoGroup activeGroup) {
        selectedPosition = activeGroup == null ? -1 : flattenedItems.indexOf(activeGroup);
    }

    public void release() {
        durationCache.clear();
        if (durationExecutor != null) {
            durationExecutor.shutdownNow();
            durationExecutor = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public int getItemViewType(int position) {
        Object item = flattenedItems.get(position);
        if (item instanceof DateSection) {
            return VIEW_TYPE_DATE_HEADER;
        } else if (item instanceof PlaybackTimelineBuilder.HourHeader) {
            return VIEW_TYPE_HOUR_HEADER;
        } else if (item instanceof PlaybackTimelineBuilder.ActiveRecordingBanner) {
            return VIEW_TYPE_ACTIVE_RECORDING;
        } else {
            return VIEW_TYPE_VIDEO_GROUP;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        switch (viewType) {
            case VIEW_TYPE_DATE_HEADER:
                return new PlaybackListViewHolders.DateHeaderViewHolder(
                        inflater.inflate(R.layout.item_date_header, parent, false));
            case VIEW_TYPE_HOUR_HEADER:
                return new PlaybackListViewHolders.HourHeaderViewHolder(
                        inflater.inflate(R.layout.item_hour_header, parent, false));
            case VIEW_TYPE_ACTIVE_RECORDING:
                return new PlaybackListViewHolders.ActiveRecordingViewHolder(
                        inflater.inflate(R.layout.item_active_recording, parent, false));
            default:
                return new VideoGroupViewHolder(
                        inflater.inflate(R.layout.item_video_group, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = flattenedItems.get(position);
        if (holder instanceof PlaybackListViewHolders.DateHeaderViewHolder) {
            @SuppressWarnings("unchecked")
            DateSection<VideoGroup> section = (DateSection<VideoGroup>) item;
            PlaybackListViewHolders.bindDateHeader(
                    (PlaybackListViewHolders.DateHeaderViewHolder) holder,
                    context,
                    section,
                    "组",
                    v -> {
                        section.toggleExpanded();
                        buildFlattenedList();
                        notifyDataSetChanged();
                        if (dateHeaderClickListener != null) {
                            dateHeaderClickListener.onDateHeaderClick(section, holder.getBindingAdapterPosition());
                        }
                    });
        } else if (holder instanceof PlaybackListViewHolders.HourHeaderViewHolder) {
            PlaybackListViewHolders.bindHourHeader(
                    (PlaybackListViewHolders.HourHeaderViewHolder) holder,
                    context,
                    (PlaybackTimelineBuilder.HourHeader) item);
        } else if (holder instanceof PlaybackListViewHolders.ActiveRecordingViewHolder) {
            PlaybackListViewHolders.bindActiveBanner(
                    (PlaybackListViewHolders.ActiveRecordingViewHolder) holder,
                    context,
                    (PlaybackTimelineBuilder.ActiveRecordingBanner) item,
                    v -> Toast.makeText(context, "当前分段仍在录制，停止或切段后可播放与分享", Toast.LENGTH_SHORT).show());
        } else if (holder instanceof VideoGroupViewHolder) {
            bindVideoGroup((VideoGroupViewHolder) holder, (VideoGroup) item, position);
        }
    }

    private void bindVideoGroup(VideoGroupViewHolder holder, VideoGroup group, int position) {
        holder.videoDate.setVisibility(View.GONE);
        holder.videoTime.setText(group.getFormattedSecondTime());
        holder.videoTime.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        holder.videoSize.setText(group.getFormattedSize());
        holder.videoSize.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));

        int count = group.getVideoCount();
        holder.videoCountBadge.setText(group.hasVideo(VideoGroup.POSITION_COMBINED) ? "合成" : count + "路");

        if (holder.lockBadge != null) {
            holder.lockBadge.setVisibility(group.isLocked() ? View.VISIBLE : View.GONE);
        }
        if (holder.activeBadge != null) {
            holder.activeBadge.setVisibility(group.isRecordingActive() ? View.VISIBLE : View.GONE);
        }

        File thumbFile = group.hasVideo(VideoGroup.POSITION_COMBINED)
                ? group.getVideoFile(VideoGroup.POSITION_COMBINED) : group.getFrontVideo();
        PlaybackListViewHolders.loadThumbnail(context, thumbFile, holder.thumbFront, true);
        PlaybackListViewHolders.loadThumbnail(context, group.getBackVideo(), holder.thumbBack, true);
        PlaybackListViewHolders.loadThumbnail(context, group.getLeftVideo(), holder.thumbLeft, true);
        PlaybackListViewHolders.loadThumbnail(context, group.getRightVideo(), holder.thumbRight, true);

        bindDurationBadgeAsync(holder, group.getThumbnailFile());

        boolean isSelected = isMultiSelectMode
                ? selectedGroups.contains(group)
                : (position == selectedPosition);
        updateSelectionStyle(holder, isSelected);

        if (isMultiSelectMode) {
            holder.checkIndicator.setVisibility(View.VISIBLE);
            holder.checkIndicator.setChecked(selectedGroups.contains(group));
        } else {
            holder.checkIndicator.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            int adapterPos = holder.getBindingAdapterPosition();
            if (adapterPos == RecyclerView.NO_POSITION) return;
            if (isMultiSelectMode) {
                if (selectedGroups.contains(group)) {
                    selectedGroups.remove(group);
                } else {
                    selectedGroups.add(group);
                }
                notifyItemChanged(adapterPos);
                if (itemSelectedListener != null) {
                    itemSelectedListener.onItemSelected(group);
                }
            } else {
                int oldPosition = selectedPosition;
                selectedPosition = adapterPos;
                if (oldPosition != -1 && oldPosition != adapterPos) {
                    notifyItemChanged(oldPosition);
                }
                notifyItemChanged(adapterPos);
                if (itemClickListener != null) {
                    itemClickListener.onItemClick(group, adapterPos);
                }
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            int adapterPos = holder.getBindingAdapterPosition();
            if (itemLongClickListener != null && adapterPos != RecyclerView.NO_POSITION) {
                itemLongClickListener.onItemLongClick(group, adapterPos);
                return true;
            }
            return false;
        });
    }

    private void bindDurationBadgeAsync(VideoGroupViewHolder holder, @Nullable File sampleFile) {
        if (holder.durationBadge == null) {
            return;
        }
        if (sampleFile == null || !sampleFile.isFile() || sampleFile.length() <= 0L) {
            holder.boundDurationKey = null;
            holder.durationBadge.setVisibility(View.GONE);
            return;
        }
        String cacheKey = sampleFile.getAbsolutePath() + ":" + sampleFile.lastModified();
        holder.boundDurationKey = cacheKey;
        String cached = durationCache.get(cacheKey);
        if (cached != null) {
            holder.durationBadge.setText(cached);
            holder.durationBadge.setVisibility(cached.isEmpty() ? View.GONE : View.VISIBLE);
            return;
        }
        holder.durationBadge.setVisibility(View.GONE);
        ensureDurationExecutor().execute(() -> {
            String formatted = probeDurationFormatted(sampleFile);
            durationCache.put(cacheKey, formatted);
            mainHandler.post(() -> {
                if (cacheKey.equals(holder.boundDurationKey) && holder.durationBadge != null) {
                    holder.durationBadge.setText(formatted);
                    holder.durationBadge.setVisibility(formatted.isEmpty() ? View.GONE : View.VISIBLE);
                }
            });
        });
    }

    private ExecutorService ensureDurationExecutor() {
        if (durationExecutor == null || durationExecutor.isShutdown()) {
            durationExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "playback-duration-probe");
                t.setDaemon(true);
                return t;
            });
        }
        return durationExecutor;
    }

    private static String probeDurationFormatted(File videoFile) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFile.getAbsolutePath());
            String val = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (val == null) return "";
            long ms = Long.parseLong(val);
            if (ms <= 0L) return "";
            long sec = ms / 1000L;
            return String.format(Locale.US, "%02d:%02d", sec / 60L, sec % 60L);
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void updateSelectionStyle(VideoGroupViewHolder holder, boolean isSelected) {
        if (holder.itemView instanceof androidx.cardview.widget.CardView) {
            int bgColor = ContextCompat.getColor(
                    context, isSelected ? R.color.item_selected_background : R.color.card_background);
            ((androidx.cardview.widget.CardView) holder.itemView).setCardBackgroundColor(bgColor);
        } else {
            int bgColor = ContextCompat.getColor(
                    context, isSelected ? R.color.item_selected_background : android.R.color.transparent);
            holder.itemView.setBackgroundColor(bgColor);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof VideoGroupViewHolder) {
            ((VideoGroupViewHolder) holder).boundDurationKey = null;
        }
        PlaybackImageMemory.clearViews(holder.itemView);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return flattenedItems.size();
    }

    static class VideoGroupViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumbFront, thumbBack, thumbLeft, thumbRight;
        final TextView videoDate, videoTime, videoSize, videoCountBadge;
        @Nullable final TextView durationBadge, lockBadge, activeBadge;
        final android.widget.CheckBox checkIndicator;
        @Nullable String boundDurationKey;

        VideoGroupViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbFront = itemView.findViewById(R.id.thumb_front);
            thumbBack = itemView.findViewById(R.id.thumb_back);
            thumbLeft = itemView.findViewById(R.id.thumb_left);
            thumbRight = itemView.findViewById(R.id.thumb_right);
            videoDate = itemView.findViewById(R.id.video_date);
            videoTime = itemView.findViewById(R.id.video_time);
            videoSize = itemView.findViewById(R.id.video_size);
            videoCountBadge = itemView.findViewById(R.id.video_count_badge);
            durationBadge = itemView.findViewById(R.id.video_duration_badge);
            lockBadge = itemView.findViewById(R.id.video_lock_badge);
            activeBadge = itemView.findViewById(R.id.video_active_badge);
            checkIndicator = itemView.findViewById(R.id.check_indicator);
        }
    }
}
