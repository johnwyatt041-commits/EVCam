package com.kooo.evcam.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kooo.evcam.vehicle.VehicleSignalSnapshot;

import java.util.Locale;

/**
 * Pure policy for main-screen overlay avoidance during Reverse gear (R) and OEM 360/AVM display.
 * Prevents status/recording floating controls from flashing on top of the headunit OEM 360 camera view.
 */
public final class OverlayAvoidancePolicy {

    public static final String DEFAULT_AVM_ACTIVITY = "com.geely.avm_app.AvmRenderActivity";
    public static final String DEFAULT_AVM_PACKAGE = "com.geely.avm_app";
    public static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    public static final String SELF_PACKAGE = "com.kooo.evcam";
    public static final long BACKGROUND_SHOW_DEBOUNCE_MS = 450L;
    public static final long AVM_RECHECK_INTERVAL_MS = 1000L;
    public static final int EVENT_MOVE_TO_FOREGROUND = 1;
    public static final int EVENT_MOVE_TO_BACKGROUND = 2;

    private static final String[] OEM_VENDOR_PREFIXES = {
            "com.geely.",
            "com.ecarx.",
            "com.flyme.",
            "com.lynkco.",
            "com.zeekr.",
            "com.smart."
    };

    private static final String[] OEM_AVM_KEYWORDS = {
            "avm",
            "panoram",
            "surround",
            "360",
            ".apa",
            "apa_app",
            "apaactivity",
            "reversecamera",
            "rearcamera",
            "parkingcamera"
    };

    private static final String[] SYSTEM_UI_AVM_KEYWORDS = {
            "avm",
            "panoram",
            "surround360",
            "360camera",
            "rearcamera",
            "reversecamera"
    };

    private OverlayAvoidancePolicy() {
    }

    /**
     * Resolves the configured AVM target for main overlay suppression.
     * Never preserves BlindSpotService's "all" setting here so floating controls still work on launcher/other apps.
     */
    @NonNull
    public static String resolveEffectiveAvmTarget(@Nullable String configuredTarget) {
        if (configuredTarget == null) {
            return DEFAULT_AVM_ACTIVITY;
        }
        String trimmed = configuredTarget.trim();
        if (trimmed.isEmpty() || "all".equalsIgnoreCase(trimmed)) {
            return DEFAULT_AVM_ACTIVITY;
        }
        return trimmed;
    }

    @NonNull
    public static String extractPackageFromTarget(@NonNull String effectiveTarget) {
        int lastDot = effectiveTarget.lastIndexOf('.');
        if (lastDot > 0 && lastDot + 1 < effectiveTarget.length()) {
            char nextChar = effectiveTarget.charAt(lastDot + 1);
            if (Character.isUpperCase(nextChar)) {
                return effectiveTarget.substring(0, lastDot);
            }
        }
        return effectiveTarget;
    }

    public static boolean isDefaultAvmTarget(@Nullable String configuredTarget) {
        if (configuredTarget == null) {
            return true;
        }
        String trimmed = configuredTarget.trim();
        return trimmed.isEmpty()
                || "all".equalsIgnoreCase(trimmed)
                || DEFAULT_AVM_ACTIVITY.equals(trimmed)
                || DEFAULT_AVM_PACKAGE.equals(trimmed);
    }

    public static boolean matchesAvmTarget(@Nullable String configuredTarget,
                                          @Nullable String foregroundPackage,
                                          @Nullable String foregroundClass) {
        if (SELF_PACKAGE.equals(foregroundPackage)) {
            return false;
        }
        String effectiveTarget = resolveEffectiveAvmTarget(configuredTarget);
        String targetPkg = extractPackageFromTarget(effectiveTarget);
        if (foregroundClass != null && !foregroundClass.isEmpty()) {
            if (effectiveTarget.equals(foregroundClass)
                    || foregroundClass.startsWith(targetPkg + ".")) {
                return true;
            }
        }
        if (foregroundPackage != null && !foregroundPackage.isEmpty()
                && targetPkg.equals(foregroundPackage)) {
            return true;
        }
        if (!isDefaultAvmTarget(configuredTarget)) {
            return false;
        }
        return matchesDefaultOemAvm(foregroundPackage, foregroundClass);
    }

    private static boolean matchesDefaultOemAvm(@Nullable String foregroundPackage,
                                                @Nullable String foregroundClass) {
        String pkgLower = foregroundPackage != null
                ? foregroundPackage.toLowerCase(Locale.ROOT)
                : "";
        String clsLower = foregroundClass != null
                ? foregroundClass.toLowerCase(Locale.ROOT)
                : "";
        if (SYSTEM_UI_PACKAGE.equals(pkgLower)) {
            return containsAnyKeyword(clsLower, SYSTEM_UI_AVM_KEYWORDS);
        }
        String vendorSubject = !pkgLower.isEmpty() ? pkgLower : clsLower;
        boolean hasVendorPrefix = false;
        for (String prefix : OEM_VENDOR_PREFIXES) {
            if (vendorSubject.startsWith(prefix)) {
                hasVendorPrefix = true;
                break;
            }
        }
        if (!hasVendorPrefix) {
            return false;
        }
        return containsAnyKeyword(pkgLower, OEM_AVM_KEYWORDS)
                || containsAnyKeyword(clsLower, OEM_AVM_KEYWORDS);
    }

    private static boolean containsAnyKeyword(@NonNull String text, @NonNull String[] keywords) {
        if (text.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public static boolean applyUsageEvent(boolean currentAvmForeground,
                                          @Nullable String configuredTarget,
                                          int eventType,
                                          @Nullable String pkg,
                                          @Nullable String cls) {
        if (eventType == EVENT_MOVE_TO_FOREGROUND) {
            if (matchesAvmTarget(configuredTarget, pkg, cls)) {
                return true;
            }
            if (pkg != null && !pkg.isEmpty() && !SYSTEM_UI_PACKAGE.equals(pkg)) {
                return false;
            }
            return currentAvmForeground;
        }
        if (eventType == EVENT_MOVE_TO_BACKGROUND) {
            if (matchesAvmTarget(configuredTarget, pkg, cls)) {
                return false;
            }
        }
        return currentAvmForeground;
    }

    public static boolean isReverseGear(@Nullable VehicleSignalSnapshot snapshot) {
        return snapshot != null && snapshot.gear == VehicleSignalSnapshot.Gear.REVERSE;
    }

    public static boolean shouldSuppressMainOverlay(boolean appInForeground,
                                                    boolean reverseGear,
                                                    boolean avmForeground) {
        return appInForeground || reverseGear || avmForeground;
    }

    public static boolean shouldPollAvmExit(boolean backgroundWanted,
                                            boolean appInForeground,
                                            boolean avmForeground,
                                            boolean reverseGear) {
        return backgroundWanted && !appInForeground && avmForeground && !reverseGear;
    }

    public static boolean shouldWatchAvmForeground(boolean backgroundWanted,
                                                   boolean appInForeground,
                                                   boolean reverseGear) {
        return backgroundWanted && !appInForeground && !reverseGear;
    }
}
