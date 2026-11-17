package com.andrea.rxhome;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

/** rexserver's status, from Jellyfin's public info page on the LAN. */
final class Server {

    private Server() {}

    /** ON if Jellyfin answers; otherwise UNREACHABLE (off and out of reach look the same, so never OFF). */
    static State status(String baseUrl) {
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
