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
        + "Music: light all on 60 warm; tv on; wait 5; tv app org.smarttube.stable claudefm\n"
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

    /** Remote keys a scene can press (name → Android keycode), and how the editor shows them. */
    static final Map<String, Integer> KEYS = new LinkedHashMap<>();
    static final Map<String, String> KEY_LABELS = new LinkedHashMap<>();
    static {
        String[][] k = {{"up", "19", "↑"}, {"down", "20", "↓"}, {"left", "21", "←"}, {"right", "22", "→"},
            {"ok", "23", "OK"}, {"back", "4", "Back"}, {"home", "3", "Home"}, {"menu", "82", "Menu"},
            {"play", "85", "⏯"}, {"rew", "89", "◀◀"}, {"ff", "90", "▶▶"}};
        for (String[] e : k) {
            KEYS.put(e[0], Integer.valueOf(e[1]));
            KEY_LABELS.put(e[0], e[2]);
        }
    }

    private static final Map<String, String> APP_NAMES = new LinkedHashMap<>();
    static {
        APP_NAMES.put("org.rxfilms.tv", "RxFilms");
        APP_NAMES.put("com.spotify.tv.android", "Spotify");
        APP_NAMES.put("org.smarttube.stable", "YouTube");
    }

    /**
     * What a scene can open in an app (value → label): "" just the app, "ask" chosen when the scene runs,
     * then the app's own shortcuts. Empty for apps rxhome doesn't know.
     */
    static Map<String, String> contents(String pkg) {
        Map<String, String> m = new LinkedHashMap<>();
        if (!APP_NAMES.containsKey(pkg)) return m;
        m.put("", "Just the app");
        m.put("ask", "Choose when run");
        if (pkg.equals("org.rxfilms.tv")) {
            m.put("resume", "Continue watching");
            m.put("nextup", "Next up");
        } else if (pkg.equals("com.spotify.tv.android")) {
            m.put("liked", "Liked Songs");
            m.put("djx", "DJ X");
        } else {
            m.put("claudefm", "Claude FM");
        }
        return m;
    }

    /** The TV apps the editor offers (package → name). */
    static Map<String, String> apps() {
        return APP_NAMES;
    }

    /** A step in words, e.g. "All lights on · 20%". */
    static String describe(String[] s) {
        String tvName = tvTarget(s);
        if (tvName != null) return describe(untargeted(s)) + " · " + tvName;
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
                if (s[1].equals("app")) {
                    String app = APP_NAMES.getOrDefault(s[2], s[2]);
                    if (s.length < 4) return "Open " + app + " on the TV";
                    if (s[3].equals("ask")) return "Open " + app + " on the TV · choose when run";
                    return app + ": " + contents(s[2]).get(s[3]) + " on the TV";
                }
                if (s[1].equals("music")) return "Claude FM on the TV";
                if (s[1].equals("volume")) return s[2].equals("mute") ? "TV muted" : "TV volume " + s[2];
                return "TV " + s[1];
            case "server":
                return s[1].equals("press") ? "Press the server's power button" : "Server must be " + s[1];
            case "wait":
                return "Wait " + s[1] + " s";
            case "phone":
                return "Open the remote on the phone";
            case "music":
                return s.length < 2 ? "Music → pick a speaker" : "Music → " + s[1];
            case "keys": {
                StringBuilder d = new StringBuilder("Remote:");
                for (int k = 1; k < s.length; k++) d.append(' ').append(KEY_LABELS.get(s[k]));
                return d.toString();
            }
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

    /**
     * What turns a scene off again: pause its music, switch off the TV and the lights it leaves on.
     * The server, waits, keys, volume and app steps are left alone. Empty = nothing to switch off.
     */
    static Scene undo(Scene scene) {
        boolean music = false;
        Map<String, Boolean> tvs = new LinkedHashMap<>(); // per TV ("" = the first): on or off at the end
        Map<String, Boolean> lights = new LinkedHashMap<>(); // the last on/off each light was left in
        for (String[] s : scene.steps) {
            if (s[0].equals("music")) music = true;
            else if (s[0].equals("tv")) {
                String[] b = untargeted(s);
                if (b[1].equals("on") || b[1].equals("off")) {
                    String t = tvTarget(s) == null ? "" : tvTarget(s);
                    tvs.remove(t);
                    tvs.put(t, b[1].equals("on"));
                }
            }
            else if (s[0].equals("light")) {
                lights.remove(s[1]); // keep the order of the last mention
                lights.put(s[1], s[2].equals("on"));
            }
        }
        List<String[]> steps = new ArrayList<>();
        if (music) steps.add(new String[] {"music-pause"});
        for (Map.Entry<String, Boolean> e : tvs.entrySet()) {
            if (e.getValue()) steps.add(e.getKey().isEmpty() ? new String[] {"tv", "off"} : new String[] {"tv", "off", "@" + e.getKey()});
        }
        for (Map.Entry<String, Boolean> e : lights.entrySet()) {
            if (e.getValue()) steps.add(new String[] {"light", e.getKey(), "off"});
        }
        return new Scene(scene.name, steps);
    }

    /**
     * From one active scene to another: the old one switches off only what the new one won't use (no TV
     * off-and-on, no music pause before it moves, lights fade straight to their new level), then the new one.
     * lightNames: every light, so "all lights off" can spare the ones the new scene sets.
     */
    static Scene swap(Scene from, Scene to, java.util.Collection<String> lightNames) {
        boolean music = false, allLights = false;
        java.util.Set<String> tvs = new java.util.HashSet<>(), lit = new java.util.HashSet<>();
        for (String[] s : to.steps) {
            if (s[0].equals("music")) music = true;
            else if (s[0].equals("tv") || s[0].equals("keys")) { // any step on a TV: the new scene decides it (even "off")
                tvs.add(tvTarget(s) == null ? "" : tvTarget(s).toLowerCase());
            } else if (s[0].equals("phone")) { // the remote on the phone drives the first TV
                tvs.add("");
            } else if (s[0].equals("light")) {
                if (s[1].equals("all")) allLights = true;
                else lit.add(s[1]);
            }
        }
        List<String[]> steps = new ArrayList<>();
        for (String[] u : undo(from).steps) {
            if (u[0].equals("music-pause")) {
                if (!music) steps.add(u);
            } else if (u[0].equals("tv")) {
                if (!tvs.contains(tvTarget(u) == null ? "" : tvTarget(u).toLowerCase())) steps.add(u);
            } else if (!allLights) { // a light off
                if (!u[1].equals("all")) {
                    if (!lit.contains(u[1])) steps.add(u);
                } else if (lightNames.isEmpty()) {
                    steps.add(u);
                } else {
                    for (String n : lightNames) if (!lit.contains(n)) steps.add(new String[] {"light", n, "off"});
                }
            }
        }
        steps.addAll(to.steps);
        return new Scene(to.name, steps);
    }

    /** The TV a "tv" / "keys" step names with a last "@Name" token; null = the first TV. */
    static String tvTarget(String[] s) {
        if (!(s[0].equals("tv") || s[0].equals("keys")) || s.length < 2 || !s[s.length - 1].startsWith("@")) return null;
        return s[s.length - 1].substring(1);
    }

    /** Whether the scene sets this light (by its name or "all"). */
    static boolean usesLight(Scene sc, String light) {
        for (String[] s : sc.steps) if (s[0].equals("light") && (s[1].equals("all") || s[1].equals(light))) return true;
        return false;
    }

    /** Whether the scene uses this TV (TV and remote-key steps; no "@Name" = the first TV). */
    static boolean usesTv(Scene sc, String tvName, boolean first) {
        for (String[] s : sc.steps) {
            if (!s[0].equals("tv") && !s[0].equals("keys")) continue;
            String t = tvTarget(s);
            if (t == null ? first : t.equalsIgnoreCase(tvName)) return true;
        }
        return false;
    }

    static boolean usesMusic(Scene sc) {
        for (String[] s : sc.steps) if (s[0].equals("music")) return true;
        return false;
    }

    /** Whether the scene leaves this light on (its last word on it, by name or "all"). */
    static boolean leavesOn(Scene sc, String light) {
        boolean on = false;
        for (String[] s : sc.steps) {
            if (s[0].equals("light") && (s[1].equals("all") || s[1].equals(light))) on = s[2].equals("on");
        }
        return on;
    }

    /** Scene text with a TV's new name in its steps; unreadable text is left as it is. */
    static String renameTv(String text, String oldName, String newName) {
        List<Scene> list;
        try {
            list = parse(text);
        } catch (ParseException e) {
            return text;
        }
        for (Scene sc : list) {
            for (String[] s : sc.steps) {
                String t = tvTarget(s);
                if (t != null && t.equalsIgnoreCase(oldName)) s[s.length - 1] = "@" + newName;
            }
        }
        return format(list);
    }

    /** The step without its "@Name". */
    static String[] untargeted(String[] s) {
        return tvTarget(s) == null ? s : java.util.Arrays.copyOf(s, s.length - 1);
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
        String tvName = tvTarget(s);
        if (tvName != null) {
            if (tvName.trim().isEmpty() || s.length < 3) throw new ParseException(bad, line);
            s = untargeted(s);
        }
        boolean ok;
        switch (s[0]) {
            case "light":
                ok = s.length >= 3 && s.length <= 5 && (s[2].equals("on") || s[2].equals("off"));
                if (ok && s.length >= 4) ok = percent(s[3]);
                if (ok && s.length == 5) ok = s[4].equals("warm") || s[4].equals("cool") || kelvin(s[4]) || color(s[4]) != null;
                break;
            case "tv":
                ok = s.length == 2 && (s[1].equals("on") || s[1].equals("off") || s[1].equals("music"))
                    || s.length == 3 && s[1].equals("app")
                    || s.length == 3 && s[1].equals("volume")
                        && (s[2].equals("mute") || s[2].matches("\\d{1,2}") && Integer.parseInt(s[2]) <= 60)
                    || s.length == 4 && s[1].equals("app") && !s[3].isEmpty() && contents(s[2]).containsKey(s[3]);
                break;
            case "server":
                ok = s.length == 2 && (s[1].equals("on") || s[1].equals("off") || s[1].equals("press"));
                break;
            case "wait":
                ok = s.length == 2 && s[1].matches("\\d{1,3}");
                break;
            case "phone":
                ok = s.length == 2 && s[1].equals("remote");
                break;
            case "music": // Spotify moves to this speaker (its name in Spotify)
                ok = s.length == 2 && !s[1].trim().isEmpty();
                break;
            case "keys":
                ok = s.length >= 2;
                for (int k = 1; k < s.length; k++) ok &= KEYS.containsKey(s[k]);
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

    /** What running a scene does now (a tap or its timer). */
    static final class Plan {
        final Scene todo, before; // before: the active scene it smoothly swaps from, or null
        final boolean turningOff;
        final java.util.Set<String> activeAfter;

        Plan(Scene todo, Scene before, boolean turningOff, java.util.Set<String> activeAfter) {
            this.todo = todo;
            this.before = before;
            this.turningOff = turningOff;
            this.activeAfter = activeAfter;
        }
    }

    /**
     * A scene that switches things on is a toggle: run while active, it switches them off instead. One
     * that only switches things off (All off) is a plain button and clears every active scene. One
     * active scene at a time: another one active means a smooth swap (fades, nothing off-and-on).
     */
    static Plan plan(Scene scene, List<Scene> all, java.util.Set<String> active, java.util.Collection<String> lights) {
        Scene off = undo(scene), before = null;
        boolean toggle = !off.steps.isEmpty(), turningOff = toggle && active.contains(scene.name);
        if (toggle && !turningOff) {
            for (Scene o : all) if (!o.name.equals(scene.name) && active.contains(o.name)) before = o;
        }
        Scene todo = turningOff ? off : before != null ? swap(before, scene, lights) : scene;
        java.util.Set<String> after = new java.util.LinkedHashSet<>();
        if (toggle && !turningOff) after.add(scene.name); // the only active one now
        return new Plan(todo, before, turningOff, after);
    }

    /**
     * When a timer set to hour:minute runs: on the picked day ({year, month 0-based, day}), or with no
     * day the next time it's that hour (today, or tomorrow if it has passed).
     */
    static long timerAt(java.util.Calendar now, int[] day, int hour, int minute) {
        java.util.Calendar c = (java.util.Calendar) now.clone();
        if (day != null) c.set(day[0], day[1], day[2]);
        c.set(java.util.Calendar.HOUR_OF_DAY, hour);
        c.set(java.util.Calendar.MINUTE, minute);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        if (day == null && !c.after(now)) c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }
}
