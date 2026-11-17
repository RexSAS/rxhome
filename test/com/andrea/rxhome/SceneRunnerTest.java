package com.andrea.rxhome;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SceneRunnerTest {

    static final List<String> log = new ArrayList<>();

    static class FakeLight extends Tapo {
        final String name;
        final boolean broken;

        FakeLight(String name, boolean broken) {
            super("0.0.0.0", "", "");
            this.name = name;
            this.broken = broken;
        }

        @Override
        void set(Boolean on, Integer brightness, Integer kelvin) throws IOException {
            if (broken) throw new IOException("unreachable");
            log.add(name + " " + on + " " + brightness + " " + kelvin);
        }
    }

    static class FakeTv extends FireTv {
        FakeTv() { super(null); }
        @Override void wake() { log.add("tv wake"); }
        @Override void sleep() { log.add("tv sleep"); }
        @Override void launch(String pkg) { log.add("tv launch " + pkg); }
    }

    public static void main(String[] a) throws Exception {
        Map<String, Tapo> lights = new LinkedHashMap<>();
        lights.put("Desk", new FakeLight("Desk", false));
        lights.put("LED strip", new FakeLight("LED strip", true));
        SceneRunner r = new SceneRunner(lights, new FakeTv(), null, null) {
            @Override void pause(int seconds) { log.add("wait " + seconds); }
        };
        List<Scenes.Scene> s = Scenes.parse(Scenes.DEFAULT);

        // Movie night: the strip is unreachable, so "light all" isn't done; the rest still runs, in order.
        int[] done = r.run(s.get(0));
        assert Arrays.equals(done, new int[] {3, 4}) : Arrays.toString(done);
        assert log.equals(Arrays.asList("Desk true 20 null", "tv wake", "wait 5", "tv launch org.rxfilms.tv")) : log;

        // "warm" is 2700 K; an unknown light name is a skipped step; server press without SwitchBot is skipped.
        log.clear();
        done = r.run(Scenes.parse("X: light Desk on 60 warm; light Nope off; server press; tv off").get(0));
        assert Arrays.equals(done, new int[] {2, 4}) : Arrays.toString(done);
        assert log.equals(Arrays.asList("Desk true 60 2700", "tv sleep")) : log;

        // All off reaches every light.
        lights.put("LED strip", new FakeLight("LED strip", false));
        log.clear();
        done = r.run(s.get(3));
        assert Arrays.equals(done, new int[] {2, 2}) : Arrays.toString(done);
        assert log.equals(Arrays.asList("tv sleep", "Desk false null null", "LED strip false null null")) : log;
    }
}
