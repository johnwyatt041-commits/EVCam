package com.kooo.evcam.external;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.KeepAliveReceiver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 内置外部可调用广播清单与格式化工具（纯 Java，供设置页「广播中心」展示、筛选与一键复制）。
 */
public final class ExternalBroadcastCatalog {

    public static final String DEFAULT_PACKAGE_NAME = "com.kooo.evcam";
    public static final String EXTERNAL_COMMAND_RECEIVER = "com.kooo.evcam.external.ExternalCommandReceiver";
    public static final String KEEP_ALIVE_RECEIVER = "com.kooo.evcam.KeepAliveReceiver";

    public enum Category {
        FOREGROUND("foreground", "前后台"),
        RECORDING("recording", "录像与抓拍"),
        PREVIEW_MODE("preview_mode", "主视图切换"),
        SYSTEM("system", "保活唤醒");

        private final String id;
        private final String label;

        Category(@NonNull String id, @NonNull String label) {
            this.id = id;
            this.label = label;
        }

        @NonNull
        public String getId() {
            return id;
        }

        @NonNull
        public String getLabel() {
            return label;
        }
    }

    public static final class BroadcastItem {
        @NonNull public final String id;
        @NonNull public final Category category;
        @NonNull public final String title;
        @NonNull public final String action;
        @NonNull public final String receiverClassName;
        @NonNull public final String debounceLabel;
        @NonNull public final String description;
        @NonNull public final String extrasHint;

