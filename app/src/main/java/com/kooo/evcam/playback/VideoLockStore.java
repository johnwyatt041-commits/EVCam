package com.kooo.evcam.playback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Manages {@code .lock} sidecar files for protected recordings and coordinates with
 * {@link com.kooo.evcam.StorageCleanupManager} so locked footage is preserved during loop cleanup.
 */
public final class VideoLockStore {

    public static final String LOCK_SUFFIX = ".lock";
    public static final String PENDING_SUFFIX = ".pending";

    private VideoLockStore() {}

    @NonNull
    public static File getLockSidecar(@NonNull File mediaFile) {
        return new File(mediaFile.getParentFile(), mediaFile.getName() + LOCK_SUFFIX);
    }

    public static boolean isLockFile(@Nullable File file) {
        return file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(LOCK_SUFFIX);
    }

    public static boolean isPendingFile(@Nullable File file) {
        return file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(PENDING_SUFFIX);
    }

    public static boolean isLocked(@Nullable File mediaFile) {
        if (mediaFile == null || isLockFile(mediaFile) || isPendingFile(mediaFile)) {
            return false;
        }
        return getLockSidecar(mediaFile).isFile();
    }

    public static boolean setLocked(@Nullable File mediaFile, boolean locked) {
        if (mediaFile == null || !mediaFile.exists() || !mediaFile.isFile() || isPendingFile(mediaFile)) {
            return false;
        }
        File lockFile = getLockSidecar(mediaFile);
        if (locked) {
            if (lockFile.exists()) {
                return true;
            }
            try {
                return lockFile.createNewFile();
            } catch (IOException e) {
                return false;
            }
        } else {
            return !lockFile.exists() || lockFile.delete();
        }
    }

    public static boolean isGroupLocked(@Nullable VideoGroup group) {
        if (group == null) {
            return false;
        }
        for (File file : group.getShareableFiles()) {
            if (isLocked(file)) {
                return true;
            }
        }
        return false;
    }

    public static boolean setGroupLocked(@Nullable VideoGroup group, boolean locked) {
        if (group == null) {
            return false;
        }
        List<File> files = group.getShareableFiles();
        if (files.isEmpty()) {
            return false;
        }
        boolean anyChangedOrOk = true;
        for (File file : files) {
            if (!setLocked(file, locked)) {
                anyChangedOrOk = false;
            }
        }
        group.setLocked(locked && anyChangedOrOk);
        return anyChangedOrOk;
    }

    public static void cleanupLockSidecar(@Nullable File mediaFile) {
        if (mediaFile == null) {
            return;
        }
        File lockFile = getLockSidecar(mediaFile);
        if (lockFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            lockFile.delete();
        }
    }

    /**
     * Returns whether {@code lockFile} no longer has a matching media or pending file in the directory.
     */
    public static boolean isOrphanLockFile(@Nullable File lockFile, @NonNull Set<String> directoryFileNames) {
        if (!isLockFile(lockFile)) {
            return false;
        }
        String name = lockFile.getName();
        String targetName = name.substring(0, name.length() - LOCK_SUFFIX.length());
        if (targetName.isEmpty()) {
            return true;
        }
        return !directoryFileNames.contains(targetName)
                && !directoryFileNames.contains(targetName + PENDING_SUFFIX);
    }

    /**
     * Determines whether {@link com.kooo.evcam.StorageCleanupManager} must preserve this file.
     * - Locked media files and their non-orphan {@code .lock} sidecars are preserved.
     * - Active {@code .pending} recordings are preserved.
     * - Orphan {@code .lock} sidecars can be deleted to keep storage tidy.
     */
    public static boolean shouldPreserveDuringCleanup(@Nullable File file,
                                                      @NonNull Set<String> directoryFileNames) {
        if (file == null) {
            return false;
        }
        if (isPendingFile(file)) {
            return true;
        }
        if (isLockFile(file)) {
            return !isOrphanLockFile(file, directoryFileNames);
        }
        return isLocked(file);
    }

    @NonNull
    public static Set<String> collectFileNames(@Nullable File[] files) {
        Set<String> names = new HashSet<>();
        if (files != null) {
            for (File f : files) {
                if (f != null && f.isFile()) {
                    names.add(f.getName());
                }
            }
        }
        return names;
    }
}
