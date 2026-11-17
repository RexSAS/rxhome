package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.json.JSONObject;

/**
 * The Philips TV's own remote API (JointSPACE v6, HTTPS 1926, digest login after a one-time PIN pairing),
 * as the open-source pylips does it. Volume, mute, standby: things the FireStick can't do.
 * The TV's certificate is self-signed, so it's pinned at pairing (SHA-256) and only that one is accepted after.
 */
class Philips {

    // pylips' shared pairing key (JointSPACE "secret_key").
    private static final String SECRET =
        "JCqdN5AcnAHgJYseUn7ER5k3qgtemfUvMRghQpTfTZq7Cvv8EPQPqfz6dDxPQPSu4gKFPWkJGw32zyASgJkHwCjU";

    static final class Pairing {
        final String deviceId, authKey, certPin;
        final long timestamp;

        Pairing(String deviceId, String authKey, long timestamp, String certPin) {
            this.deviceId = deviceId;
            this.authKey = authKey;
            this.timestamp = timestamp;
            this.certPin = certPin;
        }
    }

    private final String ip, user, pass, certPin;

    Philips(String ip, String user, String pass, String certPin) {
        this.ip = ip;
        this.user = user;
        this.pass = pass;
        this.certPin = certPin;
    }

    /** E.g. "VolumeUp", "VolumeDown", "Mute", "Standby". */
    void key(String key) throws IOException {
        request("POST", "/6/input/key", "{\"key\":\"" + key + "\"}", ip, user, pass, certPin, null);
    }

    /** True when the TV is on (not standby). In deep standby it's off the network: IOException. */
    boolean isOn() throws IOException {
        String r = request("GET", "/6/powerstate", null, ip, user, pass, certPin, null);
        return r.replace(" ", "").contains("\"powerstate\":\"On\"");
    }

    void power(boolean on) throws IOException {
        request("POST", "/6/powerstate", "{\"powerstate\":\"" + (on ? "On" : "Standby") + "\"}", ip, user, pass, certPin, null);
    }

    /** The TV's MAC on the interface we talk to, for Wake-on-LAN; null if unknown. */
    String mac() throws IOException {
        return macFor(request("GET", "/6/network/devices", null, ip, user, pass, certPin, null), ip);
    }

