package com.kooo.evcam.transfer;

import org.junit.Test;

import static org.junit.Assert.*;

public class HttpRangeParserTest {

    @Test
    public void missingHeaderReturnsFullFile() {
        HttpRangeParser.RangeSpec spec = HttpRangeParser.parse(null, 4096L);
        assertEquals(HttpRangeParser.Status.FULL, spec.status);
        assertEquals(0L, spec.start);
        assertEquals(4095L, spec.end);
        assertEquals(4096L, spec.contentLength());
        assertEquals("bytes 0-4095/4096", spec.toContentRangeHeader());
    }

    @Test
    public void explicitStartEndAndClampingWork() {
        HttpRangeParser.RangeSpec spec = HttpRangeParser.parse("bytes=100-199", 1000L);
        assertEquals(HttpRangeParser.Status.PARTIAL, spec.status);
        assertEquals(100L, spec.start);
        assertEquals(199L, spec.end);
        assertEquals(100L, spec.contentLength());
        assertEquals("bytes 100-199/1000", spec.toContentRangeHeader());

        HttpRangeParser.RangeSpec clamped = HttpRangeParser.parse("bytes=900-5000", 1000L);
        assertEquals(HttpRangeParser.Status.PARTIAL, clamped.status);
        assertEquals(900L, clamped.start);
        assertEquals(999L, clamped.end);
        assertEquals(100L, clamped.contentLength());
    }

    @Test
    public void openEndedAndSuffixRangesWork() {
        HttpRangeParser.RangeSpec openEnded = HttpRangeParser.parse("bytes=750-", 1000L);
        assertEquals(HttpRangeParser.Status.PARTIAL, openEnded.status);
        assertEquals(750L, openEnded.start);
        assertEquals(999L, openEnded.end);
        assertEquals(250L, openEnded.contentLength());

        HttpRangeParser.RangeSpec suffix = HttpRangeParser.parse("bytes=-200", 1000L);
        assertEquals(HttpRangeParser.Status.PARTIAL, suffix.status);
        assertEquals(800L, suffix.start);
        assertEquals(999L, suffix.end);
        assertEquals(200L, suffix.contentLength());

        HttpRangeParser.RangeSpec oversizedSuffix = HttpRangeParser.parse("bytes=-5000", 1000L);
        assertEquals(HttpRangeParser.Status.PARTIAL, oversizedSuffix.status);
        assertEquals(0L, oversizedSuffix.start);
        assertEquals(999L, oversizedSuffix.end);
    }

    @Test
    public void invalidMultiRangeAndOutOfBoundsReturn416Spec() {
        assertEquals(HttpRangeParser.Status.INVALID, HttpRangeParser.parse("bytes=0-10, 20-30", 1000L).status);
        assertEquals(HttpRangeParser.Status.INVALID, HttpRangeParser.parse("bytes=50-10", 1000L).status);
        assertEquals(HttpRangeParser.Status.INVALID, HttpRangeParser.parse("bytes=1000-", 1000L).status);
        assertEquals(HttpRangeParser.Status.INVALID, HttpRangeParser.parse("bytes=-0", 1000L).status);
        assertEquals(HttpRangeParser.Status.INVALID, HttpRangeParser.parse("items=0-5", 1000L).status);

        HttpRangeParser.RangeSpec emptyFileRange = HttpRangeParser.parse("bytes=0-0", 0L);
        assertEquals(HttpRangeParser.Status.INVALID, emptyFileRange.status);
        assertEquals("bytes */0", emptyFileRange.toContentRangeHeader());
    }
}
