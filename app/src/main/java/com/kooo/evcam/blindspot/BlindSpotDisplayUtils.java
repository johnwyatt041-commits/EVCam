package com.kooo.evcam.blindspot;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.NonNull;

/**
 * 补盲屏幕尺寸与 Display 解析工具。
 * 用于主屏（含 2560×1600 车机屏）及外接副屏的真实像素尺寸获取，避免多处重复逻辑。
 */
public final class BlindSpotDisplayUtils {

    private BlindSpotDisplayUtils() {
    }

    /** 返回主屏真实像素尺寸 [widthPx, heightPx]；不可用时退化为 2560×1600。 */
    @NonNull
    public static int[] getMainScreenSizePx(@NonNull Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowMetrics metrics = wm.getMaximumWindowMetrics();
                    if (metrics != null) {
                        Rect bounds = metrics.getBounds();
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            return new int[]{bounds.width(), bounds.height()};
                        }
                    }
                }
                Display display = wm.getDefaultDisplay();
                if (display != null) {
                    Point size = new Point();
                    display.getRealSize(size);
                    if (size.x > 0 && size.y > 0) {
                        return new int[]{size.x, size.y};
                    }
                }
            }
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            if (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
                return new int[]{dm.widthPixels, dm.heightPixels};
            }
        } catch (Exception ignored) {
        }
        return new int[]{BlindSpotPlacement.BASE_CANVAS_WIDTH, BlindSpotPlacement.BASE_CANVAS_HEIGHT};
    }

    /** 获取指定 DisplayId 的像素宽高 [widthPx, heightPx]。 */
    @NonNull
    public static int[] getDisplaySizePx(@NonNull Context context, int displayId) {
        if (displayId <= 0) {
            return getMainScreenSizePx(context);
        }
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                Display target = dm.getDisplay(displayId);
                if (target != null) {
                    DisplayMetrics out = new DisplayMetrics();
                    target.getRealMetrics(out);
                    if (out.widthPixels > 0 && out.heightPixels > 0) {
                        return new int[]{out.widthPixels, out.heightPixels};
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new int[]{1920, 720};
    }
}
