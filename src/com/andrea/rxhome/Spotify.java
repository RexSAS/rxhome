package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Spotify Web API: the speakers (Spotify Connect devices), what's playing, and moving playback between them.
 * Logs in with PKCE (no app secret on the phone); the refresh token is kept, access tokens are renewed as needed.
 */
class Spotify {

    static final String SCOPES = "user-read-playback-state user-modify-playback-state";
    private static final String API = "https://api.spotify.com/v1", ACCOUNTS = "https://accounts.spotify.com";

    static final class Device {
        final String id, name, type;
        final boolean active;

        Device(String id, String name, String type, boolean active) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.active = active;
        }
    }

    static final class Playing {
        final String title, by, device;
        final boolean playing;
        final int volume; // the playing speaker's volume, -1 when Spotify can't set it there

        Playing(String title, String by, String device, boolean playing, int volume) {
            this.title = title;
            this.by = by;
            this.device = device;
            this.playing = playing;
            this.volume = volume;
        }
    }

    static final class Tokens {
        final String access, refresh;
        final int expiresIn;

        Tokens(String access, String refresh, int expiresIn) {
            this.access = access;
            this.refresh = refresh;
            this.expiresIn = expiresIn;
        }
    }

    private final String clientId;
    private final Consumer<String> saveRefresh;
    private String refresh, access;
    private long accessUntil;

    /** saveRefresh: Spotify may hand out a new refresh token when renewing; it must be kept. */
    Spotify(String clientId, String refresh, Consumer<String> saveRefresh) {
        this.clientId = clientId;
        this.refresh = refresh;
        this.saveRefresh = saveRefresh;
    }

    List<Device> devices() throws IOException {
        return parseDevices(call("GET", "/me/player/devices", null));
    }

    /** What's playing, or null when nothing is. */
    Playing playing() throws IOException {
        return parsePlaying(call("GET", "/me/player", null));
    }

    /** Moves playback to this speaker and plays (it carries on where it was). */
    void transfer(String deviceId) throws IOException {
        call("PUT", "/me/player", transferBody(deviceId));
    }

    void play() throws IOException {
        call("PUT", "/me/player/play", "");
    }

    void pause() throws IOException {
        call("PUT", "/me/player/pause", "");
    }

    void next() throws IOException {
        call("POST", "/me/player/next", "");
    }

    void previous() throws IOException {
        call("POST", "/me/player/previous", "");
    }

    void volume(int percent) throws IOException {
        call("PUT", volumePath(percent), "");
    }

    static String volumePath(int percent) {
        return "/me/player/volume?volume_percent=" + Math.max(0, Math.min(100, percent));
    }

    // --- login ---

    static String verifier() {
        byte[] b = new byte[48];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String authorizeUrl(String clientId, String redirect, String challenge, String state) {
        return ACCOUNTS + "/authorize?client_id=" + enc(clientId) + "&response_type=code&redirect_uri=" + enc(redirect)
            + "&code_challenge_method=S256&code_challenge=" + enc(challenge) + "&state=" + enc(state) + "&scope=" + enc(SCOPES);
    }

    /** The login code from the browser's request line ("GET /callback?code=…&state=… HTTP/1.1"); null if not ours. */
    static String callbackCode(String requestLine, String state) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2 || !parts[1].startsWith("/callback?")) return null;
        String code = null, got = null;
        for (String kv : parts[1].substring(10).split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            String k = kv.substring(0, eq), val = dec(kv.substring(eq + 1));
            if (k.equals("code")) code = val;
            else if (k.equals("state")) got = val;
        }
        return state.equals(got) ? code : null;
    }

    /** Exchanges the login code for tokens. */
    static Tokens login(String clientId, String code, String redirect, String verifier) throws IOException {
        return parseTokens(post(ACCOUNTS + "/api/token", "grant_type=authorization_code&code=" + enc(code)
            + "&redirect_uri=" + enc(redirect) + "&client_id=" + enc(clientId) + "&code_verifier=" + enc(verifier)), null);
    }

    // --- HTTP ---

    private synchronized String token() throws IOException {
        if (access != null && System.currentTimeMillis() < accessUntil) return access;
        if (refresh == null || refresh.isEmpty()) throw new IOException("Connect Spotify in Settings");
        Tokens t = parseTokens(post(ACCOUNTS + "/api/token",
            "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(clientId)), refresh);
        if (!t.refresh.equals(refresh)) saveRefresh.accept(t.refresh);
        refresh = t.refresh;
        access = t.access;
        accessUntil = System.currentTimeMillis() + (t.expiresIn - 60) * 1000L;
        return access;
    }

    private String call(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token());
        try {
            if (body != null) {
                c.setDoOutput(true); // also for an empty body: Spotify wants a Content-Length on PUT/POST
                if (!body.isEmpty()) c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code == 401) {
                synchronized (this) { access = null; }
                throw new IOException("Spotify login expired, try again");
            }
            if (code == 204) return "";
            String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) throw new IOException(apiError(text, code));
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String post(String url, String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            try (OutputStream o = c.getOutputStream()) {
                o.write(form.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String text = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) {
                try {
                    throw new IOException("Spotify login: " + new JSONObject(text).optString("error_description", "HTTP " + code));
                } catch (JSONException e) {
                    throw new IOException("Spotify login: HTTP " + code);
                }
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = i.read(b)) > 0; ) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        }
    }

    static String apiError(String json, int code) {
        try {
            String m = new JSONObject(json).getJSONObject("error").optString("message", "");
            if (!m.isEmpty()) return "Spotify: " + m;
        } catch (JSONException e) {
            // Not JSON: fall through.
        }
        return "Spotify: HTTP " + code;
    }

    // --- parsing ---

    static Tokens parseTokens(String json, String oldRefresh) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            String r = o.optString("refresh_token", "");
            return new Tokens(o.getString("access_token"), r.isEmpty() ? oldRefresh : r, o.optInt("expires_in", 3600));
        } catch (JSONException e) {
            throw new IOException("Spotify sent an unreadable login reply");
        }
    }

    static List<Device> parseDevices(String json) throws IOException {
        List<Device> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(json).optJSONArray("devices");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject d = a.getJSONObject(i);
                if (d.isNull("id") || d.optBoolean("is_restricted")) continue; // can't be controlled
                out.add(new Device(d.getString("id"), d.optString("name", "Speaker"), d.optString("type", ""), d.optBoolean("is_active")));
            }
        } catch (JSONException e) {
            throw new IOException("Spotify sent an unreadable speaker list");
        }
        return out;
    }

    static Playing parsePlaying(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            JSONObject item = o.optJSONObject("item");
            if (item == null) return null;
            StringBuilder by = new StringBuilder();
            JSONArray artists = item.optJSONArray("artists");
            for (int i = 0; artists != null && i < artists.length(); i++) {
                if (by.length() > 0) by.append(", ");
                by.append(artists.getJSONObject(i).optString("name"));
            }
            JSONObject show = item.optJSONObject("show");
            if (by.length() == 0 && show != null) by.append(show.optString("name"));
            JSONObject dev = o.optJSONObject("device");
            int volume = dev == null || dev.isNull("volume_percent") || !dev.optBoolean("supports_volume", true)
                ? -1 : dev.optInt("volume_percent", -1);
            return new Playing(item.optString("name"), by.toString(), dev == null ? "" : dev.optString("name"),
                o.optBoolean("is_playing"), volume);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Your name for a speaker (names: "Spotify name=Your name" lines), else Spotify's. */
    static String label(String names, String spotifyName) {
        return Scenes.lights(names).getOrDefault(spotifyName, spotifyName);
    }

    /** names with this speaker renamed; an empty name goes back to Spotify's. */
    static String rename(String names, String spotifyName, String name) {
        java.util.Map<String, String> m = Scenes.lights(names); // same "Key=Value" lines as the lights
        String clean = name.replaceAll("[=:\\r\\n]", " ").replaceAll("\\s+", " ").trim();
        if (clean.isEmpty() || clean.equals(spotifyName)) m.remove(spotifyName);
        else m.put(spotifyName, clean);
        StringBuilder s = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (s.length() > 0) s.append('\n');
            s.append(e.getKey()).append('=').append(e.getValue());
        }
        return s.toString();
    }

    static Device find(List<Device> devices, String name) {
        for (Device d : devices) if (d.name.trim().equalsIgnoreCase(name.trim())) return d;
        return null;
    }

    static String transferBody(String deviceId) {
        return "{\"device_ids\":[" + JSONObject.quote(deviceId) + "],\"play\":true}";
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
            return "";
        }
    }
}
