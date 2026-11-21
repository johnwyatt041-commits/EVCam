package com.kooo.evcam;

import android.app.ActivityManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import com.kooo.evcam.blindspot.BlindSpotCoordinator;
import com.kooo.evcam.blindspot.BlindSpotDisplayUtils;
import com.kooo.evcam.blindspot.BlindSpotPlacement;
import com.kooo.evcam.blindspot.MirrorTarget;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.ui.OverlayWindowParamsFactory;
import com.kooo.evcam.ui.StandalonePreviewWindow;

/**
 * 补盲选项服务
 * 负责管理主屏悬浮窗和副屏显示
 */
public class BlindSpotService extends Service {
    private static final String TAG = "BlindSpotService";
    private static BlindSpotService sInstance;

    private WindowManager secondaryWindowManager;
    private View secondaryFloatingView;
    private TextureView secondaryTextureView;
    private Surface secondaryCachedSurface;
    private View secondaryBorderView;
    private View secondaryArrowView;
    private SingleCamera secondaryCamera;
    private String secondaryDesiredCameraPos = null; // 目标副屏摄像头位置

    private MainFloatingWindowView mainFloatingWindowView;
    private BlindSpotFloatingWindowView dedicatedBlindSpotWindow;
    private BlindSpotFloatingWindowView previewBlindSpotWindow;
    private boolean isMainTempShown = false; // 是否为主屏临时显示
    private boolean isSecondaryAdjustMode = false;
    private int secondaryAttachedDisplayId = -1;
    private int secondaryAttachedRawDisplayId = -1;
    private int secondaryAdjustDisplayId = -1;
    private String secondaryAdjustSlot = BlindSpotPlacement.SLOT_RIGHT;
    
    
    // 预创建的补盲窗口池（用于快速显示）
    private BlindSpotFloatingWindowView preCreatedLeftWindow;  // 预创建的左窗口
    private BlindSpotFloatingWindowView preCreatedRightWindow; // 预创建的右窗口
    private boolean isPreCreatedWindowsReady = false; // 预创建窗口是否已准备好
    private final BlindSpotCoordinator blindSpotCoordinator = new BlindSpotCoordinator();
    private static volatile boolean internal360Active = false;

    private LogcatSignalObserver logcatSignalObserver;
    private VhalSignalObserver vhalSignalObserver;
    private CarSignalManagerObserver carSignalManagerObserver;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable hideRunnable;
    private Runnable signalKeepAliveRunnable; // 信号保活计时器（debounce）
    private static final long SIGNAL_KEEPALIVE_MS = 1200; // 1.2秒无信号视为转向灯已关闭（约3个闪烁周期）
    private String currentSignalCamera = null; // 当前转向灯触发的摄像头
    private Runnable secondaryRetryRunnable;
    private int secondaryRetryCount = 0;
    private String previewCameraPos = null;
    private int previewTargetDisplayId = AppConfig.BLIND_SPOT_DISPLAY_MAIN;
    private String previewPlacementSlot = null;

    private AppConfig appConfig;
    private DisplayManager displayManager;

    // 全景影像避让
    private Runnable avmCheckRunnable;
    private long avmEventQueryEndMs;
    private String cachedAvmActivity;
    private boolean cachedAvmForeground;
    private boolean isAvmAvoidanceActive = false; // 当前是否处于避让状态（AVM或自身前台）
    private int avmDeactivateCount = 0; // 连续未检测到AVM前台的次数（去抖）
    private static final int AVM_DEACTIVATE_THRESHOLD = 2; // 连续2次（2秒）未检测到才解除避让
    private static final long AVM_CHECK_INTERVAL_MS = 1000; // 前台检测轮询间隔
    private static volatile boolean isSelfInForeground = false; // EVCam自身Activity是否在前台（生命周期驱动）

    /** MainActivity.onResume 时调用 */
    public static void notifySelfForeground() {
        isSelfInForeground = true;
    }

    /** MainActivity.onPause 时调用 */
    public static void notifySelfBackground() {
        isSelfInForeground = false;
    }

    /**
     * 获取当前补盲活跃的摄像头位置（供 MJPEG 流等外部消费者轮询）。
     * 优先级：转向灯信号 > 预览信号 > null（无补盲活跃）。
     * 注意：long_view / 车门补盲等特殊标记也会返回，调用方需自行判断有效性。
     */
    public static String getActiveBlindSpotCamera() {
        BlindSpotService inst = sInstance;
        if (inst == null) return null;
        if (inst.currentSignalCamera != null) return inst.currentSignalCamera;
        if (inst.previewCameraPos != null) return inst.previewCameraPos;
        return null;
    }

    /**
     * 检查是否有活跃的摄像头悬浮窗（补盲悬浮窗、常驻悬浮窗、副屏、独立预览悬浮窗）正在使用摄像头。
     * 用于 MainActivity.onPause() 判断是否应该保持摄像头连接。
     */
    public static boolean hasActiveCameraWindows() {
        BlindSpotService inst = sInstance;
        boolean mainFloating = inst != null && inst.mainFloatingWindowView != null;
        boolean secondaryHud = inst != null && inst.secondaryFloatingView != null;
        boolean blindSpot = inst != null && inst.dedicatedBlindSpotWindow != null;
        return BlindSpotCoordinator.hasAnyActiveCameraWindow(
                blindSpot,
                secondaryHud,
                mainFloating,
                StandalonePreviewWindow.isShowing()
        );
    }

    /** T07 预留接口：同步内部 360 视图显隐状态，仅抑制主屏补盲，保持双 HUD 联动并在退出后恢复未超时的转向补盲。 */
    public static void setInternal360Active(Context context, boolean active) {
        internal360Active = active;
        BlindSpotService inst = sInstance;
        if (inst != null) {
            inst.mainHandler.post(() -> inst.onInternal360StateChanged(active));
        }
    }

    public static boolean isInternal360Active() {
        return internal360Active;
    }

    /** 外部独立预览悬浮窗关闭后通知检查并释放闲置相机。 */
    public static void notifyExternalCameraWindowClosed(Context context) {
        BlindSpotService inst = sInstance;
        if (inst != null) {
            inst.mainHandler.post(inst::closeCamerasIfIdle);
        }
        MainActivity main = MainActivity.getInstance();
        if (main != null) {
            main.runOnUiThread(main::checkAndCloseCamerasIfIdle);
        }
    }

    /** 补盲服务实例当前是否处于运行状态。 */
    public static boolean isServiceRunning() {
        return sInstance != null;
    }

    /**
     * 重新启动超视模式窗口的摄像头预览（用于应用从后台返回前台时）
     */

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        appConfig = new AppConfig(this);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        syncSuppressionConfig();
        initSignalObserver();
        initAvmAvoidance();
        
