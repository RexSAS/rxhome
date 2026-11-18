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
        DEFAULTS.put(SCENES, Scenes.DEFAULT);
    }

    // One Tv per process, shared by the home and remote screens, so its ADB connections and the running
    // key helper survive moving between screens. Rebuilt when the active TV or its settings change.
    private static final java.util.Map<String, Tv> TVS_BY_UID = new java.util.HashMap<>();
    private static final java.util.Map<String, String> KEYS_BY_UID = new java.util.HashMap<>();

    static final String YOUTUBE_APP = "youtube_app_"; // + TV uid: "" = SmartTube, else the official YouTube app's package
    static final String TVS = "tvs", TV_ACTIVE = "tv_active", TVS_MIGRATED = "tvs_migrated";

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

    /** Every TV paired so far (the old single FireStick + Philips settings become the first, once). */
    synchronized java.util.List<Tvs.Saved> saved() {
        // The old app always had a FireStick (its default address if never scanned), so it always comes over;
        // the marker (not the list) says it's done, so removing every TV later doesn't bring it back.
        if (!prefs.contains(TVS_MIGRATED)) {
            java.util.List<Tvs.Saved> list = Tvs.parse(prefs.getString(TVS, "[]"));
            if (list.isEmpty()) {
                list.add(Tvs.migrate(prefs.getString(FIRETV, "192.168.1.49"), prefs.getString(PHILIPS_IP, "192.168.1.221"),
                    prefs.getString(PHILIPS_USER, ""), prefs.getString(PHILIPS_PASS, ""), prefs.getString(PHILIPS_CERT, ""),
                    prefs.getString(PHILIPS_MAC, "d4:0a:dc:d6:c7:44")));
            }
            SharedPreferences.Editor e = prefs.edit().putString(TVS, Tvs.format(list)).putString(TVS_MIGRATED, "1");
            for (String k : new String[] {FIRETV, PHILIPS_IP, PHILIPS_USER, PHILIPS_PASS, PHILIPS_CERT, PHILIPS_MAC}) e.remove(k);
            e.apply();
            return list;
        }
        return Tvs.parse(prefs.getString(TVS, "[]"));
    }

    synchronized void save(java.util.List<Tvs.Saved> list) {
        put(TVS, Tvs.format(list));
    }

    /** The TV in use: the one picked last, else the first saved; null if none. */
    synchronized Tvs.Saved active() {
        java.util.List<Tvs.Saved> list = saved();
        String uid = get(TV_ACTIVE);
        for (Tvs.Saved s : list) if (s.uid.equals(uid)) return s;
        return list.isEmpty() ? null : list.get(0);
    }

    void setActive(String uid) {
        put(TV_ACTIVE, uid);
    }

    /** The remote's style ("tv", "stick", "sky") for the TV in use, kept per TV. */
    synchronized void setInput(String mode) {
        Tvs.Saved a = active();
        if (a == null) return;
        java.util.List<Tvs.Saved> list = saved();
        for (Tvs.Saved s : list) if (s.uid.equals(a.uid)) s.input = mode;
        save(list);
    }

    /**
     * After a scan: saved TVs found here get their new IPs; if the active one isn't here but exactly one
     * saved TV is, that one becomes active. Returns the saved TVs found here.
     */
    synchronized java.util.List<Tvs.Saved> foundTvs(java.util.List<Discovery.TvFound> found) {
        java.util.List<Tvs.Saved> list = saved();
        java.util.List<Tvs.Saved> here = Tvs.match(list, found);
        save(list);
        Tvs.Saved a = active();
        boolean activeHere = false;
        for (Tvs.Saved s : here) activeHere |= a != null && s.uid.equals(a.uid);
        if (!activeHere && here.size() == 1) setActive(here.get(0).uid);
        return here;
    }

    /** The TV in use (Remote, Apps, scenes). */
    synchronized Tv tv() {
        Tvs.Saved a = active();
        return a == null ? new Tv(null, null, null, "") : tv(a);
    }

    /**
     * One Tv per saved TV, shared by every screen, so its connections (and the FireStick's key helper)
     * survive moving between screens. Rebuilt when that TV's settings change.
     */
    synchronized Tv tv(Tvs.Saved a) {
        // The remote's style isn't part of the key: changing it mustn't drop the stick's connections.
        String input = a.input;
        a.input = "";
        String key = Tvs.format(java.util.Collections.singletonList(a));
        a.input = input;
        Tv t = TVS_BY_UID.get(a.uid);
        if (t == null || !key.equals(KEYS_BY_UID.get(a.uid))) {
            if (t != null) t.close();
            FireTv stick = a.hasStick() ? stick(a.stickIp) : null;
            if (stick != null && !a.stickId.equals(a.stickIp)) stick.serial = a.stickId; // ip = no serial was readable
            if (stick != null) stick.youtube = get(YOUTUBE_APP + a.uid).isEmpty() ? null : get(YOUTUBE_APP + a.uid);
            t = new Tv(screen(a), stick, a.hasSky() ? new Sky(a.skyIp) : null, a.input);
            TVS_BY_UID.put(a.uid, t);
            KEYS_BY_UID.put(a.uid, key);
        }
        return t;
    }

    private static Screen screen(Tvs.Saved s) {
        switch (s.brand) {
            case "samsung": return new Samsung(s.ip, s.auth, s.pin, s.mac, s.id);
            case "samsung-legacy": return new SamsungLegacy(s.ip, s.id);
            case "lg": return new Lg(s.ip, s.auth, s.pin, s.mac);
            case "philips": return new Philips(s.ip, s.auth, s.pass, s.pin, s.mac);
            default: return null;
        }
    }

    private FireTv stick(String host) {
        KeyPair key = adbKey();
        Adb keysAdb = new Adb(host, 5555, key);
        keysAdb.authWaitMs = 1500; // only the main connection holds the line for the TV's prompt
        return new FireTv(new Adb(host, 5555, key), keysAdb, helperDex());
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

    /** Saves where the server was found (null = not found, keep the old one); true if it moved. */
    boolean found(Discovery.Found f) {
        if (f.server == null || f.server.equals(get(SERVER))) return false;
        put(SERVER, f.server);
        return true;
    }

    /** Devices that are on whenever home is reachable (not the server: it may be off), as "host:port". */
    java.util.List<String> homeDevices() {
        java.util.List<String> out = new java.util.ArrayList<>();
        Tvs.Saved a = active();
        if (a != null && a.hasStick()) out.add(a.stickIp + ":5555");
        if (a != null && a.hasSky()) out.add(a.skyIp + ":49160");
        if (a != null && !a.ip.isEmpty() && !a.brand.equals("none")) {
            int port = a.brand.equals("samsung") ? 8001 : a.brand.equals("samsung-legacy") ? 55000 : a.brand.equals("lg") ? 3001 : 1925;
            out.add(a.ip + ":" + port);
        }
        for (String ip : Scenes.lights(get(LIGHTS)).values()) out.add(ip + ":80");
        return out;
    }

    /** For Settings: what was found. */
    String foundLine() {
        return "Server found on this Wi-Fi at " + get(SERVER).replace("http://", "") + ". Found again automatically if it moves.";
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

    /** Null until a token and secret are set. */
    static final String SPOTIFY_ID = "spotify_client_id", SPOTIFY_REFRESH = "spotify_refresh",
        SPOTIFY_SPEAKERS = "spotify_speakers", // speaker names last seen, one per line, for the scene editor
        SPEAKER_NAMES = "speaker_names"; // your names for them: "Spotify name=Your name" lines (see Spotify.label)
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
