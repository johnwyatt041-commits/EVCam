package com.kooo.evcam;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 13. 系统与权限集中诊断评估器（逻辑与 UI 解耦，支持纯 JVM 单测）。
 */
public final class PermissionDiagnostics {

    public enum Category {
        RUNTIME("应用基础权限"),
        SPECIAL("系统特殊权限与保活");

        @NonNull
        public final String label;

        Category(@NonNull String label) {
            this.label = label;
        }
    }

    public enum Priority {
        CORE("核心必需"),
        RECOMMENDED("推荐保障"),
        OPTIONAL("按需可选");

        @NonNull
        public final String label;

        Priority(@NonNull String label) {
            this.label = label;
        }
    }

    public enum Status {
        GRANTED,
        PARTIAL,
        DENIED,
        NOT_REQUIRED
    }

    public enum ActionKind {
        RUNTIME_REQUEST,
        APP_SETTINGS,
        OVERLAY_SETTINGS,
        ALL_FILES_SETTINGS,
        NOTIFICATION_SETTINGS,
        ACCESSIBILITY_SETTINGS,
        BATTERY_SETTINGS,
        USAGE_STATS_SETTINGS,
        ADB_REQUIRED
    }

    public enum ItemId {
        CAMERA,
        STORAGE_MEDIA,
        MICROPHONE,
        NOTIFICATIONS,
        OVERLAY,
        ALL_FILES,
        ACCESSIBILITY,
        BATTERY_OPTIMIZATION,
        USAGE_STATS,
        READ_LOGS
    }

    public static final class EnvironmentSnapshot {
        public final int sdkInt;
        public final boolean cameraGranted;
        public final boolean readMediaVideoGranted;
        public final boolean readMediaImagesGranted;
        public final boolean readExternalStorageGranted;
        public final boolean writeExternalStorageGranted;
        public final boolean recordAudioGranted;
        public final boolean postNotificationsGranted;
        public final boolean overlayGranted;
        public final boolean allFilesGranted;
        public final boolean accessibilitySettingEnabled;
        public final boolean accessibilityServiceActive;
        public final boolean ignoringBatteryOptimizations;
        public final boolean usageStatsGranted;
        public final boolean readLogsGranted;

        public EnvironmentSnapshot(
                int sdkInt,
                boolean cameraGranted,
                boolean readMediaVideoGranted,
                boolean readMediaImagesGranted,
                boolean readExternalStorageGranted,
                boolean writeExternalStorageGranted,
                boolean recordAudioGranted,
                boolean postNotificationsGranted,
                boolean overlayGranted,
                boolean allFilesGranted,
                boolean accessibilitySettingEnabled,
                boolean accessibilityServiceActive,
                boolean ignoringBatteryOptimizations,
                boolean usageStatsGranted,
                boolean readLogsGranted) {
            this.sdkInt = sdkInt;
            this.cameraGranted = cameraGranted;
            this.readMediaVideoGranted = readMediaVideoGranted;
            this.readMediaImagesGranted = readMediaImagesGranted;
            this.readExternalStorageGranted = readExternalStorageGranted;
            this.writeExternalStorageGranted = writeExternalStorageGranted;
            this.recordAudioGranted = recordAudioGranted;
            this.postNotificationsGranted = postNotificationsGranted;
            this.overlayGranted = overlayGranted;
            this.allFilesGranted = allFilesGranted;
            this.accessibilitySettingEnabled = accessibilitySettingEnabled;
            this.accessibilityServiceActive = accessibilityServiceActive;
            this.ignoringBatteryOptimizations = ignoringBatteryOptimizations;
            this.usageStatsGranted = usageStatsGranted;
            this.readLogsGranted = readLogsGranted;
        }
    }

    public static final class DiagnosticItem {
        @NonNull
        public final ItemId id;
        @NonNull
        public final Category category;
        @NonNull
        public final Priority priority;
        @NonNull
        public final String title;
        @NonNull
        public final String purpose;
        @NonNull
        public final Status status;
        @NonNull
        public final String statusDetail;
        @NonNull
        public final String actionLabel;
        @NonNull
        public final ActionKind actionKind;
        @NonNull
        public final List<String> runtimePermissions;

        DiagnosticItem(
                @NonNull ItemId id,
                @NonNull Category category,
                @NonNull Priority priority,
                @NonNull String title,
                @NonNull String purpose,
                @NonNull Status status,
                @NonNull String statusDetail,
                @NonNull String actionLabel,
                @NonNull ActionKind actionKind,
                @NonNull List<String> runtimePermissions) {
            this.id = id;
            this.category = category;
            this.priority = priority;
            this.title = title;
            this.purpose = purpose;
            this.status = status;
            this.statusDetail = statusDetail;
            this.actionLabel = actionLabel;
            this.actionKind = actionKind;
            this.runtimePermissions = Collections.unmodifiableList(new ArrayList<>(runtimePermissions));
        }

