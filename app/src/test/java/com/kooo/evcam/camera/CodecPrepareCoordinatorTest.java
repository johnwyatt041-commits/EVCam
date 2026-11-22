package com.kooo.evcam.camera;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CodecPrepareCoordinatorTest {

    private static final class FakeRecorder {
        final String id;
        final AtomicInteger stopCalls = new AtomicInteger();
        final AtomicInteger releaseCalls = new AtomicInteger();

        FakeRecorder(String id) {
            this.id = id;
        }
    }

    private static Map<String, FakeRecorder> fourRecorders() {
        Map<String, FakeRecorder> map = new LinkedHashMap<>();
        map.put("front", new FakeRecorder("front"));
        map.put("back", new FakeRecorder("back"));
        map.put("left", new FakeRecorder("left"));
        map.put("right", new FakeRecorder("right"));
        return map;
    }

    @Test
    public void concurrentOutOfOrderSuccessPreservesPlannedOrderAndFiresOnce() throws Exception {
        Map<String, FakeRecorder> planned = fourRecorders();
        List<CodecPrepareCoordinator.BatchResult<FakeRecorder, String>> batches = new CopyOnWriteArrayList<>();
        CountDownLatch batchLatch = new CountDownLatch(1);

        CodecPrepareCoordinator<FakeRecorder, String> coordinator = new CodecPrepareCoordinator<>(
                1L,
                planned,
                new CodecPrepareCoordinator.Hooks<FakeRecorder, String>() {
                    @Override
                    public void requestStop(FakeRecorder recorder) {
                        recorder.stopCalls.incrementAndGet();
                    }

                    @Override
                    public void releaseRecorder(FakeRecorder recorder) {
                        recorder.releaseCalls.incrementAndGet();
                    }

                    @Override
                    public void onBatchReady(CodecPrepareCoordinator.BatchResult<FakeRecorder, String> result) {
                        batches.add(result);
                        batchLatch.countDown();
                    }
                });

        assertTrue(coordinator.isPending());
        assertTrue(coordinator.isKeyPending("front"));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch startGate = new CountDownLatch(1);
        String[] reverseOrder = {"right", "left", "back", "front"};
        try {
            for (int i = 0; i < reverseOrder.length; i++) {
                final String key = reverseOrder[i];
                final int sleepMs = i * 10;
                pool.submit(() -> {
                    try {
                        startGate.await(2, TimeUnit.SECONDS);
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ignored) {
                    }
                    coordinator.onPrepareResult(key, true, "surface-" + key, null, false);
                });
            }
            startGate.countDown();
            assertTrue(batchLatch.await(3, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertFalse(coordinator.isPending());
        assertFalse(coordinator.isKeyPending("front"));
        assertEquals(1, batches.size());

        CodecPrepareCoordinator.BatchResult<FakeRecorder, String> result = batches.get(0);
        assertEquals(1L, result.generationId);
        assertFalse(result.cancelled);
        assertFalse(result.fatalStorageError);
        assertTrue(result.failed.isEmpty());
        assertEquals(4, result.succeeded.size());
        assertEquals("front", result.succeeded.get(0).key);
        assertEquals("back", result.succeeded.get(1).key);
        assertEquals("left", result.succeeded.get(2).key);
        assertEquals("right", result.succeeded.get(3).key);

        for (FakeRecorder recorder : planned.values()) {
            assertEquals(0, recorder.stopCalls.get());
            assertEquals(0, recorder.releaseCalls.get());
        }
    }

    @Test
    public void nonFatalPartialFailureReleasesOnlyFailedAndKeepsReadySubset() {
        Map<String, FakeRecorder> planned = fourRecorders();
        List<CodecPrepareCoordinator.BatchResult<FakeRecorder, String>> batches = new ArrayList<>();

        CodecPrepareCoordinator<FakeRecorder, String> coordinator = new CodecPrepareCoordinator<>(
                2L,
                planned,
                new CodecPrepareCoordinator.Hooks<FakeRecorder, String>() {
                    @Override
                    public void requestStop(FakeRecorder recorder) {
                        recorder.stopCalls.incrementAndGet();
                    }

                    @Override
                    public void releaseRecorder(FakeRecorder recorder) {
                        recorder.releaseCalls.incrementAndGet();
                    }

                    @Override
                    public void onBatchReady(CodecPrepareCoordinator.BatchResult<FakeRecorder, String> result) {
                        batches.add(result);
                    }
                });

        coordinator.onPrepareResult("front", true, "s-front", null, false);
        coordinator.onPrepareResult("back", false, null, "encoder init failed", false);
        assertEquals(1, planned.get("back").releaseCalls.get());
        assertEquals(0, batches.size());
        assertTrue(coordinator.isPending());

        coordinator.onPrepareResult("left", true, "s-left", null, false);
        coordinator.onPrepareResult("right", true, "s-right", null, false);

        assertFalse(coordinator.isPending());
        assertEquals(1, batches.size());
        CodecPrepareCoordinator.BatchResult<FakeRecorder, String> result = batches.get(0);
        assertFalse(result.cancelled);
        assertFalse(result.fatalStorageError);
        assertEquals(3, result.succeeded.size());
        assertEquals("front", result.succeeded.get(0).key);
        assertEquals("left", result.succeeded.get(1).key);
        assertEquals("right", result.succeeded.get(2).key);
        assertEquals(Collections.singletonMap("back", "encoder init failed"), result.failed);
        assertEquals(0, planned.get("front").releaseCalls.get());
        assertEquals(0, planned.get("left").releaseCalls.get());
        assertEquals(0, planned.get("right").releaseCalls.get());
    }

    @Test
    public void fatalStorageErrorAbortsInFlightReleasesAllAndIgnoresLateCallbacks() {
        Map<String, FakeRecorder> planned = fourRecorders();
        List<CodecPrepareCoordinator.BatchResult<FakeRecorder, String>> batches = new ArrayList<>();

        CodecPrepareCoordinator<FakeRecorder, String> coordinator = new CodecPrepareCoordinator<>(
                3L,
                planned,
                new CodecPrepareCoordinator.Hooks<FakeRecorder, String>() {
                    @Override
                    public void requestStop(FakeRecorder recorder) {
                        recorder.stopCalls.incrementAndGet();
                    }

                    @Override
                    public void releaseRecorder(FakeRecorder recorder) {
                        recorder.releaseCalls.incrementAndGet();
                    }

                    @Override
                    public void onBatchReady(CodecPrepareCoordinator.BatchResult<FakeRecorder, String> result) {
                        batches.add(result);
                    }
                });

        // 1 路先成功，随后第 2 路报 EPERM 致命存储错误
        coordinator.onPrepareResult("front", true, "s-front", null, false);
        coordinator.onPrepareResult("back", false, null, "open failed: EPERM (Operation not permitted)", true);

        assertTrue(coordinator.isCancelled());
        assertFalse(coordinator.isPending());
        assertEquals(1, batches.size());
        CodecPrepareCoordinator.BatchResult<FakeRecorder, String> result = batches.get(0);
        assertFalse(result.cancelled);
        assertTrue(result.fatalStorageError);
        assertTrue(result.fatalErrorMessage.contains("EPERM"));
        assertTrue(result.succeeded.isEmpty());

        // 已成功的 front 与致命失败的 back 立刻被释放；仍在途的 left/right 收到 requestStop
        assertEquals(1, planned.get("front").releaseCalls.get());
        assertEquals(1, planned.get("back").releaseCalls.get());
        assertEquals(1, planned.get("left").stopCalls.get());
        assertEquals(1, planned.get("right").stopCalls.get());
        assertEquals(0, planned.get("left").releaseCalls.get());

        // 晚到的 left/right 完成回调自动清理释放，且不重复触发 onBatchReady
        coordinator.onPrepareResult("left", true, "s-left", null, false);
        coordinator.onPrepareResult("right", false, null, "Cancelled", false);
        assertEquals(1, planned.get("left").releaseCalls.get());
        assertEquals(1, planned.get("right").releaseCalls.get());
        assertEquals(1, batches.size());
    }

    @Test
    public void cancelStopsAllPlannedReleasesSucceededAndCleansLateInFlightWithoutBatchCallback() {
        Map<String, FakeRecorder> planned = fourRecorders();
        List<CodecPrepareCoordinator.BatchResult<FakeRecorder, String>> batches = new ArrayList<>();

        CodecPrepareCoordinator<FakeRecorder, String> coordinator = new CodecPrepareCoordinator<>(
                4L,
                planned,
                new CodecPrepareCoordinator.Hooks<FakeRecorder, String>() {
                    @Override
                    public void requestStop(FakeRecorder recorder) {
                        recorder.stopCalls.incrementAndGet();
                    }

                    @Override
                    public void releaseRecorder(FakeRecorder recorder) {
                        recorder.releaseCalls.incrementAndGet();
                    }

                    @Override
                    public void onBatchReady(CodecPrepareCoordinator.BatchResult<FakeRecorder, String> result) {
                        batches.add(result);
                    }
                });

        coordinator.onPrepareResult("front", true, "s-front", null, false);
        coordinator.onPrepareResult("back", true, "s-back", null, false);

        coordinator.cancel();
        coordinator.cancel(); // 幂等

        assertTrue(coordinator.isCancelled());
        assertFalse(coordinator.isPending());
        assertEquals(0, batches.size());
        for (FakeRecorder recorder : planned.values()) {
            assertEquals(1, recorder.stopCalls.get());
        }
        assertEquals(1, planned.get("front").releaseCalls.get());
        assertEquals(1, planned.get("back").releaseCalls.get());
        assertEquals(0, planned.get("left").releaseCalls.get());
        assertEquals(0, planned.get("right").releaseCalls.get());

        // 晚到在途回调自动释放清理且永不触发 onBatchReady
        coordinator.onPrepareResult("left", true, "s-left", null, false);
        coordinator.onPrepareResult("right", false, null, "Cancelled", false);
        assertEquals(1, planned.get("left").releaseCalls.get());
        assertEquals(1, planned.get("right").releaseCalls.get());
        assertEquals(0, batches.size());
    }

    @Test
    public void allFailuresAndDuplicateCallbacksAreHandledIdempotently() {
        Map<String, FakeRecorder> planned = fourRecorders();
        List<CodecPrepareCoordinator.BatchResult<FakeRecorder, String>> batches = new ArrayList<>();

        CodecPrepareCoordinator<FakeRecorder, String> coordinator = new CodecPrepareCoordinator<>(
                5L,
                planned,
                new CodecPrepareCoordinator.Hooks<FakeRecorder, String>() {
                    @Override
                    public void requestStop(FakeRecorder recorder) {
                        recorder.stopCalls.incrementAndGet();
                    }

                    @Override
                    public void releaseRecorder(FakeRecorder recorder) {
                        recorder.releaseCalls.incrementAndGet();
                    }

                    @Override
                    public void onBatchReady(CodecPrepareCoordinator.BatchResult<FakeRecorder, String> result) {
                        batches.add(result);
                    }
                });

        coordinator.onPrepareResult("front", false, null, "err-front", false);
        coordinator.onPrepareResult("front", true, "dup-surface", null, false); // 重复回调必须忽略
        coordinator.onPrepareResult("back", false, null, "err-back", false);
        coordinator.onPrepareResult("left", false, null, "err-left", false);
        coordinator.onPrepareResult("right", false, null, "err-right", false);

        assertEquals(1, batches.size());
        CodecPrepareCoordinator.BatchResult<FakeRecorder, String> result = batches.get(0);
        assertTrue(result.succeeded.isEmpty());
        assertEquals(4, result.failed.size());
        for (FakeRecorder recorder : planned.values()) {
            assertEquals(1, recorder.releaseCalls.get());
        }
    }

    @Test
    public void codecVideoRecorderAsyncPrepareHonorsPreRequestedStopAndRelease() throws Exception {
        CodecVideoRecorder stoppedBeforeStart = new CodecVideoRecorder("front", 1280, 800);
        try {
            stoppedBeforeStart.requestStop();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean successRef = new AtomicBoolean(true);
            AtomicReference<String> errRef = new AtomicReference<>();

            stoppedBeforeStart.prepareRecordingAsync(
                    "dummy_front.mp4",
                    Runnable::run,
                    (success, surfaceTexture, errorMessage) -> {
                        successRef.set(success);
                        errRef.set(errorMessage);
                        latch.countDown();
                    });

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertFalse(successRef.get());
            assertEquals("Cancelled", errRef.get());
            assertEquals("Cancelled", stoppedBeforeStart.getLastPrepareErrorMessage());
        } finally {
            stoppedBeforeStart.release();
        }

        CodecVideoRecorder releasedBeforeStart = new CodecVideoRecorder("back", 1280, 800);
        releasedBeforeStart.release();
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicBoolean successRef2 = new AtomicBoolean(true);
        AtomicReference<String> errRef2 = new AtomicReference<>();
        releasedBeforeStart.prepareRecordingAsync(
                "dummy_back.mp4",
                Runnable::run,
                (success, surfaceTexture, errorMessage) -> {
                    successRef2.set(success);
                    errRef2.set(errorMessage);
                    latch2.countDown();
                });
        assertTrue(latch2.await(2, TimeUnit.SECONDS));
        assertFalse(successRef2.get());
        assertEquals("Cancelled", errRef2.get());
        assertNull(releasedBeforeStart.getRecordSurface());
    }
}
