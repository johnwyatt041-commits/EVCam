package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局应用日志与会话持久化门面。
 *
 * 通过进程级幂等初始化（EVCamApplication.onCreate）与 SessionLogStore 增量落盘，
 * 保障纯后台启动、UI 重建、设置查看及崩溃场景下的日志连贯性。
 */
public final class AppLog {
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_DEBUG_TO_INFO = "debug_to_info";
    private static final int MAX_LINE_LENGTH = 4096;
    private static final long FLUSH_TIMEOUT_MS = 1000L;

    private static final Object FORMAT_LOCK = new Object();
    // Only accessed while holding FORMAT_LOCK: SimpleDateFormat is not thread safe.
    private static final SimpleDateFormat BUFFER_DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static final SessionLogStore STORE = new SessionLogStore();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean CRASH_HANDLER_INSTALLED = new AtomicBoolean(false);

    private static volatile boolean debugToInfo = false;
    private static Context sAppContext = null;
    private static Thread.UncaughtExceptionHandler sDefaultHandler = null;

    private AppLog() {
    }

    /**
     * 进程级幂等初始化：仅在首次调用时轮换上次会话日志并安装崩溃处理器。
     * 建议在 EVCamApplication.onCreate() 调用，重复调用不会覆写 previous_session.log。
     */
    public static void init(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        sAppContext = appContext;

        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        debugToInfo = prefs.getBoolean(KEY_DEBUG_TO_INFO, false);

        String header = buildSessionHeader(appContext);
        STORE.initializeOnce(getLogDirectory(appContext), header);
        setupCrashHandler();
    }

    private static String buildSessionHeader(Context context) {
        String versionName = "unknown";
        int versionCode = 0;
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            versionName = packageInfo.versionName;
            versionCode = (int) packageInfo.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        String flavor = BuildConfig.SMARTCAM_COMPOSITE ? "composite(B)" : "independent(A)";
        return String.format(Locale.US,
                "=== EVCam 会话开始 | %s | PID=%d | 版本=%s (%d) | %s | DebugToInfo=%s ===",
                formatNow(), Process.myPid(), versionName, versionCode, flavor, debugToInfo);
    }

