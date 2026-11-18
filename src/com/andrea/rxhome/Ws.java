package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A minimal WebSocket client (RFC 6455) for TVs on the home network (LG webOS, Samsung Tizen): text
 * messages, one connection per use. wss: the TV's self-signed certificate is pinned at pairing (Philips.pinned).
 */
final class Ws implements AutoCloseable {

    static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    /** pin: the TV's certificate (wss), null while pairing: then seenPin[0] gets the one shown. */
    Ws(String url, String pin, String[] seenPin, int timeoutMs) throws IOException {
        URI u = URI.create(url);
        boolean tls = u.getScheme().equals("wss");
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(u.getHost(), port), timeoutMs);
        raw.setSoTimeout(timeoutMs);
        socket = tls ? Philips.pinned(pin, seenPin).createSocket(raw, u.getHost(), port, true) : raw;
        in = new java.io.BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        byte[] k = new byte[16];
        RANDOM.nextBytes(k);
        String key = Base64.getEncoder().encodeToString(k);
        String path = u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
        // No Origin header: LG's TVs refuse connections that send one.
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port + "\r\nUpgrade: websocket\r\n"
            + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String status = line(), expect = accept(key);
        boolean accepted = false;
        for (String h; !(h = line()).isEmpty(); ) {
            if (h.toLowerCase().startsWith("sec-websocket-accept:") && h.substring(21).trim().equals(expect)) accepted = true;
        }
        if (!status.contains(" 101") || !accepted) {
            close();
            throw new IOException("The TV refused the connection (" + status.trim() + ")");
        }
    }

    void send(String text) throws IOException {
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        out.write(frame(1, text.getBytes(StandardCharsets.UTF_8), mask));
        out.flush();
    }

    /** The next text message (answers pings; a close from the TV ends it). */
    String receive(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        while (true) {
            Frame f = read(in);
            if (f.opcode == 1) return new String(f.payload, StandardCharsets.UTF_8);
            if (f.opcode == 8) throw new IOException("The TV closed the connection");
            if (f.opcode == 9) {
                byte[] mask = new byte[4];
                RANDOM.nextBytes(mask);
                out.write(frame(10, f.payload, mask)); // pong
                out.flush();
            }
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // Already closed.
        }
    }

    private String line() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1 && c != '\n'; ) if (c != '\r') b.write(c);
        return b.toString("US-ASCII");
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

    /** A client frame: final, masked (as a client must). */
    static byte[] frame(int opcode, byte[] payload, byte[] mask) {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | opcode);
        int n = payload.length;
        if (n < 126) {
            f.write(0x80 | n);
        } else if (n < 65536) {
            f.write(0x80 | 126);
            f.write(n >>> 8);
            f.write(n);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) ((long) n >>> (8 * i)));
        }
        f.write(mask, 0, 4);
        for (int i = 0; i < n; i++) f.write(payload[i] ^ mask[i % 4]);
        return f.toByteArray();
    }

    /** One whole message from the TV (continuation frames joined; control frames come back on their own). */
    static Frame read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int opcode = -1;
        while (true) {
            int b0 = d.readUnsignedByte(), b1 = d.readUnsignedByte();
            int op = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = d.readUnsignedShort();
            else if (len == 127) len = d.readLong();
            if (len > 16 * 1024 * 1024) throw new IOException("Message too big");
            byte[] mask = (b1 & 0x80) != 0 ? new byte[4] : null;
            if (mask != null) d.readFully(mask);
            byte[] p = new byte[(int) len];
            d.readFully(p);
            if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i % 4];
            if (op >= 8) return new Frame(op, p); // control frames aren't fragmented
            if (op != 0) opcode = op;
            data.write(p, 0, p.length);
            if ((b0 & 0x80) != 0) return new Frame(opcode, data.toByteArray());
        }
    }
}
