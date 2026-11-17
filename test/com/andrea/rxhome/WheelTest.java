package com.andrea.rxhome;

public class WheelTest {
    public static void main(String[] a) {
        float r = 100;
        // Angle = hue, clockwise from 3 o'clock (screen y points down, like Android's SweepGradient).
        assert java.util.Arrays.equals(Wheel.hueSat(100, 0, r), new int[] {0, 100});
        assert java.util.Arrays.equals(Wheel.hueSat(0, 100, r), new int[] {90, 100});
        assert java.util.Arrays.equals(Wheel.hueSat(-100, 0, r), new int[] {180, 100});
        assert java.util.Arrays.equals(Wheel.hueSat(0, -100, r), new int[] {270, 100});
        // Distance from the centre = saturation; the centre is white, past the rim is full colour.
        assert Wheel.hueSat(0, 0, r)[1] == 0;
        assert Wheel.hueSat(50, 0, r)[1] == 50;
        assert Wheel.hueSat(300, 0, r)[1] == 100;
        // And back: where the marker goes for a hue and saturation.
        float[] p = Wheel.point(90, 50, r);
        assert Math.abs(p[0]) < 0.01 && Math.abs(p[1] - 50) < 0.01 : p[0] + "," + p[1];
    }
}
