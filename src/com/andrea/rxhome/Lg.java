package com.andrea.rxhome;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * LG webOS TVs: the SSAP WebSocket on 3001 (TLS, self-signed: pinned), as the open-source aiowebostv
 * does it. The TV shows "Accept" once; its client-key is kept. D-pad keys go over the pointer socket.
 * Switching on is Wake-on-LAN only (needs "Turn on via Wi-Fi" / "Mobile TV On" on the TV).
 */
class Lg implements Screen {

    /** The TV said no (not a dropped connection): not worth retrying. */
    static final class Refused extends IOException {
        Refused(String m) {
            super(m);
        }
    }

    static boolean retry(IOException e) {
        return !(e instanceof Refused);
    }

    static final class Pairing {
        final String clientKey, pin;

        Pairing(String clientKey, String pin) {
            this.clientKey = clientKey;
            this.pin = pin;
        }
    }

    // aiowebostv's registration payload (handshake.py), verbatim: the TV checks the signed manifest.
    private static final String PAYLOAD =
        "{\"forcePairing\":false,\"manifest\":{\"appVersion\":\"1.1\",\"manifestVersion\":1,\"permissions\":["
        + "\"LAUNCH\",\"LAUNCH_WEBAPP\",\"APP_TO_APP\",\"CLOSE\",\"TEST_OPEN\",\"TEST_PROTECTED\",\"CONTROL_AUD"
        + "IO\",\"CONTROL_DISPLAY\",\"CONTROL_INPUT_JOYSTICK\",\"CONTROL_INPUT_MEDIA_RECORDING\",\"CONTROL_INPU"
        + "T_MEDIA_PLAYBACK\",\"CONTROL_INPUT_TV\",\"CONTROL_POWER\",\"CONTROL_TV_SCREEN\",\"READ_APP_STATUS\","
        + "\"READ_CURRENT_CHANNEL\",\"READ_INPUT_DEVICE_LIST\",\"READ_NETWORK_STATE\",\"READ_RUNNING_APPS\",\"R"
        + "EAD_TV_CHANNEL_LIST\",\"WRITE_NOTIFICATION_TOAST\",\"READ_POWER_STATE\",\"READ_COUNTRY_INFO\",\"CONT"
        + "ROL_INPUT_TEXT\",\"CONTROL_MOUSE_AND_KEYBOARD\",\"READ_INSTALLED_APPS\",\"READ_SETTINGS\"],\"signatu"
        + "res\":[{\"signature\":\"eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNp"
        + "Z25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5"
        + "iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMf"
        + "JPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXz"
        + "dKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw==\",\"signatureVersion\":1}],\"s"
        + "igned\":{\"appId\":\"com.lge.test\",\"created\":\"20140509\",\"localizedAppNames\":{\"\":\"LG Remote"
        + " App\",\"ko-KR\":\"\\ub9ac\\ubaa8\\ucee8 \\uc571\",\"zxx-XX\":\"\\u041b\\u0413 R\\u044d\\u043cot\\u0"
        + "44d A\\u041f\\u041f\"},\"localizedVendorNames\":{\"\":\"LG Electronics\"},\"permissions\":[\"TEST_SE"
        + "CURE\",\"CONTROL_INPUT_TEXT\",\"CONTROL_MOUSE_AND_KEYBOARD\",\"READ_INSTALLED_APPS\",\"READ_LGE_SDX\""
        + ",\"READ_NOTIFICATIONS\",\"SEARCH\",\"WRITE_SETTINGS\",\"WRITE_NOTIFICATION_ALERT\",\"CONTROL_POWER\""
        + ",\"READ_CURRENT_CHANNEL\",\"READ_RUNNING_APPS\",\"READ_UPDATE_INFO\",\"UPDATE_FROM_REMOTE_APP\",\"RE"
        + "AD_LGE_TV_INPUT_EVENTS\",\"READ_TV_CURRENT_TIME\"],\"serial\":\"2f930e2d2cfe083771f68e4fe7bb07\",\"v"
        + "endorId\":\"com.lge\"}},\"pairingType\":\"PROMPT\"}";

