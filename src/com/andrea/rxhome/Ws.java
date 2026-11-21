package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.net.ssl.SSLSocketFactory;

/** A minimal WebSocket client (RFC 6455): text messages only, which is all the TVs speak. */
final class Ws {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private Ws(Socket socket) throws IOException {
        this.socket = socket;
        in = new java.io.BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /** ws:// or wss:// (tls = a Pin factory; required for wss). */
    static Ws connect(String url, SSLSocketFactory tls, int timeoutMs) throws IOException {
        URI u = URI.create(url);
        boolean secure = u.getScheme().equals("wss");
        int port = u.getPort() > 0 ? u.getPort() : secure ? 443 : 80;
        Socket s = secure ? tls.createSocket() : new Socket();
        try {
            s.connect(new InetSocketAddress(u.getHost(), port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.setTcpNoDelay(true);
            Ws w = new Ws(s);
            w.handshake(u, port, timeoutMs);
            return w;
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private void handshake(URI u, int port, int timeoutMs) throws IOException {
        byte[] k = new byte[16];
        RANDOM.nextBytes(k);
        String key = Base64.getEncoder().encodeToString(k);
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
            + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        out.write(("GET " + path + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port + "\r\nUpgrade: websocket\r\n"
            + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0 || head.length() > 8192) throw new IOException("The TV closed the connection");
            head.append((char) c);
        }
        if (!head.toString().startsWith("HTTP/1.1 101")) {
            throw new IOException("The TV refused: " + head.substring(0, Math.max(0, head.indexOf("\r\n"))));
        }
        if (!head.toString().toLowerCase().contains("sec-websocket-accept: " + accept(key).toLowerCase())) {
            throw new IOException("Not a WebSocket answer");
        }
    }

    synchronized void send(String text) throws IOException {
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        out.write(frame(text, mask));
        out.flush();
    }

    /** The next text message; answers pings on the way. Throws on close or after timeoutMs. */
    String receive(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        try {
            while (true) {
                int b0 = read(), op = b0 & 0x0f;
                byte[] p = payload();
                if (op == 0x9) {
                    pong(p);
                } else if (op == 0x8) {
                    throw new IOException("The TV closed the connection");
                } else if (op == 0x1 || op == 0x0 || op == 0x2) {
                    msg.write(p, 0, p.length);
                    if ((b0 & 0x80) != 0) return msg.toString("UTF-8");
                }
            }
        } catch (SocketTimeoutException e) {
            throw new IOException("The TV didn't answer in time", e);
        }
    }

    void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // already gone
        }
    }

    private int read() throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("The TV closed the connection");
        return b;
    }

    private byte[] payload() throws IOException {
        int b1 = read();
        long len = b1 & 0x7f;
        if (len == 126) len = (read() << 8) | read();
        else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | read();
        }
        if (len > 16 << 20) throw new IOException("Message too big");
        byte[] mask = (b1 & 0x80) != 0 ? readN(4) : null;
        byte[] p = readN((int) len);
        if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i % 4];
        return p;
    }

    private byte[] readN(int n) throws IOException {
        byte[] b = new byte[n];
        for (int off = 0; off < n; ) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("The TV closed the connection");
            off += r;
        }
        return b;
    }

    private synchronized void pong(byte[] p) throws IOException {
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        out.write(frame(0xA, p, mask));
        out.flush();
    }

    static byte[] frame(String text, byte[] mask) {
        return frame(0x1, text.getBytes(StandardCharsets.UTF_8), mask);
    }

    private static byte[] frame(int op, byte[] p, byte[] mask) {
        ByteArrayOutputStream f = new ByteArrayOutputStream(p.length + 14);
        f.write(0x80 | op);
        if (p.length < 126) {
            f.write(0x80 | p.length);
        } else if (p.length < 65536) {
            f.write(0x80 | 126);
            f.write(p.length >> 8);
            f.write(p.length & 0xff);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) ((long) p.length >> (8 * i)) & 0xff);
        }
        f.write(mask, 0, 4);
        for (int i = 0; i < p.length; i++) f.write(p[i] ^ mask[i % 4]);
        return f.toByteArray();
    }

    static String accept(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
