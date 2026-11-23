package com.kooo.evcam.service;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import androidx.annotation.Nullable;

import com.kooo.evcam.KeepAliveAccessibilityService;

/**
 * Detects whether the OEM 360 / AVM interface is currently in the foreground.
 * Uses AccessibilityService metadata when available and falls back to incremental UsageEvents queries.
 */
public final class AvmForegroundDetector {

    private static final long INITIAL_LOOKBACK_MS = 300_000L;

    private String cachedTarget;
    private boolean cachedAvmForeground = false;
    private long lastQueryEndMs = 0L;

    public boolean isAvmInForeground(@Nullable Context context, @Nullable String configuredTarget) {
        String effectiveTarget = OverlayAvoidancePolicy.resolveEffectiveAvmTarget(configuredTarget);

        if (KeepAliveAccessibilityService.isRunning()) {
            String pkg = KeepAliveAccessibilityService.getLastForegroundPackage();
            String cls = KeepAliveAccessibilityService.getLastForegroundClass();
            if (pkg != null && !pkg.isEmpty()) {
                if (OverlayAvoidancePolicy.matchesAvmTarget(effectiveTarget, pkg, cls)) {
                    cachedTarget = effectiveTarget;
                    cachedAvmForeground = true;
                    return true;
                }
                if (!OverlayAvoidancePolicy.SYSTEM_UI_PACKAGE.equals(pkg)) {
                    cachedTarget = effectiveTarget;
                    cachedAvmForeground = false;
                    return false;
                }
            }
        }

        if (context == null) {
            return cachedAvmForeground;
        }

        long now = System.currentTimeMillis();
        if (!effectiveTarget.equals(cachedTarget) || now < lastQueryEndMs) {
            cachedTarget = effectiveTarget;
            cachedAvmForeground = false;
            lastQueryEndMs = 0L;
        }
        long from = lastQueryEndMs == 0L ? Math.max(0L, now - INITIAL_LOOKBACK_MS) : Math.max(0L, lastQueryEndMs - 1L);
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return cachedAvmForeground;
            }
            UsageEvents events = usm.queryEvents(from, now);
            if (events == null) {
                return cachedAvmForeground;
            }
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                int mappedType;
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    mappedType = OverlayAvoidancePolicy.EVENT_MOVE_TO_FOREGROUND;
                } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    mappedType = OverlayAvoidancePolicy.EVENT_MOVE_TO_BACKGROUND;
                } else {
                    continue;
                }
                cachedAvmForeground = OverlayAvoidancePolicy.applyUsageEvent(
                        cachedAvmForeground,
                        effectiveTarget,
                        mappedType,
                        event.getPackageName(),
                        event.getClassName()
                );
            }
            lastQueryEndMs = now;
        } catch (Exception ignored) {
            // UsageStats permission may be missing; keep last known state without crashing.
        }
        return cachedAvmForeground;
    }

    public void reset() {
        cachedTarget = null;
        cachedAvmForeground = false;
        lastQueryEndMs = 0L;
    }
}
