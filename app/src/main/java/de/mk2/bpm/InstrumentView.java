package de.mk2.bpm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import java.util.Locale;

/** Resolution-independent, code-drawn metalwork. No images, network, or third-party runtime. */
final class InstrumentView extends ViewGroup {
    interface Actions {
        void tap(long timestamp);
        void reset();
        void toggleHaptics();
        void changeRate();
        void toggleMicrophone();
    }
    private static final int INK = Color.rgb(37, 43, 45);
    private static final int AMBER = Color.rgb(255, 142, 57);
    private static final Typeface LABEL = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface MONO = Typeface.create("monospace", Typeface.NORMAL);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF face = new RectF();
    private final Header header;
    private final Readout readout;
    private final TapPad pad;
    private final Button reset;
    private final Button haptic;
    private final Button rate;
    private final Button microphone;
    private final Footer footer;
    private final float density;

    InstrumentView(Context context, Actions actions) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClipChildren(false);
        // Deliver overlapping contacts to the same pad as pointer down/up events.
        setMotionEventSplittingEnabled(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        header = new Header(context);
        readout = new Readout(context);
        pad = new TapPad(context, actions);
        reset = control("RESET", "BPM-Messung zurücksetzen");
        reset.setOnClickListener(v -> actions.reset());
        haptic = control("HAPTIK AN", "Vibration umschalten");
        haptic.setOnClickListener(v -> actions.toggleHaptics());
        rate = control("TEMPO × 1", "Tempo-Faktor umschalten: normal, doppelt oder halb");
        rate.setOnClickListener(v -> actions.changeRate());
        microphone = control("BPM ERKENNEN  ·  MIKROFON", "BPM erkennen. Musik über das Mikrofon analysieren.");
        microphone.setOnClickListener(v -> actions.toggleMicrophone());
        footer = new Footer(context);
        addView(header); addView(readout); addView(pad);
        addView(microphone); addView(reset); addView(haptic); addView(rate); addView(footer);
    }

