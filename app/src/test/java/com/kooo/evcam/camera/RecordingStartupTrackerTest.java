package com.kooo.evcam.camera;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class RecordingStartupTrackerTest {

    @Test
    public void watchdogRemainsArmedUntilEveryExpectedCameraWritesOrFails() {
        RecordingStartupTracker tracker = new RecordingStartupTracker();
        tracker.reset(Arrays.asList("front", "back", "left", "right"));

        RecordingStartupTracker.Outcome o1 = tracker.recordFirstDataWritten("front");
        assertTrue(o1.shouldNotifyFirstWrite);
        assertFalse(o1.shouldCancelWatchdog);
        assertFalse(o1.allFailed);
        assertFalse(o1.partialFailure);

        RecordingStartupTracker.Outcome o2 = tracker.recordFirstDataWritten("back");
        assertFalse(o2.shouldNotifyFirstWrite);
        assertFalse(o2.shouldCancelWatchdog);

        RecordingStartupTracker.Outcome o3 = tracker.recordFirstDataWritten("left");
        assertFalse(o3.shouldCancelWatchdog);

        RecordingStartupTracker.Outcome o4 = tracker.recordFirstDataWritten("right");
        assertTrue(o4.shouldCancelWatchdog);
        assertFalse(o4.allFailed);
        assertFalse(o4.partialFailure);
        assertEquals(4, o4.writtenKeys.size());
        assertTrue(tracker.getPendingWriteKeys().isEmpty());
    }

    @Test
    public void partialFailureAfterFirstCameraWrittenReportsPartialAndKeepsTrackingRemaining() {
        RecordingStartupTracker tracker = new RecordingStartupTracker();
        tracker.reset(Arrays.asList("front", "back", "left", "right"));

        RecordingStartupTracker.Outcome first = tracker.recordFirstDataWritten("front");
        assertTrue(first.shouldNotifyFirstWrite);
        assertFalse(first.shouldCancelWatchdog);

        // Second camera errors permanently after first camera already wrote.
        RecordingStartupTracker.Outcome backErr = tracker.recordCameraFailure("back", "Video writer failed: EIO", false);
        assertFalse(backErr.allFailed);
        assertTrue(backErr.partialFailure);
        assertFalse(backErr.shouldCancelWatchdog);
        assertEquals(Collections.singleton("front"), backErr.writtenKeys);
        assertEquals(Collections.singleton("back"), backErr.failedKeys);

        // Third writes; still waiting on fourth.
        RecordingStartupTracker.Outcome leftWrite = tracker.recordFirstDataWritten("left");
        assertFalse(leftWrite.shouldCancelWatchdog);

        // Fourth writes -> settles and cancels watchdog with partial failure summary.
        RecordingStartupTracker.Outcome rightWrite = tracker.recordFirstDataWritten("right");
        assertTrue(rightWrite.shouldCancelWatchdog);
        assertTrue(rightWrite.partialFailure);
        assertFalse(rightWrite.allFailed);
        assertEquals(3, rightWrite.writtenKeys.size());
        assertEquals(Collections.singleton("back"), rightWrite.failedKeys);
    }

    @Test
    public void fatalStorageErrorWithZeroWrittenAbortsImmediately() {
        RecordingStartupTracker tracker = new RecordingStartupTracker();
        tracker.reset(Arrays.asList("front", "back", "left", "right"));

        RecordingStartupTracker.Outcome err = tracker.recordCameraFailure("front", "EACCES permission denied", true);
        assertTrue(err.allFailed);
        assertTrue(err.shouldCancelWatchdog);
        assertFalse(err.partialFailure);
        assertTrue(err.fatalStorageError);
    }

    @Test
    public void nonFatalFailuresOnAllCamerasSettleAsAllFailedOnlyWhenLastFails() {
        RecordingStartupTracker tracker = new RecordingStartupTracker();
        tracker.reset(Arrays.asList("front", "back"));

        RecordingStartupTracker.Outcome frontErr = tracker.recordCameraFailure("front", "codec_start_failed", false);
        assertFalse(frontErr.allFailed);
        assertFalse(frontErr.shouldCancelWatchdog);

        RecordingStartupTracker.Outcome backErr = tracker.recordCameraFailure("back", "codec_start_failed", false);
        assertTrue(backErr.allFailed);
        assertTrue(backErr.shouldCancelWatchdog);
    }

    @Test
    public void watchdogTimeoutHandlesPartialAndTotalTimeoutsSeparately() {
        RecordingStartupTracker partialTracker = new RecordingStartupTracker();
        partialTracker.reset(Arrays.asList("front", "back", "left"));
        partialTracker.recordFirstDataWritten("front");

        RecordingStartupTracker.Outcome partialTimeout = partialTracker.onWatchdogTimeout();
        assertFalse(partialTimeout.allFailed);
        assertTrue(partialTimeout.partialFailure);
        assertTrue(partialTimeout.shouldCancelWatchdog);
        assertEquals(Collections.singleton("front"), partialTimeout.writtenKeys);
        assertEquals(2, partialTimeout.failedKeys.size());
        assertTrue(partialTimeout.failedKeys.contains("back"));
        assertTrue(partialTimeout.failedKeys.contains("left"));

        RecordingStartupTracker totalTracker = new RecordingStartupTracker();
        totalTracker.reset(Arrays.asList("front", "back"));
        RecordingStartupTracker.Outcome totalTimeout = totalTracker.onWatchdogTimeout();
        assertTrue(totalTimeout.allFailed);
        assertFalse(totalTimeout.partialFailure);
        assertTrue(totalTimeout.shouldCancelWatchdog);
    }

    @Test
    public void compositeModeTracksSingleCombinedKey() {
        RecordingStartupTracker tracker = new RecordingStartupTracker();
        tracker.reset(Collections.singletonList("combined"));

        RecordingStartupTracker.Outcome written = tracker.recordFirstDataWritten("combined");
        assertTrue(written.shouldNotifyFirstWrite);
        assertTrue(written.shouldCancelWatchdog);
        assertFalse(written.allFailed);
        assertFalse(written.partialFailure);
    }
}
