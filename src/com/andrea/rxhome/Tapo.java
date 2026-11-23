package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A Tapo bulb, strip or plug on the LAN, without the Tapo cloud. Logs in with KLAP (current firmware)
 * and falls back to the older RSA "securePassthrough" login.
 */
class Tapo {

    private static final int TIMEOUT = 1500;
    static final String THIRD_PARTY =
        "Turn on Third-Party Compatibility in the Tapo app (Me → Third-Party Services)";
    private final String ip, email, password;
    private Session klap;
    private byte[] ptKey, ptIv;
    private String cookie, token;
    private JSONObject lastInfo;

    Tapo(String ip, String email, String password) {
        this.ip = ip;
        this.email = email;
        this.password = password;
    }

    /** get_device_info's result; "nickname" is decoded from base64 into "name". */
    JSONObject info() throws IOException {
        JSONObject r = call("{\"method\":\"get_device_info\"}");
        try {
            r.put("name", new String(Base64.getDecoder().decode(r.optString("nickname", "")), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | JSONException ignored) {
            // Keep the raw nickname.
        }
        lastInfo = r;
        return r;
    }

    /** Null arguments are left unchanged. Plugs ignore brightness; colour temperature only where supported. */
    void set(Boolean on, Integer brightness, Integer kelvin) throws IOException {
        JSONObject i = lastInfo != null ? lastInfo : info();
        if (!i.has("brightness")) brightness = null;
        call("{\"method\":\"set_device_info\",\"params\":" + setParams(on, brightness, kelvin, i.has("color_temp_range")) + "}");
    }

    /** A colour (hue 0-360, saturation 0-100); also switches the light on. */
    void color(int hue, int saturation) throws IOException {
        call("{\"method\":\"set_device_info\",\"params\":" + colorParams(hue, saturation) + "}");
    }

    static String colorParams(int hue, int saturation) {
        int h = Math.max(0, Math.min(360, hue)), s = Math.max(0, Math.min(100, saturation));
        // color_temp 0 takes the light out of white mode so hue/saturation apply.
        return "{\"device_on\":true,\"hue\":" + h + ",\"saturation\":" + s + ",\"color_temp\":0}";
    }

    /** On, with brightness and white or colour in one command: the bulb fades in once, already right. */
    void turnOn(Integer brightness, Integer kelvin, int[] color) throws IOException {
        JSONObject i = lastInfo != null ? lastInfo : info();
        if (!i.has("brightness")) brightness = null;
        call("{\"method\":\"set_device_info\",\"params\":" + onParams(brightness, kelvin, color, i.has("color_temp_range")) + "}");
    }

    static String onParams(Integer brightness, Integer kelvin, int[] color, boolean hasTemp) {
        StringBuilder s = new StringBuilder("{\"device_on\":true");
        if (brightness != null) s.append(",\"brightness\":").append(Math.max(1, brightness));
        if (color != null) {
            String c = colorParams(color[0], color[1]); // {"device_on":true,"hue":..,"saturation":..,"color_temp":0}
            s.append(',').append(c, c.indexOf("\"hue\""), c.length() - 1);
        } else if (kelvin != null && hasTemp) {
            s.append(",\"color_temp\":").append(kelvin);
        }
        return s.append('}').toString();
    }

    /** True for colour lights (strips, colour bulbs): their info reports a hue. */
    static boolean hasColor(JSONObject info) {
        return info.has("hue");
    }

    static String setParams(Boolean on, Integer brightness, Integer kelvin, boolean hasTemp) {
        StringBuilder s = new StringBuilder("{");
        if (on != null) s.append("\"device_on\":").append(on).append(',');
        if (brightness != null) s.append("\"brightness\":").append(Math.max(1, brightness)).append(',');
        if (kelvin != null && hasTemp) s.append("\"color_temp\":").append(kelvin).append(',');
        if (s.length() > 1) s.setLength(s.length() - 1);
        return s.append('}').toString();
    }

    private synchronized JSONObject call(String json) throws IOException {
        try {
            return attempt(json);
        } catch (RuntimeException e) {
            // Garbled replies (bad base64, bad padding) must grey the card, not kill the background thread.
            klap = null;
            ptKey = null;
            throw new IOException("Tapo sent an unreadable reply");
        }
    }

    private JSONObject attempt(String json) throws IOException {
        boolean hadSession = klap != null || ptKey != null;
        try {
            return result(send(json));
        } catch (IOException e) {
            klap = null;
            ptKey = null;
            // Sessions expire (TIMEOUT cookie): log in again once, but never retry a fresh login.
            if (!hadSession) throw e;
            return result(send(json));
        }
    }

    private static JSONObject result(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            int err = o.optInt("error_code", -1);
            if (err != 0) throw new IOException("Tapo error " + err);
            JSONObject r = o.optJSONObject("result");
            return r != null ? r : new JSONObject();
        } catch (JSONException e) {
            throw new IOException("Tapo sent an unreadable reply");
        }
    }

    private String send(String json) throws IOException {
        if (klap == null && ptKey == null) login();
        if (klap != null) {
            byte[] body = klap.encrypt(json);
            return klap.decrypt(post("/app/request?seq=" + klap.seq, body));
        }
        return passthrough(json);
    }

    private void login() throws IOException {
        byte[] auth = authHash(email, password);
        byte[] local = new byte[16];
        new SecureRandom().nextBytes(local);
        Http r = http("/app/handshake1", local);
        if (r.code == 200 && r.body.length == 48) {
            byte[] remote = Arrays.copyOfRange(r.body, 0, 16);
            if (!Arrays.equals(Arrays.copyOfRange(r.body, 16, 48), sha256(local, remote, auth))) {
                throw new IOException("Wrong Tapo email or password");
            }
            cookie = r.cookie;
            post("/app/handshake2", sha256(remote, local, auth));
            klap = new Session(local, remote, auth);
        } else {
            try {
                passthroughLogin();
            } catch (IOException e) {
                // 2025 firmware speaks TPAP and only allows these older logins with this switch on.
                if (r.code == 403) throw new IOException(THIRD_PARTY);
                throw e;
            }
        }
    }

    // --- securePassthrough (older firmware) ---

    private void passthroughLogin() throws IOException {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(1024);
            KeyPair kp = g.generateKeyPair();
            String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(kp.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
            String hs = new JSONObject().put("method", "handshake")
                .put("params", new JSONObject().put("key", pem).put("requestTimeMils", 0)).toString();
            Http r = http("/app", hs.getBytes(StandardCharsets.UTF_8));
            if (r.code != 200) throw new IOException("Tapo handshake refused (HTTP " + r.code + ")");
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.DECRYPT_MODE, kp.getPrivate());
            byte[] kv = rsa.doFinal(Base64.getDecoder().decode(result(new String(r.body, StandardCharsets.UTF_8)).getString("key")));
            cookie = r.cookie;
            ptKey = Arrays.copyOfRange(kv, 0, 16);
            ptIv = Arrays.copyOfRange(kv, 16, 32);
            token = null;
            String login = new JSONObject().put("method", "login_device").put("params", new JSONObject()
                .put("username", Base64.getEncoder().encodeToString(hexSha1(email).getBytes(StandardCharsets.UTF_8)))
                .put("password", Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)))).toString();
            token = result(passthrough(login)).getString("token");
        } catch (GeneralSecurityException | JSONException e) {
            ptKey = null;
            throw new IOException("Tapo login failed");
        }
    }

    private String passthrough(String json) throws IOException {
        try {
            String req = new JSONObject().put("method", "securePassthrough").put("params", new JSONObject()
                .put("request", Base64.getEncoder().encodeToString(aes(Cipher.ENCRYPT_MODE, ptKey, ptIv, json.getBytes(StandardCharsets.UTF_8))))).toString();
            byte[] out = post(token == null ? "/app" : "/app?token=" + token, req.getBytes(StandardCharsets.UTF_8));
            String resp = result(new String(out, StandardCharsets.UTF_8)).getString("response");
            return new String(aes(Cipher.DECRYPT_MODE, ptKey, ptIv, Base64.getDecoder().decode(resp)), StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IOException("Tapo sent an unreadable reply");
        }
    }

    // --- KLAP ---

    static final class Session {
        private final byte[] key, iv, sig;
        int seq;

        Session(byte[] local, byte[] remote, byte[] auth) {
            key = Arrays.copyOf(sha256("lsk".getBytes(StandardCharsets.US_ASCII), local, remote, auth), 16);
            byte[] ivs = sha256("iv".getBytes(StandardCharsets.US_ASCII), local, remote, auth);
            iv = Arrays.copyOf(ivs, 12);
            seq = ByteBuffer.wrap(ivs, 28, 4).getInt();
            sig = Arrays.copyOf(sha256("ldk".getBytes(StandardCharsets.US_ASCII), local, remote, auth), 28);
        }

        /** Advances seq; returns sha256(sig + seq + ciphertext) followed by the ciphertext. */
        byte[] encrypt(String json) {
            seq++;
            byte[] s = ByteBuffer.allocate(4).putInt(seq).array();
            byte[] ct = aes(Cipher.ENCRYPT_MODE, key, ivFor(s), json.getBytes(StandardCharsets.UTF_8));
            return concat(sha256(sig, s, ct), ct);
        }

        String decrypt(byte[] msg) throws IOException {
            byte[] s = ByteBuffer.allocate(4).putInt(seq).array();
            try {
                return new String(aes(Cipher.DECRYPT_MODE, key, ivFor(s), Arrays.copyOfRange(msg, 32, msg.length)), StandardCharsets.UTF_8);
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw new IOException("Tapo sent an unreadable reply");
            }
        }

        private byte[] ivFor(byte[] s) {
            return concat(iv, s);
        }
    }

    static byte[] authHash(String email, String password) {
        return sha256(sha1(email.getBytes(StandardCharsets.UTF_8)), sha1(password.getBytes(StandardCharsets.UTF_8)));
    }

    // --- helpers ---

    private static final class Http {
        int code;
        byte[] body;
        String cookie;
    }

    private byte[] post(String path, byte[] body) throws IOException {
        Http r = http(path, body);
        if (r.code != 200) throw new IOException("Tapo HTTP " + r.code);
        return r.body;
    }

    private Http http(String path, byte[] body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + path).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT * 2);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        try {
            try (OutputStream o = c.getOutputStream()) {
                o.write(body);
            }
            Http r = new Http();
            r.code = c.getResponseCode();
            String set = c.getHeaderField("Set-Cookie");
            if (set != null) r.cookie = set.split(";")[0];
            InputStream in = r.code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream i = in) {
                    byte[] b = new byte[4096];
                    for (int n; (n = i.read(b)) > 0; ) buf.write(b, 0, n);
                }
            }
            r.body = buf.toByteArray();
            return r;
        } finally {
            c.disconnect();
        }
    }

    static byte[] sha256(byte[]... parts) {
        return digest("SHA-256", parts);
    }

    private static byte[] sha1(byte[] b) {
        return digest("SHA-1", b);
    }

    private static String hexSha1(String s) {
        StringBuilder h = new StringBuilder();
        for (byte x : sha1(s.getBytes(StandardCharsets.UTF_8))) h.append(String.format("%02x", x));
        return h.toString();
    }

    private static byte[] digest(String alg, byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance(alg);
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] aes(int mode, byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
