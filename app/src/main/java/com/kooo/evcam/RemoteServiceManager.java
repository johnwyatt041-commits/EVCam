package com.kooo.evcam;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.feishu.FeishuApiClient;
import com.kooo.evcam.feishu.FeishuBotManager;
import com.kooo.evcam.feishu.FeishuConfig;
import com.kooo.evcam.feishu.FeishuGearGateController;
import com.kooo.evcam.lifecycle.AppLifecycleState;
import com.kooo.evcam.vehicle.RecordingVehicleSource;
import com.kooo.evcam.vehicle.VehicleGearStatePublisher;
import com.kooo.evcam.vehicle.VehicleSignalHub;
import com.kooo.evcam.vehicle.VehicleSignalSnapshot;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 远程服务管理器（单例）
 * 统一管理飞书远程连接生命周期与 T10 P 档门控懒加载：
 * - 未配置或关闭总开关时绝不创建任何远程连接或 VHAL 档位租约；
 * - 启用后默认仅在确认 P 档（2s 防抖）懒加载连接，切离 P 档（0.5s）或 UNKNOWN 超时（3s）彻底断连并清理定时器。
 */
public class RemoteServiceManager {
    private static final String TAG = "RemoteServiceManager";
    private static volatile RemoteServiceManager instance;

    private Context boundAppContext;
    private FeishuBotManager feishuBotManager;
    private FeishuApiClient feishuApiClient;
    private volatile boolean isFeishuStarting = false;
    private final Object feishuLock = new Object();

    private FeishuGearGateController gearGateController;
    private RecordingVehicleSource.Lease gearSignalLease;
    private VehicleGearStatePublisher.GearStateListener gearStateListener;
    private boolean gearListenerRegistered = false;

    private WeakReference<StatusInfoProvider> statusInfoProviderRef;
    private WeakReference<FeishuCommandBridge> commandBridgeRef;
    private final CopyOnWriteArrayList<FeishuStateListener> stateListeners = new CopyOnWriteArrayList<>();

    public interface StatusInfoProvider {
        String getFullStatusInfo();
    }

    /** 可选的前台 Activity 命令桥，存活时直连 MainActivity，回收后自动回落 WakeUpHelper。 */
    public interface FeishuCommandBridge {
        void onRecordCommand(String chatId, String messageId, int durationSeconds);
        void onPhotoCommand(String chatId, String messageId);
        String onStartRecordingCommand();
        String onStopRecordingCommand();
        String onExitCommand(boolean confirmed);
        String onForegroundCommand();
        String onBackgroundCommand();
    }

    public interface FeishuStateListener {
        void onFeishuStateChanged();
    }

    private RemoteServiceManager() {
        AppLog.d(TAG, "RemoteServiceManager instance created");
    }

    public static synchronized boolean hasInitializedInstance() {
        return instance != null;
    }

    public static synchronized void stopAllIfInitialized() {
        if (instance != null) {
            instance.stopAllServices();
        }
    }

    public static synchronized RemoteServiceManager getInstance() {
        if (instance == null) {
            instance = new RemoteServiceManager();
        }
        return instance;
    }

    public void setStatusInfoProvider(@Nullable StatusInfoProvider provider) {
        this.statusInfoProviderRef = provider != null ? new WeakReference<>(provider) : null;
    }

    public void clearStatusInfoProvider() {
        this.statusInfoProviderRef = null;
    }

    public void setFeishuCommandBridge(@Nullable FeishuCommandBridge bridge) {
        this.commandBridgeRef = bridge != null ? new WeakReference<>(bridge) : null;
    }

    public void clearFeishuCommandBridge() {
        this.commandBridgeRef = null;
    }