        public boolean isReady() {
            return status == Status.GRANTED || status == Status.NOT_REQUIRED;
        }
    }

    public static final class DiagnosticReport {
        @NonNull
        public final List<DiagnosticItem> items;
        public final int coreReady;
        public final int coreTotal;
        public final int recommendedReady;
        public final int recommendedTotal;
        public final int optionalReady;
        public final int optionalTotal;
        @NonNull
        public final List<String> missingRuntimePermissions;

        DiagnosticReport(
                @NonNull List<DiagnosticItem> items,
                int coreReady,
                int coreTotal,
                int recommendedReady,
                int recommendedTotal,
                int optionalReady,
                int optionalTotal,
                @NonNull List<String> missingRuntimePermissions) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.coreReady = coreReady;
            this.coreTotal = coreTotal;
            this.recommendedReady = recommendedReady;
            this.recommendedTotal = recommendedTotal;
            this.optionalReady = optionalReady;
            this.optionalTotal = optionalTotal;
            this.missingRuntimePermissions = Collections.unmodifiableList(new ArrayList<>(missingRuntimePermissions));
        }

        @NonNull
        public String buildHeadlineText() {
            int missingCore = coreTotal - coreReady;
            int missingRec = recommendedTotal - recommendedReady;
            if (missingCore > 0) {
                return "缺少 " + missingCore + " 项核心必需权限（核心 " + coreReady + "/" + coreTotal + "），请优先授予";
            }
            if (missingRec > 0) {
                return "核心权限已就绪（" + coreReady + "/" + coreTotal + "），建议补齐 " + missingRec + " 项后台保障权限";
            }
            return "核心与保障权限已全部就绪（核心 " + coreReady + "/" + coreTotal + " · 保障 " + recommendedReady + "/" + recommendedTotal + "）";
        }
    }

    private PermissionDiagnostics() {
    }

    @NonNull
    public static EnvironmentSnapshot collectEnvironment(@NonNull Context context) {
        int sdk = Build.VERSION.SDK_INT;
        boolean camera = hasPerm(context, Manifest.permission.CAMERA);
        boolean mediaVideo = sdk < Build.VERSION_CODES.TIRAMISU
                || hasPerm(context, Manifest.permission.READ_MEDIA_VIDEO);
        boolean mediaImages = sdk < Build.VERSION_CODES.TIRAMISU
                || hasPerm(context, Manifest.permission.READ_MEDIA_IMAGES);
        boolean readExt = sdk >= Build.VERSION_CODES.TIRAMISU
                || hasPerm(context, Manifest.permission.READ_EXTERNAL_STORAGE);
        boolean writeExt = sdk >= Build.VERSION_CODES.TIRAMISU
                || hasPerm(context, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        boolean audio = hasPerm(context, Manifest.permission.RECORD_AUDIO);
        boolean notif = sdk < Build.VERSION_CODES.TIRAMISU
                || hasPerm(context, Manifest.permission.POST_NOTIFICATIONS);
        boolean overlay = WakeUpHelper.hasOverlayPermission(context);
        boolean allFiles = sdk < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
        boolean accEnabled = AccessibilityHelper.isAccessibilityServiceEnabled(context);
        boolean accActive = KeepAliveAccessibilityService.isAccessibilityServiceActive();
        boolean battery = WakeUpHelper.isIgnoringBatteryOptimizations(context);
        boolean usageStats = hasUsageStatsPermission(context);
        boolean readLogs = hasPerm(context, Manifest.permission.READ_LOGS);

        return new EnvironmentSnapshot(
                sdk,
                camera,
                mediaVideo,
                mediaImages,
                readExt,
                writeExt,
                audio,
                notif,
                overlay,
                allFiles,
                accEnabled,
                accActive,
                battery,
                usageStats,
                readLogs
        );
    }

    @NonNull
    public static DiagnosticReport evaluate(@NonNull EnvironmentSnapshot env) {
        List<DiagnosticItem> items = new ArrayList<>();

        // 1. CAMERA
        items.add(new DiagnosticItem(
                ItemId.CAMERA,
                Category.RUNTIME,
                Priority.CORE,
                "相机权限",
                "四路预览、日常录像、360 环视与远程拍照必需",
                env.cameraGranted ? Status.GRANTED : Status.DENIED,
                env.cameraGranted ? "已授权 ✓" : "未授权 - 无法打开摄像头",
                env.cameraGranted ? "已授权" : "去授权",
                ActionKind.RUNTIME_REQUEST,
                Collections.singletonList(Manifest.permission.CAMERA)
        ));

        // 2. STORAGE / MEDIA
        Status storageStatus;
        String storageDetail;
        List<String> storagePerms = new ArrayList<>();
        if (env.sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            storagePerms.add(Manifest.permission.READ_MEDIA_VIDEO);
            storagePerms.add(Manifest.permission.READ_MEDIA_IMAGES);
            if (env.readMediaVideoGranted && env.readMediaImagesGranted) {
                storageStatus = Status.GRANTED;
                storageDetail = "已授权 ✓（视频与图片媒体读取）";
            } else if (env.readMediaVideoGranted || env.readMediaImagesGranted) {
                storageStatus = Status.PARTIAL;
                storageDetail = "部分授权 - 建议同时授予视频与图片读取";
            } else {
                storageStatus = Status.DENIED;
                storageDetail = "未授权 - 回看及媒体读写受限";
            }
        } else {
            storagePerms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            storagePerms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (env.readExternalStorageGranted && env.writeExternalStorageGranted) {
                storageStatus = Status.GRANTED;
                storageDetail = "已授权 ✓（存储读写）";
            } else if (env.readExternalStorageGranted || env.writeExternalStorageGranted) {
                storageStatus = Status.PARTIAL;
                storageDetail = "部分授权 - 读写权限不完整";
            } else {
                storageStatus = Status.DENIED;
                storageDetail = "未授权 - 无法保存和读取录像照片";
            }
        }
        items.add(new DiagnosticItem(
                ItemId.STORAGE_MEDIA,
                Category.RUNTIME,
                Priority.CORE,
                env.sdkInt >= Build.VERSION_CODES.TIRAMISU ? "媒体读取权限" : "存储读写权限",
                "保存和回看本地录像、照片与缩略图",
                storageStatus,
                storageDetail,
                storageStatus == Status.GRANTED ? "已授权" : "去授权",
                ActionKind.RUNTIME_REQUEST,
                storagePerms
        ));

        // 3. MICROPHONE
        items.add(new DiagnosticItem(
                ItemId.MICROPHONE,
                Category.RUNTIME,
                Priority.OPTIONAL,
                "麦克风权限",
                "录制视频时同步录音（可选，未授权时静默录像）",
                env.recordAudioGranted ? Status.GRANTED : Status.DENIED,
                env.recordAudioGranted ? "已授权 ✓" : "未授权 - 录制视频时无声音",
                env.recordAudioGranted ? "已授权" : "去授权",
                ActionKind.RUNTIME_REQUEST,
                Collections.singletonList(Manifest.permission.RECORD_AUDIO)
        ));

        // 4. NOTIFICATIONS (Android 13+)
        if (env.sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            items.add(new DiagnosticItem(
                    ItemId.NOTIFICATIONS,
                    Category.RUNTIME,
                    Priority.RECOMMENDED,
                    "通知权限",
                    "显示后台录像前台服务、局域网分享状态与异常提醒",
                    env.postNotificationsGranted ? Status.GRANTED : Status.DENIED,
                    env.postNotificationsGranted ? "已授权 ✓" : "未授权 - 无法显示前台运行状态通知",
                    env.postNotificationsGranted ? "已授权" : "去授权",
                    ActionKind.NOTIFICATION_SETTINGS,
                    Collections.singletonList(Manifest.permission.POST_NOTIFICATIONS)
            ));
        }

        // 5. OVERLAY
        items.add(new DiagnosticItem(
                ItemId.OVERLAY,
                Category.SPECIAL,
                Priority.RECOMMENDED,
                "悬浮窗权限",
                "转向补盲浮窗、独立预览小窗、录制悬浮按钮及 R/D 换挡自动弹出",
                env.overlayGranted ? Status.GRANTED : Status.DENIED,
                env.overlayGranted ? "已授权 ✓" : "未授权 - 悬浮窗与后台自动唤醒不可用",
                env.overlayGranted ? "已授权" : "去授权",
                ActionKind.OVERLAY_SETTINGS,
                Collections.emptyList()
        ));

        // 6. ALL FILES ACCESS (Android 11+)
        if (env.sdkInt >= Build.VERSION_CODES.R) {
            items.add(new DiagnosticItem(
                    ItemId.ALL_FILES,
                    Category.SPECIAL,
                    Priority.OPTIONAL,
                    "所有文件访问权限",
                    "将录像写入 U 盘公共目录及跨目录局域网分享/删除管理",
                    env.allFilesGranted ? Status.GRANTED : Status.DENIED,
                    env.allFilesGranted ? "已授权 ✓" : "未授权 - 无法直接读写外接 U 盘目录",
                    env.allFilesGranted ? "已授权" : "去授权",
                    ActionKind.ALL_FILES_SETTINGS,
                    Collections.emptyList()
            ));
        }

        // 7. ACCESSIBILITY
        Status accStatus;
        String accDetail;
        if (env.accessibilitySettingEnabled && env.accessibilityServiceActive) {
            accStatus = Status.GRANTED;
            accDetail = "已启用且服务运行中 ✓（支持 2032 高层级浮窗）";
        } else if (env.accessibilitySettingEnabled) {
            accStatus = Status.PARTIAL;
            accDetail = "系统开关已开，等待系统绑定服务（暂用 2038 浮窗）";
        } else {
            accStatus = Status.DENIED;
            accDetail = "未启用 - 后台保活与 2032 优先浮窗不可用";
        }
        items.add(new DiagnosticItem(
                ItemId.ACCESSIBILITY,
                Category.SPECIAL,
                Priority.RECOMMENDED,
                "无障碍保活服务",
                "提高车机后台生存优先级并启用高层级非抢占悬浮窗",
                accStatus,
                accDetail,
                accStatus == Status.GRANTED ? "已启用" : "去启用",
                ActionKind.ACCESSIBILITY_SETTINGS,
                Collections.emptyList()
        ));

        // 8. BATTERY OPTIMIZATION
        items.add(new DiagnosticItem(
                ItemId.BATTERY_OPTIMIZATION,
                Category.SPECIAL,
                Priority.RECOMMENDED,
                "电池优化白名单",
                "防止车机 Doze 深度休眠导致息屏断录或定时保活失效",
                env.ignoringBatteryOptimizations ? Status.GRANTED : Status.DENIED,
                env.ignoringBatteryOptimizations ? "已关闭优化 ✓" : "优化中 - 熄屏后应用可能被系统休眠",
                env.ignoringBatteryOptimizations ? "已设置" : "去设置",
                ActionKind.BATTERY_SETTINGS,
                Collections.emptyList()
        ));

        // 9. USAGE STATS
        items.add(new DiagnosticItem(
                ItemId.USAGE_STATS,
                Category.SPECIAL,
                Priority.OPTIONAL,
                "使用情况访问权限",
                "原车 360 全景影像避让时检测当前前台应用",
                env.usageStatsGranted ? Status.GRANTED : Status.DENIED,
                env.usageStatsGranted ? "已授权 ✓" : "未授权 - 原车全景影像前台避让不可用",
                env.usageStatsGranted ? "已授权" : "去授权",
                ActionKind.USAGE_STATS_SETTINGS,
                Collections.emptyList()
        ));

        // 10. READ_LOGS (diagnostic for Logcat mode)
        items.add(new DiagnosticItem(
                ItemId.READ_LOGS,
                Category.SPECIAL,
                Priority.OPTIONAL,
                "系统日志读取（READ_LOGS）",
                "仅在「10. 信号预设」选用 Logcat 匹配模式时需要（需 ADB 一键授权）",
                env.readLogsGranted ? Status.GRANTED : Status.DENIED,
                env.readLogsGranted ? "已授权 ✓" : "未授权 - 默认 VHAL gRPC 不受影响",
                env.readLogsGranted ? "已授权" : "ADB 授权",
                ActionKind.ADB_REQUIRED,
                Collections.emptyList()
        ));

        int coreReady = 0, coreTotal = 0;
        int recReady = 0, recTotal = 0;
        int optReady = 0, optTotal = 0;
        Set<String> missingRuntime = new LinkedHashSet<>();

        for (DiagnosticItem item : items) {
            boolean ready = item.isReady();
            switch (item.priority) {
                case CORE:
                    coreTotal++;
                    if (ready) coreReady++;
                    break;
                case RECOMMENDED:
                    recTotal++;
                    if (ready) recReady++;
                    break;
                case OPTIONAL:
                    optTotal++;
                    if (ready) optReady++;
                    break;
            }
            if (!ready && (item.actionKind == ActionKind.RUNTIME_REQUEST
                    || item.actionKind == ActionKind.NOTIFICATION_SETTINGS)) {
                missingRuntime.addAll(item.runtimePermissions);
            }
        }

        return new DiagnosticReport(
                items,
                coreReady, coreTotal,
                recReady, recTotal,
                optReady, optTotal,
                new ArrayList<>(missingRuntime)
        );
    }

    private static boolean hasPerm(@NonNull Context context, @NonNull String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean hasUsageStatsPermission(@NonNull Context context) {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            int uid = android.os.Process.myUid();
            String pkg = context.getPackageName();
            int mode = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ? appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, pkg)
                    : appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, pkg);
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception ignored) {
            return false;
        }
    }
}
