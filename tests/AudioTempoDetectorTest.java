package de.mk2.bpm;

import java.util.Random;

/** Reproducible audio behavior tests, without Android or third-party libraries. */
public final class AudioTempoDetectorTest {
    private static int checks;

    public static void main(String[] args) {
        rejectSilenceNoiseAndPitch();
        musicalRhythms();
        clapsSyncopationAndRoomEcho();
        irregularTransients();
        startupSilenceAndReset();
        changingSong();
        beatPhase();
        liveLedLatency();
        liveLedPhaseChange();
        System.out.println("AudioTempoDetector: " + checks + " checks passed.");
    }

    private static void rejectSilenceNoiseAndPitch() {
        for (int kind = 0; kind < 3; kind++) {
            AudioTempoDetector detector = new AudioTempoDetector(44100);
            short[] pcm = new short[2048];
            Random random = new Random(17);
            int samples = 44100 * 14;
            for (int start = 0; start < samples; start += pcm.length) {
                int count = Math.min(pcm.length, samples - start);
                for (int i = 0; i < count; i++) {
                    double value = kind == 0 ? 0.0 : kind == 1 ? random.nextGaussian() * 0.075
                            : Math.sin(2.0 * Math.PI * 440.0 * (start + i) / 44100.0) * 0.3;
                    pcm[i] = quantize(value);
                }
                detector.process(pcm, count);
            }
            AudioTempoDetector.Result result = detector.getResult();
            check(!result.rhythmic && result.bpm == 0.0, "reject silence/noise/pitch kind=" + kind + " got " + result.bpm);
            check(Double.isNaN(result.beatTimeSeconds), "nonrhythmic input has no beat clock");
            near(result.seconds, 14.0, 0.00001, "audio clock follows PCM");
        }
    }

    private static void musicalRhythms() {
        double[] tempos = {60.0, 90.0, 123.4, 145.0, 180.0, 210.0};
        int[] rates = {44100, 48000, 16000};
        for (int rate : rates) {
            for (double tempo : tempos) {
                AudioTempoDetector detector = new AudioTempoDetector(rate);
                feedMusic(detector, rate, tempo, 18.0, 0.6, true, 0.012, 1027);
                AudioTempoDetector.Result result = detector.getResult();
                System.out.printf("music %d Hz %.1f BPM -> %.2f confidence %.2f%n", rate, tempo, result.bpm, result.confidence);
                near(result.bpm, tempo, 1.5, "percussion/chords/noise at " + rate + "Hz");
                check(result.rhythmic && result.confidence > 0.2, "clear rhythmic evidence");
                check(result.level >= 0.0 && result.level <= 1.0, "bounded meter");
                phaseNear(result, 0.0, 0.075, "phase follows audible kick at " + rate + "Hz / " + tempo);
            }
        }
        AudioTempoDetector quiet = new AudioTempoDetector(48000);
        feedMusic(quiet, 48000, 128.0, 16.0, 0.035, true, 0.0005, 511);
        near(quiet.getResult().bpm, 128.0, 1.5, "distant quiet music");
    }

    private static void startupSilenceAndReset() {
        AudioTempoDetector detector = new AudioTempoDetector(48000);
        feedMusic(detector, 48000, 127.0, 7.0, 0.6, false, 0.0, 2048);
        near(detector.getResult().bpm, 127.0, 2.0, "useful first estimate within seven seconds");
        short[] silence = new short[4800];
        for (int i = 0; i < 30; i++) detector.process(silence, silence.length);
        check(detector.getResult().bpm == 0.0, "quiet input clears stale song BPM");
        check(Double.isNaN(detector.getResult().beatTimeSeconds), "silence stops beat phase");
        feedMusic(detector, 48000, 103.0, 8.0, 0.25, true, 0.001, 173);
        near(detector.getResult().bpm, 103.0, 2.0, "new song after silence");
        detector.reset();
        check(detector.getResult().seconds == 0.0 && detector.getResult().bpm == 0.0, "reset clears session");
        check(Double.isNaN(detector.getResult().beatTimeSeconds), "reset clears beat phase");
    }

