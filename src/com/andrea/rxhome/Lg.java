package com.andrea.rxhome;

import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * LG webOS TVs (SSAP over WebSocket): pairing (a prompt on the TV gives a client key), power off, volume.
 * wss on 3001 (2022+ firmware), else ws on 3000. Power on: Wake-on-LAN (FireTv.wake), with the MAC read at pairing.
 */
class Lg implements TvApi {

    /** LG's own "remote app" manifest, as webOS clients send it (from PyWebOSTV, pywebostv/connection.py). */
    static final String MANIFEST = "{\"appVersion\":\"1.1\",\"manifestVersion\":1,\"permissions\":[\"LAUNCH\",\"LAUNCH_WEBAPP\",\"APP_TO_APP\",\"CLOSE\",\"TEST_OPEN\",\"TEST_PROTECTED\",\"CONTROL_AUDIO\",\"CONTROL_DISPLAY\",\"CONTROL_INPUT_JOYSTICK\",\"CONTROL_INPUT_MEDIA_RECORDING\",\"CONTROL_INPUT_MEDIA_PLAYBACK\",\"CONTROL_INPUT_TV\",\"CONTROL_POWER\",\"READ_APP_STATUS\",\"READ_CURRENT_CHANNEL\",\"READ_INPUT_DEVICE_LIST\",\"READ_NETWORK_STATE\",\"READ_RUNNING_APPS\",\"READ_TV_CHANNEL_LIST\",\"WRITE_NOTIFICATION_TOAST\",\"READ_POWER_STATE\",\"READ_COUNTRY_INFO\",\"READ_SETTINGS\",\"CONTROL_TV_SCREEN\",\"CONTROL_TV_STANBY\",\"CONTROL_FAVORITE_GROUP\",\"CONTROL_USER_INFO\",\"CHECK_BLUETOOTH_DEVICE\",\"CONTROL_BLUETOOTH\",\"CONTROL_TIMER_INFO\",\"STB_INTERNAL_CONNECTION\",\"CONTROL_RECORDING\",\"READ_RECORDING_STATE\",\"WRITE_RECORDING_LIST\",\"READ_RECORDING_LIST\",\"READ_RECORDING_SCHEDULE\",\"WRITE_RECORDING_SCHEDULE\",\"READ_STORAGE_DEVICE_LIST\",\"READ_TV_PROGRAM_INFO\",\"CONTROL_BOX_CHANNEL\",\"READ_TV_ACR_AUTH_TOKEN\",\"READ_TV_CONTENT_STATE\",\"READ_TV_CURRENT_TIME\",\"ADD_LAUNCHER_CHANNEL\",\"SET_CHANNEL_SKIP\",\"RELEASE_CHANNEL_SKIP\",\"CONTROL_CHANNEL_BLOCK\",\"DELETE_SELECT_CHANNEL\",\"CONTROL_CHANNEL_GROUP\",\"SCAN_TV_CHANNELS\",\"CONTROL_TV_POWER\",\"CONTROL_WOL\"],\"signatures\":[{\"signature\":\"eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw==\",\"signatureVersion\":1}],\"signed\":{\"appId\":\"com.lge.test\",\"created\":\"20140509\",\"localizedAppNames\":{\"\":\"LG Remote App\",\"ko-KR\":\"\\ub9ac\\ubaa8\\ucee8 \\uc571\",\"zxx-XX\":\"\\u041b\\u0413 R\\u044d\\u043cot\\u044d A\\u041f\\u041f\"},\"localizedVendorNames\":{\"\":\"LG Electronics\"},\"permissions\":[\"TEST_SECURE\",\"CONTROL_INPUT_TEXT\",\"CONTROL_MOUSE_AND_KEYBOARD\",\"READ_INSTALLED_APPS\",\"READ_LGE_SDX\",\"READ_NOTIFICATIONS\",\"SEARCH\",\"WRITE_SETTINGS\",\"WRITE_NOTIFICATION_ALERT\",\"CONTROL_POWER\",\"READ_CURRENT_CHANNEL\",\"READ_RUNNING_APPS\",\"READ_UPDATE_INFO\",\"UPDATE_FROM_REMOTE_APP\",\"READ_LGE_TV_INPUT_EVENTS\",\"READ_TV_CURRENT_TIME\"],\"serial\":\"2f930e2d2cfe083771f68e4fe7bb07\",\"vendorId\":\"com.lge\"}}";

    final String host, clientKey, certPin;

    Lg(String host, String clientKey, String certPin) {
        this.host = host;
        this.clientKey = clientKey;
        this.certPin = certPin;
    }

    static final class Pairing {
        final String clientKey, certPin, mac;

        Pairing(String clientKey, String certPin, String mac) {
            this.clientKey = clientKey;
            this.certPin = certPin;
            this.mac = mac;
        }
    }

