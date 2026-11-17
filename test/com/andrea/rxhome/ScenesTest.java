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
