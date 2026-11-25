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

    public boolean onAccessibilityWindowEvent(@Nullable String configuredTarget,
                                            @Nullable String pkg,
                                            @Nullable String cls) {
        String effectiveTarget = OverlayAvoidancePolicy.resolveEffectiveAvmTarget(configuredTarget);
        if (!effectiveTarget.equals(cachedTarget)) {
            cachedTarget = effectiveTarget;
            cachedAvmForeground = false;
            lastQueryEndMs = 0L;
        }
        cachedAvmForeground = evaluateAccessibilityWindowState(cachedAvmForeground, effectiveTarget, pkg, cls);
        return cachedAvmForeground;
    }

    static boolean evaluateAccessibilityWindowState(boolean currentAvmForeground,
                                                  @Nullable String effectiveTarget,
                                                  @Nullable String pkg,
                                                  @Nullable String cls) {
        if (OverlayAvoidancePolicy.matchesAvmTarget(effectiveTarget, pkg, cls)) {
            return true;
        }
        if (pkg != null && !pkg.isEmpty()
                && !OverlayAvoidancePolicy.SYSTEM_UI_PACKAGE.equals(pkg)
                && !OverlayAvoidancePolicy.SELF_PACKAGE.equals(pkg)) {
            return false;
        }
        return currentAvmForeground;
    }

    public boolean isAvmInForeground(@Nullable Context context, @Nullable String configuredTarget) {
        String effectiveTarget = OverlayAvoidancePolicy.resolveEffectiveAvmTarget(configuredTarget);
        if (!effectiveTarget.equals(cachedTarget)) {
            cachedTarget = effectiveTarget;
            cachedAvmForeground = false;
            lastQueryEndMs = 0L;
        }

        String accPkg = null;
        String accCls = null;
        boolean accRunning = KeepAliveAccessibilityService.isRunning();
        if (accRunning) {
            accPkg = KeepAliveAccessibilityService.getLastForegroundPackage();
            accCls = KeepAliveAccessibilityService.getLastForegroundClass();
            if (accPkg != null && !accPkg.isEmpty()
                    && OverlayAvoidancePolicy.matchesAvmTarget(effectiveTarget, accPkg, accCls)) {
                cachedAvmForeground = true;
                return true;
            }
        }

        if (context == null) {
            if (accRunning) {
                cachedAvmForeground = evaluateAccessibilityWindowState(
                        cachedAvmForeground, effectiveTarget, accPkg, accCls);
            }
            return cachedAvmForeground;
        }

        long now = System.currentTimeMillis();
        if (now < lastQueryEndMs) {
            cachedAvmForeground = false;
            lastQueryEndMs = 0L;
        }
        long from = lastQueryEndMs == 0L ? Math.max(0L, now - INITIAL_LOOKBACK_MS) : Math.max(0L, lastQueryEndMs - 1L);
        boolean sawUsageEvent = false;
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                UsageEvents events = usm.queryEvents(from, now);
                if (events != null) {
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
                        sawUsageEvent = true;
                        cachedAvmForeground = OverlayAvoidancePolicy.applyUsageEvent(
                                cachedAvmForeground,
                                effectiveTarget,
                                mappedType,
                                event.getPackageName(),
                                event.getClassName()
                        );
                    }
                    lastQueryEndMs = now;
                }
            }
        } catch (Exception ignored) {
            // UsageStats permission may be missing; keep last known state without crashing.
        }
        if (accRunning && !sawUsageEvent) {
            cachedAvmForeground = evaluateAccessibilityWindowState(
                    cachedAvmForeground, effectiveTarget, accPkg, accCls);
        }
        return cachedAvmForeground;
    }

    public void reset() {
        cachedTarget = null;
        cachedAvmForeground = false;
        lastQueryEndMs = 0L;
    }
}
