package com.andrea.rxhome;

import java.util.ArrayList;
import java.util.List;

public class GesturesTest {
    static final int STEP = 100; // px of travel per key

    static List<String> log = new ArrayList<>();

    static Gestures g() {
        log.clear();
        return new Gestures(STEP, new Gestures.Out() {
            @Override public void press(int code) { log.add("press " + code); }
            @Override public void down(int code) { log.add("down " + code); }
            @Override public void repeat(int code, int n) { log.add("repeat " + code + " " + n); }
            @Override public void up(int code) { log.add("up " + code); }
        });
    }

    static void expect(String... want) {
        assert log.equals(java.util.Arrays.asList(want)) : log;
    }

    public static void main(String[] a) {
        // Tap anywhere: OK.
        Gestures t = g();
        t.down(500, 500, 0);
        t.move(510, 495, 80);
        t.up(150);
        expect("press 23");

        // Swipe right: one RIGHT, no OK on lift.
        t = g();
        t.down(100, 500, 0);
        t.move(230, 520, 60);
        t.up(120);
        expect("press 22");

        // Keep dragging: one key per STEP of travel; the dominant axis wins.
        t = g();
        t.down(500, 900, 0);
        t.move(510, 780, 40);
        t.move(505, 690, 80);
        t.move(520, 560, 120);
        t.up(160);
        expect("press 19", "press 19", "press 19");

        // Swipe down then keep the finger still: keeps moving (a press every 110 ms) until lifted.
        t = g();
        t.down(500, 100, 0);
        t.move(500, 220, 50);
        t.tick(300);
        t.tick(460);  // 410 ms after the swipe: holding starts
        t.tick(580);
        t.tick(700);
        t.up(720);
        expect("press 20", "press 20", "press 20", "press 20");

        // Press and hold without moving: a long-press OK (down, repeats, up), like the real remote.
        t = g();
        t.down(500, 500, 0);
        t.tick(300);
        t.tick(520);
        t.tick(620);
        t.up(700);
        expect("down 23", "repeat 23 1", "repeat 23 2", "up 23");

        // A swipe after the long-press began doesn't add keys.
        t = g();
        t.down(500, 500, 0);
        t.tick(520);
        t.move(800, 500, 560);
        t.up(600);
        expect("down 23", "repeat 23 1", "up 23");
    }
}
