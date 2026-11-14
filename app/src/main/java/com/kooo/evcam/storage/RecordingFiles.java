package com.kooo.evcam.storage;

import java.io.File;
import java.util.Locale;

/** One naming policy for recording, playback, uploads and automatic cleanup. */
public final class RecordingFiles {
    private RecordingFiles() {}

    public static File pendingFileFor(File target) {
        // Android 11 DCIM policy rejects an unknown final extension such as .mp4.pending.
        // Keep video/mp4 classification, hide the unfinished file, and rename in place on success.
        return new File(target.getParentFile(), "." + target.getName() + ".pending.mp4");
    }

    public static boolean isPendingVideoName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4.pending")
                || (lower.startsWith(".") && lower.endsWith(".pending.mp4"));
    }

    public static boolean isCompletedVideoName(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".mp4") && !isPendingVideoName(name);
    }

    public static boolean canAutomaticallyDelete(File file) {
        return file.isFile() && !isPendingVideoName(file.getName());
    }
}
