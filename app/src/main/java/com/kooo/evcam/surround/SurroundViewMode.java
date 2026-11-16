package com.kooo.evcam.surround;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 360 模式 A 右侧详情区视角枚举与上下联动规则（对标 Smartcam Ljw3 / Lsv3，仅纯 Java 无 Android 依赖）。
 */
public enum SurroundViewMode {
    DRIVE("随档位"),
    FRONT("前视"),
    REAR("后视"),
    FRONT_WHEELS("前轮"),
    REAR_WHEELS("后轮"),
    SIDES("左右侧视");

    @NonNull
    public final String label;

    SurroundViewMode(@NonNull String label) {
        this.label = label;
    }

    @NonNull
    public static SurroundViewMode fromKey(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DRIVE;
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.US);
        for (SurroundViewMode mode : values()) {
            if (mode.name().equals(upper) || mode.label.equals(raw.trim())) {
                return mode;
            }
        }
        return DRIVE;
    }

    /** 是否为轮位双侧拆分子视图（前轮 / 后轮）。 */
    public boolean isWheelSplitView() {
        return this == FRONT_WHEELS || this == REAR_WHEELS;
    }

    /** 是否为底部轮位/侧视类视图（含前轮、后轮、左右侧视）。 */
    public boolean isBottomDetailCandidate() {
        return this == FRONT_WHEELS || this == REAR_WHEELS || this == SIDES;
    }

    /** 是否为道路类视图（随档位、前视、后视）。 */
    public boolean isRoadCandidate() {
        return this == DRIVE || this == FRONT || this == REAR;
    }

    /**
     * 规范化上下详情配置，确保模式 A 始终形成"上方道路/主细节 + 下方轮位/侧视"的合法组合：
     * - 上方若选 FRONT_WHEELS：固定 [FRONT_WHEELS, REAR_WHEELS]
     * - 上方若选 REAR_WHEELS：固定 [REAR_WHEELS, FRONT_WHEELS]
     * - 上方若选 SIDES：回退上方为 DRIVE，下方为 SIDES
     * - 下方仅允许 FRONT_WHEELS / REAR_WHEELS / SIDES（无效值默认 FRONT_WHEELS）
     */
    @NonNull
    public static SurroundViewMode[] normalizeDetailViews(@Nullable SurroundViewMode top,
                                                          @Nullable SurroundViewMode bottom) {
        SurroundViewMode safeTop = top != null ? top : DRIVE;
        SurroundViewMode safeBottom = bottom != null ? bottom : FRONT_WHEELS;

        if (safeTop == FRONT_WHEELS) {
            return new SurroundViewMode[]{FRONT_WHEELS, REAR_WHEELS};
        }
        if (safeTop == REAR_WHEELS) {
            return new SurroundViewMode[]{REAR_WHEELS, FRONT_WHEELS};
        }
        if (safeTop == SIDES) {
            return new SurroundViewMode[]{DRIVE, SIDES};
        }
        if (!safeBottom.isBottomDetailCandidate()) {
            safeBottom = FRONT_WHEELS;
        }
        return new SurroundViewMode[]{safeTop, safeBottom};
    }

    /**
     * 解析右上视窗实际显示的模式：
     * 优先使用画面内临时手动覆盖，否则按照配置视角与当前是否 R 档映射。
     */
    @NonNull
    public static SurroundViewMode resolveEffectiveTopView(@Nullable SurroundViewMode configuredTop,
                                                           boolean inReverse,
                                                           @Nullable SurroundViewMode manualTopOverride) {
        if (manualTopOverride != null) {
            if (manualTopOverride == DRIVE) {
                return inReverse ? REAR : FRONT;
            }
            return manualTopOverride;
        }
        SurroundViewMode safeTop = configuredTop != null ? configuredTop : DRIVE;
        if (safeTop == DRIVE) {
            return inReverse ? REAR : FRONT;
        }
        return safeTop;
    }

    /**
     * 解析右下视窗实际显示的轮位/侧视模式：
     * - 当右上配置为"随档位"（且当前画面内也未锁定到固定前/后视）时，随档位自动切换：R 档 -> 后轮，其他档位 -> 前轮（除非选了 SIDES）；
     * - 当用户点击画面内轮位切换按钮时，按 manualBottomOverride 显示；
     * - 上方若固定选了前视/后视/前轮等非随档位项，下方保持对应固定轮位配置（不做多余联动）。
     */
    @NonNull
    public static SurroundViewMode resolveEffectiveBottomView(@Nullable SurroundViewMode configuredTop,
                                                              @Nullable SurroundViewMode configuredBottom,
                                                              boolean inReverse,
                                                              @Nullable SurroundViewMode manualBottomOverride) {
        SurroundViewMode[] pair = normalizeDetailViews(configuredTop, configuredBottom);
        if (manualBottomOverride != null && manualBottomOverride.isBottomDetailCandidate()) {
            return manualBottomOverride;
        }
        if (pair[0] == DRIVE && pair[1] != SIDES) {
            return inReverse ? REAR_WHEELS : FRONT_WHEELS;
        }
        return pair[1];
    }

    /** 生成右上视窗角标文字（不输出环境/雷达冗余字样）。 */
    @NonNull
    public static String formatTopBadgeText(@Nullable SurroundViewMode effectiveTop,
                                            boolean followingGear,
                                            boolean inReverse) {
        SurroundViewMode mode = effectiveTop != null ? effectiveTop : FRONT;
        if (mode == REAR) {
            if (followingGear && inReverse) {
                return "后视 · 倒车";
            }
            return followingGear ? "后视 · 随档位" : "后视";
        }
        if (mode == FRONT) {
            return followingGear ? "前视 · 随档位" : "前视";
        }
        return mode.label;
    }

    /** 获取对应的摄像头位置 key（"front" / "back"）。 */
    @NonNull
    public String toSingleCameraKey(boolean inReverse) {
        if (this == REAR) return "back";
        if (this == FRONT) return "front";
        if (this == DRIVE) return inReverse ? "back" : "front";
        return "front";
    }
}
