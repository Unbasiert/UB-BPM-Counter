package de.mk2.bpm;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

/** A small, fully offline instrument. Touch timestamps use Android's monotonic clock. */
public final class MainActivity extends Activity {
    private final TempoEstimator tempo = new TempoEstimator();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private InstrumentView instrument;
    private SharedPreferences preferences;
    private boolean haptics = true;
    private int rateMode = 1;
    private boolean resumed;
    private MicrophoneAnalyzer microphone;
    private boolean audioMode;
    private boolean listening;
    private boolean permissionRequestPending;
    private boolean startAfterPermission;
    private double audioBpm;
    private double audioConfidence;
    private double audioSeconds;
    private double audioLevel;
    private double audioBeatUptime = Double.NaN;
    private static final int MICROPHONE_PERMISSION = 21;
    private static final double[] RATES = {0.5, 1.0, 2.0};

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            updateDisplay();
            handler.postDelayed(this, 150);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("instrument", MODE_PRIVATE);
        haptics = preferences.getBoolean("haptics", true);
        rateMode = Math.max(0, Math.min(2, preferences.getInt("rate", 1)));
        if (state != null) {
            long[] saved = state.getLongArray("tempo");
            if (saved != null) tempo.restoreState(saved);
            if (tempo.getLastTapMillis() > SystemClock.uptimeMillis()) tempo.reset();
            audioMode = state.getBoolean("audioMode", false);
            audioBpm = state.getDouble("audioBpm", 0);
            audioConfidence = state.getDouble("audioConfidence", 0);
            audioSeconds = state.getDouble("audioSeconds", 0);
            permissionRequestPending = state.getBoolean("permissionPending", false);
        }

        microphone = new MicrophoneAnalyzer(new MicrophoneAnalyzer.Listener() {
            @Override public void onBeat(double beatUptimeMillis) {
                if (!audioMode || !listening || !resumed) return;
                audioBeatUptime = beatUptimeMillis;
                instrument.flashLiveBeat(audioBpm * RATES[rateMode], audioBpm, beatUptimeMillis);
            }
            @Override public void onUpdate(AudioTempoDetector.Result result, double beatUptimeMillis) {
                if (!listening || !resumed) return;
                audioBpm = result.bpm;
                audioConfidence = result.confidence;
                audioSeconds = result.seconds;
                audioLevel = result.level;
                audioBeatUptime = beatUptimeMillis;
                updateDisplay();
            }
            @Override public void onError(String message) {
                if (!listening) return;
                stopMicrophone();
                if (resumed && !isFinishing()) new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Mikrofon nicht verfügbar").setMessage(message)
                    .setPositiveButton("OK", null).show();
            }
        });

