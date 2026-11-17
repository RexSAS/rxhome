package com.andrea.rxhome;

import java.text.ParseException;
import java.util.Arrays;
import java.util.List;

public class ScenesTest {
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
