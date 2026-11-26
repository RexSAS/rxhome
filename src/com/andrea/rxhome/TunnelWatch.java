package com.andrea.rxhome;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs while you use rxhome or Jellyfin and keeps rxhome's WireGuard tunnel right:
 *  - joining a Wi-Fi: rxhome's tunnel goes off, then if home doesn't answer (not the home Wi-Fi) it comes back;
 *  - losing Wi-Fi: if home doesn't answer, the tunnel comes on;
 *  - neither rxhome nor Jellyfin in front (screen on) for Vpn.GRACE_MS: rxhome's tunnel goes off, the watch ends.
 * Only tunnels rxhome switched on are switched off (pref "vpn_ours"). Needs "Usage access" to see the front app.
 */
public class TunnelWatch extends Service {

    static final String OURS = "vpn_ours";
    private static final long EVERY_MS = 2000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Config cfg;
    private long lastSeenOurs, registeredAt;
    private ConnectivityManager.NetworkCallback wifi;

    private final Runnable check = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (oursInFront()) lastSeenOurs = now;
            if (Vpn.shouldStop(lastSeenOurs, now)) {
                if (tunnelOurs()) down("neither rxhome nor Jellyfin in front");
                stopSelf();
                return;
            }
            main.postDelayed(this, EVERY_MS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("tunnel", "WireGuard tunnel", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "tunnel")
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle("rxhome")
            .setContentText("Keeps the WireGuard tunnel right while you use rxhome or Jellyfin")
            .setOngoing(true)
            .build());
        cfg = new Config(this);
        lastSeenOurs = SystemClock.elapsedRealtime();
        main.removeCallbacks(check);
        if (!usageAccess()) {
            // Can't tell which app is in front: leave the tunnel as it is rather than cut it blindly.
            System.err.println("rxhome-vpn: no Usage access, not watching");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wifi == null) watchWifi();
        main.postDelayed(check, EVERY_MS);
        return START_NOT_STICKY;
    }

    private void watchWifi() {
        registeredAt = SystemClock.elapsedRealtime();
        wifi = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                // Android reports the Wi-Fi we're already on right after registering: not a change.
                if (SystemClock.elapsedRealtime() - registeredAt < 2000) return;
                main.post(() -> {
                    System.err.println("rxhome-vpn: Wi-Fi connected");
                    if (tunnelOurs()) down("on Wi-Fi: checking whether it's home");
                    main.postDelayed(TunnelWatch.this::recheck, 4000); // let it get an address first
                });
            }

            @Override public void onLost(Network n) {
                main.post(() -> {
                    System.err.println("rxhome-vpn: Wi-Fi lost");
                    main.postDelayed(TunnelWatch.this::recheck, 3000);
                });
            }
        };
        getSystemService(ConnectivityManager.class).registerNetworkCallback(
            new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi);
    }

    /** Home out of reach while you're in rxhome or Jellyfin: tunnel on. */
    private void recheck() {
        boolean front = oursInFront();
        boolean vpn = vpnActive();
        java.util.List<String> home = cfg.homeDevices();
        try {
            URL u = new URL(cfg.get(Config.SERVER));
            home.add(u.getHost() + ":" + (u.getPort() > 0 ? u.getPort() : 80));
        } catch (Exception e) {
            // No server address: the other devices still tell.
        }
        io.execute(() -> {
            boolean h = cfg.atHome(home, vpn);
            main.post(() -> {
                System.err.println("rxhome-vpn: home answers: " + h);
                if (Vpn.wantUp(h, front, vpn)) up();
            });
        });
    }

    private void up() {
        System.err.println("rxhome-vpn: home out of reach: switching " + Vpn.TUNNEL + " on");
        sendBroadcast(new Intent(Vpn.ACTION_UP).setPackage(Vpn.PACKAGE).putExtra("tunnel", Vpn.TUNNEL));
        cfg.put(OURS, "1");
    }

    private void down(String why) {
        System.err.println("rxhome-vpn: " + why + ": switching " + Vpn.TUNNEL + " off");
        sendBroadcast(new Intent(Vpn.ACTION_DOWN).setPackage(Vpn.PACKAGE).putExtra("tunnel", Vpn.TUNNEL));
        cfg.put(OURS, "");
    }

    private boolean tunnelOurs() {
        return "1".equals(cfg.prefs.getString(OURS, ""));
    }

    /** A TCP connect to the URL's host and port within 1.5 s. */
    private static boolean answers(String url) {
        try (Socket s = new Socket()) {
            URL u = new URL(url);
            s.connect(new InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 80), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean oursInFront() {
        return getSystemService(PowerManager.class).isInteractive() && Vpn.ours(foreground());
    }

    /** The app whose activity was resumed last (in the past 10 min), or null. */
    private String foreground() {
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        long t = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(t - 10 * 60_000, t);
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
        }
        return last;
    }

    private boolean vpnActive() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation") // checkOpNoThrow: still the way below API 29
    private boolean usageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
            == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (wifi != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifi);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
