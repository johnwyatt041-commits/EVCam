package com.kooo.evcam.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks per-camera recording startup progress so that:
 * <ul>
 *     <li>The global startup watchdog stays armed until every expected camera either writes valid data or fails.</li>
 *     <li>Errors arriving after another camera has already started writing are never silently ignored.</li>
 *     <li>Partial failures trigger explicit per-camera cleanup and partial-status reporting instead of false full-success.</li>
 * </ul>
 */
public final class RecordingStartupTracker {

    public static final class Outcome {
        public final boolean shouldNotifyFirstWrite;
        public final boolean shouldCancelWatchdog;
        public final boolean allFailed;
        public final boolean partialFailure;
        public final boolean fatalStorageError;
        @NonNull
        public final Set<String> writtenKeys;
        @NonNull
        public final Set<String> failedKeys;
        @NonNull
        public final Map<String, String> failedReasons;

        Outcome(boolean shouldNotifyFirstWrite,
                boolean shouldCancelWatchdog,
                boolean allFailed,
                boolean partialFailure,
                boolean fatalStorageError,
                @NonNull Set<String> writtenKeys,
                @NonNull Set<String> failedKeys,
                @NonNull Map<String, String> failedReasons) {
            this.shouldNotifyFirstWrite = shouldNotifyFirstWrite;
            this.shouldCancelWatchdog = shouldCancelWatchdog;
            this.allFailed = allFailed;
            this.partialFailure = partialFailure;
            this.fatalStorageError = fatalStorageError;
            this.writtenKeys = Collections.unmodifiableSet(new LinkedHashSet<>(writtenKeys));
            this.failedKeys = Collections.unmodifiableSet(new LinkedHashSet<>(failedKeys));
            this.failedReasons = Collections.unmodifiableMap(new LinkedHashMap<>(failedReasons));
        }
    }

    private final Set<String> expectedKeys = new LinkedHashSet<>();
    private final Set<String> writtenKeys = new LinkedHashSet<>();
    private final Map<String, String> failedReasons = new LinkedHashMap<>();
    private boolean firstWriteNotified = false;
    private boolean settled = false;

    public synchronized void reset(@Nullable Collection<String> expected) {
        expectedKeys.clear();
        writtenKeys.clear();
        failedReasons.clear();
        firstWriteNotified = false;
        settled = false;
        if (expected != null) {
            for (String key : expected) {
                if (key != null && !key.isEmpty()) {
                    expectedKeys.add(key);
                }
            }
        }
    }

    public synchronized boolean isTracking() {
        return !expectedKeys.isEmpty();
    }

    @NonNull
    public synchronized Outcome recordFirstDataWritten(@Nullable String key) {
        if (key == null || key.isEmpty() || expectedKeys.isEmpty()) {
            return snapshotOutcome(false, false, false, false, false);
        }
        if (!expectedKeys.contains(key)) {
            expectedKeys.add(key);
        }
        if (failedReasons.containsKey(key)) {
            return snapshotOutcome(false, settled, false, false, false);
        }
        boolean newlyWritten = writtenKeys.add(key);
        if (!newlyWritten) {
            return snapshotOutcome(false, settled, false, false, false);
        }
        boolean notifyFirst = !firstWriteNotified;
        if (notifyFirst) {
            firstWriteNotified = true;
        }
        boolean allResolved = (writtenKeys.size() + failedReasons.size()) >= expectedKeys.size();
        if (allResolved) {
            settled = true;
        }
        boolean partialFailure = allResolved && !writtenKeys.isEmpty() && !failedReasons.isEmpty();
        return snapshotOutcome(notifyFirst, allResolved, false, partialFailure, false);
    }

    @NonNull
    public synchronized Outcome recordCameraFailure(@Nullable String key,
                                                    @Nullable String reason,
                                                    boolean fatalStorageError) {
        if (expectedKeys.isEmpty()) {
            return snapshotOutcome(false, false, fatalStorageError, false, fatalStorageError);
        }
        if (key == null || key.isEmpty()) {
            if (fatalStorageError && writtenKeys.isEmpty()) {
                settled = true;
                return snapshotOutcome(false, true, true, false, true);
            }
            return snapshotOutcome(false, settled, false, false, fatalStorageError);
        }
        if (!expectedKeys.contains(key)) {
            expectedKeys.add(key);
        }
        writtenKeys.remove(key);
        String safeReason = (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "unknown";
        failedReasons.put(key, safeReason);

        boolean allResolved = (writtenKeys.size() + failedReasons.size()) >= expectedKeys.size();
        boolean allFailed = writtenKeys.isEmpty() && (allResolved || fatalStorageError);
        if (allResolved || allFailed) {
            settled = true;
        }
        boolean partialFailure = !allFailed && !writtenKeys.isEmpty() && !failedReasons.isEmpty();
        boolean cancelWatchdog = allResolved || allFailed;
        return snapshotOutcome(false, cancelWatchdog, allFailed, partialFailure, fatalStorageError);
    }

    @NonNull
    public synchronized Outcome onWatchdogTimeout() {
        for (String key : expectedKeys) {
            if (!writtenKeys.contains(key) && !failedReasons.containsKey(key)) {
                failedReasons.put(key, "first_write_timeout");
            }
        }
        settled = true;
        boolean allFailed = writtenKeys.isEmpty();
        boolean partialFailure = !writtenKeys.isEmpty() && !failedReasons.isEmpty();
        return snapshotOutcome(false, true, allFailed, partialFailure, false);
    }

    @NonNull
    public synchronized Set<String> getPendingWriteKeys() {
        Set<String> pending = new LinkedHashSet<>();
        for (String key : expectedKeys) {
            if (!writtenKeys.contains(key) && !failedReasons.containsKey(key)) {
                pending.add(key);
            }
        }
        return pending;
    }

    @NonNull
    public synchronized Set<String> getWrittenKeys() {
        return new LinkedHashSet<>(writtenKeys);
    }

    @NonNull
    public synchronized Map<String, String> getFailedReasons() {
        return new LinkedHashMap<>(failedReasons);
    }

    @NonNull
    public synchronized String formatSummary() {
        return "expected=" + expectedKeys
                + ", written=" + writtenKeys
                + ", failed=" + failedReasons
                + ", pending=" + getPendingWriteKeys()
                + ", settled=" + settled;
    }

    @NonNull
    private Outcome snapshotOutcome(boolean shouldNotifyFirstWrite,
                                    boolean shouldCancelWatchdog,
                                    boolean allFailed,
                                    boolean partialFailure,
                                    boolean fatalStorageError) {
        return new Outcome(
                shouldNotifyFirstWrite,
                shouldCancelWatchdog,
                allFailed,
                partialFailure,
                fatalStorageError,
                writtenKeys,
                failedReasons.keySet(),
                failedReasons
        );
    }
}
