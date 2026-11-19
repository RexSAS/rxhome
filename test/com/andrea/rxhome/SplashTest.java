package com.andrea.rxhome;

public class SplashTest {
    public static void main(String[] a) {
        // Sparks spiral in first and are gone (merged into the ring) once it's drawing.
        assert Splash.sparks(0f) == 0f && Splash.sparks(0.4f) == 1f;
        assert Splash.sparks(0.2f) > 0f && Splash.sparks(0.2f) < 1f;
        // The ring sweeps after they arrive, eased; done well before the end.
        assert Splash.ring(0.1f) == 0f && Splash.ring(0.6f) == 1f;
        assert Splash.ring(0.35f) > 0.3f && Splash.ring(0.35f) < 1f;
        // The stem springs: it goes a little past its length, then settles.
        float peak = 0f;
        for (float t = 0f; t <= 1f; t += 0.01f) peak = Math.max(peak, Splash.stem(t));
        assert peak > 1.02f && Splash.stem(1f) == 1f && Splash.stem(0.4f) == 0f;
        // One shockwave: grows out and fades.
        assert Splash.wave(0.5f) == 0f && Splash.wave(0.8f) == 1f && Splash.wave(0.65f) > 0f;
        // Letters arrive one after another, each springing up.
        assert Splash.letter(0.55f, 0, 6) == 0f && Splash.letter(0.95f, 5, 6) >= 1f - 1e-4f;
        assert Splash.letter(0.75f, 0, 6) > Splash.letter(0.75f, 5, 6);   // the first one is ahead
        // Values outside the timeline are clamped; the whole thing stays short.
        assert Splash.ring(-1f) == 0f && Splash.letter(2f, 3, 6) >= 1f - 1e-4f;
        assert Splash.DRAW_MS + Splash.FADE_MS <= 2000;
    }
}
