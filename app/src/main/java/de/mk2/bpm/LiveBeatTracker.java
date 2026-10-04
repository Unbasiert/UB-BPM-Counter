package de.mk2.bpm;

/** LED-only transient tracker. It never changes the BPM estimator or its history. */
public final class LiveBeatTracker {
    private final double[] mean = new double[3];
    private double previousStrength;
    private double beforePreviousStrength;
    private double previousTime;
    private double previousRms;
    private double latestBeat = Double.NaN;
    private double deliveredBeat = Double.NEGATIVE_INFINITY;

    public void process(double low, double mid, double high, double rms, double seconds, double bpm) {
        double strength = Math.max(low / (mean[0] + .03),
            Math.max(.65 * mid / (mean[1] + .2), .35 * high / (mean[2] + .1)));
        double spacing = bpm > 0.0 ? Math.max(.16, .60 * 60.0 / bpm) : .20;
        if (previousRms >= .001 && previousStrength >= 2.5
                && previousStrength >= beforePreviousStrength && previousStrength > strength
                && (Double.isNaN(latestBeat) || previousTime - latestBeat >= spacing)) {
            latestBeat = previousTime;
        }
        double dt = Math.max(.001, Math.min(.1, seconds - previousTime));
        double alpha = 1.0 - Math.exp(-dt / .4);
        mean[0] += (low - mean[0]) * alpha;
        mean[1] += (mid - mean[1]) * alpha;
        mean[2] += (high - mean[2]) * alpha;
        beforePreviousStrength = previousStrength;
        previousStrength = rms >= .001 ? strength : 0.0;
        previousTime = seconds;
        previousRms = rms;
    }

    public double getLatestBeatSeconds() { return latestBeat; }

    /** Consume the freshest transient once per PCM buffer; no history-window wait. */
    public double pollBeatSeconds() {
        if (!(latestBeat > deliveredBeat)) return Double.NaN;
        deliveredBeat = latestBeat;
        return latestBeat;
    }

    public void reset() {
        java.util.Arrays.fill(mean, 0.0);
        previousStrength = beforePreviousStrength = previousTime = previousRms = 0.0;
        latestBeat = Double.NaN;
        deliveredBeat = Double.NEGATIVE_INFINITY;
    }
}
