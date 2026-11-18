package com.andrea.rxhome;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SceneRunnerTest {

    static final List<String> log = new ArrayList<>();
    static State stick = State.ON, server = State.ON, tvPower = State.OFF;
    static String answer;

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

        @Override
        void color(int hue, int saturation) throws IOException {
            if (broken) throw new IOException("unreachable");
            log.add(name + " color " + hue + " " + saturation);
        }
    }

    static class FakeTv extends FireTv {
        FakeTv() { super(null); }
        @Override State state() { return stick; }
        @Override State tvState(State s) { return tvPower; }
        @Override void wake() { log.add("tv wake"); }
        @Override void sleep() { log.add("tv sleep"); }
        @Override void launch(String pkg) { log.add("tv launch " + pkg); }
        @Override void view(String uri, String pkg) { log.add("tv view " + uri); }
        @Override void claudeFm() { log.add("tv play"); }
        @Override void openItem(String component, String id) { log.add("tv item " + id); }
    }

    static class FakeBot extends SwitchBot {
        FakeBot() { super("t", "s"); }
        @Override void command(String id, String cmd) { log.add("bot " + cmd); }
    }

    static int[] run(SceneRunner r, String scene) throws Exception {
        log.clear();
        return r.run(Scenes.parse(scene).get(0));
    }

    public static void main(String[] a) throws Exception {
        Map<String, Tapo> lights = new LinkedHashMap<>();
        lights.put("Desk", new FakeLight("Desk", false));
        lights.put("LED strip", new FakeLight("LED strip", true));
        SceneRunner r = new SceneRunner(lights, new FakeTv(), new FakeBot(), "bot1", () -> server, null, pkg -> {
            log.add("ask " + pkg);
            return answer;
        }, () -> log.add("phone remote")) {
            @Override void pause(int seconds) { log.add("wait " + seconds); }
        };
        List<Scenes.Scene> s = Scenes.parse(Scenes.DEFAULT);

        // Movie night: the strip is unreachable, so "light all" isn't done; the rest still runs, in order.
        log.clear();
        int[] done = r.run(s.get(0));
        assert Arrays.equals(done, new int[] {3, 4}) : Arrays.toString(done);
        assert log.equals(Arrays.asList("Desk true 20 null", "tv wake", "wait 5", "tv launch org.rxfilms.tv")) : log;

        // "warm" is 2700 K; an unknown light name is a skipped step.
        tvPower = State.ON;
        done = run(r, "X: light Desk on 60 warm; light Nope off; tv off");
        assert Arrays.equals(done, new int[] {2, 3}) : Arrays.toString(done);
        assert log.equals(Arrays.asList("Desk true 60 2700", "tv sleep")) : log;

        // A colour: brightness first, then the colour.
        done = run(r, "X: light Desk on 80 color:210,80");
        assert Arrays.equals(done, new int[] {1, 1}) && log.equals(Arrays.asList("Desk true 80 null", "Desk color 210 80")) : log;

        // Server must be on/off: the button is pressed only when it's in the other state.
        server = State.ON;
        assert Arrays.equals(run(r, "X: server on"), new int[] {1, 1}) && log.isEmpty() : log;
        assert Arrays.equals(run(r, "X: server off"), new int[] {1, 1}) && log.equals(Arrays.asList("bot press")) : log;
        server = State.UNREACHABLE; // at home (the stick answers), so the server is off
        assert Arrays.equals(run(r, "X: server on"), new int[] {1, 1}) && log.equals(Arrays.asList("bot press")) : log;
        assert Arrays.equals(run(r, "X: server off"), new int[] {1, 1}) && log.isEmpty() : log;
        stick = State.UNREACHABLE; // away: can't tell, so never press "on" (it might switch it off)
        assert Arrays.equals(run(r, "X: server on"), new int[] {0, 1}) && log.isEmpty() : log;
        stick = State.ON;
        server = State.ON;

        // What to open: something specific, or chosen when the scene runs.
        run(r, "X: tv app com.spotify.tv.android liked");
        assert log.equals(Arrays.asList("tv view spotify:collection:tracks")) : log;
        run(r, "X: tv app org.smarttube.stable claudefm");
        assert log.equals(Arrays.asList("tv play")) : log;
        answer = "djx";
        run(r, "X: tv app com.spotify.tv.android ask");
        assert log.equals(Arrays.asList("ask com.spotify.tv.android", "tv view spotify:playlist:37i9dQZF1EYkqdzj48dyYq")) : log;
        answer = "item:0f3a9b6c1d2e4f5a8b7c6d5e4f3a2b1c"; // a Continue watching / Next up item picked on the card
        run(r, "X: tv app org.rxfilms.tv ask");
        assert log.equals(Arrays.asList("ask org.rxfilms.tv", "tv item 0f3a9b6c1d2e4f5a8b7c6d5e4f3a2b1c")) : log;
        answer = "";
        run(r, "X: tv app org.rxfilms.tv ask");
        assert log.equals(Arrays.asList("ask org.rxfilms.tv", "tv launch org.rxfilms.tv")) : log;
        answer = null; // card cancelled: not done, the scene goes on
        assert Arrays.equals(run(r, "X: tv app org.rxfilms.tv ask; tv off"), new int[] {1, 2}) : log;
        // RxFilms' Continue watching needs Jellyfin: not signed in = not done.
        assert Arrays.equals(run(r, "X: tv app org.rxfilms.tv resume"), new int[] {0, 1}) : log;

        // The remote on the phone opens, the scene goes on.
        assert Arrays.equals(run(r, "X: phone remote; tv off"), new int[] {2, 2}) && log.equals(Arrays.asList("phone remote", "tv sleep")) : log;

        // TV on/off check the TV first, like the server: already there = nothing sent (a second
        // "off" mustn't turn it on).
        tvPower = State.OFF;
        assert Arrays.equals(run(r, "X: tv off"), new int[] {1, 1}) && log.isEmpty() : log;
        assert Arrays.equals(run(r, "X: tv on"), new int[] {1, 1}) && log.equals(Arrays.asList("tv wake")) : log;
        tvPower = State.ON;
        assert Arrays.equals(run(r, "X: tv on"), new int[] {1, 1}) && log.isEmpty() : log;
        assert Arrays.equals(run(r, "X: tv off"), new int[] {1, 1}) && log.equals(Arrays.asList("tv sleep")) : log;

        // All off reaches every light.
        lights.put("LED strip", new FakeLight("LED strip", false));
        done = run(r, Scenes.format(Arrays.asList(s.get(3))));
        assert Arrays.equals(done, new int[] {2, 2}) : Arrays.toString(done);
        assert log.equals(Arrays.asList("tv sleep", "Desk false null null", "LED strip false null null")) : log;
    }
}
