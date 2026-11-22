package com.kooo.evcam.surround;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SurroundRenderThrottleTest {

    @Test
    public void coalescedOesRenderDelayRespectsFrameIntervalAndEdgeCases() {
        // 首次触发或关闭限频立即绘制
        assertEquals(0L, SurroundGlRenderer.computeOesRenderDelayMs(1000L, 0L, 33L));
        assertEquals(0L, SurroundGlRenderer.computeOesRenderDelayMs(1000L, 990L, 0L));

        // 窗口内到达的多路帧合并延迟到下一个周期
        assertEquals(25L, SurroundGlRenderer.computeOesRenderDelayMs(1008L, 1000L, 33L));
        assertEquals(1L, SurroundGlRenderer.computeOesRenderDelayMs(1032L, 1000L, 33L));

        // 达到或超过间隔后立即绘制
        assertEquals(0L, SurroundGlRenderer.computeOesRenderDelayMs(1033L, 1000L, 33L));
        assertEquals(0L, SurroundGlRenderer.computeOesRenderDelayMs(1100L, 1000L, 33L));

        // 时钟异常回拨时不应负延迟或卡住
        assertEquals(0L, SurroundGlRenderer.computeOesRenderDelayMs(900L, 1000L, 33L));
    }
}
