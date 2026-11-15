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

    public static final float MODE_C_DEFAULT_MAIN_WIDTH = 0.64f;
    public static final float MODE_C_DEFAULT_SIDE_WIDTH = 0.18f;

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