        // 预创建补盲窗口（在后台线程中）
        preCreateBlindSpotWindows();
    }
    
    /**
     * 预创建补盲窗口，用于快速显示
     * 在服务启动时后台创建窗口，避免转向灯触发时再创建导致的延迟
     */
    private void preCreateBlindSpotWindows() {
        if (!appConfig.isTurnSignalLinkageEnabled() || !appConfig.isMainBlindSpotEnabled()) {
            AppLog.d(TAG, "转向灯联动或主屏补盲未启用，跳过预创建主屏窗口");
            return;
        }
        
        // 在后台线程预创建窗口
        new Thread(() -> {
            try {
                Thread.sleep(2000); // 延迟2秒，等待服务完全启动
                
                if (!canShowMainOverlay() || !appConfig.isMainBlindSpotEnabled()) {
                    AppLog.d(TAG, "无悬浮窗权限或主屏补盲已关闭，跳过预创建窗口");
                    return;
                }
                
                mainHandler.post(() -> {
                    if (!appConfig.isMainBlindSpotEnabled()) {
                        return;
                    }
                    try {
                        // 预创建左窗口
                        if (preCreatedLeftWindow == null) {
                            preCreatedLeftWindow = new BlindSpotFloatingWindowView(this, false);
                            preCreatedLeftWindow.setCameraPos("left");
                            AppLog.d(TAG, "✅ 预创建左补盲窗口完成");
                        }
                        
                        // 预创建右窗口
                        if (preCreatedRightWindow == null) {
                            preCreatedRightWindow = new BlindSpotFloatingWindowView(this, false);
                            preCreatedRightWindow.setCameraPos("right");
                            AppLog.d(TAG, "✅ 预创建右补盲窗口完成");
                        }
                        
                        isPreCreatedWindowsReady = true;
                        AppLog.i(TAG, "✅ 补盲窗口预创建完成，准备就绪");
                    } catch (Exception e) {
                        AppLog.e(TAG, "预创建补盲窗口失败: " + e.getMessage());
                    }
                });
            } catch (InterruptedException e) {
                AppLog.e(TAG, "预创建窗口线程被中断");
            }
        }, "PreCreateBlindSpotWindows").start();
    }

    private void initSignalObserver() {
        // 停止旧的观察者
        stopSignalObservers();

        String mode = appConfig.getTurnSignalTriggerMode();
        if (appConfig.isCarSignalManagerTriggerMode()) {
            initCarSignalManagerObserver();
        } else if (appConfig.isVhalGrpcTriggerMode()) {
            initVhalSignalObserver();
        } else {
            initLogcatSignalObserver();
        }
        
    }

    /**
     * 检查信号观察者是否存活，若已死亡则重新初始化。
     * 由 onStartCommand（即 update()）调用，修复观察者因连接断开、
     * 初始化失败等原因静默死亡后无法自愈的问题。
     */
    private void ensureSignalObserversAlive() {
        if (!appConfig.isBlindSpotGlobalEnabled()) return;
        if (!appConfig.isTurnSignalLinkageEnabled()) return;

        boolean needReinit = false;
        if (appConfig.isCarSignalManagerTriggerMode()) {
            if (carSignalManagerObserver == null || !carSignalManagerObserver.isAlive()) {
                AppLog.w(TAG, "CarSignalManager observer dead, reinitializing");
                needReinit = true;
            }
        } else if (appConfig.isVhalGrpcTriggerMode()) {
            if (vhalSignalObserver == null || !vhalSignalObserver.isAlive()) {
                AppLog.w(TAG, "VHAL observer dead, reinitializing");
                needReinit = true;
            }
        } else {
            if (logcatSignalObserver == null || !logcatSignalObserver.isAlive()) {
                AppLog.w(TAG, "Logcat observer dead, reinitializing");
                needReinit = true;
            }
        }

        if (needReinit) {
            initSignalObserver();
        }
    }

    private void initVhalSignalObserver() {
        AppLog.d(TAG, "Using vehicle API trigger mode");

        vhalSignalObserver = new VhalSignalObserver(new VhalSignalObserver.TurnSignalListener() {
            @Override
            public void onTurnSignal(String direction, boolean on) {
                if (!appConfig.isBlindSpotGlobalEnabled()) return;
                if (!appConfig.isTurnSignalLinkageEnabled()) return;

                if (on) {
                    handleTurnSignal(direction);
                } else {
                    // 转向灯关闭，启动隐藏计时器
                    startHideTimer();
                }
            }

            @Override
            public void onConnectionStateChanged(boolean connected) {
                AppLog.d(TAG, "Vehicle API connection: " + (connected ? "connected" : "disconnected"));
            }
        });
        vhalSignalObserver.start();
    }

    private void initCarSignalManagerObserver() {
        AppLog.d(TAG, "Using CarSignalManager API trigger mode");

        carSignalManagerObserver = new CarSignalManagerObserver(this, new CarSignalManagerObserver.TurnSignalListener() {
            @Override
            public void onTurnSignal(String direction, boolean on) {
                if (!appConfig.isBlindSpotGlobalEnabled()) return;
                if (!appConfig.isTurnSignalLinkageEnabled()) return;

                if (on) {
                    //handleTurnSignal(direction);
                    // 转向灯打开，显示摄像头
                    // 注意：不能调用 handleTurnSignal()，因为它会触发 resetSignalKeepAlive()
                    // CarSignalManager API 通过轮询获取精确状态，不需要 debounce 机制
                    showBlindSpotCamera(direction);
                } else {
                    // 转向灯关闭，启动隐藏计时器
                    startHideTimer();
                }
            }

            @Override
            public void onConnectionStateChanged(boolean connected) {
                AppLog.d(TAG, "CarSignalManager connection: " + (connected ? "connected" : "disconnected"));
            }
        });
        carSignalManagerObserver.start();
    }

    /**
     * 处理车门关闭事件（车辆API 和 CarSignalManager 共用）
     */

    private void initLogcatSignalObserver() {
        AppLog.d(TAG, "Using Logcat trigger mode");

        // 安全兜底：即使 logcat -T 已从源头跳过历史缓冲，
        // 仍保留 500ms 预热期以防极端情况（如系统时间跳变）
        final long observerStartTime = System.currentTimeMillis();
        final long WARMUP_MS = 500;

        logcatSignalObserver = new LogcatSignalObserver((line, data1) -> {
            if (System.currentTimeMillis() - observerStartTime < WARMUP_MS) return;

            if (!appConfig.isBlindSpotGlobalEnabled()) return;
            if (!appConfig.isTurnSignalLinkageEnabled()) return;

            String leftKeyword = appConfig.getTurnSignalLeftTriggerLog();
            String rightKeyword = appConfig.getTurnSignalRightTriggerLog();

            boolean matched = false;
            if (leftKeyword != null && !leftKeyword.isEmpty() && line.contains(leftKeyword)) {
                matched = true;
                hideHandler.post(() -> handleTurnSignal("left"));
            } else if (rightKeyword != null && !rightKeyword.isEmpty() && line.contains(rightKeyword)) {
                matched = true;
                hideHandler.post(() -> handleTurnSignal("right"));
            }

            if (matched) return;

            if (line.contains("left front turn signal:0") && line.contains("right front turn signal:0")) {
                hideHandler.post(this::startHideTimer);
                return;
            }

            if (line.contains("data1 = 0") || data1 == 0) {
                hideHandler.post(this::startHideTimer);
                return;
            }
        });
        // 将用户配置的触发关键字传入，用于构建 logcat -e 原生过滤正则。
        // 行驶中车机日志量暴增，不做原生过滤会导致转向灯信号被"淹没"而延迟。
        logcatSignalObserver.setFilterKeywords(
                appConfig.getTurnSignalLeftTriggerLog(),
                appConfig.getTurnSignalRightTriggerLog()
        );
        logcatSignalObserver.start();
    }

    private void stopSignalObservers() {
        if (logcatSignalObserver != null) {
            logcatSignalObserver.stop();
            logcatSignalObserver = null;
        }
        if (vhalSignalObserver != null) {
            vhalSignalObserver.stop();
            vhalSignalObserver = null;
        }
        if (carSignalManagerObserver != null) {
            carSignalManagerObserver.stop();
            carSignalManagerObserver = null;
        }

    }

    private boolean canShowMainOverlay() {
        return WakeUpHelper.hasOverlayPermission(this)
                || OverlayWindowParamsFactory.shouldUseAccessibilityOverlay(this);
    }

    private void syncSuppressionConfig() {
        if (appConfig == null) {
            return;
        }
        blindSpotCoordinator.getSuppressionController()
                .setSuppressOnInternal360(appConfig.isBlindSpotSuppressOn360());
        blindSpotCoordinator.getSuppressionController()
                .setInternal360Active(internal360Active);
        blindSpotCoordinator.getSuppressionController()
                .setAvmAvoidanceActive(isAvmAvoidanceActive);
    }

    private void onInternal360StateChanged(boolean active) {
        syncSuppressionConfig();
        blindSpotCoordinator.getSuppressionController().setInternal360Active(active);
        reconcileSuppressionTransition("internal360=" + active);
    }

    private void dismissTransientMainBlindSpotWindows() {
        if (mainFloatingWindowView != null) {
            mainFloatingWindowView.dismiss();
            mainFloatingWindowView = null;
        }
        isMainTempShown = false;
        if (dedicatedBlindSpotWindow != null) {
            dedicatedBlindSpotWindow.hideTurnSignalArrow();
            dedicatedBlindSpotWindow.dismiss();
            dedicatedBlindSpotWindow = null;
        }
    }

    private void dismissTurnSignalMainWindowsOnly() {
        if (isMainTempShown && mainFloatingWindowView != null) {
            mainFloatingWindowView.dismiss();
            mainFloatingWindowView = null;
            isMainTempShown = false;
        } else if (mainFloatingWindowView != null) {
            mainFloatingWindowView.hideTurnSignalArrow();
            mainFloatingWindowView.setBlindSpotActiveSlot(null);
            if (!appConfig.isMainFloatingEnabled()) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
            } else {
                mainFloatingWindowView.updateCamera(appConfig.getMainFloatingCamera());
            }
        }
        if (dedicatedBlindSpotWindow != null) {
            dedicatedBlindSpotWindow.hideTurnSignalArrow();
            dedicatedBlindSpotWindow.dismiss();
            dedicatedBlindSpotWindow = null;
        }
    }

    private void showMainBlindSpotWindowForSignal(String cameraPos, BlindSpotCoordinator.WindowSlot slot) {
        if (!canShowMainOverlay()) {
            return;
        }
        boolean reuseMain = appConfig.isTurnSignalReuseMainFloating();
        if (reuseMain) {
            if (dedicatedBlindSpotWindow != null) {
                dedicatedBlindSpotWindow.dismiss();
                dedicatedBlindSpotWindow = null;
            }
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
            }
            mainFloatingWindowView = new MainFloatingWindowView(this, appConfig);
            mainFloatingWindowView.setDesiredCamera(cameraPos, true);
            mainFloatingWindowView.setBlindSpotActiveSlot(BlindSpotPlacement.resolveSlotForCamera(
                    cameraPos, appConfig.isRainNightBlindSpotEnabled()));
            mainFloatingWindowView.show();
            isMainTempShown = true;
            AppLog.d(TAG, "主屏开启临时补盲悬浮窗: " + cameraPos);
            return;
        }

        if (mainFloatingWindowView != null) {
            mainFloatingWindowView.setBlindSpotActiveSlot(null);
            mainFloatingWindowView.dismiss();
            mainFloatingWindowView = null;
            isMainTempShown = false;
        }
        if (dedicatedBlindSpotWindow != null) {
            dedicatedBlindSpotWindow.dismiss();
            dedicatedBlindSpotWindow = null;
        }

        if (slot == BlindSpotCoordinator.WindowSlot.PRE_CREATED_LEFT && preCreatedLeftWindow != null) {
            dedicatedBlindSpotWindow = preCreatedLeftWindow;
            preCreatedLeftWindow = null;
            dedicatedBlindSpotWindow.setCameraPos(cameraPos);
            AppLog.d(TAG, "✅ 使用预创建的左补盲窗口");
        } else if (slot == BlindSpotCoordinator.WindowSlot.PRE_CREATED_RIGHT && preCreatedRightWindow != null) {
            dedicatedBlindSpotWindow = preCreatedRightWindow;
            preCreatedRightWindow = null;
            dedicatedBlindSpotWindow.setCameraPos(cameraPos);
            AppLog.d(TAG, "✅ 使用预创建的右补盲窗口");
        } else {
            dedicatedBlindSpotWindow = new BlindSpotFloatingWindowView(this, false);
            dedicatedBlindSpotWindow.setCameraPos(cameraPos);
            AppLog.d(TAG, "新建独立补盲窗口: " + cameraPos);
        }

        dedicatedBlindSpotWindow.show();

        if (!isPreCreatedWindowsReady || preCreatedLeftWindow == null || preCreatedRightWindow == null) {
            preCreateBlindSpotWindows();
        }
    }

    private void reconcileSuppressionTransition(String reason) {
        syncSuppressionConfig();
        if (!appConfig.isMainBlindSpotEnabled()) {
            dismissTurnSignalMainWindowsOnly();
            return;
        }
        if (blindSpotCoordinator.getSuppressionController().isMainScreenSuppressed()) {
            if (currentSignalCamera != null) {
                blindSpotCoordinator.getSuppressionController().recordSuppressedTurn(currentSignalCamera);
            }
            AppLog.i(TAG, "主屏补盲暂停(" + reason + ")，仅隐藏主屏窗口，副屏 EVCC HUD 与 ESP32 推流保持联动");
            dismissTransientMainBlindSpotWindows();
            return;
        }

        String resumeDir = blindSpotCoordinator.getSuppressionController().consumeResumeDirectionIfClear();
        String targetDir = resumeDir != null ? resumeDir : currentSignalCamera;
        if (currentSignalCamera != null && targetDir != null) {
            BlindSpotCoordinator.WindowSlot slot = BlindSpotCoordinator.resolveWindowSlot(
                    targetDir, preCreatedLeftWindow != null, preCreatedRightWindow != null);
            showMainBlindSpotWindowForSignal(targetDir, slot);
            if (!appConfig.isTurnSignalReuseMainFloating() && dedicatedBlindSpotWindow != null) {
                final String bindPos = targetDir;
                mainHandler.post(() -> {
                    if (dedicatedBlindSpotWindow != null && bindPos.equals(currentSignalCamera)) {
                        dedicatedBlindSpotWindow.setCamera(bindPos);
                    }
                });
                dedicatedBlindSpotWindow.showTurnSignalArrow(targetDir);
            }
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.showTurnSignalArrow(targetDir);
            }
            AppLog.i(TAG, "抑制解除(" + reason + ")，恢复主屏补盲: " + targetDir);
        } else {
            updateMainFloatingWindow();
        }
    }

    /**
     * 显示盲区摄像头（用于 CarSignalManager API，不使用 debounce）
     */
    private void showBlindSpotCamera(String cameraPos) {
        activateBlindSpotSignal(cameraPos, true);
    }

    private void handleTurnSignal(String cameraPos) {
        activateBlindSpotSignal(cameraPos, false);
    }

    private void activateBlindSpotSignal(String cameraPos, boolean isCarSignalManagerSource) {
        AppLog.i(TAG, "🚦 转向灯触发摄像头: " + cameraPos + " (" + (isCarSignalManagerSource ? "CarSignalManager" : "VHAL/Logcat") + ")");

        if (isMainTempShown) {
            AppLog.i(TAG, "🚦 检测到临时主屏悬浮窗，转向灯接管");
            if (isCarSignalManagerSource) {
                isMainTempShown = false;
            }
        }

        // 取消隐藏计时器
        if (hideRunnable != null) {
            hideHandler.removeCallbacks(hideRunnable);
            hideRunnable = null;
            AppLog.d(TAG, "🚦 已取消隐藏计时器");
        }

        if (isCarSignalManagerSource) {
            if (signalKeepAliveRunnable != null) {
                hideHandler.removeCallbacks(signalKeepAliveRunnable);
                signalKeepAliveRunnable = null;
            }
        } else {
            // VHAL/Logcat 每次收到信号都重置 1.2s 保活计时器（debounce）
            resetSignalKeepAlive();
        }

        syncSuppressionConfig();

        if (cameraPos != null && cameraPos.equals(currentSignalCamera)) {
            AppLog.d(TAG, "转向灯相同，不重复切换: " + cameraPos);
            return;
        }

        BlindSpotCoordinator.DispatchPlan plan = blindSpotCoordinator.planActivate(
                cameraPos,
                appConfig.isMainBlindSpotEnabled(),
                appConfig.isSecondaryDisplayEnabled(),
                preCreatedLeftWindow != null,
                preCreatedRightWindow != null
        );

        currentSignalCamera = cameraPos;
        AppLog.i(TAG, "🚦 转向灯激活: " + cameraPos + ", showMain=" + plan.showMainWindow + ", showSecondary=" + plan.showSecondaryHud);

        // --- 1. 创建主屏/副屏窗口 UI ---
        boolean reuseMain = appConfig.isTurnSignalReuseMainFloating();
        if (plan.showMainWindow) {
            showMainBlindSpotWindowForSignal(cameraPos, plan.mainWindowSlot);
        } else if (!appConfig.isMainBlindSpotEnabled()) {
            dismissTurnSignalMainWindowsOnly();
            AppLog.d(TAG, "主屏补盲已关闭（仅副屏模式），跳过主屏窗口: " + cameraPos);
        } else {
            dismissTransientMainBlindSpotWindows();
            AppLog.d(TAG, "360/AVM 抑制生效，仅暂停主屏窗口，副屏与 ESP32 推流继续联动: " + cameraPos);
        }

        if (plan.showSecondaryHud) {
            int targetRawId = resolveActiveSecondaryRawDisplayId();
            if (secondaryFloatingView != null && secondaryAttachedRawDisplayId != -1
                    && secondaryAttachedRawDisplayId != targetRawId) {
                removeSecondaryView();
            }
            if (secondaryFloatingView == null) {
                showSecondaryDisplay();
            }
        }

        // --- 2. 启动前台服务与相机管理器 ---
        CameraForegroundService.start(this, "补盲运行中", "正在显示补盲画面");
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().getOrInit(this);

        // --- 3. 提前打开相机（与 UI 布局并行） ---
        {
            MultiCameraManager cm = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
            if (cm != null) {
                SingleCamera cam = cm.getCamera(cameraPos);
                if (cam != null && !cam.isCameraOpened()) {
                    if (isCarSignalManagerSource) {
                        CameraForegroundService.whenReady(this, cam::openCameraDeferred);
                    } else {
                        cam.openCameraDeferred();
                    }
                    AppLog.d(TAG, "📷 触发打开相机: " + cameraPos);
                } else if (cam != null) {
                    AppLog.d(TAG, "📷 相机已打开: " + cameraPos);
                }
            }
        }

        // --- 4. 绑定相机到主屏悬浮窗并显示箭头 ---
        if (plan.showMainWindow) {
            if (!reuseMain && dedicatedBlindSpotWindow != null) {
                final String bindPos = cameraPos;
                mainHandler.post(() -> {
                    if (dedicatedBlindSpotWindow != null && bindPos.equals(currentSignalCamera)) {
                        dedicatedBlindSpotWindow.setCamera(bindPos);
                        AppLog.d(TAG, "📷 绑定相机到窗口: " + bindPos);
                    }
                });
                dedicatedBlindSpotWindow.showTurnSignalArrow(cameraPos);
            }
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.showTurnSignalArrow(cameraPos);
            }
        }

        // --- 5. 副屏 EVCC HUD 联动 ---
        if (plan.showSecondaryHud) {
            startSecondaryCameraPreviewDirectly(cameraPos);
        }
    }
    
    /**
     * 停止长视模式
     */

    private void startSecondaryCameraPreviewDirectly(String cameraPos) {
        secondaryDesiredCameraPos = cameraPos;
        if (secondaryFloatingView != null) {
            updateSecondaryDisplayLayout();
        } else {
            applySecondaryTransform(cameraPos);
        }
        MultiCameraManager cameraManager = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
        if (cameraManager == null) {
            scheduleSecondaryRetry(cameraPos);
            return;
        }

        SingleCamera newCamera = cameraManager.getCamera(cameraPos);
        if (newCamera == null) {
            scheduleSecondaryRetry(cameraPos);
            return;
        }
        
        boolean surfaceReady = secondaryTextureView != null && secondaryTextureView.isAvailable()
            && secondaryCachedSurface != null && secondaryCachedSurface.isValid();
        if (newCamera == secondaryCamera && surfaceReady && newCamera.isSecondaryDisplaySurfaceBound(secondaryCachedSurface)) {
            cancelSecondaryRetry();
            AppLog.d(TAG, "副屏摄像头未变化且 Surface 已绑定，跳过 Session 重建: " + cameraPos);
            return;
        }

        cancelSecondaryRetry();
        boolean isSwitchingCamera = secondaryCamera != null && secondaryCamera != newCamera;
        if (isSwitchingCamera) {
            stopSecondaryCameraPreview();
        }
        secondaryCamera = newCamera;
        
        if (secondaryCamera != null && secondaryTextureView != null && secondaryTextureView.isAvailable()) {
            if (secondaryCachedSurface == null || !secondaryCachedSurface.isValid()) {
                Size previewSize = secondaryCamera.getPreviewSize();
                if (previewSize == null || !secondaryCamera.isCameraOpened()) {
                    // 相机未打开：注册一次性回调，相机打开时立即绑定（无需轮询）
                    // 回调在 onOpened 的 backgroundHandler 线程中同步执行，
                    // 在 createCameraPreviewSession 之前完成，确保副屏 Surface 被第一次 Session 包含
                    AppLog.d(TAG, "副屏注册 onCameraOpened 回调等待绑定: " + cameraPos);
                    cancelSecondaryRetry();
                    final SingleCamera cam = secondaryCamera;
                    final TextureView tv = secondaryTextureView;
                    cam.addOnCameraOpenedCallback(() -> {
                        Size ps = cam.getPreviewSize();
                        if (ps != null && tv != null && tv.isAvailable()) {
                            android.graphics.SurfaceTexture st = tv.getSurfaceTexture();
                            if (st != null) {
                                st.setDefaultBufferSize(ps.getWidth(), ps.getHeight());
                                if (secondaryCachedSurface != null) secondaryCachedSurface.release();
                                secondaryCachedSurface = new Surface(st);
                                cam.setSecondaryDisplaySurface(secondaryCachedSurface, st);
                                AppLog.d(TAG, "副屏通过 onCameraOpened 回调立即绑定 Surface: " + cameraPos);
                                mainHandler.post(() -> applySecondaryTransform(cameraPos));
                            }
                        }
                    });
                    // 如果相机未打开，判断是否需要副屏主动打开
                    // 当主屏悬浮窗正在创建时，由主屏的 updateCamera() 打开相机，
                    // 这样 onCameraOpened 回调中副屏 Surface 已就绪，session 一次建成无需重建
                    if (!cam.isCameraOpened()) {
                        boolean mainWindowWillOpenCamera = mainFloatingWindowView != null || dedicatedBlindSpotWindow != null;
                        if (!mainWindowWillOpenCamera) {
                            AppLog.d(TAG, "副屏主动打开相机（无主屏窗口触发）: " + cameraPos);
                            CameraForegroundService.whenReady(BlindSpotService.this, cam::openCamera);
                        } else {
                            AppLog.d(TAG, "副屏等待主屏窗口打开相机（避免过早创建session）: " + cameraPos);
                        }
                    }
                    return;
                }
                if (secondaryCachedSurface != null) secondaryCachedSurface.release();
                android.graphics.SurfaceTexture surfaceTexture = secondaryTextureView.getSurfaceTexture();
                if (surfaceTexture != null) {
                    surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                }
                secondaryCachedSurface = new Surface(secondaryTextureView.getSurfaceTexture());
            }
            
            if (isSwitchingCamera) {
                // 切换摄像头：延迟绑定副屏 Surface，等旧 session 完全关闭释放 Surface
                // 主悬浮窗会先显示（不含副屏 Surface），副屏稍后加入，避免 "connect: already connected"
                AppLog.d(TAG, "副屏延迟绑定 Surface（等待旧 session 关闭）: " + cameraPos);
                final SingleCamera delayedCamera = secondaryCamera;
                final Surface delayedSurface = secondaryCachedSurface;
                hideHandler.postDelayed(() -> {
                    // 确认仍然是同一个摄像头和 Surface（防止快速切换导致的过期回调）
                    if (delayedCamera == secondaryCamera && delayedSurface == secondaryCachedSurface
                            && delayedSurface != null && delayedSurface.isValid()) {
                        AppLog.d(TAG, "副屏绑定 Surface 并重建 Session: " + cameraPos);
                        android.graphics.SurfaceTexture delaySt = (secondaryTextureView != null && secondaryTextureView.isAvailable()) ? secondaryTextureView.getSurfaceTexture() : null;
                        delayedCamera.setSecondaryDisplaySurface(delayedSurface, delaySt);
                        delayedCamera.recreateSession(false);
                        applySecondaryTransform(cameraPos);
                    }
                }, 300);
            } else {
                // 同一个摄像头或首次绑定：立即设置
                // 首次绑定：始终使用紧急模式
                // - 主屏通过 createCameraPreviewSession() 直接创建 session，不走 recreateSession，
                //   因此不存在双 urgent 冲突
                // - urgent=true 时 isConfiguring=true 的 delay=50ms（vs 非紧急的 500ms），
                //   足够等主屏 session 完成配置后立即重建
                AppLog.d(TAG, "副屏绑定新 Surface 并重建 Session: " + cameraPos + " (urgent=true)");
                android.graphics.SurfaceTexture secSt = (secondaryTextureView != null && secondaryTextureView.isAvailable()) ? secondaryTextureView.getSurfaceTexture() : null;
                secondaryCamera.setSecondaryDisplaySurface(secondaryCachedSurface, secSt);
                secondaryCamera.recreateSession(true);
            }
            applySecondaryTransform(cameraPos);
        } else {
            AppLog.d(TAG, "副屏 TextureView 尚未就绪，暂不绑定 Surface: " + cameraPos);
            scheduleSecondaryRetry(cameraPos);
        }
    }

    private void scheduleSecondaryRetry(String cameraPos) {
        cancelSecondaryRetry();
        secondaryRetryCount++;
        long delayMs;
        if (secondaryRetryCount <= 5) {
            // 前5次快速重试（50ms），覆盖冷启动等待 previewSize 就位的场景
            delayMs = 50;
        } else if (secondaryRetryCount <= 15) {
            delayMs = 500;
        } else if (secondaryRetryCount <= 35) {
            delayMs = 1000;
        } else {
            delayMs = 3000;
        }
        secondaryRetryRunnable = () -> startSecondaryCameraPreviewDirectly(cameraPos);
        hideHandler.postDelayed(secondaryRetryRunnable, delayMs);
    }

    private void cancelSecondaryRetry() {
        if (secondaryRetryRunnable != null) {
            hideHandler.removeCallbacks(secondaryRetryRunnable);
            secondaryRetryRunnable = null;
        }
        secondaryRetryCount = 0;
    }

    /**
     * 预触发相机打开（与 UI 创建并行执行）。
     * 在创建悬浮窗之前调用，使 openCamera 的异步操作与窗口创建/布局同时进行，
     * 避免等 TextureView 就绪后才串行触发 openCamera 的延迟。
     * openCamera 内部有 isOpening/isCameraOpened 防护，不会重复打开。
     */
    private void preOpenCamera(String cameraPos) {
        MultiCameraManager cameraManager = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
        if (cameraManager == null) return;
        SingleCamera cam = cameraManager.getCamera(cameraPos);
        if (cam != null && !cam.isCameraOpened()) {
            AppLog.d(TAG, "预触发相机打开（与UI并行）: " + cameraPos);
            CameraForegroundService.whenReady(this, cam::openCamera);
        }
    }

    /**
     * 重置信号保活计时器（debounce 机制）
     * 转向灯闪烁时，每 ~400ms 会产生一次 value:1 的日志。
     * 如果超过 1.2 秒没有收到新的 value:1 信号，说明转向灯已关闭，
     * 此时启动隐藏计时器（用户配置的延迟时间）。
     */
    private void resetSignalKeepAlive() {
        if (signalKeepAliveRunnable != null) {
            hideHandler.removeCallbacks(signalKeepAliveRunnable);
        }
        signalKeepAliveRunnable = () -> {
            AppLog.d(TAG, "转向灯信号超时（" + SIGNAL_KEEPALIVE_MS + "ms 无新信号），启动隐藏计时器");
            signalKeepAliveRunnable = null;
            startHideTimer();
        };
        hideHandler.postDelayed(signalKeepAliveRunnable, SIGNAL_KEEPALIVE_MS);
    }

    private void startHideTimer() {
        if (hideRunnable != null) {
            hideHandler.removeCallbacks(hideRunnable);
        }

        int timeout = appConfig.getTurnSignalTimeout();
        AppLog.i(TAG, "🚦 转向灯熄灭，启动隐藏计时器: " + timeout + "秒后关闭摄像头");

        hideRunnable = () -> {
            AppLog.i(TAG, "🚦 ⏰ 转向灯超时(" + timeout + "秒)，隐藏补盲画面");
            
            currentSignalCamera = null;
            blindSpotCoordinator.clearDirection(null);
            AppLog.i(TAG, "🚦 清除 currentSignalCamera，车门联动恢复可用");
            
            // 恢复主屏悬浮窗状态
            if (isMainTempShown && mainFloatingWindowView != null) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
                isMainTempShown = false;
            } else if (mainFloatingWindowView != null) {
                mainFloatingWindowView.setBlindSpotActiveSlot(null);
                mainFloatingWindowView.updateCamera(appConfig.getMainFloatingCamera());
            }

            // 隐藏独立补盲窗
            if (dedicatedBlindSpotWindow != null) {
                dedicatedBlindSpotWindow.hideTurnSignalArrow();
                dedicatedBlindSpotWindow.dismiss();
                dedicatedBlindSpotWindow = null;

                // 如果原本主屏悬浮窗就是开启的，补盲结束后需要恢复它
                if (appConfig.isMainFloatingEnabled()) {
                    updateMainFloatingWindow();
                }
            }

            // 隐藏主屏悬浮窗的箭头
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.hideTurnSignalArrow();
            }

            // --- 副屏显示恢复 ---
            updateSecondaryDisplay();
            hideRunnable = null;

            // 补盲结束，如果没有持久 Surface 在用且 Activity 在后台，释放相机
            closeCamerasIfIdle();
        };

        hideHandler.postDelayed(hideRunnable, timeout * 1000L);
    }

    /**
     * 补盲结束后，检查是否可以释放相机资源。
     * 条件：Activity 在后台 且 没有持久悬浮窗/副屏/超视模式窗口在使用相机。
     */
    private void closeCamerasIfIdle() {
        if (isSelfInForeground) {
            return; // Activity 在前台，由 Activity 管理相机
        }
        if (hasActiveCameraWindows()) {
            return; // 仍有持久 Surface 或独立预览悬浮窗在使用相机
        }

        MultiCameraManager cameraManager = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
        if (cameraManager != null) {
            if (cameraManager.isRecording()) {
                AppLog.d(TAG, "补盲结束但正在录制中，保持相机连接");
                return;
            }
            AppLog.d(TAG, "补盲结束且无持久 Surface，释放相机资源");
            cameraManager.closeAllCameras();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!com.kooo.evcam.lifecycle.AppLifecycleState.canRunBackgroundServices(this)) {
            AppLog.d(TAG, "user_exited=true，停止 BlindSpotService");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {

            String action = intent.getStringExtra("action");
            if ("setup_blind_spot_window".equals(action)) {
                showBlindSpotSetupWindow();
                return START_STICKY;
            }
            if ("preview_blind_spot".equals(action)) {
                String cameraPos = intent.getStringExtra("camera_pos");
                if (cameraPos == null) cameraPos = "right";
                String placementSlot = intent.getStringExtra("placement_slot");
                int targetDisplayId = intent.getIntExtra("target_display_id", AppConfig.BLIND_SPOT_DISPLAY_MAIN);
                previewCameraPos = cameraPos;
                previewTargetDisplayId = targetDisplayId;
                previewPlacementSlot = placementSlot;
                showPreviewWindow(cameraPos, placementSlot, targetDisplayId);
                updateWindows();
                return START_STICKY;
            }
            if ("stop_preview_blind_spot".equals(action)) {
                previewCameraPos = null;
                previewPlacementSlot = null;
                previewTargetDisplayId = AppConfig.BLIND_SPOT_DISPLAY_MAIN;
                if (previewBlindSpotWindow != null) {
                    previewBlindSpotWindow.dismiss();
                    previewBlindSpotWindow = null;
                }
                updateWindows();
                return START_STICKY;
            }
            if ("enter_secondary_display_adjust".equals(action)) {
                isSecondaryAdjustMode = true;
                secondaryAdjustDisplayId = intent.getIntExtra("target_display_id", -1);
                String adjustSlot = intent.getStringExtra("placement_slot");
                if (adjustSlot != null) {
                    secondaryAdjustSlot = BlindSpotPlacement.normalizeSlot(adjustSlot);
                }
                updateWindows();
                return START_STICKY;
            }
            if ("exit_secondary_display_adjust".equals(action)) {
                isSecondaryAdjustMode = false;
                secondaryAdjustDisplayId = -1;
                updateWindows();
                return START_STICKY;
            }
            if ("START_SUPERVISION_MODE".equals(intent.getAction())) {

                return START_STICKY;
            }
            if ("STOP_SUPERVISION_MODE".equals(intent.getAction())) {

                return START_STICKY;
            }
        }
        // 重新初始化新功能（设置变更时通过 update() 触发）
        appConfig = new AppConfig(this);
        syncSuppressionConfig();
        ensureSignalObserversAlive();
        initAvmAvoidance();
        reconcileSuppressionTransition("settingsUpdate");

        updateWindows();
        return START_STICKY;
    }

    private void showPreviewWindow(String cameraPos, String placementSlot, int targetDisplayId) {
        if (targetDisplayId == AppConfig.BLIND_SPOT_DISPLAY_MAIN) {
            if (!canShowMainOverlay()) return;

            if (previewBlindSpotWindow == null) {
                previewBlindSpotWindow = new BlindSpotFloatingWindowView(this, false);
                previewBlindSpotWindow.setCameraPos(cameraPos);
                if (placementSlot != null) {
                    previewBlindSpotWindow.enablePositionPreviewMode(placementSlot);
                } else {
                    previewBlindSpotWindow.enableAdjustPreviewMode();
                }
                previewBlindSpotWindow.show();
            } else {
                previewBlindSpotWindow.setCameraPos(cameraPos);
                if (placementSlot != null) {
                    previewBlindSpotWindow.enablePositionPreviewMode(placementSlot);
                } else {
                    previewBlindSpotWindow.enableAdjustPreviewMode();
                }
            }
            previewBlindSpotWindow.setCamera(cameraPos);
            return;
        }

        if (previewBlindSpotWindow != null) {
            previewBlindSpotWindow.dismiss();
            previewBlindSpotWindow = null;
        }
        CameraForegroundService.start(this, "补盲预览", "正在副屏预览补盲画面");
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().getOrInit(this);
    }

    private void showBlindSpotSetupWindow() {
        if (dedicatedBlindSpotWindow != null) {
            dedicatedBlindSpotWindow.dismiss();
        }
        dedicatedBlindSpotWindow = new BlindSpotFloatingWindowView(this, true);
        dedicatedBlindSpotWindow.show();
    }

    /**
     * 更新超视模式悬浮窗的位置和大小
     * 注意：BlindSpotFloatingWindowView.init()已经根据cameraPos加载了正确的配置
     * 此方法只在需要同步两个窗口大小时调用
     */

    private void updateWindows() {
        
        // 全局开关关闭时，清理所有补盲窗口（调整模式和预览模式除外）
        if (!appConfig.isBlindSpotGlobalEnabled() && !isSecondaryAdjustMode && previewCameraPos == null) {
            removeSecondaryView();
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
            }
            if (dedicatedBlindSpotWindow != null) {
                dedicatedBlindSpotWindow.dismiss();
                dedicatedBlindSpotWindow = null;
            }

            currentSignalCamera = null;
            blindSpotCoordinator.reset();
            isMainTempShown = false;
            {
                stopSelf();
            }
            return;
        }

        if (!appConfig.isMainBlindSpotEnabled()) {
            dismissTurnSignalMainWindowsOnly();
        }

        updateSecondaryDisplay();
        updateMainFloatingWindow();

        applyTransforms();
        
        if (isSecondaryAdjustMode
                || appConfig.isMainFloatingEnabled() // 加入主屏悬浮窗检查
                || appConfig.isSecondaryDisplayEnabled()
                || appConfig.isTurnSignalLinkageEnabled() // 加入转向灯联动检查
                || appConfig.isAvmAvoidanceEnabled() // 全景影像避让
                || currentSignalCamera != null // 加入转向灯联动检查
                || previewCameraPos != null) {
            CameraForegroundService.start(this, "补盲运行中", "正在显示补盲画面");
        }
        
        // 如果所有功能都关闭了，可以考虑停止服务
        if (!isSecondaryAdjustMode
                && !appConfig.isMainFloatingEnabled()
                && !appConfig.isSecondaryDisplayEnabled()
                && !appConfig.isTurnSignalLinkageEnabled()
                && !appConfig.isAvmAvoidanceEnabled() // 全景影像避让
                && previewCameraPos == null) {
            AppLog.i(TAG, "🚪 所有功能都关闭，停止服务");
            stopSelf();
        }
    }

    private void applyTransforms() {
        if (mainFloatingWindowView != null) {
            mainFloatingWindowView.applyTransformNow();
        }
        if (dedicatedBlindSpotWindow != null) {
            dedicatedBlindSpotWindow.updateBoundsFromPlacement();
        }
        if (previewBlindSpotWindow != null) {
            previewBlindSpotWindow.updateBoundsFromPlacement();
        }
        applySecondaryTransform(null);
    }

    private int resolveActiveSecondaryRawDisplayId() {
        if (previewCameraPos != null && previewTargetDisplayId != AppConfig.BLIND_SPOT_DISPLAY_MAIN) {
            return previewTargetDisplayId;
        }
        if (isSecondaryAdjustMode && secondaryAdjustDisplayId != -1) {
            return secondaryAdjustDisplayId;
        }
        int configured = appConfig.getSecondaryDisplayId();
        if (configured != Display.DEFAULT_DISPLAY) {
            return configured;
        }
        if (displayManager != null) {
            for (Display d : displayManager.getDisplays()) {
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    return d.getDisplayId();
                }
            }
        }
        if (EvccDashcastDisplayResolver.queryPrimary(this) != null) {
            return EvccDashcastDisplayResolver.PICK_ID;
        }
        return Display.DEFAULT_DISPLAY;
    }

    private String resolveActiveSecondarySlot() {
        if (previewCameraPos != null && previewTargetDisplayId != AppConfig.BLIND_SPOT_DISPLAY_MAIN) {
            if (previewPlacementSlot != null) {
                return BlindSpotPlacement.normalizeSlot(previewPlacementSlot);
            }
            return BlindSpotPlacement.resolveSlotForCamera(
                    previewCameraPos, appConfig.isRainNightBlindSpotEnabled());
        }
        if (isSecondaryAdjustMode && secondaryAdjustSlot != null) {
            return BlindSpotPlacement.normalizeSlot(secondaryAdjustSlot);
        }
        String cam = currentSignalCamera != null
                ? currentSignalCamera
                : (previewCameraPos != null ? previewCameraPos : secondaryDesiredCameraPos);
        return BlindSpotPlacement.resolveSlotForCamera(
                cam != null ? cam : BlindSpotPlacement.SLOT_RIGHT,
                appConfig.isRainNightBlindSpotEnabled());
    }

    @Nullable
    private String resolveActiveSecondaryCameraPos() {
        if (previewCameraPos != null && previewTargetDisplayId != AppConfig.BLIND_SPOT_DISPLAY_MAIN) {
            return BlindSpotPlacement.slotToCameraPos(previewCameraPos);
        }
        if (currentSignalCamera != null) {
            return BlindSpotPlacement.slotToCameraPos(currentSignalCamera);
        }
        if (secondaryDesiredCameraPos != null) {
            return BlindSpotPlacement.slotToCameraPos(secondaryDesiredCameraPos);
        }
        if (isSecondaryAdjustMode && secondaryAdjustSlot != null) {
            return BlindSpotPlacement.slotToCameraPos(secondaryAdjustSlot);
        }
        return null;
    }

    private void applySecondaryTransform(@Nullable String explicitCameraPos) {
        if (secondaryTextureView == null) {
            return;
        }
        int rawDisplayId = secondaryAttachedRawDisplayId != -1
                ? secondaryAttachedRawDisplayId
                : resolveActiveSecondaryRawDisplayId();
        String screenKey = BlindSpotPlacement.screenKeyForDisplay(rawDisplayId);
        int orientation = appConfig.getBlindSpotScreenOrientation(screenKey);
        String cam = explicitCameraPos != null ? explicitCameraPos : resolveActiveSecondaryCameraPos();
        BlindSpotCorrection.applyForScreen(
                secondaryTextureView,
                appConfig,
                screenKey,
                MirrorTarget.SECONDARY_HUD,
                cam,
                orientation);
    }

    private void updateSecondaryDisplay() {
        boolean isSecondaryPreviewActive = previewCameraPos != null
                && previewTargetDisplayId != AppConfig.BLIND_SPOT_DISPLAY_MAIN;
        boolean shouldShow = isSecondaryAdjustMode
                || isSecondaryPreviewActive
                || (appConfig.isBlindSpotGlobalEnabled()
                        && appConfig.isSecondaryDisplayEnabled()
                        && currentSignalCamera != null);

        if (!shouldShow) {
            removeSecondaryView();
            return;
        }

        int rawDisplayId = resolveActiveSecondaryRawDisplayId();
        if (rawDisplayId == Display.DEFAULT_DISPLAY) {
            if (secondaryFloatingView != null) removeSecondaryView();
            AppLog.w(TAG, "副屏输出未指定有效副屏 Display ID 且未检测到外接副屏/EVCC，跳过副屏显示");
            return;
        }
        int resolvedDisplayId = EvccDashcastDisplayResolver.resolve(this, rawDisplayId);
        if (resolvedDisplayId < 0) {
            // EVCC 仪表投屏占位符但 EVCC 没起投屏 — 暂不显示，等 EVCC 起来后下次触发再试
            if (secondaryFloatingView != null) removeSecondaryView();
            AppLog.w(TAG, "副屏选了 EVCC 仪表投屏但当前不可用（EVCC 未启动投屏？），跳过");
            return;
        }
        if (secondaryFloatingView != null
                && ((secondaryAttachedDisplayId != -1 && secondaryAttachedDisplayId != resolvedDisplayId)
                || (secondaryAttachedRawDisplayId != -1 && secondaryAttachedRawDisplayId != rawDisplayId))) {
            removeSecondaryView();
        }

        if (secondaryFloatingView == null) {
            showSecondaryDisplay(resolvedDisplayId, rawDisplayId);
        } else {
            updateSecondaryDisplayLayout();
        }

        if (secondaryFloatingView != null) {
            if (isSecondaryAdjustMode) {
                stopSecondaryCameraPreview();
                if (secondaryBorderView != null) {
                    secondaryBorderView.setVisibility(View.VISIBLE);
                }
            } else if (isSecondaryPreviewActive || (appConfig.isSecondaryDisplayEnabled() && currentSignalCamera != null)) {
                String screenKey = BlindSpotPlacement.screenKeyForDisplay(rawDisplayId);
                if (secondaryBorderView != null) {
                    secondaryBorderView.setVisibility(
                            appConfig.isBlindSpotScreenBorderEnabled(screenKey) ? View.VISIBLE : View.GONE);
                }
                String cameraPos = isSecondaryPreviewActive
                        ? BlindSpotPlacement.slotToCameraPos(previewCameraPos)
                        : currentSignalCamera;
                if (cameraPos != null) {
                    startSecondaryCameraPreviewDirectly(cameraPos);
                }
            } else {
                stopSecondaryCameraPreview();
            }
        }
    }

    /**
     * 更新副屏悬浮窗的布局参数和旋转（视觉坐标 -> WindowManager 逻辑坐标）
     */
    private void updateSecondaryDisplayLayout() {
        if (secondaryFloatingView == null || secondaryWindowManager == null) return;

        int rawDisplayId = secondaryAttachedRawDisplayId != -1
                ? secondaryAttachedRawDisplayId
                : resolveActiveSecondaryRawDisplayId();
        int resolvedDisplayId = secondaryAttachedDisplayId != -1
                ? secondaryAttachedDisplayId
                : EvccDashcastDisplayResolver.resolve(this, rawDisplayId);
        int[] dims = BlindSpotDisplayUtils.getDisplaySizePx(this, resolvedDisplayId);
        String screenKey = BlindSpotPlacement.screenKeyForDisplay(rawDisplayId);
        String slot = resolveActiveSecondarySlot();
        int orientation = appConfig.getBlindSpotScreenOrientation(screenKey);
        BlindSpotPlacement.RectRatio rect = appConfig.getBlindSpotScreenPlacement(
                screenKey, slot, dims[0], dims[1]);
        int[] logical = BlindSpotPlacement.visualToLogicalPixels(rect, dims[0], dims[1], orientation);

        int x = logical[0];
        int y = logical[1];
        int width = logical[2];
        int height = logical[3];

        AppLog.d(TAG, "更新副屏布局: screen=" + screenKey + ", slot=" + slot
                + ", lx=" + x + ", ly=" + y + ", lw=" + width + ", lh=" + height
                + ", orientation=" + orientation);

        WindowManager.LayoutParams params = (WindowManager.LayoutParams) secondaryFloatingView.getLayoutParams();
        params.x = x;
        params.y = y;
        params.width = width > 0 ? width : WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = height > 0 ? height : WindowManager.LayoutParams.WRAP_CONTENT;

        secondaryWindowManager.updateViewLayout(secondaryFloatingView, params);
        secondaryFloatingView.setRotation(0f);
        if (secondaryArrowView != null) {
            secondaryArrowView.setRotation(orientation);
        }

        // 应用透明度
        float alpha = appConfig.getBlindSpotScreenAlpha(screenKey) / 100f;
        secondaryFloatingView.setAlpha(alpha);

        applySecondaryTransform(null);
        
        // 设置边框
        if (secondaryBorderView != null) {
            if (isSecondaryAdjustMode) {
                secondaryBorderView.setVisibility(View.VISIBLE);
            } else {
                secondaryBorderView.setVisibility(
                        appConfig.isBlindSpotScreenBorderEnabled(screenKey) ? View.VISIBLE : View.GONE);
            }
        }
    }

    /** 无参版本：从配置读取 raw displayId 后做 EVCC 仪表投屏占位符解析。 */
    private void showSecondaryDisplay() {
        int rawDisplayId = resolveActiveSecondaryRawDisplayId();
        if (rawDisplayId == Display.DEFAULT_DISPLAY) {
            AppLog.w(TAG, "未指定有效副屏 Display ID，跳过显示");
            return;
        }
        int resolved = EvccDashcastDisplayResolver.resolve(this, rawDisplayId);
        if (resolved < 0) {
            AppLog.w(TAG, "副屏选了 EVCC 仪表投屏但当前不可用，跳过显示");
            return;
        }
        showSecondaryDisplay(resolved, rawDisplayId);
    }

    private void showSecondaryDisplay(int displayId, int rawDisplayId) {
        if (secondaryFloatingView != null) return; // 已经显示了

        Display display = displayManager.getDisplay(displayId);
        if (display == null) {
            AppLog.e(TAG, "找不到指定的副屏 Display ID: " + displayId);
            return;
        }
        secondaryAttachedDisplayId = displayId;
        secondaryAttachedRawDisplayId = rawDisplayId;

        // 创建对应显示器的 Context
        Context displayContext;
        try {
            displayContext = createDisplayContext(display);
        } catch (Exception e) {
            AppLog.e(TAG, "创建副屏 Context 失败（APK 资源可能不可用）: " + e.getMessage());
            return;
        }
        if (displayContext.getResources() == null) {
            AppLog.e(TAG, "副屏 Context 资源为空，跳过显示");
            return;
        }
        secondaryWindowManager = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);

        // 加载布局
        secondaryFloatingView = LayoutInflater.from(displayContext).inflate(R.layout.presentation_secondary_display, null);
        secondaryTextureView = secondaryFloatingView.findViewById(R.id.secondary_texture_view);
        secondaryBorderView = secondaryFloatingView.findViewById(R.id.secondary_border);
        secondaryArrowView = secondaryFloatingView.findViewById(R.id.turn_signal_arrow_view);

        String screenKey = BlindSpotPlacement.screenKeyForDisplay(rawDisplayId);
        String slot = resolveActiveSecondarySlot();
        int[] dims = BlindSpotDisplayUtils.getDisplaySizePx(this, displayId);
        int orientation = appConfig.getBlindSpotScreenOrientation(screenKey);
        BlindSpotPlacement.RectRatio rect = appConfig.getBlindSpotScreenPlacement(
                screenKey, slot, dims[0], dims[1]);
        int[] logical = BlindSpotPlacement.visualToLogicalPixels(rect, dims[0], dims[1], orientation);

        // 设置边框
        secondaryBorderView.setVisibility(isSecondaryAdjustMode ? View.VISIBLE :
                (appConfig.isBlindSpotScreenBorderEnabled(screenKey) ? View.VISIBLE : View.GONE));

        // 设置悬浮窗参数（副屏 EVCC 严格使用 DisplayContext + TYPE_APPLICATION_OVERLAY，不升级为 2032）
        int x = logical[0];
        int y = logical[1];
        int width = logical[2];
        int height = logical[3];

        AppLog.d(TAG, "显示副屏: screen=" + screenKey + ", slot=" + slot
                + ", lx=" + x + ", ly=" + y + ", lw=" + width + ", lh=" + height + ", orientation=" + orientation);

        WindowManager.LayoutParams params = OverlayWindowParamsFactory.createSecondaryDisplayParams(
                width > 0 ? width : WindowManager.LayoutParams.WRAP_CONTENT,
                height > 0 ? height : WindowManager.LayoutParams.WRAP_CONTENT,
                x,
                y
        );

        // 统一使用矩阵变换旋转 TextureView 内容（根视图保持 0°，避免倒置/侧装裁剪与坐标反向）
        secondaryFloatingView.setRotation(0f);
        if (secondaryArrowView != null) {
            secondaryArrowView.setRotation(orientation);
        }

        applySecondaryTransform(null);

        secondaryTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int w, int h) {
                boolean isSecondaryPreviewActive = previewCameraPos != null
                        && previewTargetDisplayId != AppConfig.BLIND_SPOT_DISPLAY_MAIN;
                String cameraPos = null;
                if (isSecondaryPreviewActive) {
                    cameraPos = BlindSpotPlacement.slotToCameraPos(previewCameraPos);
                } else if (appConfig.isSecondaryDisplayEnabled()) {
                    if (secondaryDesiredCameraPos != null) {
                        cameraPos = secondaryDesiredCameraPos;
                    } else if (currentSignalCamera != null) {
                        cameraPos = currentSignalCamera;
                    }
                }
                if (cameraPos == null) {
                    AppLog.d(TAG, "副屏 Surface 就绪，但未启用视频输出");
                    return;
                }
                AppLog.d(TAG, "副屏 Surface 就绪，启动预览: " + cameraPos);
                startSecondaryCameraPreviewDirectly(cameraPos);
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int w, int h) {
                applySecondaryTransform(null);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                // 保存当前 TextureView 的引用，用于判断回调是否来自旧的已替换的 TextureView
                final TextureView currentTv = secondaryTextureView;
                if (currentTv != null) {
                    android.graphics.SurfaceTexture currentSt = currentTv.getSurfaceTexture();
                    // 如果当前副屏的 SurfaceTexture 不是被销毁的那个，说明是旧的 TextureView
                    if (currentSt != null && currentSt != surface) {
                        AppLog.d(TAG, "Ignoring old secondary TextureView destroy callback");
                        return true;
                    }
                }
                stopSecondaryCameraPreview();
                if (secondaryCachedSurface != null) {
                    secondaryCachedSurface.release();
                    secondaryCachedSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {}
        });

        // 应用透明度
        float alpha = appConfig.getBlindSpotScreenAlpha(screenKey) / 100f;
        secondaryFloatingView.setAlpha(alpha);

        try {
            secondaryWindowManager.addView(secondaryFloatingView, params);
        } catch (Exception e) {
            AppLog.e(TAG, "无法添加副屏悬浮窗: " + e.getMessage());
        }
    }

    private void updateMainFloatingWindow() {
        syncSuppressionConfig();
        // 360 / 全景影像避让：仅抑制主屏补盲窗口，副屏与网络流不受影响
        if (blindSpotCoordinator.getSuppressionController().isMainScreenSuppressed()) {
            AppLog.d(TAG, "360/全景影像避让中，跳过主屏悬浮窗更新");
            return;
        }

        if (appConfig.isMainFloatingEnabled()) {
            isMainTempShown = false; // 用户开启
            if (mainFloatingWindowView == null) {
                if (canShowMainOverlay()) {
                    mainFloatingWindowView = new MainFloatingWindowView(this, appConfig);
                    mainFloatingWindowView.show();
                }
            } else {
                mainFloatingWindowView.updateLayout();
            }
            if (mainFloatingWindowView != null && currentSignalCamera == null) {
                mainFloatingWindowView.updateCamera(appConfig.getMainFloatingCamera());
            }
        } else if (currentSignalCamera == null) {
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
            }
            isMainTempShown = false;
        }
    }

    private void stopSecondaryCameraPreview() {
        if (secondaryCamera != null) {
            // 立即停止推帧并关闭 session，确保 Surface 被释放
            // 这样新摄像头才能使用同一个 Surface，避免 "connect: already connected"
            secondaryCamera.stopRepeatingNow();
            secondaryCamera.setSecondaryDisplaySurface(null);
            secondaryCamera.recreateSession();
            secondaryCamera = null;
        }
    }

    private void removeSecondaryView() {
        stopSecondaryCameraPreview();
        secondaryDesiredCameraPos = null;
        secondaryAttachedDisplayId = -1;
        secondaryAttachedRawDisplayId = -1;
        if (secondaryWindowManager != null && secondaryFloatingView != null) {
            try {
                secondaryWindowManager.removeView(secondaryFloatingView);
            } catch (Exception e) {
                // Ignore
            }
            secondaryFloatingView = null;
            secondaryTextureView = null;
            secondaryBorderView = null;
            secondaryArrowView = null;
            secondaryWindowManager = null;
        }
        if (secondaryCachedSurface != null) {
            secondaryCachedSurface.release();
            secondaryCachedSurface = null;
        }
    }

    // ==================== 全景影像避让 ====================

    /**
     * 初始化全景影像避让（前台Activity检测轮询）
     */
    private void initAvmAvoidance() {
        stopAvmAvoidance();
        if (!appConfig.isAvmAvoidanceEnabled()) return;

        String target = appConfig.getAvmAvoidanceActivity();
        AppLog.d(TAG, "启动全景影像避让检测，目标Activity: " + target);

        // "all" 模式：始终避让，不需要轮询检测前台应用
        if ("all".equalsIgnoreCase(target)) {
            isAvmAvoidanceActive = true;
            AppLog.i(TAG, "全景影像避让：all 模式，主屏补盲窗口始终隐藏（副屏与 ESP32 继续联动）");
            reconcileSuppressionTransition("avmAll");
            return;
        }

        avmCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (!appConfig.isAvmAvoidanceEnabled()) {
                    stopAvmAvoidance();
                    return;
                }
                checkAvmForeground();
                hideHandler.postDelayed(this, AVM_CHECK_INTERVAL_MS);
            }
        };
        hideHandler.post(avmCheckRunnable);
    }

    /**
     * 停止全景影像避让检测
     */
    private void stopAvmAvoidance() {
        avmEventQueryEndMs = 0;
        cachedAvmActivity = null;
        cachedAvmForeground = false;
        if (avmCheckRunnable != null) {
            hideHandler.removeCallbacks(avmCheckRunnable);
            avmCheckRunnable = null;
        }
        if (isAvmAvoidanceActive) {
            isAvmAvoidanceActive = false;
            reconcileSuppressionTransition("avmStopped");
        }
    }

    /**
     * 检测目标Activity是否在前台，并相应隐藏/恢复主屏补盲窗口
     */
    private void checkAvmForeground() {
        String targetActivity = appConfig.getAvmAvoidanceActivity();
        if (targetActivity == null || targetActivity.isEmpty()) return;

        // "all" 模式始终视为前台，主屏补盲永不显示
        boolean isAvmForeground = "all".equalsIgnoreCase(targetActivity)
                || isActivityInForeground(targetActivity);

        // EVCam 自身前台检测（基于 Activity 生命周期，即时准确，不依赖 UsageEvents）
        boolean selfFg = isSelfInForeground;

        if (isAvmForeground || selfFg) {
            if (isAvmForeground) {
                avmDeactivateCount = 0; // AVM 确实在前台，重置去抖
            }
            if (!isAvmAvoidanceActive) {
                isAvmAvoidanceActive = true;
                AppLog.i(TAG, "全景影像避让：隐藏主屏补盲窗口（AVM=" + isAvmForeground + ", 自身前台=" + selfFg + "）");
                reconcileSuppressionTransition("avmEnter");
            }
        } else if (isAvmAvoidanceActive) {
            // 两个条件都不满足：AVM 不在前台，EVCam 也不在前台
            avmDeactivateCount++;
            AppLog.d(TAG, "全景影像避让：未检测到前台 (" + avmDeactivateCount + "/" + AVM_DEACTIVATE_THRESHOLD + ")");
            if (avmDeactivateCount >= AVM_DEACTIVATE_THRESHOLD) {
                isAvmAvoidanceActive = false;
                avmDeactivateCount = 0;
                AppLog.i(TAG, "全景影像避让：" + targetActivity + " 已离开前台，恢复主屏补盲窗口");
                reconcileSuppressionTransition("avmExit");
            }
        }
    }

    /**
     * 检测指定Activity（完整类名）是否在前台
     * 使用 UsageEvents 精确到 Activity 级别（需 PACKAGE_USAGE_STATS 权限）
     * 首次查询最近5分钟，之后只读取增量事件并保留前后台状态。
     */
    private boolean isActivityInForeground(String activityClassName) {
        long now = System.currentTimeMillis();
        boolean reset = !activityClassName.equals(cachedAvmActivity) || now < avmEventQueryEndMs;
        if (reset) {
            cachedAvmActivity = activityClassName;
            cachedAvmForeground = false;
            avmEventQueryEndMs = 0;
        }
        long from = avmEventQueryEndMs == 0 ? now - 300000 : Math.max(0, avmEventQueryEndMs - 1);
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return cachedAvmForeground;
            android.app.usage.UsageEvents events = usm.queryEvents(from, now);
            if (events == null) return cachedAvmForeground;
            android.app.usage.UsageEvents.Event event = new android.app.usage.UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (!activityClassName.equals(event.getClassName())) continue;
                if (event.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    cachedAvmForeground = true;
                } else if (event.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    cachedAvmForeground = false;
                }
            }
            avmEventQueryEndMs = now;
        } catch (Exception e) {
            AppLog.w(TAG, "Foreground event query failed", e);
        }
        return cachedAvmForeground;
    }

    /**
     * 检测指定包名的应用是否在前台
     * 使用 UsageEvents 查询最近5分钟的事件，追踪该包名下任意Activity的最后前台/后台状态
     */
    private boolean isPackageInForeground(String packageName) {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return false;

            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents events = usm.queryEvents(now - 300000, now);
            if (events == null) return false;

            android.app.usage.UsageEvents.Event event = new android.app.usage.UsageEvents.Event();
            Boolean lastState = null;

            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (packageName.equals(event.getPackageName())) {
                    if (event.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        lastState = true;
                    } else if (event.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND) {
                        lastState = false;
                    }
                }
            }

            return lastState != null && lastState;
        } catch (Exception e) {
            AppLog.e(TAG, "检测前台包名失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 切换定制键唤醒的预览状态
     */

    @Override
    public void onDestroy() {
        stopSignalObservers();
        stopAvmAvoidance();
        if (hideRunnable != null) {
            hideHandler.removeCallbacks(hideRunnable);
        }
        if (signalKeepAliveRunnable != null) {
            hideHandler.removeCallbacks(signalKeepAliveRunnable);
        }
        cancelSecondaryRetry();
        removeSecondaryView();

        if (mainFloatingWindowView != null) {
            mainFloatingWindowView.dismiss();
        }
        if (dedicatedBlindSpotWindow != null) {
            dedicatedBlindSpotWindow.dismiss();
        }
        if (previewBlindSpotWindow != null) {
            previewBlindSpotWindow.dismiss();
        }
        preCreatedLeftWindow = null;
        preCreatedRightWindow = null;
        blindSpotCoordinator.reset();

        // 注销广播接收器

        sInstance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 更新服务状态
     */
    public static void update(Context context) {
        Intent intent = new Intent(context, BlindSpotService.class);
        context.startService(intent);
    }
}
