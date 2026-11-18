package com.andrea.rxhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The phone as the FireStick's remote, laid out for the thumb: everything sits at the bottom.
 * Holding any key holds it on the TV (down, repeats, up), like the real remote.
 */
public class RemoteActivity extends Activity {

    private static final long REPEAT_AFTER_MS = 450, REPEAT_EVERY_MS = 90;

    private final ExecutorService keys = Executors.newSingleThreadExecutor();
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger queued = new AtomicInteger();
    private Runnable repeater;
    private Tv tv;
    private TextView stickOn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.window(this);
        tv = new Config(this).tv();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, Ui.dp(this, 20));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, Ui.dp(this, 8)); // room between SOURCE and the switch below
        top.addView(Ui.text(this, "Remote", 26, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView power = Ui.power(this);
        power.setOnClickListener(v -> {
            Ui.haptic(v);
            Ui.burst(v);
            keys.execute(() -> {
                try {
                    tv.togglePower(tv.state());
                } catch (IOException e) {
                    fail(e);
                }
            });
        });
        TextView source = small(tvButton("SOURCE", 178)); // the TV's inputs, whatever the switch says
        LinearLayout.LayoutParams srcLp = new LinearLayout.LayoutParams(-2, -2);
        srcLp.rightMargin = Ui.dp(this, 12);
        // Shown only while the FireStick is in standby (checked on resume): wakes the stick alone.
        stickOn = small(Ui.button(this, "FIRESTICK ON", true));
        stickOn.setContentDescription("Turn the FireStick on");
        stickOn.setVisibility(View.GONE);
        stickOn.setOnClickListener(v -> {
            Ui.haptic(v);
            v.setVisibility(View.GONE);
            send(tv::wakeStick);
        });
        top.addView(stickOn, srcLp);
        top.addView(source, srcLp);
        top.addView(power);
        col.addView(top);
        if (tv.mode().equals("stick")) col.addView(typingBar(), barLp()); // typing goes through the FireStick
        col.setFocusableInTouchMode(true); // the screen, not the bar, gets focus: no keyboard until the bar is tapped
        col.requestFocus();

        // A TV with a FireStick and/or Sky box bound: which one the arrows, OK, back, home and touchpad
        // drive, and which buttons show. Volume and power always go to the TV, apps to the FireStick.
        if (tv.showSwitch()) {
            java.util.List<View> styles = new java.util.ArrayList<>();
            for (String m : tv.modes()) {
                boolean on = m.equals(tv.mode());
                String label = m.equals("tv") ? "TV" : m.equals("stick") ? "FIRESTICK" : "SKY";
                TextView b = Ui.button(this, label, on);
                b.setContentDescription("Remote controls the " + label + (on ? ", selected" : ""));
                b.setOnClickListener(v -> {
                    Ui.haptic(v);
                    tv.setMode(m);
                    new Config(this).setInput(m);
                    recreate(); // redraw in the new style
                });
                styles.add(small(b));
            }
            col.addView(row(styles.toArray(new View[0])));
        }

        // The no-look part: a touchpad filling the screen (swipes, taps and shapes, see Gestures).
        TouchpadView pad = new TouchpadView(this, new Gestures.Out() {
            @Override public void press(int code) { send(() -> tv.key(code)); }
            @Override public void down(int code) { send(() -> tv.keyDown(code)); }
            @Override public void repeat(int code, int n) { if (queued.get() < 2) send(() -> tv.keyRepeat(code, n)); }
            @Override public void up(int code) { send(() -> tv.keyUp(code)); }
        });
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(-1, 0, 1);
        padLp.topMargin = Ui.dp(this, 4);
        padLp.bottomMargin = Ui.dp(this, 12);
        col.addView(pad, padLp);

        // Home, back and play/pause are gestures on the pad; the rest sit along the bottom edge
        // (BACK too, as a backup if a gesture is misread).
        TextView apps = small(Ui.button(this, "APPS", false));
        apps.setOnClickListener(v -> startActivity(new android.content.Intent(this, TvAppsActivity.class)));
        if (!tv.can("apps")) { // e.g. a Philips without a FireStick: greyed, not silently doing nothing
            apps.setEnabled(false);
            apps.setAlpha(0.4f);
        }
        if (tv.mode().equals("sky")) {
            col.addView(row(small(key("BACK", 4)), small(skyButton("CH +", Sky.CHANNEL_UP, Ui.TEXT)),
                small(skyButton("CH −", Sky.CHANNEL_DOWN, Ui.TEXT)), small(skyButton("i", Sky.INFO, Ui.TEXT)),
                small(skyButton("REC", Sky.RECORD, Ui.RED)), small(tvButton("MUTE", 164))));
            col.addView(row(small(skyButton("●", Sky.RED, 0xFFE53935)), small(skyButton("●", Sky.GREEN, 0xFF43A047)),
                small(skyButton("●", Sky.YELLOW, 0xFFFDD835)), small(skyButton("●", Sky.BLUE, 0xFF1E88E5)),
                small(key("◀◀", 89)), small(key("▶▶", 90))));
        } else {
            col.addView(row(small(key("BACK", 4)), small(key("MENU", 82)), small(key("◀◀", 89)), small(key("▶▶", 90)),
                small(tvButton("MUTE", 164)), apps));
        }

        setContentView(col);
    }

    /** A key for the TV itself (volume / mute: its own API when paired), whatever the switch says. */
    private TextView tvButton(String label, int key) {
        TextView b = Ui.button(this, label, false);
        b.setContentDescription(label);
        b.setOnClickListener(v -> {
            Ui.haptic(v);
            send(() -> tv.volumeKey(key));
        });
        return b;
    }

    /** A Sky-only key (channels, colours, record), by its Sky code. */
    private TextView skyButton(String label, int code, int color) {
        TextView b = Ui.button(this, label, false);
        b.setTextColor(color);
        String[] names = {"red", "green", "yellow", "blue"};
        b.setContentDescription(code >= Sky.RED && code <= Sky.BLUE ? names[code - Sky.RED] + " button" : label);
        b.setOnClickListener(v -> {
            Ui.haptic(v);
            send(() -> tv.skyKey(code));
        });
        return b;
    }

    private TextView small(TextView b) {
        b.setTextSize(13);
        b.setMinHeight(Ui.dp(this, 56));
        return b;
    }

    // The phone's own volume buttons drive the TV's while the remote is open: found by feel.
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            int k = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ? 24 : 25;
            if (queued.get() < 2) send(() -> tv.volumeKey(k));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!tv.hasStick()) return;
        io.execute(() -> {
            boolean standby = tv.state() == State.OFF; // with a stick, state() is the stick's own
            runOnUiThread(() -> stickOn.setVisibility(standby ? View.VISIBLE : View.GONE));
        });
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        keys.shutdownNow();
        io.shutdownNow();
        super.onDestroy();
    }

    /** Finger down: key down now, then repeats while held. */
    private void hold(int code) {
        main.removeCallbacks(repeater);
        send(() -> tv.keyDown(code));
        int[] n = {0};
        repeater = new Runnable() {
            @Override public void run() {
                int k = ++n[0];
                // Skip a beat rather than queue repeats behind a slow network.
                if (queued.get() < 2) send(() -> tv.keyRepeat(code, k));
                main.postDelayed(this, REPEAT_EVERY_MS);
            }
        };
        main.postDelayed(repeater, REPEAT_AFTER_MS);
    }

    private void release(int code) {
        main.removeCallbacks(repeater);
        send(() -> tv.keyUp(code));
    }

    private interface Key {
        void go() throws IOException;
    }

    private String sentText = "";
    private android.widget.EditText bar;
    private final Runnable flushTyping = this::flushTyping;

    /** Typed here, shown on the TV as you go: whatever field is selected there (a search, a login). */
    private View typingBar() {
        bar = new android.widget.EditText(this);
        bar.setHint("Type on the TV…");
        bar.setHintTextColor(Ui.DIM);
        bar.setTextColor(Ui.TEXT);
        bar.setTypeface(Ui.font(this, false));
        bar.setSingleLine(true);
        bar.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        bar.setContentDescription("Type on the TV");
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Ui.TILE);
        bg.setCornerRadius(Ui.dp(this, 10));
        bar.setBackground(bg);
        int p = Ui.dp(this, 14);
        bar.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        bar.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                bar.removeCallbacks(flushTyping);
                bar.postDelayed(flushTyping, 300); // a burst at a time: each "type" takes ~1 s on the TV
            }
        });
        bar.setOnEditorActionListener((v, action, event) -> {
            bar.removeCallbacks(flushTyping);
            flushTyping();
            send(() -> tv.key(FireTv.ENTER));
            sentText = ""; // a new search starts empty; the TV keeps what it got
            bar.setText("");
            bar.removeCallbacks(flushTyping);
            return true;
        });
        return bar;
    }

    private LinearLayout.LayoutParams barLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 10);
        return lp;
    }

    /** Sends what changed since last time: deletes back to where it differs, then types the rest. */
    private void flushTyping() {
        String now = bar.getText().toString();
        String[] c = Typing.change(sentText, now);
        sentText = now;
        int deletes = Integer.parseInt(c[0]);
        String typed = c[1];
        if (deletes > 0) send(() -> { for (int i = 0; i < deletes; i++) tv.key(FireTv.DEL); });
        if (!typed.isEmpty()) send(() -> tv.type(typed));
    }

    private void send(Key k) {
        queued.incrementAndGet();
        keys.execute(() -> {
            try {
                k.go();
            } catch (IOException e) {
                fail(e);
            } finally {
                queued.decrementAndGet();
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility") // touch drives hold; a plain click (e.g. TalkBack) still presses
    private TextView key(String label, int code) {
        TextView b = Ui.button(this, label, false);
        b.setTextSize(17);
        b.setMinHeight(Ui.dp(this, 60));
        b.setContentDescription(label);
        b.setOnClickListener(v -> send(() -> tv.key(code)));
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    Ui.haptic(v);
                    hold(code);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    release(code);
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i < views.length - 1) lp.rightMargin = Ui.dp(this, 8);
            r.addView(views[i], lp);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = Ui.dp(this, 8);
        r.setLayoutParams(rp);
        return r;
    }

    private void fail(IOException e) {
        String why = e.getMessage() != null && e.getMessage().startsWith("Pair") ? e.getMessage() : MainActivity.adbError(e);
        runOnUiThread(() -> {
            if (!isDestroyed()) Toast.makeText(this, why, Toast.LENGTH_SHORT).show();
        });
    }
}
