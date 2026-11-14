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

import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

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
    private SingleCamera secondaryCamera;
    private String secondaryDesiredCameraPos = null; // 目标副屏摄像头位置

    private MainFloatingWindowView mainFloatingWindowView;
    private BlindSpotFloatingWindowView dedicatedBlindSpotWindow;
    private BlindSpotFloatingWindowView previewBlindSpotWindow;
    private boolean isMainTempShown = false; // 是否为主屏临时显示
    private boolean isSecondaryAdjustMode = false;
    private int secondaryAttachedDisplayId = -1;
    
    
    // 预创建的补盲窗口池（用于快速显示）
    private BlindSpotFloatingWindowView preCreatedLeftWindow;  // 预创建的左窗口
    private BlindSpotFloatingWindowView preCreatedRightWindow; // 预创建的右窗口
    private boolean isPreCreatedWindowsReady = false; // 预创建窗口是否已准备好

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
     * 检查是否有活跃的摄像头悬浮窗（补盲悬浮窗、常驻悬浮窗、副屏、超视模式、长视模式）正在使用摄像头。
     * 用于 MainActivity.onPause() 判断是否应该保持摄像头连接。
     */
    public static boolean hasActiveCameraWindows() {
        BlindSpotService inst = sInstance;
        if (inst == null) return false;
        return inst.mainFloatingWindowView != null
                || inst.secondaryFloatingView != null
                || inst.dedicatedBlindSpotWindow != null;
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
        if (!appConfig.isTurnSignalLinkageEnabled()) {
            AppLog.d(TAG, "转向灯联动和长视模式都未启用，跳过预创建窗口");
            return;
        }
        
        // 在后台线程预创建窗口
        new Thread(() -> {
            try {
                Thread.sleep(2000); // 延迟2秒，等待服务完全启动
                
                if (!WakeUpHelper.hasOverlayPermission(this)) {
                    AppLog.d(TAG, "无悬浮窗权限，跳过预创建窗口");
                    return;
                }
                
                mainHandler.post(() -> {
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

    /**
     * 显示盲区摄像头（用于 CarSignalManager API，不使用 debounce）
     */
    private void showBlindSpotCamera(String cameraPos) {
        // 全景影像避让：目标Activity在前台时不弹出补盲窗口
        if (isAvmAvoidanceActive) {
            AppLog.d(TAG, "全景影像避让中，忽略CarSignalManager转向灯信号: " + cameraPos);
            return;
        }

        AppLog.i(TAG, "🚦 转向灯触发摄像头: " + cameraPos);
        
        if (isMainTempShown) {
            AppLog.i(TAG, "🚦 检测到车门联动窗口，转向灯接管（优先级更高）");
            isMainTempShown = false;
        }
        
        // 取消隐藏计时器
        if (hideRunnable != null) {
            hideHandler.removeCallbacks(hideRunnable);
            hideRunnable = null;
            AppLog.d(TAG, "🚦 已取消隐藏计时器");
        }

        // 取消信号保活计时器（如果之前从其他模式切换过来）
        if (signalKeepAliveRunnable != null) {
            hideHandler.removeCallbacks(signalKeepAliveRunnable);
            signalKeepAliveRunnable = null;
        }

        if (cameraPos.equals(currentSignalCamera)) {
            AppLog.d(TAG, "转向灯相同，不重复切换: " + cameraPos);
            return;
        }

        currentSignalCamera = cameraPos;
        AppLog.i(TAG, "🚦 转向灯激活，设置 currentSignalCamera = " + cameraPos);

        // --- 1. 尽早创建窗口 UI（addView 触发布局，与后续 IPC 并行，Surface 就绪更快） ---
        boolean reuseMain = appConfig.isTurnSignalReuseMainFloating();

        if (reuseMain) {
            // 复用主屏悬浮窗
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
            }
            if (WakeUpHelper.hasOverlayPermission(this)) {
                mainFloatingWindowView = new MainFloatingWindowView(this, appConfig);
                mainFloatingWindowView.setDesiredCamera(cameraPos, true);
                mainFloatingWindowView.show();
                mainFloatingWindowView.updateStatusLabel(cameraPos);
                isMainTempShown = true;
                AppLog.d(TAG, "主屏开启临时补盲悬浮窗");
            }
        } else {
            // 使用独立补盲悬浮窗
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
                isMainTempShown = false;
            }
            if (dedicatedBlindSpotWindow != null) {
                dedicatedBlindSpotWindow.dismiss();
                dedicatedBlindSpotWindow = null;
            }
            dedicatedBlindSpotWindow = new BlindSpotFloatingWindowView(this, false);
            dedicatedBlindSpotWindow.setCameraPos(cameraPos);
            dedicatedBlindSpotWindow.show();
            dedicatedBlindSpotWindow.updateStatusLabel(cameraPos);
            // setCamera 需要 CameraManager，延后到初始化之后调用
        }

        // 副屏窗口预创建（addView 触发布局）
        if (appConfig.isSecondaryDisplayEnabled()) {
            if (secondaryFloatingView == null) {
                showSecondaryDisplay();
            }
        }

        // --- 2. 异步启动前台服务和初始化相机（与 UI 布局并行） ---
        CameraForegroundService.start(this, "补盲运行中", "正在显示补盲画面");
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().getOrInit(this);

        // --- 3. 提前打开相机（与 Surface 创建并行，节省 ~20-60ms） ---
        {
            MultiCameraManager cm = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
            if (cm != null) {
                SingleCamera cam = cm.getCamera(cameraPos);
                if (cam != null && !cam.isCameraOpened()) {
                    CameraForegroundService.whenReady(this, cam::openCameraDeferred);
                }
            }
        }

        // --- 4. 需要 CameraManager 的操作 ---
        if (!reuseMain && dedicatedBlindSpotWindow != null) {
            dedicatedBlindSpotWindow.setCamera(cameraPos);
        }

        // 显示转向箭头
        if (dedicatedBlindSpotWindow != null) {
            dedicatedBlindSpotWindow.showTurnSignalArrow(cameraPos);
        }
        if (mainFloatingWindowView != null) {
            mainFloatingWindowView.showTurnSignalArrow(cameraPos);
        }

        // 副屏摄像头预览
        if (appConfig.isSecondaryDisplayEnabled()) {
            startSecondaryCameraPreviewDirectly(cameraPos);
        }
    }

    private void handleTurnSignal(String cameraPos) {
        
        // 取消隐藏计时器
        if (hideRunnable != null) {
            hideHandler.removeCallbacks(hideRunnable);
            hideRunnable = null;
        }

        // 重置信号保活计时器（debounce）
        // 每次收到有效信号（value:1）都重置，超过 1.2 秒无新信号则认为转向灯已关闭
        resetSignalKeepAlive();

        if (cameraPos.equals(currentSignalCamera)) {
            AppLog.d(TAG, "转向灯相同，不重复切换: " + cameraPos);
            return;
        }

        currentSignalCamera = cameraPos;
        AppLog.d(TAG, "转向灯触发摄像头: " + cameraPos);

        // --- 1. 尽早创建窗口 UI（addView 触发布局，与后续 IPC 并行，Surface 就绪更快） ---
        boolean reuseMain = false;
        // 全景影像避让：目标Activity在前台时只跳过主屏窗口，副屏仍正常工作
        if (!isAvmAvoidanceActive) {
            reuseMain = appConfig.isTurnSignalReuseMainFloating();

            if (reuseMain) {
                // --- 复用主屏悬浮窗逻辑 ---
                // 切换方向时重建悬浮窗，确保窗口尺寸/旋转参数与新摄像头匹配
                if (mainFloatingWindowView != null) {
                    mainFloatingWindowView.dismiss();
                    mainFloatingWindowView = null;
                }
                if (WakeUpHelper.hasOverlayPermission(this)) {
                    mainFloatingWindowView = new MainFloatingWindowView(this, appConfig);
                    mainFloatingWindowView.setDesiredCamera(cameraPos, true);
                    mainFloatingWindowView.show();
                    mainFloatingWindowView.updateStatusLabel(cameraPos);
                    isMainTempShown = true;
                    AppLog.d(TAG, "主屏开启临时补盲悬浮窗");
                }
            } else {
                // --- 使用独立补盲悬浮窗逻辑 ---
                // 切换方向时重建悬浮窗
                if (mainFloatingWindowView != null) {
                    mainFloatingWindowView.dismiss();
                    mainFloatingWindowView = null;
                    isMainTempShown = false;
                }
                if (dedicatedBlindSpotWindow != null) {
                    dedicatedBlindSpotWindow.dismiss();
                    dedicatedBlindSpotWindow = null;
                }
                
                // 优先使用预创建的窗口
                if (cameraPos.equals("left") && preCreatedLeftWindow != null) {
                    dedicatedBlindSpotWindow = preCreatedLeftWindow;
                    preCreatedLeftWindow = null; // 清空引用，下次需要时重新预创建
                    AppLog.d(TAG, "✅ 使用预创建的左补盲窗口");
                } else if (cameraPos.equals("right") && preCreatedRightWindow != null) {
                    dedicatedBlindSpotWindow = preCreatedRightWindow;
                    preCreatedRightWindow = null; // 清空引用，下次需要时重新预创建
                    AppLog.d(TAG, "✅ 使用预创建的右补盲窗口");
                } else {
                    // 没有预创建窗口，新建一个
                    dedicatedBlindSpotWindow = new BlindSpotFloatingWindowView(this, false);
                    dedicatedBlindSpotWindow.setCameraPos(cameraPos);
                    AppLog.d(TAG, "⚠️ 未使用预创建窗口，新建窗口");
                }
                
                dedicatedBlindSpotWindow.show();
                dedicatedBlindSpotWindow.updateStatusLabel(cameraPos);
                // setCamera 需要 CameraManager，延后到初始化之后调用
                
                // 异步重新预创建窗口（为下次做准备）
                if (!isPreCreatedWindowsReady || preCreatedLeftWindow == null || preCreatedRightWindow == null) {
                    preCreateBlindSpotWindows();
                }
            }
        } else {
            AppLog.d(TAG, "全景影像避让中，跳过主屏窗口创建，副屏正常处理: " + cameraPos);
        }

        // --- 副屏窗口预创建（addView 触发布局） ---
        if (appConfig.isSecondaryDisplayEnabled()) {
            if (secondaryFloatingView == null) {
                showSecondaryDisplay();
            }
        }

        // --- 2. 异步启动前台服务和初始化相机（与 UI 布局并行） ---
        // 前台服务是后台访问摄像头的前提条件，但 addView 不需要它
        // 冷启动时 CameraForegroundService 可能还未启动，导致摄像头被系统 CAMERA_DISABLED 拦截
        CameraForegroundService.start(this, "补盲运行中", "正在显示补盲画面");

        // 确保摄像头已初始化（通过全局 Holder，不依赖 MainActivity）
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().getOrInit(this);

        // --- 3. 立即打开相机（不等待服务就绪，进一步减少延迟） ---
        {
            MultiCameraManager cm = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
            if (cm != null) {
                SingleCamera cam = cm.getCamera(cameraPos);
                if (cam != null && !cam.isCameraOpened()) {
                    // 直接打开相机，不等待服务就绪回调，减少延迟
                    cam.openCameraDeferred();
                    AppLog.d(TAG, "📷 立即打开相机: " + cameraPos);
                } else if (cam != null && cam.isCameraOpened()) {
                    AppLog.d(TAG, "📷 相机已打开: " + cameraPos);
                }
            }
        }

        // --- 4. 需要 CameraManager 的操作 ---
        // dedicatedBlindSpotWindow.setCamera() 需要 CameraManager 获取 previewSize
        // show() 之后 Surface 已就绪，可以立即绑定相机
        if (!isAvmAvoidanceActive && !reuseMain && dedicatedBlindSpotWindow != null) {
            mainHandler.post(() -> {
                if (dedicatedBlindSpotWindow != null) {
                    dedicatedBlindSpotWindow.setCamera(cameraPos);
                    AppLog.d(TAG, "📷 绑定相机到窗口: " + cameraPos);
                }
            });
        }

        // 显示转向箭头
        if (dedicatedBlindSpotWindow != null) {
            dedicatedBlindSpotWindow.showTurnSignalArrow(cameraPos);
        }
        if (mainFloatingWindowView != null) {
            mainFloatingWindowView.showTurnSignalArrow(cameraPos);
        }

        // --- 副屏摄像头预览 ---
        if (appConfig.isSecondaryDisplayEnabled()) {
            startSecondaryCameraPreviewDirectly(cameraPos);
        }
    }
    
    /**
     * 停止长视模式
     */

    private void startSecondaryCameraPreviewDirectly(String cameraPos) {
        secondaryDesiredCameraPos = cameraPos;
        BlindSpotCorrection.apply(secondaryTextureView, appConfig, cameraPos, appConfig.getSecondaryDisplayRotation());
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
            BlindSpotCorrection.apply(secondaryTextureView, appConfig, cameraPos, appConfig.getSecondaryDisplayRotation());
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
            AppLog.i(TAG, "🚦 清除 currentSignalCamera，车门联动恢复可用");
            
            // 恢复主屏悬浮窗状态
            if (isMainTempShown && mainFloatingWindowView != null) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
                isMainTempShown = false;
            } else if (mainFloatingWindowView != null) {
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
        if (mainFloatingWindowView != null || secondaryFloatingView != null) {
            return; // 仍有持久 Surface 在使用相机
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
        if (intent != null) {

            String action = intent.getStringExtra("action");
            if ("setup_blind_spot_window".equals(action)) {
                showBlindSpotSetupWindow();
                return START_STICKY;
            }
            if ("preview_blind_spot".equals(action)) {
                String cameraPos = intent.getStringExtra("camera_pos");
                if (cameraPos == null) cameraPos = "right";
                previewCameraPos = cameraPos;
                showPreviewWindow(cameraPos);
                updateWindows();
                return START_STICKY;
            }
            if ("stop_preview_blind_spot".equals(action)) {
                previewCameraPos = null;
                if (previewBlindSpotWindow != null) {
                    previewBlindSpotWindow.dismiss();
                    previewBlindSpotWindow = null;
                }
                updateWindows();
                return START_STICKY;
            }
            if ("enter_secondary_display_adjust".equals(action)) {
                isSecondaryAdjustMode = true;
                updateWindows();
                return START_STICKY;
            }
            if ("exit_secondary_display_adjust".equals(action)) {
                isSecondaryAdjustMode = false;
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
        ensureSignalObserversAlive();
        initAvmAvoidance();

        updateWindows();
        return START_STICKY;
    }

    private void showPreviewWindow(String cameraPos) {
        if (!WakeUpHelper.hasOverlayPermission(this)) return;

        if (previewBlindSpotWindow == null) {
            previewBlindSpotWindow = new BlindSpotFloatingWindowView(this, false);
            previewBlindSpotWindow.enableAdjustPreviewMode();
            previewBlindSpotWindow.setCameraPos(cameraPos); // 先设置摄像头位置，再 show
            previewBlindSpotWindow.show();
        }
        previewBlindSpotWindow.setCamera(cameraPos);

        if (appConfig.isSecondaryDisplayEnabled()) {
            if (secondaryFloatingView == null) {
                showSecondaryDisplay();
            }
            startSecondaryCameraPreviewDirectly(cameraPos);
        }
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
            isMainTempShown = false;
            {
                stopSelf();
            }
            return;
        }

        updateSecondaryDisplay();
        updateMainFloatingWindow();

        applyTransforms();
        
        if (isSecondaryAdjustMode
                || appConfig.isMainFloatingEnabled() // 加入主屏悬浮窗检查
                || appConfig.isTurnSignalLinkageEnabled() // 加入转向灯联动检查
                || appConfig.isAvmAvoidanceEnabled() // 全景影像避让
                || currentSignalCamera != null // 加入转向灯联动检查
                || previewCameraPos != null) {
            CameraForegroundService.start(this, "补盲运行中", "正在显示补盲画面");
        }
        
        // 如果两个功能都关闭了，可以考虑停止服务
        if (!isSecondaryAdjustMode
                && !appConfig.isMainFloatingEnabled()
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
            dedicatedBlindSpotWindow.applyTransformNow();
        }
        if (previewBlindSpotWindow != null) {
            previewBlindSpotWindow.applyTransformNow();
        }
        String secondaryCameraPos = currentSignalCamera != null ? currentSignalCamera : (previewCameraPos != null ? previewCameraPos : secondaryDesiredCameraPos);
        if (secondaryCameraPos != null) {
            BlindSpotCorrection.apply(secondaryTextureView, appConfig, secondaryCameraPos, appConfig.getSecondaryDisplayRotation());
        } else {
            BlindSpotCorrection.apply(secondaryTextureView, appConfig, null, appConfig.getSecondaryDisplayRotation());
        }
    }

    private void updateSecondaryDisplay() {
        boolean shouldShow = isSecondaryAdjustMode || (appConfig.isSecondaryDisplayEnabled() && (currentSignalCamera != null || previewCameraPos != null));

        if (!shouldShow) {
            removeSecondaryView();
            return;
        }

        int rawDisplayId = appConfig.getSecondaryDisplayId();
        int resolvedDisplayId = EvccDashcastDisplayResolver.resolve(this, rawDisplayId);
        if (resolvedDisplayId < 0) {
            // EVCC 仪表投屏占位符但 EVCC 没起投屏 — 暂不显示，等 EVCC 起来后下次触发再试
            if (secondaryFloatingView != null) removeSecondaryView();
            AppLog.w(TAG, "副屏选了 EVCC 仪表投屏但当前不可用（EVCC 未启动投屏？），跳过");
            return;
        }
        if (secondaryFloatingView != null && secondaryAttachedDisplayId != -1 && secondaryAttachedDisplayId != resolvedDisplayId) {
            removeSecondaryView();
        }

        if (secondaryFloatingView == null) {
            showSecondaryDisplay(resolvedDisplayId);
        } else {
            updateSecondaryDisplayLayout();
        }

        if (secondaryFloatingView != null) {
            if (isSecondaryAdjustMode) {
                stopSecondaryCameraPreview();
                if (secondaryBorderView != null) {
                    secondaryBorderView.setVisibility(View.VISIBLE);
                }
            } else if (appConfig.isSecondaryDisplayEnabled() && (currentSignalCamera != null || previewCameraPos != null)) {
                if (secondaryBorderView != null) {
                    secondaryBorderView.setVisibility(appConfig.isSecondaryDisplayBorderEnabled() ? View.VISIBLE : View.GONE);
                }
                String cameraPos = currentSignalCamera != null ? currentSignalCamera : previewCameraPos;
                if (cameraPos != null) {
                    startSecondaryCameraPreviewDirectly(cameraPos);
                }
            } else {
                stopSecondaryCameraPreview();
            }
        }
    }

    /**
     * 更新副屏悬浮窗的布局参数和旋转
     */
    private void updateSecondaryDisplayLayout() {
        if (secondaryFloatingView == null || secondaryWindowManager == null) return;

        int x = appConfig.getSecondaryDisplayX();
        int y = appConfig.getSecondaryDisplayY();
        int width = appConfig.getSecondaryDisplayWidth();
        int height = appConfig.getSecondaryDisplayHeight();
        int orientation = appConfig.getSecondaryDisplayOrientation();
        int rotation = appConfig.getSecondaryDisplayRotation();

        AppLog.d(TAG, "更新副屏布局: x=" + x + ", y=" + y + ", w=" + width + ", h=" + height + ", orientation=" + orientation);

        // 如果方向是 90 或 270 度，交换宽高
        int finalWidth = width;
        int finalHeight = height;
        if (orientation == 90 || orientation == 270) {
            finalWidth = height;
            finalHeight = width;
        }

        WindowManager.LayoutParams params = (WindowManager.LayoutParams) secondaryFloatingView.getLayoutParams();
        params.x = x;
        params.y = y;
        params.width = finalWidth > 0 ? finalWidth : WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = finalHeight > 0 ? finalHeight : WindowManager.LayoutParams.WRAP_CONTENT;

        secondaryWindowManager.updateViewLayout(secondaryFloatingView, params);
        secondaryFloatingView.setRotation(orientation);

        // 应用透明度
        float alpha = appConfig.getSecondaryDisplayAlpha() / 100f;
        secondaryFloatingView.setAlpha(alpha);

        String cameraPos = currentSignalCamera != null ? currentSignalCamera : (previewCameraPos != null ? previewCameraPos : secondaryDesiredCameraPos);
        BlindSpotCorrection.apply(secondaryTextureView, appConfig, cameraPos, rotation);
        
        // 设置边框
        if (secondaryBorderView != null) {
            if (isSecondaryAdjustMode) {
                secondaryBorderView.setVisibility(View.VISIBLE);
            } else {
                secondaryBorderView.setVisibility(appConfig.isSecondaryDisplayBorderEnabled() ? View.VISIBLE : View.GONE);
            }
        }
    }

    /** 无参版本：从配置读取 raw displayId 后做 EVCC 仪表投屏占位符解析。 */
    private void showSecondaryDisplay() {
        int rawDisplayId = appConfig.getSecondaryDisplayId();
        int resolved = EvccDashcastDisplayResolver.resolve(this, rawDisplayId);
        if (resolved < 0) {
            AppLog.w(TAG, "副屏选了 EVCC 仪表投屏但当前不可用，跳过显示");
            return;
        }
        showSecondaryDisplay(resolved);
    }

    private void showSecondaryDisplay(int displayId) {
        if (secondaryFloatingView != null) return; // 已经显示了

        Display display = displayManager.getDisplay(displayId);
        if (display == null) {
            AppLog.e(TAG, "找不到指定的副屏 Display ID: " + displayId);
            return;
        }
        secondaryAttachedDisplayId = displayId;

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

        // 设置边框
        secondaryBorderView.setVisibility(isSecondaryAdjustMode ? View.VISIBLE :
                (appConfig.isSecondaryDisplayBorderEnabled() ? View.VISIBLE : View.GONE));

        // 设置悬浮窗参数
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;

        int x = appConfig.getSecondaryDisplayX();
        int y = appConfig.getSecondaryDisplayY();
        int width = appConfig.getSecondaryDisplayWidth();
        int height = appConfig.getSecondaryDisplayHeight();
        int orientation = appConfig.getSecondaryDisplayOrientation();
        int rotation = appConfig.getSecondaryDisplayRotation();

        AppLog.d(TAG, "显示副屏: x=" + x + ", y=" + y + ", w=" + width + ", h=" + height + ", orientation=" + orientation + ", rotation=" + rotation);

        // 如果方向是 90 或 270 度，交换宽高
        int finalWidth = width;
        int finalHeight = height;
        if (orientation == 90 || orientation == 270) {
            finalWidth = height;
            finalHeight = width;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                finalWidth > 0 ? finalWidth : WindowManager.LayoutParams.WRAP_CONTENT,
                finalHeight > 0 ? finalHeight : WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = x;
        params.y = y;

        // 设置屏幕方向 (旋转整个容器)
        // 注意：某些车机系统对 WindowManager 根视图的 setRotation 支持有限
        // 我们尝试同时设置旋转和内部视图的变换
        secondaryFloatingView.setRotation(orientation);

        // 设置内容旋转 (将 orientation 和 rotation 结合处理)
        // 最终旋转角度 = 摄像头内容旋转 + 屏幕方向补偿
        String cameraPos = currentSignalCamera != null ? currentSignalCamera : (previewCameraPos != null ? previewCameraPos : secondaryDesiredCameraPos);
        BlindSpotCorrection.apply(secondaryTextureView, appConfig, cameraPos, rotation);

        secondaryTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int w, int h) {
                String cameraPos = null;
                if (appConfig.isSecondaryDisplayEnabled()) {
                    if (secondaryDesiredCameraPos != null) {
                        cameraPos = secondaryDesiredCameraPos;
                    } else if (previewCameraPos != null) {
                        cameraPos = previewCameraPos;
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
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int w, int h) {}

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
        float alpha = appConfig.getSecondaryDisplayAlpha() / 100f;
        secondaryFloatingView.setAlpha(alpha);

        try {
            secondaryWindowManager.addView(secondaryFloatingView, params);
        } catch (Exception e) {
            AppLog.e(TAG, "无法添加副屏悬浮窗: " + e.getMessage());
        }
    }

    private void updateMainFloatingWindow() {
        // 全景影像避让：目标Activity在前台时不显示主屏补盲窗口
        if (isAvmAvoidanceActive) {
            AppLog.d(TAG, "全景影像避让中，跳过主屏悬浮窗更新");
            return;
        }

        if (appConfig.isMainFloatingEnabled()) {
            isMainTempShown = false; // 用户开启
            if (mainFloatingWindowView == null) {
                if (WakeUpHelper.hasOverlayPermission(this)) {
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
        if (secondaryWindowManager != null && secondaryFloatingView != null) {
            try {
                secondaryWindowManager.removeView(secondaryFloatingView);
            } catch (Exception e) {
                // Ignore
            }
            secondaryFloatingView = null;
            secondaryTextureView = null;
            secondaryBorderView = null;
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
            AppLog.i(TAG, "全景影像避让：all 模式，主屏补盲窗口始终隐藏");
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
            }
            if (dedicatedBlindSpotWindow != null) {
                dedicatedBlindSpotWindow.dismiss();
                dedicatedBlindSpotWindow = null;
            }
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
            // 恢复窗口显示
            updateMainFloatingWindow();
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
                if (mainFloatingWindowView != null) {
                    mainFloatingWindowView.dismiss();
                    mainFloatingWindowView = null;
                }
                if (dedicatedBlindSpotWindow != null) {
                    dedicatedBlindSpotWindow.dismiss();
                    dedicatedBlindSpotWindow = null;
                }
            }
        } else if (isAvmAvoidanceActive) {
            // 两个条件都不满足：AVM 不在前台，EVCam 也不在前台
            avmDeactivateCount++;
            AppLog.d(TAG, "全景影像避让：未检测到前台 (" + avmDeactivateCount + "/" + AVM_DEACTIVATE_THRESHOLD + ")");
            if (avmDeactivateCount >= AVM_DEACTIVATE_THRESHOLD) {
                isAvmAvoidanceActive = false;
                avmDeactivateCount = 0;
                AppLog.i(TAG, "全景影像避让：" + targetActivity + " 已离开前台，恢复主屏补盲窗口");
                updateMainFloatingWindow();
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
