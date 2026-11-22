package com.kooo.evcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class UsbSpeedTesterTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void buildRequirementUsesMinimumFloorsForNormalE5Bitrate() {
        UsbSpeedTester.RecordingRequirement req = UsbSpeedTester.buildRequirement(
                3, 7_500_000L, false, 25, "1280×800", "标准");

        assertEquals(3, req.streamCount);
        assertEquals(4.0, req.minRelayMBps, 0.001);
        assertEquals(10.0, req.recommendedDirectMBps, 0.001);
        assertTrue(req.summary.contains("3路独立"));
        assertTrue(req.summary.contains("1280×800"));
        assertTrue(req.summary.contains("25fps"));
    }

    @Test
    public void buildRequirementScalesThresholdsAboveFloorsForHighMultiStreamBitrate() {
        long bitrate40Mbps = 40_000_000L; // ~4.768 MB/s
        UsbSpeedTester.RecordingRequirement req = UsbSpeedTester.buildRequirement(
                4, bitrate40Mbps, false, 30, "1920×1080", "高");

        double expectedNominal = (bitrate40Mbps / 8.0) / UsbSpeedTester.BYTES_PER_MB;
        assertEquals(expectedNominal, req.nominalMBps, 0.001);
        assertEquals(expectedNominal * 2.0, req.minRelayMBps, 0.001);
        assertEquals(expectedNominal * 3.0, req.recommendedDirectMBps, 0.001);
        assertTrue(req.minRelayMBps > 9.0);
        assertTrue(req.recommendedDirectMBps > 14.0);
    }

    @Test
    public void evaluateWriteResultClassifiesDirectRelayAndInsufficient() {
        UsbSpeedTester.RecordingRequirement req = UsbSpeedTester.buildRequirement(
                3, 7_500_000L, false, 25, "1280×800", "标准");

        UsbSpeedTester.SpeedTestResult direct = UsbSpeedTester.evaluateWriteResult(
                18.5, 14.2, 21.0, 64L * 1024L * 1024L, 3460L, "/storage/1234-ABCD/DCIM", req, true, false);
        assertEquals(UsbSpeedTester.Status.MEETS_DIRECT, direct.status);
        assertTrue(direct.detail.contains("符合录制要求"));

        UsbSpeedTester.SpeedTestResult relayOff = UsbSpeedTester.evaluateWriteResult(
                6.8, 5.1, 8.2, 64L * 1024L * 1024L, 9400L, "/storage/1234-ABCD/DCIM", req, false, false);
        assertEquals(UsbSpeedTester.Status.RELAY_ONLY, relayOff.status);
        assertTrue(relayOff.detail.contains("建议开启上方「中转写入模式」"));

        UsbSpeedTester.SpeedTestResult relayOn = UsbSpeedTester.evaluateWriteResult(
                6.8, 5.1, 8.2, 64L * 1024L * 1024L, 9400L, "/storage/1234-ABCD/DCIM", req, true, false);
        assertEquals(UsbSpeedTester.Status.RELAY_ONLY, relayOn.status);
        assertTrue(relayOn.detail.contains("当前已开启中转写入"));

        UsbSpeedTester.SpeedTestResult fail = UsbSpeedTester.evaluateWriteResult(
                2.2, 1.4, 3.0, 22L * 1024L * 1024L, 10000L, "/storage/1234-ABCD/DCIM", req, true, false);
        assertEquals(UsbSpeedTester.Status.INSUFFICIENT, fail.status);
        assertTrue(fail.detail.contains("持续写入速度过慢"));
    }

    @Test
    public void evaluateWriteResultDowngradesSevereChunkDropToRelayOnly() {
        UsbSpeedTester.RecordingRequirement req = UsbSpeedTester.buildRequirement(
                3, 7_500_000L, false, 25, "1280×800", "标准");

        UsbSpeedTester.SpeedTestResult unstable = UsbSpeedTester.evaluateWriteResult(
                15.0, 1.5, 32.0, 64L * 1024L * 1024L, 4260L, "/storage/1234-ABCD/DCIM", req, false, true);
        assertEquals(UsbSpeedTester.Status.RELAY_ONLY, unstable.status);
        assertTrue(unstable.detail.contains("掉速波动"));
        assertTrue(unstable.detail.contains("正在录像"));
    }

    @Test
    public void runDirectorySpeedTestWritesMeasuresCleansUpAndReportsProgress() throws IOException {
        File dir = temporaryFolder.newFolder("usb_dcim");
        File stale = new File(dir, ".evcam_speed_test_stale.tmp");
        assertTrue(stale.createNewFile());

        UsbSpeedTester.RecordingRequirement req = UsbSpeedTester.buildRequirement(
                3, 7_500_000L, false, 25, "1280×800", "标准");
        UsbSpeedTester.TestHandle handle = new UsbSpeedTester.TestHandle();
        List<Integer> percents = new ArrayList<>();

        long chunkBytes = UsbSpeedTester.WRITE_BUFFER_BYTES; // 256 KB
        long testBytes = chunkBytes * 4; // 1 MB
        UsbSpeedTester.SpeedTestResult result = UsbSpeedTester.runDirectorySpeedTest(
                dir,
                256L * 1024L * 1024L,
                req,
                true,
                false,
                testBytes,
                testBytes,
                chunkBytes,
                32L * 1024L * 1024L,
                (percent, currentMBps, bytesWritten, targetBytes) -> percents.add(percent),
                handle
        );

        assertFalse(stale.exists());
        String[] remaining = dir.list();
        assertEquals(0, remaining == null ? 0 : remaining.length);
        assertEquals(testBytes, result.bytesWritten);
        assertTrue(result.avgWriteMBps > 0.0);
        assertTrue(result.status == UsbSpeedTester.Status.MEETS_DIRECT
                || result.status == UsbSpeedTester.Status.RELAY_ONLY
                || result.status == UsbSpeedTester.Status.INSUFFICIENT);
        assertFalse(percents.isEmpty());
        assertEquals(Integer.valueOf(100), percents.get(percents.size() - 1));
    }

    @Test
    public void runDirectorySpeedTestRejectsInsufficientSpaceAndNonExistentDir() throws IOException {
        File dir = temporaryFolder.newFolder("usb_low_space");
        UsbSpeedTester.RecordingRequirement req = UsbSpeedTester.buildRequirement(
                3, 7_500_000L, false, 25, "1280×800", "标准");

        UsbSpeedTester.SpeedTestResult lowSpace = UsbSpeedTester.runDirectorySpeedTest(
                dir,
                20L * 1024L * 1024L,
                req,
                true,
                false,
                UsbSpeedTester.DEFAULT_TARGET_TEST_BYTES,
                UsbSpeedTester.DEFAULT_MIN_TEST_BYTES,
                UsbSpeedTester.DEFAULT_CHUNK_BYTES,
                UsbSpeedTester.DEFAULT_FREE_HEADROOM_BYTES,
                null,
                new UsbSpeedTester.TestHandle()
        );
        assertEquals(UsbSpeedTester.Status.ERROR, lowSpace.status);
        assertTrue(lowSpace.detail.contains("可用空间不足"));

        File missingDir = new File(temporaryFolder.getRoot(), "does_not_exist");
        UsbSpeedTester.SpeedTestResult missing = UsbSpeedTester.runDirectorySpeedTest(
                missingDir,
                256L * 1024L * 1024L,
                req,
                true,
                false,
                UsbSpeedTester.WRITE_BUFFER_BYTES * 2L,
                UsbSpeedTester.WRITE_BUFFER_BYTES,
                UsbSpeedTester.WRITE_BUFFER_BYTES,
                0L,
                null,
                new UsbSpeedTester.TestHandle()
        );
        assertEquals(UsbSpeedTester.Status.ERROR, missing.status);
        assertTrue(missing.detail.contains("不存在"));
    }

    @Test
    public void runDirectorySpeedTestSupportsCancellationAndDeletesTempFile() throws IOException {
        File dir = temporaryFolder.newFolder("usb_cancel");
        UsbSpeedTester.RecordingRequirement req = UsbSpeedTester.buildRequirement(
                3, 7_500_000L, false, 25, "1280×800", "标准");
        UsbSpeedTester.TestHandle handle = new UsbSpeedTester.TestHandle();

        long chunkBytes = UsbSpeedTester.WRITE_BUFFER_BYTES;
        long testBytes = chunkBytes * 8;
        UsbSpeedTester.SpeedTestResult cancelled = UsbSpeedTester.runDirectorySpeedTest(
                dir,
                256L * 1024L * 1024L,
                req,
                true,
                false,
                testBytes,
                testBytes,
                chunkBytes,
                32L * 1024L * 1024L,
                (percent, currentMBps, bytesWritten, targetBytes) -> handle.cancel(),
                handle
        );

        assertEquals(UsbSpeedTester.Status.CANCELLED, cancelled.status);
        String[] remaining = dir.list();
        assertEquals(0, remaining == null ? 0 : remaining.length);
    }
}
