package com.kooo.evcam.blindspot;

/**
 * 输出目标端标识，用于将"物理传感器镜像"与各输出端独立的"输出镜像"解耦。
 */
public enum MirrorTarget {
    MAIN_PREVIEW("main", "主预览"),
    BLIND_SPOT("blind_spot", "主屏补盲"),
    SECONDARY_HUD("secondary", "副屏 HUD"),
    NETWORK_STREAM("stream", "ESP32 / MJPEG 推流");

    private final String key;
    private final String displayName;

    MirrorTarget(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static MirrorTarget fromKey(String key) {
        if (key == null) {
            return BLIND_SPOT;
        }
        String trimmed = key.trim().toLowerCase(java.util.Locale.ROOT);
        for (MirrorTarget target : values()) {
            if (target.key.equals(trimmed)
                    || target.name().toLowerCase(java.util.Locale.ROOT).equals(trimmed)) {
                return target;
            }
        }
        return BLIND_SPOT;
    }
}
