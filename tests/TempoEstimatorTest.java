package de.mk2.bpm;

/** Deterministic, dependency-free behavior tests. Run with java de.mk2.bpm.TempoEstimatorTest. */
public final class TempoEstimatorTest {
    private static int checks;

    public static void main(String[] args) {
        initialAndExactTempo();
        humanTimingJitter();
        shortContactsPreserveTempo();
        missedBeats();
        sustainedTempoChanges();
        pauseAndReset();
        boundariesAndLongSessions();
        stateRoundTrip();
        System.out.println("TempoEstimator: " + checks + " checks passed.");
    }

    private static void initialAndExactTempo() {
        TempoEstimator tempo = new TempoEstimator();
        near(tempo.getBpm(), 0.0, 0.0, "empty BPM");
        equal(tempo.getTapCount(), 0, "empty count");
        equal(tempo.getLastTapMillis(), -1L, "empty time");
        check(tempo.tap(0L), "zero is a valid monotonic timestamp");
        near(tempo.getBpm(), 0.0, 0.0, "one tap cannot measure BPM");
        check(tempo.tap(500L), "second tap accepted");
        near(tempo.getBpm(), 120.0, 0.000001, "two taps estimate 120");
        tempo.tap(1000L);
        tempo.tap(1500L);
        tempo.tap(2000L);
        near(tempo.getBpm(), 120.0, 0.000001, "fifth tap estimate");
        check(!tempo.tap(2000L), "duplicate timestamp rejected");
        check(!tempo.tap(1900L), "backwards timestamp rejected");
        equal(tempo.getTapCount(), 5, "invalid taps do not count");
    }

    private static void humanTimingJitter() {
        TempoEstimator tempo = new TempoEstimator();
        int[] jitter = {0, 20, -13, 9, -8, 18, -17, 6, -4, 12, -11, 7, 0,
                -15, 14, -5, 9, -8, 12, -10, 6, -5, 8, -7, 0};
        double earlyConfidence = 0.0;
        for (int i = 0; i < jitter.length; i++) {
            tempo.tap(1000L + i * 500L + jitter[i]);
            if (i == 4) {
                near(tempo.getBpm(), 120.0, 2.0, "useful BPM after five imperfect taps");
                earlyConfidence = tempo.getConfidence();
            }
        }
        near(tempo.getBpm(), 120.0, 0.5, "longer tapping refines BPM");
        check(tempo.getConfidence() > earlyConfidence, "confidence rises with stable evidence");
        TempoEstimator unevenStart = new TempoEstimator();
        long[] taps = {1000L, 1520L, 1970L, 2510L, 3000L};
        for (long tap : taps) unevenStart.tap(tap);
        near(unevenStart.getBpm(), 120.0, 2.0, "startup tolerates early and late finger timing");
    }

    private static void shortContactsPreserveTempo() {
        TempoEstimator tempo = established(500L);
        long previous = tempo.getLastTapMillis();
        int previousCount = tempo.getTapCount();
        check(tempo.tap(previous + 65L), "quick separate contact accepted");
        check(tempo.tap(previous + 125L), "second quick contact accepted");
        equal(tempo.getTapCount(), previousCount + 2, "every new contact counts");
        equal(tempo.getLastTapMillis(), previous + 125L, "last contact timestamp retained");
        near(tempo.getBpm(), 120.0, 0.000001, "short contacts do not change BPM");
        check(!tempo.tap(previous + 65L), "backwards short contact rejected");
        TempoEstimator restored = new TempoEstimator();
        check(restored.restoreState(tempo.saveState()), "short contact state restores");
        restored.tap(previous + 500L);
        near(restored.getBpm(), 120.0, 0.000001, "beat origin survives recreation after short contact");
        tempo.tap(previous + 500L);
        near(tempo.getBpm(), 120.0, 0.000001, "next correct tap retains tempo");
        tempo.tap(previous + 750L);
        tempo.tap(previous + 1000L);
        tempo.tap(previous + 1500L);
        near(tempo.getBpm(), 120.0, 0.000001, "one inserted half-beat does not double tempo");
    }

    private static void missedBeats() {
        TempoEstimator tempo = established(500L);
        long time = tempo.getLastTapMillis();
        tempo.tap(time += 1000L);
        near(tempo.getBpm(), 120.0, 0.000001, "one missing beat");
        tempo.tap(time += 500L);
        tempo.tap(time += 1500L);
        near(tempo.getBpm(), 120.0, 0.000001, "two missing beats");
        tempo.tap(time + 500L);
        near(tempo.getBpm(), 120.0, 0.000001, "recovery after missing beats");
    }

