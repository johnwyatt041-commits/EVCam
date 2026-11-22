package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 线程安全的并发编码器准备协调器（纯 Java 状态机）：
 * <ul>
 *     <li>并发收集各路异步准备结果，全部就绪后按原键序一次触发批量回调；</li>
 *     <li>支持部分非致命失败降级、致命存储错误立即中止并清理、以及中途取消幂等回收；</li>
 *     <li>所有外部钩子（requestStop / releaseRecorder / onBatchReady）均在锁外调用，避免死锁。</li>
 * </ul>
 */
public final class CodecPrepareCoordinator<R, S> {

    public static final class PreparedEntry<R, S> {
        public final String key;
        public final R recorder;
        public final S surfaceToken;

        public PreparedEntry(String key, R recorder, S surfaceToken) {
            this.key = key;
            this.recorder = recorder;
            this.surfaceToken = surfaceToken;
        }
    }

    public static final class BatchResult<R, S> {
        public final long generationId;
        public final boolean cancelled;
        public final boolean fatalStorageError;
        public final String fatalErrorMessage;
        public final List<PreparedEntry<R, S>> succeeded;
        public final Map<String, String> failed;

        public BatchResult(
                long generationId,
                boolean cancelled,
                boolean fatalStorageError,
                String fatalErrorMessage,
                List<PreparedEntry<R, S>> succeeded,
                Map<String, String> failed) {
            this.generationId = generationId;
            this.cancelled = cancelled;
            this.fatalStorageError = fatalStorageError;
            this.fatalErrorMessage = fatalErrorMessage;
            this.succeeded = Collections.unmodifiableList(new ArrayList<>(succeeded));
            this.failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed));
        }
    }

    public interface Hooks<R, S> {
        void requestStop(R recorder);

        void releaseRecorder(R recorder);

        void onBatchReady(BatchResult<R, S> result);
    }

    private final long generationId;
    private final Map<String, R> planned;
    private final Set<String> pendingKeys;
    private final Map<String, PreparedEntry<R, S>> succeededByKey = new LinkedHashMap<>();
    private final Map<String, String> failed = new LinkedHashMap<>();
    private final Hooks<R, S> hooks;
    private boolean cancelled = false;
    private boolean settled = false;

    public CodecPrepareCoordinator(long generationId, Map<String, R> plannedRecorders, Hooks<R, S> hooks) {
        this.generationId = generationId;
        this.planned = Collections.unmodifiableMap(new LinkedHashMap<>(plannedRecorders));
        this.pendingKeys = new LinkedHashSet<>(this.planned.keySet());
        this.hooks = hooks;
    }

    public long getGenerationId() {
        return generationId;
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public synchronized boolean isPending() {
        return !settled && !cancelled && !pendingKeys.isEmpty();
    }

    public synchronized boolean isKeyPending(String key) {
        return key != null && !settled && !cancelled && pendingKeys.contains(key);
    }

    public void onPrepareResult(
            String key,
            boolean success,
            S surfaceToken,
            String errorMessage,
            boolean fatalStorageError) {
        List<R> stopTargets = null;
        List<R> releaseTargets = null;
        BatchResult<R, S> batchToEmit = null;

        synchronized (this) {
            R recorder = key != null ? planned.get(key) : null;
            if (recorder == null || !pendingKeys.remove(key)) {
                return;
            }

            if (settled || cancelled) {
                stopTargets = Collections.singletonList(recorder);
                releaseTargets = Collections.singletonList(recorder);
            } else if (success && surfaceToken != null) {
                succeededByKey.put(key, new PreparedEntry<>(key, recorder, surfaceToken));
                if (pendingKeys.isEmpty()) {
                    settled = true;
                    batchToEmit = new BatchResult<>(
                            generationId,
                            false,
                            false,
                            null,
                            collectOrderedSucceededLocked(),
                            failed
                    );
                }
            } else {
                String safeReason = (errorMessage != null && !errorMessage.trim().isEmpty())
                        ? errorMessage.trim()
                        : "Preparation failed";
                failed.put(key, safeReason);

                if (fatalStorageError) {
                    settled = true;
                    cancelled = true;
                    if (!pendingKeys.isEmpty()) {
                        stopTargets = new ArrayList<>(pendingKeys.size());
                        for (String pendingKey : pendingKeys) {
                            R pendingRecorder = planned.get(pendingKey);
                            if (pendingRecorder != null) {
                                stopTargets.add(pendingRecorder);
                            }
                        }
                    }
                    releaseTargets = new ArrayList<>(succeededByKey.size() + 1);
                    for (PreparedEntry<R, S> entry : succeededByKey.values()) {
                        if (entry.recorder != null) {
                            releaseTargets.add(entry.recorder);
                        }
                    }
                    succeededByKey.clear();
                    releaseTargets.add(recorder);
                    batchToEmit = new BatchResult<>(
                            generationId,
                            false,
                            true,
                            safeReason,
                            Collections.emptyList(),
                            failed
                    );
                } else {
                    releaseTargets = Collections.singletonList(recorder);
                    if (pendingKeys.isEmpty()) {
                        settled = true;
                        batchToEmit = new BatchResult<>(
                                generationId,
                                false,
                                false,
                                null,
                                collectOrderedSucceededLocked(),
                                failed
                        );
                    }
                }
            }
        }

        if (hooks != null) {
            if (stopTargets != null) {
                for (R target : stopTargets) {
                    if (target != null) {
                        hooks.requestStop(target);
                    }
                }
            }
            if (releaseTargets != null) {
                for (R target : releaseTargets) {
                    if (target != null) {
                        hooks.releaseRecorder(target);
                    }
                }
            }
            if (batchToEmit != null) {
                hooks.onBatchReady(batchToEmit);
            }
        }
    }

    public void cancel() {
        List<R> stopTargets;
        List<R> releaseTargets;

        synchronized (this) {
            if (cancelled || settled) {
                return;
            }
            cancelled = true;
            settled = true;
            stopTargets = new ArrayList<>(planned.size());
            for (R recorder : planned.values()) {
                if (recorder != null) {
                    stopTargets.add(recorder);
                }
            }
            releaseTargets = new ArrayList<>(succeededByKey.size());
            for (PreparedEntry<R, S> entry : succeededByKey.values()) {
                if (entry.recorder != null) {
                    releaseTargets.add(entry.recorder);
                }
            }
            succeededByKey.clear();
        }

        if (hooks != null) {
            for (R target : stopTargets) {
                hooks.requestStop(target);
            }
            for (R target : releaseTargets) {
                hooks.releaseRecorder(target);
            }
        }
    }

    private List<PreparedEntry<R, S>> collectOrderedSucceededLocked() {
        List<PreparedEntry<R, S>> ordered = new ArrayList<>(succeededByKey.size());
        for (String key : planned.keySet()) {
            PreparedEntry<R, S> entry = succeededByKey.get(key);
            if (entry != null) {
                ordered.add(entry);
            }
        }
        return ordered;
    }
}
