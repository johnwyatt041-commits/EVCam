package com.kooo.evcam.configplaza;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.AtomicFile;

import com.kooo.evcam.AppLog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 基于 AtomicFile 的配置广场应用前备份存储（files/config-plaza/last-backup.json）。
 */
public final class AndroidE5ConfigBackupStore implements E5ConfigApplier.BackupStore {
    private static final String TAG = "E5ConfigBackupStore";

    private final File backupFile;

    public AndroidE5ConfigBackupStore(@NonNull Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "config-plaza");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        this.backupFile = new File(dir, "last-backup.json");
    }

    @Override
    public boolean saveBackup(@NonNull E5ConfigApplier.BackupRecord record) {
        File parent = backupFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            AppLog.w(TAG, "创建备份目录失败: " + parent.getAbsolutePath());
            return false;
        }
        AtomicFile atomic = new AtomicFile(backupFile);
        FileOutputStream fos = null;
        try {
            fos = atomic.startWrite();
            fos.write(record.toJsonString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
            atomic.finishWrite(fos);
            return true;
        } catch (Exception e) {
            AppLog.e(TAG, "写入应用前原子备份失败", e);
            if (fos != null) {
                atomic.failWrite(fos);
            }
            return false;
        }
    }

    @Nullable
    @Override
    public E5ConfigApplier.BackupRecord loadBackup() {
        if (!backupFile.exists()) {
            return null;
        }
        AtomicFile atomic = new AtomicFile(backupFile);
        try {
            byte[] bytes = atomic.readFully();
            return E5ConfigApplier.BackupRecord.fromJsonString(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.e(TAG, "读取配置备份失败", e);
            return null;
        }
    }

    @Override
    public boolean hasBackup() {
        return loadBackup() != null;
    }
}
