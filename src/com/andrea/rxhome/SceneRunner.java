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

    /** Returns {steps done, steps total}. */
    int[] run(Scenes.Scene scene) {
        int done = 0;
        for (String[] s : scene.steps) {
            try {
                if (step(s)) done++;
            } catch (IOException | RuntimeException e) {
                // Unreachable or refused: counted as not done; the next steps still run.
            }
        }
        return new int[] {done, scene.steps.size()};
    }

    private boolean step(String[] s) throws IOException {
        switch (s[0]) {
            case "light":
                Collection<Tapo> targets = s[1].equals("all") ? lights.values()
                    : lights.containsKey(s[1]) ? Collections.singleton(lights.get(s[1])) : Collections.<Tapo>emptySet();
                if (targets.isEmpty()) return false;
                boolean on = s[2].equals("on");
                Integer brightness = s.length >= 4 ? Integer.valueOf(s[3]) : null;
                int[] color = s.length == 5 ? Scenes.color(s[4]) : null;
                Integer kelvin = s.length == 5 && color == null ? kelvin(s[4]) : null;
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
                        else tv.sleep();
                    }
                }
                else if (s[1].equals("app")) return open(s[2], s.length == 4 ? s[3] : "");
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
