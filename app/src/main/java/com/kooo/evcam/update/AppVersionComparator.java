package com.kooo.evcam.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 Java 版本号与 GitHub Release Tag 解析比较器。
 * 支持格式：
 * - 1.6.6
 * - 1.6.6-test-11170004
 * - v1.6.6-test-11170004-build11
 */
public final class AppVersionComparator {

    private static final Pattern BUILD_SUFFIX_PATTERN = Pattern.compile("-build(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALID_VERSION_PATTERN = Pattern.compile("^[vV]?\\d+\\.\\d+(\\.\\d+)*(?:-[A-Za-z0-9._-]+)?$");

    private AppVersionComparator() {
    }

    public static final class ParsedVersion {
        public final String normalized;
        public final List<Integer> numericParts;
        public final boolean hasTest;
        public final String testTimestamp;
        public final int buildNumber;

        ParsedVersion(String normalized, List<Integer> numericParts,
                      boolean hasTest, String testTimestamp, int buildNumber) {
            this.normalized = normalized;
            this.numericParts = numericParts;
            this.hasTest = hasTest;
            this.testTimestamp = testTimestamp;
            this.buildNumber = buildNumber;
        }
    }

    public static String normalizeVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 1 && (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V')
                && Character.isDigit(trimmed.charAt(1))) {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    public static boolean isValidVersionFormat(String version) {
        if (version == null) {
            return false;
        }
        String trimmed = version.trim();
        return !trimmed.isEmpty() && VALID_VERSION_PATTERN.matcher(trimmed).matches();
    }

    public static ParsedVersion parse(String raw) {
        String normalized = normalizeVersion(raw);
        String mainPart = extractMainVersion(normalized);
        String[] segments = mainPart.split("\\.");
        List<Integer> nums = new ArrayList<>();
        for (String seg : segments) {
            nums.add(parseLeadingDigits(seg));
        }

        String lower = normalized.toLowerCase(Locale.US);
        boolean hasTest = lower.contains("-test-");
        String ts = hasTest ? extractTestDigits(lower) : "";

        int buildNum = -1;
        Matcher matcher = BUILD_SUFFIX_PATTERN.matcher(normalized);
        if (matcher.find()) {
            try {
                buildNum = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return new ParsedVersion(normalized, nums, hasTest, ts, buildNum);
    }

    /**
     * 判断 candidateVersion 是否严格高于 currentVersion。
     * 注意：若同为主版本号 + 相同 -test- 时间戳（例如已安装 1.6.6-test-11170004 对应 Tag v1.6.6-test-11170004-build11），
     * 当当前版本未携带 -buildN 时视作同一发行版，避免死循环误报更新。
     */
    public static boolean isNewerVersion(String candidateVersion, String currentVersion) {
        if (!isValidVersionFormat(candidateVersion)) {
            return false;
        }
        if (currentVersion == null || currentVersion.trim().isEmpty()
                || "unknown".equalsIgnoreCase(currentVersion.trim())
                || !isValidVersionFormat(currentVersion)) {
            return true;
        }

        ParsedVersion next = parse(candidateVersion);
        ParsedVersion curr = parse(currentVersion);

        int maxParts = Math.max(next.numericParts.size(), curr.numericParts.size());
        for (int i = 0; i < maxParts; i++) {
            int n = i < next.numericParts.size() ? next.numericParts.get(i) : 0;
            int c = i < curr.numericParts.size() ? curr.numericParts.get(i) : 0;
            if (n > c) {
                return true;
            }
            if (n < c) {
                return false;
            }
        }

        // 遵循现有规则：同主版本号下 -test- 更新于无后缀基准版本
        if (next.hasTest && !curr.hasTest) {
            return true;
        }
        if (!next.hasTest && curr.hasTest) {
            return false;
        }

        if (next.hasTest) {
            int tsCmp = compareNumericStrings(next.testTimestamp, curr.testTimestamp);
            if (tsCmp != 0) {
                return tsCmp > 0;
            }
            if (next.buildNumber >= 0 && curr.buildNumber >= 0) {
                return next.buildNumber > curr.buildNumber;
            }
            return false;
        }

        if (next.buildNumber >= 0 && curr.buildNumber >= 0) {
            return next.buildNumber > curr.buildNumber;
        }
        return false;
    }

    private static int compareNumericStrings(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        if (a.isEmpty()) return -1;
        if (b.isEmpty()) return 1;
        if (a.length() != b.length()) {
            try {
                return Long.compare(Long.parseLong(a), Long.parseLong(b));
            } catch (NumberFormatException ignored) {
            }
        }
        return a.compareTo(b);
    }

    private static String extractMainVersion(String normalized) {
        int dashIdx = normalized.indexOf('-');
        return dashIdx > 0 ? normalized.substring(0, dashIdx) : normalized;
    }

    private static String extractTestDigits(String lowerNormalized) {
        int idx = lowerNormalized.indexOf("-test-");
        if (idx < 0) {
            return "";
        }
        int start = idx + 6;
        StringBuilder digits = new StringBuilder();
        for (int i = start; i < lowerNormalized.length(); i++) {
            char ch = lowerNormalized.charAt(i);
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else {
                break;
            }
        }
        return digits.toString();
    }

    private static int parseLeadingDigits(String part) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < part.length(); i++) {
            char ch = part.charAt(i);
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
