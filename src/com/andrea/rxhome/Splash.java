package com.andrea.rxhome;

/**
 * The splash's timeline (t = 0..1 over DRAW_MS): sparks spiral into the ring's place, the ring sweeps
 * round like a comet, the stem springs down with a shockwave, the name springs up letter by letter.
 */
final class Splash {

    static final long DRAW_MS = 1450, FADE_MS = 400;

    private Splash() {}

    /** Sparks on their way in (0..1): the first 40%, slow-fast-slow. */
    static float sparks(float t) {
        return easeInOut(clamp(t / 0.4f));
    }

    /** How much of the ring is drawn (0..1): 15% to 55%, easing out. */
    static float ring(float t) {
        return easeOut(clamp((t - 0.15f) / 0.4f));
    }

    /** The stem's length (0..~1.1..1): 45% to 65%, springing past and back. */
    static float stem(float t) {
        return easeOutBack(clamp((t - 0.45f) / 0.2f));
    }

    /** The shockwave (0..1: grows out, fades): 55% to 80%. */
    static float wave(float t) {
        return clamp((t - 0.55f) / 0.25f);
    }

    /** Letter i of n coming up (0..~1.1..1): one after another from 56%, each springing into place. */
    static float letter(float t, int i, int n) {
        return easeOutBack(clamp((t - 0.56f - i * 0.035f) / 0.18f));
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeOut(float p) {
        float q = 1 - p;
        return 1 - q * q * q;
    }

    private static float easeInOut(float p) {
        return p < 0.5f ? 4 * p * p * p : 1 - (float) Math.pow(-2 * p + 2, 3) / 2;
    }

    private static float easeOutBack(float p) {
        float c1 = 1.70158f, c3 = c1 + 1, q = p - 1;
        return 1 + c3 * q * q * q + c1 * q * q;
    }
}
