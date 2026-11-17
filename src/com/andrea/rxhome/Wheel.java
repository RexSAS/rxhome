package com.andrea.rxhome;

/**
 * Colour-wheel geometry: angle = hue (clockwise from 3 o'clock, screen y down, as Android's
 * SweepGradient draws it), distance from the centre = saturation (centre white, rim full colour).
 */
final class Wheel {

    private Wheel() {}

    /** {hue 0-359, saturation 0-100} for a touch at (dx, dy) from the centre of a wheel of radius r. */
    static int[] hueSat(float dx, float dy, float r) {
        int hue = (int) Math.round(Math.toDegrees(Math.atan2(dy, dx)));
        if (hue < 0) hue += 360;
        if (hue == 360) hue = 0;
        int sat = (int) Math.round(Math.min(1, Math.hypot(dx, dy) / r) * 100);
        return new int[] {hue, sat};
    }

    /** Where (dx, dy) a hue and saturation sit on a wheel of radius r. */
    static float[] point(int hue, int sat, float r) {
        double a = Math.toRadians(hue), d = r * sat / 100.0;
        return new float[] {(float) (Math.cos(a) * d), (float) (Math.sin(a) * d)};
    }
}
