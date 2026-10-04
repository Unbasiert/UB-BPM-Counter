package de.mk2.bpm;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Foreground-only microphone capture. Audio is processed in memory and never stored. */
public final class MicrophoneAnalyzer {
    public interface Listener {
        void onUpdate(AudioTempoDetector.Result result, double beatUptimeMillis);
        void onBeat(double beatUptimeMillis);
        void onError(String message);
    }

    // Also serializes old/new Activity instances during a configuration change.
    private static final Object CAPTURE_LOCK = new Object();
    private static final int[] SAMPLE_RATES = {44100, 48000};
    private static final int[] SOURCES = {
        MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC
    };
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile Session current;

    public MicrophoneAnalyzer(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        this.listener = listener;
    }

    /** Call after RECORD_AUDIO permission is granted; repeated starts are harmless. */
    public synchronized void start() {
        if (isRunning()) return;
        final Session session = new Session();
        current = session;
        session.worker = new Thread(() -> run(session), "MK2 microphone");
        session.worker.start();
    }

    public boolean isRunning() {
        Session session = current;
        return session != null && !session.cancelled && !session.finished;
    }

    /** Releases the microphone immediately and invalidates all callbacks for this run. */
    public void stop() {
        final Session session;
        synchronized (this) {
            session = current;
            current = null;
        }
        if (session == null) return;
        synchronized (session.lock) {
            session.cancelled = true;
            releaseLocked(session);
        }
        main.removeCallbacksAndMessages(session);
        session.worker.interrupt();
    }

    private boolean active(Session session) {
        return current == session && !session.cancelled && !session.finished;
    }

    private void run(Session session) {
        String failure = null;
        synchronized (CAPTURE_LOCK) {
            try {
                if (!active(session)) return;
                // Audio services can take a short moment to hand the input route
                // back after another app, Home, or a rotation released it. Retry
                // the complete source/rate negotiation before surfacing an error.
                for (int attempt = 0; active(session) && attempt < 4; attempt++) {
                    try {
                        capture(session);
                        failure = null;
                        break;
                    } catch (CaptureFailure unavailable) {
                        failure = unavailable.getMessage();
                        synchronized (session.lock) { releaseLocked(session); }
                        if (!active(session) || attempt == 3) break;
                        Thread.sleep(180L * (attempt + 1));
                    }
                }
            } catch (SecurityException denied) {
                failure = "Mikrofon-Zugriff fehlt. Bitte die Mikrofon-Berechtigung erlauben.";
            } catch (InterruptedException interrupted) {
                if (active(session)) failure = "Mikrofon-Aufnahme unterbrochen. Bitte erneut starten.";
                Thread.currentThread().interrupt();
            } catch (RuntimeException error) {
                android.util.Log.w("MK2Microphone", "Audio capture failed", error);
                failure = "Mikrofon-Erkennung fehlgeschlagen. Bitte erneut starten.";
            } finally {
                synchronized (session.lock) {
                    releaseLocked(session);
                    session.finished = true;
                }
            }
        }
        if (failure != null) postError(session, failure);
    }