    private static void setupCrashHandler() {
        if (!CRASH_HANDLER_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        sDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String crashInfo = "!!! APPLICATION CRASH !!!\n"
                        + "Thread: " + thread.getName() + "\n"
                        + "Exception: " + throwable.getClass().getName() + "\n"
                        + "Message: " + throwable.getMessage() + "\n"
                        + "Stack trace:\n" + Log.getStackTraceString(throwable);
                STORE.recordCrashAndFlush(formatNow(), crashInfo, 1500L);
                Log.e("AppLog", "Crash log flushed to session store");
            } catch (Exception e) {
                Log.e("AppLog", "Failed to save crash log", e);
            }
            if (sDefaultHandler != null) {
                sDefaultHandler.uncaughtException(thread, throwable);
            }
        });
        Log.i("AppLog", "Crash handler installed cleanly (once per process)");
    }

    public static File getLogDirectory(Context context) {
        File logDir = new File(context.getFilesDir(), "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        return logDir;
    }

    /**
     * 只读读取 Debug 转 INFO 开关；绝不触发会话轮换或重复注册崩溃钩子。
     */
    public static boolean isDebugToInfoEnabled(Context context) {
        if (!INITIALIZED.get() && context != null) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            debugToInfo = prefs.getBoolean(KEY_DEBUG_TO_INFO, false);
        }
        return debugToInfo;
    }

    public static void setDebugToInfoEnabled(Context context, boolean enabled) {
        debugToInfo = enabled;
        if (context != null) {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_DEBUG_TO_INFO, enabled).apply();
        }
        i("AppLog", "Debug 日志转 INFO 已" + (enabled ? "开启" : "关闭"));
        STORE.flushAsync();
    }

    /**
     * 异步触发落盘（适合 onStop 等 UI 切换时机，不阻塞主线程）。
     */
    public static void flushAsync() {
        STORE.flushAsync();
    }

    /**
     * 有界同步落盘当前会话日志（适合 onDestroy / exitApp / Service.onDestroy）。
     */
    public static void saveToPersistentLog(Context context) {
        if (context == null) {
            return;
        }
        init(context);
        STORE.flushBlocking(FLUSH_TIMEOUT_MS);
    }

    public static boolean hasPreviousSessionLogs(Context context) {
        if (context == null) {
            return false;
        }
        return STORE.hasPreviousSessionLogs(getLogDirectory(context));
    }

    public static String getPreviousSessionLogInfo(Context context) {
        if (context == null) {
            return null;
        }
        SessionLogStore.SessionSummary summary = STORE.getPreviousSessionSummary(getLogDirectory(context));
        return summary.exists ? summary.formatDisplay() : null;
    }

    public static SessionLogStore.SessionSummary getCurrentSessionSummary(Context context) {
        if (context == null) {
            return new SessionLogStore.SessionSummary(false, 0, 0L, null, null, false);
        }
        return STORE.getCurrentSessionSummary(getLogDirectory(context));
    }

    public static SessionLogStore.SessionSummary getPreviousSessionSummary(Context context) {
        if (context == null) {
            return new SessionLogStore.SessionSummary(false, 0, 0L, null, null, false);
        }
        return STORE.getPreviousSessionSummary(getLogDirectory(context));
    }

    public static List<String> getPreviousSessionLogs(Context context) {
        if (context == null) {
            return new ArrayList<>();
        }
        STORE.initializeOnce(getLogDirectory(context), buildSessionHeader(context));
        return STORE.readPreviousSessionLines();
    }

    public static boolean clearPreviousSessionLogs(Context context) {
        if (context == null) {
            return false;
        }
        return STORE.clearPreviousSessionLogs(getLogDirectory(context));
    }

    /**
     * 运行日志连贯性自检：写入探针记录、同步刷盘并验证磁盘与内存尾部一致性。
     */
    public static SessionLogStore.CoherenceReport runCoherenceCheck(Context context) {
        if (context == null) {
            return new SessionLogStore.CoherenceReport(false, false, false, false, false, false, 0, 0,
                    "上下文无效");
        }
        init(context);
        String marker = "COHERENCE_PROBE_" + System.currentTimeMillis();
        i("AppLog", "执行日志连贯性自检 marker=" + marker);
        return STORE.verifyCoherence(getLogDirectory(context), marker);
    }

    /**
     * 导出完整本次会话日志到公共 Download/EVCam_Log/ 目录。
     */
    public static File saveLogsToFile(Context context) {
        return exportSessionToDownload(context, false);
    }

    /**
     * 导出完整上次会话日志到公共 Download/EVCam_Log/ 目录。
     */
    public static File savePreviousLogsToFile(Context context) {
        return exportSessionToDownload(context, true);
    }

    private static File exportSessionToDownload(Context context, boolean previousSession) {
        if (context == null) {
            return null;
        }
        init(context);
        List<String> lines = previousSession
                ? STORE.readPreviousSessionLines()
                : STORE.readCurrentSessionLines();
        if (lines.isEmpty()) {
            return null;
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String prefix = previousSession ? "evcam_prev_log_" : "evcam_log_";
        File logDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "EVCam_Log");
        File logFile = new File(logDir, prefix + timestamp + ".txt");

        List<String> exportLines = new ArrayList<>(lines.size() + 6);
        exportLines.add("=== EVCam 本地日志导出 (" + (previousSession ? "上次运行" : "本次运行") + ") ===");
        exportLines.add("设备型号: " + Build.MODEL + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        exportLines.add("导出时间: " + formatNow() + " | 条数: " + lines.size());
        exportLines.add("========================================");
        exportLines.addAll(lines);
        return writeLogToFile(logFile, exportLines) ? logFile : null;
    }

    private static boolean writeLogToFile(File logFile, List<String> lines) {
        File parent = logFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
            return true;
        } catch (IOException e) {
            Log.w("AppLog", "Cannot write to " + logFile.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    public static void d(String tag, String message) {
        logInternal(Log.DEBUG, tag, message, null);
    }

    public static void d(String tag, String message, Throwable tr) {
        logInternal(Log.DEBUG, tag, message, tr);
    }

    public static void i(String tag, String message) {
        logInternal(Log.INFO, tag, message, null);
    }

    public static void i(String tag, String message, Throwable tr) {
        logInternal(Log.INFO, tag, message, tr);
    }

    public static void w(String tag, String message) {
        logInternal(Log.WARN, tag, message, null);
    }

    public static void w(String tag, String message, Throwable tr) {
        logInternal(Log.WARN, tag, message, tr);
    }

    public static void e(String tag, String message) {
        logInternal(Log.ERROR, tag, message, null);
    }

    public static void e(String tag, String message, Throwable tr) {
        logInternal(Log.ERROR, tag, message, tr);
    }

    private static void logInternal(int level, String tag, String message, Throwable tr) {
        if (level == Log.DEBUG && !debugToInfo) {
            return;
        }
        String safeTag = tag == null ? "AppLog" : tag;
        String safeMessage = message == null ? "" : message;
        if (tr != null) {
            safeMessage = safeMessage + "\n" + Log.getStackTraceString(tr);
        }
        int outputLevel = (level == Log.DEBUG && debugToInfo) ? Log.INFO : level;
        Log.println(outputLevel, safeTag, safeMessage);

        String line = formatNow() + " " + levelToLabel(outputLevel) + "/" + safeTag + ": " + safeMessage;
        if (line.length() > MAX_LINE_LENGTH) {
            line = line.substring(0, MAX_LINE_LENGTH) + "...";
        }
        STORE.appendLine(line);
    }

    private static String formatNow() {
        synchronized (FORMAT_LOCK) {
            return BUFFER_DATE_FORMAT.format(new Date());
        }
    }

    private static String levelToLabel(int level) {
        switch (level) {
            case Log.ERROR:
                return "E";
            case Log.WARN:
                return "W";
            case Log.INFO:
                return "I";
            case Log.DEBUG:
                return "D";
            default:
                return String.valueOf(level);
        }
    }
}
