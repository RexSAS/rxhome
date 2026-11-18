package com.andrea.rxhome;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/** Runs a scene's steps in order; a step whose device can't be reached is skipped, not fatal. */
class SceneRunner {

    private final Map<String, Tapo> lights;
    private final FireTv tv;
    private final SwitchBot sb;
    private final String botId;
    private final java.util.function.Supplier<State> server;
    private final Jellyfin jf;
    private final Ask ask;
    private final Runnable openRemote;
    private Spotify spotify;
    private boolean smooth;
    private java.util.function.Function<String, FireTv> tvByName;

    /** For "choose when run": shows the choices for an app and returns one (Scenes.contents value or "item:<id>"); null = cancelled. */
    interface Ask {
        String choose(String pkg);
    }

    SceneRunner(Map<String, Tapo> lights, FireTv tv, SwitchBot sb, String botId,
            java.util.function.Supplier<State> server, Jellyfin jf, Ask ask, Runnable openRemote) {
        this.lights = lights;
        this.tv = tv;
        this.sb = sb;
        this.botId = botId;
        this.server = server;
        this.jf = jf;
        this.ask = ask;
        this.openRemote = openRemote;
    }

    /** Spotify for "music" steps; null = not connected (those steps aren't done). */
    SceneRunner music(Spotify spotify) {
        this.spotify = spotify;
        return this;
    }

    /** The TVs by name, for steps ending in "@Name"; unknown name = null (that step isn't done). */
    SceneRunner tvs(java.util.function.Function<String, FireTv> byName) {
        tvByName = byName;
        return this;
    }

    /** Smooth (swapping scenes, turning one off): lights fade, the TV's sound fades out before it goes off. */
    SceneRunner smooth(boolean on) {
        smooth = on;
        return this;
    }

    static final int FADE_MS = 1500;
    private String signalEvent, signalFrom, sceneName;

    /** What to tell the TVs this scene uses (FireTv.signal): "start" / "swap" (from = the previous scene) / "off"; null = nothing. */
    SceneRunner signal(String event, String from) {
        signalEvent = event;
        signalFrom = from;
        return this;
    }

    long now() {
        return System.currentTimeMillis();
    }

