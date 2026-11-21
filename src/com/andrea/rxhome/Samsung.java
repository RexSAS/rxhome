package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Samsung Tizen TVs (2016+): the remote-control WebSocket on 8002 (TLS, self-signed: pinned), as the
 * open-source samsungtvws does it. The TV shows "Allow" once; its token is kept. Info on http 8001.
 */
class Samsung implements Screen {

    static final class Pairing {
        final String token, pin;

        Pairing(String token, String pin) {
            this.token = token;
            this.pin = pin;
        }
    }

    private static final Map<Integer, String> KEYS = new HashMap<>();
    static {
        int[] codes = {19, 20, 21, 22, 23, 3, 4, 82, 85, 89, 90, 24, 25, 164, 178};
        String[] names = {"UP", "DOWN", "LEFT", "RIGHT", "ENTER", "HOME", "RETURN", "MENU", "PLAY", "REWIND", "FF",
            "VOLUP", "VOLDOWN", "MUTE", "SOURCE"};
        for (int i = 0; i < codes.length; i++) KEYS.put(codes[i], "KEY_" + names[i]);
    }

    private static final int PAIR_WAIT_MS = 30000, TIMEOUT_MS = 4000;
    private final String ip, token, pin, mac, id;
    private Ws ws;

    Samsung(String ip, String token, String pin, String mac) {
        this(ip, token, pin, mac, "");
    }

    /** id: the saved device id, so another home's Samsung at the same IP isn't taken for this one. */
    Samsung(String ip, String token, String pin, String mac, String id) {
        this.id = id == null ? "" : id;
        this.ip = ip;
        this.token = token;
        this.pin = pin;
        this.mac = mac;
    }

    /** Asks the TV to trust rxhome: the user chooses Allow on the TV (up to 30 s). */
    static Pairing pair(String ip) throws IOException {
        String pin = Pin.capture(host(ip), port(ip));
        Ws w = Ws.connect(channelUrl(ip, null), Pin.factory(pin, null), TIMEOUT_MS);
        try {
            String token = expectConnect(w, PAIR_WAIT_MS);
            if (token == null) throw new IOException("The TV gave no token: try again");
            return new Pairing(token, pin);
        } finally {
            w.close();
        }
    }

    /** {id, name, mac} from the TV's info page, or null if it's not a Samsung. Never throws. */
    static String[] info(String ip) {
        return parseInfo(fetch("http://" + host(ip) + ":8001/api/v2/"));
    }

    @Override
    public synchronized void key(int keycode) throws IOException {
        send(keyMessage(keycode));
    }

    @Override
    public void setVolume(int level) {
        throw new UnsupportedOperationException("This Samsung can't set an exact volume");
    }

    // shortcut: KEY_MUTE toggles (the remote API has no "mute on"), use UPnP RenderingControl if it matters.
    @Override
    public void mute() throws IOException {
        key(164);
    }

    /** KEY_POWER toggles, so it's only sent while the TV says it's on. */
    @Override
    public void powerOff() throws IOException {
        if (!Boolean.FALSE.equals(isOn())) send(keyMessage("KEY_POWER"));
    }

    @Override
    public void powerOn() throws IOException {
        IOException failed = null;
        if (Boolean.FALSE.equals(isOn())) {
            try {
                send(keyMessage("KEY_POWER")); // standby with the network up: the API still listens
            } catch (IOException e) {
                failed = e;
            }
        }
        if (mac != null && !mac.isEmpty()) Screen.wakeOnLan(mac);
        else if (failed != null) throw failed;
    }

    @Override
    public Boolean isOn() {
        return power(fetch("http://" + host(ip) + ":8001/api/v2/"), id);
    }

    /** Its power, if the info page is this TV's (id empty = don't check). */
    static Boolean power(String json, String id) {
        String[] info = parseInfo(json);
        if (!id.isEmpty() && (info == null || !info[0].equals(id))) return null;
        return parsePower(json);
    }