    static String macFor(String json, String ip) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            String any = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject d = a.optJSONObject(i);
                if (d == null || d.optString("mac").isEmpty()) continue;
                if (ip.equals(d.optString("ip"))) return d.getString("mac");
                if (any == null) any = d.getString("mac");
            }
            return any;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** Switches the TV on from standby (needs "Switch on with Wi-Fi" on the TV). */
    static void wake(String mac) throws IOException {
        byte[] p = magicPacket(mac);
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.setBroadcast(true);
            java.net.InetAddress all = java.net.InetAddress.getByName("255.255.255.255");
            for (int port : new int[] {9, 7}) s.send(new java.net.DatagramPacket(p, p.length, all, port));
        }
    }

    static byte[] magicPacket(String mac) {
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) throw new IllegalArgumentException("Not a MAC address: " + mac);
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        byte[] p = new byte[102];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xff;
        for (int i = 0; i < 16; i++) System.arraycopy(m, 0, p, 6 + 6 * i, 6);
        return p;
    }

    /** True if the TV answers and wants a PIN pairing (read-only, no login). */
    static boolean needsPairing(String ip) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":1925/6/system").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            try (InputStream in = c.getInputStream()) {
                return read(in).contains("digest_auth_pairing");
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** Step 1: the TV shows a PIN. Pins the certificate it presents. */
    static Pairing pairRequest(String ip) throws IOException {
        String id = randomId();
        String[] seen = new String[1];
        String body = "{\"scope\":[\"read\",\"write\",\"control\"],\"device\":" + device(id) + "}";
        String reply = request("POST", "/6/pair/request", body, ip, null, null, null, seen);
        try {
            JSONObject o = new JSONObject(reply);
            if (!"SUCCESS".equals(o.optString("error_id"))) throw new IOException("TV refused pairing: " + o.optString("error_id"));
            return new Pairing(id, o.getString("auth_key"), o.getLong("timestamp"), seen[0]);
        } catch (org.json.JSONException e) {
            throw new IOException("TV sent an unreadable pairing reply");
        }
    }

    /** Step 2: the PIN the TV shows. After this, deviceId / authKey / certPin are the login. */
    static void pairGrant(String ip, Pairing p, String pin) throws IOException {
        String auth = "{\"auth_AppId\":\"1\",\"pin\":\"" + pin.trim() + "\",\"auth_timestamp\":" + p.timestamp
            + ",\"auth_signature\":\"" + signature(p.timestamp, pin.trim()) + "\"}";
        request("POST", "/6/pair/grant", "{\"auth\":" + auth + ",\"device\":" + device(p.deviceId) + "}",
            ip, p.deviceId, p.authKey, p.certPin, null);
    }

    private static String device(String id) {
        return "{\"device_name\":\"rxhome\",\"device_os\":\"Android\",\"app_name\":\"rxhome\",\"type\":\"native\","
            + "\"app_id\":\"app.id\",\"id\":\"" + id + "\"}";
    }

    static String signature(long timestamp, String pin) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA1"));
            byte[] d = mac.doFinal((timestamp + pin).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hex(d).getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- HTTPS with digest login ---

    // Speed: the TV is slow at TLS, so connections are kept alive (one shared socket factory and hostname
    // check per TV, replies read to the end, never disconnect()) and the digest challenge is remembered,
    // so a key is one request on an open connection instead of a handshake plus two requests.
    private static final javax.net.ssl.HostnameVerifier ANY_NAME = (h, s) -> true; // identity = pinned cert
    private static final Map<String, javax.net.ssl.SSLSocketFactory> FACTORIES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> CHALLENGES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, java.util.concurrent.atomic.AtomicInteger> COUNTS = new java.util.concurrent.ConcurrentHashMap<>();

    private static String request(String method, String path, String body, String ip, String user, String pass,
            String pin, String[] seenPin) throws IOException {
        String login = ip + "|" + user;
        Map<String, String> known = user == null ? null : CHALLENGES.get(login);
        HttpURLConnection c = open(method, path, ip, pin, seenPin);
        if (known != null) c.setRequestProperty("Authorization", authorize(login, user, pass, method, path, known, false));
        int code = send(c, body);
        if (code == 401 && user != null) {
            Map<String, String> ch = challenge(c.getHeaderField("WWW-Authenticate"));
            drain(c, code);
            CHALLENGES.put(login, ch);
            c = open(method, path, ip, pin, seenPin);
            c.setRequestProperty("Authorization", authorize(login, user, pass, method, path, ch, true));
            code = send(c, body);
        }
        String reply = drain(c, code);
        if (code == 401) throw new IOException("The TV doesn't know rxhome any more: pair again in Settings");
        if (code >= 400) throw new IOException("TV answered HTTP " + code);
        return reply;
    }

    private static String authorize(String login, String user, String pass, String method, String path,
            Map<String, String> ch, boolean fresh) {
        java.util.concurrent.atomic.AtomicInteger n = COUNTS.computeIfAbsent(login, k -> new java.util.concurrent.atomic.AtomicInteger());
        if (fresh) n.set(0);
        byte[] cn = new byte[8];
        new SecureRandom().nextBytes(cn);
        return digest(user, pass, method, path, ch, hex(cn), n.incrementAndGet());
    }

    /** Reads the reply to the end (so the connection can be reused) and returns it. */
    private static String drain(HttpURLConnection c, int code) throws IOException {
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        if (in == null) return "";
        try (InputStream i = in) {
            return read(i);
        }
    }

    private static HttpURLConnection open(String method, String path, String ip, String pin, String[] seenPin) throws IOException {
        String host = ip.contains(":") ? ip : ip + ":1926";
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://" + host + path).openConnection();
        c.setConnectTimeout(1500);
        c.setReadTimeout(4000);
        c.setRequestMethod(method);
        c.setRequestProperty("Content-Type", "application/json");
        if (pin != null) {
            javax.net.ssl.SSLSocketFactory f = FACTORIES.get(pin);
            if (f == null) FACTORIES.put(pin, f = pinned(pin, null));
            c.setSSLSocketFactory(f);
        } else {
            c.setSSLSocketFactory(pinned(null, seenPin)); // pairing: record the certificate
        }
        c.setHostnameVerifier(ANY_NAME);
        return c;
    }

    private static int send(HttpURLConnection c, String body) throws IOException {
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return c.getResponseCode();
    }

    /** Accepts only the certificate with this SHA-256 (or, while pairing, records whichever is shown). */
    private static javax.net.ssl.SSLSocketFactory pinned(String pin, String[] seenPin) throws IOException {
        TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                throw new CertificateException("not a server");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                String got;
                try {
                    got = hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new CertificateException(e);
                }
                if (pin == null && seenPin != null) seenPin[0] = got;
                else if (!got.equals(pin)) throw new CertificateException("Not the paired TV");
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {tm}, null);
            return ctx.getSocketFactory();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    static Map<String, String> challenge(String header) {
        Map<String, String> m = new HashMap<>();
        if (header == null) return m;
        Matcher x = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))").matcher(header);
        while (x.find()) m.put(x.group(1).toLowerCase(), x.group(2) != null ? x.group(2) : x.group(3));
        return m;
    }

    /** RFC 2617 Authorization header (qop=auth when offered, else RFC 2069). */
    static String digest(String user, String pass, String method, String uri, Map<String, String> ch, String cnonce, int nc) {
        String realm = ch.getOrDefault("realm", ""), nonce = ch.getOrDefault("nonce", "");
        String ha1 = md5(user + ":" + realm + ":" + pass), ha2 = md5(method + ":" + uri);
        boolean qop = ch.containsKey("qop") && ("," + ch.get("qop").replace(" ", "") + ",").contains(",auth,");
        String ncs = String.format("%08x", nc);
        String resp = qop ? md5(ha1 + ":" + nonce + ":" + ncs + ":" + cnonce + ":auth:" + ha2) : md5(ha1 + ":" + nonce + ":" + ha2);
        StringBuilder h = new StringBuilder("Digest username=\"" + user + "\", realm=\"" + realm + "\", nonce=\"" + nonce
            + "\", uri=\"" + uri + "\", response=\"" + resp + "\", algorithm=MD5");
        if (qop) h.append(", qop=auth, nc=").append(ncs).append(", cnonce=\"").append(cnonce).append('"');
        if (ch.containsKey("opaque")) h.append(", opaque=\"").append(ch.get("opaque")).append('"');
        return h.toString();
    }

    private static String md5(String s) {
        try {
            return hex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomId() {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom r = new SecureRandom();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 16; i++) s.append(abc.charAt(r.nextInt(abc.length())));
        return s.toString();
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
        return buf.toString("UTF-8");
    }
}
