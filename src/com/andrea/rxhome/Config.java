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

    // One FireTv per TV per process, shared by the home and remote screens, so its ADB connections and
    // the running key helper survive moving between screens.
    private static final Map<String, FireTv> tvCache = new java.util.HashMap<>();
    private static final Map<String, String> tvCacheHost = new java.util.HashMap<>();
    static final String YOUTUBE_APP = "youtube_app"; // per TV: "" = SmartTube, else the official YouTube app's package
    static final String TVS = "tvs", NO_STICK = "no-stick.invalid"; // never resolves: "unreachable", like a stick that's off

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

    /** The TVs (Settings → TVs); before there was a list, the FireStick + Philips as the first TV. */
    java.util.List<Tvs.Tv> tvs() {
        return Tvs.orLegacy(get(TVS), get(FIRETV), get(PHILIPS_IP));
    }

    void saveTvs(java.util.List<Tvs.Tv> tvs) {
        put(TVS, tvs.isEmpty() ? "-" : Tvs.format(tvs)); // "-": deliberately none (empty would bring back the old single TV)
    }

    /** A TV by id or name; null or unknown = the first one. Null only with no TVs at all. */
    Tvs.Tv tvInfo(String idOrName) {
        java.util.List<Tvs.Tv> l = tvs();
        Tvs.Tv t = Tvs.find(l, idOrName);
        return t != null ? t : l.isEmpty() ? null : l.get(0);
    }

    /** A TV device's own name, as last read by a scan; its address until then. */
    String deviceName(String ip) {
        String n = prefs.getString("devname_" + ip, "");
        return n.isEmpty() ? ip : n;
    }

    void nameDevice(String ip, String name) {
        if (name != null && !name.trim().isEmpty()) put("devname_" + ip, name.trim());
    }

    /** The first TV. */
    FireTv tv() {
        return tv(null);
    }

    /** A TV by id or name (null = the first): its stick for status, keys and apps, its Philips for power and volume. */
    FireTv tv(String idOrName) {
        Tvs.Tv t = tvInfo(idOrName);
        if (t == null) t = new Tvs.Tv("tv1", "Tv", "", "");
        String host = t.stick.isEmpty() ? NO_STICK : t.stick;
        synchronized (Config.class) {
            FireTv tv = tvCache.get(t.id);
            if (tv == null || !host.equals(tvCacheHost.get(t.id))) {
                if (tv != null) tv.close();
                KeyPair key = adbKey();
                Adb keysAdb = new Adb(host, 5555, key);
                keysAdb.authWaitMs = 1500; // only the main connection holds the line for the TV's prompt
                tv = new FireTv(new Adb(host, 5555, key), keysAdb, helperDex());
                tvCache.put(t.id, tv);
                tvCacheHost.put(t.id, host);
            }
            tv.hasStick = !t.stick.isEmpty();
            String yt = get(Tvs.key(t.id, YOUTUBE_APP));
            tv.youtube = yt.isEmpty() ? null : yt;
            tv.setPhilips(philips(t.id), get(Tvs.key(t.id, PHILIPS_MAC)));
            return tv;
        }
    }

    /** A step's TV by name (scenes' "@Name"); null if there's no such TV. */
    FireTv tvNamed(String name) {
        return Tvs.find(tvs(), name) == null ? null : tv(name);
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

    /**
     * Saves what a Wi-Fi scan found (null = not found, keep the old one); true if anything moved. With
     * several TVs there's no telling which moved where, so only a single TV follows its devices.
     */
    boolean found(Discovery.Found f) {
        boolean changed = false;
        java.util.List<Tvs.Tv> tvs = tvs();
        if (!get(TVS).isEmpty() && tvs.size() == 1) {
            Tvs.Tv t = tvs.get(0);
            String stick = f.stick != null && !t.stick.isEmpty() ? f.stick : t.stick;
            String ph = f.tv != null && !t.philips.isEmpty() ? f.tv : t.philips;
            if (!stick.equals(t.stick) || !ph.equals(t.philips)) {
                tvs.set(0, new Tvs.Tv(t.id, t.name, stick, ph));
                saveTvs(tvs);
                changed = true;
            }
        }
        if (!get(TVS).isEmpty()) {
            f.stick = null; // the TVs list is what counts now
            f.tv = null;
        }
        String[][] pairs = {{SERVER, f.server}, {FIRETV, f.stick}, {PHILIPS_IP, f.tv}};
        for (String[] p : pairs) {
            if (p[1] != null && !p[1].equals(get(p[0]))) {
                put(p[0], p[1]);
                changed = true;
            }
        }
        return changed;
    }

    /** Devices that are on whenever home is reachable (not the server: it may be off), as "host:port". */
    java.util.List<String> homeDevices() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Tvs.Tv t : tvs()) {
            if (!t.stick.isEmpty()) out.add(t.stick + ":5555");
            if (!t.philips.isEmpty()) out.add(t.philips + ":1925");
        }
        for (String ip : Scenes.lights(get(LIGHTS)).values()) out.add(ip + ":80");
        return out;
    }

    /** For Settings: where things were found. */
    String foundLine() {
        return "Found on this Wi-Fi: server " + get(SERVER).replace("http://", "") + ". Found again automatically if it moves.";
    }

    static final String JF_TOKEN = "jellyfin_token", JF_USER = "jellyfin_user", JF_DEVICE = "jellyfin_device";
    static final String BOOTS = "boot_times"; // last server boots, power press → Jellyfin answering (see Boot)

    /** Jellyfin for the RxFilms shortcuts; null until signed in (Quick Connect) in Settings. */
    Jellyfin jellyfin() {
        return get(JF_TOKEN).isEmpty() ? null : new Jellyfin(get(SERVER), get(JF_TOKEN), get(JF_USER), jellyfinDevice());
    }

    /** A stable id for this phone in Jellyfin's device list. */
    String jellyfinDevice() {
        String id = get(JF_DEVICE);
        if (id.isEmpty()) put(JF_DEVICE, id = java.util.UUID.randomUUID().toString());
        return id;
    }

    /** The first TV's own API (Philips, Samsung or LG); null until paired. */
    TvApi philips() {
        Tvs.Tv t = tvInfo(null);
        return t == null ? null : philips(t.id);
    }

    static final String LG_KEY = "lg_key", SAMSUNG_TOKEN = "samsung_token", TV_CERT = "tv_cert"; // per TV (Tvs.key)

    /** A TV's own API by its brand (address from the TV list, pairing per TV); null until paired. */
    TvApi philips(String id) {
        Tvs.Tv t = tvInfo(id);
        if (t == null || t.philips.isEmpty()) return null;
        String tid = t.id;
        switch (t.kind) {
            case Tvs.LG: {
                String key = get(Tvs.key(tid, LG_KEY));
                return key.isEmpty() ? null : new Lg(t.philips, key, get(Tvs.key(tid, TV_CERT)));
            }
            case Tvs.SAMSUNG: {
                // An empty token is a valid pairing on older models, so "paired" is the pinned certificate.
                String pin = get(Tvs.key(tid, TV_CERT));
                return pin.isEmpty() ? null : new Samsung(t.philips, get(Tvs.key(tid, SAMSUNG_TOKEN)), pin.equals("-") ? "" : pin); // "-": paired, no certificate seen
            }
            default: {
                String user = get(Tvs.key(tid, PHILIPS_USER));
                return user.isEmpty() ? null
                    : new Philips(t.philips, user, get(Tvs.key(tid, PHILIPS_PASS)), get(Tvs.key(tid, PHILIPS_CERT)));
            }
        }
    }

    /** Null until a token and secret are set. */
    static final String SPOTIFY_ID = "spotify_client_id", SPOTIFY_REFRESH = "spotify_refresh",
        SPOTIFY_SPEAKERS = "spotify_speakers", // speaker names last seen, one per line, for the scene editor
        SPEAKER_NAMES = "speaker_names", // your names for them: "Spotify name=Your name" lines (see Spotify.label)
        SCENES_ACTIVE = "scenes_active"; // scenes switched on (tap again = off), one name per line
    static final int SPOTIFY_PORT = 43821; // login reply: http://127.0.0.1:43821/callback, as registered with Spotify
    private static Spotify spotify;
    private static String spotifyKey;

    /** One Spotify per process (it keeps the access token and saves renewed logins); null if not connected. */
    Spotify spotify() {
        String id = get(SPOTIFY_ID), refresh = get(SPOTIFY_REFRESH);
        if (id.isEmpty() || refresh.isEmpty()) return null;
        synchronized (Config.class) {
            if (spotify == null || !(id + refresh).equals(spotifyKey)) {
                spotify = new Spotify(id, refresh, r -> {
                    put(SPOTIFY_REFRESH, r);
                    synchronized (Config.class) { spotifyKey = id + r; }
                });
                spotifyKey = id + refresh;
            }
            return spotify;
        }
    }

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
    KeyPair adbKey() {
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
