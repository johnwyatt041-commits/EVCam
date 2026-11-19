package com.kooo.evcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class RecordingStorageAccessTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void probeWritableDirectoryCreatesAndCleansUpProbeFile() throws IOException {
        File dir = temporaryFolder.newFolder("dcim", "EVCam_Video");
        RecordingStorageAccess.ProbeResult result = RecordingStorageAccess.probeDirectoryWritable(dir, false);
        assertTrue(result.writable);
        assertFalse(result.permissionDenied);
        assertFalse(result.needsAllFilesPermission());
        String[] remaining = dir.list();
        assertEquals(0, remaining == null ? 0 : remaining.length);
    }

    @Test
    public void probeMissingAllFilesOnUncreatablePathFlagsAllFilesPermission() throws IOException {
        File blockerFile = temporaryFolder.newFile("not_a_dir");
        File nestedDir = new File(blockerFile, "EVCam_Video");

        RecordingStorageAccess.ProbeResult result = RecordingStorageAccess.probeDirectoryWritable(nestedDir, true);
        assertFalse(result.writable);
        assertTrue(result.permissionDenied);
        assertTrue(result.needsAllFilesPermission());
    }

    @Test
    public void probeNonDirectoryTargetFailsWithoutMisclassifyingReadOnly() throws IOException {
        File regularFile = temporaryFolder.newFile("existing_file");
        RecordingStorageAccess.ProbeResult result = RecordingStorageAccess.probeDirectoryWritable(regularFile, false);
        assertFalse(result.writable);
        assertFalse(result.readOnlyFileSystem);
        assertFalse(result.outOfSpace);
        assertTrue(result.errorDetail.contains("not a directory"));
    }

    @Test
    public void fatalWriteErrorClassificationMatchesEpermEaccesErofsEnospcAndIgnoresCollision() {
        FileNotFoundException eperm = new FileNotFoundException(
                "/storage/emulated/0/DCIM/EVCam_Video/20261119_135613_front.mp4.pending: open failed: EPERM (Operation not permitted)");
        IllegalStateException wrappedEperm = new IllegalStateException("Video remains pending", eperm);
        IOException erofs = new IOException("open failed: EROFS (Read-only file system)");
        IOException enospc = new IOException("write failed: ENOSPC (No space left on device)");
        IOException collision = new IOException("Refusing to overwrite video: 20261119_135613_front.mp4");

        assertTrue(RecordingStorageAccess.isFatalWriteError(wrappedEperm));
        assertTrue(RecordingStorageAccess.isPermissionDeniedError(wrappedEperm));
        assertFalse(RecordingStorageAccess.isReadOnlyError(wrappedEperm));
        assertTrue(RecordingStorageAccess.isFatalWriteError(new SecurityException("denied")));

        assertTrue(RecordingStorageAccess.isFatalWriteError(erofs));
        assertTrue(RecordingStorageAccess.isReadOnlyError(erofs));

        assertTrue(RecordingStorageAccess.isFatalWriteError(enospc));
        assertTrue(RecordingStorageAccess.isOutOfSpaceError(enospc));

        assertFalse(RecordingStorageAccess.isFatalWriteError(collision));
        assertFalse(RecordingStorageAccess.isFatalWriteErrorMessage("Encoder timeout waiting for EOS"));
    }

    @Test
    public void formatUserStartupFailureMessageProducesActionableText() {
        String epermMsg = RecordingStorageAccess.formatUserStartupFailureMessage(
                "open failed: EPERM (Operation not permitted)", true);
        assertTrue(epermMsg.contains("所有文件访问权限"));

        String erofsMsg = RecordingStorageAccess.formatUserStartupFailureMessage(
                "open failed: EROFS (Read-only file system)", false);
        assertTrue(erofsMsg.contains("只读"));

        String enospcMsg = RecordingStorageAccess.formatUserStartupFailureMessage(
                "write failed: ENOSPC (No space left on device)", false);
        assertTrue(enospcMsg.contains("空间不足"));
    }
}
