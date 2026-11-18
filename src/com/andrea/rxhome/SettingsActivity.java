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

        section(col, "TV");
        TextView found = Ui.text(this, cfg.foundLine(), 13, Ui.DIM, false);
        found.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        col.addView(found);
        col.addView(Ui.text(this, "Philips TV: pair once (with the TV on) for its volume, mute and power.", 13, Ui.DIM, false));
        TextView pairTv = Ui.button(this,cfg.get(Config.PHILIPS_USER).isEmpty() ? "PAIR WITH THE TV" : "PAIR WITH THE TV AGAIN", false);
        pairTv.setOnClickListener(this::pairTv);
        col.addView(pairTv);

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

    /** The TV shows a PIN; typing it here gives rxhome a login for the TV's own API. */
    private void pairTv(View v) {
        io.execute(() -> {
            try {
                if (!Discovery.isPhilips(cfg.get(Config.PHILIPS_IP))) { // moved, or never found: look for it
                    String me = Discovery.wifiIp();
                    if (me != null) cfg.found(Discovery.find(me, cfg.adbKey()));
                }
                String ip = cfg.get(Config.PHILIPS_IP);
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
                    cfg.put(Config.PHILIPS_IP, ip);
                    cfg.put(Config.PHILIPS_USER, p.deviceId);
                    cfg.put(Config.PHILIPS_PASS, p.authKey);
                    cfg.put(Config.PHILIPS_CERT, p.certPin);
                    if (mac != null) cfg.put(Config.PHILIPS_MAC, mac);
                    String done = mac != null ? "Paired with the TV"
                        : "Paired with the TV (couldn't read its MAC: switching it on may not work)";
                    runOnUiThread(() -> Toast.makeText(this, done, Toast.LENGTH_LONG).show());
                } catch (IOException e) {
                    runOnUiThread(() -> Toast.makeText(this, "Pairing failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
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
