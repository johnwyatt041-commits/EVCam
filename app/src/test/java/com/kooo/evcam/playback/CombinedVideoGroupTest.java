package com.kooo.evcam.playback;
import org.junit.Test;
import java.io.File;
import static org.junit.Assert.*;
public class CombinedVideoGroupTest {
    @Test public void combinedVideoHasItsOwnPositionAndThumbnail() {
        File combined=new File("20261114_032000_combined.mp4");
        VideoGroup group=new VideoGroup("20261114_032000"); group.addFile(combined);
        assertTrue(group.hasVideo(VideoGroup.POSITION_COMBINED));
        assertSame(combined,group.getThumbnailFile());
        assertNull(group.getFrontVideo());
    }
    @Test public void pendingWriterOutputIsNotAPlayableCameraFile() {
        assertNull(VideoGroup.extractPosition("20261114_032000_left.mp4.pending"));
    }
}
