package com.kooo.evcam.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingFloatingServiceTimerTest {

    @Test
    public void formatDurationFormatsShortAndHourRangesAndClampsNegative() {
        assertEquals("00:00", RecordingFloatingService.formatDuration(-500L));
        assertEquals("00:00", RecordingFloatingService.formatDuration(0L));
        assertEquals("00:59", RecordingFloatingService.formatDuration(59_999L));
        assertEquals("01:00", RecordingFloatingService.formatDuration(60_000L));
        assertEquals("59:59", RecordingFloatingService.formatDuration(3_599_999L));
        assertEquals("01:00:00", RecordingFloatingService.formatDuration(3_600_000L));
        assertEquals("12:34:56", RecordingFloatingService.formatDuration(((12L * 3600L) + (34L * 60L) + 56L) * 1000L));
    }

    @Test
    public void hourThresholdSwitchesTemplateOnlyAtOneHourBoundary() {
        assertFalse(RecordingFloatingService.needsHourWidth(-1L));
        assertFalse(RecordingFloatingService.needsHourWidth(0L));
        assertFalse(RecordingFloatingService.needsHourWidth(3_599_999L));
        assertTrue(RecordingFloatingService.needsHourWidth(3_600_000L));

        assertEquals(RecordingFloatingService.TIME_TEMPLATE_SHORT,
                RecordingFloatingService.resolveTimeWidthTemplate(false));
        assertEquals(RecordingFloatingService.TIME_TEMPLATE_LONG,
                RecordingFloatingService.resolveTimeWidthTemplate(true));
    }

    @Test
    public void fixedDimensionsReserveTemplatePaddingAndSlackDeterministically() {
        int widthShort = RecordingFloatingService.computeFixedTimeWidthPx(42.2f, 10);
        assertEquals(43 + 20 + RecordingFloatingService.TIME_WIDTH_SLACK_PX, widthShort);

        int widthZero = RecordingFloatingService.computeFixedTimeWidthPx(-5f, -3);
        assertEquals(RecordingFloatingService.TIME_WIDTH_SLACK_PX, widthZero);

        int height = RecordingFloatingService.computeFixedTimeHeightPx(18.1f, 5);
        assertEquals(19 + 10, height);

        int minHeight = RecordingFloatingService.computeFixedTimeHeightPx(0f, 0);
        assertEquals(1, minHeight);
    }
}
