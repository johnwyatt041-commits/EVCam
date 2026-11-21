package com.kooo.evcam.transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service hosting {@link FileTransferServer} so mobile browsers can continue streaming
 * or downloading after closing the QR dialog, with automatic 60-second idle shutdown.
 */
public class LanShareService extends Service {

    private static final String TAG = "LanShareService";
    private static final String CHANNEL_ID = "lan_share";
    private static final int NOTIFICATION_ID = 7101;
    private static final int PORT_RANGE_START = 18765;
    private static final int PORT_RANGE_END = 18788;

    public static final String ACTION_START_SELECTED = "com.kooo.evcam.action.LAN_SHARE_START_SELECTED";
    public static final String ACTION_START_LIBRARY = "com.kooo.evcam.action.LAN_SHARE_START_LIBRARY";
    public static final String ACTION_SWITCH_IP = "com.kooo.evcam.action.LAN_SHARE_SWITCH_IP";
    public static final String ACTION_STOP = "com.kooo.evcam.action.LAN_SHARE_STOP";
    public static final String EXTRA_IP = "lan_share_ip";
    public static final String EXTRA_FILES = "lan_share_files";

    public static final class LanShareState {
        public final boolean running;
        public final boolean starting;
        public final FileTransferServer.ShareMode shareMode;
        public final List<File> selectedFiles;
        @Nullable public final String boundIp;
        public final int port;
        @Nullable public final String shareUrl;
        @Nullable public final String errorMessage;
        public final int activeStreams;
        public final int idleRemainingSec;
        public final String statusText;
        public final Map<String, String> fileStatuses;

        LanShareState(boolean running,
                      boolean starting,
                      FileTransferServer.ShareMode shareMode,
                      List<File> selectedFiles,
                      @Nullable String boundIp,
                      int port,
                      @Nullable String shareUrl,
                      @Nullable String errorMessage,
                      int activeStreams,
                      int idleRemainingSec,
                      String statusText,
                      Map<String, String> fileStatuses) {
            this.running = running;
            this.starting = starting;
            this.shareMode = shareMode;
            this.selectedFiles = Collections.unmodifiableList(new ArrayList<>(selectedFiles));
            this.boundIp = boundIp;
            this.port = port;
            this.shareUrl = shareUrl;
            this.errorMessage = errorMessage;
            this.activeStreams = activeStreams;
            this.idleRemainingSec = idleRemainingSec;
            this.statusText = statusText;
            this.fileStatuses = Collections.unmodifiableMap(new LinkedHashMap<>(fileStatuses));
        }

        public static LanShareState stopped() {
            return new LanShareState(false, false, FileTransferServer.ShareMode.SELECTED_FILES,
                    Collections.emptyList(), null, 0, null, null, 0, 0, "未启动",
                    Collections.emptyMap());
        }
    }

    public interface StateListener {
        void onStateChanged(LanShareState state);
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArraySet<StateListener> listeners = new CopyOnWriteArraySet<>();
    private static volatile LanShareState currentState = LanShareState.stopped();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lan-share-ctrl");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, String> liveFileStatuses = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile FileTransferServer activeServer;
    private volatile FileTransferServer.ShareMode activeMode = FileTransferServer.ShareMode.SELECTED_FILES;
    private volatile List<File> activeSelectedFiles = Collections.emptyList();
    private volatile String activeStatusText = "等待连接...";
    private volatile int startGeneration = 0;

    private final Runnable watchdogTickRunnable = new Runnable() {
        @Override
        public void run() {
            FileTransferServer server = activeServer;
            if (server == null || !server.isRunning()) {
                return;
            }
            LanIdleWatchdog wd = server.getWatchdog();
            if (wd.shouldAutoStop()) {
                AppLog.i(TAG, "LAN share idle timeout (60s) reached; auto stopping");
                stopSelf();
                return;
            }
            publishRunningState(server);
            mainHandler.postDelayed(this, LanIdleWatchdog.POLL_INTERVAL_MS);
        }
    };

    public static LanShareState getCurrentState() {
        return currentState;
    }

    public static void addStateListener(@NonNull StateListener listener) {
        listeners.add(listener);
        LanShareState snapshot = currentState;
        mainHandler.post(() -> listener.onStateChanged(snapshot));
    }

    public static void removeStateListener(@NonNull StateListener listener) {
        listeners.remove(listener);
    }

