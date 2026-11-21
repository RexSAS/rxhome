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
        top.addView(source, srcLp);
        top.addView(power);
        col.addView(top);

        // TV + FireStick: where the arrows, OK, back, home and touchpad go. Volume and power always go to
        // the TV, apps always to the FireStick.
        if (tv.showSwitch()) {
            boolean onTv = tv.inputToTv();
            TextView toStick = Ui.button(this, "FIRESTICK", !onTv), toTv = Ui.button(this, "TV", onTv);
            toStick.setContentDescription("Remote controls the FireStick" + (onTv ? "" : ", selected"));
            toTv.setContentDescription("Remote controls the TV" + (onTv ? ", selected" : ""));
            View.OnClickListener pick = v -> {
                Ui.haptic(v);
                tv.setInputToTv(v == toTv);
                new Config(this).setInputToTv(v == toTv);
                recreate(); // redraw with the new choice highlighted
            };
            toStick.setOnClickListener(pick);
            toTv.setOnClickListener(pick);
            col.addView(row(small(toStick), small(toTv)));
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
        col.addView(row(small(key("BACK", 4)), small(key("MENU", 82)), small(key("◀◀", 89)), small(key("▶▶", 90)),
            small(tvButton("MUTE", 164)), apps));

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
