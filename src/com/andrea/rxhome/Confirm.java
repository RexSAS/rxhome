package com.andrea.rxhome;

/** Two-tap confirm for the server's power button: the second tap counts only 0.6–3 s after the first. */
final class Confirm {

    static final long MIN_MS = 600, MAX_MS = 3000;
    private long armedAt = -1;

    /** True when this tap confirms the press. A tap within 0.6 s is a jitter and is ignored. */
    boolean tap(long now) {
        long since = now - armedAt;
        if (armedAt < 0 || since > MAX_MS) {
            armedAt = now;
            return false;
        }
        if (since < MIN_MS) return false;
        armedAt = -1;
        return true;
    }

    boolean armed(long now) {
        return armedAt >= 0 && now - armedAt <= MAX_MS;
    }
}
