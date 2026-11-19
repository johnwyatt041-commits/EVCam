package com.kooo.evcam;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 录像存储可写性预检与写入致命错误分类工具。
 * 保持既有的 DCIM/EVCam_Video/*.mp4.pending 文件协议不变，只在写入前快速探测真实可写性
 * 并识别权限拒绝/只读/空间不足等不可通过重建编码器恢复的错误。
 */
public final class RecordingStorageAccess {
    private static final String TAG = "RecordingStorageAccess";
    private static final AtomicLong PROBE_COUNTER = new AtomicLong();

    private RecordingStorageAccess() {
    }

    public static final class ProbeResult {
        public final boolean writable;
        public final boolean permissionDenied;
        public final boolean readOnlyFileSystem;
        public final boolean outOfSpace;
        public final boolean missingAllFilesAccess;
        public final String targetPath;
        public final String errorDetail;

        public ProbeResult(
                boolean writable,
                boolean permissionDenied,
                boolean readOnlyFileSystem,
                boolean outOfSpace,
                boolean missingAllFilesAccess,
                String targetPath,
                String errorDetail) {
            this.writable = writable;
            this.permissionDenied = permissionDenied;
            this.readOnlyFileSystem = readOnlyFileSystem;
            this.outOfSpace = outOfSpace;
            this.missingAllFilesAccess = missingAllFilesAccess;
            this.targetPath = targetPath == null ? "" : targetPath;
            this.errorDetail = errorDetail == null ? "" : errorDetail;
        }

        public boolean needsAllFilesPermission() {
            return !writable && missingAllFilesAccess && (permissionDenied || (!readOnlyFileSystem && !outOfSpace));
        }
    }

    public static boolean isMissingAllFilesAccessOnAndroid11Plus(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        return !Environment.isExternalStorageManager();
    }

    public static ProbeResult probeRecordingTarget(Context context) {
        if (context == null) {
            return new ProbeResult(false, false, false, false, false, "", "Context is null");
        }
        File recordingDir = StorageHelper.getRecordingDir(context);
        boolean appPrivate = isAppPrivateDirectory(context, recordingDir);
        boolean missingAllFiles = !appPrivate && isMissingAllFilesAccessOnAndroid11Plus(context);
        return probeDirectoryWritable(recordingDir, missingAllFiles);
    }

    public static ProbeResult probeDirectoryWritable(File dir) {
        return probeDirectoryWritable(dir, false);
    }

    public static ProbeResult probeDirectoryWritable(File dir, boolean missingAllFilesAccess) {
        if (dir == null) {
            return new ProbeResult(false, false, false, false, missingAllFilesAccess, "", "Recording directory is null");
        }
        String targetPath = dir.getAbsolutePath();
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            File ancestor = findExistingAncestor(dir);
            boolean ancestorUnwritable = ancestor != null && ancestor.isDirectory() && !ancestor.canWrite();
            boolean permDenied = missingAllFilesAccess || ancestorUnwritable;
            return new ProbeResult(
                    false,
                    permDenied,
                    false,
                    false,
                    missingAllFilesAccess,
                    targetPath,
                    "Cannot create recording directory: " + targetPath);
        }
        if (!dir.isDirectory()) {
            return new ProbeResult(
                    false,
                    false,
                    false,
                    false,
                    missingAllFilesAccess,
                    targetPath,
                    "Recording target is not a directory: " + targetPath);
        }

