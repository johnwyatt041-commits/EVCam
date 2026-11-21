package com.kooo.evcam.blindspot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.EvccDashcastDisplayResolver;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class BlindSpotPlacementAndCorrectionTest {

    @Test
    public void testPlacementDefaultsAndPixelConversion2560x1600() {
        BlindSpotPlacement.RectRatio left = BlindSpotPlacement.defaultForSlot(BlindSpotPlacement.SLOT_LEFT);
        assertEquals(2, left.xPercent());
        assertEquals(20, left.yPercent());
        assertEquals(42, left.widthPercent());
        assertEquals(60, left.heightPercent());

        int[] pxMain = left.toPixelBounds(2560, 1600);
        assertEquals(51, pxMain[0]);
        assertEquals(320, pxMain[1]);
        assertEquals(1075, pxMain[2]);
        assertEquals(960, pxMain[3]);

        // Different HUD resolution scales proportionally
        int[] pxHud = left.toPixelBounds(1920, 720);
        assertEquals(38, pxHud[0]);
        assertEquals(144, pxHud[1]);
        assertEquals(806, pxHud[2]);
        assertEquals(432, pxHud[3]);
    }

    @Test
    public void testPlacementClampingAndResizeBounds() {
        BlindSpotPlacement.RectRatio tooSmall = new BlindSpotPlacement.RectRatio(0.95f, 0.95f, 0.05f, 0.05f);
        assertEquals(BlindSpotPlacement.MIN_WIDTH_RATIO, tooSmall.width, 1e-4f);
        assertEquals(BlindSpotPlacement.MIN_HEIGHT_RATIO, tooSmall.height, 1e-4f);
        assertEquals(0.85f, tooSmall.x, 1e-4f);
        assertEquals(0.85f, tooSmall.y, 1e-4f);

        BlindSpotPlacement.RectRatio base = new BlindSpotPlacement.RectRatio(0.36f, 0.31f, 0.42f, 0.60f);
        BlindSpotPlacement.RectRatio movedPastEdge = base.movedBy(0.50f, 0.50f);
        assertEquals(0.58f, movedPastEdge.x, 1e-4f);
        assertEquals(0.40f, movedPastEdge.y, 1e-4f);

        BlindSpotPlacement.RectRatio resizedPastEdge = base.resizedRightBottomBy(0.50f, 0.50f);
        assertEquals(0.36f, resizedPastEdge.x, 1e-4f);
        assertEquals(0.31f, resizedPastEdge.y, 1e-4f);
        assertEquals(0.64f, resizedPastEdge.width, 1e-4f);
        assertEquals(0.69f, resizedPastEdge.height, 1e-4f);
    }

    @Test
    public void testFromPixelsRoundTrip() {
        BlindSpotPlacement.RectRatio roundTrip = BlindSpotPlacement.fromPixels(
                922, 496, 1075, 960, 2560, 1600);
        assertEquals(36, roundTrip.xPercent());
        assertEquals(31, roundTrip.yPercent());
        assertEquals(42, roundTrip.widthPercent());
        assertEquals(60, roundTrip.heightPercent());
    }

    @Test
    public void testSideSyncAndRainSlotResolution() {
        assertTrue(BlindSpotPlacement.isNormalSideSlot(BlindSpotPlacement.SLOT_LEFT));
        assertTrue(BlindSpotPlacement.isNormalSideSlot(BlindSpotPlacement.SLOT_RIGHT));
        assertFalse(BlindSpotPlacement.isNormalSideSlot(BlindSpotPlacement.SLOT_RAIN_LEFT));
        assertFalse(BlindSpotPlacement.isNormalSideSlot(BlindSpotPlacement.SLOT_FRONT));

        assertEquals(BlindSpotPlacement.SLOT_RIGHT,
                BlindSpotPlacement.oppositeNormalSideSlot(BlindSpotPlacement.SLOT_LEFT));
        assertNull(BlindSpotPlacement.oppositeNormalSideSlot(BlindSpotPlacement.SLOT_RAIN_LEFT));

        assertEquals(BlindSpotPlacement.SLOT_RAIN_LEFT,
                BlindSpotPlacement.resolveSlotForCamera("left", true));
        assertEquals(BlindSpotPlacement.SLOT_RAIN_RIGHT,
                BlindSpotPlacement.resolveSlotForCamera("right", true));
        assertEquals(BlindSpotPlacement.SLOT_FRONT,
                BlindSpotPlacement.resolveSlotForCamera("front", true));
    }

    @Test
    public void testEdgeCropMathExcludesCroppedMarginsAndPreventsCollapse() {
        BlindSpotCorrectionMath.EdgeCrop cropLeftOnly = new BlindSpotCorrectionMath.EdgeCrop(0.20f, 0f, 0f, 0f);
        assertEquals(0.80f, cropLeftOnly.visibleWidth(), 1e-4f);
        assertEquals(1.00f, cropLeftOnly.visibleHeight(), 1e-4f);
        assertEquals(0.60f, cropLeftOnly.centerU(), 1e-4f);
        assertEquals(0.50f, cropLeftOnly.centerV(), 1e-4f);
        assertEquals(1.25f, cropLeftOnly.uniformZoom(), 1e-4f);

        // Verify point u = 0.20 (left cut line) lands at normalized view x = 0.0 after translate+scale
        float shiftedU = 0.20f + cropLeftOnly.preZoomShiftXNorm();
        float finalU = 0.5f + (shiftedU - 0.5f) * cropLeftOnly.uniformZoom();
        assertEquals(0.0f, finalU, 1e-4f);

        // Verify extreme opposite edges still preserve >= 20% visible band
        BlindSpotCorrectionMath.EdgeCrop extreme = new BlindSpotCorrectionMath.EdgeCrop(0.45f, 0.45f, 0.45f, 0.45f);
        assertTrue(extreme.visibleWidth() >= 0.20f - 1e-4f);
        assertTrue(extreme.visibleHeight() >= 0.20f - 1e-4f);
    }

    @Test
    public void testIsotropicCoverScaleCoversRotatedViewportWithoutBlackCorners() {
        // 1280x720 camera in 640x400 view @ 0 deg
        float s0 = BlindSpotCorrectionMath.computeIsotropicCoverScale(1280, 720, 640, 400, 0);
        assertEquals(400f / 720f, s0, 1e-4f);

        // 1280x720 camera in portrait 400x640 view @ 90 deg
        float s90 = BlindSpotCorrectionMath.computeIsotropicCoverScale(1280, 720, 400, 640, 90);
        assertEquals(400f / 720f, s90, 1e-4f);

        // Oblique 45 deg guarantees both rotated source dims exceed corner projections
        float s45 = BlindSpotCorrectionMath.computeIsotropicCoverScale(1280, 720, 640, 400, 45);
        double req = (640 + 400) / Math.sqrt(2.0);
        assertEquals((float) (req / 720.0), s45, 1e-3f);
    }

    @Test
    public void testVisualToLogicalPixelsAllOrientationsAnd180InversionFix() {
        int displayW = 1920;
        int displayH = 720;

        // 0°：视觉坐标与逻辑坐标一致
        BlindSpotPlacement.RectRatio rectA = new BlindSpotPlacement.RectRatio(0.10f, 0.20f, 0.30f, 0.40f);
        int[] log0 = BlindSpotPlacement.visualToLogicalPixels(rectA, displayW, displayH, 0);
        assertEquals(192, log0[0]);
        assertEquals(144, log0[1]);
        assertEquals(576, log0[2]);
        assertEquals(288, log0[3]);
        assertEquals(rectA, BlindSpotPlacement.logicalToVisualRect(
                log0[0], log0[1], log0[2], log0[3], displayW, displayH, 0));

        // 180°（E5 倒置副屏）：往右挪（vx 增大）逻辑 lx 必须减小；往下挪（vy 增大）逻辑 ly 必须减小
        BlindSpotPlacement.RectRatio rectRightDown = new BlindSpotPlacement.RectRatio(0.25f, 0.35f, 0.30f, 0.40f);
        int[] log180A = BlindSpotPlacement.visualToLogicalPixels(rectA, displayW, displayH, 180);
        int[] log180B = BlindSpotPlacement.visualToLogicalPixels(rectRightDown, displayW, displayH, 180);
        assertEquals(1920 - 192 - 576, log180A[0]);
        assertEquals(720 - 144 - 288, log180A[1]);
        assertTrue("180度屏幕视觉右移应使逻辑X减小", log180B[0] < log180A[0]);
        assertTrue("180度屏幕视觉下移应使逻辑Y减小", log180B[1] < log180A[1]);
        assertEquals(rectRightDown, BlindSpotPlacement.logicalToVisualRect(
                log180B[0], log180B[1], log180B[2], log180B[3], displayW, displayH, 180));

        // 90° 与 270°：可视宽高交换且正反变换精确闭合
        int[] visDims90 = BlindSpotPlacement.visualScreenDimensions(displayW, displayH, 90);
        assertEquals(720, visDims90[0]);
        assertEquals(1920, visDims90[1]);

        int[] log90 = BlindSpotPlacement.visualToLogicalPixels(rectA, displayW, displayH, 90);
        BlindSpotPlacement.RectRatio round90 = BlindSpotPlacement.logicalToVisualRect(
                log90[0], log90[1], log90[2], log90[3], displayW, displayH, 90);
        assertTrue(Math.abs(rectA.x - round90.x) < 0.01f);
        assertTrue(Math.abs(rectA.y - round90.y) < 0.01f);
        assertTrue(Math.abs(rectA.width - round90.width) < 0.01f);
        assertTrue(Math.abs(rectA.height - round90.height) < 0.01f);

        int[] log270 = BlindSpotPlacement.visualToLogicalPixels(rectA, displayW, displayH, 270);
        BlindSpotPlacement.RectRatio round270 = BlindSpotPlacement.logicalToVisualRect(
                log270[0], log270[1], log270[2], log270[3], displayW, displayH, 270);
        assertTrue(Math.abs(rectA.x - round270.x) < 0.01f);
        assertTrue(Math.abs(rectA.y - round270.y) < 0.01f);
        assertTrue(Math.abs(rectA.width - round270.width) < 0.01f);
        assertTrue(Math.abs(rectA.height - round270.height) < 0.01f);
    }

    @Test
    public void testPerScreenIsolationFourWaySyncOutputModesAndLegacyMigration() {
        Map<String, Object> store = new HashMap<>();
        AppConfig config = createMockAppConfig(store);

        // 输出模式切换：仅主屏 / 仅副屏 / 主副屏同时
        assertEquals(AppConfig.BLIND_SPOT_OUTPUT_MAIN_ONLY, config.getBlindSpotOutputMode());
        assertTrue(config.isMainBlindSpotEnabled());
        assertFalse(config.isSecondaryDisplayEnabled());

        config.setBlindSpotOutputMode(AppConfig.BLIND_SPOT_OUTPUT_SECONDARY_ONLY);
        assertEquals(AppConfig.BLIND_SPOT_OUTPUT_SECONDARY_ONLY, config.getBlindSpotOutputMode());
        assertFalse(config.isMainBlindSpotEnabled());
        assertTrue(config.isSecondaryDisplayEnabled());

        config.setBlindSpotOutputMode(AppConfig.BLIND_SPOT_OUTPUT_BOTH);
        assertEquals(AppConfig.BLIND_SPOT_OUTPUT_BOTH, config.getBlindSpotOutputMode());
        assertTrue(config.isMainBlindSpotEnabled());
        assertTrue(config.isSecondaryDisplayEnabled());

        // 屏幕键映射与默认方向：物理副屏默认 180°，EVCC 默认 0°
        assertEquals(BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.screenKeyForDisplay(-1));
        assertEquals(BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.screenKeyForDisplay(0));
        assertEquals(BlindSpotPlacement.SCREEN_EVCC,
                BlindSpotPlacement.screenKeyForDisplay(EvccDashcastDisplayResolver.PICK_ID));
        assertEquals("display_2", BlindSpotPlacement.screenKeyForDisplay(2));
        assertEquals(0, config.getBlindSpotScreenOrientation(BlindSpotPlacement.SCREEN_MAIN));
        assertEquals(180, config.getBlindSpotScreenOrientation("display_2"));
        assertEquals(0, config.getBlindSpotScreenOrientation(BlindSpotPlacement.SCREEN_EVCC));

        // 不同屏幕、不同摄像头参数独立互不覆盖
        config.setBlindSpotScreenCameraRotation(BlindSpotPlacement.SCREEN_MAIN, "left", 90);
        config.setBlindSpotScreenCameraRotation("display_2", "left", 270);
        config.setBlindSpotScreenCameraRotation("display_2", "right", 45);
        config.setBlindSpotScreenCameraEdgeCrop("display_2", "left",
                BlindSpotCorrectionMath.EdgeCrop.fromPercents(10, 5, 0, 15));

        assertEquals(90, config.getBlindSpotScreenCameraRotation(BlindSpotPlacement.SCREEN_MAIN, "left"));
        assertEquals(270, config.getBlindSpotScreenCameraRotation("display_2", "left"));
        assertEquals(45, config.getBlindSpotScreenCameraRotation("display_2", "right"));
        assertEquals(0f, config.getBlindSpotScreenCameraEdgeCrop(BlindSpotPlacement.SCREEN_MAIN, "left").left, 1e-4f);
        assertEquals(0.10f, config.getBlindSpotScreenCameraEdgeCrop("display_2", "left").left, 1e-4f);

        // 主屏默认不开启四路位置同步；开启四路同步后修改任一路自动同步其余三路，但不覆盖雨夜槽位
        assertFalse(config.isBlindSpotSyncAllFourPositions(BlindSpotPlacement.SCREEN_MAIN));
        BlindSpotPlacement.RectRatio mainRainLeft = new BlindSpotPlacement.RectRatio(0.12f, 0.12f, 0.50f, 0.50f);
        config.setBlindSpotScreenPlacement(
                BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.SLOT_RAIN_LEFT, mainRainLeft, 2560, 1600);

        BlindSpotPlacement.RectRatio unifiedMain = new BlindSpotPlacement.RectRatio(0.30f, 0.25f, 0.40f, 0.50f);
        config.setBlindSpotScreenPlacement(
                BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.SLOT_RIGHT, unifiedMain, 2560, 1600);
        config.setBlindSpotSyncAllFourPositions(
                BlindSpotPlacement.SCREEN_MAIN, true, BlindSpotPlacement.SLOT_RIGHT, 2560, 1600);

        for (String slot : BlindSpotPlacement.STANDARD_FOUR_SLOTS) {
            assertEquals(unifiedMain,
                    config.getBlindSpotScreenPlacement(BlindSpotPlacement.SCREEN_MAIN, slot, 2560, 1600));
        }
        assertEquals(mainRainLeft, config.getBlindSpotScreenPlacement(
                BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.SLOT_RAIN_LEFT, 2560, 1600));

        // 开启四路同步后再改左路，其余三路联动更新
        BlindSpotPlacement.RectRatio movedUnified = new BlindSpotPlacement.RectRatio(0.45f, 0.20f, 0.40f, 0.50f);
        config.setBlindSpotScreenPlacement(
                BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.SLOT_LEFT, movedUnified, 2560, 1600);
        assertEquals(movedUnified, config.getBlindSpotScreenPlacement(
                BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.SLOT_FRONT, 2560, 1600));
        assertEquals(movedUnified, config.getBlindSpotScreenPlacement(
                BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.SLOT_BACK, 2560, 1600));
        assertEquals(movedUnified, config.getBlindSpotScreenPlacement(
                BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.SLOT_RIGHT, 2560, 1600));

        // 取消四路同步后恢复各自独立修改
        config.setBlindSpotSyncAllFourPositions(
                BlindSpotPlacement.SCREEN_MAIN, false, BlindSpotPlacement.SLOT_LEFT, 2560, 1600);
        BlindSpotPlacement.RectRatio frontOnly = new BlindSpotPlacement.RectRatio(0.10f, 0.10f, 0.30f, 0.30f);
        config.setBlindSpotScreenPlacement(
                BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.SLOT_FRONT, frontOnly, 2560, 1600);
        assertEquals(frontOnly, config.getBlindSpotScreenPlacement(
                BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.SLOT_FRONT, 2560, 1600));
        assertEquals(movedUnified, config.getBlindSpotScreenPlacement(
                BlindSpotPlacement.SCREEN_MAIN, BlindSpotPlacement.SLOT_RIGHT, 2560, 1600));

        // 旧副屏逻辑像素坐标自动迁移为视觉坐标
        store.put("secondary_display_id", 3);
        store.put("secondary_display_orientation", 180);
        store.put("secondary_display_x", 100);
        store.put("secondary_display_y", 80);
        store.put("secondary_display_width", 600);
        store.put("secondary_display_height", 300);
        BlindSpotPlacement.RectRatio migrated =
                config.getBlindSpotScreenPlacement("display_3", BlindSpotPlacement.SLOT_RIGHT, 1920, 720);
        int[] reEncodedLogical = BlindSpotPlacement.visualToLogicalPixels(migrated, 1920, 720, 180);
        assertEquals(100, reEncodedLogical[0]);
        assertEquals(80, reEncodedLogical[1]);
        assertEquals(600, reEncodedLogical[2]);
        assertEquals(300, reEncodedLogical[3]);
    }

    private AppConfig createMockAppConfig(Map<String, Object> backingStore) {
        Context context = mock(Context.class);
        SharedPreferences prefs = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);

        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
        when(prefs.edit()).thenReturn(editor);
        when(prefs.contains(anyString())).thenAnswer(inv -> backingStore.containsKey(inv.getArgument(0)));
        when(prefs.getString(anyString(), nullable(String.class))).thenAnswer(inv -> {
            Object val = backingStore.get(inv.getArgument(0));
            return val instanceof String ? (String) val : inv.getArgument(1);
        });
        when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            Object val = backingStore.get(inv.getArgument(0));
            return val instanceof Boolean ? (Boolean) val : inv.getArgument(1);
        });
        when(prefs.getInt(anyString(), anyInt())).thenAnswer(inv -> {
            Object val = backingStore.get(inv.getArgument(0));
            return val instanceof Integer ? (Integer) val : inv.getArgument(1);
        });
        when(prefs.getFloat(anyString(), anyFloat())).thenAnswer(inv -> {
            Object val = backingStore.get(inv.getArgument(0));
            return val instanceof Float ? (Float) val : inv.getArgument(1);
        });
        when(editor.putString(anyString(), nullable(String.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            if (value == null) backingStore.remove(key);
            else backingStore.put(key, value);
            return editor;
        });
        when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            backingStore.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.putInt(anyString(), anyInt())).thenAnswer(inv -> {
            backingStore.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.putFloat(anyString(), anyFloat())).thenAnswer(inv -> {
            backingStore.put(inv.getArgument(0), inv.getArgument(1));
            return editor;
        });
        when(editor.remove(anyString())).thenAnswer(inv -> {
            backingStore.remove(inv.getArgument(0));
            return editor;
        });
        return new AppConfig(context);
    }
}
