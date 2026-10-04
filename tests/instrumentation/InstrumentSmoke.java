package de.mk2.bpm.qa;

import android.app.Activity;
import android.app.Instrumentation;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.AudioRecord;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Separate test APK: real Activity, Android input dispatch, accessible output and lifecycle. */
public final class InstrumentSmoke extends Instrumentation {
    private Activity activity;
    private int assertions;
    private final StringBuilder report = new StringBuilder();
    private boolean brandingOnly;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        brandingOnly = args != null && "true".equals(args.getString("branding"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getTargetContext().getSharedPreferences("instrument", Context.MODE_PRIVATE).edit()
                .putBoolean("haptics", true).putInt("rate", 1).commit();
            Intent launch = getTargetContext().getPackageManager().getLaunchIntentForPackage("de.mk2.bpm");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(launch);
            waitForIdleSync();
            SystemClock.sleep(400);
            if (brandingOnly) {
                check("UB BPM Counter".contentEquals(activity.getApplicationInfo().loadLabel(activity.getPackageManager())),
                    "Launcher label is UB BPM Counter");
                android.graphics.drawable.Drawable icon = activity.getApplicationInfo().loadIcon(activity.getPackageManager());
                Bitmap preview = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                icon.setBounds(0, 0, 512, 512);
                icon.draw(new android.graphics.Canvas(preview));
                File folder = new File(activity.getExternalFilesDir(null), "qa");
                folder.mkdirs();
                try (FileOutputStream stream = new FileOutputStream(new File(folder, "ub-icon.png"))) {
                    preview.compress(Bitmap.CompressFormat.PNG, 100, stream);
                }
                // Check the composition at a small, circular launcher size too.
                Bitmap circle = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                android.graphics.Paint iconPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                    | android.graphics.Paint.FILTER_BITMAP_FLAG);
                android.graphics.BitmapShader iconShader = new android.graphics.BitmapShader(preview,
                    android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                android.graphics.Matrix iconScale = new android.graphics.Matrix();
                iconScale.setScale(96f/512, 96f/512);
                iconShader.setLocalMatrix(iconScale);
                iconPaint.setShader(iconShader);
                new android.graphics.Canvas(circle).drawCircle(48, 48, 48, iconPaint);
                try (FileOutputStream stream = new FileOutputStream(new File(folder, "ub-icon-circle-96.png"))) {
                    circle.compress(Bitmap.CompressFormat.PNG, 100, stream);
                }
                circle.recycle();
                preview.recycle();
                assertBounds();
                shot("ub-portrait.png");
                changeOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                assertBounds();
                shot("ub-landscape.png");
                result.putString("stream", report + "PASS: " + assertions + " branding preview assertions\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            click("BPM-Messung zurücksetzen");
            assertText("0 Taps");
            assertBounds();
            shot("portrait-ready.png");

            // Delivered through the real system input queue, using exact event timestamps.
            long start = SystemClock.uptimeMillis() + 80;
            for (int i = 0; i < 5; i++) tapAt(start + i * 500L);
            assertText("120,0 BPM");
            assertText("5 Taps");
            shot("portrait-120bpm.png");
            for (int i = 5; i < 8; i++) tapAt(start + i * 500L);
            assertText("120,0 BPM");
            assertText("8 Taps");
            tapAt(start + 3530L); // New contact counts without shifting the beat origin.
            assertText("9 Taps");
            tapAt(start + 4500L); // One missed beat retains the established tempo.
            assertText("120,0 BPM");
            assertText("10 Taps");
            check(!find("Tap-Fläche").isPressed(), "Touch release clears pressed state");

            click("Tempo-Faktor"); assertText("240,0 BPM");
            click("Tempo-Faktor"); assertText("60,0 BPM");
            click("Tempo-Faktor"); assertText("120,0 BPM");

            ActivityMonitor recreated = addMonitor("de.mk2.bpm.MainActivity", null, false);
            runOnMainSync(() -> activity.recreate());
            Activity restored = recreated.waitForActivityWithTimeout(4000);
            check(restored != null, "Activity recreated");
            activity = restored;
            removeMonitor(recreated);
            waitForIdleSync();
            assertText("120,0 BPM"); assertText("10 Taps");

            SystemClock.sleep(4300);
            assertText("HOLD"); assertText("120,0 BPM");
            tapAt(SystemClock.uptimeMillis() + 50);
            assertText("1 Taps"); assertText("Noch kein Tempo");

            click("BPM-Messung zurücksetzen");
            runOnMainSync(() -> find("Tap-Fläche").performClick());
            assertText("1 Taps"); // TalkBack's click action follows the same path.
            click("BPM-Messung zurücksetzen");
            setInTouchMode(false); // Hardware keyboard focus starts outside touch mode.
            runOnMainSync(() -> find("Tap-Fläche").requestFocus());
            sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE);
            assertText("1 Taps");
            SystemClock.sleep(500);
            sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE);
            assertText("2 Taps");
            check(!find("Tap-Fläche").isPressed(), "Keyboard release clears pressed state");
            touchChecks();
            automaticLedChecks();

            click("Vibration");
            check(find("Vibration aus") != null, "Haptics toggle updates accessible state");
            click("Vibration");
            click("BPM-Messung zurücksetzen");
            changeOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            assertBounds();
            edgeTouchChecks();
            shot("landscape-ready.png");
            changeOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            assertBounds();
            microphoneChecks();
            report.append("PASS: ").append(assertions).append(" Android integration assertions\n");
            result.putString("stream", report.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", report.toString() + "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void touchChecks() throws Exception {
        click("BPM-Messung zurücksetzen");
        int[] pad = screenBounds("Tap-Fläche");
        float x = (pad[0] + pad[2]) / 2f, y = (pad[1] + pad[3]) / 2f;
        long t = SystemClock.uptimeMillis() + 50;
        contact(t, t, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{x}, new float[]{y}, .04f, .05f);
        contact(t, t+12, MotionEvent.ACTION_UP, new int[]{0}, new float[]{x}, new float[]{y}, .04f, .05f);
        contact(t+65, t+65, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{x}, new float[]{y}, .9f, 1f);
        contact(t+65, t+77, MotionEvent.ACTION_UP, new int[]{0}, new float[]{x}, new float[]{y}, .9f, 1f);
        contact(t+125, t+125, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{x}, new float[]{y}, .5f, .1f);
        contact(t+125, t+137, MotionEvent.ACTION_UP, new int[]{0}, new float[]{x}, new float[]{y}, .5f, .1f);
        assertTapCount(3, "Small, broad and quick contacts all count");
        contact(t+500, t+500, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{x}, new float[]{y}, .8f, .8f);
        assertTapCount(4, "Tap counts on contact before release");
        assertText("120,0 BPM");
        for (int i=1; i<=3; i++) contact(t+500, t+500+i*40, MotionEvent.ACTION_MOVE,
            new int[]{0}, new float[]{x+i*10}, new float[]{y+i*10}, .8f-i*.1f, .7f);
        assertTapCount(4, "Rolling/sliding a broad finger does not add beats");
        contact(t+500, t+640, MotionEvent.ACTION_CANCEL, new int[]{0}, new float[]{x}, new float[]{y}, .6f, .7f);
        check(!find("Tap-Fläche").isPressed(), "Cancel clears pressed contact state");
        tapAt(t+1000);
        assertTapCount(5, "Next tap works after cancellation");

        click("BPM-Messung zurücksetzen");
        t = SystemClock.uptimeMillis() + 50;
        contact(t, t, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{x}, new float[]{y}, .8f, .8f);
        contact(t, t+65, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new int[]{0,7}, new float[]{x,x+60}, new float[]{y,y+60}, .8f, .8f);
        assertTapCount(2, "Second overlapping finger counts inside 140 ms");
        contact(t, t+90, MotionEvent.ACTION_POINTER_UP, new int[]{0,7}, new float[]{x,x+60}, new float[]{y,y+60}, .8f, .8f);
        check(find("Tap-Fläche").isPressed(), "Pad stays pressed while the other finger remains");
        contact(t, t+125, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new int[]{7,0}, new float[]{x+60,x}, new float[]{y+60,y}, .8f, .8f);
        assertTapCount(3, "Released pointer can tap again with reordered pointer indices");
        contact(t, t+150, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new int[]{7,0}, new float[]{x+60,x}, new float[]{y+60,y}, .8f, .8f);
        contact(t, t+175, MotionEvent.ACTION_UP, new int[]{7}, new float[]{x+60}, new float[]{y+60}, .8f, .8f);
        assertTapCount(3, "Multi-finger releases do not double count");
        check(!find("Tap-Fläche").isPressed(), "Final finger release clears pressed state");

        click("BPM-Messung zurücksetzen");
        int[] mic = screenBounds("BPM erkennen");
        t = SystemClock.uptimeMillis() + 50;
        contact(t, t, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{x}, new float[]{y}, .8f, .8f);
        contact(t, t+200, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new int[]{0,7}, new float[]{x,x}, new float[]{y,(mic[1]+mic[3])/2f}, .8f, .8f);
        assertTapCount(1, "Second finger over microphone control is not a tap");
        contact(t, t+220, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            new int[]{0,7}, new float[]{x,x}, new float[]{y,(mic[1]+mic[3])/2f}, .8f, .8f);
        contact(t, t+240, MotionEvent.ACTION_UP, new int[]{0}, new float[]{x}, new float[]{y}, .8f, .8f);
        edgeTouchChecks();
        click("BPM-Messung zurücksetzen");
        t = SystemClock.uptimeMillis() + 50;
        for (int i=0; i<20; i++) tapAt(t + i*200L);
        assertTapCount(20, "Fast 300 BPM tapping loses no contacts");
        assertText("300,0 BPM");
        click("BPM-Messung zurücksetzen");
    }

    private void edgeTouchChecks() throws Exception {
        click("BPM-Messung zurücksetzen");
        int[] pad = screenBounds("Tap-Fläche");
        float d = activity.getResources().getDisplayMetrics().density;
        float cx = (pad[0]+pad[2])/2f, cy = (pad[1]+pad[3])/2f;
        float[] xs = {pad[0]+1, pad[2]-1, pad[0]-8*d, pad[2]+8*d, cx, cx};
        float[] ys = {pad[1]+1, pad[3]-1, cy, cy, pad[1]-4*d, pad[3]+2*d};
        long t = SystemClock.uptimeMillis() + 50;
        for (int i=0; i<xs.length; i++) {
            long at = t+i*250L;
            contact(at, at, MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{xs[i]}, new float[]{ys[i]}, .9f, .5f);
            contact(at, at+12, MotionEvent.ACTION_UP, new int[]{0}, new float[]{xs[i]}, new float[]{ys[i]}, .9f, .5f);
            assertTapCount(i+1, "Corner or extended pad edge contact " + (i+1));
        }
        click("BPM-Messung zurücksetzen");
    }

    private int[] screenBounds(String prefix) {
        final int[] bounds = new int[4];
        runOnMainSync(() -> {
            View v = find(prefix); int[] xy = new int[2]; v.getLocationOnScreen(xy);
            bounds[0]=xy[0]; bounds[1]=xy[1]; bounds[2]=xy[0]+v.getWidth(); bounds[3]=xy[1]+v.getHeight();
        });
        return bounds;
    }

    private void assertTapCount(int expected, String message) throws Exception {
        Field field = activity.getClass().getDeclaredField("tempo"); field.setAccessible(true);
        Object tempo = field.get(activity);
        int count = (Integer)tempo.getClass().getMethod("getTapCount").invoke(tempo);
        check(count == expected, message + " [" + count + "/" + expected + "]");
    }

    private void contact(long downTime, long eventTime, int action, int[] ids,
            float[] xs, float[] ys, float size, float pressure) {
        long delay = eventTime - SystemClock.uptimeMillis();
        if (delay > 0) SystemClock.sleep(delay);
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i=0; i<ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i]; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x=xs[i]; coords[i].y=ys[i]; coords[i].size=size; coords[i].pressure=pressure;
            coords[i].touchMajor=size*100; coords[i].touchMinor=size*50;
        }
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, ids.length, properties, coords,
            0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { sendPointerSync(event); } finally { event.recycle(); }
        waitForIdleSync();
    }

    /** Deterministic detected-audio state exercises the real Handler and painted LED.
     * DSP phase accuracy is checked separately with known PCM beats. */
    private void automaticLedChecks() throws Exception {
        click("BPM-Messung zurücksetzen");
        check(!autoClockActive(), "Manual mode has no automatic LED timer");
        long startupCount = (Long)padField("autoPulseCount");
        long requestTime = SystemClock.uptimeMillis();
        runOnMainSync(() -> {
            try {
                setField("audioMode", true); setField("listening", true);
                setField("audioBpm", 0.0); setField("audioSeconds", 1.0); setField("audioLevel", .8);
                Field owner = activity.getClass().getDeclaredField("microphone"); owner.setAccessible(true);
                Object mic = owner.get(activity);
                Field field = mic.getClass().getDeclaredField("listener"); field.setAccessible(true);
                Object listener = field.get(mic);
                Method beat = listener.getClass().getMethod("onBeat", double.class); beat.setAccessible(true);
                beat.invoke(listener, SystemClock.uptimeMillis() - 60.0);
                Method update = activity.getClass().getDeclaredMethod("updateDisplay");
                update.setAccessible(true); update.invoke(activity);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        check((Long)padField("autoPulseCount") == startupCount + 1, "Live audio callback flashes LED before any BPM estimate");
        check(SystemClock.uptimeMillis() - requestTime < 100, "Live LED callback has no seconds-long UI wait");
        check((Long)padField("autoFlashUntil") > SystemClock.uptimeMillis(), "Startup display refresh retains live LED glow");
        assertText("Noch kein Tempo");
        check(!autoClockActive(), "Startup uses actual onsets without inventing a tempo");
        SystemClock.sleep(170);
        check((Long)padField("autoPulseCount") == startupCount + 1, "One live attack creates exactly one startup pulse");
        click("BPM-Messung zurücksetzen");
        runOnMainSync(() -> {
            try {
                setField("audioMode", true); setField("listening", true);
                setField("audioBpm", 120.0); setField("audioConfidence", .9);
                setField("audioSeconds", 12.0); setField("audioLevel", .8);
                setField("audioBeatUptime", SystemClock.uptimeMillis() + 180.0);
                Method update = activity.getClass().getDeclaredMethod("updateDisplay");
                update.setAccessible(true); update.invoke(activity);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        assertText("120,0 BPM");
        check(autoClockActive(), "Recognized microphone tempo enables LED clock");
        waitForAutoLed();
        check((Long)padField("autoFlashUntil") > SystemClock.uptimeMillis(), "LED turns on at scheduled beat");
        captureLitAutoLed();
        long before = (Long)padField("autoPulseCount");
        SystemClock.sleep(1600);
        long pulses = (Long)padField("autoPulseCount") - before;
        check(pulses >= 3 && pulses <= 4, "120 BPM produces three/four pulses in 1.6 seconds: " + pulses);
        click("Tempo-Faktor");
        check(Math.abs(autoPeriod() - 250.0) < .01, "TEMPO x2 doubles LED rate with display");
        click("Tempo-Faktor");
        check(Math.abs(autoPeriod() - 1000.0) < .01, "TEMPO half halves LED rate with display");
        click("Tempo-Faktor");
        click("Erkennung stoppen");
        check(!autoClockActive(), "Stopping recognition cancels LED timer");
        check((Long)padField("autoFlashUntil") == 0, "Stopping recognition clears automatic glow immediately");
        before = (Long)padField("autoPulseCount");
        SystemClock.sleep(650);
        check((Long)padField("autoPulseCount") == before, "Stopped recognition schedules no further pulses");
        runOnMainSync(() -> find("Tap-Fläche").performClick());
        check((Long)padField("flashUntil") > SystemClock.uptimeMillis(), "Manual tap still flashes the original LED");
        check(!autoClockActive(), "Manual tap keeps automatic LED disabled");
        click("BPM-Messung zurücksetzen");

        runOnMainSync(() -> {
            try {
                setField("audioMode", true); setField("listening", true);
                setField("audioBpm", 120.0); setField("audioLevel", 0.0);
                setField("audioBeatUptime", (double)SystemClock.uptimeMillis());
                Method update = activity.getClass().getDeclaredMethod("updateDisplay");
                update.setAccessible(true); update.invoke(activity);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        check(!autoClockActive(), "Quiet microphone suppresses automatic pulses even with held BPM");
        click("BPM-Messung zurücksetzen");
    }

    private void setField(String name, Object value) throws Exception {
        Field field = activity.getClass().getDeclaredField(name); field.setAccessible(true); field.set(activity, value);
    }
    private Object padField(String name) throws Exception {
        View pad = find("Tap-Fläche");
        Field field = pad.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(pad);
    }
    private boolean autoClockActive() throws Exception {
        Object clock = padField("autoClock");
        return (Boolean)clock.getClass().getMethod("isActive").invoke(clock);
    }
    private double autoPeriod() throws Exception {
        Object clock = padField("autoClock");
        return (Double)clock.getClass().getMethod("getPeriodMillis").invoke(clock);
    }
    private void waitForAutoLed() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 1500;
        while ((Long)padField("autoFlashUntil") <= SystemClock.uptimeMillis() && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(5);
    }

    private void captureLitAutoLed() throws Exception {
        int[] bounds = screenBounds("Tap-Fläche");
        float scale = (bounds[2] - bounds[0]) / 360f;
        int x = Math.round(bounds[0] + 313*scale), y = Math.round(bounds[1] + 32*scale);
        long deadline = SystemClock.uptimeMillis() + 1800;
        do {
            Bitmap bitmap = getUiAutomation().takeScreenshot();
            if (bitmap != null) {
                int pixel = bitmap.getPixel(x, y);
                if (((pixel >> 16) & 255) > 245 && ((pixel >> 8) & 255) > 140 && (pixel & 255) < 100) {
                    check(true, "Automatic LED is visibly painted bright amber");
                    saveBitmap(bitmap, "automatic-beat-led.png");
                    bitmap.recycle(); return;
                }
                bitmap.recycle();
            }
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        check(false, "Automatic LED is visibly painted bright amber");
    }

    private void microphoneChecks() throws Exception {
        check(getTargetContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_DENIED, "Microphone starts without permission");
        click("BPM erkennen");
        check(clickSystemNode("com.android.permissioncontroller:id/permission_deny_button", 5000),
            "Real Android microphone permission dialog denied");
        waitForIdleSync(); SystemClock.sleep(450);
        check(!captureRunning(), "Denied microphone permission creates no recording");
        check(!autoClockActive(), "Denied permission starts no LED clock");
        check(clickSystemNode("android:id/button1", 4000), "Permission explanation can be dismissed");
        runOnMainSync(() -> find("Tap-Fläche").performClick());
        assertText("1 Taps");
        shot("microphone-permission-denied-tap.png");
        click("BPM-Messung zurücksetzen");

        click("BPM erkennen");
        check(clickSystemNode("com.android.permissioncontroller:id/permission_allow_foreground_only_button", 5000),
            "Real Android microphone permission dialog granted while using app");
        waitForIdleSync(); SystemClock.sleep(800);
        check(getTargetContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED, "Microphone permission granted for recorder tests");
        check(captureRunning(), "Grant callback starts AudioRecord microphone session");
        check(find("Erkennung stoppen") != null, "Grant callback exposes stop control");
        AudioRecord recording = captureRecord();
        check(recording != null && recording.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING,
            "Microphone uses a recording AudioRecord");
        assertBounds();
        check((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0,
            "Listening keeps the screen awake");
        // The emulator is launched with -no-audio: no host microphone is accessed.
        SystemClock.sleep(6500);
        check(captureRunning(), "Silent PCM keeps capture alive");
        check(!autoClockActive(), "Actual silent microphone keeps automatic LED off");
        assertText("Noch kein Tempo");
        shot("microphone-silence-portrait.png");
        click("Erkennung stoppen");
        check(!captureRunning(), "Stop releases microphone session");
        check(recording.getState() == AudioRecord.STATE_UNINITIALIZED, "Stop releases the native AudioRecord");
        check((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0,
            "Stop removes screen-awake flag");

        for (int i=0; i<3; i++) {
            startMicrophone();
            click("Erkennung stoppen");
            check(!captureRunning(), "Repeated start/stop releases microphone " + (i+1));
        }
        // Same-looper rapid taps exercise cancellation before the audio worker can finish startup.
        runOnMainSync(() -> {
            find("BPM erkennen").performClick();
            find("Erkennung stoppen").performClick();
            find("BPM erkennen").performClick();
        });
        SystemClock.sleep(900);
        check(captureRunning(), "Rapid start-stop-start preserves newest session");
        recording = captureRecord();
        check(recording != null && recording.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING,
            "Rapid restart has a functioning AudioRecord");
        click("Erkennung stoppen");
        check(recording.getState() == AudioRecord.STATE_UNINITIALIZED, "Rapid restart recorder also releases");
        startMicrophone();
        recording = captureRecord();
        runOnMainSync(() -> find("Tap-Fläche").performClick());
        waitForIdleSync();
        check(!captureRunning(), "Tapping stops microphone immediately");
        check(recording.getState() == AudioRecord.STATE_UNINITIALIZED, "Tap releases native AudioRecord");
        assertText("1 Taps");
        assertText("Noch kein Tempo");

        startMicrophone();
        click("BPM-Messung zurücksetzen");
        check(!captureRunning(), "Reset stops microphone immediately");
        assertText("0 Taps");

        startMicrophone();
        recording = captureRecord();
        sendKeyDownUpSync(KeyEvent.KEYCODE_HOME);
        SystemClock.sleep(650);
        check(!captureRunning(), "Going to launcher releases microphone");
        check(!autoClockActive(), "Going to launcher cancels LED callbacks");
        check(recording.getState() == AudioRecord.STATE_UNINITIALIZED, "Background releases native AudioRecord");
        ActivityMonitor returned = addMonitor("de.mk2.bpm.MainActivity", null, false);
        shell("am start -n de.mk2.bpm/.MainActivity");
        Activity resumedActivity = returned.waitForActivityWithTimeout(1500);
        if (resumedActivity != null) activity = resumedActivity;
        removeMonitor(returned);
        waitForIdleSync(); SystemClock.sleep(400);
        check(!captureRunning(), "Returning to app does not restart microphone");

        startMicrophone();
        recording = captureRecord();
        changeOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        check(!captureRunning(), "Rotation releases microphone and does not silently restart");
        check(!autoClockActive(), "Rotation leaves no automatic LED timer");
        check(recording.getState() == AudioRecord.STATE_UNINITIALIZED, "Rotation releases previous native AudioRecord");
        assertBounds();
        startMicrophone();
        shot("microphone-landscape.png");
        click("Erkennung stoppen");
        changeOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        click("BPM-Messung zurücksetzen");
        assertBounds();
        shot("portrait-final.png");
    }

    private void startMicrophone() throws Exception {
        click("BPM erkennen");
        // After returning from Home, Android's audio service can need a few
        // seconds to hand the input route back to the app. Wait for the
        // recorder itself instead of making the lifecycle check timing based.
        long deadline = SystemClock.uptimeMillis() + 3500;
        while (!captureRunning() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100);
        }
        check(captureRunning(), "AudioRecord microphone session starts");
        check(find("Erkennung stoppen") != null, "Recording has accessible stop control");
        AudioRecord recorder = captureRecord();
        while ((recorder == null || recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
            && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100);
            recorder = captureRecord();
        }
        check(recorder != null && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING,
            "Session owns a recording AudioRecord");
    }

    private boolean captureRunning() throws Exception {
        Field field = activity.getClass().getDeclaredField("microphone");
        field.setAccessible(true);
        Object capture = field.get(activity);
        if (capture == null) return false;
        Method running = capture.getClass().getMethod("isRunning");
        return (Boolean)running.invoke(capture);
    }

    private AudioRecord captureRecord() throws Exception {
        Field owner = activity.getClass().getDeclaredField("microphone");
        owner.setAccessible(true);
        Object capture = owner.get(activity);
        if (capture == null) return null;
        Field current = capture.getClass().getDeclaredField("current");
        current.setAccessible(true);
        Object session = current.get(capture);
        if (session == null) return null;
        Field recorder = session.getClass().getDeclaredField("recorder");
        recorder.setAccessible(true);
        return (AudioRecord)recorder.get(session);
    }

    private boolean clickSystemNode(String resourceId, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        do {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(resourceId);
                for (AccessibilityNodeInfo node : nodes) {
                    if (node.isVisibleToUser() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                }
            }
            SystemClock.sleep(150);
        } while (SystemClock.uptimeMillis() < end);
        return false;
    }

    private void shell(String command) throws Exception {
        ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(command);
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] data = new byte[1024];
            while (input.read(data) != -1) { }
        }
    }

    private View find(String prefix) { return find(activity.getWindow().getDecorView(), prefix); }
    private View find(View view, String prefix) {
        CharSequence desc = view.getContentDescription();
        if (desc != null && desc.toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = find(group.getChildAt(i), prefix);
                if (child != null) return child;
            }
        }
        return null;
    }
    private View readout(View view) {
        if (view.getClass().getSimpleName().equals("Readout")) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0; i<group.getChildCount(); i++) {
                View found = readout(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    private String display() {
        final String[] value = new String[1];
        runOnMainSync(() -> value[0] = readout(activity.getWindow().getDecorView()).getContentDescription().toString());
        return value[0];
    }
    private void assertText(String expected) {
        String actual = display();
        check(actual.contains(expected), "Readout contains " + expected + " [" + actual + "]");
    }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++; report.append("OK ").append(message).append('\n');
    }
    private void click(String prefix) {
        runOnMainSync(() -> {
            View view = find(prefix);
            if (view == null) throw new AssertionError("Missing control " + prefix);
            view.performClick();
        });
        waitForIdleSync();
    }
    private void tapAt(long time) {
        final int[] xy = new int[2];
        runOnMainSync(() -> {
            View view = find("Tap-Fläche"); view.getLocationOnScreen(xy);
            xy[0] += view.getWidth()/2; xy[1] += view.getHeight()/2;
        });
        long delay = time - SystemClock.uptimeMillis();
        if (delay > 0) SystemClock.sleep(delay);
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, xy[0], xy[1], 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        sendPointerSync(down); down.recycle();
        MotionEvent up = MotionEvent.obtain(time, time + 12, MotionEvent.ACTION_UP, xy[0], xy[1], 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        sendPointerSync(up); up.recycle();
        waitForIdleSync();
    }
    private void changeOrientation(int orientation) {
        ActivityMonitor monitor = addMonitor("de.mk2.bpm.MainActivity", null, false);
        runOnMainSync(() -> activity.setRequestedOrientation(orientation));
        Activity next = monitor.waitForActivityWithTimeout(8000);
        check(next != null, "Orientation recreated Activity");
        activity = next; removeMonitor(monitor);
        waitForIdleSync(); SystemClock.sleep(250);
    }
    private void assertBounds() {
        runOnMainSync(() -> {
            View pad = find("Tap-Fläche");
            ViewGroup body = (ViewGroup)pad.getParent();
            Rect safe = new Rect(body.getPaddingLeft(), body.getPaddingTop(),
                body.getWidth()-body.getPaddingRight(), body.getHeight()-body.getPaddingBottom());
            for (int i=0; i<body.getChildCount(); i++) {
                View child = body.getChildAt(i);
                Rect bounds = new Rect(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
                check(safe.contains(bounds), "Inside safe insets: " + child.getClass().getSimpleName() + " " + bounds);
                if (child.isClickable()) {
                    float density = child.getResources().getDisplayMetrics().density;
                    check(child.getHeight()/density >= 48 && child.getWidth()/density >= 48,
                        "Touch target at least 48dp: " + child.getClass().getSimpleName());
                }
            }
        });
    }
    private void shot(String filename) throws Exception {
        SystemClock.sleep(160);
        snapshot(filename);
    }
    private void snapshot(String filename) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        check(bitmap != null, "Screenshot " + filename);
        saveBitmap(bitmap, filename);
        bitmap.recycle();
    }
    private void saveBitmap(Bitmap bitmap, String filename) throws Exception {
        File folder = new File(getTargetContext().getExternalFilesDir(null), "qa");
        folder.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(folder, filename))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
