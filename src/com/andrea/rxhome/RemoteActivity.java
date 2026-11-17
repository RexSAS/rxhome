package com.andrea.rxhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The phone as the FireStick's remote. Keys go one at a time, in order. */
public class RemoteActivity extends Activity {

    private final ExecutorService keys = Executors.newSingleThreadExecutor();
    private final ExecutorService io = Executors.newCachedThreadPool();
    private FireTv tv;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.window(this);
        tv = new Config(this).tv();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, p);
        TextView title = Ui.text(this, "Remote", 26, Ui.TEXT, true);
        title.setPadding(0, 0, 0, Ui.dp(this, 16));
        col.addView(title);

        TextView power = Ui.button(this, "POWER", true);
        power.setMinHeight(Ui.dp(this, 64));
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
        col.addView(row(power, key("HOME", 3), key("BACK", 4), key("MENU", 82)));

        // D-pad: 3×3 with the corners empty.
        col.addView(row(space(), key("▲", 19), space()));
        col.addView(row(key("◀", 21), key("OK", 23), key("▶", 22)));
        col.addView(row(space(), key("▼", 20), space()));

        col.addView(row(key("◀◀", 89), key("⏯", 85), key("▶▶", 90)));
        col.addView(row(key("VOL −", 25), key("MUTE", 164), key("VOL +", 24)));

        TextView apps = Ui.button(this, "APPS", false);
        apps.setOnClickListener(v -> MainActivity.showApps(this, io, tv));
        col.addView(row(apps));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onDestroy() {
        keys.shutdownNow();
        io.shutdownNow();
        super.onDestroy();
    }

    private TextView key(String label, int code) {
        TextView b = Ui.button(this, label, false);
        b.setTextSize(18);
        b.setMinHeight(Ui.dp(this, 64));
        b.setContentDescription(label);
        b.setOnClickListener(v -> {
            Ui.haptic(v);
            keys.execute(() -> {
                try {
                    tv.key(code);
                } catch (IOException e) {
                    fail(e);
                }
            });
        });
        return b;
    }

    private View space() {
        return new View(this);
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER);
        for (int i = 0; i < views.length; i++) {
            // Wrap height: a bare spacer View with match_parent would swallow the whole screen.
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
        // Drop queued keys: they'd all fail the same way.
        runOnUiThread(() -> {
            if (!isDestroyed()) Toast.makeText(this, MainActivity.adbError(e), Toast.LENGTH_SHORT).show();
        });
    }
}
