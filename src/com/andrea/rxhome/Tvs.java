package com.andrea.rxhome;

import java.util.ArrayList;
import java.util.List;

/**
 * The TVs: each groups a stick (FireStick / Android TV: status, remote, apps) and the TV's own API
 * (Philips: power, volume), either optional. Stored one per line as "id|name|stick|philips".
 */
final class Tvs {

    static final class Tv {
        final String id, name, stick, philips; // "" = that part isn't set

        Tv(String id, String name, String stick, String philips) {
            this.id = id;
            this.name = name;
            this.stick = stick;
            this.philips = philips;
        }
    }

    private Tvs() {}

    static List<Tv> parse(String text) {
        List<Tv> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] p = line.split("\\|", -1);
            if (p.length != 4 || p[0].trim().isEmpty() || p[1].trim().isEmpty()) continue;
            out.add(new Tv(p[0].trim(), p[1].trim(), p[2].trim(), p[3].trim()));
        }
        return out;
    }

    /** The TV list, or (nothing saved yet) today's single FireStick + Philips as the first TV. */
    static List<Tv> orLegacy(String text, String stick, String philips) {
        List<Tv> l = parse(text);
        if (l.isEmpty() && text.trim().isEmpty()) l.add(new Tv("tv1", "Tv", stick, philips));
        return l;
    }

    static String format(List<Tv> tvs) {
        StringBuilder s = new StringBuilder();
        for (Tv t : tvs) {
            if (s.length() > 0) s.append('\n');
            s.append(t.id).append('|').append(t.name).append('|').append(t.stick).append('|').append(t.philips);
        }
        return s.toString();
    }

    /** A setting for this TV: the first TV keeps the settings from before there was a list. */
    static String key(String id, String base) {
        return id.equals("tv1") ? base : base + "_" + id;
    }

    static String newId(List<Tv> tvs) {
        int max = 0;
        for (Tv t : tvs) {
            try {
                max = Math.max(max, Integer.parseInt(t.id.substring(2)));
            } catch (RuntimeException e) {
                // Not "tvN": ignored.
            }
        }
        return "tv" + (max + 1);
    }

    /** By id or name (any case); null if none. */
    static Tv find(List<Tv> tvs, String idOrName) {
        if (idOrName == null) return null;
        for (Tv t : tvs) if (t.id.equals(idOrName) || t.name.equalsIgnoreCase(idOrName.trim())) return t;
        return null;
    }

    static String clean(String name) {
        String n = name.replaceAll("[|\\r\\n\"@;:]", " ").replaceAll("\\s+", " ").trim();
        return n.isEmpty() ? "TV" : n;
    }

    /** Settings' row line, with the devices' names (name: address → name, or the address if unknown). */
    static String describe(Tv t, boolean paired, java.util.function.Function<String, String> name) {
        if (t.stick.isEmpty() && t.philips.isEmpty()) return "No devices yet";
        if (t.philips.isEmpty()) return name.apply(t.stick) + " · power and volume through it";
        String ph = name.apply(t.philips) + (paired ? " (paired)" : " (not paired)");
        return t.stick.isEmpty() ? ph + ": power and volume only" : name.apply(t.stick) + " · " + ph;
    }

    static String[] names(List<Tv> tvs) {
        String[] n = new String[tvs.size()];
        for (int i = 0; i < n.length; i++) n[i] = tvs.get(i).name;
        return n;
    }
}
