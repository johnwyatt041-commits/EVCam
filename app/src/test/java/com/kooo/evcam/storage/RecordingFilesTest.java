package com.kooo.evcam.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

public class RecordingFilesTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test public void pendingRetainsMediaSuffixAndStaysInSameDirectory() {
        File target = new File(directory.getRoot(), "20261115_020000_left.mp4");
        File pending = RecordingFiles.pendingFileFor(target);
        assertEquals(target.getParentFile(), pending.getParentFile());
        assertEquals(".20261115_020000_left.mp4.pending.mp4", pending.getName());
        assertTrue(pending.getName().startsWith("."));
        assertTrue(pending.getName().endsWith(".mp4"));
        assertFalse(RecordingFiles.isCompletedVideoName(pending.getName()));
        assertNull(com.kooo.evcam.playback.VideoGroup.extractPosition(pending.getName()));
    }

    @Test public void automaticCleanupProtectsBothPendingProtocols() throws Exception {
        File legacy = directory.newFile("20261115_020000_left.mp4.pending");
        File current = directory.newFile(".20261115_020000_right.mp4.pending.mp4");
        assertFalse(RecordingFiles.canAutomaticallyDelete(legacy));
        assertFalse(RecordingFiles.canAutomaticallyDelete(current));
        assertFalse(RecordingFiles.isCompletedVideoName(legacy.getName()));
    }

    @Test public void finalizedVideoBecomesVisibleAndCleanupEligible() throws Exception {
        File target = new File(directory.getRoot(), "20261115_020000_combined.mp4");
        File pending = RecordingFiles.pendingFileFor(target);
        assertTrue(pending.createNewFile());
        assertTrue(pending.renameTo(target));
        assertTrue(RecordingFiles.isCompletedVideoName(target.getName()));
        assertTrue(RecordingFiles.canAutomaticallyDelete(target));
        assertEquals("combined", com.kooo.evcam.playback.VideoGroup.extractPosition(target.getName()));
    }

    @Test public void namingClassificationDoesNotMatchArbitraryNonVideoFiles() {
        assertFalse(RecordingFiles.isPendingVideoName("photo.jpg.pending"));
        assertFalse(RecordingFiles.isCompletedVideoName("video.mp4.pending"));
        assertTrue(RecordingFiles.isCompletedVideoName("20261115_020000_RIGHT.MP4"));
    }
}
