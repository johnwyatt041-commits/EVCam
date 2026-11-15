package com.kooo.evcam.ui;

/**
 * T04 主预览展示形态（与 T03 分屏布局 Mode B / Mode C 正交）。
 *
 * - SIDEBAR: 保留左侧导航栏（默认，兼容 T02/T03 基线）。
 * - FULLSCREEN: 沉浸全屏预览（隐藏侧边栏，角标导航按策略显示）。
 * - WINDOW: 独立系统悬浮小窗（切至后台交还车机导航焦点的非抢占窗口）。
 */
public enum PreviewPresentation {
    SIDEBAR("sidebar", "保留侧边栏"),
    FULLSCREEN("fullscreen", "全屏"),
    WINDOW("window", "独立悬浮窗");

    private final String key;
    private final String displayName;

    PreviewPresentation(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static PreviewPresentation fromKey(String rawKey) {
        if (rawKey == null) {
            return SIDEBAR;
        }
        String normalized = rawKey.trim().toLowerCase();
        for (PreviewPresentation value : values()) {
            if (value.key.equals(normalized)) {
                return value;
            }
        }
        return SIDEBAR;
    }

    public static String normalizeKey(String rawKey) {
        return fromKey(rawKey).key;
    }
}
