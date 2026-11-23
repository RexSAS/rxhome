package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** The FireStick over ADB: power, keys, apps, music. */
class FireTv {

    // Fire OS 8 ignores these keys unless they come from the d-pad source.
    static final int SLEEP = 223, WAKEUP = 224;
    static final String CLAUDE_FM_FALLBACK = "tRsQsTMvPNg";

    private static final String HELPER_MAIN = "com.andrea.rxhome.tv.Keys";
    private static final long HELPER_RETRY_MS = 30000;

    private final Adb adb;
    // Fast keys: a helper (tv/.../Keys.java) kept running on the stick over its own connection.
    private final Adb keysAdb;
    private final byte[] helperDex;
    private Adb.Stream keys;
    private long helperFailedAt;

    FireTv(Adb adb) {
        this(adb, null, null);
    }

    FireTv(Adb adb, Adb keysAdb, byte[] helperDex) {
        this.adb = adb;
        this.keysAdb = keysAdb;
        this.helperDex = helperDex;
    }

    void close() {
        keys = null;
        if (keysAdb != null) keysAdb.close();
        adb.close();
    }

    /** Runs when the TV starts showing "Allow USB debugging?" for this phone. */
    void onKeyPrompt(Runnable r) {
        adb.onKeyPrompt = r;
    }

    State state() {
        try {
            return checkedState();
        } catch (IOException e) {
            return State.UNREACHABLE;
        }
    }

    /** Like state(), but says why: Adb.AdbAuthException means the TV hasn't accepted our key yet. */
    State checkedState() throws IOException {
        return parseState(adb.shell("getprop ro.serialno; dumpsys power | grep mWakefulness=", 1500), serial);
    }

    /** The saved serial ("" = not known yet): another home's FireStick at the same IP isn't used. */
    volatile String serial = "";

    static State parseState(String dump, String serial) throws IOException {
        String first = dump.trim().split("\\s+", 2)[0];
        if (!serial.isEmpty() && !first.equals(serial)) throw new IOException("Another FireStick answers here, not this TV's");
        return parseAwake(dump);
    }

    /** One d-pad key: through the helper when it runs (one round trip), else `input` (~1 s on Fire OS). */
    synchronized void key(int code) throws IOException {
        if (!helper(Integer.toString(code))) adb.shell("input dpad keyevent " + code, 3000);
    }

    /** Start holding a key; without the helper it's a plain press. */
    synchronized void keyDown(int code) throws IOException {
        if (!helper("d " + code)) adb.shell("input dpad keyevent " + code, 3000);
    }

    /** n-th repeat while held. Helper only: slow `input` presses would pile up behind the finger. */
    synchronized void keyRepeat(int code, int n) {
        helper("r " + code + " " + n);
    }

    synchronized void keyUp(int code) {
        helper("u " + code);
    }

    /** Sends one helper command; false if the helper isn't available (caller falls back or skips). */
    private boolean helper(String command) {
        if (helperDex == null || keysAdb == null) return false;
        try {
            if (keys == null && System.currentTimeMillis() - helperFailedAt > HELPER_RETRY_MS) startHelper();
            if (keys != null) {
                keys.writeLine(command, 2000);
                if ("ok".equals(keys.readLine(2000))) return true;
            }
        } catch (IOException | RuntimeException e) {
            keys = null;
            helperFailedAt = System.currentTimeMillis();
            keysAdb.close();
        }
        return false;
    }

