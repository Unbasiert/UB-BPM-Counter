package de.mk2.bpm;

import java.util.Arrays;

/**
 * Streaming, offline tempo estimate from mono signed 16-bit PCM. All methods belong
 * to the capture thread; publish the immutable Result to the UI. No audio is saved.
 *
 * Spectral changes in three bands form onset envelopes. Their normalized
 * autocorrelations vote for a common beat period, with a subharmonic penalty to
 * avoid simply choosing every second beat. This measures rhythm, not sound pitch.
 * As with any beat tracker, half/double tempo can remain musically ambiguous.
 */
public final class AudioTempoDetector {
    public static final class Result {
        public final double bpm;
        public final double confidence;
        public final double level;
        public final double seconds;
        public final boolean rhythmic;
        /** An aligned beat in PCM-session seconds, NaN until a rhythm is known. */
        public final double beatTimeSeconds;

        private Result(double bpm, double confidence, double level, double seconds, double beatTimeSeconds) {
            this.bpm = bpm;
            this.confidence = confidence;
            this.level = level;
            this.seconds = seconds;
            this.rhythmic = bpm > 0.0;
            this.beatTimeSeconds = bpm > 0.0 ? beatTimeSeconds : Double.NaN;
        }
    }

    private static final int FFT_SIZE = 1024;
    private static final int HOP = 128;
    private static final int BANDS = 3;
    private static final double MIN_BPM = 45.0;
    private static final double MAX_BPM = 220.0;
    private static final double QUIET_RMS = 0.0008;
    private final int sampleRate;
    private final int decimation;
    private final double frameRate;
    private final int historySize;
    private final double[] audio = new double[FFT_SIZE];
    private final double[] window = new double[FFT_SIZE];
    private final double[] real = new double[FFT_SIZE];
    private final double[] imaginary = new double[FFT_SIZE];
    private final double[] previousMagnitude = new double[FFT_SIZE / 2];
    private final int[] binBand = new int[FFT_SIZE / 2];
    private final double[][] history;
    private final double[][] envelopes;
    private final double[][] correlations;
    private final double[] combined;
    private final double[] movingMean = new double[BANDS];
    private int audioPosition;
    private int audioFill;
    private int hopPosition;
    private int decimationCount;
    private double decimationSum;
    private int historyPosition;
    private int historyCount;
    private int analysisFrames;
    private int quietFrames;
    private long sampleCount;
    private double level;
    private double bpm;
    private double confidence;
    private boolean quietCleared;
    private double beatTimeSeconds = Double.NaN;
    private double newestFrameSeconds;
    private final double[] phaseWeight = new double[BANDS];
    private final LiveBeatTracker ledBeats = new LiveBeatTracker();