        getWindow().setStatusBarColor(Color.rgb(23, 26, 28));
        getWindow().setNavigationBarColor(Color.rgb(23, 26, 28));
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        instrument = new InstrumentView(this, new InstrumentView.Actions() {
            @Override public void tap(long time) { registerTap(time); }
            @Override public void reset() {
                stopMicrophone();
                audioMode = false;
                tempo.reset();
                updateDisplay();
                instrument.announceForAccessibility("Messung zurückgesetzt. Im Takt tippen.");
            }
            @Override public void toggleHaptics() {
                haptics = !haptics;
                preferences.edit().putBoolean("haptics", haptics).apply();
                if (haptics && !listening) instrument.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                updateDisplay();
            }
            @Override public void changeRate() {
                rateMode = (rateMode + 1) % RATES.length;
                preferences.edit().putInt("rate", rateMode).apply();
                updateDisplay();
                instrument.announceForAccessibility("Tempo-Faktor " + rateLabel());
            }
            @Override public void toggleMicrophone() { toggleDetection(); }
        });
        instrument.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                int left = insets.getSystemWindowInsetLeft();
                int top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                    top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                    right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                    bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
                }
                view.setPadding(left, top, right, bottom);
            }
            return insets;
        });
        setContentView(instrument);
        instrument.requestApplyInsets();
        updateDisplay();
    }

    private String rateLabel() {
        return rateMode == 0 ? "× ½" : rateMode == 2 ? "× 2" : "× 1";
    }

    private void registerTap(long timestamp) {
        if (audioMode) {
            stopMicrophone();
            audioMode = false;
            tempo.reset();
        }
        if (tempo.tap(timestamp)) {
            if (haptics) instrument.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            instrument.flash();
            updateDisplay();
        }
    }

    private void toggleDetection() {
        if (listening) { stopMicrophone(); return; }
        if (permissionRequestPending) return;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            new AlertDialog.Builder(this).setTitle("Kein Mikrofon gefunden")
                .setMessage("Auf diesem Gerät ist keine Mikrofonaufnahme verfügbar.")
                .setPositiveButton("OK", null).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMicrophone();
        } else {
            permissionRequestPending = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
        }
    }

    private void startMicrophone() {
        // The button can be delivered immediately after the task is brought
        // back from Home, before Android has completed the resume callback.
        // The foreground lifecycle still stops capture in onPause; allowing
        // this start here avoids dropping that deliberate user action.
        if (listening) return;
        audioMode = true;
        listening = true;
        audioBpm = audioConfidence = audioSeconds = audioLevel = 0;
        audioBeatUptime = Double.NaN;
        updateDisplay();
        microphone.start();
    }

    private void stopMicrophone() {
        listening = false;
        startAfterPermission = false;
        if (microphone != null) microphone.stop();
        audioLevel = 0;
        updateDisplay();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != MICROPHONE_PERMISSION) return;
        permissionRequestPending = false;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            if (resumed) startMicrophone();
            else startAfterPermission = true;
        } else if (!isFinishing()) {
            AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Mikrofon erlauben")
                .setMessage("Für „BPM erkennen“ braucht MK2 BPM Zugriff auf das Mikrofon. "
                    + "Die Analyse läuft auf deinem Gerät; es wird keine Aufnahme gespeichert. "
                    + "Du kannst weiterhin im Takt tippen.")
                .setPositiveButton("OK", null);
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                dialog.setNeutralButton("Einstellungen", (d, w) -> startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
            }
            dialog.show();
        }
    }

    private void updateDisplay() {
        if (instrument == null) return;
        long now = SystemClock.uptimeMillis();
        boolean holding = tempo.isHolding(now);
        double bpm = tempo.getBpm() * RATES[rateMode];
        String status = tempo.getTapCount() == 0 ? "BEREIT"
            : bpm == 0 ? "WEITER TIPPEN" : holding ? "HOLD"
            : tempo.getTapCount() < 5 ? "ERFASSEN"
            : tempo.getConfidence() >= 0.72 ? "STABIL" : "EINPENDELN";
        double confidence = tempo.getConfidence();
        String hint = holding ? "WERT GEHALTEN · WEITER TIPPEN" : "DEIN TAKT. DEIN TEMPO.";
        if (audioMode) {
            bpm = audioBpm * RATES[rateMode];
            confidence = audioConfidence;
            holding = !listening && bpm > 0;
            status = !listening ? (bpm > 0 ? "HOLD" : "GESTOPPT")
                : audioSeconds >= 2 && audioLevel < 0.27 ? "ZU LEISE"
                : audioSeconds < 6 ? "HÖRT ZU"
                : bpm > 0 ? (confidence >= 0.72 ? "STABIL" : "EINPENDELN")
                : audioSeconds >= 12 ? "KEIN TAKT" : "ANALYSE";
            hint = !listening ? "MIKROFON AUS · TAP ODER START"
                : status.equals("ZU LEISE") ? "NÄHER AN DEN LAUTSPRECHER"
                : bpm > 0 ? "HALB / DOPPELT? TEMPO-TASTE"
                : "MIKROFON · 8–20 S ZUHÖREN";
        }
        instrument.update(bpm, tempo.getTapCount(), confidence, status,
            holding, haptics, rateLabel(), audioMode, listening, audioSeconds, audioLevel, hint);
        instrument.setAutoBeat(bpm, audioBpm, audioBeatUptime,
            audioMode && listening && resumed && (audioBpm <= 0 || audioLevel >= 0.27));
        if (resumed && (listening || (!audioMode && tempo.getTapCount() > 0 && !holding
                && now - tempo.getLastTapMillis() < 8000))) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLongArray("tempo", tempo.saveState());
        state.putBoolean("audioMode", audioMode);
        state.putDouble("audioBpm", audioBpm);
        state.putDouble("audioConfidence", audioConfidence);
        state.putDouble("audioSeconds", audioSeconds);
        state.putBoolean("permissionPending", permissionRequestPending);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (startAfterPermission) { startAfterPermission = false; startMicrophone(); }
        handler.post(refresh);
    }

    @Override protected void onPause() {
        resumed = false;
        // Capture is foreground-only. A held estimate survives rotation or returning later,
        // but the user explicitly starts a fresh capture session with the microphone button.
        if (listening) stopMicrophone();
        handler.removeCallbacks(refresh);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        if (microphone != null) microphone.stop();
        super.onDestroy();
    }
}
