package com.andrea.rxhome;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * A Sky Q box: its network remote on TCP 49160, as the open-source sky-remote does it. No pairing. Each
 * key is one short connection: the box's handshake echoed back, then the key down and up.
 * (Sky Glass and Sky Stream have no such remote.)
 */
class Sky {

    static final int POWER = 0, SELECT = 1, BACK = 2, CHANNEL_UP = 6, CHANNEL_DOWN = 7, HELP = 9, SEARCH = 10,
        HOME = 11, INFO = 14, TEXT = 15, RED = 32, GREEN = 33, YELLOW = 34, BLUE = 35, PLAY = 64, PAUSE = 65, STOP = 66,
        RECORD = 67, FAST_FORWARD = 69, REWIND = 71, SKY = 241;

    private static final Map<Integer, Integer> KEYS = new HashMap<>();
    static {
        int[][] map = {{19, 16}, {20, 17}, {21, 18}, {22, 19}, {23, SELECT}, {4, BACK}, {3, HOME}, {82, INFO},
            {85, PLAY}, {89, REWIND}, {90, FAST_FORWARD}};
        for (int[] m : map) KEYS.put(m[0], m[1]);
        for (int d = 0; d < 10; d++) KEYS.put(7 + d, 48 + d); // KEYCODE_0..9
    }

    private static final int TIMEOUT_MS = 2000;
    private final String ip;

    Sky(String ip) {
        this.ip = ip;
    }

    /** An Android keycode (touchpad, d-pad buttons) as a Sky key. */
    void key(int keycode) throws IOException {
        press(code(keycode));
    }

    void press(int code) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host(ip), port(ip)), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] buf = new byte[64];
            int echo = 12; // the version string first, then one byte per step
            while (true) {
                int n = in.read(buf);
                if (n < 0) throw new IOException("The Sky box closed the connection");
                if (n < 24) {
                    out.write(buf, 0, Math.min(echo, n));
                    out.flush();
                    echo = 1;
                } else {
                    out.write(command(code, true));
                    out.write(command(code, false));
                    out.flush();
                    return;
                }
            }
        } catch (IOException e) {
            throw new IOException("The Sky box doesn't answer: is it on, and on this Wi-Fi?", e);
        }
    }

    /** True if something listens on its remote port (a Sky Q in standby still does). */
    boolean answers() {
        return Discovery.anyAnswers(java.util.Collections.singletonList(host(ip) + ":" + port(ip)), 1200);
    }

    static int code(int keycode) {
        Integer c = KEYS.get(keycode);
        if (c == null) throw new UnsupportedOperationException("No such key on a Sky box: " + keycode);
        return c;
    }

    static byte[] command(int code, boolean down) {
        return new byte[] {4, (byte) (down ? 1 : 0), 0, 0, 0, 0, (byte) (224 + code / 16), (byte) (code % 16)};
    }

    private static String host(String ip) {
        return ip.contains(":") ? ip.substring(0, ip.indexOf(':')) : ip;
    }

    private static int port(String ip) {
        return ip.contains(":") ? Integer.parseInt(ip.substring(ip.indexOf(':') + 1)) : 49160;
    }
}
