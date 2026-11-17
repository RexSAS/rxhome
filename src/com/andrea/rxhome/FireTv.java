package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
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
        return parseAwake(adb.shell("dumpsys power | grep mWakefulness=", 1500));
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

    // The TV itself (Fire OS never sends it CEC Standby and its remote's IR is closed to apps): the
    // Philips' own network API, when paired. Its MAC wakes it from standby, when it went off the network.
    private volatile Philips philips;
    private volatile String philipsMac;

    void setPhilips(Philips p, String mac) {
        philips = p;
        philipsMac = mac == null || mac.isEmpty() ? null : mac;
    }

    /** TV to standby (if paired and reachable), then the stick to sleep. */
    void sleep() throws IOException {
        Philips p = philips;
        if (p != null) {
            try {
                p.key("Standby");
            } catch (IOException e) {
                // Already off, or not on this network: the stick still goes to sleep.
                log("standby", e);
            }
        }
        key(SLEEP);
    }

    /**
     * TV on: the PowerOn key through its API (it keeps the network up in standby), Wake-on-LAN for deep
     * standby (needs "Switch on with Wi-Fi"), then the stick.
     */
    void wake() throws IOException {
        Philips p = philips;
        if (p != null) {
            try {
                p.key("PowerOn");
            } catch (IOException e) {
                log("power on", e);
            }
        }
        if (philipsMac != null) {
            try {
                Philips.wake(philipsMac);
            } catch (IOException | IllegalArgumentException e) {
                // No broadcast here (away from home).
            }
        }
        key(WAKEUP);
    }

    /**
     * The TV's power (stick = the FireStick's state, already known by the caller), from what the TV
     * announced over HDMI-CEC: its network API answers the same on and in standby. No CEC news yet: the
     * stick's state stands in. Stick unreachable: away from home, unknown.
     */
    State tvState(State stick) {
        if (stick == State.UNREACHABLE) return State.UNREACHABLE;
        try {
            Boolean on = parseTvPower(cecLog());
            if (on != null) return on ? State.ON : State.OFF;
        } catch (IOException e) {
            log("power state", e);
        }
        return stick;
    }

    /** The stick's HDMI-CEC log lines that carry the TV's power. */
    String cecLog() throws IOException {
        return adb.shell("dumpsys hdmi_control | grep -E 'Standby>|Report Power Status>'", 3000);
    }

    private static final java.util.regex.Pattern TV_POWER = java.util.regex.Pattern.compile(
        "\\[R\\][^\\n]*<(?:Standby> 0[0-9A-Fa-f]:36|Report Power Status> 0[0-9A-Fa-f]:90:0([0-3]))");

    /**
     * The TV's last word on its power, from messages it sent (logical address 0): &lt;Standby&gt; = off,
     * &lt;Report Power Status&gt; 00 on / 02 turning on / 01, 03 standby. Null if it said nothing.
     */
    static Boolean parseTvPower(String dump) {
        java.util.regex.Matcher m = TV_POWER.matcher(dump);
        Boolean on = null;
        while (m.find()) on = m.group(1) != null && (m.group(1).equals("0") || m.group(1).equals("2"));
        return on;
    }

    /** TV errors that don't stop the action go to the system log (Android: tag System.err). No secrets. */
    private static void log(String what, Exception e) {
        System.err.println("rxhome-tv: " + what + " failed: " + e);
    }

    /** The power button: by the TV's state, not the stick's. */
    void togglePower(State stick) throws IOException {
        if (tvState(stick) == State.ON) sleep();
        else wake();
    }

    /** A key on the TV itself: "VolumeUp", "VolumeDown", "Mute". */
    void tvKey(String key) throws IOException {
        Philips p = philips;
        if (p == null) throw new IOException("Pair the TV in Settings to control its volume");
        p.key(key);
    }

    void launch(String pkg) throws IOException {
        adb.shell("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1", 5000);
    }

    /** Plays a YouTube video on the TV; SmartTube picks up the link. */
    void play(String youtubeId) throws IOException {
        adb.shell("am start -a android.intent.action.VIEW -d https://www.youtube.com/watch?v=" + youtubeId, 5000);
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
