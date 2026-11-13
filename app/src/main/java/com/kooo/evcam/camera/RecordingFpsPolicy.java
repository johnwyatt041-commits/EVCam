package com.kooo.evcam.camera;

import android.util.Range;

/** Selects only a range the camera actually advertises; encoder fps alone cannot set sensor fps. */
final class RecordingFpsPolicy {
    static Range<Integer> choose(Range<Integer>[] ranges, int target) {
        if (ranges == null || target <= 0) return null;
        Range<Integer> best = null;
        long bestScore = Long.MAX_VALUE;
        for (Range<Integer> range : ranges) {
            if (range == null || range.getLower() == null || range.getUpper() == null) continue;
            int lower = range.getLower(), upper = range.getUpper();
            if (lower <= 0 || upper < lower) continue;
            long score;
            if (lower == target && upper == target) score = 0;
            else if (lower <= target && upper >= target) score = 1000L + upper - lower;
            else score = 100000L + Math.abs((long) upper - target) * 100 + (upper - lower);
            if (score < bestScore) { best = range; bestScore = score; }
        }
        return best;
    }
}
