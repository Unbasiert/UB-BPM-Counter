package de.mk2.bpm;

import java.util.Arrays;

/**
 * A bounded, monotonic-time tap estimator with no Android dependencies.
 *
 * Estimates start at the second tap. A robust expanding interval window makes
 * the first few taps responsive and a longer run steadier. Isolated missed
 * beats are divided by their likely beat count. Three similar intervals at a
 * different tempo deliberately start a new window, including half/double tempo.
 * There is no prediction of taps which have not happened.
 */
public final class TempoEstimator {
    public static final double MIN_BPM = 30.0;
    public static final double MAX_BPM = 300.0;
    private static final int CAPACITY = 24;
    private static final int CHANGE_INTERVALS = 3;
    private static final long STATE_VERSION = 2L;
    private final double[] intervals = new double[CAPACITY];
    private final double[] pending = new double[CHANGE_INTERVALS];
    private int intervalCount;
    private int pendingCount;
    private int tapCount;
    private long lastTapMillis = -1L;
    private long lastBeatMillis = -1L;
    private double periodMillis;

    /**
     * Pass a monotonic event timestamp in milliseconds (Android eventTime).
     * Returns false only for duplicate or backwards timestamps. Every distinct
     * contact counts immediately. Intervals shorter than 160 ms do not move
     * the beat origin or affect BPM; outlying intervals can be held out of the
     * estimate until subsequent taps clarify whether the tempo has changed.
     */
    public boolean tap(long eventTimeMillis) {
        if (eventTimeMillis < 0L) return false;
        if (lastTapMillis < 0L) {
            lastTapMillis = eventTimeMillis;
            lastBeatMillis = eventTimeMillis;
            tapCount = 1;
            return true;
        }
        if (eventTimeMillis <= lastTapMillis) return false;
        if (eventTimeMillis - lastTapMillis > restartAfterMillis()) {
            reset();
            lastTapMillis = eventTimeMillis;
            lastBeatMillis = eventTimeMillis;
            tapCount = 1;
            return true;
        }
        lastTapMillis = eventTimeMillis;
        if (tapCount < Integer.MAX_VALUE) tapCount++;
        final long elapsed = eventTimeMillis - lastBeatMillis;
        if (elapsed < 160L) return true;
        lastBeatMillis = eventTimeMillis;

        // Slight headroom permits human timing jitter at the advertised limits.
        final boolean plausibleSingleBeat = elapsed >= 160L && elapsed <= 2300L;
        if (intervalCount == 0) {
            if (plausibleSingleBeat) append(elapsed);
            return true;
        }

        double ratio = elapsed / periodMillis;
        // Startup has too little evidence to reject ordinary human jitter.
        // Keep the wider startup band away from obvious half/double intervals.
        boolean withinStartupBand = intervalCount < 3 && ratio >= 0.65 && ratio <= 1.55;
        if (plausibleSingleBeat && (withinStartupBand || (ratio >= 0.88 && ratio <= 1.12))) {
            pendingCount = 0;
            append(elapsed);
            return true;
        }

        if (plausibleSingleBeat) {
            if (pendingCount > 0 && Math.abs(elapsed / pending[0] - 1.0) > 0.10) {
                pendingCount = 0;
            }
            pending[pendingCount++] = elapsed;
            if (pendingCount == CHANGE_INTERVALS) {
                // A sustained new rhythm is deliberate, even at half/double BPM.
                intervalCount = 0;
                for (int i = 0; i < pendingCount; i++) append(pending[i]);
                pendingCount = 0;
                return true;
            }
        } else {
            pendingCount = 0;
        }

        // Correct a single missing beat only after an initial rhythm is known.
        // Repeated long intervals still trigger the tempo change above.
        if (intervalCount >= 3) {
            int beats = (int) Math.round(ratio);
            if (beats >= 2 && beats <= 3 && Math.abs(ratio / beats - 1.0) <= 0.12) {
                append((double) elapsed / beats);
            }
        }
        return true;
    }

    public void reset() {
        intervalCount = 0;
        pendingCount = 0;
        tapCount = 0;
        lastTapMillis = -1L;
        lastBeatMillis = -1L;
        periodMillis = 0.0;
        Arrays.fill(intervals, 0.0);
        Arrays.fill(pending, 0.0);
    }

    public double getBpm() {
        if (periodMillis <= 0.0) return 0.0;
        return Math.max(MIN_BPM, Math.min(MAX_BPM, 60000.0 / periodMillis));
    }

    public int getTapCount() { return tapCount; }

    /** A UI stability score, not a statistical confidence probability. */
    public double getConfidence() {
        if (intervalCount == 0) return 0.0;
        double[] deviations = new double[intervalCount];
        for (int i = 0; i < intervalCount; i++) {
            deviations[i] = Math.abs(intervals[i] - periodMillis);
        }
        Arrays.sort(deviations);
        double relativeJitter = median(deviations, intervalCount) / periodMillis;
        double stability = Math.max(0.0, 1.0 - relativeJitter / 0.16);
        double quantity = Math.min(1.0, intervalCount / 12.0);
        return stability * quantity * (pendingCount > 0 ? 0.75 : 1.0);
    }

