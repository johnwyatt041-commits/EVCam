package com.kooo.evcam;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 进程级会话日志存储与一致性核心（纯 Java，可用于 JVM 单测）。
 *
 * 解决旧实现仅在 onDestroy / 崩溃时用内存 1000 行环形缓冲覆写 current_session.log、
 * 以及重复 init() 意外轮换冲掉 previous_session.log 的断链问题。
 */
public final class SessionLogStore {

    public static final String CURRENT_SESSION_LOG = "current_session.log";
    public static final String PREVIOUS_SESSION_LOG = "previous_session.log";
    public static final int DEFAULT_MAX_BUFFER_LINES = 1000;
    public static final int DEFAULT_FLUSH_BATCH_LINES = 24;
    public static final long DEFAULT_FLUSH_DELAY_MS = 4000L;
    public static final long DEFAULT_MAX_SESSION_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_CRASH_STACK_LINES = 120;

    public static final class SessionSummary {
        public final boolean exists;
        public final int lineCount;
        public final long byteLength;
        public final String startTime;
        public final String endTime;
        public final boolean hasCrashMarker;

        public SessionSummary(boolean exists, int lineCount, long byteLength,
                              String startTime, String endTime, boolean hasCrashMarker) {
            this.exists = exists;
            this.lineCount = lineCount;
            this.byteLength = byteLength;
            this.startTime = startTime;
            this.endTime = endTime;
            this.hasCrashMarker = hasCrashMarker;
        }

        public String formatDisplay() {
            if (!exists || lineCount == 0) {
                return "暂无日志";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(lineCount).append(" 条");
            if (startTime != null && endTime != null) {
                sb.append(" (").append(startTime).append(" ~ ").append(endTime).append(")");
            }
            sb.append(" · ").append(formatByteSize(byteLength));
            if (hasCrashMarker) {
                sb.append(" · 含崩溃记录");
            }
            return sb.toString();
        }
    }

    public static final class CoherenceReport {
        public final boolean passed;
        public final boolean sessionInitialized;
        public final boolean logDirWritable;
        public final boolean currentFilePresent;
        public final boolean markerPersisted;
        public final boolean tailConsistent;
        public final int memoryLines;
        public final int diskLines;
        public final String detail;

        public CoherenceReport(boolean passed, boolean sessionInitialized, boolean logDirWritable,
                               boolean currentFilePresent, boolean markerPersisted,
                               boolean tailConsistent, int memoryLines, int diskLines, String detail) {
            this.passed = passed;
            this.sessionInitialized = sessionInitialized;
            this.logDirWritable = logDirWritable;
            this.currentFilePresent = currentFilePresent;
            this.markerPersisted = markerPersisted;
            this.tailConsistent = tailConsistent;
            this.memoryLines = memoryLines;
            this.diskLines = diskLines;
            this.detail = detail;
        }
    }

    private final int maxBufferLines;
    private final int flushBatchLines;
    private final long flushDelayMs;
    private final long maxSessionBytes;

    private final Object lock = new Object();
    private final ArrayDeque<String> memoryBuffer = new ArrayDeque<>();
    private final List<String> pendingDiskLines = new ArrayList<>();
    private final ScheduledExecutorService ioExecutor;

    private File logDir;
    private File currentSessionFile;
    private File previousSessionFile;
    private String sessionHeaderLine;
    private boolean initialized = false;
    private ScheduledFuture<?> delayedFlushFuture;

    public SessionLogStore() {
        this(DEFAULT_MAX_BUFFER_LINES, DEFAULT_FLUSH_BATCH_LINES,
                DEFAULT_FLUSH_DELAY_MS, DEFAULT_MAX_SESSION_BYTES);
    }