    public void addFeishuStateListener(@Nullable FeishuStateListener listener) {
        if (listener != null && !stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }

    public void removeFeishuStateListener(@Nullable FeishuStateListener listener) {
        if (listener != null) {
            stateListeners.remove(listener);
        }
    }

    private void notifyFeishuStateListeners() {
        for (FeishuStateListener listener : stateListeners) {
            try {
                listener.onFeishuStateChanged();
            } catch (Exception e) {
                AppLog.w(TAG, "通知飞书状态监听器异常: " + e.getMessage());
            }
        }
    }

    public String getStatusInfo(Context context) {
        if (statusInfoProviderRef != null) {
            StatusInfoProvider provider = statusInfoProviderRef.get();
            if (provider != null) {
                try {
                    String fullInfo = provider.getFullStatusInfo();
                    if (fullInfo != null) {
                        return fullInfo;
                    }
                } catch (Exception e) {
                    AppLog.e(TAG, "获取完整状态信息失败，使用基本信息", e);
                }
            } else {
                statusInfoProviderRef = null;
            }
        }
        return buildBasicStatusInfo(context);
    }

    public synchronized void setFeishuService(FeishuBotManager manager, FeishuApiClient apiClient) {
        this.feishuBotManager = manager;
        this.feishuApiClient = apiClient;
        notifyFeishuStateListeners();
    }

    public synchronized FeishuBotManager getFeishuBotManager() {
        return feishuBotManager;
    }

    public synchronized FeishuApiClient getFeishuApiClient() {
        return feishuApiClient;
    }

    public boolean isFeishuRunning() {
        FeishuBotManager manager = getFeishuBotManager();
        return manager != null && manager.isRunning();
    }

    public boolean isFeishuStartingOrRunning() {
        synchronized (feishuLock) {
            FeishuBotManager manager = getFeishuBotManager();
            return isFeishuStarting || (manager != null && manager.isStartingOrRunning());
        }
    }

    public void clearFeishuService() {
        FeishuBotManager managerToStop;
        synchronized (this) {
            managerToStop = this.feishuBotManager;
            this.feishuBotManager = null;
            this.feishuApiClient = null;
        }
        synchronized (feishuLock) {
            isFeishuStarting = false;
        }
        if (managerToStop != null) {
            managerToStop.stop();
        }
        AppLog.d(TAG, "Feishu service cleared");
        notifyFeishuStateListeners();
    }

    public boolean hasAnyServiceRunning() {
        return isFeishuRunning();
    }

    public void stopAllServices() {
        AppLog.d(TAG, "Stopping all remote services");
        teardownGearGate();
        clearFeishuService();
    }

    public String getServiceStatusDescription() {
        if (isFeishuRunning()) {
            return "飞书远程服务运行中";
        }
        return "远程服务已待命";
    }

    /**
     * 静态无副作用查询：在不激活 RemoteServiceManager 单例的情况下返回展示文案（供设置页使用）。
     */
    public static String describeCurrentFeishuStatus(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        FeishuConfig config = new FeishuConfig(appContext);
        if (!config.isConfigured()) {
            return "未配置凭据（已断开，零后台初始化）";
        }
        if (!config.isEnabled()) {
            return "总开关关闭（已断开，零后台初始化）";
        }
        if (!hasInitializedInstance()) {
            return config.isPGearOnly() ? "等待进入 P 档后按需懒加载..." : "待初始化...";
        }
        RemoteServiceManager mgr = getInstance();
        FeishuGearGateController controller = mgr.gearGateController;
        boolean wsConnected = mgr.isFeishuRunning();
        boolean wsConnecting = mgr.isFeishuStartingOrRunning();
        if (controller == null) {
            if (wsConnected) return "已连接";
            if (wsConnecting) return "正在连接...";
            return config.isPGearOnly() ? "等待进入 P 档..." : "未连接";
        }
        VehicleGearStatePublisher.GearState gearState =
                VehicleSignalHub.getInstance().getGearStatePublisher().getCurrentState();
        return FeishuGearGateController.formatStatusLabel(
                controller.getStatus(), gearState.stableGear, wsConnected, wsConnecting);
    }

    /**
     * 统一同步飞书生命周期（开机自启 / 服务重建 / 配置变更 / P 档切换时调用）。
     */
    public synchronized void syncFeishuLifecycle(@NonNull Context context, @NonNull String reason) {
        Context appContext = context.getApplicationContext();
        this.boundAppContext = appContext;

        boolean canRun = AppLifecycleState.canRunBackgroundServices(appContext);
        FeishuConfig feishuConfig = new FeishuConfig(appContext);
        boolean configured = feishuConfig.isConfigured();
        boolean enabled = feishuConfig.isEnabled();
        boolean pGearOnly = feishuConfig.isPGearOnly();

        if (!canRun || !configured || !enabled) {
            if (gearGateController != null) {
                gearGateController.updateConfig(configured, enabled, pGearOnly, !canRun);
            }
            releaseGearResourcesLocked();
            clearFeishuService();
            AppLog.d(TAG, "飞书总开关关闭/未配置/已退出，已停止并清理资源, reason=" + reason);
            return;
        }

        ensureGearGateInitializedLocked();

        AppConfig appConfig = new AppConfig(appContext);
        boolean needVhalLease = pGearOnly && appConfig.isVhalGrpcTriggerMode();
        if (needVhalLease && gearSignalLease == null) {
            gearSignalLease = RecordingVehicleSource.acquire();
        } else if (!needVhalLease && gearSignalLease != null) {
            gearSignalLease.close();
            gearSignalLease = null;
        }

        VehicleGearStatePublisher publisher = VehicleSignalHub.getInstance().getGearStatePublisher();
        if (pGearOnly && !gearListenerRegistered) {
            publisher.addListener(gearStateListener);
            gearListenerRegistered = true;
        } else if (!pGearOnly && gearListenerRegistered) {
            publisher.removeListener(gearStateListener);
            gearListenerRegistered = false;
        }

        gearGateController.onGearStateChanged(publisher.getCurrentState());
        gearGateController.updateConfig(true, true, pGearOnly, false);
        notifyFeishuStateListeners();
    }

    /**
     * 从 CameraForegroundService 启动配置好的远程服务（严格经过总开关与 P 档门控检查）。
     */
    public void startRemoteServicesFromService(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        if (!FeishuConfig.isFeatureActive(appContext)) {
            return;
        }
        syncFeishuLifecycle(appContext, "service_start");
    }

    public boolean canStartRemoteServices(Context context) {
        return context != null
                && AppLifecycleState.canRunBackgroundServices(context.getApplicationContext());
    }

    /**
     * 按需懒加载入口（门控控制器在 P 档确认后调用，也可由 BackgroundCapabilityController 触发并受门控约束）。
     */
    public boolean ensureFeishuLazyStarted(Context context, String reason) {
        if (context == null) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        if (!canStartRemoteServices(appContext)) {
            AppLog.d(TAG, "user_exited=true，拒绝按需启动飞书服务, reason=" + reason);
            return false;
        }
        FeishuConfig feishuConfig = new FeishuConfig(appContext);
        if (!feishuConfig.isConfigured() || !feishuConfig.isEnabled()) {
            return false;
        }
        if (feishuConfig.isPGearOnly()) {
            VehicleGearStatePublisher.GearState current =
                    VehicleSignalHub.getInstance().getGearStatePublisher().getCurrentState();
            if (!current.isParkConfirmed()) {
                AppLog.d(TAG, "当前非已确认 P 档，跳过飞书建连并保持同步等待, reason=" + reason);
                syncFeishuLifecycle(appContext, reason);
                return false;
            }
        }
        synchronized (feishuLock) {
            if (isFeishuRunning() || isFeishuStarting) {
                return true;
            }
        }
        AppLog.d(TAG, "按需拉起飞书服务, reason=" + reason);
        startFeishuFromService(appContext, feishuConfig);
        return true;
    }

    /**
     * 用户在界面主动点击“启动服务”：保存开启总开关并同步门控（若仅 P 档且不在 P 档则进入等待 P 档，不违规抢连）。
     */
    public String startFeishuManually(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        FeishuConfig feishuConfig = new FeishuConfig(appContext);
        if (!feishuConfig.isConfigured()) {
            return "请先配置并保存飞书 App ID 与 App Secret";
        }
        if (!canStartRemoteServices(appContext)) {
            return "应用处于退出状态，无法启动服务";
        }
        feishuConfig.setEnabled(true);
        syncFeishuLifecycle(appContext, "manual_start");

        if (feishuConfig.isPGearOnly()) {
            VehicleGearStatePublisher.GearState state =
                    VehicleSignalHub.getInstance().getGearStatePublisher().getCurrentState();
            if (state.isParkConfirmed()) {
                ensureFeishuLazyStarted(appContext, "manual_start_in_park");
                return "当前处于 P 档，正在连接飞书...";
            }
            return "已启用仅 P 档模式，将在进入 P 档稳定 2 秒后自动连接";
        }
        return "正在连接飞书服务...";
    }

    /**
     * 用户主动点击“停止服务”：关闭总开关并 0ms 彻底断连、清理心跳与重连定时器。
     */
    public void stopFeishuManually(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        FeishuConfig feishuConfig = new FeishuConfig(appContext);
        feishuConfig.setEnabled(false);
        syncFeishuLifecycle(appContext, "manual_stop");
    }

    private void ensureGearGateInitializedLocked() {
        if (gearGateController != null) {
            return;
        }
        gearGateController = new FeishuGearGateController(new FeishuGearGateController.Host() {
            @Override
            public void onConnectRequested(@NonNull String reason) {
                Context ctx = boundAppContext;
                if (ctx != null) {
                    AppLog.i(TAG, "P 档门控触发飞书懒加载建连: " + reason);
                    startFeishuIfAllowed(ctx, reason);
                }
            }

            @Override
            public void onDisconnectRequested(@NonNull String reason) {
                AppLog.i(TAG, "飞书门控触发彻底断连（停心跳与重连定时器）: " + reason);
                clearFeishuService();
            }

            @Override
            public void onStatusChanged(@NonNull FeishuGearGateController.GateStatus status,
                                        @NonNull VehicleGearStatePublisher.GearState gearState) {
                notifyFeishuStateListeners();
            }
        });
        gearStateListener = state -> {
            FeishuGearGateController controller = gearGateController;
            if (controller != null) {
                controller.onGearStateChanged(state);
            }
        };
    }

    private void teardownGearGate() {
        synchronized (this) {
            if (gearGateController != null) {
                gearGateController.reset();
            }
            releaseGearResourcesLocked();
        }
    }

    private void releaseGearResourcesLocked() {
        if (gearListenerRegistered && gearStateListener != null) {
            VehicleSignalHub.getInstance().getGearStatePublisher().removeListener(gearStateListener);
            gearListenerRegistered = false;
        }
        if (gearSignalLease != null) {
            gearSignalLease.close();
            gearSignalLease = null;
        }
    }

    private void startFeishuIfAllowed(@NonNull Context context, @NonNull String reason) {
        Context appContext = context.getApplicationContext();
        if (!canStartRemoteServices(appContext)) {
            return;
        }
        FeishuConfig config = new FeishuConfig(appContext);
        if (!config.isConfigured() || !config.isEnabled()) {
            return;
        }
        startFeishuFromService(appContext, config);
    }

    private void startFeishuFromService(Context context, FeishuConfig config) {
        synchronized (feishuLock) {
            if (isFeishuRunning() || isFeishuStarting) {
                AppLog.d(TAG, "飞书服务已在运行或正在启动，跳过");
                return;
            }
            isFeishuStarting = true;
        }

        AppLog.d(TAG, "正在懒加载启动飞书服务...");
        try {
            FeishuApiClient apiClient = new FeishuApiClient(config);

            FeishuBotManager.ConnectionCallback connectionCallback = new FeishuBotManager.ConnectionCallback() {
                @Override
                public void onConnected() {
                    AppLog.d(TAG, "飞书服务已连接");
                    notifyFeishuStateListeners();
                }

                @Override
                public void onDisconnected() {
                    AppLog.d(TAG, "飞书服务已断开");
                    notifyFeishuStateListeners();
                }

                @Override
                public void onError(String error) {
                    AppLog.e(TAG, "飞书服务连接错误: " + error);
                    notifyFeishuStateListeners();
                }
            };

            FeishuBotManager.CommandCallback commandCallback = createUnifiedCommandCallback(context);
            FeishuBotManager botManager = new FeishuBotManager(context, config, apiClient, connectionCallback);
            botManager.start(commandCallback);
            setFeishuService(botManager, apiClient);
            AppLog.d(TAG, "飞书服务已注册并开始建连");
        } catch (Exception e) {
            AppLog.e(TAG, "启动飞书服务失败", e);
        } finally {
            synchronized (feishuLock) {
                isFeishuStarting = false;
            }
        }
    }

    private FeishuBotManager.CommandCallback createUnifiedCommandCallback(Context context) {
        return new FeishuBotManager.CommandCallback() {
            @Override
            public void onRecordCommand(String chatId, String messageId, int durationSeconds) {
                FeishuCommandBridge bridge = getActiveBridge();
                if (bridge != null) {
                    bridge.onRecordCommand(chatId, messageId, durationSeconds);
                } else {
                    WakeUpHelper.launchForRecordingFeishu(context, chatId, messageId, durationSeconds);
                }
            }

            @Override
            public void onPhotoCommand(String chatId, String messageId) {
                FeishuCommandBridge bridge = getActiveBridge();
                if (bridge != null) {
                    bridge.onPhotoCommand(chatId, messageId);
                } else {
                    WakeUpHelper.launchForPhotoFeishu(context, chatId, messageId);
                }
            }

            @Override
            public String getStatusInfo() {
                return RemoteServiceManager.this.getStatusInfo(context);
            }

            @Override
            public String onStartRecordingCommand() {
                FeishuCommandBridge bridge = getActiveBridge();
                if (bridge != null) {
                    return bridge.onStartRecordingCommand();
                }
                WakeUpHelper.launchForStartRecording(context);
                return "✅ 正在启动录制...";
            }

            @Override
            public String onStopRecordingCommand() {
                FeishuCommandBridge bridge = getActiveBridge();
                if (bridge != null) {
                    return bridge.onStopRecordingCommand();
                }
                WakeUpHelper.launchForStopRecording(context);
                return "✅ 正在停止录制...";
            }

            @Override
            public String onExitCommand(boolean confirmed) {
                FeishuCommandBridge bridge = getActiveBridge();
                if (bridge != null) {
                    return bridge.onExitCommand(confirmed);
                }
                if (confirmed) {
                    com.kooo.evcam.lifecycle.AppExitCoordinator.exitFromRemoteService(context, null);
                    return "✅ EVCam 已退出";
                }
                return "⚠️ 发送「确认退出」执行退出操作";
            }

            @Override
            public String onForegroundCommand() {
                FeishuCommandBridge bridge = getActiveBridge();
                if (bridge != null) {
                    return bridge.onForegroundCommand();
                }
                WakeUpHelper.launchForForeground(context);
                return "📱 应用已切换到前台";
            }

            @Override
            public String onBackgroundCommand() {
                FeishuCommandBridge bridge = getActiveBridge();
                if (bridge != null) {
                    return bridge.onBackgroundCommand();
                }
                WakeUpHelper.sendBackgroundBroadcast(context);
                return "📴 应用已切换到后台";
            }
        };
    }

    @Nullable
    private FeishuCommandBridge getActiveBridge() {
        return commandBridgeRef != null ? commandBridgeRef.get() : null;
    }

    private String buildBasicStatusInfo(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 EVCam 状态\n");
        sb.append("━━━━━━━━━━━━━━\n");
        try {
            AppConfig appConfig = new AppConfig(context);
            sb.append("🌐 远程服务:\n");
            sb.append("• 飞书: ").append(isFeishuRunning() ? "已连接" : "未连接").append("\n");
            try {
                boolean useExternal = appConfig.isUsingExternalSdCard();
                java.io.File storageDir = useExternal
                        ? StorageHelper.getExternalSdCardRoot(context)
                        : android.os.Environment.getExternalStorageDirectory();
                if (storageDir != null && storageDir.exists()) {
                    long available = StorageHelper.getAvailableSpace(storageDir);
                    String availableStr = StorageHelper.formatSize(available);
                    sb.append("💾 存储: ").append(useExternal ? "U盘" : "内部");
                    sb.append("（剩余 ").append(availableStr).append("）\n");
                }
            } catch (Exception ignored) {
            }
            sb.append("━━━━━━━━━━━━━━\n");
            sb.append("💡 发送指令可远程控制录制/拍照");
        } catch (Exception e) {
            sb.append("获取状态失败: ").append(e.getMessage());
        }
        return sb.toString();
    }
}
