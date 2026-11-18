package com.andrea.rxhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One TV: its name and what it groups (a stick for status/remote/apps, a Philips for power/volume). */
public class TvEditorActivity extends Activity {

    private static final String NONE = "None";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Config cfg;
    private String id, stick, philips;
    private boolean isNew;
    private final List<String> foundSticks = new ArrayList<>(), foundPhilips = new ArrayList<>();
    private EditText name;
    private LinearLayout parts;
    private TextView scan;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.window(this);
        cfg = new Config(this);
        List<Tvs.Tv> tvs = cfg.tvs();
        Tvs.Tv t = Tvs.find(tvs, getIntent().getStringExtra("tv"));
        isNew = t == null;
        if (isNew) t = new Tvs.Tv(Tvs.newId(tvs), "TV " + (tvs.size() + 1), "", "");
        id = t.id;
        stick = t.stick;
        philips = t.philips;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, p);
        col.addView(Ui.text(this, isNew ? "New TV" : t.name, 26, Ui.TEXT, true));
        label(col, "NAME");
        name = new EditText(this);
        name.setText(t.name);
        name.setTextColor(Ui.TEXT);
        name.setSingleLine(true);
        name.setContentDescription("TV name");
        col.addView(name);

        scan = Ui.button(this, "SCAN THE WI-FI FOR TVS", false);
        scan.setOnClickListener(v -> scan());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = Ui.dp(this, 16);
        col.addView(scan, sl);

        parts = new LinearLayout(this);
        parts.setOrientation(LinearLayout.VERTICAL);
        Ui.animateLayout(parts);
        col.addView(parts);
        renderParts();

        TextView save = Ui.button(this, "SAVE", true);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 24);
        col.addView(save, lp);
        if (!isNew) {
            TextView del = Ui.button(this, "DELETE THIS TV", false);
            del.setOnClickListener(v -> confirmDelete());
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
            dl.topMargin = Ui.dp(this, 8);
            col.addView(del, dl);
        }

        Ui.cascade(col, 0);
        ScrollView s = new ScrollView(this);
        s.addView(col);
        setContentView(s);
        scan(); // fills the choices; the current devices show meanwhile
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void label(LinearLayout col, String text) {
        TextView t = Ui.text(this, text, 13, Ui.RED, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 6));
        col.addView(t);
    }

    /** The two choices: the stick and the Philips, each from what the scan found (plus the current one). */
    private void renderParts() {
        parts.removeAllViews();
        label(parts, "CONTROLS: STATUS, REMOTE, APPS");
        parts.addView(Ui.text(this, "A FireStick or Android TV (ADB debugging on).", 13, Ui.DIM, false));
        chips(parts, foundSticks, stick, pick -> stick = pick);
        label(parts, "POWER & VOLUME");
        parts.addView(Ui.text(this, "A Philips TV. Without one, the stick switches the TV over HDMI.", 13, Ui.DIM, false));
        chips(parts, foundPhilips, philips, pick -> philips = pick);
        if (!philips.isEmpty()) {
            boolean paired = !cfg.get(Tvs.key(id, Config.PHILIPS_USER)).isEmpty();
            TextView pair = Ui.button(this, paired ? "PAIRED · PAIR AGAIN" : "PAIR WITH THIS PHILIPS (TV ON)", false);
            pair.setOnClickListener(v -> pair());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 10);
            parts.addView(pair, lp);
        }
    }

    private interface Pick {
        void picked(String value);
    }

    private void chips(LinearLayout box, List<String> found, String current, Pick pick) {
        List<String> choices = new ArrayList<>(found);
        if (!current.isEmpty() && !choices.contains(current)) choices.add(0, current);
        choices.add(NONE);
        LinearLayout row = new LinearLayout(this);
        for (String c : choices) {
            boolean on = c.equals(NONE) ? current.isEmpty() : c.equals(current);
            TextView chip = Ui.button(this, c, on);
            chip.setTextSize(13);
            chip.setContentDescription(c + (on ? ", selected" : ""));
            chip.setOnClickListener(v -> {
                Ui.haptic(v);
                pick.picked(c.equals(NONE) ? "" : c);
                renderParts();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Ui.dp(this, 8);
            row.addView(chip, lp);
        }
        HorizontalScrollView sc = new HorizontalScrollView(this);
        sc.setHorizontalScrollBarEnabled(false);
        sc.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 10);
        box.addView(sc, lp);
    }

    private void scan() {
        scan.setText("SCANNING THE WI-FI…");
        scan.setEnabled(false);
        io.execute(() -> {
            String me = Discovery.wifiIp();
            List<List<String>> found = me == null ? null : Discovery.scanTvs(me);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                scan.setText("SCAN THE WI-FI AGAIN");
                scan.setEnabled(true);
                if (found == null) {
                    Toast.makeText(this, "Connect to the home Wi-Fi to find TVs", Toast.LENGTH_LONG).show();
                    return;
                }
                foundSticks.clear();
                foundSticks.addAll(found.get(0));
                foundPhilips.clear();
                foundPhilips.addAll(found.get(1));
                renderParts();
            });
        });
    }

    /** The Philips shows a PIN; typing it gives rxhome a login for this TV's own API. */
    private void pair() {
        String ip = philips;
        io.execute(() -> {
            try {
                Philips.Pairing p = Philips.pairRequest(ip);
                runOnUiThread(() -> askPin(ip, p));
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "Can't reach the TV (is it on?): " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void askPin(String ip, Philips.Pairing p) {
        if (isFinishing()) return;
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setHint("PIN");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Type the PIN shown on the TV")
            .setView(pin)
            .setPositiveButton("Pair", (d, w) -> io.execute(() -> {
                try {
                    Philips.pairGrant(ip, p, pin.getText().toString());
                    String mac = null;
                    try {
                        mac = new Philips(ip, p.deviceId, p.authKey, p.certPin).mac();
                    } catch (IOException e) {
                        // Without the MAC the TV can still be switched off, just not woken from standby.
                    }
                    cfg.put(Tvs.key(id, Config.PHILIPS_USER), p.deviceId);
                    cfg.put(Tvs.key(id, Config.PHILIPS_PASS), p.authKey);
                    cfg.put(Tvs.key(id, Config.PHILIPS_CERT), p.certPin);
                    if (mac != null) cfg.put(Tvs.key(id, Config.PHILIPS_MAC), mac);
                    String done = mac != null ? "Paired with the TV"
                        : "Paired with the TV (couldn't read its MAC: switching it on may not work)";
                    runOnUiThread(() -> {
                        Toast.makeText(this, done, Toast.LENGTH_LONG).show();
                        renderParts();
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> Toast.makeText(this, "Pairing failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void save() {
        String n = Tvs.clean(name.getText().toString());
        List<Tvs.Tv> tvs = cfg.tvs();
        Tvs.Tv same = Tvs.find(tvs, n);
        if (same != null && !same.id.equals(id)) {
            Toast.makeText(this, "Another TV is already called " + n + ": scenes find TVs by name", Toast.LENGTH_LONG).show();
            return;
        }
        Tvs.Tv t = new Tvs.Tv(id, n, stick, philips);
        Tvs.Tv old = Tvs.find(tvs, id);
        if (old == null) tvs.add(t);
        else {
            tvs.set(tvs.indexOf(old), t);
            if (!old.name.equals(n)) cfg.put(Config.SCENES, Scenes.renameTv(cfg.get(Config.SCENES), old.name, n));
        }
        cfg.saveTvs(tvs);
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete " + name.getText() + "?")
            .setMessage("Scene steps that name it will be skipped.")
            .setPositiveButton("Delete", (d, w) -> {
                List<Tvs.Tv> tvs = cfg.tvs();
                tvs.remove(Tvs.find(tvs, id));
                cfg.saveTvs(tvs);
                for (String k : new String[] {Config.PHILIPS_USER, Config.PHILIPS_PASS, Config.PHILIPS_CERT, Config.PHILIPS_MAC}) {
                    cfg.prefs.edit().remove(Tvs.key(id, k)).apply();
                }
                finish();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
