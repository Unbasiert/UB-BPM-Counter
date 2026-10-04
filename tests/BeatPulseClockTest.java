package de.mk2.bpm;

public final class BeatPulseClockTest {
    private static int checks;
    public static void main(String[] args) {
        BeatPulseClock clock = new BeatPulseClock();
        clock.update(120, 120, 1000, true);
        equal(clock.nextPulseMillis(900), 1000, "first pulse aligned to source");
        clock.recordPulse(1000);
        check(!clock.acceptsLiveBeat(1000, 1060), "audio callback does not repeat an already predicted flash");
        equal(clock.nextPulseMillis(1001), 1500, "120 BPM is one pulse per 500 ms");
        clock.update(120, 120, 1505, true);
        equal(clock.nextPulseMillis(1200), 1505, "small phase correction follows audio");
        clock.recordPulse(1505);
        clock.update(120, 120, 1490, true);
        check(clock.nextPulseMillis(1510) >= 1990, "phase correction does not duplicate a beat");
        equal(clock.nextPulseMillis(10050), 10490, "late UI callback skips old pulses");
        clock.stop();
        equal(clock.nextPulseMillis(10000), -1, "stopped clock has no deadline");
        clock.update(60, 120, 1000, true);
        clock.recordPulse(1000);
        clock.update(60, 120, 1500, true);
        check(!clock.acceptsLiveBeat(1500, 1560), "live offbeat does not defeat half-tempo setting");
        check(clock.acceptsLiveBeat(2000, 2060), "live selected beat can flash without an extra cycle wait");
        equal(clock.nextPulseMillis(1600), 2000, "half tempo preserves even/odd beat choice");
        clock.update(240, 120, 2000, true);
        clock.recordPulse(2000);
        equal(clock.nextPulseMillis(2001), 2250, "double tempo follows displayed BPM");
        clock.update(120, 120, 1000, false);
        check(!clock.isActive(), "disabled clock stops");
        clock.update(120, 120, Double.NaN, true);
        check(!clock.isActive(), "no beat phase means no flashing");
        clock.update(0, 0, 1000, true);
        check(!clock.isActive(), "no tempo means no flashing");
        clock.update(Double.POSITIVE_INFINITY, 120, 1000, true);
        check(!clock.isActive(), "invalid tempo rejected");
        clock.update(123.456, 123.456, 1000, true);
        check(Math.abs(clock.getPeriodMillis() - 60000.0/123.5) < 1e-9, "pulse follows rounded display value");
        int pulses = 0;
        long deadline = clock.nextPulseMillis(900);
        for (long now=900; now<60900; now++) {
            if (now % 150 == 0) {
                clock.update(123.456, 123.456, 1000, true);
                deadline = clock.nextPulseMillis(now);
            }
            if (now >= deadline) {
                clock.recordPulse(now); pulses++;
                deadline = clock.nextPulseMillis(now);
            }
        }
        check(pulses == 124, "minute of updates does not accumulate drift or extra flashes: " + pulses);
        System.out.println("BeatPulseClock: " + checks + " checks passed.");
    }
    private static void equal(long actual, long expected, String message) {
        check(actual == expected, message + " expected " + expected + " got " + actual);
    }
    private static void check(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }
}
