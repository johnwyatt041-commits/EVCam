package com.kooo.evcam.configplaza;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 银河 E5 离线配置广场白名单分区定义（严格剔除旋转外设、云端字段与非 E5 选项）。
 */
public enum E5ConfigSection {
    APPEARANCE(
            "appearance",
            1,
            "外观与交互",
            "主题外观、默认启动页、预览模式与形态、导航快捷键、开门退出及开机自启"
    ),
    CAMERA_MAPPING(
            "camera_mapping",
            1,
            "摄像头与布局",
            "银河 E5 车型标识、四路摄像头 ID、日常录制路数与预览窗格布局"
    ),
    RECORDING(
            "recording",
            1,
            "录制、画质与水印",
            "录制模式、编码/帧率/分段、存储清理、调色裁剪、物理镜像、水印与提示音"
    ),
    VEHICLE_RULES_SIGNALS(
            "vehicle_rules_signals",
            1,
            "车辆规则与信号",
            "4 类白名单自动化规则、E5 信号源预设与 VHAL 属性 ID 映射"
    ),
    BLIND_SPOT_HUD(
            "blind_spot_hud",
            1,
            "补盲与双 HUD",
            "转向联动、悬浮窗位姿、360/AVM 避让、补盲矫正、输出镜像与 EVCC/ESP32 输出"
    ),
    SURROUND_360(
            "surround_360",
            1,
            "360 环视与返桌",
            "上下分视角、R 档延时预览、车速/P 档返桌、地面视野尺寸与引导线开关"
    );

    public static final int SCHEMA_VERSION = 1;
    public static final String VEHICLE_MODEL_GALAXY_E5 = "galaxy_e5";

    private final String id;
    private final int version;
    private final String label;
    private final String description;

    E5ConfigSection(@NonNull String id, int version, @NonNull String label, @NonNull String description) {
        this.id = id;
        this.version = version;
        this.label = label;
        this.description = description;
    }

    @NonNull
    public String getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    @NonNull
    public String getLabel() {
        return label;
    }

    @NonNull
    public String getDescription() {
        return description;
    }

    @Nullable
    public static E5ConfigSection fromId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        for (E5ConfigSection section : values()) {
            if (section.id.equals(trimmed)) {
                return section;
            }
        }
        return null;
    }

    @NonNull
    public static Set<E5ConfigSection> allSections() {
        Set<E5ConfigSection> set = new LinkedHashSet<>();
        Collections.addAll(set, values());
        return Collections.unmodifiableSet(set);
    }
}
