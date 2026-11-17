package com.andrea.rxhome;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Scenes back to text (what the visual editor saves); parse(format(x)) gives x again. */
    static String format(List<Scene> scenes) {
        StringBuilder s = new StringBuilder();
        for (Scene sc : scenes) {
            s.append(cleanName(sc.name)).append(':');
            for (int i = 0; i < sc.steps.size(); i++) s.append(i == 0 ? " " : "; ").append(formatStep(sc.steps.get(i)));
            s.append('\n');
        }
        return s.toString();
    }

    static String formatStep(String[] step) {
        StringBuilder s = new StringBuilder();
        for (String t : step) {
            if (s.length() > 0) s.append(' ');
            s.append(t.contains(" ") ? "\"" + t + "\"" : t);
        }
        return s.toString();
    }

    /** A scene name without the format's separators (":" ";" quotes, newlines). */
    static String cleanName(String name) {
        String n = name.replaceAll("[:;\"\\r\\n]", "").replaceAll("\\s+", " ").trim();
        return n.isEmpty() ? "Scene" : n;
    }

    private static final Map<String, String> APP_NAMES = new LinkedHashMap<>();
    static {
        APP_NAMES.put("org.rxfilms.tv", "RxFilms");
        APP_NAMES.put("com.spotify.tv.android", "Spotify");
        APP_NAMES.put("org.smarttube.stable", "YouTube");
    }

    /** The TV apps the editor offers (package → name). */
    static Map<String, String> apps() {
        return APP_NAMES;
    }

    /** A step in words, e.g. "All lights on · 20%". */
    static String describe(String[] s) {
        switch (s[0]) {
            case "light": {
                String who = s[1].equals("all") ? "All lights" : s[1];
                StringBuilder d = new StringBuilder(who + " " + s[2]);
                if (s[2].equals("on") && s.length >= 4) d.append(" · ").append(s[3]).append('%');
                if (s.length == 5) {
                    int[] c = color(s[4]);
                    d.append(" · ").append(c != null ? colorName(c[0], c[1])
                        : s[4].equals("warm") ? "warm white" : s[4].equals("cool") ? "cool white" : s[4] + "K");
                }
                return d.toString();
            }
            case "tv":
                if (s[1].equals("app")) return "Open " + APP_NAMES.getOrDefault(s[2], s[2]) + " on the TV";
                if (s[1].equals("music")) return "Claude FM on the TV";
                return "TV " + s[1];
            case "server":
                return "Press the server's power button";
            case "wait":
                return "Wait " + s[1] + " s";
            default:
                return String.join(" ", s);
        }
    }

    /** {hue, saturation} of a "color:H,S" value (hue 0-360, saturation 0-100), else null. */
    static int[] color(String token) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("color:(\\d{1,3}),(\\d{1,3})").matcher(token);
        if (!m.matches()) return null;
        int h = Integer.parseInt(m.group(1)), sat = Integer.parseInt(m.group(2));
        return h <= 360 && sat <= 100 ? new int[] {h, sat} : null;
    }

    /** A colour in words, e.g. "blue", "pale green". */
    static String colorName(int hue, int sat) {
        String n = hue < 15 || hue >= 345 ? "red" : hue < 45 ? "orange" : hue < 70 ? "yellow" : hue < 160 ? "green"
            : hue < 200 ? "cyan" : hue < 250 ? "blue" : hue < 290 ? "purple" : "pink";
        return sat < 35 ? "pale " + n : n;
    }

    /** One line for Settings' scene rows. */
    static String summary(Scene sc) {
        int n = sc.steps.size();
        if (n == 0) return "No steps yet";
        return n + (n == 1 ? " step · " : " steps · ") + describe(sc.steps.get(0)) + (n > 1 ? ", …" : "");
    }

    /** The lights setting: one "Name=IP" per line; lines without "=" or starting with "#" are ignored. */
    static Map<String, String> lights(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.trim().startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0 && !line.substring(eq + 1).trim().isEmpty()) {
                out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
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
                if (ok && s.length == 5) ok = s[4].equals("warm") || s[4].equals("cool") || kelvin(s[4]) || color(s[4]) != null;
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
