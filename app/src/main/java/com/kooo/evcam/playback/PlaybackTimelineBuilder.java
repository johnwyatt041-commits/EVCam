package com.kooo.evcam.playback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Java scanner and timeline flattener for video/photo playback lists.
 * Organizes groups into collapsible {@link DateSection}s with hourly headers
 * ({@code HH:00 · N 段}), {@code .lock} filtering, and active {@code .pending} segment badges.
 */
public final class PlaybackTimelineBuilder {

    public static final int FILTER_ALL = 0;
    public static final int FILTER_TODAY = 1;
    public static final int FILTER_LOCKED = 2;

    public static final class HourHeader {
        public final String hourLabel;
        public final int count;
        public final String unit;

        public HourHeader(@NonNull String hourLabel, int count, @NonNull String unit) {
            this.hourLabel = hourLabel;
            this.count = count;
            this.unit = unit;
        }

        @NonNull
        public String getDisplayText() {
            return hourLabel + "  ·  " + count + " " + unit;
        }
    }

    public static final class ActiveRecordingBanner {
        public final String timestampPrefix;
        public final String formattedTime;
        public final int activeFileCount;

        public ActiveRecordingBanner(@NonNull String timestampPrefix,
                                     @NonNull String formattedTime,
                                     int activeFileCount) {
            this.timestampPrefix = timestampPrefix;
            this.formattedTime = formattedTime;
            this.activeFileCount = activeFileCount;
        }
    }

    public static final class VideoScanResult {
        @Nullable public final ActiveRecordingBanner activeRecording;
        public final List<DateSection<VideoGroup>> dateSections;

        public VideoScanResult(@Nullable ActiveRecordingBanner activeRecording,
                               @NonNull List<DateSection<VideoGroup>> dateSections) {
            this.activeRecording = activeRecording;
            this.dateSections = dateSections;
        }
    }

    private PlaybackTimelineBuilder() {}

