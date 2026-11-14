package com.kooo.evcam.camera;

import org.junit.Test;
import java.util.Locale;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class TimestampTextCacheTest {
    @Test public void sharedSecondChangesAndBackwardClockRemainCorrect() {
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.US);
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            String first = TimestampTextCache.get(1000);
            assertEquals("1970-01-01 00:00:01", first);
            assertSame(first, TimestampTextCache.get(1999));
            assertEquals("1970-01-01 00:00:02", TimestampTextCache.get(2000));
            assertEquals(first, TimestampTextCache.get(1000));
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"));
            assertEquals("1970-01-01 08:00:03", TimestampTextCache.get(3000));
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
        }
    }
}
