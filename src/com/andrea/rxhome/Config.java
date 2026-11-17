package com.andrea.rxhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.Map;

/** Settings (app-private SharedPreferences) and the device clients built from them. */
final class Config {

    static final String SERVER = "server", FIRETV = "firetv", SB_TOKEN = "sb_token", SB_SECRET = "sb_secret",
        SB_BOT = "sb_bot", TAPO_EMAIL = "tapo_email", TAPO_PASSWORD = "tapo_password", LIGHTS = "lights",
        SCENES = "scenes";

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put(SERVER, "http://192.168.1.166:8096");
        DEFAULTS.put(FIRETV, "192.168.1.49");
        DEFAULTS.put(SCENES, Scenes.DEFAULT);
    }

    // One ADB connection per process, shared by the home and remote screens.
    private static Adb adb;
    private static String adbHost;

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
        if (adb == null || !host.equals(adbHost)) {
            if (adb != null) adb.close();
            adb = new Adb(host, 5555, adbKey());
            adbHost = host;
        }
        return new FireTv(adb);
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
