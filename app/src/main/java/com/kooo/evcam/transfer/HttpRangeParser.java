package com.kooo.evcam.transfer;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Pure HTTP single-range header parser supporting RFC 9110 byte ranges:
 * {@code bytes=start-end}, {@code bytes=start-}, and {@code bytes=-suffix}.
 * Multi-range requests (containing ',') and out-of-bounds/empty ranges resolve to INVALID (HTTP 416).
 */
public final class HttpRangeParser {

    public enum Status {
        FULL,
        PARTIAL,
        INVALID
    }

    public static final class RangeSpec {
        public final Status status;
        public final long start;
        public final long end;
        public final long totalLength;

        private RangeSpec(Status status, long start, long end, long totalLength) {
            this.status = status;
            this.start = start;
            this.end = end;
            this.totalLength = totalLength;
        }

        public static RangeSpec full(long totalLength) {
            long normalizedTotal = Math.max(0L, totalLength);
            long end = normalizedTotal == 0L ? -1L : normalizedTotal - 1L;
            return new RangeSpec(Status.FULL, 0L, end, normalizedTotal);
        }

        public static RangeSpec partial(long start, long end, long totalLength) {
            return new RangeSpec(Status.PARTIAL, start, end, totalLength);
        }

        public static RangeSpec invalid(long totalLength) {
            return new RangeSpec(Status.INVALID, -1L, -1L, Math.max(0L, totalLength));
        }

        public long contentLength() {
            if (status == Status.INVALID || totalLength == 0L) {
                return 0L;
            }
            return (end - start) + 1L;
        }

        public String toContentRangeHeader() {
            if (status == Status.INVALID) {
                return "bytes */" + totalLength;
            }
            return String.format(Locale.US, "bytes %d-%d/%d", start, end, totalLength);
        }
    }

    private HttpRangeParser() {}

    public static RangeSpec parse(@Nullable String rangeHeader, long totalLength) {
        if (totalLength < 0L) {
            return RangeSpec.invalid(0L);
        }
        if (rangeHeader == null || rangeHeader.trim().isEmpty()) {
            return RangeSpec.full(totalLength);
        }
        if (totalLength == 0L) {
            // Any non-empty Range header on an empty resource is unsatisfiable per RFC 9110.
            return RangeSpec.invalid(0L);
        }
        String trimmed = rangeHeader.trim();
        if (trimmed.length() < 6 || !trimmed.regionMatches(true, 0, "bytes=", 0, 6)) {
            return RangeSpec.invalid(totalLength);
        }
        String spec = trimmed.substring(6).trim();
        if (spec.isEmpty() || spec.indexOf(',') >= 0) {
            return RangeSpec.invalid(totalLength);
        }
        int dashIndex = spec.indexOf('-');
        if (dashIndex < 0) {
            return RangeSpec.invalid(totalLength);
        }

        String startPart = spec.substring(0, dashIndex).trim();
        String endPart = spec.substring(dashIndex + 1).trim();

        try {
            if (startPart.isEmpty()) {
                if (endPart.isEmpty()) {
                    return RangeSpec.invalid(totalLength);
                }
                long suffixLength = Long.parseLong(endPart);
                if (suffixLength <= 0L) {
                    return RangeSpec.invalid(totalLength);
                }
                long start = Math.max(0L, totalLength - suffixLength);
                return RangeSpec.partial(start, totalLength - 1L, totalLength);
            }

            long start = Long.parseLong(startPart);
            if (start < 0L || start >= totalLength) {
                return RangeSpec.invalid(totalLength);
            }
            if (endPart.isEmpty()) {
                return RangeSpec.partial(start, totalLength - 1L, totalLength);
            }
            long end = Long.parseLong(endPart);
            if (end < start) {
                return RangeSpec.invalid(totalLength);
            }
            long clampedEnd = Math.min(end, totalLength - 1L);
            return RangeSpec.partial(start, clampedEnd, totalLength);
        } catch (NumberFormatException e) {
            return RangeSpec.invalid(totalLength);
        }
    }
}
