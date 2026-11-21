package com.andrea.rxhome;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

public class SkyTest {

    static final List<String> got = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** A fake Sky Q: version (12 bytes, echoed back), two short steps (1 byte echoed each), then 24 bytes; then the key. */
    static ServerSocket box() throws Exception {
        ServerSocket ss = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    out.write("SKY 000.001\n".getBytes());
                    out.flush();
                    got.add("echo " + new String(in.readNBytes(12)).trim());
                    out.write(new byte[] {1, 1});
                    out.flush();
                    got.add("echo " + Arrays.toString(in.readNBytes(1)));
                    out.write(new byte[] {0, 0, 0, 0});
                    out.flush();
                    got.add("echo " + Arrays.toString(in.readNBytes(1)));
                    out.write(new byte[24]);
                    out.flush();
                    got.add("key " + Arrays.toString(in.readNBytes(8)) + " " + Arrays.toString(in.readNBytes(8)));
                } catch (Exception e) {
                    // closed
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    public static void main(String[] a) throws Exception {
        // sky-remote's packets: 4, down/up, 0 0 0 0, 224 + code/16, code % 16.
        assert Arrays.equals(Sky.command(1, true), new byte[] {4, 1, 0, 0, 0, 0, (byte) 224, 1});
        assert Arrays.equals(Sky.command(241, false), new byte[] {4, 0, 0, 0, 0, 0, (byte) 239, 1});
        // Android keys on the touchpad and buttons, as Sky codes.
        assert Sky.code(19) == 16 && Sky.code(23) == 1 && Sky.code(4) == 2 && Sky.code(3) == 11 && Sky.code(85) == 64;
        boolean unsupported = false;
        try {
            Sky.code(999);
        } catch (UnsupportedOperationException e) {
            unsupported = true;
        }
        assert unsupported;

        got.clear();
        try (ServerSocket ss = box()) {
            Sky sky = new Sky("127.0.0.1:" + ss.getLocalPort());
            sky.press(Sky.CHANNEL_UP);
            Thread.sleep(100);
            assert got.equals(Arrays.asList("echo SKY 000.001", "echo [1]", "echo [0]",
                "key [4, 1, 0, 0, 0, 0, -32, 6] [4, 0, 0, 0, 0, 0, -32, 6]")) : got;
            assert sky.answers();
        }
        // Nothing there: fails fast with a readable message.
        long t0 = System.currentTimeMillis();
        try {
            new Sky("127.0.0.1:9").press(Sky.CHANNEL_UP);
            throw new AssertionError("no box, no error");
        } catch (java.io.IOException e) {
            assert e.getMessage().contains("Sky") : e.getMessage();
        }
        assert !new Sky("127.0.0.1:9").answers();
        assert System.currentTimeMillis() - t0 < 3000;
    }
}
