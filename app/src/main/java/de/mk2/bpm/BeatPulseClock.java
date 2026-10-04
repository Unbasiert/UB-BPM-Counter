package de.mk2.bpm;

/** A phase-locked visual clock, with no Android dependencies or polling loop. */
public final class BeatPulseClock {
    private boolean active;
    private double periodMillis;
    private double anchorMillis;
    private long lastPulseMillis = Long.MIN_VALUE;

    public void update(double displayedBpm, double sourceBpm, double beatMillis, boolean enabled) {
        if (!enabled || !finite(displayedBpm) || !finite(sourceBpm)
                || !finite(beatMillis) || displayedBpm <= 0.0 || sourceBpm <= 0.0) {
            stop(); return;
        }
        double roundedBpm = Math.round(displayedBpm * 10.0) / 10.0;
        if (roundedBpm <= 0.0) { stop(); return; }
        double period = 60000.0 / roundedBpm;
        if (active && Math.abs(period / periodMillis - 1.0) < 0.10) {
            // Preserve the even/odd beat choice at half tempo. Phase updates
            // correct the source rhythm without toggling its subdivision.
            double phasePeriod = Math.min(period, 60000.0 / sourceBpm);
            double correction = beatMillis - anchorMillis;
            correction -= Math.rint(correction / phasePeriod) * phasePeriod;
            anchorMillis += correction;
        } else {
            anchorMillis = beatMillis;
        }
        periodMillis = period;
        active = true;
    }

    public boolean isActive() { return active; }
    public double getPeriodMillis() { return periodMillis; }

    /** Absolute deadlines avoid accumulating drift and skip delayed old beats. */
    public long nextPulseMillis(long now) {
        if (!active) return -1L;
        double earliest = now - 12.0;
        if (lastPulseMillis != Long.MIN_VALUE) earliest = Math.max(earliest, lastPulseMillis + periodMillis * 0.65);
        double number = Math.ceil((earliest - anchorMillis) / periodMillis);
        return Math.max(now, (long) Math.ceil(anchorMillis + number * periodMillis));
    }

    public void recordPulse(long now) { lastPulseMillis = now; }

    /** A fresh audio onset may light the current beat, but never a half-tempo offbeat
     * or the same beat that was already shown by the predictive clock. */
    public boolean acceptsLiveBeat(double beatMillis, long now) {
        if (!active || !finite(beatMillis)) return false;
        if (lastPulseMillis != Long.MIN_VALUE && now - lastPulseMillis < periodMillis * .65) return false;
        double error = beatMillis - anchorMillis;
        error -= Math.rint(error / periodMillis) * periodMillis;
        return Math.abs(error) < periodMillis * .20;
    }

    public void stop() {
        active = false;
        periodMillis = anchorMillis = 0.0;
        lastPulseMillis = Long.MIN_VALUE;
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