    @Override
    public synchronized List<String[]> apps() throws IOException {
        send(emit("ed.installedApp.get", null));
        long until = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < until) {
            String m = ws.receive(TIMEOUT_MS);
            if (m.contains("\"ed.installedApp.get\"")) return parseApps(m);
        }
        throw new IOException("The TV didn't list its apps");
    }

    @Override
    public void launch(String appId) throws IOException {
        JSONObject data = new JSONObject();
        try {
            data.put("appId", appId).put("action_type", "DEEP_LINK");
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        send(emit("ed.apps.launch", data));
    }

    @Override
    public boolean can(String what) {
        return !what.equals("volume");
    }

    /** Sends on the open connection; once more on a fresh one if it had dropped. */
    private synchronized void send(String message) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                if (ws == null) open();
                ws.send(message);
                return;
            } catch (IOException e) {
                if (ws != null) ws.close();
                ws = null;
                if (attempt == 1) throw e;
            }
        }
    }

    private void open() throws IOException {
        Ws w = Ws.connect(channelUrl(ip, token), Pin.factory(pin, null), TIMEOUT_MS);
        try {
            expectConnect(w, TIMEOUT_MS);
        } catch (IOException e) {
            w.close();
            throw e;
        }
        ws = w;
    }

    /** Waits for ms.channel.connect; returns its token (null if none). */
    private static String expectConnect(Ws w, int waitMs) throws IOException {
        long until = System.currentTimeMillis() + waitMs;
        while (true) {
            int left = (int) Math.max(1, until - System.currentTimeMillis());
            String m;
            try {
                m = w.receive(left);
            } catch (IOException e) {
                if (System.currentTimeMillis() >= until) throw new IOException("No answer on the TV: tap Add again and choose Allow");
                throw e;
            }
            try {
                JSONObject o = new JSONObject(m);
                String ev = o.optString("event");
                if (ev.equals("ms.channel.unauthorized")) {
                    throw new IOException("The TV said no. Tap Add again and choose Allow");
                }
                if (ev.equals("ms.channel.connect")) {
                    JSONObject d = o.optJSONObject("data");
                    String t = d == null ? "" : d.optString("token");
                    return t.isEmpty() ? null : t;
                }
            } catch (JSONException e) {
                // not JSON: skip
            }
        }
    }

    static String keyMessage(int keycode) {
        String k = KEYS.get(keycode);
        if (k == null) throw new UnsupportedOperationException("No such key on a Samsung: " + keycode);
        return keyMessage(k);
    }

    private static String keyMessage(String key) {
        try {
            return new JSONObject().put("method", "ms.remote.control").put("params", new JSONObject()
                .put("Cmd", "Click").put("DataOfCmd", key).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))
                .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String emit(String event, JSONObject data) {
        try {
            JSONObject p = new JSONObject().put("event", event).put("to", "host");
            if (data != null) p.put("data", data);
            return new JSONObject().put("method", "ms.channel.emit").put("params", p).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** name = base64("rxhome"), shown on the TV's Allow prompt. */
    static String channelUrl(String ip, String token) {
        return "wss://" + host(ip) + ":" + port(ip) + "/api/v2/channels/samsung.remote.control?name=cnhob21l"
            + (token == null ? "" : "&token=" + token);
    }

    static Boolean parsePower(String json) {
        try {
            JSONObject d = new JSONObject(json).getJSONObject("device");
            String s = d.optString("PowerState");
            return s.isEmpty() || s.equals("on");
        } catch (JSONException e) {
            return null;
        }
    }

    static String[] parseInfo(String json) {
        try {
            JSONObject d = new JSONObject(json).getJSONObject("device");
            return new String[] {d.getString("id"), d.optString("name", "Samsung TV"), d.optString("wifiMac", "")};
        } catch (JSONException e) {
            return null;
        }
    }

    static List<String[]> parseApps(String json) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(json).getJSONObject("data").getJSONArray("data");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[] {o.getString("appId"), o.optString("name", o.getString("appId"))});
            }
        } catch (JSONException e) {
            // none
        }
        return out;
    }

    private static String host(String ip) {
        return ip.contains(":") ? ip.substring(0, ip.indexOf(':')) : ip;
    }

    private static int port(String ip) {
        return ip.contains(":") ? Integer.parseInt(ip.substring(ip.indexOf(':') + 1)) : 8002;
    }

    private static String fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2000);
            if (c.getResponseCode() != 200) return "";
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } catch (IOException e) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
