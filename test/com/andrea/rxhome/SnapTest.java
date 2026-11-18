package com.andrea.rxhome;

public class SnapTest {
    public static void main(String[] a) {
        // Let go: the nearest card (pages 1000 px wide, 3 TVs).
        assert Snap.page(0, 1000, 0, 0, 3) == 0;
        assert Snap.page(450, 1000, 0, 0, 3) == 0;
        assert Snap.page(550, 1000, 0, 0, 3) == 1;
        // A flick goes one card on (or back) from where it started, even if only dragged a little.
        assert Snap.page(100, 1000, 0, 2000, 3) == 1;
        assert Snap.page(1900, 1000, 2, -2000, 3) == 1;
        assert Snap.page(2000, 1000, 2, 2000, 3) == 2;   // no card after the last
        assert Snap.page(0, 1000, 0, -2000, 3) == 0;     // or before the first
        assert Snap.page(5000, 1000, 0, 0, 3) == 2;      // never past the end
        assert Snap.page(300, 0, 1, 0, 3) == 1;          // not laid out yet: stays
    }
}
