package com.kooo.evcam.blindspot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 补盲可视化画面位置与尺寸的数据模型（纯 Java，可直接走 JVM 单测）。
 *
 * <p>以主屏 2560×1600 (16:10) 作为设计基准画布，用归一化坐标 (x, y, width, height ∈ [0, 1])
 * 表示前/后/左/右以及雨夜左/雨夜右 6 个槽位的位置与大小，并在目标显示屏幕上换算为真实像素。</p>
 */
public final class BlindSpotPlacement {

    public static final int BASE_CANVAS_WIDTH = 2560;
    public static final int BASE_CANVAS_HEIGHT = 1600;

    public static final float MIN_WIDTH_RATIO = 0.15f;
    public static final float MIN_HEIGHT_RATIO = 0.15f;

    public static final String SLOT_FRONT = "front";
    public static final String SLOT_BACK = "back";
    public static final String SLOT_LEFT = "left";
    public static final String SLOT_RIGHT = "right";
    public static final String SLOT_RAIN_LEFT = "rain_left";
    public static final String SLOT_RAIN_RIGHT = "rain_right";

    public static final List<String> ALL_SLOTS = Collections.unmodifiableList(Arrays.asList(
            SLOT_FRONT,
            SLOT_BACK,
            SLOT_LEFT,
            SLOT_RIGHT,
            SLOT_RAIN_LEFT,
            SLOT_RAIN_RIGHT
    ));

    private BlindSpotPlacement() {
    }

    /** 归一化矩形比例 [0, 1]。 */
    public static final class RectRatio {
        public final float x;
        public final float y;
        public final float width;
        public final float height;

        public RectRatio(float x, float y, float width, float height) {
            float safeW = clamp(Float.isNaN(width) ? MIN_WIDTH_RATIO : width, MIN_WIDTH_RATIO, 1.0f);
            float safeH = clamp(Float.isNaN(height) ? MIN_HEIGHT_RATIO : height, MIN_HEIGHT_RATIO, 1.0f);
            float safeX = clamp(Float.isNaN(x) ? 0f : x, 0f, Math.max(0f, 1.0f - safeW));
            float safeY = clamp(Float.isNaN(y) ? 0f : y, 0f, Math.max(0f, 1.0f - safeH));
            this.x = safeX;
            this.y = safeY;
            this.width = safeW;
            this.height = safeH;
        }

        public int xPercent() {
            return Math.round(x * 100f);
        }

        public int yPercent() {
            return Math.round(y * 100f);
        }

        public int widthPercent() {
            return Math.round(width * 100f);
        }

        public int heightPercent() {
            return Math.round(height * 100f);
        }

        /** 按目标屏幕像素尺寸换算为 [x, y, width, height]。 */
        public int[] toPixelBounds(int displayWidthPx, int displayHeightPx) {
            int safeDisplayW = displayWidthPx > 0 ? displayWidthPx : BASE_CANVAS_WIDTH;
            int safeDisplayH = displayHeightPx > 0 ? displayHeightPx : BASE_CANVAS_HEIGHT;
            int wPx = Math.max(1, Math.min(safeDisplayW, Math.round(width * safeDisplayW)));
            int hPx = Math.max(1, Math.min(safeDisplayH, Math.round(height * safeDisplayH)));
            int xPx = Math.max(0, Math.min(safeDisplayW - wPx, Math.round(x * safeDisplayW)));
            int yPx = Math.max(0, Math.min(safeDisplayH - hPx, Math.round(y * safeDisplayH)));
            return new int[]{xPx, yPx, wPx, hPx};
        }

        /** 按当前偏移增量移动，自动约束在画布 [0, 1] 内。 */
        public RectRatio movedBy(float dxRatio, float dyRatio) {
            return new RectRatio(x + dxRatio, y + dyRatio, width, height);
        }

