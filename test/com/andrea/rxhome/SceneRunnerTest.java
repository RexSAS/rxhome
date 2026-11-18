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
        @Override synchronized void key(int code) { log.add("key " + code); }
        @Override void tvVolume(int level) { log.add("tv volume " + level); }
        @Override void tvMute() { log.add("tv mute"); }
    }

    static class FakeBot extends SwitchBot {
        FakeBot() { super("t", "s"); }
        @Override void command(String id, String cmd) { log.add("bot " + cmd); }
    }

    static class FakeSpotify extends Spotify {
        FakeSpotify() { super("cid", "r", x -> {}); }
        @Override List<Spotify.Device> devices() {
            return Arrays.asList(new Spotify.Device("d1", "Cucina", "Speaker", false), new Spotify.Device("d2", "Echo Dot", "Speaker", true));
        }
        @Override void transfer(String id) { log.add("spotify " + id); }
        @Override void pause() { log.add("spotify pause"); }
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
            @Override void keyGap() { log.add("gap"); }
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
        // "Choose when run" asks as soon as the scene starts; the pick waits for its step's turn.
        tvPower = State.OFF;
        run(r, "X: tv on; wait 5; tv app com.spotify.tv.android ask");
        assert log.equals(Arrays.asList("ask com.spotify.tv.android", "tv wake", "wait 5",
            "tv view spotify:playlist:37i9dQZF1EYkqdzj48dyYq")) : log;
        tvPower = State.ON;
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

        // Remote keys: pressed in order, with a gap between them so the TV keeps up.
        assert Arrays.equals(run(r, "X: keys home down ok"), new int[] {1, 1});
        assert log.equals(Arrays.asList("key 3", "gap", "key 20", "gap", "key 23")) : log;

        // TV volume: a level, or mute.
        assert Arrays.equals(run(r, "X: tv volume 20; tv volume mute"), new int[] {2, 2});
        assert log.equals(Arrays.asList("tv volume 20", "tv mute")) : log;

        // Music: moved to the named speaker; an unknown one, or no Spotify, isn't done.
        assert Arrays.equals(run(r, "X: music Bagno"), new int[] {0, 1}) && log.isEmpty() : log; // not connected
        r.music(new FakeSpotify());
        assert Arrays.equals(run(r, "X: music cucina"), new int[] {1, 1}) && log.equals(Arrays.asList("spotify d1")) : log;
        assert Arrays.equals(run(r, "X: music Bagno"), new int[] {0, 1}) && log.isEmpty() : log;
        // Turning a scene off pauses its music.
        log.clear();
        assert Arrays.equals(r.run(Scenes.undo(Scenes.parse("X: music Cucina; light Desk on").get(0))), new int[] {2, 2}) : log;
        assert log.equals(Arrays.asList("spotify pause", "Desk false null null")) : log;

        // All off reaches every light.
        lights.put("LED strip", new FakeLight("LED strip", false));
        done = run(r, Scenes.format(Arrays.asList(s.get(3))));
        assert Arrays.equals(done, new int[] {2, 2}) : Arrays.toString(done);
        assert log.equals(Arrays.asList("tv sleep", "Desk false null null", "LED strip false null null")) : log;
    }
}
