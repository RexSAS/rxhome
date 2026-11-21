package com.andrea.rxhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Every setting on one page. Secrets stay in app-private storage. */
public class SettingsActivity extends Activity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private Config cfg;
    private TextView error;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.window(this);
        cfg = new Config(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        col.setPadding(p, p, p, p);
        col.addView(Ui.text(this, "Settings", 26, Ui.TEXT, true));

        section(col, "Server");
        field(col, Config.SERVER_PUBLIC, "Jellyfin public address, tried when the LAN one doesn't answer", InputType.TYPE_TEXT_VARIATION_URI, false);

        section(col, "SwitchBot (server power button)");
        field(col, Config.SB_TOKEN, "Token: SwitchBot app → Profile → Preferences → tap App Version 10× → Developer Options", 0, true);
        field(col, Config.SB_SECRET, "Secret (same page)", 0, true);
        field(col, Config.SB_BOT, "Bot device ID", 0, false);
        TextView pick = Ui.button(this, "PICK THE BOT FROM MY SWITCHBOT DEVICES", false);
        pick.setOnClickListener(this::pickBot);
        col.addView(pick);

        section(col, "TVs");
        TextView found = Ui.text(this, cfg.foundLine(), 13, Ui.DIM, false);
        found.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        col.addView(found);
        col.addView(Ui.text(this, "Samsung, LG, Philips and FireSticks, in any home: add each once, with the TV on. "
            + "The app picks the one on the Wi-Fi you're on; tap a TV to use it now.", 13, Ui.DIM, false));
        tvList = new LinearLayout(this);
        tvList.setOrientation(LinearLayout.VERTICAL);
        col.addView(tvList);
        showTvs();
        TextView add = Ui.button(this, "ADD A TV", false);
        add.setOnClickListener(this::addTv);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, -2);
        addLp.topMargin = Ui.dp(this, 8);
        col.addView(add, addLp);

        section(col, "Jellyfin (RxFilms shortcuts)");
        col.addView(Ui.text(this, "For Continue watching / Next up in Apps: sign in once with Quick Connect.", 13, Ui.DIM, false));
        TextView jf = Ui.button(this, cfg.get(Config.JF_TOKEN).isEmpty() ? "SIGN IN TO JELLYFIN" : "SIGNED IN · SIGN IN AGAIN", false);
        jf.setOnClickListener(this::signInJellyfin);
        LinearLayout.LayoutParams jfLp = new LinearLayout.LayoutParams(-1, -2);
        jfLp.topMargin = Ui.dp(this, 8);
        col.addView(jf, jfLp);

        section(col, "Tapo lights");
        field(col, Config.TAPO_EMAIL, "Tapo account email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, false);
        field(col, Config.TAPO_PASSWORD, "Tapo account password", 0, true);
        EditText lights = field(col, Config.LIGHTS, "Found automatically on the Wi-Fi. One per line, Name=IP: rename freely, the app keeps your names. Put # before a name to hide that light", InputType.TYPE_TEXT_FLAG_MULTI_LINE, false);
        lights.setMinLines(3);

        section(col, "Scenes");
        scenesBox = new LinearLayout(this);
        scenesBox.setOrientation(LinearLayout.VERTICAL);
        Ui.animateLayout(scenesBox);
        col.addView(scenesBox);

        error = Ui.text(this, "", 14, Ui.RED, false);
        col.addView(error);
        TextView save = Ui.button(this, "SAVE", true);
        save.setOnClickListener(v -> save());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 16);
        col.addView(save, lp);

        Ui.cascade(col, 0); // cards rise in one after another
        ScrollView s = new ScrollView(this);
        s.addView(col);
        setContentView(s);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void section(LinearLayout col, String title) {
        TextView t = Ui.text(this, title.toUpperCase(), 13, Ui.RED, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 4));
        col.addView(t);
    }

    private EditText field(LinearLayout col, String key, String hint, int type, boolean secret) {
        TextView label = Ui.text(this, hint, 13, Ui.DIM, false);
        label.setPadding(0, Ui.dp(this, 8), 0, 0);
        col.addView(label);
        EditText e = new EditText(this);
        e.setId(View.generateViewId());
        e.setTextColor(Ui.TEXT);
        e.setTypeface(Ui.font(this, false));
        e.setText(cfg.get(key));
        e.setInputType(InputType.TYPE_CLASS_TEXT | type | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        if (type == InputType.TYPE_CLASS_PHONE) e.setInputType(InputType.TYPE_CLASS_PHONE);
        e.setContentDescription(hint);
        label.setLabelFor(e.getId());
        col.addView(e);
        fields.put(key, e);
        return e;
    }

    private LinearLayout tvList;

    private static String brandName(String brand) {
        switch (brand) {
            case "samsung": return "Samsung";
            case "lg": return "LG";
            case "philips": return "Philips";
            case "firetv": return "FireStick";
            default: return "FireStick";
        }
    }

    /** Saved TVs: tap one to use it now, or remove it. */
    private void showTvs() {
        tvList.removeAllViews();
        Tvs.Saved active = cfg.active();
        for (Tvs.Saved t : cfg.saved()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, 0);
            String label = brandName(t.brand) + " · " + t.name + (t.hasStick() && !t.brand.equals("none") ? " + FireStick" : "")
                + (active != null && active.uid.equals(t.uid) ? "  (in use)" : "");
            TextView name = Ui.text(this, label, 15, Ui.TEXT, false);
            name.setMinHeight(Ui.dp(this, 48));
            name.setGravity(android.view.Gravity.CENTER_VERTICAL);
            name.setOnClickListener(v -> {
                cfg.setActive(t.uid);
                showTvs();
            });
            name.setContentDescription("Use " + label);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView remove = Ui.button(this, "REMOVE", false);
            remove.setTextSize(12);
            remove.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Remove " + brandName(t.brand) + " · " + t.name + "?")
                .setMessage("You can add it again later (the TV will ask again).")
                .setPositiveButton("Remove", (d, w) -> {
                    java.util.List<Tvs.Saved> list = cfg.saved();
                    for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).uid.equals(t.uid)) list.remove(i);
                    cfg.save(list);
                    showTvs();
                })
                .setNegativeButton("Cancel", null)
                .show());
            row.addView(remove);
            tvList.addView(row);
        }
        if (cfg.saved().isEmpty()) tvList.addView(Ui.text(this, "No TVs yet.", 13, Ui.DIM, false));
    }

    private void toast(String m) {
        runOnUiThread(() -> { if (!isFinishing()) Toast.makeText(this, m, Toast.LENGTH_LONG).show(); });
    }

    /** Scans this Wi-Fi and lists what's there; tapping one pairs it. Nothing gets a prompt until then. */
    private void addTv(View v) {
        String me = Discovery.wifiIp();
        if (me == null) {
            toast("Connect to the Wi-Fi the TV is on first");
            return;
        }
        Toast.makeText(this, "Looking for TVs on this Wi-Fi…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            java.util.List<Discovery.TvFound> scan = Discovery.findTvs(me, cfg.adbKey());
            runOnUiThread(() -> pickTv(scan));
        });
    }

    private void pickTv(java.util.List<Discovery.TvFound> scan) {
        if (isFinishing()) return;
        if (scan.isEmpty()) {
            toast("No TV found on this Wi-Fi. Is it on, and on this network?");
            return;
        }
        java.util.Set<String> saved = new java.util.HashSet<>();
        for (Tvs.Saved t : cfg.saved()) {
            saved.add(t.brand + "|" + t.id);
            saved.add("firetv|" + t.stickId);
        }
        String[] items = new String[scan.size()];
        for (int i = 0; i < items.length; i++) {
            Discovery.TvFound f = scan.get(i);
            items[i] = brandName(f.brand) + " · " + f.name + " (" + f.ip + ")" + (saved.contains(f.brand + "|" + f.id) ? " · saved" : "");
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Which one?")
            .setItems(items, (d, which) -> pair(scan.get(which), scan))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void pair(Discovery.TvFound f, java.util.List<Discovery.TvFound> scan) {
        switch (f.brand) {
            case "samsung":
                toast("Look at the TV and choose Allow");
                io.execute(() -> {
                    try {
                        Samsung.Pairing p = Samsung.pair(f.ip);
                        Tvs.Saved t = tv(f);
                        t.auth = p.token;
                        t.pin = p.pin;
                        saveTv(t, scan, "Added " + f.name);
                    } catch (IOException e) {
                        toast(e.getMessage());
                    }
                });
                break;
            case "lg":
                toast("Look at the TV and choose Accept");
                io.execute(() -> {
                    try {
                        Lg.Pairing p = Lg.pair(f.ip);
                        Tvs.Saved t = tv(f);
                        t.auth = p.clientKey;
                        t.pin = p.pin;
                        String mac = new Lg(f.ip, p.clientKey, p.pin, null).readMac();
                        if (mac != null) t.mac = mac;
                        saveTv(t, scan, "Added. To switch it on from the phone, turn on \"Turn on via Wi-Fi\" "
                            + "(or \"Mobile TV On\") in the TV's settings");
                    } catch (IOException e) {
                        toast(e.getMessage());
                    }
                });
                break;
            case "philips":
                io.execute(() -> {
                    try {
                        Philips.Pairing p = Philips.pairRequest(f.ip);
                        runOnUiThread(() -> askPin(f, p, scan));
                    } catch (IOException e) {
                        toast("Can't reach the TV (is it on?): " + e.getMessage());
                    }
                });
                break;
            default: // firetv
                if (f.id != null) {
                    saveTv(Tvs.stick(f.ip, f.id), scan, "Added the FireStick");
                    return;
                }
                toast("On the TV: tick \"Always allow\", then Allow. If nothing shows, turn on ADB debugging "
                    + "in the FireStick's Settings → My Fire TV → Developer options");
                io.execute(() -> {
                    Adb a = new Adb(f.ip, 5555, cfg.adbKey());
                    try {
                        String serial = a.shell("getprop ro.serialno", 5000).trim();
                        saveTv(Tvs.stick(f.ip, serial.isEmpty() ? f.ip : serial), scan, "Added the FireStick");
                    } catch (IOException e) {
                        toast("The FireStick didn't allow it: " + e.getMessage());
                    } finally {
                        a.close();
                    }
                });
        }
    }

    private static Tvs.Saved tv(Discovery.TvFound f) {
        Tvs.Saved t = new Tvs.Saved();
        t.brand = f.brand;
        t.id = f.id == null ? "" : f.id;
        t.name = f.name;
        t.ip = f.ip;
        t.mac = f.mac == null ? "" : f.mac;
        return t;
    }

    private void saveTv(Tvs.Saved t, java.util.List<Discovery.TvFound> scan, String done) {
        java.util.List<Tvs.Saved> list = cfg.saved();
        Tvs.add(list, t, scan);
        cfg.save(list);
        cfg.foundTvs(scan); // makes it the TV in use if it's the one here
        toast(done);
        runOnUiThread(this::showTvs);
    }

    /** The Philips shows a PIN; typing it here gives rxhome a login for the TV's own API. */
    private void askPin(Discovery.TvFound f, Philips.Pairing p, java.util.List<Discovery.TvFound> scan) {
        if (isFinishing()) return;
        EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setHint("PIN");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Type the PIN shown on the TV")
            .setView(pin)
            .setPositiveButton("Pair", (d, w) -> io.execute(() -> {
                try {
                    Philips.pairGrant(f.ip, p, pin.getText().toString());
                    Tvs.Saved t = tv(f);
                    t.auth = p.deviceId;
                    t.pass = p.authKey;
                    t.pin = p.certPin;
                    String mac = null;
                    try {
                        mac = new Philips(f.ip, p.deviceId, p.authKey, p.certPin).mac();
                    } catch (IOException e) {
                        // Without the MAC the TV can still be switched off, just not woken from standby.
                    }
                    if (mac != null) t.mac = mac;
                    saveTv(t, scan, mac != null ? "Paired with the TV"
                        : "Paired with the TV (couldn't read its MAC: switching it on may not work)");
                } catch (IOException e) {
                    toast("Pairing failed: " + e.getMessage());
                }
            }))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private volatile boolean signingIn;

    /** Quick Connect: shows a code to approve in a signed-in Jellyfin app, waits up to 5 minutes. */
    private void signInJellyfin(View v) {
        String server = cfg.get(Config.SERVER), device = cfg.jellyfinDevice();
        io.execute(() -> {
            Jellyfin.Start start;
            try {
                start = Jellyfin.initiate(server, device);
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "Can't reach Jellyfin: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            signingIn = true;
            AlertDialog[] dialog = new AlertDialog[1];
            runOnUiThread(() -> {
                if (isFinishing()) return;
                TextView code = Ui.text(this, start.code, 40, Ui.RED, true);
                code.setGravity(android.view.Gravity.CENTER);
                code.setLetterSpacing(0.2f);
                code.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
                dialog[0] = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Approve this code in Jellyfin")
                    .setMessage("In RxFilms or the Jellyfin app: Settings → Quick Connect")
                    .setView(code)
                    .setNegativeButton("Cancel", (d, w) -> signingIn = false)
                    .setOnDismissListener(d -> signingIn = false)
                    .show();
            });
            for (int i = 0; i < 100 && signingIn; i++) {
                try {
                    Thread.sleep(3000);
                    Jellyfin.Login login = Jellyfin.check(server, device, start.secret);
                    if (login != null) {
                        cfg.put(Config.JF_TOKEN, login.token);
                        cfg.put(Config.JF_USER, login.userId);
                        runOnUiThread(() -> {
                            if (dialog[0] != null) dialog[0].dismiss();
                            Toast.makeText(this, "Signed in to Jellyfin", Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (IOException e) {
                    // Server blip: keep waiting.
                }
            }
        });
    }

    private void pickBot(View v) {
        String token = fields.get(Config.SB_TOKEN).getText().toString().trim();
        String secret = fields.get(Config.SB_SECRET).getText().toString().trim();
        if (token.isEmpty() || secret.isEmpty()) {
            Toast.makeText(this, "Enter the token and secret first", Toast.LENGTH_LONG).show();
            return;
        }
        io.execute(() -> {
            try {
                Map<String, String> devices = new SwitchBot(token, secret).devices();
                List<String> ids = new ArrayList<>(devices.keySet());
                List<String> names = new ArrayList<>();
                for (String id : ids) names.add(devices.get(id) + "  (" + id + ")");
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Which one presses the server's button?")
                        .setItems(names.toArray(new String[0]), (d, i) -> fields.get(Config.SB_BOT).setText(ids.get(i)))
                        .show();
                });
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "SwitchBot: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private LinearLayout scenesBox;

    @Override
    protected void onResume() {
        super.onResume();
        renderScenes(); // back from the scene editor: show what changed
    }

    /** A row per scene (tap to edit), then "Add new scene". */
    private void renderScenes() {
        scenesBox.removeAllViews();
        java.util.List<Scenes.Scene> list;
        try {
            list = Scenes.parse(cfg.get(Config.SCENES));
        } catch (ParseException e) {
            scenesBox.addView(Ui.text(this, "Scenes can't be read: " + e.getMessage(), 13, Ui.RED, false));
            return; // editing now would replace them all
        }
        for (int i = 0; i < list.size(); i++) {
            int index = i;
            Scenes.Scene sc = list.get(i);
            sceneRow(sc.name, Scenes.summary(sc), false, v -> openEditor(index));
        }
        sceneRow("+ Add new scene", "", true, v -> openEditor(-1));
    }

    private void sceneRow(String title, String sub, boolean add, View.OnClickListener open) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(Ui.button(this, "", add).getBackground());
        int p = Ui.dp(this, 14);
        row.setPadding(p, p, p, p);
        row.addView(Ui.text(this, title, 16, add ? 0xFFFFFFFF : Ui.TEXT, true));
        if (!sub.isEmpty()) row.addView(Ui.text(this, sub, 12, Ui.DIM, false));
        row.setClickable(true);
        row.setFocusable(true);
        Ui.press(row);
        row.setContentDescription(title + (sub.isEmpty() ? "" : ", " + sub));
        row.setOnClickListener(open);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 8);
        scenesBox.addView(row, lp);
    }

    private void openEditor(int index) {
        startActivity(new android.content.Intent(this, SceneEditorActivity.class).putExtra("index", index));
    }

    private void save() {
        for (Map.Entry<String, EditText> f : fields.entrySet()) cfg.put(f.getKey(), f.getValue().getText().toString().trim());
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }
}
