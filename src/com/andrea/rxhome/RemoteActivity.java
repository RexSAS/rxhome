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
import android.widget.ScrollView;
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
    private FireTv tv;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.window(this);
        tv = new Config(this).tv();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, Ui.dp(this, 28));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.text(this, "Remote", 26, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView power = Ui.power(this);
        power.setOnClickListener(v -> {
            Ui.haptic(v);
            keys.execute(() -> {
                try {
                    if (tv.state() == State.ON) tv.sleep();
                    else tv.wake();
                } catch (IOException e) {
                    fail(e);
                }
            });
        });
        top.addView(power);
        col.addView(top);

        // Empty space above: the controls hang at the bottom, where the thumb is.
        col.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));

        col.addView(row(key("BACK", 4), key("HOME", 3), key("MENU", 82)));

        DpadView pad = new DpadView(this);
        pad.listener = new DpadView.Listener() {
            @Override public void down(int code) { Ui.haptic(pad); hold(code); }
            @Override public void up(int code) { release(code); }
        };
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(-1, -2);
        padLp.topMargin = Ui.dp(this, 8);
        padLp.bottomMargin = Ui.dp(this, 16);
        col.addView(pad, padLp);

        col.addView(row(key("◀◀", 89), key("⏯", 85), key("▶▶", 90)));
        col.addView(row(key("VOL −", 25), key("MUTE", 164), key("VOL +", 24)));
        TextView apps = Ui.button(this, "APPS", false);
        apps.setOnClickListener(v -> MainActivity.showApps(this, io, tv));
        col.addView(row(apps));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); // lets the spacer push the controls to the bottom
        scroll.addView(col, new ScrollView.LayoutParams(-1, -1));
        setContentView(scroll);
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
        runOnUiThread(() -> {
            if (!isDestroyed()) Toast.makeText(this, MainActivity.adbError(e), Toast.LENGTH_SHORT).show();
        });
    }
}
