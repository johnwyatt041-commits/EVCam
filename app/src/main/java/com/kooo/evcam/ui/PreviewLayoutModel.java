package com.kooo.evcam.ui;

/** Four normalized rectangles; no camera, bitmap or rendering resources. */
public final class PreviewLayoutModel {
    public static final int LEFT = 0, FRONT = 1, BACK = 2, RIGHT = 3;
    public static final float MIN_SIZE = 0.18f;
    public static final class Pane {
        public float x, y, width, height;
        private Pane(float x, float y, float width, float height) { set(x, y, width, height); }
        private void set(float x, float y, float width, float height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }
    private final Pane[] panes = new Pane[4];
    public PreviewLayoutModel() {
        for (int i = 0; i < 4; i++) panes[i] = new Pane(0, 0, .5f, .5f);
        preset(0);
    }
    public Pane pane(int index) { return panes[index]; }
    public void preset(int preset) {
        if (preset == 1) {
            panes[LEFT].set(0, 0, .5f, .5f); panes[RIGHT].set(.5f, 0, .5f, .5f);
            panes[FRONT].set(0, .5f, .5f, .5f); panes[BACK].set(.5f, .5f, .5f, .5f);
        } else if (preset == 2) {
            panes[FRONT].set(0, 0, 1, .6f); panes[LEFT].set(0, .6f, .33f, .4f);
            panes[BACK].set(.33f, .6f, .34f, .4f); panes[RIGHT].set(.67f, .6f, .33f, .4f);
        } else {
            panes[LEFT].set(0, 0, .3f, 1); panes[RIGHT].set(.7f, 0, .3f, 1);
            panes[FRONT].set(.3f, 0, .4f, .5f); panes[BACK].set(.3f, .5f, .4f, .5f);
        }
    }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    public void move(int index, float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        Pane pane = panes[index];
        pane.x = clamp(x, 0, 1 - pane.width); pane.y = clamp(y, 0, 1 - pane.height);
    }
    public void resize(int index, float width, float height) {
        if (!Float.isFinite(width) || !Float.isFinite(height)) return;
        Pane pane = panes[index];
        pane.width = clamp(width, MIN_SIZE, 1 - pane.x);
        pane.height = clamp(height, MIN_SIZE, 1 - pane.y);
    }
    public float[] snapshot() {
        float[] result = new float[16];
        for (int i = 0; i < 4; i++) {
            Pane pane = panes[i]; int offset = i * 4;
            result[offset] = pane.x; result[offset + 1] = pane.y;
            result[offset + 2] = pane.width; result[offset + 3] = pane.height;
        }
        return result;
    }
    /** Validate the entire saved layout before replacing any rectangle. */
    public boolean restore(float[] values) {
        if (values == null || values.length != 16) return false;
        for (int i = 0; i < 4; i++) {
            int offset = i * 4;
            for (int j = 0; j < 4; j++) if (!Float.isFinite(values[offset + j])) return false;
            float x = values[offset], y = values[offset + 1], w = values[offset + 2], h = values[offset + 3];
            if (x < 0 || y < 0 || w < MIN_SIZE || h < MIN_SIZE || x + w > 1.0001f || y + h > 1.0001f) return false;
        }
        for (int i = 0; i < 4; i++) panes[i].set(values[i * 4], values[i * 4 + 1], values[i * 4 + 2], values[i * 4 + 3]);
        return true;
    }
}
