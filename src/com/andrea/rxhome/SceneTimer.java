package com.andrea.rxhome;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import java.text.ParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scene timers ("run Movie night in 20 min"): an exact alarm starts this service at the time, which
 * runs the scene like a tap would, with rxhome closed too. One timer per scene; a new one replaces it.
 */
public class SceneTimer extends Service {

    private static final String KEY = "scene_timer:"; // + scene name → when it runs (epoch ms)
    private final ExecutorService io = Executors.newSingleThreadExecutor(); // timers due together run in turn

    static void schedule(Context c, String scene, long at) {
        new Config(c).put(KEY + scene, Long.toString(at));
        c.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c, scene));
    }

    static void cancel(Context c, String scene) {
        new Config(c).put(KEY + scene, "");
        c.getSystemService(AlarmManager.class).cancel(intent(c, scene));
    }

    /** When the scene's timer runs (epoch ms), or 0 if none is set. */
    static long due(Config cfg, String scene) {
        try {
            long at = Long.parseLong(cfg.get(KEY + scene));
            return at > System.currentTimeMillis() ? at : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Scheduled scenes → when they run, soonest first (past ones dropped). */
    static java.util.Map<String, Long> all(Config cfg) {
        java.util.TreeMap<Long, String> byTime = new java.util.TreeMap<>();
        for (String k : cfg.prefs.getAll().keySet()) {
            if (!k.startsWith(KEY)) continue;
            String name = k.substring(KEY.length());
            long at = due(cfg, name);
            if (at > 0) byTime.put(at, name);
        }
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<Long, String> e : byTime.entrySet()) out.put(e.getValue(), e.getKey());
        return out;
    }

    /** "at 21:30" today, "tomorrow at 7:00", else "Sat 28 Nov at 21:30". */
    static String when(Context c, long at) {
        java.util.Calendar t = java.util.Calendar.getInstance(), today = java.util.Calendar.getInstance();
        t.setTimeInMillis(at);
        java.util.Calendar tomorrow = (java.util.Calendar) today.clone();
        tomorrow.add(java.util.Calendar.DAY_OF_MONTH, 1);
        String time = android.text.format.DateFormat.getTimeFormat(c).format(t.getTime());
        return sameDay(t, today) ? "at " + time : sameDay(t, tomorrow) ? "tomorrow at " + time
            : android.text.format.DateFormat.format("EEE d MMM", t) + " at " + time;
    }

    private static boolean sameDay(java.util.Calendar a, java.util.Calendar b) {
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
            && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /** Sent when a timer starts and ends its run: an open rxhome redraws the clocks and active scenes. */
    static final String CHANGED = "com.andrea.rxhome.SCENE_TIMERS";

    private static PendingIntent intent(Context c, String scene) {
        // The scene's name in the data URI: one PendingIntent (so one timer) per scene.
        Intent i = new Intent(c, SceneTimer.class).setData(android.net.Uri.fromParts("scene", scene, null));
        return PendingIntent.getForegroundService(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The same runner a tap builds; ask and openRemote are what the caller can do (none from a timer). */
    static SceneRunner runner(Config cfg, Scenes.Plan p, SceneRunner.Ask ask, Runnable openRemote) {
        String server = cfg.get(Config.SERVER), pub = cfg.get(Config.SERVER_PUBLIC);
        List<Tvs.Saved> tvs = cfg.saved(); // a step without "@Name" is for the first TV
        return new SceneRunner(cfg.lights(), tvs.isEmpty() ? null : cfg.tv(tvs.get(0)), cfg.switchBot(), cfg.get(Config.SB_BOT),
            () -> Server.effective(Server.status(server, pub), MainActivity.stats(server, pub) != null),
            cfg.jellyfin(), ask, openRemote)
            .music(cfg.spotify())
            .tvs(cfg::tvNamed)
            .smooth(p.turningOff || p.before != null)
            .signal(p.turningOff ? "off" : p.before != null ? "swap" : "start", p.before == null ? null : p.before.name);
    }

    static Set<String> active(Config cfg) {
        Set<String> s = new LinkedHashSet<>();
        for (String n : cfg.get(Config.SCENES_ACTIVE).split("\n")) if (!n.trim().isEmpty()) s.add(n.trim());
        return s;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String name = intent == null || intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("scenes", "Scene timers", NotificationManager.IMPORTANCE_LOW));
        startForeground(2, note("Running " + name + "…"));
        io.execute(() -> {
            String result = run(name);
            sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
            nm.notify(3, note(result)); // stays after the service ends
            stopSelf(startId);
        });
        return START_NOT_STICKY;
    }

    private String run(String name) {
        Config cfg = new Config(this);
        cfg.put(KEY + name, "");
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName())); // its clock goes
        try {
            List<Scenes.Scene> all = Scenes.parse(cfg.get(Config.SCENES));
            for (Scenes.Scene scene : all) {
                if (!scene.name.equals(name)) continue;
                Scenes.Plan p = Scenes.plan(scene, all, active(cfg), cfg.lights().keySet());
                // No one to ask from a timer: a "choose when run" step is skipped.
                int[] r = runner(cfg, p, pkg -> null, () -> {}).run(p.todo);
                cfg.put(Config.SCENES_ACTIVE, String.join("\n", p.activeAfter));
                return name + (p.turningOff ? " off" : "") + (r[0] == r[1] ? ": done" : ": " + r[0] + "/" + r[1] + " done");
            }
            return name + ": timer skipped, the scene no longer exists";
        } catch (ParseException e) {
            return name + ": timer skipped, scenes unreadable (" + e.getMessage() + ")";
        }
    }

    private Notification note(String text) {
        return new Notification.Builder(this, "scenes")
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle("rxhome scene timer")
            .setContentText(text)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
