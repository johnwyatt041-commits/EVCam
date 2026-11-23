package com.kooo.evcam;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.camera.ActiveRecordingRegistry;
import com.kooo.evcam.playback.VideoLockStore;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 协调手动扫描并安全清理不可恢复碎片（{@code .pending} / {@code .part} / 测速及探测临时文件 / 孤立 {@code .lock}）。
 * 严禁在扫描阶段自动删除数据，必须向用户展示明细并经二次确认，删除前复核活跃录制/传输与宽限窗口。
 */
public final class FragmentCleanupCoordinator {

    private static final AtomicBoolean OPERATION_IN_PROGRESS = new AtomicBoolean(false);

    private FragmentCleanupCoordinator() {
    }

    public static void startManualScanAndPrompt(@NonNull Fragment fragment,
                                                @Nullable Runnable onScanStarted,
                                                @Nullable Runnable onScanFinished,
                                                @Nullable Consumer<String> onStatusMessage,
                                                @Nullable Runnable onFilesChanged) {
        Context context = fragment.getContext();
        Activity activity = fragment.getActivity();
        if (context == null || activity == null || activity.isFinishing() || activity.isDestroyed() || !fragment.isAdded()) {
            return;
        }
        if (!OPERATION_IN_PROGRESS.compareAndSet(false, true)) {
            Toast.makeText(context, "正在扫描或清理碎片，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }

        final Context appCtx = context.getApplicationContext();
        if (onScanStarted != null) {
            onScanStarted.run();
        }
        if (onStatusMessage != null) {
            onStatusMessage.accept("正在扫描录像存储目录与中转缓存中的残留碎片...");
        }

        PendingFileRecoveryManager.scanForManualCleanupAsync(appCtx, report -> {
            if (onScanFinished != null) {
                onScanFinished.run();
            }
            Context uiCtx = fragment.getContext();
            Activity uiAct = fragment.getActivity();
            if (uiCtx == null || uiAct == null || uiAct.isFinishing() || uiAct.isDestroyed() || !fragment.isAdded()) {
                OPERATION_IN_PROGRESS.set(false);
                return;
            }
            if (report.hasRecoveredFiles() && onFilesChanged != null) {
                onFilesChanged.run();
            }
            if (!report.hasDeletableFragments()) {
                OPERATION_IN_PROGRESS.set(false);
                showNothingToDeleteDialog(uiCtx, report, onStatusMessage);
                return;
            }
            showConfirmCleanupDialog(fragment, appCtx, uiCtx, report, onStatusMessage, onFilesChanged);
        });
    }

    private static void showNothingToDeleteDialog(@NonNull Context uiCtx,
                                                  @NonNull PendingFileRecoveryManager.ManualScanReport report,
                                                  @Nullable Consumer<String> onStatusMessage) {
        StringBuilder msg = new StringBuilder();
        if (report.hasRecoveredFiles()) {
            msg.append("已自动无损恢复 ")
                    .append(report.recoveredFiles.size())
                    .append(" 个可播放的录像文件。\n\n");
        }
        msg.append("未发现可清理的不可恢复残留碎片。");
        if (report.protectedActiveCount > 0) {
            msg.append("\n已自动跳过并保护 ")
                    .append(report.protectedActiveCount)
                    .append(" 个正在录制或传输中的活跃文件。");
        }
        String summary = report.hasRecoveredFiles()
                ? "已恢复 " + report.recoveredFiles.size() + " 个录像，未发现不可恢复碎片"
                : (report.protectedActiveCount > 0
                        ? "未发现可清理碎片（已保护 " + report.protectedActiveCount + " 个录制/传输中文件）"
                        : "未发现可清理的残留碎片");
        if (onStatusMessage != null) {
            onStatusMessage.accept(summary);
        }
        new MaterialAlertDialogBuilder(uiCtx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("存储检查完成")
                .setMessage(msg.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    private static void showConfirmCleanupDialog(@NonNull Fragment fragment,
                                                 @NonNull Context appCtx,
                                                 @NonNull Context uiCtx,
                                                 @NonNull PendingFileRecoveryManager.ManualScanReport report,
                                                 @Nullable Consumer<String> onStatusMessage,
                                                 @Nullable Runnable onFilesChanged) {
        StringBuilder msg = new StringBuilder();
        if (report.hasRecoveredFiles()) {
            msg.append("已先自动恢复 ")
                    .append(report.recoveredFiles.size())
                    .append(" 个可播放的录像文件。\n\n");
        }
        msg.append("检测到 ")
                .append(report.getTotalDeletableCount())
                .append(" 个不可恢复残留碎片（预计释放 ")
                .append(StorageHelper.formatSize(report.totalDeletableBytes))
                .append("）：\n\n");
        if (report.pendingCount > 0) {
            msg.append("• 中断 .pending 碎片：").append(report.pendingCount)
                    .append(" 个（").append(StorageHelper.formatSize(report.pendingBytes)).append("）\n");
        }
        if (report.partCount > 0) {
            msg.append("• 未完成传输 .part 碎片：").append(report.partCount)
                    .append(" 个（").append(StorageHelper.formatSize(report.partBytes)).append("）\n");
        }
        if (report.tempProbeCount > 0) {
            msg.append("• 探测/测速临时文件：").append(report.tempProbeCount)
                    .append(" 个（").append(StorageHelper.formatSize(report.tempProbeBytes)).append("）\n");
        }
        if (report.orphanLockCount > 0) {
            msg.append("• 孤立 .lock 标记：").append(report.orphanLockCount)
                    .append(" 个（").append(StorageHelper.formatSize(report.orphanLockBytes)).append("）\n");
        }
        if (report.protectedActiveCount > 0) {
            msg.append("\n已自动保护跳过 ").append(report.protectedActiveCount)
                    .append(" 个正在录制或传输中的文件。\n");
        }
        msg.append("\n安全确认：仅清理上述异常碎片，绝不删除正常录像、照片、已锁定媒体或录制中的文件。确认立即清理？");

        final boolean[] cleanupDispatched = {false};
        AlertDialog dialog = new MaterialAlertDialogBuilder(uiCtx, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认清理不可恢复碎片")
                .setMessage(msg.toString())
                .setPositiveButton("确认清理", (d, w) -> {
                    cleanupDispatched[0] = true;
                    runSafeDeletion(fragment, appCtx, report, onStatusMessage, onFilesChanged);
                })
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (!cleanupDispatched[0]) {
                OPERATION_IN_PROGRESS.set(false);
                if (onStatusMessage != null) {
                    onStatusMessage.accept("已取消清理（发现 " + report.getTotalDeletableCount() + " 个碎片，约 "
                            + StorageHelper.formatSize(report.totalDeletableBytes) + "）");
                }
            }
        });
        dialog.show();
    }

    private static void runSafeDeletion(@NonNull Fragment fragment,
                                        @NonNull Context appCtx,
                                        @NonNull PendingFileRecoveryManager.ManualScanReport report,
                                        @Nullable Consumer<String> onStatusMessage,
                                        @Nullable Runnable onFilesChanged) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                Set<String> freshProtected = new HashSet<>(ActiveRecordingRegistry.snapshot());
                freshProtected.addAll(FileTransferManager.getInstance(appCtx).collectActiveTransferPaths());

                PendingFileRecoveryManager.ManualDeleteResult result =
                        PendingFileRecoveryManager.deleteFragmentsSafely(
                                report.deletableFragments,
                                freshProtected,
                                System.currentTimeMillis(),
                                VideoLockStore.ACTIVE_PENDING_GRACE_WINDOW_MS);

                mainHandler.post(() -> {
                    Context uiCtx = fragment.getContext();
                    Activity uiAct = fragment.getActivity();
                    String resultText = result.skippedCount > 0
                            ? "已清理 " + result.deletedCount + " 个碎片，释放 "
                                    + StorageHelper.formatSize(result.freedBytes)
                                    + "（安全跳过 " + result.skippedCount + " 个）"
                            : "已清理 " + result.deletedCount + " 个碎片，释放 "
                                    + StorageHelper.formatSize(result.freedBytes);
                    if (uiCtx != null && uiAct != null && !uiAct.isFinishing() && !uiAct.isDestroyed() && fragment.isAdded()) {
                        Toast.makeText(uiCtx, resultText, Toast.LENGTH_LONG).show();
                        if (onStatusMessage != null) {
                            onStatusMessage.accept(resultText);
                        }
                        if (result.deletedCount > 0 && onFilesChanged != null) {
                            onFilesChanged.run();
                        }
                    }
                });
            } finally {
                OPERATION_IN_PROGRESS.set(false);
            }
        }, "ManualFragmentCleanup");
        worker.setDaemon(true);
        worker.start();
    }
}
