package com.kooo.evcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;

import org.junit.Test;

/**
 * 13. 系统与权限集中诊断逻辑纯 JVM 单元测试：
 * 覆盖 Android API 28/30/34 差异化权限列表、媒体部分授权与无障碍未激活 PARTIAL 状态。
 */
public class PermissionDiagnosticsTest {

    @Test
    public void api34AllDeniedProducesCoreAndRecommendedBreakdownAndMissingRuntimeList() {
        PermissionDiagnostics.EnvironmentSnapshot env = new PermissionDiagnostics.EnvironmentSnapshot(
                34,
                false, // camera
                false, // readMediaVideo
                false, // readMediaImages
                false, // readExt
                false, // writeExt
                false, // recordAudio
                false, // postNotifications
                false, // overlay
                false, // allFiles
                false, // accEnabled
                false, // accActive
                false, // battery
                false, // usageStats
                false  // readLogs
        );

        PermissionDiagnostics.DiagnosticReport report = PermissionDiagnostics.evaluate(env);
        assertEquals(2, report.coreTotal);
        assertEquals(0, report.coreReady);
        assertEquals(4, report.recommendedTotal); // notifications + overlay + accessibility + battery
        assertEquals(0, report.recommendedReady);
        assertEquals(4, report.optionalTotal); // mic + allFiles + usageStats + readLogs
        assertEquals(0, report.optionalReady);

        assertTrue(report.missingRuntimePermissions.contains(Manifest.permission.CAMERA));
        assertTrue(report.missingRuntimePermissions.contains(Manifest.permission.READ_MEDIA_VIDEO));
        assertTrue(report.missingRuntimePermissions.contains(Manifest.permission.READ_MEDIA_IMAGES));
        assertTrue(report.missingRuntimePermissions.contains(Manifest.permission.RECORD_AUDIO));
        assertTrue(report.missingRuntimePermissions.contains(Manifest.permission.POST_NOTIFICATIONS));
        assertFalse(report.missingRuntimePermissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE));
        assertTrue(report.buildHeadlineText().contains("缺少 2 项核心必需权限"));
    }

    @Test
    public void api28UsesLegacyStorageAndOmitsNotificationsAndAllFiles() {
        PermissionDiagnostics.EnvironmentSnapshot env = new PermissionDiagnostics.EnvironmentSnapshot(
                28,
                true,  // camera
                true,  // mediaVideo (unused on 28)
                true,  // mediaImages (unused on 28)
                true,  // readExt
                true,  // writeExt
                true,  // recordAudio
                true,  // postNotifications
                true,  // overlay
                false, // allFiles (unused < 30)
                true,  // accEnabled
                true,  // accActive
                true,  // battery
                true,  // usageStats
                true   // readLogs
        );

        PermissionDiagnostics.DiagnosticReport report = PermissionDiagnostics.evaluate(env);
        assertEquals(2, report.coreTotal);
        assertEquals(2, report.coreReady);
        assertEquals(3, report.recommendedTotal); // overlay + accessibility + battery (no POST_NOTIFICATIONS < 33)
        assertEquals(3, report.recommendedReady);
        assertEquals(3, report.optionalTotal); // mic + usageStats + readLogs (no ALL_FILES < 30)
        assertEquals(3, report.optionalReady);
        assertTrue(report.missingRuntimePermissions.isEmpty());
        assertNull(findItem(report, PermissionDiagnostics.ItemId.NOTIFICATIONS));
        assertNull(findItem(report, PermissionDiagnostics.ItemId.ALL_FILES));
        assertTrue(report.buildHeadlineText().contains("核心与保障权限已全部就绪"));
    }

    @Test
    public void partialMediaAndInactiveAccessibilityReportPartialStatus() {
        PermissionDiagnostics.EnvironmentSnapshot env = new PermissionDiagnostics.EnvironmentSnapshot(
                34,
                true,  // camera
                true,  // mediaVideo granted
                false, // mediaImages denied -> PARTIAL
                true,
                true,
                true,  // mic
                true,  // notifications
                true,  // overlay
                true,  // allFiles
                true,  // accSettingEnabled
                false, // accServiceActive -> PARTIAL
                true,  // battery
                false, // usageStats
                false  // readLogs
        );

        PermissionDiagnostics.DiagnosticReport report = PermissionDiagnostics.evaluate(env);
        PermissionDiagnostics.DiagnosticItem storage = findItem(report, PermissionDiagnostics.ItemId.STORAGE_MEDIA);
        assertNotNull(storage);
        assertEquals(PermissionDiagnostics.Status.PARTIAL, storage.status);
        assertFalse(storage.isReady());

        PermissionDiagnostics.DiagnosticItem acc = findItem(report, PermissionDiagnostics.ItemId.ACCESSIBILITY);
        assertNotNull(acc);
        assertEquals(PermissionDiagnostics.Status.PARTIAL, acc.status);
        assertFalse(acc.isReady());

        assertEquals(1, report.coreReady);
        assertEquals(2, report.coreTotal);
        assertEquals(3, report.recommendedReady);
        assertEquals(4, report.recommendedTotal);
        assertTrue(report.missingRuntimePermissions.contains(Manifest.permission.READ_MEDIA_VIDEO));
        assertTrue(report.missingRuntimePermissions.contains(Manifest.permission.READ_MEDIA_IMAGES));
    }

    @Test
    public void coreReadyButMissingRecommendedShowsRecommendedPrompt() {
        PermissionDiagnostics.EnvironmentSnapshot env = new PermissionDiagnostics.EnvironmentSnapshot(
                30,
                true,  // camera
                true,
                true,
                true,  // readExt
                true,  // writeExt
                false, // mic
                true,
                false, // overlay missing
                true,  // allFiles present on API 30
                false, // accessibility missing
                false,
                true,  // battery
                false,
                false
        );

        PermissionDiagnostics.DiagnosticReport report = PermissionDiagnostics.evaluate(env);
        assertEquals(2, report.coreReady);
        assertEquals(2, report.coreTotal);
        assertEquals(1, report.recommendedReady);
        assertEquals(3, report.recommendedTotal);
        assertNotNull(findItem(report, PermissionDiagnostics.ItemId.ALL_FILES));
        assertTrue(report.buildHeadlineText().contains("建议补齐 2 项后台保障权限"));
    }

    private static PermissionDiagnostics.DiagnosticItem findItem(
            PermissionDiagnostics.DiagnosticReport report,
            PermissionDiagnostics.ItemId id) {
        for (PermissionDiagnostics.DiagnosticItem item : report.items) {
            if (item.id == id) {
                return item;
            }
        }
        return null;
    }
}
