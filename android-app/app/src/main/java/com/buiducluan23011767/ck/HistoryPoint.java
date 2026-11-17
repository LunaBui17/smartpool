package com.buiducluan23011767.ck;

final class HistoryPoint {
    final long timeMs;
    final double value;

    HistoryPoint(long timeMs, double value) {
        if (timeMs <= 0 || !Double.isFinite(value)) {
            throw new IllegalArgumentException("Invalid history point");
        }
        this.timeMs = timeMs;
        this.value = value;
    }

    static double timeFraction(long timeMs, long startMs, long endMs) {
        if (endMs < startMs || timeMs < startMs || timeMs > endMs) {
            throw new IllegalArgumentException("Timestamp outside chart range");
        }
        return startMs == endMs ? 0.5 : (double) (timeMs - startMs) / (endMs - startMs);
    }
}
