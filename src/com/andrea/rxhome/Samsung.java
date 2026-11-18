package com.andrea.rxhome;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Samsung Tizen TVs (2016+): remote keys over WebSocket (wss 8002; a prompt on the TV gives a token), the
 * TV's info (http 8001: name, MAC, on/standby). No exact volume level: up, down and mute only.
 * Power on: Wake-on-LAN (FireTv.wake) or, in network standby, the power key.
 */
class Samsung implements TvApi {

    static final String APP_NAME = "rxhome";
    final String host, token, certPin;

    Samsung(String host, String token, String certPin) {
        this.host = host;
        this.token = token;
        this.certPin = certPin;
    }

    static final class Pairing {
        final String token, certPin, mac;

        Pairing(String token, String certPin, String mac) {
            this.token = token;
            this.certPin = certPin;
            this.mac = mac;
        }
    }

    /** Asks the TV to allow rxhome (a prompt on the TV, up to a minute); its MAC for switching it on. */
    static Pairing pair(String host) throws IOException {
        String[] seen = {null};
        try (Ws ws = new Ws(url(host, APP_NAME, null), null, seen, 3000)) {
            String token = null;
            long until = System.currentTimeMillis() + 60_000;
            while (token == null) {
                if (System.currentTimeMillis() > until) throw new IOException("Nobody allowed rxhome on the TV");
                String reply = ws.receive(60_000);
                if (reply.contains("ms.channel.unauthorized")) throw new IOException("The TV said no");
                token = token(reply);
            }
            return new Pairing(token, seen[0] == null ? "" : seen[0], mac(info(host)));
        }
    }

    /** The TV's info page (http 8001), "" if it doesn't answer. */
    static String info(String host) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":8001/api/v2/").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2500);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return b.toString("UTF-8");
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return "";
        }
    }

    private void send(String remoteKey) throws IOException {
        try (Ws ws = new Ws(url(host, APP_NAME, token), certPin.isEmpty() ? null : certPin, null, 3000)) {
            while (token(ws.receive(5000)) == null) {
                // Until it says connected.
            }
            ws.send(keyMessage(remoteKey));
        }
    }

    @Override
    public void key(String key) throws IOException {
        Boolean on = powerOn(info(host));
        switch (key) {
            case "Standby": // its power key toggles: only when it isn't already in standby
                if (!Boolean.FALSE.equals(on)) send("KEY_POWER");
                break;
            case "PowerOn": // in network standby the power key wakes it; fully off, Wake-on-LAN does (FireTv.wake)
                if (Boolean.FALSE.equals(on)) send("KEY_POWER");
                break;
            default:
                send(keyFor(key));
        }
    }

    @Override
    public void setVolume(int level) throws IOException {
        throw new IOException("A Samsung TV only turns its volume up and down, not to a level");
    }

    @Override
    public void mute() throws IOException {
        send("KEY_MUTE");
    }

    @Override
    public int currentVolume() {
        return -1;
    }

    static String url(String host, String name, String token) {
        String n = Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        return "wss://" + host + ":8002/api/v2/channels/samsung.remote.control?name=" + n
            + (token == null || token.isEmpty() ? "" : "&token=" + token);
    }

    static String keyMessage(String remoteKey) {
        try {
            return new JSONObject().put("method", "ms.remote.control").put("params", new JSONObject().put("Cmd", "Click")
                .put("DataOfCmd", remoteKey).put("Option", "false").put("TypeOfRemote", "SendRemoteKey")).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String keyFor(String key) {
        return key.equals("VolumeUp") ? "KEY_VOLUP" : key.equals("VolumeDown") ? "KEY_VOLDOWN" : "KEY_MUTE";
    }

    /** Connected: its token ("" on models that don't use one); null for anything else. */
    static String token(String reply) {
        try {
            JSONObject o = new JSONObject(reply);
            if (!o.optString("event").equals("ms.channel.connect")) return null;
            JSONObject d = o.optJSONObject("data");
            return d == null ? "" : d.optString("token", "");
        } catch (JSONException e) {
            return null;
        }
    }

    private static JSONObject device(String apiJson) {
        try {
            return new JSONObject(apiJson).optJSONObject("device");
        } catch (JSONException e) {
            return null;
        }
    }

    static boolean isSamsung(String apiJson) {
        JSONObject d = device(apiJson);
        return d != null && d.optString("type").contains("Samsung");
    }

    /** On (TRUE) or standby (FALSE) per the TV; null if it doesn't say (older models). */
    static Boolean powerOn(String apiJson) {
        JSONObject d = device(apiJson);
        String p = d == null ? "" : d.optString("PowerState", "");
        return p.equals("on") ? Boolean.TRUE : p.equals("standby") ? Boolean.FALSE : null;
    }

    static String mac(String apiJson) {
        JSONObject d = device(apiJson);
        String m = d == null ? "" : d.optString("wifiMac", "");
        return m.matches("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}") ? m : null;
    }

    static String name(String apiJson) {
        JSONObject d = device(apiJson);
        String n = d == null ? "" : d.optString("name", "").trim();
        return n.isEmpty() ? null : n;
    }
}