    private static void changingSong() {
        AudioTempoDetector detector = new AudioTempoDetector(44100);
        feedMusic(detector, 44100, 110.0, 24.0, 0.5, true, 0.005, 2048);
        near(detector.getResult().bpm, 110.0, 1.5, "first song");
        feedMusic(detector, 44100, 150.0, 24.0, 0.5, true, 0.005, 2048);
        near(detector.getResult().bpm, 150.0, 1.5, "bounded history follows changed song");
    }

    private static void clapsSyncopationAndRoomEcho() {
        for (int kind = 0; kind < 3; kind++) {
            AudioTempoDetector detector = new AudioTempoDetector(48000);
            feedTransientArrangement(detector, 48000, 120.0, 20.0, kind, 0);
            AudioTempoDetector.Result result = detector.getResult();
            System.out.printf("clap/syncopation/echo kind %d -> %.2f confidence %.2f%n", kind, result.bpm, result.confidence);
            near(result.bpm, 120.0, 2.0, "transient arrangement " + kind);
        }
    }

    private static void irregularTransients() {
        for (int seed = 0; seed < 4; seed++) {
            AudioTempoDetector detector = new AudioTempoDetector(44100);
            feedTransientArrangement(detector, 44100, 120.0, 24.0, 3, seed);
            AudioTempoDetector.Result result = detector.getResult();
            check(!result.rhythmic, "random nonmusical impacts rejected, seed " + seed + " got " + result.bpm);
        }
        // A held musical chord has spectral beating but no repeating attacks.
        AudioTempoDetector detector = new AudioTempoDetector(44100);
        short[] pcm = new short[2048];
        for (int start = 0; start < 44100 * 16; start += pcm.length) {
            for (int i = 0; i < pcm.length; i++) {
                double t = (start + i) / 44100.0;
                pcm[i] = quantize(0.15 * (Math.sin(t * 2 * Math.PI * 261.63)
                        + Math.sin(t * 2 * Math.PI * 329.63) + Math.sin(t * 2 * Math.PI * 392.0)));
            }
            detector.process(pcm, pcm.length);
        }
        check(!detector.getResult().rhythmic, "held chord is not a musical beat: " + detector.getResult().bpm);
    }

    private static void feedTransientArrangement(AudioTempoDetector detector, int rate, double bpm,
                                                   double seconds, int kind, int seed) {
        int samples = (int) (rate * seconds);
        double[] signal = new double[samples];
        double period = 60.0 / bpm;
        Random random = new Random(93911 + seed * 7919);
        if (kind == 3) {
            double time = 0.11;
            while (time < seconds) {
                addTransient(signal, rate, time, 0.1 + 0.8 * random.nextDouble(), false, random);
                time += 0.08 + random.nextDouble() * 1.6;
            }
        } else {
            for (int beat = 0; beat * period < seconds; beat++) {
                double time = beat * period;
                if (kind == 0) addTransient(signal, rate, time, 0.55, false, random);
                else {
                    // Funk-like bass pattern: one beat omitted, one offbeat kick;
                    // snare on two/four, hats supply the shared subdivision.
                    if (kind == 2 || beat % 4 == 0 || beat % 4 == 2) {
                        addTransient(signal, rate, time, 0.7, true, random);
                    }
                    if (beat % 4 == 1) addTransient(signal, rate, time + period * .5, 0.45, true, random);
                    if (beat % 2 == 1) addTransient(signal, rate, time, .45, false, random);
                    addTransient(signal, rate, time, .10, false, random);
                    addTransient(signal, rate, time + period * .5, .07, false, random);
                }
            }
        }
        short[] pcm = new short[1597];
        int echoDelay = (int) (rate * .137);
        for (int start = 0; start < samples; start += pcm.length) {
            int count = Math.min(pcm.length, samples - start);
            for (int i = 0; i < count; i++) {
                int index = start + i;
                double value = signal[index];
                if (kind == 2) {
                    if (index >= echoDelay) value += signal[index - echoDelay] * .38;
                    if (index >= echoDelay * 2) value += signal[index - echoDelay * 2] * .13;
                }
                pcm[i] = quantize(value + random.nextGaussian() * .006);
            }
            detector.process(pcm, count);
        }
    }

