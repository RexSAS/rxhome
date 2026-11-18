package com.andrea.rxhome;

public class SplashTest {
    public static void main(String[] a) {
        // The ring draws first, the name rises in after, and everything is settled before the fade.
        assert Splash.ring(0f) == 0f && Splash.ring(0.55f) == 1f && Splash.ring(1f) == 1f;
        assert Splash.ring(0.275f) > 0.4f && Splash.ring(0.275f) < 0.9f; // eased, not linear
        assert Splash.name(0.3f) == 0f && Splash.name(0.8f) == 1f;
        assert Splash.name(0.55f) > 0f && Splash.name(0.55f) < 1f;
        // Values outside the timeline are clamped.
        assert Splash.ring(-1f) == 0f && Splash.name(2f) == 1f;
        assert Splash.DRAW_MS + Splash.FADE_MS <= 1300; // never longer than ~1.3 s
    }
}