    private void capture(Session session) throws CaptureFailure, InterruptedException {
        int sampleRate = openRecorder(session);
        if (sampleRate == 0 || !active(session)) return;
        AudioTempoDetector detector = new AudioTempoDetector(sampleRate);
        AudioTimestamp timestamp = new AudioTimestamp();
        short[] samples = new short[2048];
        long lastData = SystemClock.elapsedRealtime();
        long lastUpdate = 0;
        long lastSilenceCheck = 0;
        while (active(session)) {
            final int count;
            final long readUptime;
            long now = SystemClock.elapsedRealtime();
            synchronized (session.lock) {
                if (!active(session) || session.recorder == null) return;
                // The lock is never held over a blocking read or over DSP work, so
                // Activity.onPause can stop and release the microphone promptly.
                count = session.recorder.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
                readUptime = SystemClock.uptimeMillis();
                if (Build.VERSION.SDK_INT >= 29 && now - lastSilenceCheck >= 1000) {
                    lastSilenceCheck = now;
                    AudioRecordingConfiguration configuration =
                        session.recorder.getActiveRecordingConfiguration();
                    if (configuration != null && configuration.isClientSilenced()) {
                        throw new CaptureFailure("Mikrofon ist stummgeschaltet oder wird von einer anderen App verwendet.");
                    }
                }
            }
            if (!active(session)) return;
            if (count < 0) {
                if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                    throw new CaptureFailure("Mikrofon-Verbindung verloren. Bitte erneut starten.");
                }
                throw new CaptureFailure("Mikrofon liefert keine Audiodaten. Bitte erneut starten.");
            }
            if (count == 0) {
                if (now - lastData >= 3000) {
                    throw new CaptureFailure("Keine Mikrofon-Daten. Bitte Mikrofon-Zugriff prüfen und erneut starten.");
                }
                Thread.sleep(10);
                continue;
            }
            lastData = now;
            detector.process(samples, count);
            double liveBeat = detector.pollLedBeatSeconds();
            boolean updateDue = now - lastUpdate >= 250;
            if (!Double.isNaN(liveBeat) || updateDue) {
                AudioTempoDetector.Result result = detector.getResult();
                double endUptime = readUptime;
                if (Build.VERSION.SDK_INT >= 24) {
                    synchronized (session.lock) {
                        if (!active(session) || session.recorder == null) return;
                        if (session.recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                                && timestamp.nanoTime > 0 && timestamp.framePosition > 0) {
                            double endNano = timestamp.nanoTime + (result.seconds * sampleRate - timestamp.framePosition)
                                    * 1_000_000_000.0 / sampleRate;
                            double offsetMillis = (endNano - System.nanoTime()) / 1_000_000.0;
                            if (offsetMillis > -1000.0 && offsetMillis < 50.0)
                                endUptime = SystemClock.uptimeMillis() + offsetMillis;
                        }
                    }
                }
                if (!Double.isNaN(liveBeat)) {
                    double beatUptime = endUptime - (result.seconds - liveBeat) * 1000.0;
                    main.postAtTime(() -> {
                        if (active(session) && SystemClock.uptimeMillis() - beatUptime <= 200.0)
                            listener.onBeat(beatUptime);
                    }, session, SystemClock.uptimeMillis());
                }
                if (updateDue) {
                    lastUpdate = now;
                    double beatUptime = endUptime - (result.seconds - detector.getLatestLedBeatSeconds()) * 1000.0;
                    postUpdate(session, result, beatUptime);
                }
            }
        }
    }

    /** Tries minimally processed speech-recognition input, then the regular microphone. */
    private int openRecorder(Session session) throws CaptureFailure {
        for (int source : SOURCES) {
            for (int requestedRate : SAMPLE_RATES) {
                if (!active(session)) return 0;
                int minimum = AudioRecord.getMinBufferSize(requestedRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) continue;
                int bufferBytes = Math.max(minimum * 2, requestedRate * 2 / 5);
                final AudioRecord candidate;
                try {
                    candidate = new AudioRecord(source, requestedRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
                } catch (SecurityException denied) {
                    throw denied;
                } catch (RuntimeException unsupported) {
                    continue;
                }
                synchronized (session.lock) {
                    // Ownership transfers exactly once; every path releases through
                    // releaseLocked, including cancellation while construction ran.
                    session.recorder = candidate;
                    if (!active(session)) {
                        releaseLocked(session);
                        return 0;
                    }
                    try {
                        if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                            candidate.startRecording();
                            if (candidate.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                                return candidate.getSampleRate();
                            }
                        }
                    } catch (SecurityException denied) {
                        releaseLocked(session);
                        throw denied;
                    } catch (RuntimeException unsupported) {
                        // A source can initialize but fail to start on a particular
                        // device. Release it before trying the next configuration.
                    }
                    releaseLocked(session);
                }
            }
        }
        throw new CaptureFailure("Mikrofon nicht verfügbar. Bitte andere Aufnahme-Apps schließen und erneut versuchen.");
    }

    /** session.lock must be held; native reads and starts use the same lock. */
    private static void releaseLocked(Session session) {
        AudioRecord recorder = session.recorder;
        session.recorder = null;
        if (recorder == null) return;
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
        } catch (RuntimeException ignored) {
            // A dead audio service can make stop fail; release is still mandatory.
        } finally {
            try { recorder.release(); } catch (RuntimeException ignored) { }
        }
    }

    private void postUpdate(final Session session, AudioTempoDetector.Result result, double beatUptime) {
        synchronized (session.lock) {
            if (!active(session)) return;
            session.latest = result;
            session.beatUptime = beatUptime;
            if (session.updateQueued) return;
            session.updateQueued = true;
            main.postAtTime(() -> {
                final AudioTempoDetector.Result latest;
                final double latestBeat;
                synchronized (session.lock) {
                    session.updateQueued = false;
                    latest = session.latest;
                    latestBeat = session.beatUptime;
                }
                if (active(session)) listener.onUpdate(latest, latestBeat);
            }, session, SystemClock.uptimeMillis());
        }
    }

    private void postError(final Session session, final String message) {
        main.removeCallbacksAndMessages(session);
        main.postAtTime(() -> {
            synchronized (MicrophoneAnalyzer.this) {
                if (current != session || session.cancelled) return;
                current = null;
            }
            listener.onError(message);
        }, session, SystemClock.uptimeMillis());
    }

    private static final class Session {
        final Object lock = new Object();
        volatile boolean cancelled;
        volatile boolean finished;
        Thread worker;
        AudioRecord recorder;
        AudioTempoDetector.Result latest;
        double beatUptime;
        boolean updateQueued;
    }

    private static final class CaptureFailure extends Exception {
        CaptureFailure(String message) { super(message); }
    }
}
