package com.kooo.evcam.surround;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 覆盖 T07 分视角模式（SurroundViewMode）枚举、上下联动归一化与随档位解析规则。
 */
public class SurroundViewModeTest {

    @Test
    public void fromKeyParsesEnumsLabelsAndFallsBackToDrive() {
        assertEquals(SurroundViewMode.DRIVE, SurroundViewMode.fromKey("DRIVE"));
        assertEquals(SurroundViewMode.FRONT, SurroundViewMode.fromKey("front"));
        assertEquals(SurroundViewMode.REAR, SurroundViewMode.fromKey("后视"));
        assertEquals(SurroundViewMode.FRONT_WHEELS, SurroundViewMode.fromKey("FRONT_WHEELS"));
        assertEquals(SurroundViewMode.REAR_WHEELS, SurroundViewMode.fromKey("后轮"));
        assertEquals(SurroundViewMode.SIDES, SurroundViewMode.fromKey("左右侧视"));
        assertEquals(SurroundViewMode.DRIVE, SurroundViewMode.fromKey("unknown_key"));
        assertEquals(SurroundViewMode.DRIVE, SurroundViewMode.fromKey(null));
    }

    @Test
    public void detailViewNormalizationEnforcesMutualExclusivity() {
        SurroundViewMode[] frontWheelsTop = SurroundViewMode.normalizeDetailViews(
                SurroundViewMode.FRONT_WHEELS, SurroundViewMode.FRONT_WHEELS);
        assertEquals(SurroundViewMode.FRONT_WHEELS, frontWheelsTop[0]);
        assertEquals(SurroundViewMode.REAR_WHEELS, frontWheelsTop[1]);

        SurroundViewMode[] rearWheelsTop = SurroundViewMode.normalizeDetailViews(
                SurroundViewMode.REAR_WHEELS, SurroundViewMode.REAR_WHEELS);
        assertEquals(SurroundViewMode.REAR_WHEELS, rearWheelsTop[0]);
        assertEquals(SurroundViewMode.FRONT_WHEELS, rearWheelsTop[1]);

        SurroundViewMode[] sidesTop = SurroundViewMode.normalizeDetailViews(
                SurroundViewMode.SIDES, SurroundViewMode.FRONT_WHEELS);
        assertEquals(SurroundViewMode.DRIVE, sidesTop[0]);
        assertEquals(SurroundViewMode.SIDES, sidesTop[1]);

        SurroundViewMode[] normal = SurroundViewMode.normalizeDetailViews(
                SurroundViewMode.DRIVE, SurroundViewMode.REAR_WHEELS);
        assertEquals(SurroundViewMode.DRIVE, normal[0]);
        assertEquals(SurroundViewMode.REAR_WHEELS, normal[1]);
    }

    @Test
    public void driveModeFollowsGearForTopAndWheelViewsUnlessFixedOrOverridden() {
        // 随档位默认：非倒车 -> 前视 + 前轮；倒车 -> 后视 + 后轮
        assertEquals(SurroundViewMode.FRONT,
                SurroundViewMode.resolveEffectiveTopView(SurroundViewMode.DRIVE, false, null));
        assertEquals(SurroundViewMode.REAR,
                SurroundViewMode.resolveEffectiveTopView(SurroundViewMode.DRIVE, true, null));

        assertEquals(SurroundViewMode.FRONT_WHEELS,
                SurroundViewMode.resolveEffectiveBottomView(
                        SurroundViewMode.DRIVE, SurroundViewMode.FRONT_WHEELS, false, null));
        assertEquals(SurroundViewMode.REAR_WHEELS,
                SurroundViewMode.resolveEffectiveBottomView(
                        SurroundViewMode.DRIVE, SurroundViewMode.FRONT_WHEELS, true, null));

        // 上方固定为前视时，下方轮位不随倒车档位被迫变化
        assertEquals(SurroundViewMode.FRONT_WHEELS,
                SurroundViewMode.resolveEffectiveBottomView(
                        SurroundViewMode.FRONT, SurroundViewMode.FRONT_WHEELS, true, null));

        // 画面内手动覆盖
        assertEquals(SurroundViewMode.REAR,
                SurroundViewMode.resolveEffectiveTopView(SurroundViewMode.DRIVE, false, SurroundViewMode.REAR));
        assertEquals(SurroundViewMode.REAR_WHEELS,
                SurroundViewMode.resolveEffectiveBottomView(
                        SurroundViewMode.DRIVE, SurroundViewMode.FRONT_WHEELS, false, SurroundViewMode.REAR_WHEELS));
    }

    @Test
    public void badgeTextReflectsGearStateWithoutRadarOrEnvironmentPrompts() {
        String forward = SurroundViewMode.formatTopBadgeText(SurroundViewMode.FRONT, true, false);
        String reverse = SurroundViewMode.formatTopBadgeText(SurroundViewMode.REAR, true, true);
        String fixedRear = SurroundViewMode.formatTopBadgeText(SurroundViewMode.REAR, false, true);

        assertEquals("前视 · 随档位", forward);
        assertEquals("后视 · 倒车", reverse);
        assertEquals("后视", fixedRear);
        assertFalse(forward.contains("环境"));
        assertFalse(reverse.contains("雷达"));
    }
}
