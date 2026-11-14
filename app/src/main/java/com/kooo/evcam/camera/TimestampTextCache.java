package com.kooo.evcam.camera;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** One formatted timestamp shared by recording GL threads for each wall-clock second. */
final class TimestampTextCache {
    private static final Date DATE = new Date();
    private static SimpleDateFormat formatter;
    private static Locale locale;
    private static TimeZone zone;
    private static long second = Long.MIN_VALUE;
    private static String text;

    static synchronized String get(long nowMs) {
        long currentSecond = Math.floorDiv(nowMs, 1000);
        if (formatter == null || second != currentSecond) {
            Locale currentLocale = Locale.getDefault();
            TimeZone currentZone = TimeZone.getDefault();
            if (formatter == null || !currentLocale.equals(locale) || !currentZone.equals(zone)) {
                locale = currentLocale;
                zone = currentZone;
                formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale);
                formatter.setTimeZone(zone);
            }
            DATE.setTime(nowMs);
            text = formatter.format(DATE);
            second = currentSecond;
        }
        return text;
    }

    private TimestampTextCache() {}
}
