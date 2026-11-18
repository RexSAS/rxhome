package com.andrea.rxhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Following a server power-on: remembered boot times (power press → Jellyfin answering, last 5, stored
 * as "ms,ms,..."), the typical one as the estimate, and what the card says meanwhile.
 */
final class Boot {

    static final int KEEP = 5;
    static final long MAX_MS = 15 * 60_000, GIVE_UP_MS = 10 * 60_000;

    private Boot() {}

    /** Adds a boot time; ignores impossible ones (≤ 0, or a press that never finished). */
    static String record(String history, long ms) {
        if (ms <= 0 || ms > MAX_MS) return history;
        List<Long> all = parse(history);
        all.add(ms);
        while (all.size() > KEEP) all.remove(0);
        StringBuilder s = new StringBuilder();
        for (long v : all) {
            if (s.length() > 0) s.append(',');
            s.append(v);
        }
        return s.toString();
    }

    /** The typical boot time (median), or 0 with no history. */
    static long estimate(String history) {
        List<Long> all = parse(history);
        if (all.isEmpty()) return 0;
        Collections.sort(all);
        int n = all.size();
        return n % 2 == 1 ? all.get(n / 2) : (all.get(n / 2 - 1) + all.get(n / 2)) / 2;
    }

    static String label(long elapsedMs, long estimateMs) {
        if (estimateMs <= 0) return "Powering on…";
        long left = estimateMs - elapsedMs;
        if (left <= 0) return "Powering on… taking longer than usual";
        long secs = Math.max(1, (left + 999) / 1000);
        return "Powering on… ~" + (secs < 60 ? secs + " s" : secs / 60 + " min " + secs % 60 + " s") + " left";
    }

    /** Stop following after 10 minutes: it isn't coming up on its own. */
    static boolean expired(long elapsedMs) {
        return elapsedMs > GIVE_UP_MS;
    }

    private static List<Long> parse(String history) {
        List<Long> out = new ArrayList<>();
        for (String p : history.split(",")) {
            try {
                long v = Long.parseLong(p.trim());
                if (v > 0 && v <= MAX_MS) out.add(v);
            } catch (NumberFormatException e) {
                // Junk in the setting: skipped.
            }
        }
        return out;
    }
}