    @NonNull
    public static VideoScanResult scanVideoDirectory(@Nullable File videoDir,
                                                     int filterMode,
                                                     long todayReferenceMs) {
        if (videoDir == null || !videoDir.exists() || !videoDir.isDirectory()) {
            return new VideoScanResult(null, Collections.emptyList());
        }
        File[] rawFiles = videoDir.listFiles();
        if (rawFiles == null || rawFiles.length == 0) {
            return new VideoScanResult(null, Collections.emptyList());
        }

        Map<String, VideoGroup> groupMap = new HashMap<>();
        Map<String, Integer> pendingPrefixCounts = new HashMap<>();

        for (File file : rawFiles) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(VideoLockStore.PENDING_SUFFIX)) {
                String completedName = name.substring(0, name.length() - VideoLockStore.PENDING_SUFFIX.length());
                if (completedName.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                    String prefix = VideoGroup.extractTimestampPrefix(completedName);
                    Integer prev = pendingPrefixCounts.get(prefix);
                    pendingPrefixCounts.put(prefix, (prev == null ? 0 : prev) + 1);
                }
                continue;
            }
            if (!lower.endsWith(".mp4") || file.length() <= 0L) {
                continue;
            }
            if (VideoGroup.extractPosition(name) == null) {
                continue;
            }
            String prefix = VideoGroup.extractTimestampPrefix(name);
            VideoGroup group = groupMap.get(prefix);
            if (group == null) {
                group = new VideoGroup(prefix);
                groupMap.put(prefix, group);
            }
            group.addFile(file);
        }

        ActiveRecordingBanner activeBanner = null;
        if (!pendingPrefixCounts.isEmpty()) {
            List<String> prefixes = new ArrayList<>(pendingPrefixCounts.keySet());
            Collections.sort(prefixes, Collections.reverseOrder());
            String latestPrefix = prefixes.get(0);
            VideoGroup existing = groupMap.get(latestPrefix);
            if (existing != null) {
                existing.setRecordingActive(true);
            } else {
                VideoGroup tempForTime = new VideoGroup(latestPrefix);
                int count = pendingPrefixCounts.get(latestPrefix);
                activeBanner = new ActiveRecordingBanner(
                        latestPrefix, tempForTime.getFormattedSecondTime(), count);
            }
        }

        String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(todayReferenceMs));
        List<VideoGroup> filteredGroups = new ArrayList<>();
        for (VideoGroup group : groupMap.values()) {
            if (group.getVideoCount() == 0) {
                continue;
            }
            boolean locked = VideoLockStore.isGroupLocked(group);
            group.setLocked(locked);

            if (filterMode == FILTER_LOCKED && !locked) {
                continue;
            }
            if (filterMode == FILTER_TODAY && !todayKey.equals(group.getFormattedDate())) {
                continue;
            }
            filteredGroups.add(group);
        }

        Collections.sort(filteredGroups, (g1, g2) -> g2.getRecordTime().compareTo(g1.getRecordTime()));

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Map<String, DateSection<VideoGroup>> sectionMap = new LinkedHashMap<>();
        for (VideoGroup group : filteredGroups) {
            String dateStr = dateFormat.format(group.getRecordTime());
            DateSection<VideoGroup> section = sectionMap.get(dateStr);
            if (section == null) {
                section = new DateSection<>(dateStr, group.getRecordTime());
                sectionMap.put(dateStr, section);
            }
            section.addItem(group);
        }

        List<DateSection<VideoGroup>> sections = new ArrayList<>(sectionMap.values());
        if (filterMode == FILTER_LOCKED && !sections.isEmpty()) {
            for (DateSection<VideoGroup> section : sections) {
                section.setExpanded(true);
            }
        }
        return new VideoScanResult(activeBanner, sections);
    }

    @NonNull
    public static List<DateSection<PhotoGroup>> scanPhotoDirectory(@Nullable File photoDir,
                                                                   int filterMode,
                                                                   long todayReferenceMs) {
        if (photoDir == null || !photoDir.exists() || !photoDir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = photoDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
        });
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        Map<String, PhotoGroup> groupMap = new HashMap<>();
        for (File file : files) {
            if (file == null || !file.isFile() || file.length() <= 0L) {
                continue;
            }
            String prefix = PhotoGroup.extractTimestampPrefix(file.getName());
            PhotoGroup group = groupMap.get(prefix);
            if (group == null) {
                group = new PhotoGroup(prefix);
                groupMap.put(prefix, group);
            }
            group.addFile(file);
        }

        String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(todayReferenceMs));
        List<PhotoGroup> allGroups = new ArrayList<>();
        for (PhotoGroup group : groupMap.values()) {
            if (group.getPhotoCount() == 0) {
                continue;
            }
            boolean locked = false;
            for (File f : group.getShareableFiles()) {
                if (VideoLockStore.isLocked(f)) {
                    locked = true;
                    break;
                }
            }
            group.setLocked(locked);
            if (filterMode == FILTER_LOCKED && !locked) {
                continue;
            }
            if (filterMode == FILTER_TODAY && !todayKey.equals(group.getFormattedDate())) {
                continue;
            }
            allGroups.add(group);
        }

        Collections.sort(allGroups, (g1, g2) -> g2.getCaptureTime().compareTo(g1.getCaptureTime()));

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Map<String, DateSection<PhotoGroup>> sectionMap = new LinkedHashMap<>();
        for (PhotoGroup group : allGroups) {
            String dateStr = dateFormat.format(group.getCaptureTime());
            DateSection<PhotoGroup> section = sectionMap.get(dateStr);
            if (section == null) {
                section = new DateSection<>(dateStr, group.getCaptureTime());
                sectionMap.put(dateStr, section);
            }
            section.addItem(group);
        }

        List<DateSection<PhotoGroup>> sections = new ArrayList<>(sectionMap.values());
        if (filterMode == FILTER_LOCKED) {
            for (DateSection<PhotoGroup> section : sections) {
                section.setExpanded(true);
            }
        }
        return sections;
    }

    @NonNull
    public static List<Object> flattenVideoSections(@NonNull List<DateSection<VideoGroup>> sections,
                                                    @Nullable ActiveRecordingBanner activeBanner) {
        List<Object> out = new ArrayList<>();
        if (activeBanner != null) {
            out.add(activeBanner);
        }
        for (DateSection<VideoGroup> section : sections) {
            out.add(section);
            if (!section.isExpanded()) {
                continue;
            }
            appendGroupedByHour(section.getItems(), out, "段", group -> formatHourLabel(group.getRecordTime()));
        }
        return out;
    }

    @NonNull
    public static List<Object> flattenPhotoSections(@NonNull List<DateSection<PhotoGroup>> sections) {
        List<Object> out = new ArrayList<>();
        for (DateSection<PhotoGroup> section : sections) {
            out.add(section);
            if (!section.isExpanded()) {
                continue;
            }
            appendGroupedByHour(section.getItems(), out, "组", group -> formatHourLabel(group.getCaptureTime()));
        }
        return out;
    }

    private interface HourExtractor<T> {
        String hourLabel(T item);
    }

    private static <T> void appendGroupedByHour(List<T> items,
                                                List<Object> out,
                                                String unit,
                                                HourExtractor<T> extractor) {
        Map<String, List<T>> buckets = new LinkedHashMap<>();
        for (T item : items) {
            String hour = extractor.hourLabel(item);
            List<T> bucket = buckets.get(hour);
            if (bucket == null) {
                bucket = new ArrayList<>();
                buckets.put(hour, bucket);
            }
            bucket.add(item);
        }
        for (Map.Entry<String, List<T>> entry : buckets.entrySet()) {
            out.add(new HourHeader(entry.getKey(), entry.getValue().size(), unit));
            out.addAll(entry.getValue());
        }
    }

    @NonNull
    public static String formatHourLabel(@Nullable Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date != null ? date : new Date(0));
        return String.format(Locale.US, "%02d:00", cal.get(Calendar.HOUR_OF_DAY));
    }
}
