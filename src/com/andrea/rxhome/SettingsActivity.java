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
        field(col, Config.SERVER, "Jellyfin on the LAN (shows if the server is up)", InputType.TYPE_TEXT_VARIATION_URI, false);
        field(col, Config.SERVER_PUBLIC, "Jellyfin public address, tried when the LAN one doesn't answer", InputType.TYPE_TEXT_VARIATION_URI, false);

        section(col, "SwitchBot (server power button)");
        field(col, Config.SB_TOKEN, "Token: SwitchBot app → Profile → Preferences → tap App Version 10× → Developer Options", 0, true);
        field(col, Config.SB_SECRET, "Secret (same page)", 0, true);
        field(col, Config.SB_BOT, "Bot device ID", 0, false);
        TextView pick = Ui.button(this, "PICK THE BOT FROM MY SWITCHBOT DEVICES", false);
        pick.setOnClickListener(this::pickBot);
        col.addView(pick);

        section(col, "TV");
        field(col, Config.FIRETV, "FireStick IP address (ADB debugging on)", InputType.TYPE_CLASS_PHONE, false);
        field(col, Config.PHILIPS_IP, "Philips TV IP address (for its volume and power; the TV must be on to pair)",
            InputType.TYPE_CLASS_PHONE, false);
        field(col, Config.PHILIPS_MAC, "Philips TV MAC address (switches it on from standby: enable \"Switch on with Wi-Fi\" on the TV)",
            0, false);
        TextView pairTv = Ui.button(this,cfg.get(Config.PHILIPS_USER).isEmpty() ? "PAIR WITH THE TV" : "PAIR WITH THE TV AGAIN", false);
        pairTv.setOnClickListener(this::pairTv);
        col.addView(pairTv);

        section(col, "Tapo lights");
        field(col, Config.TAPO_EMAIL, "Tapo account email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, false);
        field(col, Config.TAPO_PASSWORD, "Tapo account password", 0, true);
        EditText lights = field(col, Config.LIGHTS, "Found automatically on the Wi-Fi. One per line, Name=IP: rename freely, the app keeps your names. Put # before a name to hide that light", InputType.TYPE_TEXT_FLAG_MULTI_LINE, false);
        lights.setMinLines(3);

        section(col, "Scenes");
        EditText scenes = field(col, Config.SCENES, "One per line: Name: step; step; …\n"
            + "Steps: light <name|all> on|off [0-100] [warm|cool]; tv on|off; tv app <package>; tv music; server press; wait <seconds>",
            InputType.TYPE_TEXT_FLAG_MULTI_LINE, false);
        scenes.setMinLines(5);

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
        String ip = fields.get(Config.PHILIPS_IP).getText().toString().trim();
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

    private void save() {
        try {
            Scenes.parse(fields.get(Config.SCENES).getText().toString());
        } catch (ParseException e) {
            error.setText("Scenes: " + e.getMessage() + ". Nothing was saved.");
            return;
        }
        for (Map.Entry<String, EditText> f : fields.entrySet()) cfg.put(f.getKey(), f.getValue().getText().toString().trim());
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }
}
