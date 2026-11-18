package com.andrea.rxhome;

/** Keeps the server card steady: only the newest check counts, and one missed stats reading doesn't hide them. */
final class Steady {

    private int latest, misses;
    private String last;

    /** Numbers a new check. */
    int next() {
        return ++latest;
    }

    /** Whether this check is still the newest (an older one finishing late is ignored). */
    boolean current(int id) {
        return id == latest;
    }

    /** The stats to show for this reading: the last good ones after one miss, none after two in a row. */
    String stats(String s) {
        if (s != null) {
            last = s;
            misses = 0;
        } else if (++misses >= 2) {
            last = null;
        }
        return last;
    }
}