    public AudioTempoDetector(int sampleRate) {
        if (sampleRate < 8000 || sampleRate > 192000) {
            throw new IllegalArgumentException("Unsupported PCM sample rate");
        }
        this.sampleRate = sampleRate;
        decimation = Math.max(1, sampleRate / 11025);
        double workingRate = sampleRate / (double) decimation;
        frameRate = workingRate / HOP;
        historySize = (int) Math.ceil(frameRate * 22.0);
        history = new double[BANDS][historySize];
        envelopes = new double[BANDS][historySize];
        int maxLag = (int) Math.ceil(frameRate * 60.0 / MIN_BPM) + 3;
        correlations = new double[BANDS][maxLag * 4 + 4];
        combined = new double[maxLag * 4 + 4];
        for (int i = 0; i < FFT_SIZE; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1));
        }
        for (int i = 0; i < binBand.length; i++) {
            double hz = i * workingRate / FFT_SIZE;
            // Keep the beat band focused on kick/bass fundamentals. A held
            // guitar or synth chord around 200–400 Hz otherwise creates window
            // leakage that looks like a false low-frequency pulse.
            binBand[i] = hz < 40.0 ? -1 : hz < 120.0 ? 0 : hz < 2300.0 ? 1 : 2;
        }
    }

    public void process(short[] pcm, int count) {
        if (pcm == null || count < 0 || count > pcm.length) {
            throw new IllegalArgumentException("Invalid PCM buffer length");
        }
        for (int i = 0; i < count; i++) {
            sampleCount++;
            decimationSum += pcm[i] / 32768.0;
            if (++decimationCount < decimation) continue;
            audio[audioPosition] = decimationSum / decimation;
            audioPosition = (audioPosition + 1) & (FFT_SIZE - 1);
            decimationSum = 0.0;
            decimationCount = 0;
            audioFill = Math.min(FFT_SIZE, audioFill + 1);
            if (++hopPosition >= HOP) {
                hopPosition = 0;
                if (audioFill == FFT_SIZE) analyzeFrame();
            }
        }
    }

    public Result getResult() {
        return new Result(bpm, confidence, clamp(level), sampleCount / (double) sampleRate, beatTimeSeconds);
    }

    public double pollLedBeatSeconds() { return ledBeats.pollBeatSeconds(); }
    public double getLatestLedBeatSeconds() { return ledBeats.getLatestBeatSeconds(); }

    public void reset() {
        Arrays.fill(audio, 0.0);
        Arrays.fill(previousMagnitude, 0.0);
        Arrays.fill(movingMean, 0.0);
        for (double[] band : history) Arrays.fill(band, 0.0);
        audioPosition = audioFill = hopPosition = decimationCount = 0;
        historyPosition = historyCount = analysisFrames = quietFrames = 0;
        sampleCount = 0L;
        decimationSum = level = bpm = confidence = 0.0;
        quietCleared = false;
        beatTimeSeconds = Double.NaN;
        newestFrameSeconds = 0.0;
        ledBeats.reset();
    }

    private void analyzeFrame() {
        double energy = 0.0;
        for (int i = 0; i < FFT_SIZE; i++) {
            double value = audio[(audioPosition + i) & (FFT_SIZE - 1)];
            energy += value * value;
            real[i] = value * window[i];
            imaginary[i] = 0.0;
        }
        double rms = Math.sqrt(energy / FFT_SIZE);
        // A decibel-scaled meter remains readable for music across the room.
        double meter = rms <= 0.0001 ? 0.0 : (20.0 * Math.log10(rms) + 80.0) / 65.0;
        level += (clamp(meter) - level) * 0.25;
        quietFrames = rms < QUIET_RMS ? quietFrames + 1 : 0;
        if (quietFrames > frameRate * 1.8) {
            bpm = confidence = 0.0;
            if (!quietCleared) {
                historyPosition = historyCount = analysisFrames = 0;
                Arrays.fill(movingMean, 0.0);
                quietCleared = true;
            }
        } else if (quietFrames == 0) {
            quietCleared = false;
        }

        fft(real, imaginary);
        double low = 0.0, mid = 0.0, high = 0.0;
        double lowTotal = 0.0, midTotal = 0.0, highTotal = 0.0;
        for (int i = 1; i < FFT_SIZE / 2; i++) {
            double magnitude = Math.sqrt(real[i] * real[i] + imaginary[i] * imaginary[i]) / FFT_SIZE;
            double compressed = Math.log1p(magnitude * 1000.0);
            double flux = Math.max(0.0, compressed - previousMagnitude[i]);
            previousMagnitude[i] = compressed;
            if (binBand[i] == 0) { low += flux; lowTotal += compressed; }
            else if (binBand[i] == 1) { mid += flux; midTotal += compressed; }
            else if (binBand[i] == 2) { high += flux; highTotal += compressed; }
        }
        if (quietCleared) {
            ledBeats.process(0, 0, 0, rms, sampleCount / (double) sampleRate, bpm);
            return;
        }
        // Tiny FFT leakage/phase fluctuations of a held tone are not attacks.
        double floor = Math.max(0.1, (lowTotal + midTotal + highTotal) * 0.01);
        putFlux(0, lowTotal > floor ? Math.max(0.0, low - lowTotal * 0.025) : 0.0);
        putFlux(1, midTotal > floor ? Math.max(0.0, mid - midTotal * 0.025) : 0.0);
        putFlux(2, highTotal > floor ? Math.max(0.0, high - highTotal * 0.025) : 0.0);
        ledBeats.process(history[0][historyPosition], history[1][historyPosition], history[2][historyPosition],
            rms, sampleCount / (double) sampleRate - FFT_SIZE * decimation / (2.0 * sampleRate), bpm);
        historyPosition = (historyPosition + 1) % historySize;
        historyCount = Math.min(historySize, historyCount + 1);
        // Each flux frame describes the center of its FFT window. Account for
        // that delay instead of treating the end of a processed buffer as a beat.
        newestFrameSeconds = sampleCount / (double) sampleRate
                - FFT_SIZE * decimation / (2.0 * sampleRate);
        if (++analysisFrames >= frameRate) {
            analysisFrames = 0;
            if (historyCount >= frameRate * 5.5) estimateTempo();
        }
    }

    private void putFlux(int band, double flux) {
        movingMean[band] += (flux - movingMean[band]) / (frameRate * 0.65);
        history[band][historyPosition] = Math.max(0.0, flux - movingMean[band] * 0.8);
    }

    private void estimateTempo() {
        int count = historyCount;
        int minLag = (int) Math.floor(frameRate * 60.0 / MAX_BPM);
        int maxLag = (int) Math.ceil(frameRate * 60.0 / MIN_BPM);
        int correlationLimit = Math.min(combined.length - 1, count / 2);
        Arrays.fill(combined, 0.0);
        Arrays.fill(phaseWeight, 0.0);
        double totalWeight = 0.0;
        double strongest = 0.0;
        for (int band = 0; band < BANDS; band++) {
            double[] signal = envelopes[band];
            double mean = 0.0;
            int start = (historyPosition - count + historySize) % historySize;
            for (int i = 0; i < count; i++) {
                signal[i] = history[band][(start + i) % historySize];
                mean += signal[i];
            }
            mean /= count;
            double variance = 0.0;
            for (int i = 0; i < count; i++) {
                signal[i] -= mean;
                variance += signal[i] * signal[i];
            }
            double[] ac = correlations[band];
            Arrays.fill(ac, 0.0);
            if (variance < 0.00001 || mean < 0.00001) continue;
            double peak = 0.0;
            for (int lag = Math.max(2, minLag / 2 - 1); lag <= correlationLimit; lag++) {
                double product = 0.0, a = 0.0, b = 0.0;
                for (int i = lag; i < count; i++) {
                    // Exponential weighting follows a newly playing track while
                    // keeping up to 22 seconds of evidence for weak rhythms.
                    double weight = 0.35 + 0.65 * i / count;
                    double x = signal[i], y = signal[i - lag];
                    product += weight * x * y;
                    a += weight * x * x;
                    b += weight * y * y;
                }
                ac[lag] = a * b > 1e-16 ? product / Math.sqrt(a * b) : 0.0;
                if (lag >= minLag && lag <= maxLag) peak = Math.max(peak, ac[lag]);
            }
            strongest = Math.max(strongest, peak);
            // Uncorrelated fans, speech and background hiss do not get an equal
            // vote with a band containing a repeatable kick/snare pattern.
            double weight = (band == 0 ? 0.55 : band == 1 ? 0.30 : 0.15)
                    * Math.max(0.0, peak - 0.12) * Math.max(0.0, peak - 0.12);
            phaseWeight[band] = weight / mean;
            for (int lag = 0; lag <= correlationLimit; lag++) combined[lag] += weight * ac[lag];
            totalWeight += weight;
        }
        if (totalWeight < 0.0001 || strongest < 0.24) {
            bpm = confidence = 0.0;
            return;
        }
        for (int lag = 0; lag <= correlationLimit; lag++) combined[lag] /= totalWeight;
        double bestScore = -Double.MAX_VALUE;
        int bestLag = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            if (combined[lag] < combined[lag - 1] || combined[lag] < combined[lag + 1]) continue;
            double score = combined[lag]
                    + 0.25 * samplePeak(combined, lag * 2.0, correlationLimit)
                    + 0.12 * samplePeak(combined, lag * 3.0, correlationLimit)
                    // Prefer the faster lattice when both a beat and its bar-length
                    // subharmonic are present. This avoids reporting 60 BPM for a
                    // syncopated 120 BPM groove whose downbeat repeats strongly.
                    - 0.68 * Math.max(0.0, samplePeak(combined, lag / 2.0, correlationLimit))
                    - 0.15 * Math.max(0.0, samplePeak(combined, lag / 3.0, correlationLimit));
            if (score > bestScore) {
                bestScore = score;
                bestLag = lag;
            }
        }
        // Downbeats can dominate a syncopated arrangement and make the raw
        // autocorrelation prefer exactly half the musical tempo. If the faster
        // lattice still has a substantial, repeatable peak, resolve that octave
        // ambiguity toward the beat subdivision; the UI retains a half/double
        // control for genuinely ambiguous recordings.
        if (bestLag >= minLag * 2) {
            int faster = bestLag / 2;
            for (int candidate = faster - 2; candidate <= faster + 2; candidate++) {
                if (candidate >= minLag && candidate <= maxLag
                        && combined[candidate] > combined[faster]) faster = candidate;
            }
            if (combined[faster] >= 0.35 && combined[faster] >= combined[bestLag] * 0.42) {
                bestLag = faster;
            }
        }
        if (bestLag == 0 || combined[bestLag] < 0.26 || bestScore < 0.32) {
            bpm = confidence = 0.0;
            return;
        }
        // Interpolation removes the integer-hop quantization (several BPM at
        // faster tempos); longer harmonic peaks refine that sub-frame period.
        double refinedLag = parabolicPeak(combined, bestLag);
        double weightedLag = refinedLag;
        double lagWeight = 1.0;
        for (int multiple = 2; multiple <= 4; multiple++) {
            int predicted = (int) Math.round(refinedLag * multiple);
            if (predicted + 3 >= correlationLimit) break;
            int local = predicted;
            for (int j = predicted - 2; j <= predicted + 2; j++) {
                if (combined[j] > combined[local]) local = j;
            }
            if (combined[local] > combined[bestLag] * 0.65) {
                double weight = 0.5 * multiple;
                weightedLag += weight * parabolicPeak(combined, local) / multiple;
                lagWeight += weight;
            }
        }
        double estimate = 60.0 * frameRate / (weightedLag / lagWeight);
        if (estimate < MIN_BPM - 0.5 || estimate > MAX_BPM + 0.5) {
            bpm = confidence = 0.0;
            return;
        }
        // Smooth only nearby estimates; a new track is allowed to change tempo.
        bpm = bpm > 0.0 && Math.abs(estimate - bpm) < 3.0 ? bpm * 0.30 + estimate * 0.70 : estimate;
        confidence = clamp((combined[bestLag] - 0.22) / 0.60)
                * Math.min(1.0, count / (frameRate * 12.0));
        estimateBeatPhase();
    }

    /** Fold recent onset peaks onto the measured tempo, favouring kick evidence. */
    private void estimateBeatPhase() {
        double period = frameRate * 60.0 / bpm;
        int beats = Math.max(3, (int) Math.min(12, (historyCount - 3) / period));
        double best = -1.0;
        int bestOffset = 0;
        for (int offset = 0; offset < Math.ceil(period); offset++) {
            double score = 0.0;
            for (int beat = 0; beat < beats; beat++) {
                double age = offset + beat * period;
                double weight = 1.0 - 0.5 * beat / beats;
                for (int band = 0; band < BANDS; band++) {
                    if (phaseWeight[band] == 0.0) continue;
                    score += weight * phaseWeight[band] * (fluxAtAge(band, age) * 0.5
                            + fluxAtAge(band, age - 1) * 0.25 + fluxAtAge(band, age + 1) * 0.25);
                }
            }
            if (score > best) { best = score; bestOffset = offset; }
        }
        beatTimeSeconds = newestFrameSeconds - bestOffset / frameRate;
    }

    private double fluxAtAge(int band, double age) {
        if (age < 0.0 || age >= historyCount - 1) return 0.0;
        int whole = (int) age;
        double fraction = age - whole;
        int recent = (historyPosition - 1 - whole + historySize) % historySize;
        int earlier = (recent - 1 + historySize) % historySize;
        return history[band][recent] * (1.0 - fraction) + history[band][earlier] * fraction;
    }

    private static double samplePeak(double[] values, double position, int limit) {
        int center = (int) Math.round(position);
        if (center < 2 || center + 1 > limit) return 0.0;
        return Math.max(values[center], Math.max(values[center - 1], values[center + 1]));
    }

    private static double parabolicPeak(double[] values, int index) {
        double denominator = values[index - 1] - 2.0 * values[index] + values[index + 1];
        if (Math.abs(denominator) < 1e-9) return index;
        double offset = 0.5 * (values[index - 1] - values[index + 1]) / denominator;
        return index + Math.max(-0.5, Math.min(0.5, offset));
    }

    private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }

    private static void fft(double[] re, double[] im) {
        int size = re.length;
        for (int i = 1, j = 0; i < size; i++) {
            int bit = size >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int length = 2; length <= size; length <<= 1) {
            double angle = -2.0 * Math.PI / length;
            double stepRe = Math.cos(angle), stepIm = Math.sin(angle);
            for (int offset = 0; offset < size; offset += length) {
                double wr = 1.0, wi = 0.0;
                for (int j = 0; j < length / 2; j++) {
                    int a = offset + j, b = a + length / 2;
                    double br = re[b] * wr - im[b] * wi;
                    double bi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - br; im[b] = im[a] - bi;
                    re[a] += br; im[a] += bi;
                    double next = wr * stepRe - wi * stepIm;
                    wi = wr * stepIm + wi * stepRe; wr = next;
                }
            }
        }
    }
}
