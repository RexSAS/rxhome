package com.andrea.rxhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Samsung TVs from before Tizen (2010-2015, e.g. the F series): the remote on TCP 55000, as the
 * open-source samsungctl's legacy remote does it. The TV shows "Allow" once for rxhome. These TVs drop
 * off the network when off, so they can't be switched on from the phone, and have no app list.
 */
class SamsungLegacy implements Screen {

    private static final String NAME = "rxhome"; // shown on the TV's prompt; also the id it remembers
    private static final int PAIR_WAIT_MS = 30000, TIMEOUT_MS = 3000;

    private static final Map<Integer, String> KEYS = new HashMap<>();
    static {
        int[] codes = {19, 20, 21, 22, 23, 3, 4, 82, 85, 89, 90, 24, 25, 164, 178};
        String[] names = {"UP", "DOWN", "LEFT", "RIGHT", "ENTER", "CONTENTS", "RETURN", "TOOLS", "PLAY", "REWIND", "FF",
            "VOLUP", "VOLDOWN", "MUTE", "SOURCE"};
        for (int i = 0; i < codes.length; i++) KEYS.put(codes[i], "KEY_" + names[i]);
    }

    private final String ip, id;
    private Socket socket;

    /** id: the saved uuid (unused for commands: another home's TV would just ask to Allow rxhome). */
    SamsungLegacy(String ip, String id) {
        this.ip = ip;
        this.id = id;
    }

    /** Asks the TV to allow rxhome: the user chooses Allow on the TV (up to 30 s). */
    static void pair(String ip) throws IOException {
        try (Socket s = open(ip, PAIR_WAIT_MS)) {
            // open() did the handshake: allowed.
        }
    }

    @Override
    public synchronized void key(int keycode) throws IOException {
        send(keyName(keycode));
    }

    @Override
    public void setVolume(int level) {
        throw new UnsupportedOperationException("This Samsung can't set an exact volume");
    }

    // shortcut: KEY_MUTE toggles (no "mute on" in this protocol).
    @Override
    public void mute() throws IOException {
        key(164);
    }

    @Override
    public synchronized void powerOff() throws IOException {
        send("KEY_POWEROFF");
    }

    @Override
    public void powerOn() throws IOException {
        throw new IOException("This Samsung switches on only with its own remote");
    }

    /** Answering on 55000 = on (these TVs leave the network when off); else unknown. */
    @Override
    public Boolean isOn() {
        return Discovery.anyAnswers(java.util.Collections.singletonList(host(ip) + ":" + port(ip)), 1200) ? Boolean.TRUE : null;
    }

    @Override
    public List<String[]> apps() {
        throw new UnsupportedOperationException("This Samsung can't list its apps");
    }

    @Override
    public void launch(String appId) {
        throw new UnsupportedOperationException("This Samsung can't open apps");
    }

    @Override
    public boolean can(String what) {
        return !what.equals("volume") && !what.equals("apps");
    }

    /** Sends a key on the open connection; once more on a fresh one if it had dropped. */
    private void send(String key) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                if (socket == null) socket = open(ip, TIMEOUT_MS);
                OutputStream out = socket.getOutputStream();
                out.write(keyPacket(key));
                out.flush();
                read(socket.getInputStream(), false);
                return;
            } catch (IOException e) {
                if (socket != null) socket.close();
                socket = null;
                if (attempt == 1 || e.getMessage() != null && e.getMessage().contains("said no")) throw e;
            }
        }
    }

    private static Socket open(String ip, int waitMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host(ip), port(ip)), TIMEOUT_MS);
            s.setSoTimeout(waitMs);
            s.setTcpNoDelay(true);
            s.getOutputStream().write(authPacket());
            s.getOutputStream().flush();
            read(s.getInputStream(), true);
            s.setSoTimeout(TIMEOUT_MS);
            return s;
        } catch (IOException e) {
            s.close();
            if (e instanceof java.net.SocketTimeoutException && waitMs == PAIR_WAIT_MS) {
                throw new IOException("No answer on the TV: tap Add again and choose Allow");
            }
            throw e;
        }
    }

    /** One answer (skipping "waiting for the user" ones); throws if the TV said no. */
    private static void read(InputStream in, boolean handshake) throws IOException {
        while (true) {
            byte[] head = readN(in, 3);
            readN(in, (head[1] & 0xff) | (head[2] & 0xff) << 8); // the TV's app name
            byte[] len = readN(in, 2);
            byte[] r = readN(in, (len[0] & 0xff) | (len[1] & 0xff) << 8);
            if (r.length == 0) throw new IOException("The TV closed the connection");
            if (r[0] == 0x0a) continue; // the prompt is showing: keep waiting
            if (r[0] == 0x65 || r.length == 4 && r[0] == 0x64 && r[2] == 0) {
                throw new IOException(handshake ? "The TV said no. Tap Add again and choose Allow" : "The TV said no");
            }
            return; // 64 00 01 00 = allowed, 00 00 00 00 = key accepted
        }
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        for (int off = 0; off < n; ) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("The TV closed the connection");
            off += r;
        }
        return b;
    }

    static String keyName(int keycode) {
        String k = KEYS.get(keycode);
        if (k == null) throw new UnsupportedOperationException("No such key on this Samsung: " + keycode);
        return k;
    }

    static byte[] authPacket() {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0x64);
        p.write(0);
        for (int i = 0; i < 3; i++) string(p, b64(NAME)); // description, id, name
        return packet(p.toByteArray());
    }

    static byte[] keyPacket(String key) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0);
        p.write(0);
        p.write(0);
        string(p, b64(key));
        return packet(p.toByteArray());
    }

    private static byte[] packet(byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0);
        o.write(0);
        o.write(0);
        string(o, payload);
        return o.toByteArray();
    }

    /** samsungctl's _serialize_string: one length byte, a zero, the bytes. */
    private static void string(ByteArrayOutputStream o, byte[] s) {
        o.write(s.length);
        o.write(0);
        o.write(s, 0, s.length);
    }

    private static byte[] b64(String s) {
        return Base64.getEncoder().encode(s.getBytes(StandardCharsets.UTF_8));
    }

    /** The TV's lasting id: the uuid of its remote-control receiver (asked directly, see Discovery). */
    static String uuid(String ip) {
        return Discovery.upnpUuid(host(ip), "urn:samsung.com:device:RemoteControlReceiver:1");
    }

    private static String host(String ip) {
        return ip.contains(":") ? ip.substring(0, ip.indexOf(':')) : ip;
    }

    private static int port(String ip) {
        return ip.contains(":") ? Integer.parseInt(ip.substring(ip.indexOf(':') + 1)) : 55000;
    }
}
