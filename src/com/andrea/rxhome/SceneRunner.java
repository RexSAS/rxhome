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

    /** Between fade steps: 5 steps ≈ 1.5 s with the lights' own round trips. */
    void fadeGap() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Fades these lights together to on/off (and brightness): 5 steps from where each is, then the white
     * or colour. Returns whether every light took it.
     */
    private boolean fade(Collection<Tapo> targets, boolean on, Integer brightness, Integer kelvin, int[] color) {
        java.util.Map<Tapo, java.util.List<Integer>> plan = new java.util.LinkedHashMap<>();
        boolean all = true;
        for (Tapo t : targets) {
            java.util.List<Integer> lv = new java.util.ArrayList<>();
            try {
                org.json.JSONObject i = t.info();
                int now = i.optBoolean("device_on") ? i.optInt("brightness", 100) : 0;
                if (!on) {
                    for (int k = 1; k <= 5 && now > 0; k++) lv.add(Math.max(1, now * (5 - k) / 5));
                } else if (brightness != null) {
                    if (now == 0) lv.add(1);
                    for (int k = 1; k <= 5; k++) lv.add(now + (brightness - now) * k / 5);
                }
            } catch (IOException | RuntimeException e) {
                all = false;
                continue;
            }
            plan.put(t, lv);
        }
        int steps = 0;
        for (java.util.List<Integer> lv : plan.values()) steps = Math.max(steps, lv.size());
        for (int k = 0; k < steps; k++) {
            if (k > 0) fadeGap();
            for (java.util.Iterator<java.util.Map.Entry<Tapo, java.util.List<Integer>>> it = plan.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<Tapo, java.util.List<Integer>> e = it.next();
                java.util.List<Integer> lv = e.getValue();
                if (k >= lv.size()) continue;
                try {
                    e.getKey().set(true, lv.get(k), on && k == lv.size() - 1 ? kelvin : null);
                } catch (IOException ex) {
                    all = false;
                    it.remove();
                }
            }
        }
        for (java.util.Map.Entry<Tapo, java.util.List<Integer>> e : plan.entrySet()) {
            try {
                if (!on) e.getKey().set(false, null, null);
                else if (e.getValue().isEmpty()) e.getKey().set(true, null, kelvin); // no level to fade to
                if (on && color != null) e.getKey().color(color[0], color[1]);
            } catch (IOException ex) {
                all = false;
            }
        }
        return all;
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
                            int volume = smooth ? tv.fadeVolume() : -1; // sound fades out, then off
                            tv.sleep();
                            if (volume > 0) tv.tvVolume(volume); // back for next time
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