        /** 从右下角缩放，保持左上角锚点不变并约束在画布边界及最小尺寸内。 */
        public RectRatio resizedRightBottomBy(float dwRatio, float dhRatio) {
            float maxW = Math.max(MIN_WIDTH_RATIO, 1.0f - x);
            float maxH = Math.max(MIN_HEIGHT_RATIO, 1.0f - y);
            float newW = clamp(width + dwRatio, MIN_WIDTH_RATIO, maxW);
            float newH = clamp(height + dhRatio, MIN_HEIGHT_RATIO, maxH);
            return new RectRatio(x, y, newW, newH);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RectRatio)) return false;
            RectRatio other = (RectRatio) obj;
            return Math.abs(x - other.x) < 1e-4f
                    && Math.abs(y - other.y) < 1e-4f
                    && Math.abs(width - other.width) < 1e-4f
                    && Math.abs(height - other.height) < 1e-4f;
        }
    }

    /** 将真实像素坐标换算为归一化 RectRatio；displayW/H 非法时退化为 2560×1600。 */
    @NonNull
    public static RectRatio fromPixels(int pxX, int pxY, int pxW, int pxH, int displayWidthPx, int displayHeightPx) {
        int safeDisplayW = displayWidthPx > 0 ? displayWidthPx : BASE_CANVAS_WIDTH;
        int safeDisplayH = displayHeightPx > 0 ? displayHeightPx : BASE_CANVAS_HEIGHT;
        float nx = (float) pxX / safeDisplayW;
        float ny = (float) pxY / safeDisplayH;
        float nw = (float) pxW / safeDisplayW;
        float nh = (float) pxH / safeDisplayH;
        return new RectRatio(nx, ny, nw, nh);
    }

    @NonNull
    public static String normalizeSlot(@Nullable String slot) {
        if (slot == null) return SLOT_LEFT;
        switch (slot.trim().toLowerCase()) {
            case SLOT_FRONT:
                return SLOT_FRONT;
            case SLOT_BACK:
                return SLOT_BACK;
            case SLOT_LEFT:
                return SLOT_LEFT;
            case SLOT_RIGHT:
                return SLOT_RIGHT;
            case SLOT_RAIN_LEFT:
                return SLOT_RAIN_LEFT;
            case SLOT_RAIN_RIGHT:
                return SLOT_RAIN_RIGHT;
            default:
                return SLOT_LEFT;
        }
    }

    /** 根据摄像头方位与雨夜状态解析对应视觉槽位。 */
    @NonNull
    public static String resolveSlotForCamera(@Nullable String cameraPos, boolean rainNightActive) {
        String norm = cameraPos == null ? SLOT_LEFT : cameraPos.trim().toLowerCase();
        if (SLOT_RAIN_LEFT.equals(norm) || SLOT_RAIN_RIGHT.equals(norm)) {
            return norm;
        }
        if (rainNightActive) {
            if (SLOT_LEFT.equals(norm)) return SLOT_RAIN_LEFT;
            if (SLOT_RIGHT.equals(norm)) return SLOT_RAIN_RIGHT;
        }
        switch (norm) {
            case SLOT_FRONT:
            case SLOT_BACK:
            case SLOT_LEFT:
            case SLOT_RIGHT:
                return norm;
            default:
                return SLOT_LEFT;
        }
    }

    /** 普通左右槽位判断（雨夜左右不参与“左右使用同一位置”联动）。 */
    public static boolean isNormalSideSlot(@Nullable String slot) {
        String s = normalizeSlot(slot);
        return SLOT_LEFT.equals(s) || SLOT_RIGHT.equals(s);
    }

    @Nullable
    public static String oppositeNormalSideSlot(@Nullable String slot) {
        String s = normalizeSlot(slot);
        if (SLOT_LEFT.equals(s)) return SLOT_RIGHT;
        if (SLOT_RIGHT.equals(s)) return SLOT_LEFT;
        return null;
    }

    @NonNull
    public static String getSlotDisplayName(@Nullable String slot) {
        switch (normalizeSlot(slot)) {
            case SLOT_FRONT:
                return "前";
            case SLOT_BACK:
                return "后";
            case SLOT_LEFT:
                return "左";
            case SLOT_RIGHT:
                return "右";
            case SLOT_RAIN_LEFT:
                return "雨夜左";
            case SLOT_RAIN_RIGHT:
                return "雨夜右";
            default:
                return "左";
        }
    }

    @NonNull
    public static RectRatio defaultForSlot(@Nullable String slot) {
        switch (normalizeSlot(slot)) {
            case SLOT_FRONT:
                return new RectRatio(0.29f, 0.04f, 0.42f, 0.46f);
            case SLOT_BACK:
                return new RectRatio(0.29f, 0.50f, 0.42f, 0.46f);
            case SLOT_LEFT:
                return new RectRatio(0.02f, 0.20f, 0.42f, 0.60f);
            case SLOT_RIGHT:
                return new RectRatio(0.56f, 0.20f, 0.42f, 0.60f);
            case SLOT_RAIN_LEFT:
                return new RectRatio(0.20f, 0.15f, 0.60f, 0.70f);
            case SLOT_RAIN_RIGHT:
                return new RectRatio(0.20f, 0.15f, 0.60f, 0.70f);
            default:
                return new RectRatio(0.02f, 0.20f, 0.42f, 0.60f);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
