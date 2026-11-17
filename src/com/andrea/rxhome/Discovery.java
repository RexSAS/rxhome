package com.andrea.rxhome;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finds the server, the FireStick and the Philips on the Wi-Fi, so Settings needs no IP addresses:
 * one parallel scan of the /24 for their ports (8096, 5555, 1925), each hit confirmed by what it is.
 * The FireStick counts only if it already trusts our key: a scan never offers the key, so no other
 * device gets an "Allow USB debugging?" prompt.
 */
final class Discovery {

    static final class Found {
        String server, stick, tv;
    }

    private static final int CONNECT_MS = 350;

    private Discovery() {}

    /**
     * The phone's Wi-Fi IPv4 address, or null when not on Wi-Fi. Wi-Fi interfaces only: mobile data
     * (rmnet) also has private-range addresses (e.g. 10.159.77.117/30), and scanning the carrier's is pointless.
     */
    static String wifiIp() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback() || !n.getName().startsWith("wlan")) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    // shortcut: assumes a /24 home network (192.168.1.x); read the real prefix if the LAN ever grows.
    static List<String> subnet(String myIp) {
        String prefix = myIp.substring(0, myIp.lastIndexOf('.') + 1);
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            String h = prefix + i;
            if (!h.equals(myIp)) out.add(h);
        }
        return out;
    }

    /** The hosts with `port` open, in the order given. */
    static List<String> open(List<String> hosts, int port, int timeoutMs) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(128, Math.max(1, hosts.size())));
        try {
            List<Future<Boolean>> f = new ArrayList<>();
            for (String h : hosts) {
                f.add(pool.submit(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(h, port), timeoutMs);
                        return true;
                    } catch (IOException e) {
                        return false;
                    }
                }));
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < hosts.size(); i++) {
                try {
                    if (f.get(i).get()) out.add(hosts.get(i));
                } catch (Exception e) {
                    // Treated as closed.
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    static boolean isJellyfin(String base) {
        return fetch(base + "/System/Info/Public").contains("Jellyfin");
    }

    static boolean isPhilips(String host) {
        String h = host.contains(":") ? host : host + ":1925";
        String r = fetch("http://" + h + "/6/system");
        return r.contains("\"name\"") && r.contains("featuring");
    }

    static boolean isOurFireStick(String ip, KeyPair key) {
        Adb a = new Adb(ip, 5555, key);
        a.offerKey = false;
        try {
            return a.shell("getprop ro.product.manufacturer", 2000).trim().equals("Amazon");
        } catch (IOException e) {
            return false;
        } finally {
            a.close();
        }
    }

    /** One scan for all three. A FireStick nobody trusts yet counts only if it's the only ADB device. */
    static Found find(String myIp, KeyPair key) {
        List<String> hosts = subnet(myIp);
        Found f = new Found();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<List<String>> jelly = pool.submit(() -> open(hosts, 8096, CONNECT_MS));
            Future<List<String>> adb = pool.submit(() -> open(hosts, 5555, CONNECT_MS));
            Future<List<String>> philips = pool.submit(() -> open(hosts, 1925, CONNECT_MS));
            for (String h : jelly.get()) {
                if (isJellyfin("http://" + h + ":8096")) {
                    f.server = "http://" + h + ":8096";
                    break;
                }
            }
            List<String> adbHosts = adb.get();
            for (String h : adbHosts) {
                if (isOurFireStick(h, key)) {
                    f.stick = h;
                    break;
                }
            }
            if (f.stick == null && adbHosts.size() == 1) f.stick = adbHosts.get(0);
            for (String h : philips.get()) {
                if (isPhilips(h)) {
                    f.tv = h;
                    break;
                }
            }
        } catch (Exception e) {
            // Whatever was found so far.
        } finally {
            pool.shutdownNow();
        }
        return f;
    }

    private static String fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2000);
            if (c.getResponseCode() != 200) return "";
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } catch (IOException e) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