    private static void addTransient(double[] signal, int rate, double time,
                                     double gain, boolean kick, Random random) {
        int offset = (int) (time * rate);
        int length = (int) (rate * (kick ? .18 : .045));
        for (int i = 0; i < length && offset + i < signal.length; i++) {
            double t = i / (double) rate;
            double value = kick ? Math.sin(t * 2 * Math.PI * 70.0) * Math.exp(-t * 27.0)
                    : random.nextGaussian() * Math.exp(-t * 180.0);
            signal[offset + i] += value * gain;
        }
    }

    /** Kick on beats, alternating snare, eighth-note hats and a sustained chord. */
    private static void feedMusic(AudioTempoDetector detector, int rate, double bpm,
                                  double seconds, double gain, boolean arrangement,
                                  double noise, int chunk) {
        short[] pcm = new short[chunk];
        Random random = new Random(31285);
        int samples = (int) Math.round(rate * seconds);
        double period = 60.0 / bpm;
        for (int start = 0; start < samples; start += chunk) {
            int count = Math.min(chunk, samples - start);
            for (int i = 0; i < count; i++) {
                double t = (start + i) / (double) rate;
                int beat = (int) (t / period);
                double within = t - beat * period;
                double kick = Math.sin(2.0 * Math.PI * (51.0 * within + 4.0 * (1.0 - Math.exp(-within * 38.0))))
                        * Math.exp(-within * 22.0);
                // Vary beat intensity and slow microphone gain, never its timing.
                kick *= beat % 4 == 0 ? 0.85 : 0.64;
                double value = kick;
                if (arrangement) {
                    double hatTime = t % (period / 2.0);
                    value += random.nextGaussian() * 0.13 * Math.exp(-hatTime * 120.0);
                    if (beat % 2 == 1) value += random.nextGaussian() * 0.28 * Math.exp(-within * 38.0);
                    value += 0.045 * (Math.sin(t * 2.0 * Math.PI * 261.63)
                            + Math.sin(t * 2.0 * Math.PI * 329.63) + Math.sin(t * 2.0 * Math.PI * 392.0));
                }
                value *= gain * (0.80 + 0.20 * Math.sin(t * 0.23));
                value += noise * random.nextGaussian();
                pcm[i] = quantize(value);
            }
            detector.process(pcm, count);
        }
    }

    private static short quantize(double value) {
        return (short) Math.round(Math.max(-1.0, Math.min(0.9999, value)) * 32767.0);
    }

    private static void beatPhase() {
        for (int rate : new int[]{44100,48000}) {
            for (double tempo : new double[]{90,123.4,180}) {
                AudioTempoDetector detector = new AudioTempoDetector(rate);
                double offset = .173;
                double period = 60.0 / tempo;
                short[] pcm = new short[1031];
                int samples = rate*14;
                Random random = new Random(97813);
                for (int start=0; start<samples; start+=pcm.length) {
                    int count = Math.min(pcm.length, samples-start);
                    for (int i=0; i<count; i++) {
                        double t = (start+i)/(double)rate - offset;
                        double v = .003*random.nextGaussian();
                        if (t >= 0) {
                            double within = t % period;
                            v += .7*Math.sin(within*2*Math.PI*70)*Math.exp(-within*27);
                            v += .08*random.nextGaussian()*Math.exp(-(t%(period/2))*120);
                        }
                        pcm[i] = quantize(v);
                    }
                    detector.process(pcm, count);
                }
                near(detector.getResult().bpm, tempo, 1.5, "shifted beat tempo");
                phaseNear(detector.getResult(), offset, .075, "shifted beat phase " + rate + " / " + tempo);
            }
        }
    }

