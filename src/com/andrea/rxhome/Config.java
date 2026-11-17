package com.andrea.rxhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

/** Settings (app-private SharedPreferences) and the device clients built from them. */
final class Config {

    static final String SERVER = "server", SERVER_PUBLIC = "server_public", FIRETV = "firetv", SB_TOKEN = "sb_token", SB_SECRET = "sb_secret",
        SB_BOT = "sb_bot", TAPO_EMAIL = "tapo_email", TAPO_PASSWORD = "tapo_password", LIGHTS = "lights",
        SCENES = "scenes", PHILIPS_IP = "philips_ip", PHILIPS_USER = "philips_user",
        PHILIPS_PASS = "philips_pass", PHILIPS_CERT = "philips_cert", PHILIPS_MAC = "philips_mac";

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put(SERVER, "http://192.168.1.166:8096");
        DEFAULTS.put(SERVER_PUBLIC, "https://rxfilms.rxsas.it");
        DEFAULTS.put(FIRETV, "192.168.1.49");
        DEFAULTS.put(PHILIPS_IP, "192.168.1.221");
        DEFAULTS.put(PHILIPS_MAC, "d4:0a:dc:d6:c7:44"); // seen on the LAN 2026-11-17; pairing overwrites it if the TV reports one
        DEFAULTS.put(SCENES, Scenes.DEFAULT);
    }

    // One FireTv per process, shared by the home and remote screens, so its ADB connections and the
    // running key helper survive moving between screens.
    private static FireTv tv;
    private static String tvHost;

    final SharedPreferences prefs;
    private final Context ctx;

    Config(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        prefs = this.ctx.getSharedPreferences("rxhome", Context.MODE_PRIVATE);
    }

    String get(String key) {
        String d = DEFAULTS.get(key);
        return prefs.getString(key, d == null ? "" : d).trim();
    }

    void put(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    synchronized FireTv tv() {
        String host = get(FIRETV);
        if (tv == null || !host.equals(tvHost)) {
            if (tv != null) tv.close();
            KeyPair key = adbKey();
            Adb keysAdb = new Adb(host, 5555, key);
            keysAdb.authWaitMs = 1500; // only the main connection holds the line for the TV's prompt
            tv = new FireTv(new Adb(host, 5555, key), keysAdb, helperDex());
            tvHost = host;
        }
        tv.setPhilips(philips(), get(PHILIPS_MAC));
        return tv;
    }

    /** The key helper built by keys.sh and packed into assets by build.sh; null if missing. */
    private byte[] helperDex() {
        try (java.io.InputStream in = ctx.getAssets().open("rxhome-keys.dex")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** The Philips TV's own API; null until paired in Settings. */
    Philips philips() {
        return get(PHILIPS_USER).isEmpty() ? null
            : new Philips(get(PHILIPS_IP), get(PHILIPS_USER), get(PHILIPS_PASS), get(PHILIPS_CERT));
    }

    /** Null until a token and secret are set. */
    SwitchBot switchBot() {
        return get(SB_TOKEN).isEmpty() || get(SB_SECRET).isEmpty() ? null : new SwitchBot(get(SB_TOKEN), get(SB_SECRET));
    }

    Map<String, Tapo> lights() {
        Map<String, Tapo> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : Scenes.lights(get(LIGHTS)).entrySet()) {
            out.put(e.getKey(), new Tapo(e.getValue(), get(TAPO_EMAIL), get(TAPO_PASSWORD)));
        }
        return out;
    }

    /** The phone's ADB key, made once and kept; the TV asks to accept it on first connect. */
    private KeyPair adbKey() {
        String priv = prefs.getString("adb_priv", null), pub = prefs.getString("adb_pub", null);
        if (priv != null && pub != null) {
            try {
                return Adb.load(Base64.decode(priv, Base64.NO_WRAP), Base64.decode(pub, Base64.NO_WRAP));
            } catch (Exception e) {
                // Corrupt: make a new one below.
            }
        }
        KeyPair k = Adb.generateKey();
        prefs.edit()
            .putString("adb_priv", Base64.encodeToString(k.getPrivate().getEncoded(), Base64.NO_WRAP))
            .putString("adb_pub", Base64.encodeToString(k.getPublic().getEncoded(), Base64.NO_WRAP))
            .apply();
        return k;
    }
}
