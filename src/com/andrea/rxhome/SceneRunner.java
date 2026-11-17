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

    SceneRunner(Map<String, Tapo> lights, FireTv tv, SwitchBot sb, String botId) {
        this.lights = lights;
        this.tv = tv;
        this.sb = sb;
        this.botId = botId;
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
                if (s[1].equals("on")) tv.wake();
                else if (s[1].equals("off")) tv.sleep();
                else if (s[1].equals("app")) tv.launch(s[2]);
                else tv.play(FireTv.claudeFmId());
                return true;
            case "server":
                if (sb == null || botId == null || botId.isEmpty()) return false;
                sb.command(botId, "press");
                return true;
            case "wait":
                pause(Integer.parseInt(s[1]));
                return true;
            default:
                return false;
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
