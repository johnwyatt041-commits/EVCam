package com.kooo.evcam.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure normalized preview layout math for editable multi-mode views (no Android View dependencies).
 */
public final class PreviewLayoutModel {
    public static final int LEFT = 0, FRONT = 1, BACK = 2, RIGHT = 3;
    public static final int PANE_COUNT = 4;
    public static final float MIN_SIZE = 0.18f;

    public static final int PRESET_THREE_COL_SIDE_BLIND = 0;
    public static final int PRESET_GRID_2X2 = 1;
    public static final int PRESET_FRONT_LARGE = 2;
    public static final int PRESET_MODE_B_THREE_COL = 3;
    public static final int PRESET_MODE_C_FRONT = 4;
    public static final int PRESET_MODE_C_BACK = 5;
    public static final int PRESET_MODE_D_DUAL = 6;
    public static final int PRESET_MODE_E_SIDES = 7;

    public static final float MODE_C_DEFAULT_MAIN_WIDTH = 0.64f;
    public static final float MODE_C_DEFAULT_SIDE_WIDTH = 0.18f;
    private static final float OVERLAP_EPS = 0.002f;
    private static final float SWAP_OVERLAP_THRESHOLD = 0.30f;

    public static final class Pane {
        public float x, y, width, height;

        private Pane(float x, float y, float width, float height) {
            set(x, y, width, height);
        }

