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
        String server;
    }

    /** A TV (or FireStick) on this Wi-Fi. brand: samsung | lg | philips | firetv. id null = can't tell yet. */
    static final class TvFound {
        final String brand, ip, id, name, mac;

        TvFound(String brand, String ip, String id, String name, String mac) {
            this.brand = brand;
            this.ip = ip;
            this.id = id;
            this.name = name;
            this.mac = mac;
        }
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

    /** True as soon as any "host:port" accepts a connection (all tried in parallel); false if none does. */
    static boolean anyAnswers(List<String> hostPorts, int timeoutMs) {
        if (hostPorts.isEmpty()) return false;
        ExecutorService pool = Executors.newFixedThreadPool(hostPorts.size());
        java.util.concurrent.ExecutorCompletionService<Boolean> done = new java.util.concurrent.ExecutorCompletionService<>(pool);
        try {
            for (String hp : hostPorts) {
                int c = hp.lastIndexOf(':');
                String host = hp.substring(0, c);
                int port = Integer.parseInt(hp.substring(c + 1));
                done.submit(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(host, port), timeoutMs);
                        return true;
                    } catch (IOException e) {
                        return false;
                    }
                });
            }
            for (int i = 0; i < hostPorts.size(); i++) {
                try {
                    if (done.take().get()) return true;
                } catch (Exception e) {
                    // That one counts as silent.
                }
            }
            return false;
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

    /** The Jellyfin server on this Wi-Fi (TVs: findTvs). */
    static Found find(String myIp, KeyPair key) {
        Found f = new Found();
        for (String h : open(subnet(myIp), 8096, CONNECT_MS)) {
            if (isJellyfin("http://" + h + ":8096")) {
                f.server = "http://" + h + ":8096";
                break;
            }
        }
        return f;
    }

    /**
     * Every TV and FireStick on this Wi-Fi, each with a lasting id: Samsung's own device id, else the UPnP
     * uuid, else its certificate's pin; a FireStick's serial (only if it already trusts our key: the scan
     * never offers it, so nothing gets a prompt).
     */
    static List<TvFound> findTvs(String myIp, KeyPair key) {
        List<String> hosts = subnet(myIp);
        List<TvFound> out = new java.util.ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            Future<List<Ssdp.Hit>> ssdp = pool.submit(() -> Ssdp.search(2500));
            Future<List<String>> samsung = pool.submit(() -> open(hosts, 8001, CONNECT_MS));
            Future<List<String>> lg = pool.submit(() -> open(hosts, 3001, CONNECT_MS));
            Future<List<String>> philips = pool.submit(() -> open(hosts, 1925, CONNECT_MS));
            Future<List<String>> adb = pool.submit(() -> open(hosts, 5555, CONNECT_MS));
            List<Ssdp.Hit> hits = ssdp.get();
            java.util.Set<String> taken = new java.util.HashSet<>();
            for (String h : samsung.get()) {
                String[] info = Samsung.info(h);
                if (info != null && taken.add(h)) out.add(new TvFound("samsung", h, info[0], info[1], info[2]));
            }
            for (String h : lg.get()) {
                if (taken.contains(h)) continue;
                taken.add(h);
                out.add(new TvFound("lg", h, identity(hits, h, 3001), "LG TV", null));
            }
            for (String h : philips.get()) {
                if (taken.contains(h) || !isPhilips(h)) continue;
                taken.add(h);
                String name = "Philips TV";
                try {
                    name = new org.json.JSONObject(fetch("http://" + h + ":1925/6/system")).optString("name", name);
                } catch (org.json.JSONException e) {
                    // keep the default
                }
                out.add(new TvFound("philips", h, identity(hits, h, 1926), name, null));
            }
            for (String h : adb.get()) {
                Adb a = new Adb(h, 5555, key);
                a.offerKey = false;
                try {
                    String serial = a.shell("getprop ro.serialno", 2000).trim();
                    out.add(new TvFound("firetv", h, serial.isEmpty() ? null : serial, "FireStick", null));
                } catch (IOException e) {
                    out.add(new TvFound("firetv", h, null, "FireStick (not paired)", null));
                } finally {
                    a.close();
                }
            }
        } catch (Exception e) {
            // Whatever was found so far.
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** UPnP uuid, else the pin of the certificate on tlsPort; null if neither. */
    private static String identity(List<Ssdp.Hit> hits, String ip, int tlsPort) {
        String u = Ssdp.rootUuid(hits, ip);
        if (u != null) return u;
        try {
            return "pin:" + Pin.capture(ip, tlsPort);
        } catch (IOException e) {
            return null;
        }
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
