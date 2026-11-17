package com.andrea.rxhome;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

/**
 * Runs while a WireGuard tunnel rxhome switched on is up: every 5 s it looks at which app is in front
 * (Android's usage events, needs "Usage access") and switches the tunnel off once neither rxhome nor
 * Jellyfin has been in front, screen on, for Vpn.GRACE_MS.
 */
public class TunnelWatch extends Service {

    private static final long EVERY_MS = 5000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastSeenOurs;

    private final Runnable check = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            boolean screenOn = getSystemService(PowerManager.class).isInteractive();
            if (screenOn && Vpn.ours(foreground())) lastSeenOurs = now;
            if (Vpn.shouldStop(lastSeenOurs, now)) {
                System.err.println("rxhome-vpn: neither rxhome nor Jellyfin in front: switching " + Vpn.TUNNEL + " off");
                sendBroadcast(new Intent(Vpn.ACTION_DOWN).setPackage(Vpn.PACKAGE).putExtra("tunnel", Vpn.TUNNEL));
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
        Notification n = new Notification.Builder(this, "tunnel")
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle("WireGuard tunnel on")
            .setContentText("Switches off when you leave rxhome and Jellyfin")
            .setOngoing(true)
            .build();
        startForeground(1, n);
        lastSeenOurs = SystemClock.elapsedRealtime();
        main.removeCallbacks(check);
        if (!usageAccess()) {
            // Can't tell which app is in front: leave the tunnel on rather than cut it blindly.
            System.err.println("rxhome-vpn: no Usage access, the tunnel stays on");
            stopSelf();
            return START_NOT_STICKY;
        }
        main.postDelayed(check, EVERY_MS);
        return START_NOT_STICKY;
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

    @SuppressWarnings("deprecation") // checkOpNoThrow: still the way below API 29
    private boolean usageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
            == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(check);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
