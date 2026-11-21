package com.andrea.rxhome;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

public class SamsungLegacyTest {

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    /** One TV answer: 3-byte header with the name length, the name, then the response. */
    static byte[] answer(byte[] response) {
        byte[] name = "iapp.samsung".getBytes();
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0);
        o.write(name.length);
        o.write(0);
        o.write(name, 0, name.length);
        o.write(response.length);
        o.write(0);
        o.write(response, 0, response.length);
        return o.toByteArray();
    }

    static final byte[] GRANTED = {0x64, 0, 1, 0}, DENIED = {0x64, 0, 0, 0}, WAIT = {0x0a, 0, 0, 0}, OK = {0, 0, 0, 0};

    static final List<String> got = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** A fake F-series Samsung: answers the handshake with `auth` (in order), then OK to every key. */
    static ServerSocket tv(byte[]... auth) throws Exception {
        ServerSocket ss = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    boolean first = true;
                    while (true) {
                        // 00, app name (2-byte length + bytes, empty here), payload (2-byte length + bytes)
                        byte[] head = in.readNBytes(3);
                        if (head.length < 3) break;
                        byte[] app = in.readNBytes((head[1] & 0xff) | (head[2] & 0xff) << 8);
                        byte[] len = in.readNBytes(2);
                        byte[] body = in.readNBytes((len[0] & 0xff) | (len[1] & 0xff) << 8);
                        got.add(hex(head) + hex(app) + hex(len) + hex(body));
                        if (first) {
                            for (byte[] r : auth) out.write(answer(r));
                            first = false;
                        } else {
                            out.write(answer(OK));
                        }
                        out.flush();
                    }
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
        // Byte for byte what samsungctl's legacy remote sends (name, id and description "rxhome").
        assert hex(SamsungLegacy.authPacket()).equals(
            "000000200064000800636e686f6232316c0800636e686f6232316c0800636e686f6232316c") : hex(SamsungLegacy.authPacket());
        assert hex(SamsungLegacy.keyPacket("KEY_UP")).equals("0000000d0000000008005330565a58315651");
        boolean unsupported = false;
        try {
            SamsungLegacy.keyName(999);
        } catch (UnsupportedOperationException e) {
            unsupported = true;
        }
        assert unsupported;
        assert SamsungLegacy.keyName(4).equals("KEY_RETURN") && SamsungLegacy.keyName(178).equals("KEY_SOURCE");

        // The user is asked on the TV (wait…), then allows.
        try (ServerSocket ss = tv(WAIT, GRANTED)) {
            SamsungLegacy.pair("127.0.0.1:" + ss.getLocalPort());
        }
        // pairDenied
        try (ServerSocket ss = tv(DENIED)) {
            boolean denied = false;
            try {
                SamsungLegacy.pair("127.0.0.1:" + ss.getLocalPort());
            } catch (java.io.IOException e) {
                denied = e.getMessage().contains("said no");
            }
            assert denied;
        }

        // Keys: one connection, handshake once, then each key.
        got.clear();
        try (ServerSocket ss = tv(GRANTED)) {
            SamsungLegacy s = new SamsungLegacy("127.0.0.1:" + ss.getLocalPort(), "");
            s.key(19);
            s.key(24);
            s.powerOff();
            Thread.sleep(100);
            assert got.size() == 4 : got;
            assert got.get(1).equals(hex(SamsungLegacy.keyPacket("KEY_UP"))) : got;
            assert got.get(3).equals(hex(SamsungLegacy.keyPacket("KEY_POWEROFF"))) : got;
            assert s.isOn() == Boolean.TRUE;
            assert !s.can("volume") && !s.can("apps") && s.can("mute");
        }
        // Off (nothing answers on 55000): unknown, quickly; switching on isn't possible on these models.
        SamsungLegacy off = new SamsungLegacy("127.0.0.1:9", "");
        assert off.isOn() == null;
        try {
            off.powerOn();
            throw new AssertionError("an F-series can't be switched on over the network");
        } catch (java.io.IOException e) {
            assert e.getMessage().contains("remote") : e.getMessage();
        }

        // Its id: the uuid in the answer to a UPnP search for its remote-control receiver.
        assert Discovery.parseUuid("HTTP/1.1 200 OK\r\nUSN: uuid:0b532b81-00be-1000-9e19-5056bf18d8bd::urn:samsung.com:"
            + "device:RemoteControlReceiver:1\r\n\r\n").equals("0b532b81-00be-1000-9e19-5056bf18d8bd");
        assert Discovery.parseUuid("HTTP/1.1 200 OK\r\n\r\n") == null;

        // Live (RXHOME_SAMSUNG_LEGACY=ip): the real TV's id, read-only (no prompt on the TV).
        String ip = System.getenv("RXHOME_SAMSUNG_LEGACY");
        if (ip != null) {
            String id = SamsungLegacy.uuid(ip);
            System.out.println("  live: " + ip + " id=" + id);
            assert id != null;
        }
    }
}
