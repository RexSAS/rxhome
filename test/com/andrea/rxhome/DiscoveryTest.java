package com.andrea.rxhome;

import java.io.File;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;

public class DiscoveryTest {
    public static void main(String[] a) throws Exception {
        // The /24 around the phone's address, without the phone itself.
        List<String> hosts = Discovery.subnet("192.168.1.57");
        assert hosts.size() == 253 && hosts.get(0).equals("192.168.1.1") && hosts.get(252).equals("192.168.1.254");
        assert !hosts.contains("192.168.1.57");

        // Port scan: an open port is found, a closed one isn't, quickly.
        try (ServerSocket open = new ServerSocket(0)) {
            long t0 = System.currentTimeMillis();
            assert Discovery.open(Arrays.asList("127.0.0.1"), open.getLocalPort(), 800).equals(Arrays.asList("127.0.0.1"));
            assert Discovery.open(Arrays.asList("127.0.0.1"), 9, 800).isEmpty();
            assert System.currentTimeMillis() - t0 < 3000;
        }

        // "Is anything at home there?": any one of several host:port answering, quickly.
        try (ServerSocket open = new ServerSocket(0)) {
            long t0 = System.currentTimeMillis();
            assert Discovery.anyAnswers(Arrays.asList("127.0.0.1:9", "127.0.0.1:" + open.getLocalPort()), 800);
            assert !Discovery.anyAnswers(Arrays.asList("127.0.0.1:9", "192.168.1.250:80"), 800);
            assert !Discovery.anyAnswers(Arrays.<String>asList(), 800);
            assert System.currentTimeMillis() - t0 < 3000 : System.currentTimeMillis() - t0;
        }

        // Identifying what answers: Jellyfin and the Philips by their own info pages.
        com.sun.net.httpserver.HttpServer fake = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/System/Info/Public", x -> reply(x, "{\"ProductName\":\"Jellyfin Server\"}"));
        fake.createContext("/6/system", x -> reply(x, "{\"name\":\"55OLED770/12\",\"featuring\":{}}"));
        fake.start();
        String base = "127.0.0.1:" + fake.getAddress().getPort();
        assert Discovery.isJellyfin("http://" + base);
        assert Discovery.isPhilips(base);
        assert !Discovery.isJellyfin("http://127.0.0.1:9");
        fake.stop(0);

        // The FireStick: only one that already trusts our key counts; a scan never offers the key.
        String host = System.getenv("RXHOME_ADB");
        File priv = new File(System.getProperty("user.home"), ".android/adbkey");
        if (host != null && priv.exists()) {
            assert Discovery.isOurFireStick(host, AdbTest.pem(priv));
            long t0 = System.currentTimeMillis();
            assert !Discovery.isOurFireStick(host, Adb.generateKey()); // no prompt on the TV, no 60 s wait
            assert System.currentTimeMillis() - t0 < 3000;
        }

        // Live (RXHOME_SCAN set): the real network.
        if (System.getenv("RXHOME_SCAN") != null && priv.exists()) {
            Discovery.Found f = Discovery.find("192.168.1.57", AdbTest.pem(priv));
            System.out.println("  live: server=" + f.server);
            assert "http://192.168.1.166:8096".equals(f.server);
        }
    }

    static void reply(com.sun.net.httpserver.HttpExchange x, String body) throws java.io.IOException {
        byte[] b = body.getBytes("UTF-8");
        x.sendResponseHeaders(200, b.length);
        x.getResponseBody().write(b);
        x.close();
    }
}
