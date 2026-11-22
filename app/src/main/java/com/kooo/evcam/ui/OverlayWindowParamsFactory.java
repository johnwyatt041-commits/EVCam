package com.kooo.evcam.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.KeepAliveAccessibilityService;
import com.kooo.evcam.WakeUpHelper;

/**
 * 统一的悬浮窗 WindowManager.LayoutParams 构建器与安全挂载句柄。
 *
 * 重点解决原生 SYSTEM/APPLICATION_OVERLAY 抢占焦点、拦截返回键与干扰输入法的问题，
 * 并在主屏开启无障碍且用户允许时优先使用 TYPE_ACCESSIBILITY_OVERLAY（2032），
 * 当无障碍解绑或 Token 异常时自动平滑降级为 TYPE_APPLICATION_OVERLAY（2038）或安全移除；
 * 副屏（EVCC）严格使用 DisplayContext + TYPE_APPLICATION_OVERLAY（2038）。
 */
public final class OverlayWindowParamsFactory {

    private static final String TAG = "OverlayWindowParams";

    public static final int TYPE_ACCESSIBILITY_OVERLAY = 2032; // WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    public static final int TYPE_APPLICATION_OVERLAY = 2038;   // WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    public static final int TYPE_PHONE_LEGACY = 2002;          // WindowManager.LayoutParams.TYPE_PHONE

    public static final int FLAG_NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    public static final int FLAG_NOT_TOUCH_MODAL = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    public static final int FLAG_ALT_FOCUSABLE_IM = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
    public static final int FLAG_LAYOUT_IN_SCREEN = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    public static final int FLAG_HARDWARE_ACCELERATED = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    public static final int FLAG_NOT_TOUCHABLE = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

    private OverlayWindowParamsFactory() {
    }

    /** 可测试的纯 Java 窗口规格描述。 */
    public static final class WindowSpec {
        public final int windowType;
        public final int flags;
        public final boolean usingAccessibilityOverlay;

        public WindowSpec(int windowType, int flags, boolean usingAccessibilityOverlay) {
            this.windowType = windowType;
            this.flags = flags;
            this.usingAccessibilityOverlay = usingAccessibilityOverlay;
        }
    }

    /** 已挂载悬浮窗的安全句柄：跟踪实际使用的 WindowManager 并在无障碍解绑时防崩溃清理/降级。 */
    public static final class AttachedOverlay {
        private final Context fallbackContext;
        private final WindowManager fallbackWm;
        private final View view;
        private final WindowManager.LayoutParams params;
        private final Runnable onDetachedFallback;
        private WindowManager activeWm;
        private boolean attached;
        private Runnable accessibilityDetachListener;

        AttachedOverlay(
                Context fallbackContext,
                WindowManager fallbackWm,
                View view,
                WindowManager.LayoutParams params,
                WindowManager activeWm,
                Runnable onDetachedFallback) {
            this.fallbackContext = fallbackContext;
            this.fallbackWm = fallbackWm;
            this.view = view;
            this.params = params;
            this.activeWm = activeWm;
            this.attached = true;
            this.onDetachedFallback = onDetachedFallback;

            if (isAccessibilityOverlayParams(params)) {
                this.accessibilityDetachListener = this::handleAccessibilityDetached;
                KeepAliveAccessibilityService.registerDetachListener(this.accessibilityDetachListener);
            }
        }

        private void handleAccessibilityDetached() {
            if (!attached || !isAccessibilityOverlayParams(params)) {
                return;
            }
            AppLog.w(TAG, "检测到无障碍服务解绑，安全清理/降级 TYPE_ACCESSIBILITY_OVERLAY 窗口");
            try {
                if (activeWm != null) {
                    activeWm.removeViewImmediate(view);
                }
            } catch (Exception ignored) {
                try {
                    if (activeWm != null) {
                        activeWm.removeView(view);
                    }
                } catch (Exception ignored2) {
                }
            }
            unregisterListener();
            if (fallbackContext != null && fallbackWm != null && WakeUpHelper.hasOverlayPermission(fallbackContext)) {
                params.type = fallbackOverlayType(Build.VERSION.SDK_INT);
                try {
                    fallbackWm.addView(view, params);
                    activeWm = fallbackWm;
                    AppLog.d(TAG, "悬浮窗已无缝降级为 TYPE_APPLICATION_OVERLAY");
                    return;
                } catch (Exception e) {
                    AppLog.e(TAG, "降级重新挂载悬浮窗失败: " + e.getMessage());
                }
            }
            attached = false;
            if (onDetachedFallback != null) {
                onDetachedFallback.run();
            }
        }

        private void unregisterListener() {
            if (accessibilityDetachListener != null) {
                KeepAliveAccessibilityService.unregisterDetachListener(accessibilityDetachListener);
                accessibilityDetachListener = null;
            }
        }

        public WindowManager.LayoutParams getParams() {
            return params;
        }

        public WindowManager getActiveWindowManager() {
            return activeWm != null ? activeWm : fallbackWm;
        }