    /** -1 before the first tap. */
    public long getLastTapMillis() { return lastTapMillis; }

    /** The last measured value stays visible during silence. */
    public boolean isHolding(long nowMillis) {
        return periodMillis > 0.0 && nowMillis > lastTapMillis
                && nowMillis - lastTapMillis > Math.max(1800.0, periodMillis * 2.5);
    }

    /** Primitive state suitable for an Android Bundle; contains no wall-clock time. */
    public long[] saveState() {
        long[] result = new long[6 + CAPACITY + CHANGE_INTERVALS];
        result[0] = STATE_VERSION;
        result[1] = lastTapMillis;
        result[2] = tapCount;
        result[3] = intervalCount;
        result[4] = pendingCount;
        for (int i = 0; i < CAPACITY; i++) {
            result[5 + i] = Double.doubleToLongBits(intervals[i]);
        }
        for (int i = 0; i < CHANGE_INTERVALS; i++) {
            result[5 + CAPACITY + i] = Double.doubleToLongBits(pending[i]);
        }
        result[result.length - 1] = lastBeatMillis;
        return result;
    }

    /** Invalid state is ignored and leaves the current estimate unchanged. */
    public boolean restoreState(long[] state) {
        if (state == null || !((state.length == 6 + CAPACITY + CHANGE_INTERVALS && state[0] == STATE_VERSION)
                || (state.length == 5 + CAPACITY + CHANGE_INTERVALS && state[0] == 1L))
                || state[1] < -1L
                || state[2] < 0L || state[2] > Integer.MAX_VALUE
                || state[3] < 0L || state[3] > CAPACITY
                || state[4] < 0L || state[4] >= CHANGE_INTERVALS) return false;
        int restoredCount = (int) state[3];
        int restoredPending = (int) state[4];
        long restoredBeat = state[0] == STATE_VERSION ? state[state.length - 1] : state[1];
        if ((state[2] == 0L) != (state[1] == -1L)
                || restoredBeat < -1L || restoredBeat > state[1]
                || (state[2] == 0L) != (restoredBeat == -1L)
                || state[2] < restoredCount + (restoredCount > 0 ? 1 : 0)
                || (restoredCount == 0 && restoredPending != 0)) return false;
        double[] restoredIntervals = new double[CAPACITY];
        double[] restoredCandidates = new double[CHANGE_INTERVALS];
        for (int i = 0; i < restoredCount; i++) {
            double value = Double.longBitsToDouble(state[5 + i]);
            if (!validInterval(value)) return false;
            restoredIntervals[i] = value;
        }
        for (int i = 0; i < restoredPending; i++) {
            double value = Double.longBitsToDouble(state[5 + CAPACITY + i]);
            if (!validInterval(value)) return false;
            restoredCandidates[i] = value;
        }
        System.arraycopy(restoredIntervals, 0, intervals, 0, CAPACITY);
        System.arraycopy(restoredCandidates, 0, pending, 0, CHANGE_INTERVALS);
        lastTapMillis = state[1];
        lastBeatMillis = restoredBeat;
        tapCount = (int) state[2];
        intervalCount = restoredCount;
        pendingCount = restoredPending;
        recalculate();
        return true;
    }

    private static boolean validInterval(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value)
                && value >= 140.0 && value <= 2300.0;
    }

    private long restartAfterMillis() {
        return (long) Math.max(3500.0, periodMillis * 3.5);
    }

    private void append(double interval) {
        if (intervalCount == CAPACITY) {
            System.arraycopy(intervals, 1, intervals, 0, CAPACITY - 1);
            intervalCount--;
        }
        intervals[intervalCount++] = Math.max(160.0, Math.min(2300.0, interval));
        recalculate();
    }

    private void recalculate() {
        if (intervalCount == 0) {
            periodMillis = 0.0;
            return;
        }
        double[] sorted = Arrays.copyOf(intervals, intervalCount);
        Arrays.sort(sorted);
        double centre = median(sorted, intervalCount);
        double sum = 0.0;
        double weights = 0.0;
        for (int i = 0; i < intervalCount; i++) {
            if (Math.abs(intervals[i] / centre - 1.0) <= 0.20) {
                // Mild recency weighting follows drift without amplifying jitter.
                double weight = 0.75 + 0.5 * (i + 1.0) / intervalCount;
                sum += intervals[i] * weight;
                weights += weight;
            }
        }
        periodMillis = weights > 0.0 ? sum / weights : centre;
    }

    private static double median(double[] sorted, int count) {
        int middle = count / 2;
        return count % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2.0 : sorted[middle];
    }
}
