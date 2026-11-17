package com.andrea.rxhome;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One-tap scenes, written as text: one scene per line, "Name: step; step; …". */
final class Scenes {

    static final String DEFAULT =
          "Movie night: light all on 20; tv on; wait 5; tv app org.rxfilms.tv\n"
        + "Music: light all on 60 warm; tv on; wait 5; tv music\n"
        + "Gaming: light all on 30; light \"LED strip\" on 100\n"
        + "All off: tv off; light all off\n";

    static final class Scene {
        final String name;
        final List<String[]> steps;

        Scene(String name, List<String[]> steps) {
            this.name = name;
            this.steps = steps;
        }
    }

    private static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    private Scenes() {}

    /** Parses scene text; a ParseException's error offset is the 1-based line number. */
    static List<Scene> parse(String text) throws ParseException {
        List<Scene> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new ParseException("Line " + (i + 1) + ": expected \"Name: steps\"", i + 1);
            List<String[]> steps = new ArrayList<>();
            for (String raw : line.substring(colon + 1).split(";")) {
                if (raw.trim().isEmpty()) continue;
                String[] step = tokens(raw, i + 1);
                check(step, i + 1);
                steps.add(step);
            }
            out.add(new Scene(line.substring(0, colon).trim(), steps));
        }
        return out;
    }

    private static String[] tokens(String raw, int line) throws ParseException {
        if ((raw.length() - raw.replace("\"", "").length()) % 2 != 0) {
            throw new ParseException("Line " + line + ": unclosed quote", line);
        }
        List<String> t = new ArrayList<>();
        Matcher m = TOKEN.matcher(raw);
        while (m.find()) t.add(m.group(1) != null ? m.group(1) : m.group(2));
        return t.toArray(new String[0]);
    }

    private static void check(String[] s, int line) throws ParseException {
        String bad = "Line " + line + ": can't understand \"" + String.join(" ", s) + "\"";
        boolean ok;
        switch (s[0]) {
            case "light":
                ok = s.length >= 3 && s.length <= 5 && (s[2].equals("on") || s[2].equals("off"));
                if (ok && s.length >= 4) ok = percent(s[3]);
                if (ok && s.length == 5) ok = s[4].equals("warm") || s[4].equals("cool") || kelvin(s[4]);
                break;
            case "tv":
                ok = s.length == 2 && (s[1].equals("on") || s[1].equals("off") || s[1].equals("music"))
                    || s.length == 3 && s[1].equals("app");
                break;
            case "server":
                ok = s.length == 2 && s[1].equals("press");
                break;
            case "wait":
                ok = s.length == 2 && s[1].matches("\\d{1,3}");
                break;
            default:
                ok = false;
        }
        if (!ok) throw new ParseException(bad, line);
    }

    private static boolean percent(String v) {
        return v.matches("\\d{1,3}") && Integer.parseInt(v) <= 100;
    }

    private static boolean kelvin(String v) {
        return v.matches("\\d{4}") && Integer.parseInt(v) >= 2500 && Integer.parseInt(v) <= 6500;
    }
}