    public SessionLogStore(int maxBufferLines, int flushBatchLines,
                           long flushDelayMs, long maxSessionBytes) {
        this.maxBufferLines = Math.max(50, maxBufferLines);
        this.flushBatchLines = Math.max(1, flushBatchLines);
        this.flushDelayMs = Math.max(0L, flushDelayMs);
        this.maxSessionBytes = Math.max(8192L, maxSessionBytes);
        this.ioExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EVCam-LogWriter");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 进程生命周期内仅执行一次会话轮换与文件头创建，重复调用保持幂等。
     */
    public boolean initializeOnce(File targetLogDir, String headerLine) {
        if (targetLogDir == null) {
            return false;
        }
        List<String> earlyLines = null;
        synchronized (lock) {
            if (initialized) {
                return false;
            }
            if (!targetLogDir.exists() && !targetLogDir.mkdirs()) {
                return false;
            }
            this.logDir = targetLogDir;
            this.currentSessionFile = new File(targetLogDir, CURRENT_SESSION_LOG);
            this.previousSessionFile = new File(targetLogDir, PREVIOUS_SESSION_LOG);
            this.sessionHeaderLine = headerLine;
            rotateLocked(this.currentSessionFile, this.previousSessionFile);
            startNewSessionFileLocked(this.currentSessionFile, headerLine);
            if (!memoryBuffer.isEmpty()) {
                earlyLines = new ArrayList<>(memoryBuffer);
                pendingDiskLines.clear();
            }
            this.initialized = true;
        }
        if (earlyLines != null && !earlyLines.isEmpty()) {
            writeLinesSynchronously(earlyLines);
        }
        return true;
    }

    public boolean isInitialized() {
        synchronized (lock) {
            return initialized;
        }
    }

    public void appendLine(String formattedLine) {
        if (formattedLine == null) {
            return;
        }
        boolean flushNow = false;
        boolean scheduleLater = false;
        synchronized (lock) {
            addMemoryLineLocked(formattedLine);
            if (initialized && currentSessionFile != null) {
                pendingDiskLines.add(formattedLine);
                if (pendingDiskLines.size() >= flushBatchLines) {
                    flushNow = true;
                    cancelDelayedFlushLocked();
                } else if (delayedFlushFuture == null) {
                    scheduleLater = true;
                }
            }
        }
        if (flushNow) {
            flushAsync();
        } else if (scheduleLater) {
            scheduleDelayedFlush();
        }
    }

    public void recordCrashAndFlush(String timestampPrefix, String crashText, long timeoutMs) {
        if (crashText == null || crashText.isEmpty()) {
            flushBlocking(timeoutMs);
            return;
        }
        String[] rawLines = crashText.split("\\r?\\n");
        int count = Math.min(rawLines.length, MAX_CRASH_STACK_LINES);
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                String line = (timestampPrefix != null ? timestampPrefix + " E/CRASH: " : "") + rawLines[i];
                addMemoryLineLocked(line);
                if (initialized && currentSessionFile != null) {
                    pendingDiskLines.add(line);
                }
            }
            cancelDelayedFlushLocked();
        }
        flushBlocking(timeoutMs);
    }

    public void flushAsync() {
        synchronized (lock) {
            if (!initialized || currentSessionFile == null || pendingDiskLines.isEmpty()) {
                return;
            }
        }
        try {
            ioExecutor.execute(this::drainAndWritePending);
        } catch (Exception ignored) {
        }
    }

