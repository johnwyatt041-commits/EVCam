package com.kooo.evcam.surround;

import com.kooo.evcam.surround.SurroundLayoutCalculator.SurroundLayoutSpec;
import com.kooo.evcam.surround.SurroundLayoutCalculator.ViewportBox;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 验证模式 A 左 360 鸟瞰 + 右上道路 + 右下三列车轮/双侧视与折叠态布局坐标。
 */
public class SurroundLayoutCalculatorTest {

    @Test
    public void wheelSplitLayoutPlacesBirdLeftRoadTopRightAndThreeColumnBottom() {
        SurroundLayoutSpec spec = SurroundLayoutCalculator.calculate(
                1920, 1080,
                SurroundViewMode.FRONT_WHEELS,
                false,
                206, 530, 0,
                16);

        assertFalse(spec.wheelsCollapsed);
        assertFalse(spec.birdBox.isEmpty());
        assertFalse(spec.topDetailBox.isEmpty());
        assertFalse(spec.bottomLeftBox.isEmpty());
        assertFalse(spec.bottomCenterCardBox.isEmpty());
        assertFalse(spec.bottomRightBox.isEmpty());

        // 左侧鸟瞰占据左侧区域且占满高度
        assertEquals(16, spec.birdBox.left);
        assertTrue(spec.birdBox.width >= 550 && spec.birdBox.width <= 820);
        assertEquals(1080 - 32, spec.birdBox.height);

        // 右上视窗位于鸟瞰右侧、左轮上方
        assertTrue(spec.topDetailBox.left > spec.birdBox.right());
        assertTrue(spec.topDetailBox.bottom() < spec.bottomBandBox.top);

        // 右下三列按左轮 < 中央控制卡 < 右轮水平排列且高度一致
        assertTrue(spec.bottomLeftBox.right() < spec.bottomCenterCardBox.left);
        assertTrue(spec.bottomCenterCardBox.right() < spec.bottomRightBox.left);
        assertEquals(spec.bottomLeftBox.height, spec.bottomRightBox.height);

        // OpenGL glY 与 UI top/bottom 转换正确
        assertEquals(0 + 16, spec.birdBox.glY(1080));
        assertTrue(spec.topDetailBox.glY(1080) > spec.bottomLeftBox.glY(1080));
    }

    @Test
    public void sidesAndCollapsedStatesAdjustBottomBoxesCleanly() {
        SurroundLayoutSpec sidesSpec = SurroundLayoutCalculator.calculate(
                1920, 1080,
                SurroundViewMode.SIDES,
                false,
                206, 530, 0,
                16);
        assertTrue(sidesSpec.bottomCenterCardBox.isEmpty());
        assertFalse(sidesSpec.bottomLeftBox.isEmpty());
        assertFalse(sidesSpec.bottomRightBox.isEmpty());
        assertTrue(sidesSpec.bottomLeftBox.width > 400);

        SurroundLayoutSpec collapsedSpec = SurroundLayoutCalculator.calculate(
                1920, 1080,
                SurroundViewMode.FRONT_WHEELS,
                true,
                206, 530, 0,
                16);
        assertTrue(collapsedSpec.wheelsCollapsed);
        assertEquals(collapsedSpec.birdBox.height, collapsedSpec.topDetailBox.height);
        assertTrue(collapsedSpec.bottomLeftBox.isEmpty());
        assertTrue(collapsedSpec.bottomCenterCardBox.isEmpty());
        assertTrue(collapsedSpec.bottomRightBox.isEmpty());
    }

    @Test
    public void birdOnlyBoxCentersAndPreservesGroundAspectAcrossDimensionChanges() {
        ViewportBox defaultBox = SurroundLayoutCalculator.calculateBirdOnlyBox(
                1000, 900, 206, 530, 0, 12);
        ViewportBox widerBox = SurroundLayoutCalculator.calculateBirdOnlyBox(
                1000, 900, 260, 530, 0, 12);
        ViewportBox extendedBox = SurroundLayoutCalculator.calculateBirdOnlyBox(
                1000, 900, 206, 600, 100, 12);

        assertFalse(defaultBox.isEmpty());
        assertEquals(12, defaultBox.top);
        assertEquals(900 - 24, defaultBox.height);
        // 水平居中
        assertEquals(1000 - defaultBox.right(), defaultBox.left, 1);

        // 地面加宽后，在等高受限下鸟瞰框按物理宽高比相应变宽
        assertTrue(widerBox.width > defaultBox.width);
        // 地面加长并增加车头延伸后，长宽比变瘦长，等高下宽度相应变窄
        assertTrue(extendedBox.width < defaultBox.width);

        assertTrue(SurroundLayoutCalculator.calculateBirdOnlyBox(0, 900, 206, 530, 0, 12).isEmpty());
    }

    @Test
    public void hidingCenterCardExpandsWheelPanesToEliminateEmptyGap() {
        SurroundLayoutSpec withCard = SurroundLayoutCalculator.calculate(
                1920, 1080,
                SurroundViewMode.FRONT_WHEELS,
                false,
                true,
                206, 530, 0,
                16);
        SurroundLayoutSpec noCard = SurroundLayoutCalculator.calculate(
                1920, 1080,
                SurroundViewMode.FRONT_WHEELS,
                false,
                false,
                206, 530, 0,
                16);

        assertFalse(withCard.bottomCenterCardBox.isEmpty());
        assertTrue(noCard.bottomCenterCardBox.isEmpty());
        assertTrue(noCard.bottomLeftBox.width > withCard.bottomLeftBox.width);
        assertTrue(noCard.bottomRightBox.width > withCard.bottomRightBox.width);
        assertEquals(noCard.bottomRightBox.left - noCard.bottomLeftBox.right(), 16);
    }
}
