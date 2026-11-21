package com.andrea.rxhome;

import java.util.Map;

public class PhilipsTest {

    /**
     * A fake TV: HTTPS with a self-signed certificate and digest login, counting TCP connections and
     * 401 challenges. Keys must reuse one connection and answer the challenge once, not per key.
     */
    static void speed() throws Exception {
        java.io.File ks = new java.io.File("build/test/tv.p12");
        if (!ks.exists()) {
            Process k = new ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-keystore",
                ks.getPath(), "-storetype", "PKCS12", "-storepass", "secret", "-keypass", "secret", "-alias", "tv",
                "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=tv", "-validity", "30").inheritIO().start();
            assert k.waitFor() == 0;
        }
        java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
        try (java.io.FileInputStream in = new java.io.FileInputStream(ks)) {
            store.load(in, "secret".toCharArray());
        }
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance("SunX509");
        kmf.init(store, "secret".toCharArray());
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        StringBuilder pin = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(store.getCertificate("tv").getEncoded())) {
            pin.append(String.format("%02x", b));
        }

        java.util.Set<Integer> ports = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        int[] challenges = {0}, requests = {0};
        String[] nonce = {"n1"};
        com.sun.net.httpserver.HttpsServer tv = com.sun.net.httpserver.HttpsServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        tv.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ctx));
        tv.createContext("/", x -> {
            ports.add(x.getRemoteAddress().getPort());
            requests[0]++;
            x.getRequestBody().readAllBytes();
            String auth = x.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.contains("nonce=\"" + nonce[0] + "\"")) {
                challenges[0]++;
                x.getResponseHeaders().add("WWW-Authenticate", "Digest realm=\"XTV\", qop=\"auth\", nonce=\"" + nonce[0] + "\"");
                x.sendResponseHeaders(401, -1);
            } else {
                byte[] ok = "{}".getBytes("UTF-8");
                x.sendResponseHeaders(200, ok.length);
                x.getResponseBody().write(ok);
            }
            x.close();
        });
        tv.start();
        try {
            String host = "127.0.0.1:" + tv.getAddress().getPort();
            for (int i = 0; i < 10; i++) new Philips(host, "dev", "key", pin.toString()).key("VolumeUp");
            assert challenges[0] == 1 : challenges[0] + " login challenges for 10 keys";
            assert requests[0] == 11 : requests[0] + " requests for 10 keys";
            assert ports.size() == 1 : ports.size() + " connections for 10 keys";
            // The TV changes its nonce: one new challenge, answered, and the key still goes through.
            nonce[0] = "n2";
            new Philips(host, "dev", "key", pin.toString()).key("VolumeDown");
            assert challenges[0] == 2 : challenges[0];
        } finally {
            tv.stop(0);
        }
    }
    // Reference values from python3 hmac/hashlib, following pylips (JointSPACE v6 pairing) and RFC 2617 digest.
    public static void main(String[] a) throws Exception {
        // As a Screen: Android keycodes map to JointSPACE key names.
        java.util.List<String> keys = new java.util.ArrayList<>();
        Philips screen = new Philips("x", "u", "p", "c", null) {
            @Override void key(String k) { keys.add(k); }
        };
        screen.key(19);
        screen.key(23);
        screen.key(178);
        screen.powerOff();
        assert keys.equals(java.util.Arrays.asList("CursorUp", "Confirm", "Source", "Standby")) : keys;
        boolean unsupported = false;
        try {
            screen.key(999);
        } catch (UnsupportedOperationException e) {
            unsupported = true;
        }
        assert unsupported;
        assert screen.isOn() == null && !screen.can("apps") && screen.can("volume");

        assert Philips.signature(1700000000L, "4321").equals("ZWUxMDA3MTYyMTAzMDIwYjFjMzhmODBmODhlNGY1ZWI3NjdkODM0Zg==")
            : Philips.signature(1700000000L, "4321");

        Map<String, String> ch = Philips.challenge("Digest realm=\"XTV\", qop=\"auth,auth-int\", nonce=\"abc123\", opaque=\"op\"");
        assert ch.get("realm").equals("XTV") && ch.get("nonce").equals("abc123") && ch.get("qop").equals("auth,auth-int");
        assert ch.get("opaque").equals("op");

        String h = Philips.digest("dev123", "authkey", "POST", "/6/input/key", ch, "0a4f113b", 1);
        assert h.contains("response=\"49c21ed3a3e3851b91100ab5e639c7de\"") : h;
        assert h.contains("qop=auth") && h.contains("nc=00000001") && h.contains("cnonce=\"0a4f113b\"") && h.contains("opaque=\"op\"") : h;
        // Servers that don't send qop get the RFC 2069 form.
        String old = Philips.digest("dev123", "authkey", "POST", "/6/input/key", Philips.challenge("Digest realm=\"XTV\", nonce=\"abc123\""), "x", 1);
        assert old.contains("response=\"dbf4191d1d2b07b4ad55795a65365fcd\"") && !old.contains("qop") : old;

        // Wake-on-LAN: 6 × 0xFF, then the MAC 16 times (any separator, any case).
        byte[] wol = Screen.magicPacket("aa:BB-cc:dd:ee:FF");
        assert wol.length == 102 && wol[0] == (byte) 0xff && wol[5] == (byte) 0xff;
        assert wol[6] == (byte) 0xaa && wol[11] == (byte) 0xff && wol[96] == (byte) 0xaa && wol[101] == (byte) 0xff;
        try {
            Screen.magicPacket("nonsense");
            throw new AssertionError("bad MAC accepted");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        // The TV's MAC from GET /6/network/devices: the interface with the address we talk to.
        String net = "[{\"type\":\"Ethernet\",\"mac\":\"11:22:33:44:55:66\",\"ip\":\"\"},"
            + "{\"type\":\"Wifi\",\"mac\":\"aa:bb:cc:dd:ee:ff\",\"ip\":\"192.168.1.221\"}]";
        assert Philips.macFor(net, "192.168.1.221").equals("aa:bb:cc:dd:ee:ff") : Philips.macFor(net, "192.168.1.221");
        assert Philips.macFor("[]", "192.168.1.221") == null;

        // Volume on the TV's API: a level (unmutes), or mute keeping the level.
        assert Philips.volumeBody(20, false).equals("{\"muted\":false,\"current\":20}");
        assert Philips.volumeBody(6, true).equals("{\"muted\":true,\"current\":6}");
        assert Philips.currentVolume("{\"muted\": false,\"current\": 6,\"min\": 0,\"max\": 60}") == 6;

        speed();

        // Live, read-only (RXHOME_PHILIPS=192.168.1.221): the TV answers and wants digest pairing.
        String ip = System.getenv("RXHOME_PHILIPS");
        if (ip != null) {
            assert Philips.needsPairing(ip) : "expected digest_auth_pairing";
        }
    }
}
