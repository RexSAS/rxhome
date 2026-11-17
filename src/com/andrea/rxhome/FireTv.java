package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The FireStick over ADB, plus TV power through the SwitchBot Hub Mini's IR: Fire OS never sends
 * CEC Standby, so the TV itself is switched by infrared (see the design doc).
 */
class FireTv {

    // Fire OS 8 ignores these keys unless they come from the d-pad source.
    static final int SLEEP = 223, WAKEUP = 224;
    static final String CLAUDE_FM_FALLBACK = "tRsQsTMvPNg";

    private static final Pattern TV_POWER = Pattern.compile("\\[R\\][^\\n]*<Report Power Status> 0[0-9a-f]:90:0([0-3])");

    private final Adb adb;
    private final SwitchBot sb;
    private final String irTvId;

    FireTv(Adb adb, SwitchBot sb, String irTvId) {
        this.adb = adb;
        this.sb = sb;
        this.irTvId = irTvId;
    }

    State state() {
        try {
            return parseAwake(adb.shell("dumpsys power | grep mWakefulness=", 1500));
        } catch (IOException e) {
            return State.UNREACHABLE;
        }
    }

    void key(int code) throws IOException {
        adb.shell("input dpad keyevent " + code, 3000);
    }

    /** TV off by IR (if set up), then the stick to sleep. */
    void sleep() throws IOException {
        IOException irError = null;
        if (sb != null && irTvId != null) {
            try {
                sb.command(irTvId, "turnOff");
            } catch (IOException e) {
                irError = e;
            }
        }
        key(SLEEP);
        if (irError != null) throw irError;
    }

    /** Wakes the stick; if the TV hasn't reported "on" over CEC within 4 s, switches it on by IR. */
    void wake() throws IOException {
        key(WAKEUP);
        if (sb == null || irTvId == null) return;
        try {
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!tvReportsOn()) sb.command(irTvId, "turnOn");
    }

    boolean tvReportsOn() {
        try {
            return parseTvOn(adb.shell("dumpsys hdmi_control | grep 'Report Power Status'", 3000));
        } catch (IOException e) {
            return false;
        }
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

    /** The TV's last CEC power report: 0 = on, 2 = turning on. */
    static boolean parseTvOn(String dump) {
        Matcher m = TV_POWER.matcher(dump);
        String last = null;
        while (m.find()) last = m.group(1);
        return "0".equals(last) || "2".equals(last);
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
