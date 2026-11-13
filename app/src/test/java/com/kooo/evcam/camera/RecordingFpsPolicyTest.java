package com.kooo.evcam.camera;
import android.util.Range;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RecordingFpsPolicyTest {
    @SuppressWarnings("unchecked") private Range<Integer> range(int low,int high) {
        Range<Integer> r=mock(Range.class); when(r.getLower()).thenReturn(low); when(r.getUpper()).thenReturn(high); return r;
    }
    @Test public void prefersFixedTargetAndNeverCreatesAnUnsupportedRange() {
        Range<Integer> fixed=range(25,25), variable=range(15,30);
        assertSame(fixed,RecordingFpsPolicy.choose(new Range[]{variable,fixed},25));
        assertSame(variable,RecordingFpsPolicy.choose(new Range[]{variable},25));
    }
    @Test public void usesClosestAdvertisedFallbackOrLeavesDefaultWhenUnavailable() {
        Range<Integer> low=range(15,15), high=range(30,30);
        assertSame(high,RecordingFpsPolicy.choose(new Range[]{low,high},25));
        assertNull(RecordingFpsPolicy.choose(null,25));
        assertNull(RecordingFpsPolicy.choose(new Range[0],25));
    }
}