        private void set(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /** Computed effective rectangles and per-pane visibility after applying mode rules and reflow. */
    public static final class EffectiveLayout {
        public final Pane[] panes = new Pane[PANE_COUNT];
        public final boolean[] visible = new boolean[PANE_COUNT];

        private EffectiveLayout() {
            for (int i = 0; i < PANE_COUNT; i++) {
                panes[i] = new Pane(0f, 0f, 0.5f, 0.5f);
            }
        }
    }

    private final Pane[] panes = new Pane[PANE_COUNT];

    public PreviewLayoutModel() {
        for (int i = 0; i < PANE_COUNT; i++) panes[i] = new Pane(0, 0, .5f, .5f);
        preset(PRESET_THREE_COL_SIDE_BLIND);
    }

    public PreviewLayoutModel(int initialPreset) {
        for (int i = 0; i < PANE_COUNT; i++) panes[i] = new Pane(0, 0, .5f, .5f);
        preset(initialPreset);
    }

    public Pane pane(int index) {
        return panes[index];
    }

    public void preset(int preset) {
        if (preset == PRESET_GRID_2X2) {
            panes[LEFT].set(0f, 0f, .5f, .5f);
            panes[RIGHT].set(.5f, 0f, .5f, .5f);
            panes[FRONT].set(0f, .5f, .5f, .5f);
            panes[BACK].set(.5f, .5f, .5f, .5f);
        } else if (preset == PRESET_FRONT_LARGE) {
            panes[FRONT].set(0f, 0f, 1f, .6f);
            panes[LEFT].set(0f, .6f, .33f, .4f);
            panes[BACK].set(.33f, .6f, .34f, .4f);
            panes[RIGHT].set(.67f, .6f, .33f, .4f);
        } else if (preset == PRESET_MODE_B_THREE_COL) {
            panes[FRONT].set(0f, 0f, .40f, .5f);
            panes[BACK].set(0f, .5f, .40f, .5f);
            panes[LEFT].set(.40f, 0f, .30f, 1f);
            panes[RIGHT].set(.70f, 0f, .30f, 1f);
        } else if (preset == PRESET_MODE_C_FRONT || preset == PRESET_MODE_C_BACK) {
            panes[FRONT].set(0f, 0f, MODE_C_DEFAULT_MAIN_WIDTH, 1f);
            panes[BACK].set(0f, 0f, MODE_C_DEFAULT_MAIN_WIDTH, 1f);
            panes[LEFT].set(MODE_C_DEFAULT_MAIN_WIDTH, 0f, MODE_C_DEFAULT_SIDE_WIDTH, 1f);
            panes[RIGHT].set(MODE_C_DEFAULT_MAIN_WIDTH + MODE_C_DEFAULT_SIDE_WIDTH, 0f, MODE_C_DEFAULT_SIDE_WIDTH, 1f);
        } else if (preset == PRESET_MODE_D_DUAL) {
            panes[FRONT].set(0f, 0f, .50f, 1f);
            panes[BACK].set(0f, 0f, .50f, 1f);
            panes[LEFT].set(.50f, 0f, .50f, 1f);
            panes[RIGHT].set(.50f, 0f, .50f, 1f);
        } else if (preset == PRESET_MODE_E_SIDES) {
            panes[LEFT].set(0f, 0f, .50f, 1f);
            panes[RIGHT].set(.50f, 0f, .50f, 1f);
            panes[FRONT].set(0f, 0f, .50f, 1f);
            panes[BACK].set(.50f, 0f, .50f, 1f);
        } else {
            panes[LEFT].set(0f, 0f, .3f, 1f);
            panes[RIGHT].set(.7f, 0f, .3f, 1f);
            panes[FRONT].set(.3f, 0f, .4f, .5f);
            panes[BACK].set(.3f, .5f, .4f, .5f);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public void move(int index, float x, float y) {
        if (index < 0 || index >= PANE_COUNT || !Float.isFinite(x) || !Float.isFinite(y)) return;
        Pane pane = panes[index];
        pane.x = clamp(x, 0f, 1f - pane.width);
        pane.y = clamp(y, 0f, 1f - pane.height);
    }

    public void resize(int index, float width, float height) {
        if (index < 0 || index >= PANE_COUNT || !Float.isFinite(width) || !Float.isFinite(height)) return;
        Pane pane = panes[index];
        pane.width = clamp(width, MIN_SIZE, 1f - pane.x);
        pane.height = clamp(height, MIN_SIZE, 1f - pane.y);
    }

    /**
     * 拖动画框时执行碰撞检测与自动让位/互换，保证活跃画框间互不重叠。
     * 若拖动块进入相邻块重心或显著重叠区，触发让位/互换（如把 A 拖到 B 右侧时，B 自动挤回 A 左侧）；
     * 轻微触碰边界且未达换位阈值时先贴边阻挡，避免画面互相遮挡。
     */
    public void movePaneWithCollision(int movingIndex, float targetX, float targetY,
                                      boolean[] activeMask, float[] dragOriginSnapshot) {
        if (movingIndex < 0 || movingIndex >= PANE_COUNT || !Float.isFinite(targetX) || !Float.isFinite(targetY)) {
            return;
        }
        boolean[] mask = normalizeMask(activeMask, movingIndex);
        Pane moving = panes[movingIndex];
        float w = clamp(moving.width, MIN_SIZE, 1f);
        float h = clamp(moving.height, MIN_SIZE, 1f);
        float prevX = moving.x;
        float prevY = moving.y;
        float origX = (dragOriginSnapshot != null && dragOriginSnapshot.length == 16)
                ? dragOriginSnapshot[movingIndex * 4] : prevX;
        float origY = (dragOriginSnapshot != null && dragOriginSnapshot.length == 16)
                ? dragOriginSnapshot[movingIndex * 4 + 1] : prevY;

        float nx = clamp(targetX, 0f, 1f - w);
        float ny = clamp(targetY, 0f, 1f - h);
        Pane candidate = new Pane(nx, ny, w, h);

        List<Integer> colliding = new ArrayList<>();
        for (int i = 0; i < PANE_COUNT; i++) {
            if (i == movingIndex || !mask[i]) continue;
            if (intersects(candidate, panes[i])) {
                colliding.add(i);
            }
        }
        if (colliding.isEmpty()) {
            moving.set(nx, ny, w, h);
            return;
        }

        boolean shouldSwapOrPush = false;
        for (int otherIdx : colliding) {
            Pane other = panes[otherIdx];
            float interArea = intersectionArea(candidate, other);
            float minArea = Math.min(candidate.width * candidate.height, other.width * other.height);
            if (minArea > 0f && interArea / minArea >= SWAP_OVERLAP_THRESHOLD) {
                shouldSwapOrPush = true;
                break;
            }
            float candCx = candidate.x + candidate.width * 0.5f;
            float candCy = candidate.y + candidate.height * 0.5f;
            float otherCx = other.x + other.width * 0.5f;
            float otherCy = other.y + other.height * 0.5f;
            boolean crossedX = (origX < other.x && candCx >= otherCx - 0.08f)
                    || (origX > other.x && candCx <= otherCx + 0.08f);
            boolean crossedY = (origY < other.y && candCy >= otherCy - 0.08f)
                    || (origY > other.y && candCy <= otherCy + 0.08f);
            if (crossedX || crossedY) {
                shouldSwapOrPush = true;
                break;
            }
        }

        if (!shouldSwapOrPush) {
            // 未到换位阈值：尝试沿移动轴贴紧不重叠位置
            Pane clamped = clampNonOverlapping(candidate, movingIndex, prevX, prevY, mask);
            moving.set(clamped.x, clamped.y, clamped.width, clamped.height);
            return;
        }

        moving.set(nx, ny, w, h);
        for (int otherIdx : colliding) {
            if (intersects(moving, panes[otherIdx])) {
                yieldOtherPane(moving, otherIdx, origX, origY, mask);
            }
        }
        resolveRemainingOverlaps(movingIndex, mask);
    }

    /**
     * 调整画框大小时执行碰撞挤压与尺寸约束，保证活跃画框间互不重叠且不低于 MIN_SIZE。
     */
    public void resizePaneWithCollision(int resizingIndex, float targetW, float targetH, boolean[] activeMask) {
        if (resizingIndex < 0 || resizingIndex >= PANE_COUNT || !Float.isFinite(targetW) || !Float.isFinite(targetH)) {
            return;
        }
        boolean[] mask = normalizeMask(activeMask, resizingIndex);
        Pane pane = panes[resizingIndex];
        float oldW = pane.width;
        float oldH = pane.height;
        float newW = clamp(targetW, MIN_SIZE, 1f - pane.x);
        float newH = clamp(targetH, MIN_SIZE, 1f - pane.y);
        Pane candidate = new Pane(pane.x, pane.y, newW, newH);

        for (int i = 0; i < PANE_COUNT; i++) {
            if (i == resizingIndex || !mask[i]) continue;
            Pane other = panes[i];
            if (!intersects(candidate, other)) continue;

            float otherCx = other.x + other.width * 0.5f;
            float otherCy = other.y + other.height * 0.5f;
            float oldRight = pane.x + oldW;
            float oldBottom = pane.y + oldH;
            boolean pushRight = other.x >= oldRight - 0.04f || otherCx >= oldRight;
            boolean pushDown = other.y >= oldBottom - 0.04f || otherCy >= oldBottom;

            if (pushRight && (!pushDown || (candidate.x + newW - other.x) <= (candidate.y + newH - other.y))) {
                float maxAllowRight = Math.max(pane.x + MIN_SIZE, 1f - MIN_SIZE);
                if (candidate.x + newW > maxAllowRight) {
                    newW = Math.max(MIN_SIZE, maxAllowRight - pane.x);
                    candidate.width = newW;
                }
                float nextX = candidate.x + newW;
                float nextW = clamp(other.x + other.width - nextX, MIN_SIZE, 1f - nextX);
                if (nextX + nextW > 1f) {
                    nextW = Math.max(MIN_SIZE, 1f - nextX);
                }
                other.set(nextX, other.y, nextW, other.height);
            } else if (pushDown) {
                float maxAllowBottom = Math.max(pane.y + MIN_SIZE, 1f - MIN_SIZE);
                if (candidate.y + newH > maxAllowBottom) {
                    newH = Math.max(MIN_SIZE, maxAllowBottom - pane.y);
                    candidate.height = newH;
                }
                float nextY = candidate.y + newH;
                float nextH = clamp(other.y + other.height - nextY, MIN_SIZE, 1f - nextY);
                if (nextY + nextH > 1f) {
                    nextH = Math.max(MIN_SIZE, 1f - nextY);
                }
                other.set(other.x, nextY, other.width, nextH);
            } else {
                // 其他方位重合（例如对方在左上方）：截断到不碰触边界
                if (other.x + other.width > candidate.x && other.x < candidate.x) {
                    float avail = candidate.x - other.x;
                    if (avail >= MIN_SIZE) {
                        other.width = avail;
                    } else {
                        newW = oldW;
                        candidate.width = newW;
                    }
                }
                if (other.y + other.height > candidate.y && other.y < candidate.y) {
                    float avail = candidate.y - other.y;
                    if (avail >= MIN_SIZE) {
                        other.height = avail;
                    } else {
                        newH = oldH;
                        candidate.height = newH;
                    }
                }
            }
        }
        pane.set(candidate.x, candidate.y, candidate.width, candidate.height);
        resolveRemainingOverlaps(resizingIndex, mask);
    }

    public static boolean hasAnyOverlap(Pane[] targetPanes, boolean[] activeMask) {
        if (targetPanes == null || targetPanes.length < PANE_COUNT) return false;
        for (int i = 0; i < PANE_COUNT; i++) {
            if (activeMask != null && i < activeMask.length && !activeMask[i]) continue;
            for (int j = i + 1; j < PANE_COUNT; j++) {
                if (activeMask != null && j < activeMask.length && !activeMask[j]) continue;
                if (intersects(targetPanes[i], targetPanes[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean[] normalizeMask(boolean[] activeMask, int fallbackIndex) {
        if (activeMask == null || activeMask.length < PANE_COUNT) {
            return new boolean[]{true, true, true, true};
        }
        boolean[] copy = new boolean[PANE_COUNT];
        System.arraycopy(activeMask, 0, copy, 0, PANE_COUNT);
        copy[clampIndex(fallbackIndex)] = true;
        return copy;
    }

    private static boolean intersects(Pane a, Pane b) {
        float ow = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
        float oh = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
        return ow > OVERLAP_EPS && oh > OVERLAP_EPS;
    }

    private static float intersectionArea(Pane a, Pane b) {
        float ow = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
        float oh = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
        if (ow <= 0f || oh <= 0f) return 0f;
        return ow * oh;
    }

    private Pane clampNonOverlapping(Pane desired, int movingIndex, float prevX, float prevY, boolean[] mask) {
        Pane tryX = new Pane(desired.x, prevY, desired.width, desired.height);
        boolean xBad = false;
        for (int i = 0; i < PANE_COUNT; i++) {
            if (i == movingIndex || !mask[i]) continue;
            if (intersects(tryX, panes[i])) { xBad = true; break; }
        }
        Pane tryY = new Pane(prevX, desired.y, desired.width, desired.height);
        boolean yBad = false;
        for (int i = 0; i < PANE_COUNT; i++) {
            if (i == movingIndex || !mask[i]) continue;
            if (intersects(tryY, panes[i])) { yBad = true; break; }
        }
        if (!xBad) return tryX;
        if (!yBad) return tryY;
        return new Pane(prevX, prevY, desired.width, desired.height);
    }

    private void yieldOtherPane(Pane moving, int otherIdx, float origX, float origY, boolean[] mask) {
        Pane other = panes[otherIdx];
        float movingCx = moving.x + moving.width * 0.5f;
        float otherCx = other.x + other.width * 0.5f;
        float movingCy = moving.y + moving.height * 0.5f;
        float otherCy = other.y + other.height * 0.5f;
        float dx = moving.x - origX;
        float dy = moving.y - origY;

        List<Pane> candidates = new ArrayList<>(6);
        boolean preferHorizontal = Math.abs(dx) >= Math.abs(dy) || Math.abs(movingCx - otherCx) >= Math.abs(movingCy - otherCy);
        boolean pushLeftFirst = dx > 0.01f || (Math.abs(dx) <= 0.01f && movingCx >= otherCx);
        boolean pushUpFirst = dy > 0.01f || (Math.abs(dy) <= 0.01f && movingCy >= otherCy);

        Pane leftCand = new Pane(clamp(moving.x - other.width, 0f, Math.max(0f, moving.x - MIN_SIZE)), other.y,
                Math.min(other.width, Math.max(MIN_SIZE, moving.x)), other.height);
        Pane rightCand = new Pane(moving.x + moving.width, other.y,
                Math.min(other.width, Math.max(MIN_SIZE, 1f - (moving.x + moving.width))), other.height);
        Pane upCand = new Pane(other.x, clamp(moving.y - other.height, 0f, Math.max(0f, moving.y - MIN_SIZE)),
                other.width, Math.min(other.height, Math.max(MIN_SIZE, moving.y)));
        Pane downCand = new Pane(other.x, moving.y + moving.height,
                other.width, Math.min(other.height, Math.max(MIN_SIZE, 1f - (moving.y + moving.height))));
        Pane swapCand = new Pane(clamp(origX, 0f, 1f - Math.min(other.width, 1f)),
                clamp(origY, 0f, 1f - Math.min(other.height, 1f)), other.width, other.height);

        if (preferHorizontal) {
            if (pushLeftFirst) {
                candidates.add(leftCand);
                candidates.add(swapCand);
                candidates.add(rightCand);
            } else {
                candidates.add(rightCand);
                candidates.add(swapCand);
                candidates.add(leftCand);
            }
            candidates.add(pushUpFirst ? upCand : downCand);
            candidates.add(pushUpFirst ? downCand : upCand);
        } else {
            if (pushUpFirst) {
                candidates.add(upCand);
                candidates.add(swapCand);
                candidates.add(downCand);
            } else {
                candidates.add(downCand);
                candidates.add(swapCand);
                candidates.add(upCand);
            }
            candidates.add(pushLeftFirst ? leftCand : rightCand);
            candidates.add(pushLeftFirst ? rightCand : leftCand);
        }

        for (Pane c : candidates) {
            if (c.x < -OVERLAP_EPS || c.y < -OVERLAP_EPS
                    || c.x + c.width > 1f + OVERLAP_EPS || c.y + c.height > 1f + OVERLAP_EPS
                    || c.width < MIN_SIZE - OVERLAP_EPS || c.height < MIN_SIZE - OVERLAP_EPS) {
                continue;
            }
            c.x = clamp(c.x, 0f, 1f - MIN_SIZE);
            c.y = clamp(c.y, 0f, 1f - MIN_SIZE);
            c.width = clamp(c.width, MIN_SIZE, 1f - c.x);
            c.height = clamp(c.height, MIN_SIZE, 1f - c.y);
            if (intersects(c, moving)) continue;
            boolean hitsOther = false;
            for (int j = 0; j < PANE_COUNT; j++) {
                if (j == otherIdx || j == -1 || !mask[j] || panes[j] == moving) continue;
                if (intersects(c, panes[j])) { hitsOther = true; break; }
            }
            if (!hitsOther) {
                other.set(c.x, c.y, c.width, c.height);
                return;
            }
        }
        // 次优：先找不撞 moving 的位置，再交给 resolveRemainingOverlaps 统一消解
        for (Pane c : candidates) {
            if (c.width < MIN_SIZE - OVERLAP_EPS || c.height < MIN_SIZE - OVERLAP_EPS) continue;
            float cx = clamp(c.x, 0f, 1f - MIN_SIZE);
            float cy = clamp(c.y, 0f, 1f - MIN_SIZE);
            float cw = clamp(c.width, MIN_SIZE, 1f - cx);
            float ch = clamp(c.height, MIN_SIZE, 1f - cy);
            Pane norm = new Pane(cx, cy, cw, ch);
            if (!intersects(norm, moving)) {
                other.set(norm.x, norm.y, norm.width, norm.height);
                return;
            }
        }
    }

    private void resolveRemainingOverlaps(int anchorIndex, boolean[] mask) {
        Pane anchor = panes[anchorIndex];
        for (int pass = 0; pass < 6 && hasAnyOverlap(panes, mask); pass++) {
            for (int i = 0; i < PANE_COUNT; i++) {
                if (!mask[i]) continue;
                for (int j = i + 1; j < PANE_COUNT; j++) {
                    if (!mask[j]) continue;
                    if (!intersects(panes[i], panes[j])) continue;
                    int victimIdx = (j == anchorIndex) ? i : (i == anchorIndex ? j : j);
                    Pane fixed = (victimIdx == i) ? panes[j] : panes[i];
                    Pane victim = panes[victimIdx];
                    relocateOrShrinkFrom(victim, fixed, anchor, victimIdx, mask);
                }
            }
        }
        if (hasAnyOverlap(panes, mask)) {
            // 极端大尺寸锚点导致剩余空间不足时的兜底：安全收缩锚点留出最小可容纳条带后按列铺排
            List<Integer> active = new ArrayList<>();
            for (int i = 0; i < PANE_COUNT; i++) {
                if (mask[i]) active.add(i);
            }
            active.sort(Comparator.comparingDouble(i -> panes[i].x));
            float w = 1f / active.size();
            float cursor = 0f;
            for (int k = 0; k < active.size(); k++) {
                int idx = active.get(k);
                float pw = (k == active.size() - 1) ? (1f - cursor) : w;
                panes[idx].set(cursor, 0f, pw, 1f);
                cursor += pw;
            }
        }
    }

    private void relocateOrShrinkFrom(Pane victim, Pane fixed, Pane anchor, int victimIdx, boolean[] mask) {
        float rightSpace = 1f - (fixed.x + fixed.width);
        float leftSpace = fixed.x;
        float bottomSpace = 1f - (fixed.y + fixed.height);
        float topSpace = fixed.y;
        List<Pane> opts = new ArrayList<>(4);
        if (leftSpace >= MIN_SIZE) {
            float nw = Math.min(victim.width, leftSpace);
            opts.add(new Pane(fixed.x - nw, victim.y, nw, victim.height));
        }
        if (rightSpace >= MIN_SIZE) {
            float nw = Math.min(victim.width, rightSpace);
            opts.add(new Pane(fixed.x + fixed.width, victim.y, nw, victim.height));
        }
        if (topSpace >= MIN_SIZE) {
            float nh = Math.min(victim.height, topSpace);
            opts.add(new Pane(victim.x, fixed.y - nh, victim.width, nh));
        }
        if (bottomSpace >= MIN_SIZE) {
            float nh = Math.min(victim.height, bottomSpace);
            opts.add(new Pane(victim.x, fixed.y + fixed.height, victim.width, nh));
        }
        for (Pane o : opts) {
            boolean bad = false;
            for (int k = 0; k < PANE_COUNT; k++) {
                if (k == victimIdx || !mask[k]) continue;
                if (intersects(o, panes[k])) { bad = true; break; }
            }
            if (!bad) {
                victim.set(o.x, o.y, o.width, o.height);
                return;
            }
        }
        for (Pane o : opts) {
            if (!intersects(o, fixed) && (fixed == anchor || !intersects(o, anchor))) {
                victim.set(o.x, o.y, o.width, o.height);
                return;
            }
        }
    }

    public float[] snapshot() {
        float[] result = new float[16];
        for (int i = 0; i < PANE_COUNT; i++) {
            Pane pane = panes[i];
            int offset = i * 4;
            result[offset] = pane.x;
            result[offset + 1] = pane.y;
            result[offset + 2] = pane.width;
            result[offset + 3] = pane.height;
        }
        return result;
    }

    /** Validate the entire saved layout before replacing any rectangle. */
    public boolean restore(float[] values) {
        if (values == null || values.length != 16) return false;
        for (int i = 0; i < PANE_COUNT; i++) {
            int offset = i * 4;
            for (int j = 0; j < 4; j++) if (!Float.isFinite(values[offset + j])) return false;
            float x = values[offset], y = values[offset + 1], w = values[offset + 2], h = values[offset + 3];
            if (x < 0 || y < 0 || w < MIN_SIZE || h < MIN_SIZE || x + w > 1.0001f || y + h > 1.0001f) return false;
        }
        for (int i = 0; i < PANE_COUNT; i++) {
            panes[i].set(values[i * 4], values[i * 4 + 1], values[i * 4 + 2], values[i * 4 + 3]);
        }
        return true;
    }

    /**
     * Computes effective layout for Mode B (retaining edited layout verbatim when all 4 panes are visible,
     * and proportionally reflowing visible columns/rows when 1-3 panes are hidden so no dead holes appear).
     */
    public EffectiveLayout computeEffectiveModeB(boolean[] requestedVisible) {
        EffectiveLayout out = new EffectiveLayout();
        boolean[] vis = normalizeVisibleAtLeastOne(requestedVisible, FRONT);
        System.arraycopy(vis, 0, out.visible, 0, PANE_COUNT);

        int visibleCount = countVisible(vis);
        if (visibleCount == PANE_COUNT) {
            copyAllPanes(out);
            return out;
        }
        copyAllPanes(out);
        if (visibleCount == 1) {
            for (int i = 0; i < PANE_COUNT; i++) {
                if (vis[i]) out.panes[i].set(0f, 0f, 1f, 1f);
            }
            return out;
        }

        if (isTwoByTwoGrid()) {
            reflowTwoByTwo(out, vis);
            return out;
        }
        if (isFrontLargePreset()) {
            reflowFrontLarge(out, vis);
            return out;
        }
        if (isFrontBackStackedInColumn()) {
            reflowThreeColumnWithFrontBackStack(out, vis);
            return out;
        }

        // Arbitrary user freeform layout with some panes hidden: pack visible panes cleanly into columns.
        reflowHorizontalStripByX(out, vis);
        return out;
    }

    /**
     * Computes effective layout for Mode C (primary FRONT or BACK + side LEFT/RIGHT columns).
     * Strictly marks the inactive primary slot invisible so its Surface can be released.
     */
    public EffectiveLayout computeEffectiveModeC(int primaryIndex, boolean showLeft, boolean showRight) {
        EffectiveLayout out = new EffectiveLayout();
        int activePrimary = (primaryIndex == BACK) ? BACK : FRONT;
        int inactivePrimary = (activePrimary == BACK) ? FRONT : BACK;

        out.visible[activePrimary] = true;
        out.visible[inactivePrimary] = false;
        out.visible[LEFT] = showLeft;
        out.visible[RIGHT] = showRight;

        // Preserve user-adjusted primary width & side proportion from Mode C's base panes.
        float rawMainW = panes[activePrimary].width;
        float mainW = clamp(Float.isFinite(rawMainW) ? rawMainW : MODE_C_DEFAULT_MAIN_WIDTH, 0.30f, 0.82f);
        float leftW = clamp(Float.isFinite(panes[LEFT].width) ? panes[LEFT].width : MODE_C_DEFAULT_SIDE_WIDTH, 0.12f, 0.6f);
        float rightW = clamp(Float.isFinite(panes[RIGHT].width) ? panes[RIGHT].width : MODE_C_DEFAULT_SIDE_WIDTH, 0.12f, 0.6f);

        if (!showLeft && !showRight) {
            out.panes[activePrimary].set(0f, 0f, 1f, 1f);
            out.panes[inactivePrimary].set(0f, 0f, 1f, 1f);
        } else if (showLeft && showRight) {
            float sidesBudget = 1f - mainW;
            float sideSum = leftW + rightW;
            float effLeftW = sidesBudget * (leftW / sideSum);
            float effRightW = sidesBudget - effLeftW;

            // Keep left-to-right column order respecting whether LEFT or RIGHT was placed first, or main pane position.
            boolean mainOnRight = panes[activePrimary].x > panes[LEFT].x && panes[activePrimary].x > panes[RIGHT].x;
            if (mainOnRight) {
                out.panes[LEFT].set(0f, 0f, effLeftW, 1f);
                out.panes[RIGHT].set(effLeftW, 0f, effRightW, 1f);
                out.panes[activePrimary].set(sidesBudget, 0f, mainW, 1f);
                out.panes[inactivePrimary].set(sidesBudget, 0f, mainW, 1f);
            } else {
                out.panes[activePrimary].set(0f, 0f, mainW, 1f);
                out.panes[inactivePrimary].set(0f, 0f, mainW, 1f);
                if (panes[RIGHT].x < panes[LEFT].x) {
                    out.panes[RIGHT].set(mainW, 0f, effRightW, 1f);
                    out.panes[LEFT].set(mainW + effRightW, 0f, effLeftW, 1f);
                } else {
                    out.panes[LEFT].set(mainW, 0f, effLeftW, 1f);
                    out.panes[RIGHT].set(mainW + effLeftW, 0f, effRightW, 1f);
                }
            }
        } else {
            int singleSide = showLeft ? LEFT : RIGHT;
            float sideW = 1f - mainW;
            boolean mainOnRight = panes[activePrimary].x > panes[singleSide].x;
            if (mainOnRight) {
                out.panes[singleSide].set(0f, 0f, sideW, 1f);
                out.panes[activePrimary].set(sideW, 0f, mainW, 1f);
                out.panes[inactivePrimary].set(sideW, 0f, mainW, 1f);
            } else {
                out.panes[activePrimary].set(0f, 0f, mainW, 1f);
                out.panes[inactivePrimary].set(0f, 0f, mainW, 1f);
                out.panes[singleSide].set(mainW, 0f, sideW, 1f);
            }
        }
        return out;
    }

    /**
     * 计算模式 D（左侧前/后主框 + 右侧方向盘联动左/右框）的有效排版。
     * 未选中的另一对摄像头（如后摄与右摄）强制设为不可见以便释放预览流，并与已选槽位共享坐标矩形。
     */
    public EffectiveLayout computeEffectiveModeD(int leftSlotCamIndex, int rightSlotCamIndex) {
        EffectiveLayout out = new EffectiveLayout();
        int activeMain = (leftSlotCamIndex == BACK) ? BACK : FRONT;
        int inactiveMain = (activeMain == BACK) ? FRONT : BACK;
        int activeSide = (rightSlotCamIndex == RIGHT) ? RIGHT : LEFT;
        int inactiveSide = (activeSide == RIGHT) ? LEFT : RIGHT;

        out.visible[activeMain] = true;
        out.visible[inactiveMain] = false;
        out.visible[activeSide] = true;
        out.visible[inactiveSide] = false;

        Pane mainRect = panes[activeMain];
        Pane sideRect = panes[activeSide];
        out.panes[activeMain].set(mainRect.x, mainRect.y, mainRect.width, mainRect.height);
        out.panes[activeSide].set(sideRect.x, sideRect.y, sideRect.width, sideRect.height);

        boolean[] mask = new boolean[PANE_COUNT];
        mask[activeMain] = true;
        mask[activeSide] = true;
        if (hasAnyOverlap(out.panes, mask)) {
            float mw = clamp(mainRect.width, MIN_SIZE, 1f - MIN_SIZE);
            boolean mainOnRight = mainRect.x > sideRect.x;
            if (mainOnRight) {
                out.panes[activeSide].set(0f, 0f, 1f - mw, 1f);
                out.panes[activeMain].set(1f - mw, 0f, mw, 1f);
            } else {
                out.panes[activeMain].set(0f, 0f, mw, 1f);
                out.panes[activeSide].set(mw, 0f, 1f - mw, 1f);
            }
        }
        out.panes[inactiveMain].set(out.panes[activeMain].x, out.panes[activeMain].y,
                out.panes[activeMain].width, out.panes[activeMain].height);
        out.panes[inactiveSide].set(out.panes[activeSide].x, out.panes[activeSide].y,
                out.panes[activeSide].width, out.panes[activeSide].height);
        return out;
    }

    /**
     * 计算模式 E（左侧左摄像头 + 右侧右摄像头）的有效排版。
     * 强制隐藏前摄与后摄以便释放对应预览流，并保证左/右活跃画框互不重叠。
     */
    public EffectiveLayout computeEffectiveModeE() {
        EffectiveLayout out = new EffectiveLayout();
        out.visible[LEFT] = true;
        out.visible[RIGHT] = true;
        out.visible[FRONT] = false;
        out.visible[BACK] = false;

        Pane leftRect = panes[LEFT];
        Pane rightRect = panes[RIGHT];
        out.panes[LEFT].set(leftRect.x, leftRect.y, leftRect.width, leftRect.height);
        out.panes[RIGHT].set(rightRect.x, rightRect.y, rightRect.width, rightRect.height);

        boolean[] mask = new boolean[PANE_COUNT];
        mask[LEFT] = true;
        mask[RIGHT] = true;
        if (hasAnyOverlap(out.panes, mask)) {
            float lw = clamp(leftRect.width, MIN_SIZE, 1f - MIN_SIZE);
            boolean leftOnRight = leftRect.x > rightRect.x;
            if (leftOnRight) {
                out.panes[RIGHT].set(0f, 0f, 1f - lw, 1f);
                out.panes[LEFT].set(1f - lw, 0f, lw, 1f);
            } else {
                out.panes[LEFT].set(0f, 0f, lw, 1f);
                out.panes[RIGHT].set(lw, 0f, 1f - lw, 1f);
            }
        }
        out.panes[FRONT].set(out.panes[LEFT].x, out.panes[LEFT].y,
                out.panes[LEFT].width, out.panes[LEFT].height);
        out.panes[BACK].set(out.panes[RIGHT].x, out.panes[RIGHT].y,
                out.panes[RIGHT].width, out.panes[RIGHT].height);
        return out;
    }

    /** 在双切换槽位模型（C 的主框、D 的主/侧框）中同步配对镜像坐标。 */
    public void syncPairedSlot(int activeIndex, int pairedIndex) {
        if (activeIndex < 0 || activeIndex >= PANE_COUNT || pairedIndex < 0 || pairedIndex >= PANE_COUNT) return;
        Pane src = panes[activeIndex];
        panes[pairedIndex].set(src.x, src.y, src.width, src.height);
    }

    private void copyAllPanes(EffectiveLayout out) {
        for (int i = 0; i < PANE_COUNT; i++) {
            Pane src = panes[i];
            out.panes[i].set(src.x, src.y, src.width, src.height);
        }
    }

    private boolean isFrontBackStackedInColumn() {
        return Math.abs(panes[FRONT].x - panes[BACK].x) < 0.05f
                && Math.abs(panes[FRONT].width - panes[BACK].width) < 0.05f
                && Math.abs(panes[LEFT].height - 1f) < 0.05f
                && Math.abs(panes[RIGHT].height - 1f) < 0.05f;
    }

    private boolean isTwoByTwoGrid() {
        return Math.abs(panes[LEFT].x - panes[FRONT].x) < 0.05f
                && Math.abs(panes[RIGHT].x - panes[BACK].x) < 0.05f
                && Math.abs(panes[LEFT].y - panes[RIGHT].y) < 0.05f
                && Math.abs(panes[FRONT].y - panes[BACK].y) < 0.05f
                && panes[LEFT].height < 0.7f
                && panes[RIGHT].height < 0.7f;
    }

    private boolean isFrontLargePreset() {
        return Math.abs(panes[FRONT].width - 1f) < 0.05f
                && panes[FRONT].y < 0.05f
                && panes[LEFT].y >= 0.4f
                && panes[BACK].y >= 0.4f
                && panes[RIGHT].y >= 0.4f;
    }

    private void reflowThreeColumnWithFrontBackStack(EffectiveLayout out, boolean[] vis) {
        boolean centerHasFront = vis[FRONT];
        boolean centerHasBack = vis[BACK];
        boolean centerVisible = centerHasFront || centerHasBack;

        // Identify the 3 horizontal columns sorted by base x coordinate.
        final int COL_FRONT_BACK = 100;
        List<int[]> cols = new ArrayList<>();
        float fbWidth = Math.max(MIN_SIZE, (panes[FRONT].width + panes[BACK].width) * 0.5f);
        cols.add(new int[]{COL_FRONT_BACK, Math.round(panes[FRONT].x * 10000)});
        cols.add(new int[]{LEFT, Math.round(panes[LEFT].x * 10000)});
        cols.add(new int[]{RIGHT, Math.round(panes[RIGHT].x * 10000)});
        cols.sort(Comparator.comparingInt(a -> a[1]));

        float[] colWidths = new float[cols.size()];
        boolean[] colVisible = new boolean[cols.size()];
        float visibleWidthSum = 0f;
        for (int c = 0; c < cols.size(); c++) {
            int tag = cols.get(c)[0];
            colVisible[c] = (tag == COL_FRONT_BACK) ? centerVisible : vis[tag];
            colWidths[c] = (tag == COL_FRONT_BACK) ? fbWidth : panes[tag].width;
            if (colVisible[c]) visibleWidthSum += colWidths[c];
        }
        if (visibleWidthSum <= 0f) return;

        float cursorX = 0f;
        int remainingCols = 0;
        for (boolean cv : colVisible) if (cv) remainingCols++;
        int seen = 0;
        for (int c = 0; c < cols.size(); c++) {
            if (!colVisible[c]) continue;
            seen++;
            float w = (seen == remainingCols) ? (1f - cursorX) : (colWidths[c] / visibleWidthSum);
            int tag = cols.get(c)[0];
            if (tag == COL_FRONT_BACK) {
                if (centerHasFront && centerHasBack) {
                    float fH = panes[FRONT].height;
                    float bH = panes[BACK].height;
                    float sumH = Math.max(0.01f, fH + bH);
                    float effFH = fH / sumH;
                    if (panes[FRONT].y <= panes[BACK].y) {
                        out.panes[FRONT].set(cursorX, 0f, w, effFH);
                        out.panes[BACK].set(cursorX, effFH, w, 1f - effFH);
                    } else {
                        out.panes[BACK].set(cursorX, 0f, w, 1f - effFH);
                        out.panes[FRONT].set(cursorX, 1f - effFH, w, effFH);
                    }
                } else if (centerHasFront) {
                    out.panes[FRONT].set(cursorX, 0f, w, 1f);
                } else {
                    out.panes[BACK].set(cursorX, 0f, w, 1f);
                }
            } else {
                out.panes[tag].set(cursorX, 0f, w, 1f);
            }
            cursorX += w;
        }
    }

    private void reflowTwoByTwo(EffectiveLayout out, boolean[] vis) {
        boolean topVis = vis[LEFT] || vis[RIGHT];
        boolean botVis = vis[FRONT] || vis[BACK];
        float topRatio = (topVis && botVis) ? clamp(Math.max(panes[LEFT].height, panes[RIGHT].height), 0.25f, 0.75f) : (topVis ? 1f : 0f);
        float botRatio = botVis ? (1f - topRatio) : 0f;

        if (vis[LEFT] && vis[RIGHT]) {
            float sumW = Math.max(0.01f, panes[LEFT].width + panes[RIGHT].width);
            float lw = panes[LEFT].width / sumW;
            out.panes[LEFT].set(0f, 0f, lw, topRatio);
            out.panes[RIGHT].set(lw, 0f, 1f - lw, topRatio);
        } else if (vis[LEFT]) {
            out.panes[LEFT].set(0f, 0f, 1f, topRatio);
        } else if (vis[RIGHT]) {
            out.panes[RIGHT].set(0f, 0f, 1f, topRatio);
        }

        float botY = topVis ? topRatio : 0f;
        if (vis[FRONT] && vis[BACK]) {
            float sumW = Math.max(0.01f, panes[FRONT].width + panes[BACK].width);
            float fw = panes[FRONT].width / sumW;
            out.panes[FRONT].set(0f, botY, fw, botRatio);
            out.panes[BACK].set(fw, botY, 1f - fw, botRatio);
        } else if (vis[FRONT]) {
            out.panes[FRONT].set(0f, botY, 1f, botRatio);
        } else if (vis[BACK]) {
            out.panes[BACK].set(0f, botY, 1f, botRatio);
        }
    }

    private void reflowFrontLarge(EffectiveLayout out, boolean[] vis) {
        boolean topVis = vis[FRONT];
        int[] bottomOrder = {LEFT, BACK, RIGHT};
        List<Integer> botVis = new ArrayList<>();
        float botWidthSum = 0f;
        for (int idx : bottomOrder) {
            if (vis[idx]) {
                botVis.add(idx);
                botWidthSum += panes[idx].width;
            }
        }
        float topH = (topVis && !botVis.isEmpty()) ? clamp(panes[FRONT].height, 0.30f, 0.80f) : (topVis ? 1f : 0f);
        if (topVis) {
            out.panes[FRONT].set(0f, 0f, 1f, topH);
        }
        if (!botVis.isEmpty() && botWidthSum > 0f) {
            float y = topVis ? topH : 0f;
            float h = 1f - y;
            float x = 0f;
            for (int i = 0; i < botVis.size(); i++) {
                int idx = botVis.get(i);
                float w = (i == botVis.size() - 1) ? (1f - x) : (panes[idx].width / botWidthSum);
                out.panes[idx].set(x, y, w, h);
                x += w;
            }
        }
    }

    private void reflowHorizontalStripByX(EffectiveLayout out, boolean[] vis) {
        List<Integer> active = new ArrayList<>();
        float weightSum = 0f;
        for (int i = 0; i < PANE_COUNT; i++) {
            if (vis[i]) {
                active.add(i);
                weightSum += Math.max(MIN_SIZE, panes[i].width);
            }
        }
        active.sort(Comparator.comparingDouble(i -> panes[i].x));
        float x = 0f;
        for (int i = 0; i < active.size(); i++) {
            int idx = active.get(i);
            float w = (i == active.size() - 1) ? (1f - x) : (Math.max(MIN_SIZE, panes[idx].width) / weightSum);
            out.panes[idx].set(x, 0f, w, 1f);
            x += w;
        }
    }

    public static boolean[] normalizeVisibleAtLeastOne(boolean[] requested, int fallbackIndex) {
        boolean[] vis = new boolean[]{true, true, true, true};
        if (requested != null && requested.length >= PANE_COUNT) {
            System.arraycopy(requested, 0, vis, 0, PANE_COUNT);
        }
        if (countVisible(vis) == 0) {
            vis[clampIndex(fallbackIndex)] = true;
        }
        return vis;
    }

    public static int countVisible(boolean[] vis) {
        int count = 0;
        for (int i = 0; i < PANE_COUNT && i < vis.length; i++) {
            if (vis[i]) count++;
        }
        return count;
    }

    private static int clampIndex(int idx) {
        return (idx >= 0 && idx < PANE_COUNT) ? idx : FRONT;
    }
}