        public void markDetached() {
            unregisterListener();
            attached = false;
        }

        public boolean isAttached() {
            return attached;
        }

        public boolean updateLayout() {
            if (!attached || activeWm == null) {
                return false;
            }
            try {
                activeWm.updateViewLayout(view, params);
                return true;
            } catch (Exception e) {
                AppLog.w(TAG, "更新悬浮窗布局失败: " + e.getMessage());
                return false;
            }
        }

        public void detach() {
            unregisterListener();
            if (!attached) {
                return;
            }
            attached = false;
            if (activeWm != null) {
                try {
                    activeWm.removeView(view);
                } catch (Exception e) {
                    AppLog.w(TAG, "移除悬浮窗失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 主屏交互式悬浮窗参数规格：始终不抢焦点、不模态拦触摸、不夺输入法焦点。
     */
    public static WindowSpec resolveMainOverlaySpec(
            boolean preferAccessibilityOverlay,
            boolean accessibilityServiceActive,
            int sdkInt,
            boolean touchable) {
        int windowType;
        boolean usingAccessibility = false;
        if (sdkInt >= Build.VERSION_CODES.O) {
            if (preferAccessibilityOverlay && accessibilityServiceActive) {
                windowType = TYPE_ACCESSIBILITY_OVERLAY;
                usingAccessibility = true;
            } else {
                windowType = TYPE_APPLICATION_OVERLAY;
            }
        } else {
            windowType = TYPE_PHONE_LEGACY;
        }

        int flags = FLAG_NOT_FOCUSABLE
                | FLAG_NOT_TOUCH_MODAL
                | FLAG_ALT_FOCUSABLE_IM
                | FLAG_LAYOUT_IN_SCREEN
                | FLAG_HARDWARE_ACCELERATED;
        if (!touchable) {
            flags |= FLAG_NOT_TOUCHABLE;
        }
        return new WindowSpec(windowType, flags, usingAccessibility);
    }

    /**
     * 轻量纯 2D 悬浮按钮规格（不含 TextureView 视频流）：
     * 有标准悬浮窗权限时优先使用 TYPE_APPLICATION_OVERLAY（2038），仅无悬浮窗权限且无障碍激活时回退 2032，
     * 并移除 FLAG_LAYOUT_IN_SCREEN 与 FLAG_HARDWARE_ACCELERATED，降低车机退后台时的整屏闪屏风险。
     */
    public static WindowSpec resolveLightweightMainOverlaySpec(
            boolean hasOverlayPermission,
            boolean preferAccessibilityOverlay,
            boolean accessibilityServiceActive,
            int sdkInt,
            boolean touchable) {
        int windowType;
        boolean usingAccessibility = false;
        if (sdkInt >= Build.VERSION_CODES.O) {
            if (!hasOverlayPermission && preferAccessibilityOverlay && accessibilityServiceActive) {
                windowType = TYPE_ACCESSIBILITY_OVERLAY;
                usingAccessibility = true;
            } else {
                windowType = TYPE_APPLICATION_OVERLAY;
            }
        } else {
            windowType = TYPE_PHONE_LEGACY;
        }

        int flags = FLAG_NOT_FOCUSABLE
                | FLAG_NOT_TOUCH_MODAL
                | FLAG_ALT_FOCUSABLE_IM;
        if (!touchable) {
            flags |= FLAG_NOT_TOUCHABLE;
        }
        return new WindowSpec(windowType, flags, usingAccessibility);
    }

    /**
     * 副屏（EVCC）专用窗口参数规格：必须使用 DisplayContext 的 TYPE_APPLICATION_OVERLAY，禁止 2032。
     */
    public static WindowSpec resolveSecondaryDisplayOverlaySpec(int sdkInt) {
        int windowType = sdkInt >= Build.VERSION_CODES.O
                ? TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        int flags = FLAG_NOT_FOCUSABLE
                | FLAG_NOT_TOUCH_MODAL
                | FLAG_ALT_FOCUSABLE_IM
                | FLAG_LAYOUT_IN_SCREEN
                | FLAG_HARDWARE_ACCELERATED;
        return new WindowSpec(windowType, flags, false);
    }

    public static int fallbackOverlayType(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.O ? TYPE_APPLICATION_OVERLAY : TYPE_PHONE_LEGACY;
    }

    public static boolean shouldUseAccessibilityOverlay(Context context) {
        if (context == null) {
            return false;
        }
        AppConfig config = new AppConfig(context);
        return config.isAccessibilityOverlayEnabled()
                && KeepAliveAccessibilityService.isAccessibilityServiceActive();
    }

    public static WindowSpec resolveMainOverlaySpec(Context context, boolean touchable) {
        return resolveMainOverlaySpec(
                shouldUseAccessibilityOverlay(context),
                KeepAliveAccessibilityService.isAccessibilityServiceActive(),
                Build.VERSION.SDK_INT,
                touchable
        );
    }

    public static WindowSpec resolveLightweightMainOverlaySpec(Context context, boolean touchable) {
        boolean hasOverlayPermission = context != null && WakeUpHelper.hasOverlayPermission(context);
        return resolveLightweightMainOverlaySpec(
                hasOverlayPermission,
                shouldUseAccessibilityOverlay(context),
                KeepAliveAccessibilityService.isAccessibilityServiceActive(),
                Build.VERSION.SDK_INT,
                touchable
        );
    }

    public static WindowManager.LayoutParams createMainParams(
            Context context,
            int width,
            int height,
            int x,
            int y,
            boolean touchable) {
        WindowSpec spec = resolveMainOverlaySpec(context, touchable);
        return buildLayoutParams(spec, width, height, x, y);
    }

    public static WindowManager.LayoutParams createLightweightMainParams(
            Context context,
            int width,
            int height,
            int x,
            int y,
            boolean touchable) {
        WindowSpec spec = resolveLightweightMainOverlaySpec(context, touchable);
        return buildLayoutParams(spec, width, height, x, y);
    }

    private static WindowManager.LayoutParams buildLayoutParams(
            WindowSpec spec,
            int width,
            int height,
            int x,
            int y) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                spec.windowType,
                spec.flags,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = x;
        params.y = y;
        return params;
    }

    public static WindowManager.LayoutParams createSecondaryDisplayParams(
            int width,
            int height,
            int x,
            int y) {
        WindowSpec spec = resolveSecondaryDisplayOverlaySpec(Build.VERSION.SDK_INT);
        return buildLayoutParams(spec, width, height, x, y);
    }

    /**
     * 安全添加主屏悬浮窗：当 TYPE_ACCESSIBILITY_OVERLAY 可用时使用无障碍 WindowManager 挂载；
     * 若 Token 失效或无权限，自动无缝降级到 TYPE_APPLICATION_OVERLAY。
     */
    public static AttachedOverlay attachMainOverlay(
            Context context,
            WindowManager fallbackWm,
            View view,
            WindowManager.LayoutParams params,
            Runnable onDetachedFallback) {
        if (context == null || fallbackWm == null || view == null || params == null) {
            return null;
        }
        WindowSpec desiredSpec = resolveMainOverlaySpec(context, (params.flags & FLAG_NOT_TOUCHABLE) == 0);
        return attachWithSpec(context, fallbackWm, view, params, desiredSpec, onDetachedFallback);
    }

    /**
     * 安全添加轻量纯 2D 主屏悬浮窗：不升级为 2032/硬件加速全屏层，减少车机 HWC 合成抖动。
     */
    public static AttachedOverlay attachLightweightMainOverlay(
            Context context,
            WindowManager fallbackWm,
            View view,
            WindowManager.LayoutParams params,
            Runnable onDetachedFallback) {
        if (context == null || fallbackWm == null || view == null || params == null) {
            return null;
        }
        WindowSpec desiredSpec = resolveLightweightMainOverlaySpec(context, (params.flags & FLAG_NOT_TOUCHABLE) == 0);
        return attachWithSpec(context, fallbackWm, view, params, desiredSpec, onDetachedFallback);
    }

    private static AttachedOverlay attachWithSpec(
            Context context,
            WindowManager fallbackWm,
            View view,
            WindowManager.LayoutParams params,
            WindowSpec desiredSpec,
            Runnable onDetachedFallback) {
        params.type = desiredSpec.windowType;
        params.flags = desiredSpec.flags;
        if (isAccessibilityOverlayParams(params)) {
            KeepAliveAccessibilityService acc = KeepAliveAccessibilityService.getInstance();
            if (acc != null && KeepAliveAccessibilityService.isAccessibilityServiceActive()) {
                WindowManager accWm = (WindowManager) acc.getSystemService(Context.WINDOW_SERVICE);
                if (accWm != null) {
                    try {
                        accWm.addView(view, params);
                        return new AttachedOverlay(context, fallbackWm, view, params, accWm, onDetachedFallback);
                    } catch (RuntimeException e) {
                        AppLog.w(TAG, "TYPE_ACCESSIBILITY_OVERLAY 挂载失败，降级为 TYPE_APPLICATION_OVERLAY: " + e.getMessage());
                    }
                }
            }
            params.type = fallbackOverlayType(Build.VERSION.SDK_INT);
        }
        try {
            fallbackWm.addView(view, params);
            return new AttachedOverlay(context, fallbackWm, view, params, fallbackWm, onDetachedFallback);
        } catch (RuntimeException e) {
            AppLog.e(TAG, "悬浮窗挂载失败: " + e.getMessage(), e);
            return null;
        }
    }

    public static void detachSafely(AttachedOverlay handle) {
        if (handle != null) {
            handle.detach();
        }
    }

    public static boolean isAccessibilityOverlayParams(WindowManager.LayoutParams params) {
        return params != null && params.type == TYPE_ACCESSIBILITY_OVERLAY;
    }
}
