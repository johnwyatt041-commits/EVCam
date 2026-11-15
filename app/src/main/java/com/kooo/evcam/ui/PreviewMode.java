package com.kooo.evcam.ui;

/**
 * 主预览显示模式枚举：
 * - MODE_B：分栏可调模式（默认 3 列：前/后上下 + 左/右长条，可调位置比例并支持按路显隐）
 * - MODE_C：三路大屏模式（左大屏随 D/R 档切换前/后 + 右侧左/右 2 列）
 * - MODE_A：360 全景 + 分视角模式（为 T07 预留挂载槽，T03 阶段不启用渲染）
 */
public enum PreviewMode {
    MODE_B("mode_b", "分栏 B"),
    MODE_C("mode_c", "三路 C"),
    MODE_A("mode_a", "360 A");

    public final String key;
    public final String label;

    PreviewMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public static PreviewMode fromKey(String key) {
        if (MODE_C.key.equals(key)) return MODE_C;
        if (MODE_A.key.equals(key)) return MODE_A;
        return MODE_B;
    }
}
