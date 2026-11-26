package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** SwitchBot cloud API v1.1: the Bot on the server's power button and the Hub Mini's IR TV remote. */
class SwitchBot {

    private static final String API = "https://api.switch-bot.com/v1.1/devices";
    private final String token, secret;

    SwitchBot(String token, String secret) {
        this.token = token;
        this.secret = secret;
    }

    /** Device id → name, Bots and IR remotes together. */
    Map<String, String> devices() throws IOException {
        return parseDevices(request("GET", API, null));
    }

    /** E.g. command(botId, "press"), command(tvId, "turnOff"). */
    void command(String deviceId, String command) throws IOException {
        String body = "{\"command\":\"" + command + "\",\"parameter\":\"default\",\"commandType\":\"command\"}";
        check(request("POST", API + "/" + deviceId + "/commands", body));
    }

    /**
     * The request never left the phone (no DNS, no connection), so retrying can't press twice. A read
     * timeout may have pressed already: not retried, a second press could switch the server off.
     */
    static boolean neverSent(IOException e) {
        return e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException
            || e instanceof java.net.NoRouteToHostException
            || e instanceof java.net.SocketTimeoutException && String.valueOf(e.getMessage()).contains("connect");
    }

    private String request(String method, String url, String body) throws IOException {
        long t = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        // Cloud round trip, so a longer timeout than the LAN checks.
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", token);
        c.setRequestProperty("sign", sign(token, secret, t, nonce));
        c.setRequestProperty("t", Long.toString(t));
        c.setRequestProperty("nonce", nonce);
        c.setRequestProperty("Content-Type", "application/json; charset=utf8");
        try {
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("SwitchBot refused the token or secret");
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in == null) throw new IOException("SwitchBot HTTP " + code);
            try (InputStream i = in) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = i.read(b)) > 0; ) buf.write(b, 0, n);
                return buf.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    static String sign(String token, String secret, long t, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal((token + t + nonce).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject check(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            if (o.optInt("statusCode") != 100) throw new IOException(o.optString("message", "SwitchBot error"));
            return o;
        } catch (JSONException e) {
            throw new IOException("SwitchBot sent an unreadable reply");
        }
    }

    static Map<String, String> parseDevices(String json) throws IOException {
        JSONObject body = check(json).optJSONObject("body");
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null) return out;
        for (String list : new String[] {"deviceList", "infraredRemoteList"}) {
            JSONArray a = body.optJSONArray(list);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject d = a.optJSONObject(i);
                String id = d == null ? "" : d.optString("deviceId");
                if (!id.isEmpty()) out.put(id, d.optString("deviceName", id));
            }
        }
        return out;
    }
}
