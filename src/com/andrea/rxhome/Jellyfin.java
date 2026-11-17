package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Just enough of Jellyfin for the TV shortcuts: Continue watching / Next up with pictures, and a
 * Quick Connect sign-in (as rxhome-tv's launcher does it) to get a token.
 */
final class Jellyfin {

    static final class Item {
        final String id, title, episode, image; // episode: "S2:E5", or "" for a film; image: a picture URL

        Item(String id, String title, String episode, String image) {
            this.id = id;
            this.title = title;
            this.episode = episode;
            this.image = image;
        }

        String label() {
            return episode.isEmpty() ? title : title + " " + episode;
        }
    }

    static final class Start {
        final String secret, code;

        Start(String secret, String code) {
            this.secret = secret;
            this.code = code;
        }
    }

    static final class Login {
        final String token, userId;

        Login(String token, String userId) {
            this.token = token;
            this.userId = userId;
        }
    }

    private final String url, token, userId, deviceId;

    Jellyfin(String url, String token, String userId, String deviceId) {
        this.url = url;
        this.token = token;
        this.userId = userId;
        this.deviceId = deviceId;
    }

    /** Continue watching (up to 20). */
    java.util.List<Item> resumeAll() throws IOException {
        return all(send("GET", url + "/UserItems/Resume?UserId=" + userId + "&Limit=20&MediaTypes=Video", null, url, token, deviceId), url);
    }

    /** Next up (up to 20). */
    java.util.List<Item> nextUpAll() throws IOException {
        return all(send("GET", url + "/Shows/NextUp?UserId=" + userId + "&Limit=20", null, url, token, deviceId), url);
    }

    /** Fetches a picture with the login (some servers want it for images too). */
    byte[] image(String imageUrl) throws IOException {
        return sendBytes(imageUrl, token, deviceId);
    }

    /** Quick Connect, step 1: a code to approve in a signed-in Jellyfin app. */
    static Start initiate(String url, String deviceId) throws IOException {
        Start s = start(send("POST", url + "/QuickConnect/Initiate", "", url, null, deviceId));
        if (s == null) throw new IOException("Quick Connect is off on the server");
        return s;
    }

    /** Quick Connect, step 2 (poll): the login once the code was approved, else null. */
    static Login check(String url, String deviceId, String secret) throws IOException {
        String state = send("GET", url + "/QuickConnect/Connect?Secret=" + secret, null, url, null, deviceId);
        if (!state.contains("\"Authenticated\":true")) return null;
        return login(send("POST", url + "/Users/AuthenticateWithQuickConnect", "{\"Secret\":\"" + secret + "\"}", url, null, deviceId));
    }

    /** Every item of a Resume / NextUp reply, with picture URLs on `server`; empty if unreadable. */
    static java.util.List<Item> all(String json, String server) {
        java.util.List<Item> out = new java.util.ArrayList<>();
        try {
            JSONArray items = new JSONObject(json).getJSONArray("Items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String id = it.getString("Id");
                boolean episode = "Episode".equals(it.optString("Type"));
                out.add(new Item(id, episode ? it.optString("SeriesName") : it.optString("Name"),
                    episode ? "S" + it.optInt("ParentIndexNumber") + ":E" + it.optInt("IndexNumber") : "",
                    server + "/Items/" + id + "/Images/" + (episode ? "Primary" : "Backdrop") + "?maxHeight=240"));
            }
        } catch (Exception e) {
            // Unreadable: nothing to show.
        }
        return out;
    }

    static Start start(String json) {
        try {
            JSONObject o = new JSONObject(json);
            return new Start(o.getString("Secret"), o.getString("Code"));
        } catch (Exception e) {
            return null;
        }
    }

    static Login login(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            return new Login(o.getString("AccessToken"), o.getJSONObject("User").getString("Id"));
        } catch (Exception e) {
            throw new IOException("Jellyfin sent an unreadable sign-in reply");
        }
    }

    private static byte[] sendBytes(String target, String token, String deviceId) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(target).openConnection();
        try {
            h.setConnectTimeout(3000);
            h.setReadTimeout(8000);
            h.setRequestProperty("Authorization", "MediaBrowser Client=\"rxhome\", Device=\"Phone\", DeviceId=\""
                + deviceId + "\", Version=\"1.0\"" + (token == null ? "" : ", Token=\"" + token + "\""));
            if (h.getResponseCode() / 100 != 2) throw new IOException("No picture (HTTP " + h.getResponseCode() + ")");
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                return buf.toByteArray();
            }
        } finally {
            h.disconnect();
        }
    }

    private static String send(String method, String target, String body, String server, String token, String deviceId)
            throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(target).openConnection();
        try {
            h.setRequestMethod(method);
            h.setConnectTimeout(3000);
            h.setReadTimeout(8000);
            h.setRequestProperty("Authorization", "MediaBrowser Client=\"rxhome\", Device=\"Phone\", DeviceId=\""
                + deviceId + "\", Version=\"1.0\"" + (token == null ? "" : ", Token=\"" + token + "\""));
            if (body != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = h.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = h.getResponseCode();
            if (code == 401) throw new IOException("Jellyfin signed rxhome out: sign in again in Settings");
            if (code / 100 != 2) throw new IOException("Jellyfin answered HTTP " + code);
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                return buf.toString("UTF-8");
            }
        } finally {
            h.disconnect();
        }
    }
}
