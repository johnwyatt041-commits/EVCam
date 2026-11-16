package com.kooo.evcam.ui;

import androidx.annotation.DrawableRes;

import com.kooo.evcam.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * SC 风格设置根目录清单（仅保留已实装可进入项，剔除屏幕尺寸、4/7/8/9 等未实装占位分类）。
 */
public final class SettingsCatalog {

    public static final String ID_APPEARANCE = "appearance";
    public static final String ID_RECORDER = "recorder";
    public static final String ID_SURROUND_360 = "surround_360";
    public static final String ID_BLIND_SPOT = "blind_spot";
    public static final String ID_SIGNAL_PRESET = "signal_preset";
    public static final String ID_FEISHU = "feishu";
    public static final String ID_SYSTEM_PERMISSIONS = "system_permissions";
    public static final String ID_UPDATE = "update";
    public static final String ID_DEBUG_LOG = "debug_log";

    public static final class Entry {
        public final String id;
        public final String number;
        public final String title;
        public final String displayTitle;
        public final String description;
        @DrawableRes
        public final int iconResId;

        public Entry(String id, String number, String title, String description) {
            this(id, number, title, stripNumericPrefix(title), description, R.drawable.ic_nav_settings);
        }

        public Entry(String id, String number, String title, String displayTitle,
                     String description, @DrawableRes int iconResId) {
            this.id = id;
            this.number = number;
            this.title = title;
            this.displayTitle = displayTitle;
            this.description = description;
            this.iconResId = iconResId;
        }
    }

    private static String stripNumericPrefix(String title) {
        if (title == null) return "";
        int dot = title.indexOf(". ");
        return dot >= 0 ? title.substring(dot + 2) : title;
    }

    private static final List<Entry> IMPLEMENTED_ENTRIES = Collections.unmodifiableList(Arrays.asList(
            new Entry(ID_APPEARANCE, "1", "1. 外观与交互", "外观与交互",
                    "主题外观（跟随系统/深色/浅色）、默认启动页、开门退出二次确认", R.drawable.ic_nav_settings),
            new Entry(ID_RECORDER, "3", "3. 记录仪", "记录仪",
                    "车型布局、录制模式、分段时长、摄像头选择、画质分辨率、存储与水印", R.drawable.ic_nav_recording),
            new Entry(ID_SURROUND_360, "4", "4. 360 画面", "360 画面",
                    "360 全景模式 A、分视角、R 档自动预览返桌、E5 校准与离线车模", R.drawable.ic_nav_recording),
            new Entry(ID_BLIND_SPOT, "5", "5. 补盲", "补盲",
                    "转向补盲联动、悬浮窗口、副屏显示/流输出与位置校准", R.drawable.ic_nav_blind_spot),
            new Entry(ID_SIGNAL_PRESET, "10", "10. 信号预设与匹配", "信号预设与匹配",
                    "银河 E5 四类信号源预设、实时快照诊断与 VHAL 属性映射", R.drawable.ic_vehicle_outline),
            new Entry(ID_FEISHU, "11", "11. 飞书远程", "飞书远程",
                    "飞书机器人凭据、自启动连接与远程查看控制", R.drawable.ic_nav_feishu_mono),
            new Entry(ID_SYSTEM_PERMISSIONS, "13", "13. 系统与权限", "系统与权限",
                    "开机自启动、使用提示、权限授予与系统后台白名单", R.drawable.ic_nav_permissions),
            new Entry(ID_UPDATE, "14", "14. 软件更新", "软件更新",
                    "当前版本信息、自定义更新服务器与手动检查更新", R.drawable.ic_nav_update),
            new Entry(ID_DEBUG_LOG, "15", "15. Debug 日志", "Debug 日志",
                    "Debug 日志级别开关、本地保存导出与诊断上传", R.drawable.ic_nav_debug)
    ));

    private SettingsCatalog() {
    }

    public static List<Entry> getImplementedEntries() {
        return IMPLEMENTED_ENTRIES;
    }

    public static Entry findById(String id) {
        if (id == null) return null;
        for (Entry entry : IMPLEMENTED_ENTRIES) {
            if (id.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }
}
