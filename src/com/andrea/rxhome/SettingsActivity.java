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
        col.addView(Ui.text(this, "Samsung, LG and Philips TVs, FireSticks and Sky boxes, in any home: add each once, with the "
            + "TV on. Tap a TV for its info: its name, and which FireStick or Sky box is plugged into it.", 13, Ui.DIM, false));
        tvList = new LinearLayout(this);
        tvList.setOrientation(LinearLayout.VERTICAL);
        col.addView(tvList);
        showTvs();
        TextView add = Ui.button(this, "ADD A TV, FIRESTICK OR SKY", false);
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

        section(col, "Spotify (move music between speakers)");
        col.addView(Ui.text(this, "Once: developer.spotify.com → Dashboard → Create app. Redirect URI: http://127.0.0.1:"
            + Config.SPOTIFY_PORT + "/callback, tick Web API. Copy its Client ID here, then Connect.", 13, Ui.DIM, false));
        field(col, Config.SPOTIFY_ID, "Client ID", 0, false);
        spotifyButton = Ui.button(this, cfg.get(Config.SPOTIFY_REFRESH).isEmpty() ? "CONNECT SPOTIFY" : "CONNECTED · CONNECT AGAIN", false);
        spotifyButton.setOnClickListener(this::connectSpotify);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(-1, -2);
        spLp.topMargin = Ui.dp(this, 8);
        col.addView(spotifyButton, spLp);

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

        section(col, "Scheduled scenes");
        timersBox = new LinearLayout(this);
        timersBox.setOrientation(LinearLayout.VERTICAL);
        Ui.animateLayout(timersBox);
        col.addView(timersBox);

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
            case "samsung":
            case "samsung-legacy": return "Samsung";
            case "lg": return "LG";
            case "philips": return "Philips";
            case "firetv": return "FireStick";
            case "sky": return "Sky";
            default: return "FireStick";
        }
    }

    /** Saved TVs, in home-screen order: tap one for its info, ▲ ▼ to move it. */
    private void showTvs() {
        tvList.removeAllViews();
        java.util.List<Tvs.Saved> list = cfg.saved();
        for (int i = 0; i < list.size(); i++) {
            Tvs.Saved t = list.get(i);
            int at = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, 0);
            String kind = t.brand.equals("none") ? (t.hasSky() ? "Sky" : "FireStick") : brandName(t.brand);
            String label = t.name + "  (" + kind + (t.hasStick() && !t.brand.equals("none") ? " + FireStick" : "")
                + (t.hasSky() && !t.brand.equals("none") ? " + Sky" : "") + ")";
            TextView name = Ui.text(this, label, 15, Ui.TEXT, false);
            name.setMinHeight(Ui.dp(this, 48));
            name.setGravity(android.view.Gravity.CENTER_VERTICAL);
            name.setContentDescription(label + ": open its info");
            name.setOnClickListener(v -> startActivity(new android.content.Intent(this, TvInfoActivity.class)
                .putExtra(TvInfoActivity.UID, t.uid)));
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(arrow("▲", "Move " + t.name + " up", at > 0, () -> move(at, -1)));
            row.addView(arrow("▼", "Move " + t.name + " down", at < list.size() - 1, () -> move(at, 1)));
            tvList.addView(row);
        }
        if (list.isEmpty()) tvList.addView(Ui.text(this, "No TVs yet.", 13, Ui.DIM, false));
    }

    private TextView arrow(String label, String description, boolean enabled, Runnable r) {
        TextView b = Ui.button(this, label, false);
        b.setContentDescription(description);
        b.setMinWidth(Ui.dp(this, 48));
        b.setMinHeight(Ui.dp(this, 48));
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.3f);
        b.setOnClickListener(v -> {
            Ui.haptic(v);
            r.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.dp(this, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private void move(int i, int delta) {
        java.util.List<Tvs.Saved> list = cfg.saved();
        Tvs.move(list, i, delta);
        cfg.save(list);
        showTvs();
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
            saved.add("sky|" + t.skyId);
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
        if (f.id == null && !f.brand.equals("firetv") && !f.brand.equals("sky")) { // it couldn't be told apart from other TVs later
            toast("Couldn't identify this TV: is it fully on? Try again in a minute");
            return;
        }
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
            case "samsung-legacy":
                toast("Look at the TV and choose Allow");
                io.execute(() -> {
                    try {
                        SamsungLegacy.pair(f.ip);
                        saveTv(tv(f), scan, "Added " + f.name + ". It switches on only with its own remote");
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
            case "sky":
                if (f.id == null) {
                    toast("Couldn't identify this Sky box: is it on? Try again in a minute");
                    return;
                }
                saveTv(Tvs.sky(f.ip, f.id), scan, "Added the Sky box. Bind it to its TV: tap the TV in this list");
                break;
            default: // firetv
                if (f.id != null) {
                    saveTv(Tvs.stick(f.ip, f.id), scan, "Added the FireStick. Bind it to its TV: tap the TV in this list");
                    return;
                }
                toast("On the TV: tick \"Always allow\", then Allow. If nothing shows, turn on ADB debugging "
                    + "in the FireStick's Settings → My Fire TV → Developer options");
                io.execute(() -> {
                    Adb a = new Adb(f.ip, 5555, cfg.adbKey());
                    try {
                        String serial = a.shell("getprop ro.serialno", 5000).trim();
                        saveTv(Tvs.stick(f.ip, serial.isEmpty() ? f.ip : serial), scan, "Added the FireStick. Bind it to its TV: tap the TV in this list");
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
        boolean added = Tvs.add(list, t);
        cfg.save(list);
        if (!added) done = "Already saved: updated";
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

    private TextView spotifyButton;
    private java.net.ServerSocket spotifyListener;

    /**
     * Spotify login: the browser shows Spotify's page, which sends the phone's browser back to
     * http://127.0.0.1:PORT/callback, answered here for up to 5 minutes. PKCE, so no app secret is needed.
     */
    private void connectSpotify(View v) {
        String id = fields.get(Config.SPOTIFY_ID).getText().toString().trim();
        if (id.isEmpty()) {
            Toast.makeText(this, "Paste your Spotify app's Client ID first", Toast.LENGTH_LONG).show();
            return;
        }
        cfg.put(Config.SPOTIFY_ID, id);
        String redirect = "http://127.0.0.1:" + Config.SPOTIFY_PORT + "/callback";
        String verifier = Spotify.verifier(), state = Spotify.verifier().substring(0, 16);
        java.net.ServerSocket ss;
        try {
            closeListener();
            ss = new java.net.ServerSocket(Config.SPOTIFY_PORT, 4, java.net.InetAddress.getByName("127.0.0.1"));
            ss.setSoTimeout(5 * 60_000);
            spotifyListener = ss;
        } catch (IOException e) {
            Toast.makeText(this, "Can't start the Spotify login: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(Spotify.authorizeUrl(id, redirect, Spotify.challenge(verifier), state))));
        new Thread(() -> {
            try (java.net.ServerSocket server = ss) {
                while (true) {
                    try (java.net.Socket s = server.accept()) {
                        String line = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), "UTF-8")).readLine();
                        String code = line == null ? null : Spotify.callbackCode(line, state);
                        boolean denied = line != null && line.startsWith("GET /callback") && code == null;
                        String page = code != null ? "rxhome is connected to Spotify."
                            : denied ? "Spotify login didn't finish. Try Connect again in rxhome." : "";
                        String html = "<html><body style='background:#0c0c0e;color:#ececf0;font:20px sans-serif;padding:32px'>"
                            + page + "<p><a style='color:#ff1e3c' href='intent:#Intent;component=com.andrea.rxhome/.MainActivity;end'>"
                            + "Back to rxhome</a></body></html>";
                        java.io.OutputStream o = s.getOutputStream();
                        o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n" + html)
                            .getBytes("UTF-8"));
                        o.flush();
                        if (code == null && !denied) continue; // e.g. the browser asking for a favicon
                        if (code == null) return;
                        Spotify.Tokens t = Spotify.login(id, code, redirect, verifier);
                        cfg.put(Config.SPOTIFY_REFRESH, t.refresh);
                        runOnUiThread(() -> {
                            spotifyButton.setText("CONNECTED · CONNECT AGAIN");
                            Toast.makeText(this, "Spotify connected", Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                }
            } catch (IOException e) {
                if (!ss.isClosed() || e instanceof java.net.SocketTimeoutException) {
                    runOnUiThread(() -> Toast.makeText(this, "Spotify login: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        }, "spotify-login").start();
    }

    private void closeListener() {
        try {
            if (spotifyListener != null) spotifyListener.close();
        } catch (IOException e) {
            // Already closed.
        }
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

    private LinearLayout scenesBox, timersBox;

    @Override
    protected void onResume() {
        super.onResume();
        renderScenes(); // back from the scene editor: show what changed
        renderTimers();
        showTvs(); // ...or from renaming / reordering the TVs
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

    /** A row per scheduled scene (tap to remove its timer). Set them by holding a scene on the home screen. */
    private void renderTimers() {
        timersBox.removeAllViews();
        Map<String, Long> due = SceneTimer.all(cfg);
        if (due.isEmpty()) {
            timersBox.addView(Ui.text(this, "None. Hold a scene on the home screen to run it at a set time.", 13, Ui.DIM, false));
            return;
        }
        for (Map.Entry<String, Long> e : due.entrySet()) {
            String name = e.getKey();
            row(timersBox, name, "Runs " + SceneTimer.when(this, e.getValue()) + " · tap to remove", false, v ->
                new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Remove the timer for " + name + "?")
                    .setPositiveButton("Remove", (d, w) -> {
                        SceneTimer.cancel(this, name);
                        renderTimers();
                    })
                    .setNegativeButton("Keep", null)
                    .show());
        }
    }

    private void sceneRow(String title, String sub, boolean add, View.OnClickListener open) {
        row(scenesBox, title, sub, add, open);
    }

    private void row(LinearLayout box, String title, String sub, boolean add, View.OnClickListener open) {
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
        box.addView(row, lp);
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