    public boolean flushBlocking(long timeoutMs) {
        synchronized (lock) {
            if (!initialized || currentSessionFile == null) {
                return false;
            }
            cancelDelayedFlushLocked();
        }
        try {
            Future<?> future = ioExecutor.submit(this::drainAndWritePending);
            future.get(Math.max(100L, timeoutMs), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            return false;
        }
    }

    public List<String> getMemorySnapshot() {
        synchronized (lock) {
            return new ArrayList<>(memoryBuffer);
        }
    }

    public List<String> readCurrentSessionLines() {
        flushBlocking(1000L);
        File file;
        synchronized (lock) {
            file = currentSessionFile;
        }
        if (file != null && file.exists()) {
            List<String> diskLines = readLinesFromFile(file);
            if (!diskLines.isEmpty()) {
                return diskLines;
            }
        }
        return getMemorySnapshot();
    }

    public List<String> readPreviousSessionLines() {
        File file;
        synchronized (lock) {
            file = previousSessionFile;
        }
        if (file == null || !file.exists()) {
            return new ArrayList<>();
        }
        return readLinesFromFile(file);
    }

    public boolean hasPreviousSessionLogs(File targetLogDir) {
        File prev = resolvePreviousFile(targetLogDir);
        return prev != null && prev.exists() && prev.length() > 0L;
    }

    public SessionSummary getCurrentSessionSummary(File targetLogDir) {
        flushBlocking(600L);
        File cur = resolveCurrentFile(targetLogDir);
        return summarizeFile(cur);
    }

    public SessionSummary getPreviousSessionSummary(File targetLogDir) {
        File prev = resolvePreviousFile(targetLogDir);
        return summarizeFile(prev);
    }

    public boolean clearPreviousSessionLogs(File targetLogDir) {
        File prev = resolvePreviousFile(targetLogDir);
        if (prev == null || !prev.exists()) {
            return true;
        }
        return prev.delete();
    }

    /**
     * 运行连贯性自检：校验进程初始化、目录可写、磁盘刷盘衔接及内存/文件尾部一致。
     */
    public CoherenceReport verifyCoherence(File targetLogDir, String probeMarker) {
        boolean initOk = isInitialized();
        if (!initOk && targetLogDir != null) {
            initOk = initializeOnce(targetLogDir, "=== EVCam Late Init ===");
        }
        File dir = targetLogDir != null ? targetLogDir : logDir;
        boolean dirWritable = dir != null && (dir.exists() || dir.mkdirs()) && dir.canWrite();
        boolean flushed = flushBlocking(1500L);

        File cur = resolveCurrentFile(dir);
        boolean fileExists = cur != null && cur.exists() && cur.length() > 0L;
        List<String> diskLines = fileExists ? readLinesFromFile(cur) : new ArrayList<>();
        List<String> memLines = getMemorySnapshot();

        boolean markerPersisted = probeMarker == null || probeMarker.isEmpty();
        if (!markerPersisted) {
            for (int i = diskLines.size() - 1; i >= 0; i--) {
                if (diskLines.get(i).contains(probeMarker)) {
                    markerPersisted = true;
                    break;
                }
            }
        }

        boolean tailConsistent = true;
        if (!memLines.isEmpty() && !diskLines.isEmpty()) {
            String lastMem = memLines.get(memLines.size() - 1);
            tailConsistent = containsLine(diskLines, lastMem);
        } else if (!memLines.isEmpty()) {
            tailConsistent = false;
        }

        boolean passed = initOk && dirWritable && flushed && fileExists && markerPersisted && tailConsistent;
        String detail;
        if (passed) {
            detail = "连贯性校验通过：内存缓冲 " + memLines.size() + " 条，当前会话磁盘 "
                    + diskLines.size() + " 条，尾部记录完全一致。";
        } else {
            detail = "校验异常：init=" + initOk
                    + ", writable=" + dirWritable
                    + ", flushed=" + flushed
                    + ", fileExists=" + fileExists
                    + ", markerPersisted=" + markerPersisted
                    + ", tailConsistent=" + tailConsistent;
        }
        return new CoherenceReport(passed, initOk, dirWritable, fileExists, markerPersisted,
                tailConsistent, memLines.size(), diskLines.size(), detail);
    }

    private void addMemoryLineLocked(String line) {
        if (memoryBuffer.size() >= maxBufferLines) {
            memoryBuffer.removeFirst();
        }
        memoryBuffer.addLast(line);
    }

    private void scheduleDelayedFlush() {
        if (flushDelayMs <= 0L) {
            flushAsync();
            return;
        }
        synchronized (lock) {
            if (!initialized || delayedFlushFuture != null) {
                return;
            }
            try {
                delayedFlushFuture = ioExecutor.schedule(() -> {
                    synchronized (lock) {
                        delayedFlushFuture = null;
                    }
                    drainAndWritePending();
                }, flushDelayMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    private void cancelDelayedFlushLocked() {
        if (delayedFlushFuture != null) {
            delayedFlushFuture.cancel(false);
            delayedFlushFuture = null;
        }
    }

    private void drainAndWritePending() {
        List<String> batch;
        File file;
        synchronized (lock) {
            if (pendingDiskLines.isEmpty() || currentSessionFile == null) {
                return;
            }
            batch = new ArrayList<>(pendingDiskLines);
            pendingDiskLines.clear();
            file = currentSessionFile;
        }
        writeLinesToFile(file, batch);
    }

    private void writeLinesSynchronously(List<String> lines) {
        File file;
        synchronized (lock) {
            file = currentSessionFile;
        }
        if (file != null && lines != null && !lines.isEmpty()) {
            writeLinesToFile(file, lines);
        }
    }

    private void writeLinesToFile(File file, List<String> lines) {
        if (file == null || lines == null || lines.isEmpty()) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (file.exists() && file.length() > maxSessionBytes) {
                trimSessionFileKeepingTail(file);
            }
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
                writer.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private void trimSessionFileKeepingTail(File file) {
        List<String> allLines = readLinesFromFile(file);
        if (allLines.isEmpty()) {
            return;
        }
        int keepCount = Math.max(100, allLines.size() * 6 / 10);
        int startIdx = Math.max(0, allLines.size() - keepCount);
        File tmpFile = new File(file.getParentFile(), file.getName() + ".trim.tmp");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmpFile, false), StandardCharsets.UTF_8))) {
            if (sessionHeaderLine != null && !sessionHeaderLine.isEmpty()) {
                writer.write(sessionHeaderLine);
                writer.newLine();
            }
            writer.write("... [会话日志超过上限，已自动裁剪早期记录，保留最新尾部] ...");
            writer.newLine();
            for (int i = startIdx; i < allLines.size(); i++) {
                writer.write(allLines.get(i));
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
            return;
        }
        if (!replaceFile(tmpFile, file) && tmpFile.exists()) {
            tmpFile.delete();
        }
    }

    private static void rotateLocked(File current, File previous) {
        if (current == null || previous == null || !current.exists() || current.length() == 0L) {
            return;
        }
        if (previous.exists()) {
            previous.delete();
        }
        if (!current.renameTo(previous)) {
            if (copyFile(current, previous)) {
                current.delete();
            }
        }
    }

    private static void startNewSessionFileLocked(File current, String headerLine) {
        if (current == null) {
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(current, false), StandardCharsets.UTF_8))) {
            if (headerLine != null && !headerLine.isEmpty()) {
                writer.write(headerLine);
                writer.newLine();
            }
            writer.flush();
        } catch (IOException ignored) {
        }
    }

    private File resolveCurrentFile(File targetLogDir) {
        synchronized (lock) {
            if (currentSessionFile != null) {
                return currentSessionFile;
            }
        }
        return targetLogDir == null ? null : new File(targetLogDir, CURRENT_SESSION_LOG);
    }

    private File resolvePreviousFile(File targetLogDir) {
        synchronized (lock) {
            if (previousSessionFile != null) {
                return previousSessionFile;
            }
        }
        return targetLogDir == null ? null : new File(targetLogDir, PREVIOUS_SESSION_LOG);
    }

    private static SessionSummary summarizeFile(File file) {
        if (file == null || !file.exists() || file.length() == 0L) {
            return new SessionSummary(false, 0, 0L, null, null, false);
        }
        int lineCount = 0;
        String firstTimedLine = null;
        String lastTimedLine = null;
        boolean hasCrash = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                if (line.contains("!!! APPLICATION CRASH !!!")) {
                    hasCrash = true;
                }
                if (extractTimeFromLogLine(line) != null) {
                    if (firstTimedLine == null) {
                        firstTimedLine = line;
                    }
                    lastTimedLine = line;
                }
            }
        } catch (IOException e) {
            return new SessionSummary(false, 0, 0L, null, null, false);
        }
        return new SessionSummary(true, lineCount, file.length(),
                extractTimeFromLogLine(firstTimedLine),
                extractTimeFromLogLine(lastTimedLine),
                hasCrash);
    }

    private static List<String> readLinesFromFile(File file) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException ignored) {
        }
        return lines;
    }

    private static boolean containsLine(List<String> lines, String target) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (target.equals(lines.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean replaceFile(File src, File dst) {
        if (dst.exists() && !dst.delete()) {
            return copyFile(src, dst) && src.delete();
        }
        if (src.renameTo(dst)) {
            return true;
        }
        return copyFile(src, dst) && src.delete();
    }

    private static boolean copyFile(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst, false)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String extractTimeFromLogLine(String line) {
        if (line == null || line.length() < 19) {
            return null;
        }
        if (line.charAt(4) != '-' || line.charAt(7) != '-' || line.charAt(10) != ' '
                || line.charAt(13) != ':' || line.charAt(16) != ':') {
            return null;
        }
        return line.substring(11, 19);
    }

    private static String formatByteSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
