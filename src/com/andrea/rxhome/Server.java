package com.andrea.rxhome;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

/** rexserver's status, from Jellyfin's public info page on the LAN. */
final class Server {

    private Server() {}

    /**
     * What the card says: Jellyfin answering = "online"; only the stats (node_exporter starts early in
     * boot, Jellyfin later) = "starting"; neither while home devices answer = "off"; nothing answers = "unknown".
     */
    static String label(State jellyfin, boolean statsSeen, boolean homeSeen) {
        // homeSeen: other home devices answer (home Wi-Fi or the tunnel), so a silent server is really off.
        return jellyfin == State.ON ? "online" : statsSeen ? "starting" : homeSeen ? "off" : "unknown";
    }

    /** For "server must be on": a starting server counts as on, so its power button isn't pressed mid-boot. */
    static State effective(State jellyfin, boolean statsSeen) {
        return jellyfin == State.ON || statsSeen ? State.ON : jellyfin;
    }

    /**
     * ON if Jellyfin answers at any of the addresses (LAN first, then public); otherwise UNREACHABLE
     * (off and out of reach look the same, so never OFF).
     */
    static State status(String... baseUrls) {
        for (String u : baseUrls) {
            if (u != null && !u.isEmpty() && one(u) == State.ON) return State.ON;
        }
        return State.UNREACHABLE;
    }

    /** The HTTP status of the public info page, or -1 if nothing answered (no network, dead tunnel). */
    static int code(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) return -1;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/System/Info/Public").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            c.connect(); // TLS to Cloudflare's edge: only says the internet works
            try {
                return c.getResponseCode();
            } catch (java.net.SocketTimeoutException e) {
                // Its whitelist block is instant; a wait means it let us through and waits on a dead server
                // (its 522 comes after ~15 s).
                return 522;
            }
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Cloudflare's whitelist let us through: anything but its 403 block (52x = through, server down). */
    static boolean passes(int code) {
        return code > 0 && code != 403;
    }

    private static State one(String baseUrl) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/System/Info/Public").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            if (c.getResponseCode() != 200) return State.UNREACHABLE;
            try (InputStream in = c.getInputStream(); Scanner s = new Scanner(in, "UTF-8")) {
                return s.useDelimiter("\\A").hasNext() && s.next().contains("Jellyfin") ? State.ON : State.UNREACHABLE;
            }
        } catch (Exception e) {
            return State.UNREACHABLE;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