        public BroadcastItem(@NonNull String id,
                             @NonNull Category category,
                             @NonNull String title,
                             @NonNull String action,
                             @NonNull String receiverClassName,
                             @NonNull String debounceLabel,
                             @NonNull String description,
                             @Nullable String extrasHint) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.action = action;
            this.receiverClassName = receiverClassName;
            this.debounceLabel = debounceLabel;
            this.description = description;
            this.extrasHint = extrasHint != null ? extrasHint : "";
        }
    }

    private static final List<BroadcastItem> ITEMS = Collections.unmodifiableList(Arrays.asList(
            new BroadcastItem(
                    "toggle_foreground",
                    Category.FOREGROUND,
                    "前后台切换",
                    ExternalCommandContract.ACTION_TOGGLE_FOREGROUND,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 400ms",
                    "当前在前台则静默退回后台（不断录）；当前在后台或未打开界面则拉起到前台。",
                    ""
            ),
            new BroadcastItem(
                    "move_to_foreground",
                    Category.FOREGROUND,
                    "拉起到前台",
                    ExternalCommandContract.ACTION_MOVE_TO_FOREGROUND,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 400ms",
                    "仅将应用拉起到前台；若独立小窗预览正在显示，会同步关闭并回到主界面。",
                    ""
            ),
            new BroadcastItem(
                    "move_to_background",
                    Category.FOREGROUND,
                    "退回到后台",
                    ExternalCommandContract.ACTION_MOVE_TO_BACKGROUND,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 400ms",
                    "仅将当前界面静默退回后台，不中断正在进行的后台录像。",
                    ""
            ),
            new BroadcastItem(
                    "toggle_recording",
                    Category.RECORDING,
                    "开始/停止录像（切换）",
                    ExternalCommandContract.ACTION_TOGGLE_RECORDING,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 1000ms",
                    "正在录像则停止；未在录像则开始录制（优先就地开启，必要时自动拉起）。",
                    ""
            ),
            new BroadcastItem(
                    "start_recording",
                    Category.RECORDING,
                    "开始录像",
                    ExternalCommandContract.ACTION_START_RECORDING,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 1000ms",
                    "开始持续录像；若已在录像中则自动忽略，不重复建流。",
                    ""
            ),
            new BroadcastItem(
                    "stop_recording",
                    Category.RECORDING,
                    "停止录像",
                    ExternalCommandContract.ACTION_STOP_RECORDING,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 1000ms",
                    "停止当前录像，保持应用当前前后台状态不变。",
                    ""
            ),
            new BroadcastItem(
                    "take_photo",
                    Category.RECORDING,
                    "一键抓拍",
                    ExternalCommandContract.ACTION_TAKE_PHOTO,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 1000ms",
                    "在前台直接抓拍；在后台触发时会自动拉起抓拍，约 1.5 秒后自动退回后台。",
                    ""
            ),
            new BroadcastItem(
                    "open_preview_mode_a",
                    Category.PREVIEW_MODE,
                    "直达 360 A 视图",
                    ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_A,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 400ms",
                    "拉起主界面并开启主预览切至「360 A」；若已在前台记录仪页且当前即为 360 A，再发一次退回后台。",
                    ""
            ),
            new BroadcastItem(
                    "open_preview_mode_b",
                    Category.PREVIEW_MODE,
                    "直达分栏 B 视图",
                    ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_B,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 400ms",
                    "拉起主界面并开启主预览切至「分栏 B」；若已在前台记录仪页且当前即为分栏 B，再发一次退回后台。",
                    ""
            ),
            new BroadcastItem(
                    "open_preview_mode_c",
                    Category.PREVIEW_MODE,
                    "直达三路 C 视图",
                    ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_C,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 400ms",
                    "拉起主界面并开启主预览切至「三路 C」；若已在前台记录仪页且当前即为三路 C，再发一次退回后台。",
                    ""
            ),
            new BroadcastItem(
                    "open_preview_mode_d",
                    Category.PREVIEW_MODE,
                    "直达模式 D 视图",
                    ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_D,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 400ms",
                    "拉起主界面并开启主预览切至「模式 D」；若已在前台记录仪页且当前即为模式 D，再发一次退回后台。",
                    ""
            ),
            new BroadcastItem(
                    "open_preview_mode_e",
                    Category.PREVIEW_MODE,
                    "直达左右 E 视图",
                    ExternalCommandContract.ACTION_OPEN_PREVIEW_MODE_E,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 400ms",
                    "拉起主界面并开启主预览切至「左右 E」；若已在前台记录仪页且当前即为左右 E，再发一次退回后台。",
                    ""
            ),
            new BroadcastItem(
                    "switch_preview_mode",
                    Category.PREVIEW_MODE,
                    "循环/指定切换视图",
                    ExternalCommandContract.ACTION_SWITCH_PREVIEW_MODE,
                    EXTERNAL_COMMAND_RECEIVER,
                    "防抖 400ms",
                    "不带参数按 A→B→C→D→E 循环切换；也可通过 Extra 指定目标视图及是否再次触发退回后台。",
                    "可选 String Extra: mode（mode_a~mode_e 或 a~e）；可选 boolean Extra: toggle_background（true/false）"
            ),
            new BroadcastItem(
                    "keep_alive",
                    Category.SYSTEM,
                    "手动保活唤醒",
                    KeepAliveReceiver.ACTION_KEEP_ALIVE,
                    KEEP_ALIVE_RECEIVER,
                    "防抖 3000ms",
                    "触发一次保活状态检查并拉起后台保活服务（若用户已主动退出应用则忽略）。",
                    ""
            )
    ));

    private ExternalBroadcastCatalog() {
    }

    @NonNull
    public static List<BroadcastItem> getAllItems() {
        return ITEMS;
    }

    @NonNull
    public static List<BroadcastItem> filter(@Nullable Category category, @Nullable String keyword) {
        String normalized = keyword != null ? keyword.trim().toLowerCase(Locale.ROOT) : "";
        List<BroadcastItem> result = new ArrayList<>();
        for (BroadcastItem item : ITEMS) {
            if (category != null && item.category != category) {
                continue;
            }
            if (normalized.isEmpty()) {
                result.add(item);
                continue;
            }
            String haystack = (item.title + " "
                    + item.action + " "
                    + item.description + " "
                    + item.extrasHint + " "
                    + item.category.getLabel()).toLowerCase(Locale.ROOT);
            if (haystack.contains(normalized)) {
                result.add(item);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @NonNull
    public static String resolvePackageName(@Nullable String packageName) {
        if (packageName == null) {
            return DEFAULT_PACKAGE_NAME;
        }
        String trimmed = packageName.trim();
        return trimmed.isEmpty() ? DEFAULT_PACKAGE_NAME : trimmed;
    }

    @NonNull
    public static String formatAdbCommand(@NonNull BroadcastItem item, @Nullable String packageName) {
        return "am broadcast -a " + item.action + " -p " + resolvePackageName(packageName);
    }

    @NonNull
    public static String formatConfigSnippet(@NonNull BroadcastItem item, @Nullable String packageName) {
        String pkg = resolvePackageName(packageName);
        StringBuilder sb = new StringBuilder();
        sb.append("功能：").append(item.title).append('\n');
        sb.append("目标包名：").append(pkg).append('\n');
        sb.append("接收器：").append(item.receiverClassName).append('\n');
        sb.append("Action：").append(item.action).append('\n');
        if (!item.extrasHint.isEmpty()) {
            sb.append("可选参数：").append(item.extrasHint).append('\n');
        }
        sb.append("防抖：").append(item.debounceLabel).append('\n');
        sb.append("说明：").append(item.description).append('\n');
        sb.append("ADB 命令：").append(formatAdbCommand(item, pkg));
        return sb.toString();
    }

    @NonNull
    public static String formatFullCatalogExport(@Nullable String packageName) {
        String pkg = resolvePackageName(packageName);
        StringBuilder sb = new StringBuilder();
        sb.append("EVCam 内置广播清单（共 ").append(ITEMS.size()).append(" 条）\n");
        sb.append("目标包名：").append(pkg).append("（请务必在第三方软件中填写显式包名）\n");
        sb.append("说明：后台通过广播拉起主界面需已授予悬浮窗权限（SYSTEM_ALERT_WINDOW）。\n");
        for (Category category : Category.values()) {
            sb.append("\n[").append(category.getLabel()).append("]\n");
            for (BroadcastItem item : ITEMS) {
                if (item.category != category) {
                    continue;
                }
                sb.append("- ").append(item.title).append("（").append(item.debounceLabel).append("）\n");
                sb.append("  Action: ").append(item.action).append('\n');
                if (!item.extrasHint.isEmpty()) {
                    sb.append("  Extra: ").append(item.extrasHint).append('\n');
                }
                sb.append("  ADB: ").append(formatAdbCommand(item, pkg)).append('\n');
                sb.append("  说明: ").append(item.description).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
