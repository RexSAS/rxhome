package com.andrea.rxhome;

/** The splash's timeline (t = 0..1 over DRAW_MS): the power ring draws, then the name rises in. */
final class Splash {

    static final long DRAW_MS = 900, FADE_MS = 300;

    private Splash() {}

    /** How much of the ring is drawn (0..1): the first 55%, easing out. */
    static float ring(float t) {
        return easeOut(clamp(t / 0.55f));
    }

    /** How far the name has come in (0..1): from 35% to 80%, easing out. */
    static float name(float t) {
        return easeOut(clamp((t - 0.35f) / 0.45f));
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeOut(float p) {
        float q = 1 - p;
        return 1 - q * q * q;
    }
}
