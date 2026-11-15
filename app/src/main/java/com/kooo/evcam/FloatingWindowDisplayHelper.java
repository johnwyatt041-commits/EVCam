package com.kooo.evcam;

import android.view.View;
import android.view.WindowManager;

/**
 * 悬浮窗无动画显隐与首帧抗闪烁辅助类。
 * 统一服务于补盲悬浮窗与主屏常驻悬浮窗，消除开启动效残留与重复布局更新代码。
 */
final class FloatingWindowDisplayHelper {

    private FloatingWindowDisplayHelper() {
    }

    /** 立即以完全不透明、无缩放状态展示已附加到 WindowManager 的悬浮窗。 */
    static void revealNow(View view, WindowManager windowManager, WindowManager.LayoutParams params) {
        if (view == null || params == null) {
            return;
        }
        view.setScaleX(1f);
        view.setScaleY(1f);
        params.alpha = 1f;
        try {
            if (view.getParent() != null && windowManager != null) {
                windowManager.updateViewLayout(view, params);
            }
        } catch (Exception ignored) {
        }
    }

    /** 立即将悬浮窗从 WindowManager 中移除（无关闭动效延迟）。 */
    static void dismissNow(View view, WindowManager windowManager, WindowManager.LayoutParams params) {
        if (view == null || params == null) {
            return;
        }
        params.alpha = 1f;
        if (view.getParent() == null || windowManager == null) {
            return;
        }
        try {
            windowManager.removeView(view);
        } catch (Exception ignored) {
        }
    }
}