    private static void phaseNear(AudioTempoDetector.Result result, double expectedPhase, double tolerance, String message) {
        double period = 60.0 / result.bpm;
        double error = result.beatTimeSeconds - expectedPhase;
        error -= Math.rint(error / period) * period;
        check(Double.isFinite(error) && Math.abs(error) <= tolerance,
            message + " phase error " + error + " seconds (anchor " + result.beatTimeSeconds + ")");
        check(result.beatTimeSeconds <= result.seconds, "beat anchor belongs to processed PCM");
    }

    private static void liveLedLatency() {
        for (int rate : new int[]{44100,48000}) {
            AudioTempoDetector detector = new AudioTempoDetector(rate);
            short[] pcm = new short[2048];
            double period = 60.0/127;
            double offset = .70;
            int events = 0;
            double firstObserved = Double.NaN;
            for (int start=0; start<rate*3; start+=pcm.length) {
                int count = Math.min(pcm.length, rate*3-start);
                for (int i=0; i<count; i++) {
                    double t = (start+i)/(double)rate-offset;
                    double within = t >= 0 ? t%period : 0;
                    pcm[i] = quantize(t >= 0 ? .7*Math.sin(within*2*Math.PI*70)*Math.exp(-within*27) : 0);
                }
                detector.process(pcm, count);
                double beat = detector.pollLedBeatSeconds();
                if (!Double.isNaN(beat)) {
                    double observed = (start+count)/(double)rate;
                    if (events++ == 0) firstObserved = observed;
                    double actualBeat = offset + Math.rint((beat-offset)/period)*period;
                    check(observed-actualBeat >= 0 && observed-actualBeat <= .16,
                        "LED onset delivered within 160 ms: " + (observed-actualBeat) + " at " + rate);
                    check(Math.abs(beat-actualBeat) <= .09, "live LED phase follows current attack");
                    check(Double.isNaN(detector.pollLedBeatSeconds()), "live beat is consumed once");
                }
            }
            check(events >= 4, "live LED tracks startup attacks: " + events);
            check(firstObserved <= .90, "first LED beat does not wait five/six seconds: " + firstObserved);
            check(detector.getResult().bpm == 0, "BPM still waits for original evidence window");
            detector.reset();
            check(Double.isNaN(detector.pollLedBeatSeconds()), "reset clears live LED queue");
        }
    }

    private static void liveLedPhaseChange() {
        for (int rate : new int[]{44100,48000}) {
            AudioTempoDetector detector = new AudioTempoDetector(rate);
            short[] pcm = new short[2048];
            double period = 60.0/127;
            int laterEvents = 0;
            double firstAfterChange = Double.NaN;
            for (int start=0; start<rate*11; start+=pcm.length) {
                int count = Math.min(pcm.length, rate*11-start);
                for (int i=0; i<count; i++) {
                    double seconds = (start+i)/(double)rate;
                    double offset = seconds < 8 ? .70 : 8.173;
                    double t = seconds-offset;
                    double within = t >= 0 ? t%period : 0;
                    pcm[i] = quantize(t >= 0 ? .7*Math.sin(within*2*Math.PI*70)*Math.exp(-within*27) : 0);
                }
                detector.process(pcm, count);
                double beat = detector.pollLedBeatSeconds();
                if (!Double.isNaN(beat) && beat >= 8.10) {
                    double observed = (start+count)/(double)rate;
                    if (laterEvents++ == 0) firstAfterChange = observed;
                    double actual = 8.173 + Math.rint((beat-8.173)/period)*period;
                    check(observed-actual >= 0 && observed-actual <= .16,
                        "ongoing LED attack stays fresh after phase change: " + (observed-actual));
                }
            }
            check(laterEvents >= 5, "LED follows ongoing phase change");
            check(firstAfterChange <= 8.333, "LED does not wait for historical phase to catch up: " + firstAfterChange);
            check(detector.getResult().bpm > 0, "ongoing LED check runs with acquired BPM");
        }
    }

    private static void near(double actual, double expected, double tolerance, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                message + ": expected " + expected + " +/- " + tolerance + ", got " + actual);
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
