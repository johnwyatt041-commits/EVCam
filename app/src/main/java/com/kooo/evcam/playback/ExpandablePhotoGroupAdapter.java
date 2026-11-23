package com.kooo.evcam.playback;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.kooo.evcam.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Expandable date + hourly timeline adapter for multi-camera photo groups.
 */
public class ExpandablePhotoGroupAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_DATE_HEADER = 0;
    public static final int VIEW_TYPE_PHOTO_GROUP = 1;
    public static final int VIEW_TYPE_HOUR_HEADER = 2;

    private final Context context;
    private final List<DateSection<PhotoGroup>> dateSections;
    private final List<Object> flattenedItems = new ArrayList<>();

    private Set<PhotoGroup> selectedGroups = new HashSet<>();
    private int selectedPosition = -1;
    private boolean isMultiSelectMode = false;

    private OnItemClickListener itemClickListener;
    private OnItemSelectedListener itemSelectedListener;
    private OnDateHeaderClickListener dateHeaderClickListener;
    private OnItemLongClickListener itemLongClickListener;

    public interface OnItemClickListener {
        void onItemClick(PhotoGroup group, int position);
    }

    public interface OnItemSelectedListener {
        void onItemSelected(PhotoGroup group);
    }

    public interface OnDateHeaderClickListener {
        void onDateHeaderClick(DateSection<PhotoGroup> section, int position);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(PhotoGroup group, int position);
    }

    public ExpandablePhotoGroupAdapter(Context context, List<DateSection<PhotoGroup>> dateSections) {
        this.context = context;
        this.dateSections = dateSections;
        buildFlattenedList();
    }

    public void buildFlattenedList() {
        flattenedItems.clear();
        flattenedItems.addAll(PlaybackTimelineBuilder.flattenPhotoSections(dateSections));
    }

    public boolean isFullSpanPosition(int position) {
        if (position < 0 || position >= flattenedItems.size()) {
            return true;
        }
        return getItemViewType(position) != VIEW_TYPE_PHOTO_GROUP;
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

    public Set<PhotoGroup> getSelectedGroups() {
        return selectedGroups;
    }

    public void setSelectedGroups(Set<PhotoGroup> groups) {
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
        for (DateSection<PhotoGroup> section : dateSections) {
            selectedGroups.addAll(section.getItems());
        }
    }

    public int getTotalGroupCount() {
        int count = 0;
        for (DateSection<PhotoGroup> section : dateSections) {
            count += section.getItemCount();
        }
        return count;
    }

    public void syncSelectionByGroup(@Nullable PhotoGroup activeGroup) {
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
            if (item instanceof PhotoGroup && targetPrefix.equals(((PhotoGroup) item).getTimestampPrefix())) {
                selectedPosition = i;
                return;
            }
        }
        selectedPosition = -1;
    }

    @Override
    public int getItemViewType(int position) {
        Object item = flattenedItems.get(position);
        if (item instanceof DateSection) {
            return VIEW_TYPE_DATE_HEADER;
        } else if (item instanceof PlaybackTimelineBuilder.HourHeader) {
            return VIEW_TYPE_HOUR_HEADER;
        } else {
            return VIEW_TYPE_PHOTO_GROUP;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        if (viewType == VIEW_TYPE_DATE_HEADER) {
            return new PlaybackListViewHolders.DateHeaderViewHolder(
                    inflater.inflate(R.layout.item_date_header, parent, false));
        } else if (viewType == VIEW_TYPE_HOUR_HEADER) {
            return new PlaybackListViewHolders.HourHeaderViewHolder(
                    inflater.inflate(R.layout.item_hour_header, parent, false));
        } else {
            return new PhotoGroupViewHolder(
                    inflater.inflate(R.layout.item_video_group, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = flattenedItems.get(position);
        if (holder instanceof PlaybackListViewHolders.DateHeaderViewHolder) {
            @SuppressWarnings("unchecked")
            DateSection<PhotoGroup> section = (DateSection<PhotoGroup>) item;
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
        } else if (holder instanceof PhotoGroupViewHolder) {
            bindPhotoGroup((PhotoGroupViewHolder) holder, (PhotoGroup) item, position);
        }
    }

    private void bindPhotoGroup(PhotoGroupViewHolder holder, PhotoGroup group, int position) {
        holder.videoDate.setVisibility(View.GONE);
        holder.videoTime.setText(group.getFormattedSecondTime());
        holder.videoTime.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        holder.videoSize.setText(group.getFormattedSize());
        holder.videoSize.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        holder.videoCountBadge.setText(group.getPhotoCount() + "张");

        if (holder.lockBadge != null) {
            holder.lockBadge.setVisibility(group.isLocked() ? View.VISIBLE : View.GONE);
        }

        PlaybackListViewHolders.loadThumbnail(context, group.getFrontPhoto(), holder.thumbFront, false);
        PlaybackListViewHolders.loadThumbnail(context, group.getBackPhoto(), holder.thumbBack, false);
        PlaybackListViewHolders.loadThumbnail(context, group.getLeftPhoto(), holder.thumbLeft, false);
        PlaybackListViewHolders.loadThumbnail(context, group.getRightPhoto(), holder.thumbRight, false);

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

    private void updateSelectionStyle(PhotoGroupViewHolder holder, boolean isSelected) {
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
        PlaybackImageMemory.clearViews(holder.itemView);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return flattenedItems.size();
    }

    static class PhotoGroupViewHolder extends RecyclerView.ViewHolder {
        final ImageView thumbFront, thumbBack, thumbLeft, thumbRight;
        final TextView videoDate, videoTime, videoSize, videoCountBadge;
        @Nullable final TextView lockBadge;
        final android.widget.CheckBox checkIndicator;

        PhotoGroupViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbFront = itemView.findViewById(R.id.thumb_front);
            thumbBack = itemView.findViewById(R.id.thumb_back);
            thumbLeft = itemView.findViewById(R.id.thumb_left);
            thumbRight = itemView.findViewById(R.id.thumb_right);
            videoDate = itemView.findViewById(R.id.video_date);
            videoTime = itemView.findViewById(R.id.video_time);
            videoSize = itemView.findViewById(R.id.video_size);
            videoCountBadge = itemView.findViewById(R.id.video_count_badge);
            lockBadge = itemView.findViewById(R.id.video_lock_badge);
            checkIndicator = itemView.findViewById(R.id.check_indicator);
        }
    }
}