    public static void startSelectedShare(@NonNull Context context,
                                          @NonNull List<File> files,
                                          @Nullable String preferredIp) {
        Context app = context.getApplicationContext();
        ArrayList<String> paths = new ArrayList<>(files.size());
        for (File file : files) {
            if (file != null) {
                paths.add(file.getAbsolutePath());
            }
        }
        Intent intent = new Intent(app, LanShareService.class)
                .setAction(ACTION_START_SELECTED)
                .putStringArrayListExtra(EXTRA_FILES, paths);
        if (preferredIp != null && !preferredIp.isEmpty()) {
            intent.putExtra(EXTRA_IP, preferredIp);
        }
        ContextCompat.startForegroundService(app, intent);
    }

    public static void startLibraryShare(@NonNull Context context, @Nullable String preferredIp) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, LanShareService.class)
                .setAction(ACTION_START_LIBRARY);
        if (preferredIp != null && !preferredIp.isEmpty()) {
            intent.putExtra(EXTRA_IP, preferredIp);
        }
        ContextCompat.startForegroundService(app, intent);
    }

    public static void switchIp(@NonNull Context context, @NonNull String newIp) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, LanShareService.class)
                .setAction(ACTION_SWITCH_IP)
                .putExtra(EXTRA_IP, newIp);
        ContextCompat.startForegroundService(app, intent);
    }

    public static void stopShare(@NonNull Context context) {
        Context app = context.getApplicationContext();
        app.stopService(new Intent(app, LanShareService.class));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification("正在准备局域网分享..."));
        } catch (Exception e) {
            AppLog.e(TAG, "Failed entering foreground for LAN share", e);
            publishErrorState("无法启动前台分享服务，请检查系统通知/后台权限");
            stopSelf();
            return START_NOT_STICKY;
        }

        String requestedIp = intent != null ? intent.getStringExtra(EXTRA_IP) : null;
        if (ACTION_SWITCH_IP.equals(action)) {
            if (requestedIp != null && !requestedIp.isEmpty()) {
                FileTransferServer running = activeServer;
                if (running != null && running.isRunning() && requestedIp.equals(running.getIp())) {
                    running.getWatchdog().touchActivity();
                    publishRunningState(running);
                    return START_NOT_STICKY;
                }
                scheduleRestart(activeMode, activeSelectedFiles, requestedIp);
            }
            return START_NOT_STICKY;
        }

        if (ACTION_START_LIBRARY.equals(action)) {
            if (isReusableRunning(FileTransferServer.ShareMode.LIBRARY, Collections.emptyList(), requestedIp)) {
                activeServer.getWatchdog().touchActivity();
                publishRunningState(activeServer);
                return START_NOT_STICKY;
            }
            scheduleRestart(FileTransferServer.ShareMode.LIBRARY, Collections.emptyList(), requestedIp);
            return START_NOT_STICKY;
        }

        List<File> files = extractFiles(intent);
        if (files.isEmpty()) {
            publishErrorState("没有可分享的文件");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (isReusableRunning(FileTransferServer.ShareMode.SELECTED_FILES, files, requestedIp)) {
            activeServer.getWatchdog().touchActivity();
            publishRunningState(activeServer);
            return START_NOT_STICKY;
        }
        scheduleRestart(FileTransferServer.ShareMode.SELECTED_FILES, files, requestedIp);
        return START_NOT_STICKY;
    }

    private boolean isReusableRunning(FileTransferServer.ShareMode targetMode,
                                      List<File> targetFiles,
                                      @Nullable String requestedIp) {
        FileTransferServer server = activeServer;
        if (server == null || !server.isRunning() || activeMode != targetMode) {
            return false;
        }
        if (requestedIp != null && !requestedIp.isEmpty() && !requestedIp.equals(server.getIp())) {
            return false;
        }
        if (targetMode == FileTransferServer.ShareMode.LIBRARY) {
            return true;
        }
        return sameFilePaths(activeSelectedFiles, targetFiles);
    }

    private static boolean sameFilePaths(List<File> a, List<File> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).getAbsolutePath().equals(b.get(i).getAbsolutePath())) {
                return false;
            }
        }
        return true;
    }

    private void scheduleRestart(FileTransferServer.ShareMode mode,
                                 List<File> files,
                                 @Nullable String preferredIp) {
        final int generation = ++startGeneration;
        activeMode = mode;
        activeSelectedFiles = new ArrayList<>(files);
        activeStatusText = "正在启动局域网服务...";
        liveFileStatuses.clear();
        for (File f : files) {
            liveFileStatuses.put(f.getName(), "等待");
        }
        updateState(new LanShareState(
                false, true, mode, activeSelectedFiles, preferredIp, 0, null, null,
                0, (int) (LanIdleWatchdog.DEFAULT_IDLE_TIMEOUT_MS / 1000L),
                activeStatusText, liveFileStatuses));

        executor.execute(() -> {
            if (generation != startGeneration) {
                return;
            }
            stopActiveServer();

            String targetIp = resolveTargetIp(preferredIp);
            if (targetIp == null || targetIp.isEmpty()) {
                publishErrorState("未检测到可用的局域网或车机热点网络");
                mainHandler.post(this::stopSelf);
                return;
            }

            FileTransferServer created = startServerWithPortFallback(mode, activeSelectedFiles, targetIp);
            if (created == null) {
                publishErrorState("无法绑定局域网端口，请检查网络后重试");
                mainHandler.post(this::stopSelf);
                return;
            }
            if (generation != startGeneration) {
                created.stop();
                return;
            }

            activeServer = created;
            activeStatusText = "等待连接...";
            created.setCallback(new FileTransferServer.TransferCallback() {
                @Override
                public void onServerStarted(String ipAddress, int port, String qrUrl) {
                    publishRunningState(created);
                }

                @Override
                public void onServerStopped() {
                    // Handled explicitly in stopActiveServer / onDestroy.
                }

                @Override
                public void onFileRequested(String fileName) {
                    liveFileStatuses.put(fileName, "传输中...");
                    activeStatusText = "正在传输: " + fileName;
                    publishRunningState(created);
                }

                @Override
                public void onFileTransferred(String fileName, boolean success) {
                    liveFileStatuses.put(fileName, success ? "完成" : "中断");
                    activeStatusText = (success ? "传输完成: " : "连接已中断: ") + fileName;
                    publishRunningState(created);
                }

                @Override
                public void onError(String error) {
                    publishErrorState(error);
                }
            });

            publishRunningState(created);
            mainHandler.removeCallbacks(watchdogTickRunnable);
            mainHandler.postDelayed(watchdogTickRunnable, LanIdleWatchdog.POLL_INTERVAL_MS);
        });
    }

    @Nullable
    private String resolveTargetIp(@Nullable String preferredIp) {
        List<LanNetworkSelector.NetworkEndpoint> endpoints = LanNetworkSelector.enumerateLocalEndpoints(this);
        if (preferredIp != null && !preferredIp.isEmpty()) {
            for (LanNetworkSelector.NetworkEndpoint ep : endpoints) {
                if (preferredIp.equals(ep.ip)) {
                    return preferredIp;
                }
            }
            if (!endpoints.isEmpty()) {
                String fallbackIp = endpoints.get(0).ip;
                AppLog.w(TAG, "Preferred LAN IP " + preferredIp + " is not in active endpoints; falling back to " + fallbackIp);
                return fallbackIp;
            }
            AppLog.w(TAG, "Preferred LAN IP " + preferredIp + " is unavailable and no active LAN endpoints were detected");
            return null;
        }
        return endpoints.isEmpty() ? null : endpoints.get(0).ip;
    }

    @Nullable
    private FileTransferServer startServerWithPortFallback(FileTransferServer.ShareMode mode,
                                                             List<File> files,
                                                             String targetIp) {
        // 1. Bind strictly to the selected NIC IP across the preferred port range.
        for (int port = PORT_RANGE_START; port <= PORT_RANGE_END; port++) {
            FileTransferServer candidate = tryStartServer(targetIp, targetIp, port, mode, files);
            if (candidate != null) {
                return candidate;
            }
        }
        AppLog.w(TAG, "Strict bind on " + targetIp + ":" + PORT_RANGE_START + "-" + PORT_RANGE_END
                + " failed; retrying wildcard bind across preferred port range");

        // 2. Fallback to wildcard (0.0.0.0) across preferred port range while advertising targetIp.
        for (int port = PORT_RANGE_START; port <= PORT_RANGE_END; port++) {
            FileTransferServer wildcard = tryStartServer(null, targetIp, port, mode, files);
            if (wildcard != null) {
                return wildcard;
            }
        }

        // 3. Fallback to ephemeral port on the selected NIC, then wildcard ephemeral port.
        FileTransferServer ephemeralBound = tryStartServer(targetIp, targetIp, 0, mode, files);
        if (ephemeralBound != null) {
            return ephemeralBound;
        }
        FileTransferServer wildcardEphemeral = tryStartServer(null, targetIp, 0, mode, files);
        if (wildcardEphemeral == null) {
            AppLog.w(TAG, "All bind attempts failed for targetIp=" + targetIp);
        }
        return wildcardEphemeral;
    }

    @Nullable
    private FileTransferServer tryStartServer(@Nullable String bindHost,
                                              String advertiseIp,
                                              int port,
                                              FileTransferServer.ShareMode mode,
                                              List<File> files) {
        FileTransferServer server = new FileTransferServer(this, bindHost, port, mode);
        server.setSpecifiedIpAddress(advertiseIp);
        if (mode == FileTransferServer.ShareMode.SELECTED_FILES) {
            for (int i = 0; i < files.size(); i++) {
                server.addFile("file_" + i, files.get(i));
            }
        }
        try {
            server.start();
            if (server.isRunning() && server.getShareUrl() != null) {
                return server;
            }
            AppLog.w(TAG, "FileTransferServer did not stay running after start (bind="
                    + (bindHost != null ? bindHost : "0.0.0.0") + ", port=" + port
                    + ", url=" + server.getShareUrl() + ")");
            server.stop();
        } catch (Exception e) {
            AppLog.w(TAG, "Bind failed (bind=" + (bindHost != null ? bindHost : "0.0.0.0")
                    + ", port=" + port + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void publishRunningState(FileTransferServer server) {
        LanIdleWatchdog wd = server.getWatchdog();
        String url = server.getShareUrl();
        updateNotification(url != null
                ? url + "（" + wd.getRemainingIdleSeconds() + "s 无访问关闭）"
                : "局域网分享运行中");
        updateState(new LanShareState(
                true,
                false,
                activeMode,
                activeSelectedFiles,
                server.getIp(),
                server.getPort(),
                url,
                null,
                wd.getActiveStreamCount(),
                wd.getRemainingIdleSeconds(),
                activeStatusText,
                liveFileStatuses));
    }

    private void publishErrorState(String message) {
        updateState(new LanShareState(
                false,
                false,
                activeMode,
                activeSelectedFiles,
                null,
                0,
                null,
                message,
                0,
                0,
                message,
                liveFileStatuses));
    }

    private static void updateState(LanShareState newState) {
        currentState = newState;
        mainHandler.post(() -> {
            for (StateListener listener : listeners) {
                listener.onStateChanged(newState);
            }
        });
    }

    private void stopActiveServer() {
        mainHandler.removeCallbacks(watchdogTickRunnable);
        FileTransferServer server = activeServer;
        activeServer = null;
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                AppLog.w(TAG, "Error stopping FileTransferServer: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDestroy() {
        startGeneration++;
        mainHandler.removeCallbacks(watchdogTickRunnable);
        stopActiveServer();
        executor.shutdownNow();
        updateState(LanShareState.stopped());
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        AppLog.i(TAG, "LanShareService destroyed");
        super.onDestroy();
    }

    private static List<File> extractFiles(@Nullable Intent intent) {
        if (intent == null) {
            return Collections.emptyList();
        }
        ArrayList<String> paths = intent.getStringArrayListExtra(EXTRA_FILES);
        if (paths == null || paths.isEmpty()) {
            return Collections.emptyList();
        }
        List<File> files = new ArrayList<>(paths.size());
        for (String path : paths) {
            if (path == null || path.isEmpty()) continue;
            File file = new File(path);
            if (file.isFile() && file.canRead() && LanPathGuard.isAllowedMediaName(file.getName())) {
                files.add(file);
            }
        }
        return files;
    }

    private void ensureNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Wi-Fi 录像分享", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示手机扫码/浏览器访问局域网录像的运行状态");
            nm.createNotificationChannel(channel);
        }
    }

    private void updateNotification(String content) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(content));
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String contentText) {
        PendingIntent launchIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopIntent = PendingIntent.getService(
                this,
                1,
                new Intent(this, LanShareService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Wi-Fi 录像与照片分享")
                .setContentText(contentText)
                .setOngoing(true)
                .setContentIntent(launchIntent)
                .addAction(0, "结束分享", stopIntent)
                .build();
    }
}