    private Button control(String label, String description) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextColor(INK);
        button.setTextSize(11);
        button.setTypeface(LABEL);
        button.setLetterSpacing(0.03f);
        button.setAllCaps(false);
        button.setGravity(android.view.Gravity.CENTER);
        button.setPadding(2, 0, 2, 0);
        button.setMinHeight(0); button.setMinimumHeight(0);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setSingleLine(false);
        button.setContentDescription(description);
        StateListDrawable state = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xff828b8e, 0xffb7bfc0});
        pressed.setCornerRadius(dp(5)); pressed.setStroke((int) dp(1), 0xff626b6e);
        GradientDrawable normal = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xffedf0ee, 0xffb2b9bb});
        normal.setCornerRadius(dp(5)); normal.setStroke((int) dp(1), 0xff7a8487);
        GradientDrawable focused = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xfff8e1c8, 0xffc6b59e});
        focused.setCornerRadius(dp(5)); focused.setStroke((int) dp(2), AMBER);
        state.addState(new int[]{android.R.attr.state_pressed}, pressed);
        GradientDrawable activated = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xff374043, 0xff20272a});
        activated.setCornerRadius(dp(5)); activated.setStroke((int) dp(1), 0xff161e21);
        state.addState(new int[]{android.R.attr.state_activated}, activated);
        state.addState(new int[]{android.R.attr.state_focused}, focused);
        state.addState(new int[]{}, normal);
        button.setBackground(state);
        button.setStateListAnimator(null);
        return button;
    }

    void update(double bpm, int taps, double confidence, String status, boolean holding,
                boolean haptics, String factor, boolean audioMode, boolean listening,
                double seconds, double level, String hint) {
        readout.update(bpm, taps, confidence, status, holding, audioMode, listening, seconds, level);
        String hapticText = haptics ? "HAPTIK AN" : "HAPTIK AUS";
        if (!hapticText.contentEquals(haptic.getText())) haptic.setText(hapticText);
        haptic.setContentDescription("Vibration " + (haptics ? "an" : "aus") + ". Antippen zum Umschalten.");
        String rateText = "TEMPO " + factor;
        if (!rateText.contentEquals(rate.getText())) rate.setText(rateText);
        rate.setContentDescription("Tempo-Faktor " + factor + ". Antippen zum Umschalten.");
        String micText = listening ? "■  ERKENNUNG STOPPEN" : "BPM ERKENNEN  ·  MIKROFON";
        if (!micText.contentEquals(microphone.getText())) {
            microphone.setText(micText);
            microphone.setActivated(listening);
            microphone.setTextColor(listening ? AMBER : INK);
            microphone.setContentDescription(listening ? "Erkennung stoppen. Mikrofon aktiv."
                : "BPM erkennen. Musik über das Mikrofon analysieren.");
        }
        footer.setHint(hint);
    }

    void flash() { pad.flash(); readout.flash(); }
    void setAutoBeat(double displayedBpm, double sourceBpm, double beatUptime, boolean enabled) {
        pad.setAutoBeat(displayedBpm, sourceBpm, beatUptime, enabled);
    }
    void flashLiveBeat(double displayedBpm, double sourceBpm, double beatUptime) {
        pad.flashLiveBeat(displayedBpm, sourceBpm, beatUptime);
    }
    private float dp(float value) { return value * density; }

    private void size(View view, float w, float h) {
        view.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(w)), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(h)), MeasureSpec.EXACTLY));
    }
    private void place(View view, float x, float y) {
        int ix = Math.round(x), iy = Math.round(y);
        view.layout(ix, iy, ix + view.getMeasuredWidth(), iy + view.getMeasuredHeight());
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        arrange(false);
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { arrange(true); }

    private void arrange(boolean layout) {
        float availableW = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        float availableH = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        boolean landscape = availableW > availableH * 1.2f;
        float outside = dp(10);
        float fw = Math.min(availableW - outside * 2, dp(landscape ? 940 : 540));
        float fh = availableH - outside * 2;
        float fx = getPaddingLeft() + (availableW - fw) / 2;
        float fy = getPaddingTop() + outside;
        face.set(fx, fy, fx + fw, fy + fh);
        float inset = dp(22), gap = dp(14);
        float x = fx + inset, width = fw - inset * 2;
        float headerH = dp(landscape ? 61 : 83);
        float footerH = dp(32), controlsH = dp(50);
        float controlsY = face.bottom - dp(18) - footerH - controlsH;
        float contentY = fy + headerH;
        float contentBottom = controlsY - gap;
        float micH = dp(50);
        size(header, width, headerH);
        size(footer, width, footerH);
        if (landscape) {
            float column = (width - gap) / 2;
            size(readout, column, contentBottom - contentY);
            size(pad, column, contentBottom - contentY - micH - dp(8));
            size(microphone, column, micH);
            if (layout) {
                place(readout, x, contentY);
                place(pad, x + column + gap, contentY);
                place(microphone, x + column + gap, contentBottom - micH);
            }
        } else {
            float contentH = contentBottom - contentY - micH - gap;
            float displayH = Math.min(width * 0.61f, contentH * 0.48f);
            size(readout, width, displayH);
            size(pad, width, contentH - displayH - gap);
            size(microphone, width, micH);
            if (layout) {
                place(readout, x, contentY);
                place(pad, x, contentY + displayH + gap);
                place(microphone, x, contentBottom - micH);
            }
        }
        float buttonGap = dp(8);
        float buttonW = (width - buttonGap * 2) / 3;
        size(reset, buttonW, controlsH); size(haptic, buttonW, controlsH); size(rate, buttonW, controlsH);
        if (layout) {
            place(header, x, fy);
            place(reset, x, controlsY);
            place(haptic, x + buttonW + buttonGap, controlsY);
            place(rate, x + (buttonW + buttonGap) * 2, controlsY);
            place(footer, x, controlsY + controlsH + dp(7));
            Rect hit = new Rect(pad.getLeft() - Math.round(dp(12)), pad.getTop() - Math.round(dp(7)),
                pad.getRight() + Math.round(dp(12)), pad.getBottom() + Math.round(dp(3)));
            setTouchDelegate(new TouchDelegate(hit, pad));
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff171a1c);
        paint.setShader(null); paint.setColor(0xff060809);
        canvas.drawRoundRect(face.left - dp(2), face.top + dp(3), face.right + dp(2), face.bottom + dp(4), dp(15), dp(15), paint);
        paint.setShader(new LinearGradient(face.left, 0, face.right, 0,
            new int[]{0xff929b9e, 0xffcfd3d1, 0xffb9c0c0, 0xffd2d5d2, 0xff929c9f},
            new float[]{0, .16f, .51f, .83f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(face, dp(13), dp(13), paint);
        paint.setShader(null);
        canvas.save();
        Path clip = new Path(); clip.addRoundRect(face, dp(13), dp(13), Path.Direction.CW); canvas.clipPath(clip);
        paint.setStrokeWidth(Math.max(.5f, density * .35f));
        for (float y = face.top + dp(1); y < face.bottom; y += dp(1.8f)) {
            int grain = ((int)(y / density) * 37) % 13;
            paint.setColor(Color.argb(9 + grain, 31, 40, 44));
            canvas.drawLine(face.left, y, face.right, y, paint);
            paint.setColor(0x19ffffff);
            canvas.drawLine(face.left, y + dp(.6f), face.right, y + dp(.6f), paint);
        }
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0xffdce0dc);
        canvas.drawRoundRect(face.left + dp(1), face.top + dp(1), face.right - dp(1), face.bottom - dp(1), dp(12), dp(12), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private abstract static class Artwork extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Artwork(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void text(Canvas c, String value, float x, float y, float size, int color, Typeface font) {
            p.setShader(null); p.setColor(color); p.setTypeface(font); p.setTextSize(size);
            p.setStyle(Paint.Style.FILL);
            c.drawText(value, x, y, p);
        }
        void rect(Canvas c, float l, float t, float r, float b, float radius, int color) {
            p.setShader(null); p.setColor(color); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(l, t, r, b, radius, radius, p);
        }
    }

    private static final class Header extends Artwork {
        Header(Context context) { super(context); }
        @Override protected void onDraw(Canvas c) {
            // The landscape header is wide but shallow; use the limiting dimension so
            // its lettering stays inside the inset-safe header instead of clipping above it.
            float scale = Math.min(getWidth() / 360f, getHeight() / 83f);
            c.save(); c.scale(scale, scale);
            float h = getHeight() / scale;
            float w = getWidth() / scale;
            float base = Math.min(h - 25, 46);
            text(c, "UB", 0, base, 37, INK, Typeface.create("sans-serif-black", Typeface.ITALIC));
            text(c, "BPM Counter", 64, base - 1, 21, INK, LABEL);
            text(c, "PRECISION TAP INSTRUMENT", 1, base + 17, 8.7f, 0xff434c50, MONO);
            rect(c, w - 76, base - 24, w, base - 5, 3, 0xff30383b);
            text(c, "SERIES / 01", w - 68, base - 11, 8, 0xffe0e4df, MONO);
            p.setColor(0xff858f91); p.setStrokeWidth(1);
            c.drawLine(0, h - 8, w, h - 8, p);
            p.setColor(0xffe1e5e1); c.drawLine(0, h - 7, w, h - 7, p);
            c.restore();
        }
    }

    private static final class Readout extends Artwork {
        private double bpm;
        private int taps;
        private double confidence;
        private String status = "BEREIT";
        private boolean holding;
        private boolean audioMode;
        private boolean listening;
        private int seconds;
        private int levelBars;
        private long flashUntil;
        private static final int[] SEGMENTS = {0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f};

        Readout(Context context) {
            super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setFocusable(true);
            setContentDescription("Noch kein Tempo, 0 Taps. BEREIT.");
        }
        void update(double value, int count, double certainty, String label, boolean hold,
                    boolean micMode, boolean recording, double elapsed, double level) {
            int newSeconds = (int) elapsed;
            int newLevel = recording ? (int) Math.round(Math.min(1, Math.max(0, level)) * 8) : 0;
            if (bpm == value && taps == count && confidence == certainty && status.equals(label)
                    && holding == hold && audioMode == micMode && listening == recording
                    && seconds == newSeconds && levelBars == newLevel) return;
            bpm = value; taps = count; confidence = certainty; status = label; holding = hold;
            audioMode = micMode; listening = recording; seconds = newSeconds; levelBars = newLevel;
            setContentDescription((value > 0 ? String.format(Locale.GERMANY, "%.1f BPM", value) : "Noch kein Tempo")
                + (audioMode ? ", Mikrofon, " + seconds + " Sekunden. " : ", " + taps + " Taps. ")
                + label + "." + (listening ? " Mikrofon aktiv." : ""));
            invalidate();
        }
        void flash() { flashUntil = SystemClock.uptimeMillis() + 95; invalidate(); postInvalidateDelayed(100); }

        @Override protected void onDraw(Canvas c) {
            rect(c, 0, 0, getWidth(), getHeight(), 9, 0xff667073);
            rect(c, 0, 0, getWidth(), getHeight() - 2, 9, 0xff242b2e);
            rect(c, 3, 4, getWidth() - 3, getHeight() - 4, 7, 0xff0c1112);
            float scale = Math.min(getWidth() / 360f, getHeight() / 216f);
            c.save(); c.translate((getWidth() - 360 * scale) / 2, (getHeight() - 216 * scale) / 2); c.scale(scale, scale);
            p.setShader(new LinearGradient(0, 5, 0, 210, 0xff192121, 0xff090d0e, Shader.TileMode.CLAMP));
            c.drawRoundRect(5, 5, 355, 211, 5, 5, p); p.setShader(null);
            boolean blink = SystemClock.uptimeMillis() < flashUntil;
            rect(c, 22, 20, 29, 27, 1, blink || listening ? 0xffffb468 : 0xff804529);
            text(c, audioMode ? "MIKROFON" : "TEMPO", 36, 27, 10, 0xffc4aaa0, MONO);
            if (audioMode) {
                for (int i = 0; i < 8; i++) rect(c, 106 + i * 5, 27 - (4 + i * .6f),
                    109 + i * 5, 27, .5f, i < levelBars ? AMBER : 0xff303631);
            }
            text(c, "BEATS / MIN", 267, 27, 10, 0xffa78c7c, MONO);
            String digits = bpm > 0 ? String.format(Locale.US, "%5.1f", bpm) : "---.-";
            float x = 24;
            for (int i = 0; i < digits.length(); i++) {
                char digit = digits.charAt(i);
                if (digit == '.') {
                    rect(c, x + 1, 122, x + 8, 129, 1.5f, bpm > 0 ? AMBER : 0xffa4532c);
                    x += 17;
                } else {
                    drawDigit(c, digit, x, 43, 59, 88); x += 69;
                }
            }
            text(c, audioMode ? String.format(Locale.US, "%02d S AUDIO", seconds)
                : String.format(Locale.US, "%02d TAPS", taps), 23, 154, 11, 0xffbaa598, MONO);
            p.setTypeface(MONO); p.setTextSize(11);
            float labelWidth = p.measureText(status);
            text(c, status, 337 - labelWidth, 154, 11, holding ? 0xffd5b578 : 0xffeea869, MONO);
            for (int i = 0; i < 28; i++) {
                int color = i < Math.round(confidence * 28) ? (holding ? 0xff947450 : AMBER) : 0xff28302d;
                rect(c, 23 + i * 11.35f, 172, 30 + i * 11.35f, 179, 1, color);
            }
            text(c, "STABILITÄT", 23, 197, 8.5f, 0xff908e7d, MONO);
            text(c, audioMode ? "LIVE / MIC INPUT" : "LIVE / TAP INPUT", 254, 197, 8.5f, 0xff908e7d, MONO);
            // A restrained reflection across the glass.
            Path reflection = new Path(); reflection.moveTo(7, 7); reflection.lineTo(353, 7);
            reflection.lineTo(353, 45); reflection.lineTo(7, 83); reflection.close();
            p.setColor(0x07ffffff); c.drawPath(reflection, p);
            c.restore();
            if (isFocused()) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(AMBER);
                c.drawRoundRect(2, 2, getWidth()-2, getHeight()-2, 7, 7, p); p.setStyle(Paint.Style.FILL); }
        }

        private void drawDigit(Canvas c, char digit, float x, float y, float w, float h) {
            int mask = digit >= '0' && digit <= '9' ? SEGMENTS[digit - '0'] : digit == '-' ? 0x40 : 0;
            float th = 7, half = h / 2;
            float[][] endpoints = {{x+8,y+4,x+w-8,y+4}, {x+w-4,y+10,x+w-4,y+half-7},
                {x+w-4,y+half+7,x+w-4,y+h-10}, {x+8,y+h-4,x+w-8,y+h-4},
                {x+4,y+half+7,x+4,y+h-10}, {x+4,y+10,x+4,y+half-7},
                {x+9,y+half,x+w-9,y+half}};
            for (int i=0; i<7; i++) {
                float[] e = endpoints[i];
                Path segment = new Path();
                if (e[1] == e[3]) {
                    segment.moveTo(e[0]-3,e[1]); segment.lineTo(e[0]+1,e[1]-th/2);
                    segment.lineTo(e[2]-1,e[3]-th/2); segment.lineTo(e[2]+3,e[3]);
                    segment.lineTo(e[2]-1,e[3]+th/2); segment.lineTo(e[0]+1,e[1]+th/2);
                } else {
                    segment.moveTo(e[0],e[1]-3); segment.lineTo(e[0]+th/2,e[1]+1);
                    segment.lineTo(e[2]+th/2,e[3]-1); segment.lineTo(e[2],e[3]+3);
                    segment.lineTo(e[2]-th/2,e[3]-1); segment.lineTo(e[0]-th/2,e[1]+1);
                }
                segment.close();
                boolean lit = (mask & (1 << i)) != 0;
                p.setColor(lit ? (bpm > 0 ? AMBER : 0xffa4532c) : 0xff231c17);
                c.drawPath(segment, p);
                if (lit) { p.setColor(0x1fff964f); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4);
                    c.drawPath(segment, p); p.setStyle(Paint.Style.FILL); }
            }
        }
    }

    private static final class TapPad extends Artwork {
        private final Actions actions;
        private boolean physicalClick;
        private boolean touching;
        private int downPointerBits;
        private long flashUntil;
        private long autoFlashUntil;
        private long autoPulseCount;
        private long lastAutoPulseMillis = Long.MIN_VALUE;
        private final BeatPulseClock autoClock = new BeatPulseClock();
        private final Runnable clearAutoFlash = () -> invalidateLed();
        private final Runnable autoPulse = new Runnable() {
            @Override public void run() {
                if (!autoClock.isActive() || !isShown()) { stopAutoBeat(); return; }
                long now = SystemClock.uptimeMillis();
                flashAutoPulse(now);
                scheduleAutoPulse();
            }
        };

        void setAutoBeat(double displayedBpm, double sourceBpm, double beatUptime, boolean enabled) {
            autoClock.update(displayedBpm, sourceBpm, beatUptime, enabled);
            removeCallbacks(autoPulse);
            if (autoClock.isActive()) {
                if (lastAutoPulseMillis != Long.MIN_VALUE) autoClock.recordPulse(lastAutoPulseMillis);
                scheduleAutoPulse();
            } else if (!enabled) stopAutoBeat();
        }

        void flashLiveBeat(double displayedBpm, double sourceBpm, double beatUptime) {
            if (!isShown()) return;
            long now = SystemClock.uptimeMillis();
            setAutoBeat(displayedBpm, sourceBpm, beatUptime, true);
            if (!autoClock.isActive() || autoClock.acceptsLiveBeat(beatUptime, now)) {
                if (lastAutoPulseMillis == Long.MIN_VALUE || now - lastAutoPulseMillis >= 160) flashAutoPulse(now);
            }
            removeCallbacks(autoPulse);
            if (autoClock.isActive()) scheduleAutoPulse();
        }

        private void flashAutoPulse(long now) {
            autoClock.recordPulse(now);
            lastAutoPulseMillis = now;
            autoPulseCount++;
            long duration = autoClock.isActive() ? (long) Math.min(90.0, autoClock.getPeriodMillis() * .22) : 90L;
            autoFlashUntil = now + duration;
            invalidateLed();
            removeCallbacks(clearAutoFlash);
            postDelayed(clearAutoFlash, duration + 1);
        }

        private void scheduleAutoPulse() {
            long now = SystemClock.uptimeMillis();
            long next = autoClock.nextPulseMillis(now);
            if (next >= 0) postDelayed(autoPulse, Math.max(0, next - now));
        }

        private void stopAutoBeat() {
            autoClock.stop();
            lastAutoPulseMillis = Long.MIN_VALUE;
            removeCallbacks(autoPulse);
            removeCallbacks(clearAutoFlash);
            if (autoFlashUntil != 0L) { autoFlashUntil = 0L; invalidateLed(); }
        }

        private void invalidateLed() {
            float s = getWidth() / 360f;
            invalidate((int) (296*s), (int) (25*s), (int) Math.ceil(331*s), (int) Math.ceil(43*s));
        }

        @Override protected void onDetachedFromWindow() {
            stopAutoBeat();
            super.onDetachedFromWindow();
        }
        TapPad(Context context, Actions listener) {
            super(context); actions = listener;
            setClickable(true); setFocusable(true); setFocusableInTouchMode(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setContentDescription("Tap-Fläche. Mit Daumen oder Fingern im Rhythmus antippen, um BPM zu messen.");
        }
        void flash() { flashUntil = SystemClock.uptimeMillis() + 100; invalidate(); postInvalidateDelayed(110); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                if (action == MotionEvent.ACTION_DOWN) downPointerBits = 0;
                int index = event.getActionIndex();
                int bit = 1 << event.getPointerId(index);
                float extraX = 12 * getResources().getDisplayMetrics().density;
                float extraTop = 7 * getResources().getDisplayMetrics().density;
                float extraBottom = 3 * getResources().getDisplayMetrics().density;
                // A held finger in the pad must not turn a second finger over
                // another control into a beat. Contact size/pressure is unrestricted.
                if (event.getX(index) < -extraX || event.getX(index) >= getWidth() + extraX
                        || event.getY(index) < -extraTop || event.getY(index) >= getHeight() + extraBottom) return true;
                if ((downPointerBits & bit) != 0) return true;
                downPointerBits |= bit;
                getParent().requestDisallowInterceptTouchEvent(true);
                touching = true; setPressed(true); actions.tap(event.getEventTime()); invalidate(); return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP) {
                downPointerBits &= ~(1 << event.getPointerId(event.getActionIndex()));
                touching = downPointerBits != 0;
                setPressed(touching); invalidate(); return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                downPointerBits = 0;
                touching = false; setPressed(false); physicalClick = true;
                performClick(); invalidate(); return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                downPointerBits = 0;
                touching = false; setPressed(false); physicalClick = false; invalidate(); return true;
            }
            return true;
        }
        @Override public boolean performClick() {
            super.performClick();
            if (!physicalClick) actions.tap(SystemClock.uptimeMillis());
            physicalClick = false;
            return true;
        }
        @Override public boolean onKeyDown(int code, KeyEvent event) {
            if (code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (event.getRepeatCount() == 0) { touching = true; setPressed(true); actions.tap(event.getEventTime()); invalidate(); }
                return true;
            }
            return super.onKeyDown(code, event);
        }
        @Override public boolean onKeyUp(int code, KeyEvent event) {
            if (code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                touching = false; setPressed(false); invalidate(); return true;
            }
            return super.onKeyUp(code, event);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info); info.setClassName(Button.class.getName());
        }
        @Override protected void onDraw(Canvas c) {
            float scale = getWidth() / 360f;
            c.save(); c.scale(scale, scale);
            float h = getHeight() / scale;
            boolean active = SystemClock.uptimeMillis() < Math.max(flashUntil, autoFlashUntil);
            float sink = touching ? 3 : 0;
            rect(c, 0, 0, 360, h, 9, 0xff737d80);
            rect(c, 1, 1, 359, h - 2, 8, 0xff252c2e);
            rect(c, 5, 5, 355, h - 6, 5, 0xff13191b);
            p.setShader(new LinearGradient(0, 9, 0, h - 9,
                touching ? new int[]{0xffbac2c3,0xffcbd0cf,0xffa0aaad} : new int[]{0xfff1f2eb,0xffc4cccd,0xffb3bdc0},
                new float[]{0,.30f,1}, Shader.TileMode.CLAMP));
            c.drawRoundRect(9, 8 + sink, 351, h - 14 + sink, 4, 4, p); p.setShader(null);
            for (float y=12+sink; y<h-15+sink; y+=2) {
                p.setColor(((int)y % 3 == 0) ? 0x14ffffff : 0x0c172b33);
                p.setStrokeWidth(.6f); c.drawLine(11, y, 349, y, p);
            }
            p.setColor(touching ? 0xff808b90 : 0xffeef2eb); p.setStrokeWidth(1);
            c.drawLine(11, 9 + sink, 349, 9 + sink, p);
            p.setColor(0xff8b9599); c.drawLine(11,h-15+sink,349,h-15+sink,p);
            float titleY = Math.max(33, Math.min(90, h * .36f)) + sink;
            text(c, "TAP", 29, titleY, Math.min(56, h * .25f), INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
            if (h > 95) text(c, "IM TAKT ANTIPPEN", 31, titleY + 23, 10, 0xff465256, MONO);
            rect(c, 300, 29 + sink, 327, 36 + sink, 2, 0xff586062);
            rect(c, 302, 30 + sink, 325, 34 + sink, 1, active ? 0xffffa147 : 0xff995b32);
            float grooveY = Math.max(titleY + 45, h - 64) + sink;
            for (int i=0; h > 135 && i<3; i++) {
                rect(c, 31, grooveY+i*5, 329, grooveY+i*5+1, .5f, 0xff8b999f);
                rect(c, 31, grooveY+i*5+1, 329, grooveY+i*5+2, .5f, 0xffdde4e2);
            }
            if (h > 195) text(c, "DIRECT DRIVE  /  MANUAL TEMPO CONTROL", 31, h - 28 + sink, 8, 0xff4e5e63, MONO);
            if (isFocused()) {
                p.setStyle(Paint.Style.STROKE); p.setColor(AMBER); p.setStrokeWidth(2);
                c.drawRoundRect(2, 2, 358, h-2, 8, 8, p); p.setStyle(Paint.Style.FILL);
            }
            c.restore();
        }
    }

    private static final class Footer extends Artwork {
        private String hint = "DEIN TAKT. DEIN TEMPO.";
        Footer(Context context) { super(context); }
        void setHint(String value) { if (!hint.equals(value)) { hint = value; invalidate(); } }
        @Override protected void onDraw(Canvas c) {
            float scale = Math.min(getWidth() / 360f, getHeight() / 32f);
            c.save(); c.scale(scale,scale);
            float w = getWidth() / scale;
            text(c, hint, 1, 17, 9, 0xff404e53, MONO);
            text(c, "UB / 1.2.2", w - 68, 17, 8.5f, 0xff4d5b60, MONO);
            c.restore();
        }
    }
}
