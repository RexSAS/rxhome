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

    /** Draws an arc around (cx, cy) from angle a0 to a1 (degrees; 0 = right, 90 = down), lifts, returns the log. */
    static List<String> arc(float cx, float cy, float r, float a0, float a1) {
        Gestures t = g();
        int n = 40;
        long time = 0;
        for (int i = 0; i <= n; i++) {
            double a = Math.toRadians(a0 + (a1 - a0) * i / n);
            float x = cx + (float) (Math.cos(a) * r), y = cy + (float) (Math.sin(a) * r);
            if (i == 0) t.down(x, y, time);
            else t.move(x, y, time);
            time += 10;
        }
        t.up(time);
        return new ArrayList<>(log);
    }

    public static void main(String[] a) {
        // Tap anywhere: OK.
        Gestures t = g();
        t.down(500, 500, 0);
        t.move(510, 495, 80);
        t.up(150);
        expect("press 23");

        // Swipe right: one RIGHT, sent on lift.
        t = g();
        t.down(100, 500, 0);
        t.move(230, 520, 60);
        expect();
        t.up(120);
        expect("press 22");

        // A longer drag: one key per STEP of travel; the dominant axis wins; wobble doesn't matter.
        t = g();
        t.down(500, 900, 0);
        t.move(510, 780, 40);
        t.move(495, 690, 80);
        t.move(520, 560, 120);
        t.up(160);
        expect("press 19", "press 19", "press 19");

        // Swipe down then keep the finger still: keeps moving (a press every 110 ms) until lifted,
        // but only after a deliberate 650 ms pause, so a finger resting after a swipe doesn't run on.
        t = g();
        t.down(500, 100, 0);
        t.move(500, 220, 50);
        t.tick(500);  // 450 ms after the swipe: nothing yet
        expect();
        t.tick(710);  // 660 ms: the swipe's step, then holding starts
        t.tick(830);
        t.tick(950);
        t.up(970);
        expect("press 20", "press 20", "press 20", "press 20");

        // Press and hold without moving: a long-press OK (down, repeats, up), like the real remote.
        t = g();
        t.down(500, 500, 0);
        t.tick(300);
        t.tick(520);
        t.tick(620);
        t.up(700);
        expect("down 23", "repeat 23 1", "repeat 23 2", "up 23");

        // Shapes. Circle either way: HOME.
        assert arc(500, 500, 150, -90, 270).equals(java.util.Arrays.asList("press 3")) : log;
        assert arc(500, 500, 150, -90, -450).equals(java.util.Arrays.asList("press 3")) : log;
        // ⊃ (open on the left): right, down, left — or right, up, left: BACK.
        assert arc(500, 500, 150, -90, 90).equals(java.util.Arrays.asList("press 4")) : log;
        assert arc(500, 500, 150, 90, -90).equals(java.util.Arrays.asList("press 4")) : log;
        // C (open on the right): left, down, right — or left, up, right: play/pause.
        assert arc(500, 500, 150, -90, -270).equals(java.util.Arrays.asList("press 85")) : log;
        assert arc(500, 500, 150, 90, 270).equals(java.util.Arrays.asList("press 85")) : log;
        // A gentle curve is still a swipe, not a shape.
        assert arc(500, 2000, 1500, -100, -80).equals(java.util.Arrays.asList("press 22", "press 22", "press 22", "press 22", "press 22")) : log;
    }
}