    void waitMs(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Between a light's fade steps; its own round trip is most of the pace. */
    void fadeGap() {
        waitMs(30);
    }

    /** A brightness along an eased fade (slow start, slow end): t from 0 to 1. */
    static int fadeLevel(int from, int to, double t) {
        t = Math.max(0, Math.min(1, t));
        double s = t * t * (3 - 2 * t);
        return (int) Math.round(from + (to - from) * s);
    }

    /** Fades these lights at the same time (a thread each) to on/off and brightness; true if every light took it. */
    private boolean fade(Collection<Tapo> targets, boolean on, Integer brightness, Integer kelvin, int[] color) {
        java.util.concurrent.atomic.AtomicBoolean all = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (Tapo t : targets) {
            Thread th = new Thread(() -> { if (!fadeOne(t, on, brightness, kelvin, color)) all.set(false); }, "light-fade");
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return all.get();
    }

    /** One light: from where it is, a new level as fast as it answers for FADE_MS, then off / white / colour. */
    private boolean fadeOne(Tapo t, boolean on, Integer brightness, Integer kelvin, int[] color) {
        try {
            org.json.JSONObject i = t.info();
            int from = i.optBoolean("device_on") ? i.optInt("brightness", 100) : on ? 1 : 0;
            if (on && brightness == null) {
                t.set(true, null, kelvin); // no level to fade to
            } else if (from > 0) {
                int to = on ? brightness : 1, last = -1;
                long start = now();
                while (true) {
                    double f = Math.min(1, (now() - start) / (double) FADE_MS);
                    int lv = Math.max(1, fadeLevel(from, to, f));
                    boolean end = f >= 1;
                    if (lv != last || end) {
                        t.set(true, lv, end && on ? kelvin : null);
                        last = lv;
                    }
                    if (end) break;
                    fadeGap();
                }
            }
            if (!on) t.set(false, null, null);
            else if (color != null) t.color(color[0], color[1]);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Returns {steps done, steps total}. */
    int[] run(Scenes.Scene scene) {
        // "Choose when run" asks right at the start; each pick waits in its step's place. Cancelled = skipped.
        java.util.List<String[]> steps = new java.util.ArrayList<>();
        for (String[] s : scene.steps) {
            String[] b = Scenes.untargeted(s), named = s;
            if (b[0].equals("tv") && b.length == 4 && b[1].equals("app") && b[3].equals("ask")) {
                String pick = ask == null ? null : ask.choose(b[2]);
                String t = Scenes.tvTarget(named);
                s = pick == null ? new String[] {"skip"}
                    : t == null ? new String[] {"tv", "app", b[2], pick} : new String[] {"tv", "app", b[2], pick, "@" + t};
            }
            steps.add(s);
        }
        sceneName = scene.name;
        if (signalEvent != null) { // tell each TV the scene uses, once
            java.util.Set<FireTv> told = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (String[] s : steps) {
                if (!s[0].equals("tv") && !s[0].equals("keys")) continue;
                String n = Scenes.tvTarget(s);
                FireTv t = n == null ? tv : tvByName == null ? null : tvByName.apply(n);
                if (t == null || !told.add(t)) continue;
                try {
                    t.signal(signalEvent, scene.name, signalFrom);
                } catch (IOException | RuntimeException e) {
                    // Only a courtesy to the launcher: the scene goes on.
                }
            }
        }
        int done = 0;
        for (String[] s : steps) {
            try {
                if (step(s)) done++;
            } catch (IOException | RuntimeException e) {
                // Unreachable or refused: counted as not done; the next steps still run.
            }
        }
        return new int[] {done, scene.steps.size()};
    }

    private boolean step(String[] s) throws IOException {
        FireTv tv = this.tv;
        String tvName = Scenes.tvTarget(s);
        if (tvName != null) {
            tv = tvByName == null ? null : tvByName.apply(tvName);
            s = Scenes.untargeted(s);
        }
        switch (s[0]) {
            case "light":
                Collection<Tapo> targets = s[1].equals("all") ? lights.values()
                    : lights.containsKey(s[1]) ? Collections.singleton(lights.get(s[1])) : Collections.<Tapo>emptySet();
                if (targets.isEmpty()) return false;
                boolean on = s[2].equals("on");
                Integer brightness = s.length >= 4 ? Integer.valueOf(s[3]) : null;
                int[] color = s.length == 5 ? Scenes.color(s[4]) : null;
                Integer kelvin = s.length == 5 && color == null ? kelvin(s[4]) : null;
                if (smooth) return fade(targets, on, brightness, kelvin, color);
                boolean all = true;
                for (Tapo t : targets) {
                    try {
                        t.set(on, brightness, kelvin);
                        if (color != null) t.color(color[0], color[1]); // after the brightness, like the light rows
                    } catch (IOException e) {
                        all = false;
                    }
                }
                return all;
            case "tv":
                if (tv == null) return false;
                if (s[1].equals("on") || s[1].equals("off")) {
                    // Like the server: check first. Sending "off" to a TV that's already off can turn it on.
                    boolean want = s[1].equals("on"), isOn = tv.tvState(tv.state()) == State.ON;
                    if (want != isOn) {
                        if (want) tv.wake();
                        else {
                            if (signalEvent != null) { // the launcher fades the picture first
                                try {
                                    tv.signal("tv_off", sceneName, null);
                                    waitMs(FADE_MS);
                                } catch (IOException | RuntimeException e) {
                                    // No launcher to fade: straight off.
                                }
                            }
                            tv.sleep();
                        }
                    }
                }
                else if (s[1].equals("app")) return open(s[2], s.length == 4 ? s[3] : "");
                else if (s[1].equals("volume")) {
                    if (s[2].equals("mute")) tv.tvMute();
                    else tv.tvVolume(Integer.parseInt(s[2]));
                }
                else tv.claudeFm(); // the old "tv music" step
                return true;
            case "server":
                if (sb == null || botId == null || botId.isEmpty()) return false;
                if (s[1].equals("press")) {
                    sb.command(botId, "press");
                    return true;
                }
                boolean want = s[1].equals("on"), isOn = server.get() == State.ON;
                if (want == isOn) return true;
                // Away (the stick doesn't answer): "off" may just be out of reach, and a press could switch it off.
                if (want && (tv == null || tv.state() == State.UNREACHABLE)) return false;
                sb.command(botId, "press");
                return true;
            case "wait":
                pause(Integer.parseInt(s[1]));
                return true;
            case "keys":
                if (tv == null) return false;
                for (int k = 1; k < s.length; k++) {
                    if (k > 1) keyGap();
                    tv.key(Scenes.KEYS.get(s[k]));
                }
                return true;
            case "music": {
                if (spotify == null) return false;
                Spotify.Device d = Spotify.find(spotify.devices(), s[1]);
                if (d == null) return false; // that speaker isn't on Spotify right now
                spotify.transfer(d.id);
                return true;
            }
            case "music-pause": // only from turning a scene off (Scenes.undo)
                if (spotify == null) return false;
                spotify.pause();
                return true;
            case "phone":
                if (openRemote == null) return false;
                openRemote.run();
                return true;
            default:
                return false;
        }
    }

    /** Opens an app on the TV, or something in it (Scenes.contents), asking first for "ask". */
    private boolean open(String pkg, String what) throws IOException {
        if (what.equals("ask")) {
            if (ask == null) return false;
            what = ask.choose(pkg);
            if (what == null) return false;
        }
        switch (what) {
            case "":
                tv.launch(pkg);
                return true;
            case "liked":
                tv.view(FireTv.LIKED, FireTv.SPOTIFY);
                return true;
            case "djx":
                tv.view(FireTv.DJ_X, FireTv.SPOTIFY);
                return true;
            case "claudefm":
                tv.claudeFm();
                return true;
            case "resume":
            case "nextup":
                if (jf == null) return false;
                java.util.List<Jellyfin.Item> items = what.equals("resume") ? jf.resumeAll() : jf.nextUpAll();
                if (items.isEmpty()) return false;
                tv.openItem(FireTv.RXFILMS, items.get(0).id);
                return true;
            default:
                if (!what.startsWith("item:")) return false;
                tv.openItem(FireTv.RXFILMS, what.substring(5));
                return true;
        }
    }

    /** Between remote keys: menus on the TV need a moment to move. */
    void keyGap() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void pause(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Integer kelvin(String v) {
        if (v.equals("warm")) return 2700;
        if (v.equals("cool")) return 6500;
        return Integer.valueOf(v);
    }
}