    private static final Map<Integer, String> BUTTONS = new HashMap<>();
    static {
        int[] codes = {19, 20, 21, 22, 23, 3, 4, 82, 85, 89, 90, 24, 25, 164};
        String[] names = {"UP", "DOWN", "LEFT", "RIGHT", "ENTER", "HOME", "BACK", "MENU", "PLAY", "REWIND", "FASTFORWARD",
            "VOLUMEUP", "VOLUMEDOWN", "MUTE"};
        for (int i = 0; i < codes.length; i++) BUTTONS.put(codes[i], names[i]);
    }

    private static final int PAIR_WAIT_MS = 60000, TIMEOUT_MS = 4000;
    private final String ip, clientKey, pin, mac;
    private Ws ws, pointer;
    private int nextId;

    Lg(String ip, String clientKey, String pin, String mac) {
        this.ip = ip;
        this.clientKey = clientKey;
        this.pin = pin;
        this.mac = mac;
    }

    /** Asks the TV to trust rxhome: the user chooses Accept on the TV (up to 60 s). */
    static Pairing pair(String ip) throws IOException {
        String pin = Pin.capture(host(ip), port(ip));
        Ws w = Ws.connect(url(ip), Pin.factory(pin, null), TIMEOUT_MS);
        try {
            return new Pairing(register(w, null, PAIR_WAIT_MS), pin);
        } finally {
            w.close();
        }
    }