    /** Asks the TV to accept rxhome (a prompt on the TV, up to a minute), then reads its MAC for switching it on. */
    static Pairing pair(String host) throws IOException {
        String[] seen = {null};
        try (Ws ws = open(host, null, seen)) {
            ws.send(registerMessage(null));
            String key = null;
            long until = System.currentTimeMillis() + 60_000;
            while (key == null) {
                if (System.currentTimeMillis() > until) throw new IOException("Nobody accepted rxhome on the TV");
                String reply = ws.receive(60_000);
                if (reply.contains("\"type\":\"error\"")) throw new IOException("The TV said no");
                key = clientKey(reply);
            }
            ws.send(request("mac", "ssap://com.webos.service.update/getCurrentSWInformation", null));
            String mac = null;
            try {
                mac = mac(waitFor(ws, "mac"));
            } catch (IOException | RuntimeException e) {
                // Without it the TV can still be switched off, not on.
            }
            return new Pairing(key, seen[0] == null ? "" : seen[0], mac);
        }
    }

    /** wss on 3001, else ws on 3000 (older firmware). */
    private static Ws open(String host, String pin, String[] seen) throws IOException {
        boolean pinned = pin != null && !pin.isEmpty();
        try {
            return new Ws("wss://" + host + ":3001/", pinned ? pin : null, seen, 3000);
        } catch (IOException e) {
            if (pinned) throw e; // paired over wss: never fall back to plain
            return new Ws("ws://" + host + ":3000/", null, null, 3000);
        }
    }

    /** One request with the saved key; its answer. */
    private String call(String uri, String payload) throws IOException {
        try (Ws ws = open(host, certPin, null)) {
            ws.send(registerMessage(clientKey));
            while (clientKey(ws.receive(5000)) == null) {
                // Until it says "registered".
            }
            ws.send(request("1", uri, payload));
            return waitFor(ws, "1");
        }
    }

    private static String waitFor(Ws ws, String id) throws IOException {
        while (true) {
            String r = ws.receive(5000);
            try {
                JSONObject o = new JSONObject(r);
                if (!id.equals(o.optString("id"))) continue;
                if (o.optString("type").equals("error")) throw new IOException("The TV said: " + o.optString("error"));
                return r;
            } catch (JSONException e) {
                // Not for us.
            }
        }
    }

    @Override
    public void key(String key) throws IOException {
        switch (key) {
            case "Standby":
                call("ssap://system/turnOff", null);
                break;
            case "PowerOn":
                break; // off means off the network: Wake-on-LAN does it (FireTv.wake)
            case "VolumeUp":
                call("ssap://audio/volumeUp", null);
                break;
            case "VolumeDown":
                call("ssap://audio/volumeDown", null);
                break;
            default: // Mute: toggles, like a remote
                call("ssap://audio/setMute", "{\"mute\":" + !muted(call("ssap://audio/getVolume", null)) + "}");
        }
    }

    @Override
    public void setVolume(int level) throws IOException {
        call("ssap://audio/setVolume", "{\"volume\":" + Math.max(0, level) + "}");
    }

    @Override
    public void mute() throws IOException {
        call("ssap://audio/setMute", "{\"mute\":true}");
    }

    @Override
    public int currentVolume() throws IOException {
        return volume(call("ssap://audio/getVolume", null));
    }

    static String registerMessage(String clientKey) {
        try {
            JSONObject p = new JSONObject().put("forcePairing", false).put("pairingType", "PROMPT")
                .put("manifest", new JSONObject(MANIFEST));
            if (clientKey != null && !clientKey.isEmpty()) p.put("client-key", clientKey);
            return new JSONObject().put("type", "register").put("id", "reg0").put("payload", p).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String request(String id, String uri, String payloadJson) {
        try {
            JSONObject o = new JSONObject().put("type", "request").put("id", id).put("uri", uri);
            if (payloadJson != null) o.put("payload", new JSONObject(payloadJson));
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The key in a "registered" reply; null for anything else (e.g. the prompt being shown). */
    static String clientKey(String reply) {
        try {
            JSONObject o = new JSONObject(reply);
            if (!o.optString("type").equals("registered")) return null;
            String k = o.getJSONObject("payload").optString("client-key", "");
            return k.isEmpty() ? null : k;
        } catch (JSONException e) {
            return null;
        }
    }

    private static JSONObject payload(String reply) {
        try {
            return new JSONObject(reply).optJSONObject("payload");
        } catch (JSONException e) {
            return null;
        }
    }

    static int volume(String reply) {
        JSONObject p = payload(reply);
        if (p == null) return -1;
        if (p.has("volume")) return p.optInt("volume", -1);
        JSONObject s = p.optJSONObject("volumeStatus");
        return s == null ? -1 : s.optInt("volume", -1);
    }

    static boolean muted(String reply) {
        JSONObject p = payload(reply);
        if (p == null) return false;
        JSONObject s = p.optJSONObject("volumeStatus");
        return p.optBoolean("muted", s != null && s.optBoolean("muteStatus"));
    }

    static String mac(String reply) {
        JSONObject p = payload(reply);
        String m = p == null ? "" : p.optString("device_id", "");
        return m.matches("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}") ? m : null;
    }
}
