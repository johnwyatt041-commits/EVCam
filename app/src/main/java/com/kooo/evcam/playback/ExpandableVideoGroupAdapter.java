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
 * Loads only ONE actually-recorded representative thumbnail per group with concurrency throttling
 * and negative caching, and lazily probes segment durations on a single background worker.
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
    private final PlaybackThumbnailGuard thumbnailGuard;
    private final Runnable cooldownDrainRunnable;
    private ExecutorService durationExecutor;
    @Nullable
    private RecyclerView attachedRecyclerView;
    @Nullable
    private RecyclerView.OnScrollListener scrollPauseListener;

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
        this.cooldownDrainRunnable = this::runCooldownDrain;
        this.thumbnailGuard = PlaybackThumbnailGuard.createShared(this::scheduleCooldownDrain);
        buildFlattenedList();
    }

    private void runCooldownDrain() {
        if (attachedRecyclerView != null || !flattenedItems.isEmpty()) {
            thumbnailGuard.drainPending(System.currentTimeMillis());
        }
    }

    private void scheduleCooldownDrain(long delayMs) {
        mainHandler.removeCallbacks(cooldownDrainRunnable);
        mainHandler.postDelayed(cooldownDrainRunnable, Math.max(1L, delayMs));
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
        if (scrollPauseListener == null) {
            scrollPauseListener = new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                    thumbnailGuard.setScrollPaused(
                            newState == RecyclerView.SCROLL_STATE_SETTLING,
                            System.currentTimeMillis());
                }
            };
        }
        recyclerView.addOnScrollListener(scrollPauseListener);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        if (scrollPauseListener != null) {
            recyclerView.removeOnScrollListener(scrollPauseListener);
        }
        if (attachedRecyclerView == recyclerView) {
            attachedRecyclerView = null;
        }
        thumbnailGuard.clearPendingAndInFlight();
        super.onDetachedFromRecyclerView(recyclerView);
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
        if (activeGroup == null) {
            selectedPosition = -1;
            return;
        }
        int exactIdx = flattenedItems.indexOf(activeGroup);
        if (exactIdx >= 0) {
            selectedPosition = exactIdx;
            return;
        }
        String targetPrefix = activeGroup.getTimestampPrefix();
        for (int i = 0; i < flattenedItems.size(); i++) {
            Object item = flattenedItems.get(i);
            if (item instanceof VideoGroup && targetPrefix.equals(((VideoGroup) item).getTimestampPrefix())) {
                selectedPosition = i;
                return;
            }
        }
        selectedPosition = -1;
    }

    public void release() {
        thumbnailGuard.clearPendingAndInFlight();
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
        long nowMs = System.currentTimeMillis();
        cancelActiveThumbnailLoad(holder, nowMs);
        holder.boundGroup = group;

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

        if (holder.thumbGrid2x2 != null) {
            holder.thumbGrid2x2.setVisibility(View.GONE);
        }
        PlaybackImageMemory.clearImageView(holder.thumbFront);
        PlaybackImageMemory.clearImageView(holder.thumbBack);
        PlaybackImageMemory.clearImageView(holder.thumbLeft);
        PlaybackImageMemory.clearImageView(holder.thumbRight);

        VideoGroup.RepresentativeMedia representative = thumbnailGuard.selectRepresentativeMedia(group, nowMs);
        updateThumbCameraBadge(holder, PlaybackThumbnailGuard.resolveDisplayCameraLabel(group, representative));

        if (holder.thumbPrimary != null) {
            holder.thumbPrimary.setVisibility(View.VISIBLE);
            PlaybackImageMemory.clearImageView(holder.thumbPrimary);
            holder.thumbPrimary.setBackgroundColor(ContextCompat.getColor(context, R.color.preview_frame_background));
            if (representative != null) {
                submitRepresentativeThumbnail(holder, group, representative, nowMs);
            }
        }

        File durationSample = representative != null ? representative.file : group.getThumbnailFile();
        bindDurationBadgeAsync(holder, durationSample);

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

    private void submitRepresentativeThumbnail(@NonNull VideoGroupViewHolder holder,
                                               @NonNull VideoGroup group,
                                               @NonNull VideoGroup.RepresentativeMedia rep,
                                               long nowMs) {
        if (holder.thumbPrimary == null) {
            return;
        }
        String fileKey = PlaybackThumbnailGuard.buildFileKey(rep.file);
        holder.activeThumbKey = fileKey;
        long token = thumbnailGuard.submit(fileKey, nowMs, assignedToken -> {
            if (holder.thumbPrimary == null
                    || holder.boundGroup != group
                    || !fileKey.equals(holder.activeThumbKey)) {
                thumbnailGuard.cancel(assignedToken, System.currentTimeMillis());
                return;
            }
            holder.activeThumbToken = assignedToken;
            boolean started = PlaybackListViewHolders.loadVideoThumbnailGuarded(
                    context,
                    rep.file,
                    holder.thumbPrimary,
                    () -> thumbnailGuard.onLoadSucceeded(assignedToken, fileKey, System.currentTimeMillis()),
                    () -> {
                        long failNow = System.currentTimeMillis();
                        thumbnailGuard.onLoadFailed(assignedToken, fileKey, failNow);
                        mainHandler.post(() -> {
                            if (holder.boundGroup == group
                                    && holder.activeThumbToken == assignedToken
                                    && holder.thumbPrimary != null) {
                                VideoGroup.RepresentativeMedia fallback =
                                        thumbnailGuard.selectRepresentativeMedia(group, System.currentTimeMillis());
                                updateThumbCameraBadge(
                                        holder, PlaybackThumbnailGuard.resolveDisplayCameraLabel(group, fallback));
                                if (fallback != null) {
                                    submitRepresentativeThumbnail(holder, group, fallback, System.currentTimeMillis());
                                } else {
                                    holder.activeThumbToken = 0L;
                                    holder.activeThumbKey = null;
                                    PlaybackImageMemory.clearImageView(holder.thumbPrimary);
                                    holder.thumbPrimary.setBackgroundColor(
                                            ContextCompat.getColor(context, R.color.preview_frame_background));
                                }
                            }
                        });
                    });
            if (!started) {
                if (holder.activeThumbToken == assignedToken) {
                    holder.activeThumbToken = 0L;
                    holder.activeThumbKey = null;
                }
                thumbnailGuard.cancel(assignedToken, System.currentTimeMillis());
            }
        });
        if (holder.boundGroup == group && fileKey.equals(holder.activeThumbKey)) {
            holder.activeThumbToken = token;
        }
    }

    private void cancelActiveThumbnailLoad(@NonNull VideoGroupViewHolder holder, long nowMs) {
        if (holder.activeThumbToken != 0L) {
            thumbnailGuard.cancel(holder.activeThumbToken, nowMs);
            holder.activeThumbToken = 0L;
        }
        holder.activeThumbKey = null;
    }

    private void updateThumbCameraBadge(@NonNull VideoGroupViewHolder holder, @NonNull String label) {
        if (holder.thumbCameraBadge == null) {
            return;
        }
        if (label.isEmpty()) {
            holder.thumbCameraBadge.setVisibility(View.GONE);
        } else {
            holder.thumbCameraBadge.setText(label);
            holder.thumbCameraBadge.setVisibility(View.VISIBLE);
        }
    }

    private void bindDurationBadgeAsync(VideoGroupViewHolder holder, @Nullable File sampleFile) {
        if (holder.durationBadge == null) {
            return;
        }
        if (sampleFile == null
                || !sampleFile.isFile()
                || sampleFile.length() < PlaybackThumbnailGuard.MIN_PLAYABLE_THUMB_BYTES
                || thumbnailGuard.isNegativeCached(
                        PlaybackThumbnailGuard.buildFileKey(sampleFile), System.currentTimeMillis())) {
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
            VideoGroupViewHolder videoHolder = (VideoGroupViewHolder) holder;
            cancelActiveThumbnailLoad(videoHolder, System.currentTimeMillis());
            videoHolder.boundGroup = null;
            videoHolder.boundDurationKey = null;
        }
        PlaybackImageMemory.clearViews(holder.itemView);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return flattenedItems.size();
    }

    static class VideoGroupViewHolder extends RecyclerView.ViewHolder {
        @Nullable final ImageView thumbPrimary;
        @Nullable final View thumbGrid2x2;
        final ImageView thumbFront, thumbBack, thumbLeft, thumbRight;
        final TextView videoDate, videoTime, videoSize, videoCountBadge;
        @Nullable final TextView thumbCameraBadge, durationBadge, lockBadge, activeBadge;
        final android.widget.CheckBox checkIndicator;
        @Nullable String boundDurationKey;
        @Nullable VideoGroup boundGroup;
        @Nullable String activeThumbKey;
        long activeThumbToken = 0L;

        VideoGroupViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbPrimary = itemView.findViewById(R.id.thumb_primary);
            thumbGrid2x2 = itemView.findViewById(R.id.thumb_grid_2x2);
            thumbFront = itemView.findViewById(R.id.thumb_front);
            thumbBack = itemView.findViewById(R.id.thumb_back);
            thumbLeft = itemView.findViewById(R.id.thumb_left);
            thumbRight = itemView.findViewById(R.id.thumb_right);
            videoDate = itemView.findViewById(R.id.video_date);
            videoTime = itemView.findViewById(R.id.video_time);
            videoSize = itemView.findViewById(R.id.video_size);
            videoCountBadge = itemView.findViewById(R.id.video_count_badge);
            thumbCameraBadge = itemView.findViewById(R.id.video_thumb_camera_badge);
            durationBadge = itemView.findViewById(R.id.video_duration_badge);
            lockBadge = itemView.findViewById(R.id.video_lock_badge);
            activeBadge = itemView.findViewById(R.id.video_active_badge);
            checkIndicator = itemView.findViewById(R.id.check_indicator);
        }
    }
}