        File probeFile = new File(dir, ".evcam_write_probe_" + PROBE_COUNTER.incrementAndGet() + ".mp4.pending");
        try {
            try (RandomAccessFile raf = new RandomAccessFile(probeFile, "rw")) {
                raf.write(0);
            }
            return new ProbeResult(true, false, false, false, missingAllFilesAccess, targetPath, "");
        } catch (IOException | SecurityException e) {
            String detail = describeThrowable(e);
            boolean readOnly = isReadOnlyMessage(detail);
            boolean outOfSpace = isOutOfSpaceMessage(detail);
            boolean permDenied = isPermissionDeniedError(e) || (missingAllFilesAccess && !readOnly && !outOfSpace);
            return new ProbeResult(false, permDenied, readOnly, outOfSpace, missingAllFilesAccess, targetPath, detail);
        } finally {
            if (probeFile.exists() && !probeFile.delete()) {
                AppLog.w(TAG, "Failed to delete storage probe file: " + probeFile.getAbsolutePath());
            }
        }
    }

    public static boolean isFatalWriteError(Throwable error) {
        return isPermissionDeniedError(error) || isReadOnlyError(error) || isOutOfSpaceError(error);
    }

    public static boolean isFatalWriteErrorMessage(String message) {
        return isPermissionDeniedMessage(message) || isReadOnlyMessage(message) || isOutOfSpaceMessage(message);
    }

    public static boolean isPermissionDeniedError(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cursor = error;
        while (cursor != null && visited.add(cursor)) {
            if (cursor instanceof SecurityException || isPermissionDeniedMessage(cursor.getMessage())) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    public static boolean isPermissionDeniedMessage(String message) {
        return containsAnyToken(message,
                "eperm",
                "eacces",
                "operation not permitted",
                "permission denied",
                "缺少「所有文件访问权限」");
    }

    public static boolean isReadOnlyError(Throwable error) {
        return matchesAnyTokenInChain(error, "erofs", "read-only file system");
    }

    public static boolean isReadOnlyMessage(String message) {
        return containsAnyToken(message, "erofs", "read-only file system", "只读");
    }

    public static boolean isOutOfSpaceError(Throwable error) {
        return matchesAnyTokenInChain(error, "enospc", "no space left on device");
    }

    public static boolean isOutOfSpaceMessage(String message) {
        return containsAnyToken(message, "enospc", "no space left on device", "空间不足");
    }

    public static String formatUserStartupFailureMessage(String errorDetail, boolean missingAllFilesAccess) {
        if (isPermissionDeniedMessage(errorDetail)
                || (missingAllFilesAccess && !isReadOnlyMessage(errorDetail) && !isOutOfSpaceMessage(errorDetail))) {
            return "无法写入录像目录：缺少「所有文件访问权限」，请在权限设置中授权后重试";
        }
        if (isReadOnlyMessage(errorDetail)) {
            return "无法写入录像目录：存储介质处于只读状态，请检查 U 盘或存储路径";
        }
        if (isOutOfSpaceMessage(errorDetail)) {
            return "无法写入录像目录：存储空间不足，请清理后重试";
        }
        if (errorDetail != null && !errorDetail.trim().isEmpty()) {
            return "录像启动失败：" + errorDetail.trim();
        }
        return "录像启动失败，请在设置中保存日志";
    }

    public static String describeThrowable(Throwable error) {
        if (error == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cursor = error;
        while (cursor != null && visited.add(cursor)) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(cursor.getClass().getSimpleName());
            if (cursor.getMessage() != null && !cursor.getMessage().isEmpty()) {
                sb.append(": ").append(cursor.getMessage());
            }
            cursor = cursor.getCause();
        }
        return sb.toString();
    }

    private static boolean matchesAnyTokenInChain(Throwable error, String... tokens) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cursor = error;
        while (cursor != null && visited.add(cursor)) {
            if (containsAnyToken(cursor.getMessage(), tokens)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static boolean containsAnyToken(String message, String... tokens) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static File findExistingAncestor(File dir) {
        File cursor = dir.getParentFile();
        while (cursor != null && !cursor.exists()) {
            cursor = cursor.getParentFile();
        }
        return cursor;
    }

    private static boolean isAppPrivateDirectory(Context context, File target) {
        if (context == null || target == null) {
            return false;
        }
        return isUnderDirectory(target, context.getCacheDir())
                || isUnderDirectory(target, context.getFilesDir())
                || isUnderDirectory(target, context.getExternalCacheDir())
                || isUnderDirectory(target, context.getExternalFilesDir(null));
    }

    private static boolean isUnderDirectory(File target, File root) {
        if (target == null || root == null) {
            return false;
        }
        String targetPath = normalizePath(target);
        String rootPath = normalizePath(root);
        return targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator);
    }

    private static String normalizePath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }
}