    /** Pushes the helper once (named by content, so a new build replaces it) and starts it. */
    private void startHelper() throws IOException {
        String path = "/data/local/tmp/rxhome-keys-" + shortHash(helperDex) + ".dex";
        if (!adb.shell("[ -f " + path + " ] && echo y", 3000).trim().equals("y")) {
            String b64 = java.util.Base64.getEncoder().encodeToString(helperDex);
            adb.shell("rm -f /data/local/tmp/rxhome-keys-*", 3000);
            // ADB messages carry at most 4 KB, so the file goes over in chunks.
            for (int i = 0; i < b64.length(); i += 3000) {
                adb.shell("echo -n '" + b64.substring(i, Math.min(b64.length(), i + 3000)) + "' >> " + path + ".b64", 3000);
            }
            adb.shell("base64 -d " + path + ".b64 > " + path + " && rm " + path + ".b64", 3000);
        }
        Adb.Stream s = keysAdb.open("exec:CLASSPATH=" + path + " app_process / " + HELPER_MAIN, 5000);
        if (!"ready".equals(s.readLine(5000))) throw new IOException("Key helper didn't start");
        keys = s;
    }

    private static String shortHash(byte[] b) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 6; i++) s.append(String.format("%02x", d[i]));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The stick to sleep. Fire OS never sends the TV CEC Standby: the TV's own API does that (see Tv). */
    void sleep() throws IOException {
        key(SLEEP);
    }

    void wake() throws IOException {
        key(WAKEUP);
    }

    /** What the TV last announced over HDMI-CEC about its power; null if nothing yet or no answer. */
    Boolean cecTvOn() {
        try {
            return parseTvPower(cecLog());
        } catch (IOException e) {
            log("power state", e);
            return null;
        }
    }

    /** The stick's HDMI-CEC log lines that carry the TV's power. */
    String cecLog() throws IOException {
        return adb.shell("dumpsys hdmi_control | grep -E 'Standby>|Report Power Status>|Set Stream Path>|Routing Change>|Request Active Source>'", 3000);
    }

    /** A message the TV sent (logical address 0): its opcode, and the first parameter if any. */
    private static final java.util.regex.Pattern TV_SAID = java.util.regex.Pattern.compile(
        "\\[R\\][^\\n]*<[^>\\n]*> 0[0-9A-Fa-f]:([0-9A-Fa-f]{2})(?::([0-9A-Fa-f]{2}))?");

    /**
     * The TV's last word on its power, from messages it sent: &lt;Standby&gt; = off, &lt;Report Power Status&gt;
     * 00 on / 02 turning on / 01, 03 standby; and "show this input" (Routing Change 80, Request Active Source
     * 85, Set Stream Path 86), which a TV switched on with its own remote sends instead of a power report and
     * never sends in standby. Null if it said nothing about it.
     */
    static Boolean parseTvPower(String dump) {
        java.util.regex.Matcher m = TV_SAID.matcher(dump);
        Boolean on = null;
        while (m.find()) {
            String op = m.group(1).toUpperCase(), arg = m.group(2);
            if (op.equals("36")) on = false;
            else if (op.equals("90") && arg != null) on = arg.equals("00") || arg.equals("02");
            else if (op.equals("80") || op.equals("85") || op.equals("86")) on = true;
        }
        return on;
    }

    static final String SIGNAL = "com.andrea.rxhome.SCENE", LAUNCHER = "com.andrea.tvlauncher";

    /**
     * Tells the launcher on the TV what rxhome is doing (so it can fade): event "start" / "swap" / "off" (a
     * scene, sent right before it first changes the picture), "tv_off" (about to switch the TV off: fade now,
     * fade_ms long); from = a swap's previous scene.
     */
    void signal(String event, String scene, String from) throws IOException {
        adb.shell(signalCommand(event, scene, from, SceneRunner.FADE_MS), 3000);
    }

    /** "ready": the scene's last step has run. id names it in the launcher's log (awaitReveal). */
    void ready(String scene, String id) throws IOException {
        adb.shell(signalCommand("ready", scene, null, SceneRunner.FADE_MS) + " --es id " + quote(id), 3000);
    }

    private static final java.util.regex.Pattern REVEAL = java.util.regex.Pattern.compile("reveal (\\S+) (\\d+)");

    /**
     * Waits up to maxMs for the launcher to start fading the picture in after "ready" `id` (it logs
     * "reveal <id> <ms>"); returns how long that fade lasts, or -1.
     */
    int awaitReveal(String id, int maxMs) {
        long end = System.currentTimeMillis() + maxMs;
        try {
            while (System.currentTimeMillis() < end) {
                int ms = parseReveal(adb.shell("logcat -d -s HomeService:I | grep 'reveal " + id + " '", 3000), id);
                if (ms >= 0) return ms;
                Thread.sleep(250);
            }
        } catch (IOException | RuntimeException | InterruptedException e) {
            // No launcher log to follow.
        }
        return -1;
    }

    static int parseReveal(String log, String id) {
        java.util.regex.Matcher m = REVEAL.matcher(log);
        while (m.find()) if (m.group(1).equals(id)) return Integer.parseInt(m.group(2));
        return -1;
    }

    static String signalCommand(String event, String scene, String from, int fadeMs) {
        return "am broadcast -a " + SIGNAL + " -p " + LAUNCHER + " --es event " + quote(event) + " --es scene " + quote(scene)
            + " --ei fade_ms " + fadeMs + (from == null ? "" : " --es from " + quote(from));
    }

    /** For the TV's shell: one word, whatever it holds. */
    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** TV errors that don't stop the action go to the system log (Android: tag System.err). No secrets. */
    private static void log(String what, Exception e) {
        System.err.println("rxhome-tv: " + what + " failed: " + e);
    }

    static final int DEL = 67, ENTER = 66;

    /** Types into whatever field is selected on the TV (plain characters only: see textCommand). */
    void type(String text) throws IOException {
        String cmd = textCommand(text);
        if (cmd != null) adb.shell(cmd, 5000);
    }

    /**
     * The TV's "input text" for this text, or null if nothing's left to type: it only types plain ASCII, so
     * accents are dropped (è → e) and the rest skipped; a space is its "%s"; quoted for the TV's shell.
     */
    static String textCommand(String text) {
        String plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder s = new StringBuilder();
        for (char c : plain.toCharArray()) {
            if (c == ' ') s.append("%s");
            else if (c > ' ' && c < 127) s.append(c);
        }
        if (s.length() == 0) return null;
        return "input text '" + s.toString().replace("'", "'\\''") + "'";
    }

    void launch(String pkg) throws IOException {
        pkg = appFor(pkg, youtube);
        safe(pkg); // scene text reaches here ("tv app <package>")
        adb.shell("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1", 5000);
    }

    /** The YouTube app chosen for this TV (Apps page): null = SmartTube, else the official app's package. */
    volatile String youtube;

    /** SmartTube's place taken by the official YouTube app when that's this TV's choice. */
    static String appFor(String pkg, String youtube) {
        return pkg.equals(SMARTTUBE) && youtube != null ? youtube : pkg;
    }

    /** Every package on the TV, built-in ones too (Fire TV's YouTube is one). */
    String[] packages() throws IOException {
        return adb.shell("pm list packages", 5000).replace("package:", "").trim().split("\\s+");
    }

    /** The stick's processor (picks the right APK) and whether it's a Fire TV (Amazon's store). */
    String abi() throws IOException {
        return adb.shell("getprop ro.product.cpu.abi", 3000).trim();
    }

    boolean amazon() throws IOException {
        return adb.shell("getprop ro.product.manufacturer", 3000).trim().equals("Amazon");
    }

    /** Streams an APK into the TV's installer (no copy on the TV first); returns once it's installed. */
    void install(java.io.File apk) throws IOException {
        long size = apk.length();
        Adb.Stream s = adb.open("exec:cmd package install -S " + size, 10000);
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = new java.io.FileInputStream(apk)) {
            for (int n; (n = in.read(buf)) > 0; ) s.write(buf, n, 30000);
        }
        String reply = s.readAll(120000);
        if (!TvInstall.installed(reply)) throw new IOException("The TV didn't install it: " + reply.trim());
    }

    /** The app's page in the TV's own store, for the user to confirm there. */
    void openStore(String pkg) throws IOException {
        safe(pkg);
        adb.shell(TvInstall.storeCommand(pkg, amazon()), 5000);
    }

    static final String SMARTTUBE = "org.smarttube.stable", SPOTIFY = "com.spotify.tv.android",
        RXFILMS = "org.rxfilms.tv/org.jellyfin.androidtv.ui.startup.StartupActivity";

    static final String LIKED = "spotify:collection:tracks", DJ_X = "spotify:playlist:37i9dQZF1EYkqdzj48dyYq";

    /** The Claude FM live stream on the TV (looks up its current video id first). */
    void claudeFm() throws IOException {
        play(claudeFmId());
    }

    /** Plays a YouTube video on the TV, in SmartTube or the official app (this TV's choice). */
    void play(String youtubeId) throws IOException {
        view("https://www.youtube.com/watch?v=" + youtubeId, appFor(SMARTTUBE, youtube));
    }

    /** Opens a link in a TV app, e.g. a Spotify playlist. */
    void view(String uri, String pkg) throws IOException {
        adb.shell(viewCommand(uri, pkg), 5000);
    }

    /** Opens a Jellyfin item in RxFilms (its details page, one OK from playing). */
    void openItem(String component, String itemId) throws IOException {
        adb.shell(itemCommand(component, itemId), 5000);
    }

    // Only plain URL/package characters reach the TV's shell: no quotes, spaces, ; | $ ` & etc.
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9:/._?=%+-]+");

    static String viewCommand(String uri, String pkg) {
        safe(uri);
        safe(pkg);
        return "am start -a android.intent.action.VIEW -d '" + uri + "' -p " + pkg;
    }

    static String itemCommand(String component, String itemId) {
        safe(component);
        if (!itemId.matches("[0-9a-fA-F-]{32,36}")) throw new IllegalArgumentException("Not a Jellyfin item id: " + itemId);
        return "am start -n " + component + " --es ItemId " + itemId;
    }

    private static void safe(String s) {
        if (!SAFE.matcher(s).matches()) throw new IllegalArgumentException("Not safe for the TV's shell: " + s);
    }

    /** Installed third-party apps, one package per entry. */
    String[] apps() throws IOException {
        return adb.shell("pm list packages -3", 5000).replace("package:", "").trim().split("\\s+");
    }

    static State parseAwake(String dump) {
        return dump.contains("mWakefulness=Awake") ? State.ON : State.OFF;
    }

    /** Current Claude FM live stream id (same lookup as rxhome-tv's ClaudeFm), or the last known one. */
    static String claudeFmId() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            // "EgJAAQ==" is YouTube's own "Live" search filter.
            String body = "{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.20250101.00.00\"}},"
                + "\"query\":\"Claude FM\",\"params\":\"EgJAAQ%3D%3D\"}";
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() == 200) {
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[16384];
                    for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                    String id = claudeLive(new JSONObject(buf.toString("UTF-8")));
                    if (id != null) return id;
                }
            }
        } catch (Exception ignored) {
            // Fall back to the last known id.
        }
        return CLAUDE_FM_FALLBACK;
    }

    private static String claudeLive(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject v = o.optJSONObject("videoRenderer");
            if (v != null && "Claude".equals(runs(v.optJSONObject("ownerText"))) && v.toString().contains("LIVE")) {
                return v.optString("videoId", null);
            }
            for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
                String found = claudeLive(o.opt(keys.next()));
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String found = claudeLive(a.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String runs(JSONObject text) {
        if (text == null) return null;
        JSONArray r = text.optJSONArray("runs");
        StringBuilder s = new StringBuilder();
        for (int i = 0; r != null && i < r.length(); i++) s.append(r.optJSONObject(i).optString("text"));
        return s.toString();
    }
}
