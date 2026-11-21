package com.kooo.evcam.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks canonical paths of {@code .mp4.pending} files currently opened by active
 * {@link AsyncMuxerWriter} instances in this process so storage cleanup, relay cleanup,
 * startup orphan recovery, and playback scanning can distinguish active recordings from
 * crash/power-loss leftovers.
 */
public final class ActiveRecordingRegistry {

    private static final Set<String> ACTIVE_PENDING_PATHS = ConcurrentHashMap.newKeySet();

    private ActiveRecordingRegistry() {
    }

    @NonNull
    public static String normalizePath(@Nullable File file) {
        if (file == null) {
            return "";
        }
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    public static void register(@Nullable File file) {
        String path = normalizePath(file);
        if (!path.isEmpty()) {
            ACTIVE_PENDING_PATHS.add(path);
        }
    }

    public static void unregister(@Nullable File file) {
        String path = normalizePath(file);
        if (!path.isEmpty()) {
            ACTIVE_PENDING_PATHS.remove(path);
        }
    }

    public static boolean isActive(@Nullable File file) {
        String path = normalizePath(file);
        return !path.isEmpty() && ACTIVE_PENDING_PATHS.contains(path);
    }

    @NonNull
    public static Set<String> snapshot() {
        return new HashSet<>(ACTIVE_PENDING_PATHS);
    }

    static void clearForTest() {
        ACTIVE_PENDING_PATHS.clear();
    }
}
