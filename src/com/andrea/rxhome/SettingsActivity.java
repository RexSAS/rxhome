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

        TextView found = Ui.text(this, cfg.foundLine(), 13, Ui.DIM, false);
        found.setPadding(0, Ui.dp(this, 8), 0, 0);
        col.addView(found);

        section(col, "TVs");
        col.addView(Ui.text(this, "Each TV groups a stick (status, remote, apps) and a Philips (power, volume).", 13, Ui.DIM, false));
        tvsBox = new LinearLayout(this);
        tvsBox.setOrientation(LinearLayout.VERTICAL);
        tvsBox.setPadding(0, Ui.dp(this, 8), 0, 0);
        Ui.animateLayout(tvsBox);
        col.addView(tvsBox);

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

    private LinearLayout scenesBox;

    @Override
    protected void onResume() {
        super.onResume();
        renderScenes(); // back from the scene editor: show what changed
        renderTvs();
    }

    private LinearLayout tvsBox;

    /** A row per TV (tap to edit), then "Add a TV". */
    private void renderTvs() {
        tvsBox.removeAllViews();
        for (Tvs.Tv t : cfg.tvs()) {
            row(tvsBox, t.name, Tvs.describe(t, cfg.philips(t.id) != null, cfg::deviceName), false,
                v -> startActivity(new android.content.Intent(this, TvEditorActivity.class).putExtra("tv", t.id)));
        }
        row(tvsBox, "+ Add a TV", "", true, v -> startActivity(new android.content.Intent(this, TvEditorActivity.class)));
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
            row(scenesBox, sc.name, Scenes.summary(sc), false, v -> openEditor(index));
        }
        row(scenesBox, "+ Add new scene", "", true, v -> openEditor(-1));
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
