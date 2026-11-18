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
