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
        Map<String, String> lights = Scenes.lights(lightsText); // name → IP, in order
        for (Map.Entry<String, String> f : found.entrySet()) {
            String ip = f.getKey(), name = f.getValue();
            String atIp = null;
            for (Map.Entry<String, String> e : lights.entrySet()) {
                if (e.getValue().equals(ip)) atIp = e.getKey();
            }
            if (atIp != null) {
                if (!isPlaceholder(atIp) || isPlaceholder(name) || atIp.equals(name)) continue;
                lights.remove(atIp);
                lights.put(name, ip); // updates in place if the name already exists at an old IP
            } else {
                lights.put(name, ip); // new light, or a known one that moved
            }
        }
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, String> e : lights.entrySet()) {
            if (s.length() > 0) s.append('\n');
            s.append(e.getKey()).append('=').append(e.getValue());
        }
        return s.toString();
    }

    static boolean isPlaceholder(String name) {
        return name.matches("[A-Z]+\\d+ \\.\\d+");
    }
}
