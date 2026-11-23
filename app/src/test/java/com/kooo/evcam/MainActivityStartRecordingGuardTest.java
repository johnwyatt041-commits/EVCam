package com.kooo.evcam;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainActivityStartRecordingGuardTest {

    @Test
    public void shortCircuitsStartRecordingIntentWheneverAnyRecordingStateIsActive() {
        assertFalse(MainActivity.shouldShortCircuitStartRecordingIntent(false, false, false, false));

        assertTrue(MainActivity.shouldShortCircuitStartRecordingIntent(true, false, false, false));
        assertTrue(MainActivity.shouldShortCircuitStartRecordingIntent(false, true, false, false));
        assertTrue(MainActivity.shouldShortCircuitStartRecordingIntent(false, false, true, false));
        assertTrue(MainActivity.shouldShortCircuitStartRecordingIntent(false, false, false, true));
        assertTrue(MainActivity.shouldShortCircuitStartRecordingIntent(true, false, false, true));
    }
}