    private static void sustainedTempoChanges() {
        TempoEstimator tempo = established(500L);
        long time = tempo.getLastTapMillis();
        for (int i = 0; i < 3; i++) tempo.tap(time += 250L);
        near(tempo.getBpm(), 240.0, 0.000001, "intentional double tempo");
        for (int i = 0; i < 3; i++) tempo.tap(time += 500L);
        near(tempo.getBpm(), 120.0, 0.000001, "intentional half tempo");
        for (int i = 0; i < 3; i++) tempo.tap(time += 1000L);
        near(tempo.getBpm(), 60.0, 0.000001, "slower half tempo");
        for (int i = 0; i < 3; i++) tempo.tap(time += 400L);
        near(tempo.getBpm(), 150.0, 0.000001, "new unrelated tempo");
        for (int i = 0; i < 28; i++) tempo.tap(time += 375L);
        near(tempo.getBpm(), 160.0, 0.000001, "gradual nearby tempo follows bounded window");
    }

    private static void pauseAndReset() {
        TempoEstimator tempo = established(500L);
        long time = tempo.getLastTapMillis();
        check(!tempo.isHolding(time + 500L), "normal rhythm is active");
        check(tempo.isHolding(time + 2500L), "pause marks held value");
        near(tempo.getBpm(), 120.0, 0.000001, "pause preserves final value");
        tempo.tap(time + 5000L);
        near(tempo.getBpm(), 0.0, 0.0, "tap after long pause starts fresh");
        equal(tempo.getTapCount(), 1, "new session starts at one tap");
        tempo.tap(time + 5600L);
        near(tempo.getBpm(), 100.0, 0.000001, "fresh session estimates new tempo");
        tempo.reset();
        equal(tempo.getTapCount(), 0, "manual reset clears count");
        near(tempo.getBpm(), 0.0, 0.0, "manual reset clears estimate");
        check(!tempo.isHolding(time + 10000L), "empty estimator is not holding");
    }

    private static void boundariesAndLongSessions() {
        near(established(2000L).getBpm(), 30.0, 0.000001, "30 BPM supported");
        near(established(200L).getBpm(), 300.0, 0.000001, "300 BPM supported");
        near(established(190L).getBpm(), 300.0, 0.000001, "upper range clamps jitter");
        near(established(2100L).getBpm(), 30.0, 0.000001, "lower range clamps jitter");
        TempoEstimator tempo = new TempoEstimator();
        for (int i = 0; i < 10000; i++) tempo.tap(1000L + 480L * i);
        near(tempo.getBpm(), 125.0, 0.000001, "long sessions retain accuracy");
        equal(tempo.getTapCount(), 10000, "long session count");
        check(tempo.getConfidence() >= 0.0 && tempo.getConfidence() <= 1.0,
                "confidence remains bounded");
        equal(tempo.saveState().length, 33, "history has fixed storage size");
    }

    private static void stateRoundTrip() {
        TempoEstimator original = established(500L);
        long time = original.getLastTapMillis();
        original.tap(time += 250L); // Save while deciding if the tempo has changed.
        TempoEstimator restored = new TempoEstimator();
        check(restored.restoreState(original.saveState()), "state accepted");
        near(restored.getBpm(), original.getBpm(), 0.0, "state preserves BPM");
        equal(restored.getTapCount(), original.getTapCount(), "state preserves count");
        equal(restored.getLastTapMillis(), original.getLastTapMillis(), "state preserves timestamp");
        for (int i = 0; i < 2; i++) {
            original.tap(time += 250L);
            restored.tap(time);
        }
        near(restored.getBpm(), 240.0, 0.000001, "pending change survives recreation");
        near(restored.getBpm(), original.getBpm(), 0.0, "continued state matches");
        check(!restored.restoreState(null), "null state rejected");
        check(!restored.restoreState(new long[3]), "truncated state rejected");
        long[] corrupt = restored.saveState();
        corrupt[5] = Double.doubleToLongBits(Double.NaN);
        check(!restored.restoreState(corrupt), "corrupt interval rejected");
        near(restored.getBpm(), 240.0, 0.000001, "bad state leaves estimate intact");
        check(new TempoEstimator().restoreState(new TempoEstimator().saveState()), "empty state restores");
        long[] legacy = java.util.Arrays.copyOf(established(500L).saveState(), 32);
        legacy[0] = 1L;
        TempoEstimator upgraded = new TempoEstimator();
        check(upgraded.restoreState(legacy), "version 1.1.0 state is compatible");
        near(upgraded.getBpm(), 120.0, 0.000001, "legacy state preserves tempo");
        long[] badBeat = upgraded.saveState();
        badBeat[32] = badBeat[1] + 1L;
        check(!upgraded.restoreState(badBeat), "future beat anchor rejected");
    }

    private static TempoEstimator established(long interval) {
        TempoEstimator tempo = new TempoEstimator();
        for (int i = 0; i < 13; i++) tempo.tap(1000L + i * interval);
        return tempo;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(long actual, long expected, String message) {
        check(actual == expected, message + ": expected " + expected + ", got " + actual);
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        check(!Double.isNaN(actual) && Math.abs(actual - expected) <= tolerance,
                message + ": expected " + expected + " ± " + tolerance + ", got " + actual);
    }
}
