package com.kooo.evcam.ui;

/**
 * 主预览显示模式枚举：
 * - MODE_B：分栏可调模式（默认 3 列：前/后上下 + 左/右长条，可调位置比例并支持按路显隐）
 * - MODE_C：三路大屏模式（左大屏手动切换前/后 + 右侧左/右 2 列）
 * - MODE_A：360 全景 + 分视角模式
 * - MODE_D：双视转向联动模式（左前/后 + 右随转向灯/方向盘/手动左/右）
 * - MODE_E：左右双摄视图（左侧显示左摄 + 右侧显示右摄）
 */
public enum PreviewMode {
    MODE_B("mode_b", "分栏 B"),
    MODE_C("mode_c", "三路 C"),
    MODE_A("mode_a", "360 A"),
    MODE_D("mode_d", "模式 D"),
    MODE_E("mode_e", "左右 E");

    public final String key;
    public final String label;

    PreviewMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public static PreviewMode fromKey(String key) {
        for (PreviewMode mode : values()) {
            if (mode.key.equals(key)) {
                return mode;
            }
        }
        return MODE_B;
    }
}
