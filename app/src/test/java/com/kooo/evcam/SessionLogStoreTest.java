package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SessionLogStoreTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void idempotentInitPreventsDoubleRotationWithinSameProcess() throws Exception {
        File logDir = tempFolder.newFolder("logs");
        File oldCurrent = new File(logDir, SessionLogStore.CURRENT_SESSION_LOG);
        try (FileOutputStream fos = new FileOutputStream(oldCurrent)) {
            fos.write("2026-11-17 00:10:00.123 I/Test: previous-run-line-1\n".getBytes(StandardCharsets.UTF_8));
        }

        SessionLogStore store = new SessionLogStore(100, 10, 0L, 64 * 1024L);
        assertTrue(store.initializeOnce(logDir, "=== Session 2 Start ==="));
        store.appendLine("2026-11-17 01:00:00.000 I/Test: current-run-line-1");
        assertTrue(store.flushBlocking(1000L));

        // 模拟 Activity 重建或打开设置页再次调用 initializeOnce：不得二次轮换冲掉 previous_session.log
        assertFalse(store.initializeOnce(logDir, "=== Duplicate Init Should Be Ignored ==="));

        List<String> prev = store.readPreviousSessionLines();
        assertEquals(1, prev.size());
        assertTrue(prev.get(0).contains("previous-run-line-1"));

        List<String> cur = store.readCurrentSessionLines();
        assertTrue(cur.stream().anyMatch(line -> line.contains("current-run-line-1")));
        assertFalse(cur.stream().anyMatch(line -> line.contains("Duplicate Init")));
    }

    @Test
    public void batchFlushCrashCaptureAndCoherenceVerificationAreConsistent() throws Exception {
        File logDir = tempFolder.newFolder("logs_coherence");
        SessionLogStore store = new SessionLogStore(50, 8, 50L, 128 * 1024L);
        assertTrue(store.initializeOnce(logDir, "=== Session Coherence Header ==="));

        for (int i = 0; i < 15; i++) {
            store.appendLine(String.format("2026-11-17 01:10:%02d.000 I/Coherence: line-%d", i, i));
        }
        String probeMarker = "PROBE_OK_991";
        store.appendLine("2026-11-17 01:10:20.000 I/Coherence: " + probeMarker);

        SessionLogStore.CoherenceReport report = store.verifyCoherence(logDir, probeMarker);
        assertTrue(report.passed);
        assertTrue(report.sessionInitialized);
        assertTrue(report.logDirWritable);
        assertTrue(report.currentFilePresent);
        assertTrue(report.markerPersisted);
        assertTrue(report.tailConsistent);

        // 模拟异常崩溃写入并立即跨进程重启轮换
        store.recordCrashAndFlush("2026-11-17 01:11:00.000", "!!! APPLICATION CRASH !!!\njava.lang.IllegalStateException: boom", 1000L);
        assertTrue(store.getCurrentSessionSummary(logDir).hasCrashMarker);

        SessionLogStore nextProcessStore = new SessionLogStore(50, 8, 50L, 128 * 1024L);
        assertTrue(nextProcessStore.initializeOnce(logDir, "=== Next Process Session ==="));
        SessionLogStore.SessionSummary prevSummary = nextProcessStore.getPreviousSessionSummary(logDir);
        assertTrue(prevSummary.exists);
        assertTrue(prevSummary.hasCrashMarker);
        assertTrue(nextProcessStore.readPreviousSessionLines().stream().anyMatch(l -> l.contains("IllegalStateException")));
    }

    @Test
    public void oversizedSessionFileTrimsSafelyRetainingLatestTail() throws Exception {
        File logDir = tempFolder.newFolder("logs_trim");
        SessionLogStore store = new SessionLogStore(200, 10, 0L, 8192L);
        assertTrue(store.initializeOnce(logDir, "=== Session Header ==="));

        for (int i = 0; i < 160; i++) {
            store.appendLine(String.format("2026-11-17 01:12:00.%03d I/Trim: payload_%03d_0123456789abcdefghijklmnopqrstuvwxyz0123456789", i, i));
        }
        assertTrue(store.flushBlocking(2000L));

        List<String> lines = store.readCurrentSessionLines();
        assertTrue(lines.stream().anyMatch(l -> l.contains("payload_159")));
        assertTrue(store.verifyCoherence(logDir, "payload_159").passed);
    }

    @Test
    public void isDebugToInfoEnabledIsStrictlyReadOnlyWithoutTouchingFilesDir() {
        Context context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)).thenReturn(prefs);
        when(prefs.getBoolean("debug_to_info", false)).thenReturn(true);

        assertTrue(AppLog.isDebugToInfoEnabled(context));
        // 读取开关绝不能再触发 AppLog.init -> getFilesDir / rotateSessionLogs
        verify(context, never()).getFilesDir();
    }
}
