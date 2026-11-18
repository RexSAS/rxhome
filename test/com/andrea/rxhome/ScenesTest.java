package com.andrea.rxhome;

import java.text.ParseException;
import java.util.Arrays;
import java.util.List;

public class ScenesTest {
    static String undo(String scene) throws Exception {
        StringBuilder s = new StringBuilder();
        for (String[] step : Scenes.undo(Scenes.parse(scene).get(0)).steps) {
            if (s.length() > 0) s.append("; ");
            s.append(Scenes.formatStep(step));
        }
        return s.toString();
    }

    public static void main(String[] a) throws Exception {
        List<Scenes.Scene> s = Scenes.parse(Scenes.DEFAULT);
        assert s.size() == 4 : s.size();
        assert s.get(0).name.equals("Movie night");
        assert s.get(0).steps.size() == 4;
        assert Arrays.equals(s.get(0).steps.get(0), new String[] {"light", "all", "on", "20"});
        assert Arrays.equals(s.get(2).steps.get(1), new String[] {"light", "LED strip", "on", "100"});
        assert Arrays.equals(s.get(1).steps.get(0), new String[] {"light", "all", "on", "60", "warm"});

        // Comments and blank lines are ignored; errors carry the 1-based line number.
        assert Scenes.parse("# hi\n\nA: tv on").size() == 1;
        failsOnLine("X: lamp on", 1);
        failsOnLine("A: tv on\nBad", 2);
        failsOnLine("A: light all on 150", 1);
        failsOnLine("A: wait soon", 1);
        failsOnLine("A: tv dance", 1);
        failsOnLine("A: light \"Desk on", 1);

        // The visual editor writes the same format back: a round trip changes nothing.
        assert Scenes.format(Scenes.parse(Scenes.DEFAULT)).equals(Scenes.DEFAULT) : Scenes.format(Scenes.parse(Scenes.DEFAULT));
        assert Scenes.formatStep(new String[] {"light", "LED strip", "on", "100"}).equals("light \"LED strip\" on 100");
        // Names can't carry the format's own separators.
        assert Scenes.cleanName("Movie: night;\n") .equals("Movie night");
        assert Scenes.cleanName("   ").equals("Scene");

        // What each step says in the editor and in Settings' scene rows.
        assert Scenes.describe(new String[] {"light", "all", "on", "20"}).equals("All lights on · 20%");
        assert Scenes.describe(new String[] {"light", "all", "on", "60", "warm"}).equals("All lights on · 60% · warm white");
        assert Scenes.describe(new String[] {"light", "Desk", "off"}).equals("Desk off");
        assert Scenes.describe(new String[] {"tv", "on"}).equals("TV on");
        assert Scenes.describe(new String[] {"tv", "app", "org.rxfilms.tv"}).equals("Open RxFilms on the TV");
        assert Scenes.describe(new String[] {"tv", "app", "com.example"}).equals("Open com.example on the TV");
        assert Scenes.describe(new String[] {"tv", "music"}).equals("Claude FM on the TV");
        assert Scenes.describe(new String[] {"server", "press"}).equals("Press the server's power button");
        assert Scenes.describe(new String[] {"wait", "5"}).equals("Wait 5 s");
        assert Scenes.summary(Scenes.parse(Scenes.DEFAULT).get(0)).equals("4 steps · All lights on · 20%, …")
            : Scenes.summary(Scenes.parse(Scenes.DEFAULT).get(0));

        // Colour in a light step: color:<hue 0-360>,<saturation 0-100>, named in words.
        assert Arrays.equals(Scenes.parse("A: light all on 80 color:210,80").get(0).steps.get(0),
            new String[] {"light", "all", "on", "80", "color:210,80"});
        failsOnLine("A: light all on 80 color:400,10", 1);
        failsOnLine("A: light all on 80 color:red", 1);
        assert Scenes.describe(new String[] {"light", "all", "on", "80", "color:210,80"}).equals("All lights on · 80% · blue");
        assert Scenes.describe(new String[] {"light", "Desk", "on", "50", "color:0,100"}).equals("Desk on · 50% · red");
        assert Scenes.describe(new String[] {"light", "Desk", "on", "50", "color:120,20"}).equals("Desk on · 50% · pale green");
        assert Arrays.equals(Scenes.color("color:210,80"), new int[] {210, 80});
        assert Scenes.color("warm") == null;

        // "Server must be on/off" (the old "server press" still reads).
        assert Scenes.parse("A: server on; server off; server press").get(0).steps.size() == 3;
        failsOnLine("A: server sideways", 1);
        assert Scenes.describe(new String[] {"server", "on"}).equals("Server must be on");
        assert Scenes.describe(new String[] {"server", "off"}).equals("Server must be off");

        // Open an app, and what in it: just the app, chosen when the scene runs, or something specific.
        assert Scenes.parse("A: tv app com.spotify.tv.android liked; tv app org.rxfilms.tv ask; tv app org.smarttube.stable claudefm")
            .get(0).steps.size() == 3;
        failsOnLine("A: tv app com.spotify.tv.android claudefm", 1); // not a Spotify thing
        failsOnLine("A: tv app com.example liked", 1);              // only the known apps have extras
        assert Scenes.describe(new String[] {"tv", "app", "com.spotify.tv.android", "liked"}).equals("Spotify: Liked Songs on the TV");
        assert Scenes.describe(new String[] {"tv", "app", "org.rxfilms.tv", "ask"}).equals("Open RxFilms on the TV · choose when run");
        assert Scenes.describe(new String[] {"tv", "app", "org.smarttube.stable", "claudefm"}).equals("YouTube: Claude FM on the TV");
        assert Scenes.contents("org.rxfilms.tv").keySet().toString().equals("[, ask, resume, nextup]");
        assert Scenes.contents("com.spotify.tv.android").get("djx").equals("DJ X");
        assert Scenes.contents("com.example").keySet().toString().equals("[]");
        // The default Music scene plays Claude FM through YouTube now.
        assert Scenes.DEFAULT.contains("Music: light all on 60 warm; tv on; wait 5; tv app org.smarttube.stable claudefm");

        // Open the remote on the phone.
        assert Scenes.parse("A: tv on; phone remote").get(0).steps.size() == 2;
        failsOnLine("A: phone dance", 1);
        assert Scenes.describe(new String[] {"phone", "remote"}).equals("Open the remote on the phone");

        // Remote keys: a sequence pressed on the TV, by name.
        assert Arrays.equals(Scenes.parse("A: keys home down down ok").get(0).steps.get(0),
            new String[] {"keys", "home", "down", "down", "ok"});
        failsOnLine("A: keys home jump", 1);
        failsOnLine("A: keys", 1);
        assert Scenes.describe(new String[] {"keys", "home", "down", "down", "ok"}).equals("Remote: Home ↓ ↓ OK");
        assert Scenes.KEYS.get("ok") == 23 && Scenes.KEYS.get("back") == 4 && Scenes.KEYS.get("play") == 85;

        // TV volume: a level on the TV's own 0-60 scale, or mute.
        assert Scenes.parse("A: tv volume 20; tv volume mute; tv volume 0").get(0).steps.size() == 3;
        failsOnLine("A: tv volume 61", 1);
        failsOnLine("A: tv volume loud", 1);
        assert Scenes.describe(new String[] {"tv", "volume", "20"}).equals("TV volume 20");
        assert Scenes.describe(new String[] {"tv", "volume", "mute"}).equals("TV muted");

        // Music: move Spotify to a speaker, by its name.
        assert Scenes.parse("A: music Cucina; music \"Echo Dot\"").get(0).steps.size() == 2;
        failsOnLine("A: music", 1);
        assert Scenes.describe(new String[] {"music", "Cucina"}).equals("Music → Cucina");

        // Tapping an active scene again turns off what it turned on: music first, then the TV, then the
        // lights it left on. The server, waits, keys, volume and app steps are left alone.
        assert undo("Movie night: light all on 20; tv on; wait 5; tv app org.rxfilms.tv; server on")
            .equals("tv off; light all off") : undo("M: light all on 20; tv on");
        assert undo("M: light Desk on 60 warm; light \"LED strip\" on 30 color:200,50; music Cucina; tv on; tv volume 20")
            .equals("music-pause; tv off; light Desk off; light \"LED strip\" off") : undo("M: light Desk on; music Cucina");
        assert undo("M: light Desk on; light Desk off; tv on; tv off").isEmpty();       // left off: nothing to undo
        assert undo("M: tv off; tv on; light Desk off; light Desk on 50").equals("tv off; light Desk off");
        assert undo("All off: tv off; light all off; server off").isEmpty();
        assert undo("M: tv app org.rxfilms.tv; keys home; phone remote; wait 3; server press").isEmpty();

        // Lights setting: "Name=IP" per line, order kept, junk lines ignored.
        java.util.Map<String, String> l = Scenes.lights("Ceiling=192.168.1.20\n LED strip = 192.168.1.21 \n\nnonsense\n");
        assert l.size() == 2;
        assert l.get("LED strip").equals("192.168.1.21");
        assert l.keySet().iterator().next().equals("Ceiling");
    }

    static void failsOnLine(String text, int line) {
        try {
            Scenes.parse(text);
        } catch (ParseException e) {
            assert e.getErrorOffset() == line : text + " -> line " + e.getErrorOffset();
            return;
        }
        throw new AssertionError("no error for: " + text);
    }
}
