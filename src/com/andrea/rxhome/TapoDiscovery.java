package com.andrea.rxhome;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Finds Tapo lights and plugs on the Wi-Fi: the same UDP broadcast (port 20002) python-kasa uses. */
final class TapoDiscovery {

    // python-kasa's DISCOVERY_QUERY_2: devices answer with a 16-byte header and plain JSON.
    private static final byte[] QUERY = {0x02, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0x46, 0x3c, (byte) 0xb5, (byte) 0xd3};

    static final class Found {
        final String ip, model;

        Found(String ip, String model) {
            this.ip = ip;
            this.model = model;
        }

        /** Shown until we can log in and read the name set in the Tapo app, e.g. "L900 .10". */
        String fallbackName() {
            return model + " ." + ip.substring(ip.lastIndexOf('.') + 1);
        }
    }

    private TapoDiscovery() {}

    /** Broadcasts and collects answers for timeoutMs; one entry per device. */
    static List<Found> discover(int timeoutMs) throws IOException {
        Map<String, Found> out = new LinkedHashMap<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            InetAddress all = InetAddress.getByName("255.255.255.255");
            for (int i = 0; i < 2; i++) s.send(new DatagramPacket(QUERY, QUERY.length, all, 20002));
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                s.setSoTimeout((int) Math.max(1, end - System.currentTimeMillis()));
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                Found f = parse(Arrays.copyOf(p.getData(), p.getLength()), p.getAddress().getHostAddress());
                if (f != null) out.put(f.ip, f);
            }
        }
        return new ArrayList<>(out.values());
    }

    /** A reply's device, or null for junk and for devices that aren't lights, strips or plugs. */
    static Found parse(byte[] reply, String fromIp) {
        if (reply.length <= 16) return null;
        try {
            JSONObject r = new JSONObject(new String(reply, 16, reply.length - 16, StandardCharsets.UTF_8)).getJSONObject("result");
            String type = r.optString("device_type");
            if (!type.contains("BULB") && !type.contains("PLUG") && !type.contains("SWITCH")) return null;
            String model = r.optString("device_model", "Tapo").split("[-(]")[0];
            return new Found(r.optString("ip", fromIp), model);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Merges found lights (IP → name) into the lights setting ("Name=IP" lines): adds new ones,
     * follows a light whose IP changed, swaps a placeholder for the real name, keeps names the user typed.
     */
    static String merge(String lightsText, Map<String, String> found) {
        // Each row: {prefix, name, ip}; prefix is "" or the "#" (plus spaces) that hides a light.
        List<String[]> rows = new ArrayList<>();
        for (String line : lightsText.split("\n")) {
            String t = line.trim();
            int eq = t.indexOf('=');
            if (eq <= 0 || t.substring(eq + 1).trim().isEmpty()) continue;
            String name = t.substring(0, eq);
            int i = 0;
            if (name.startsWith("#")) {
                i = 1;
                while (i < name.length() && name.charAt(i) == ' ') i++;
            }
            rows.add(new String[] {name.substring(0, i), name.substring(i).trim(), t.substring(eq + 1).trim()});
        }
        for (Map.Entry<String, String> f : found.entrySet()) {
            String ip = f.getKey(), name = f.getValue();
            String[] atIp = row(rows, 2, ip);
            if (atIp != null) {
                if (!isPlaceholder(atIp[1]) || isPlaceholder(name) || atIp[1].equals(name)) continue;
                String[] named = row(rows, 1, name);
                if (named != null) {
                    named[2] = ip; // the real name was already listed at an old IP
                    rows.remove(atIp);
                } else {
                    atIp[1] = name;
                }
                continue;
            }
            String[] named = row(rows, 1, name);
            if (named != null) named[2] = ip; // a known light (hidden or not) that moved
            else rows.add(new String[] {"", name, ip});
        }
        StringBuilder s = new StringBuilder();
        for (String[] r : rows) {
            if (s.length() > 0) s.append('\n');
            s.append(r[0]).append(r[1]).append('=').append(r[2]);
        }
        return s.toString();
    }

    private static String[] row(List<String[]> rows, int field, String value) {
        for (String[] r : rows) {
            if (r[field].equals(value)) return r;
        }
        return null;
    }

    static boolean isPlaceholder(String name) {
        return name.matches("[A-Z]+\\d+ \\.\\d+");
    }
}