    @Override
    public synchronized void key(int keycode) throws IOException {
        if (keycode == 178) {
            launch("com.webos.app.inputpicker");
            return;
        }
        String m = buttonMessage(keycode);
        for (int attempt = 0; ; attempt++) {
            try {
                if (pointer == null) {
                    String path = call("ssap://com.webos.service.networkinput/getPointerInputSocket", null).getString("socketPath");
                    pointer = Ws.connect(path, path.startsWith("wss") ? Pin.factory(pin, null) : null, TIMEOUT_MS);
                }
                pointer.send(m);
                return;
            } catch (IOException | JSONException e) {
                if (pointer != null) pointer.close();
                pointer = null;
                if (attempt == 1) throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
    }

    @Override
    public void setVolume(int level) throws IOException {
        call("ssap://audio/setVolume", obj("volume", level));
    }

    @Override
    public void mute() throws IOException {
        call("ssap://audio/setMute", obj("mute", true));
    }

    @Override
    public void powerOff() throws IOException {
        call("ssap://system/turnOff", null);
    }

    @Override
    public void powerOn() throws IOException {
        if (mac == null || mac.isEmpty()) throw new IOException("This LG can't be switched on: add it again with the TV on");
        Screen.wakeOnLan(mac);
    }

    @Override
    public Boolean isOn() {
        if (!Discovery.anyAnswers(java.util.Collections.singletonList(host(ip) + ":" + port(ip)), 1200)) return null; // off or away: fast
        try {
            JSONObject p = call("ssap://com.webos.service.tvpower/power/getPowerState", null);
            return p.optString("state").equals("Active");
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public List<String[]> apps() throws IOException {
        JSONObject p = call("ssap://com.webos.applicationManager/listLaunchPoints", null);
        return parseApps("{\"payload\":" + p + "}");
    }

    @Override
    public void launch(String appId) throws IOException {
        call("ssap://system.launcher/launch", obj("id", appId));
    }

    @Override
    public boolean can(String what) {
        return true;
    }

    /** The TV's MAC, for Wake-on-LAN (read while it's on, at pairing). Null if it won't say. */
    String readMac() {
        try {
            JSONObject p = call("ssap://com.webos.service.connectionmanager/getinfo", null);
            String m = p.optString("wifiInfo").isEmpty() ? "" : p.getJSONObject("wifiInfo").optString("macAddress");
            if (m.isEmpty() && p.has("wiredInfo")) m = p.getJSONObject("wiredInfo").optString("macAddress");
            return m.isEmpty() ? null : m;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** One request on the registered connection (opened, or reopened once, as needed); its payload. */
    private synchronized JSONObject call(String uri, JSONObject payload) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                if (ws == null) {
                    Ws w = Ws.connect(url(ip), Pin.factory(pin, null), TIMEOUT_MS);
                    try {
                        register(w, clientKey, TIMEOUT_MS);
                    } catch (IOException e) {
                        w.close();
                        throw e;
                    }
                    ws = w;
                }
                String id = Integer.toString(++nextId);
                ws.send(request(nextId, uri, payload));
                return await(ws, id);
            } catch (IOException e) {
                if (ws != null) ws.close();
                ws = null;
                if (attempt == 1 || !retry(e)) throw e;
            }
        }
    }

    private static JSONObject await(Ws w, String id) throws IOException {
        long until = System.currentTimeMillis() + TIMEOUT_MS;
        while (true) {
            String m = w.receive((int) Math.max(1, until - System.currentTimeMillis()));
            try {
                JSONObject o = new JSONObject(m);
                if (!id.equals(o.optString("id"))) continue;
                if (o.optString("type").equals("error")) throw new Refused("The TV refused: " + o.optString("error"));
                JSONObject p = o.optJSONObject("payload");
                return p == null ? new JSONObject() : p;
            } catch (JSONException e) {
                // not JSON: skip
            }
        }
    }

    /** Sends register, waits for registered; returns the client-key. */
    private static String register(Ws w, String key, int waitMs) throws IOException {
        w.send(registerMessage(key));
        long until = System.currentTimeMillis() + waitMs;
        while (true) {
            String m;
            try {
                m = w.receive((int) Math.max(1, until - System.currentTimeMillis()));
            } catch (IOException e) {
                if (System.currentTimeMillis() >= until) throw new IOException("No answer on the TV: tap Add again and choose Accept");
                throw e;
            }
            try {
                JSONObject o = new JSONObject(m);
                String type = o.optString("type");
                if (type.equals("error")) {
                    throw new Refused(key == null ? "The TV said no. Tap Add again and choose Accept"
                        : "The TV doesn't know rxhome any more: add it again in Settings");
                }
                if (type.equals("registered")) return o.getJSONObject("payload").getString("client-key");
            } catch (JSONException e) {
                // not JSON, or a "response" while the prompt shows: keep waiting
            }
        }
    }

    static String registerMessage(String clientKey) {
        try {
            JSONObject p = new JSONObject(PAYLOAD);
            if (clientKey != null) p.put("client-key", clientKey);
            return new JSONObject().put("type", "register").put("id", "register_0").put("payload", p).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String request(int id, String uri, JSONObject payload) {
        try {
            JSONObject o = new JSONObject().put("type", "request").put("id", Integer.toString(id)).put("uri", uri);
            if (payload != null) o.put("payload", payload);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String buttonMessage(int keycode) {
        String b = BUTTONS.get(keycode);
        if (b == null) throw new UnsupportedOperationException("No such key on an LG: " + keycode);
        return "type:button\nname:" + b + "\n\n";
    }

    static Boolean parsePower(String json) {
        try {
            return new JSONObject(json).getJSONObject("payload").optString("state").equals("Active");
        } catch (JSONException e) {
            return null;
        }
    }

    static List<String[]> parseApps(String json) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(json).getJSONObject("payload").getJSONArray("launchPoints");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[] {o.getString("id"), o.optString("title", o.getString("id"))});
            }
        } catch (JSONException e) {
            // none
        }
        return out;
    }

    private static JSONObject obj(String k, Object v) {
        try {
            return new JSONObject().put(k, v);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String url(String ip) {
        return "wss://" + host(ip) + ":" + port(ip) + "/";
    }

    private static String host(String ip) {
        return ip.contains(":") ? ip.substring(0, ip.indexOf(':')) : ip;
    }

    private static int port(String ip) {
        return ip.contains(":") ? Integer.parseInt(ip.substring(ip.indexOf(':') + 1)) : 3001;
    }
}
