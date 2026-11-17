package com.buiducluan23011767.ck;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HistoryPointTest {
    @Test
    public void irregularSamplesUseElapsedTimeInsteadOfIndex() {
        long start = 1_700_000_000_000L;
        assertEquals(0.0, HistoryPoint.timeFraction(start, start, start + 65_000), 0.000001);
        assertEquals(60.0 / 65.0,
                HistoryPoint.timeFraction(start + 60_000, start, start + 65_000), 0.000001);
        assertEquals(1.0, HistoryPoint.timeFraction(start + 65_000, start, start + 65_000), 0.000001);
    }

    @Test
    public void missingSensorReadingDoesNotCompressTimeGap() {
        long start = 1_700_000_000_000L;
        assertEquals(0.25, HistoryPoint.timeFraction(start + 15_000, start, start + 60_000), 0.000001);
        assertEquals(0.75, HistoryPoint.timeFraction(start + 45_000, start, start + 60_000), 0.000001);
    }

    @Test
    public void singleOrDuplicateTimestampIsCentered() {
        assertEquals(0.5, HistoryPoint.timeFraction(1234, 1234, 1234), 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void reversedTimeRangeIsRejected() {
        HistoryPoint.timeFraction(2000, 3000, 1000);
    }
}
