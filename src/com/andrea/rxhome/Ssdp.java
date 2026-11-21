package com.andrea.rxhome;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UPnP discovery: who's on the Wi-Fi and their device uuid (a TV's lasting id, whatever its IP).
 * Answers to an M-SEARCH come back unicast, so no multicast lock is needed to hear them.
 */
final class Ssdp {

    static final class Hit {
        final String ip, uuid, st;

        Hit(String ip, String uuid, String st) {
            this.ip = ip;
            this.uuid = uuid;
            this.st = st;
        }
    }

    private Ssdp() {}

    /** Asks everyone (ssdp:all, then upnp:rootdevice) and collects answers for waitMs. */
    static List<Hit> search(int waitMs) {
        List<Hit> out = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (String st : new String[] {"ssdp:all", "upnp:rootdevice"}) {
                byte[] m = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: "
                    + st + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                s.send(new DatagramPacket(m, m.length, group, 1900));
            }
            long until = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < until) {
                s.setSoTimeout((int) Math.max(1, until - System.currentTimeMillis()));
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                Hit h = parse(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8), p.getAddress().getHostAddress());
                if (h != null) out.add(h);
            }
        } catch (IOException e) {
            // No Wi-Fi or multicast blocked: whatever came back.
        }
        return out;
    }

    /** One answer; null without a uuid. */
    static Hit parse(String response, String fromIp) {
        String usn = header(response, "usn"), st = header(response, "st");
        if (usn == null || !usn.toLowerCase(Locale.ROOT).startsWith("uuid:")) return null;
        String uuid = usn.substring(5);
        int end = uuid.indexOf("::");
        if (end >= 0) uuid = uuid.substring(0, end);
        return new Hit(fromIp, uuid, st == null ? "" : st);
    }

    /**
     * The device's uuid at ip: the smallest root-device uuid (one IP can announce several, in any order),
     * else the smallest uuid seen, so the answer is the same every scan.
     */
    static String rootUuid(List<Hit> hits, String ip) {
        String root = null, any = null;
        for (Hit h : hits) {
            if (!h.ip.equals(ip)) continue;
            if (h.st.equalsIgnoreCase("upnp:rootdevice") && (root == null || h.uuid.compareTo(root) < 0)) root = h.uuid;
            if (any == null || h.uuid.compareTo(any) < 0) any = h.uuid;
        }
        return root != null ? root : any;
    }

    /** True if ip announced a service type containing `part` (any case). */
    static boolean any(List<Hit> hits, String ip, String part) {
        for (Hit h : hits) if (h.ip.equals(ip) && h.st.toLowerCase(Locale.ROOT).contains(part)) return true;
        return false;
    }

    private static String header(String response, String name) {
        for (String line : response.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return null;
    }
}
