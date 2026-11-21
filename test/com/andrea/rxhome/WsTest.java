package com.andrea.rxhome;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class WsTest {

    /** A TLS server context with a fresh self-signed cert; [1] = its SHA-256 pin. */
    static Object[] tls() throws Exception {
        java.io.File ks = new java.io.File("build/test/ws.p12");
        if (!ks.exists()) {
            Process k = new ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-keystore",
                ks.getPath(), "-storetype", "PKCS12", "-storepass", "secret", "-keypass", "secret", "-alias", "tv",
                "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=tv", "-validity", "30").start();
            k.getInputStream().readAllBytes();
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
        return new Object[] {ctx, pin.toString()};
    }

    /** Fake WebSocket server: upgrade, then for each text frame received, reply with what `reply` says. */
    interface Reply { String to(String text); }

    static final java.util.List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    static ServerSocket server(javax.net.ssl.SSLContext tls, String greeting, boolean pingFirst, Reply reply) throws Exception {
        ServerSocket ss = tls == null ? new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
            : tls.getServerSocketFactory().createServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    new Thread(() -> serve(s, greeting, pingFirst, reply)).start();
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    static void serve(Socket s, String greeting, boolean pingFirst, Reply reply) {
        try (Socket sock = s) {
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) head.append((char) in.read());
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Sec-WebSocket-Key: (\\S+)").matcher(head);
            assert m.find();
            seen.add("GET " + head.substring(4, head.indexOf(" HTTP")));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + Ws.accept(m.group(1)) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            if (pingFirst) out.write(new byte[] {(byte) 0x89, 0});
            if (greeting != null) out.write(unmasked(greeting));
            out.flush();
            while (true) {
                int b0 = in.read();
                if (b0 < 0) return;
                int b1 = in.read(), len = b1 & 0x7f;
                if (len == 126) len = (in.read() << 8) | in.read();
                byte[] mask = in.readNBytes(4), p = in.readNBytes(len);
                for (int i = 0; i < p.length; i++) p[i] ^= mask[i % 4];
                int op = b0 & 0x0f;
                if (op == 0xA) { seen.add("pong"); continue; }
                if (op == 0x8) return;
                String r = reply.to(new String(p, StandardCharsets.UTF_8));
                seen.add("got " + new String(p, StandardCharsets.UTF_8));
                if (r != null) { out.write(unmasked(r)); out.flush(); }
            }
        } catch (Exception e) {
            // client gone
        }
    }

    static byte[] unmasked(String text) {
        byte[] p = text.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x81);
        if (p.length < 126) o.write(p.length);
        else { o.write(126); o.write(p.length >> 8); o.write(p.length & 0xff); }
        o.write(p, 0, p.length);
        return o.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        // RFC 6455 examples.
        assert Ws.accept("dGhlIHNhbXBsZSBub25jZQ==").equals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
        byte[] f = Ws.frame("Hello", new byte[] {0x37, (byte) 0xfa, 0x21, 0x3d});
        byte[] want = {(byte) 0x81, (byte) 0x85, 0x37, (byte) 0xfa, 0x21, 0x3d, 0x7f, (byte) 0x9f, 0x4d, 0x51, 0x58};
        assert java.util.Arrays.equals(f, want) : java.util.Arrays.toString(f);
        byte[] big = Ws.frame("x".repeat(200), new byte[4]);
        assert big[1] == (byte) (0x80 | 126) && (big[2] & 0xff) == 0 && (big[3] & 0xff) == 200;

        // Echo over plain ws, with a ping first that must be answered.
        seen.clear();
        try (ServerSocket ss = server(null, null, true, t -> t)) {
            Ws w = Ws.connect("ws://127.0.0.1:" + ss.getLocalPort() + "/x?y=1", null, 2000);
            w.send("hi");
            assert w.receive(2000).equals("hi");
            w.close();
            Thread.sleep(100);
            assert seen.contains("pong") : seen;
            assert seen.contains("GET /x?y=1") : seen;
        }

        // wss: only the pinned certificate is accepted; capture reports it.
        Object[] tls = tls();
        String pin = (String) tls[1];
        try (ServerSocket ss = server((javax.net.ssl.SSLContext) tls[0], "hello", false, t -> null)) {
            String url = "wss://127.0.0.1:" + ss.getLocalPort() + "/";
            boolean refused = false;
            try {
                Ws.connect(url, Pin.factory("00".repeat(32), null), 2000);
            } catch (java.io.IOException e) {
                refused = true;
            }
            assert refused : "a wrong pin must be refused";
            assert Pin.capture("127.0.0.1", ss.getLocalPort()).equals(pin);
            Ws w = Ws.connect(url, Pin.factory(pin, null), 2000);
            assert w.receive(2000).equals("hello");
            w.close();
        }

        // Timeout: nothing arrives.
        try (ServerSocket ss = server(null, null, false, t -> null)) {
            Ws w = Ws.connect("ws://127.0.0.1:" + ss.getLocalPort() + "/", null, 2000);
            boolean timedOut = false;
            try {
                w.receive(200);
            } catch (java.io.IOException e) {
                timedOut = true;
            }
            assert timedOut;
            w.close();
        }
    }
}
