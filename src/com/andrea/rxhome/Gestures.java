package com.andrea.rxhome;

import java.util.ArrayList;
import java.util.List;

/**
 * Touchpad gestures → TV keys, so the remote works without looking. Decided when the finger lifts:
 *   tap = OK · swipe = one step per `step` px of travel (dominant axis)
 *   circle = HOME · ⊃ (right, up/down, left) = BACK · C (left, up/down, right) = play/pause
 * While the finger is down: hold still without moving = long-press OK (down, repeats, up);
 * swipe then hold still = keep moving. Times in ms; call tick() every ~30 ms while touching.
 */
final class Gestures {

    interface Out {
        void press(int code);
        void down(int code);
        void repeat(int code, int n);
        void up(int code);
    }

    static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, OK = 23, HOME = 3, BACK = 4, PLAY_PAUSE = 85;
    static final long LONG_PRESS_MS = 500, KEEP_MOVING_AFTER_MS = 650, MOVE_EVERY_MS = 110, OK_REPEAT_MS = 90;
    // Total turning of the stroke: a circle turns ~360°, a C ~180°, a swipe ~0°.
    static final double CIRCLE_DEG = 280, C_DEG = 120, STRAIGHT_DEG = 90;

    private final float step;
    private final Out out;
    private final List<float[]> pts = new ArrayList<>();
    private long downAt, lastMoveAt, next;
    private boolean okHeld, keepMoving;
    private int lastDir, okRepeats;

    Gestures(float step, Out out) {
        this.step = step;
        this.out = out;
    }

    void down(float x, float y, long t) {
        pts.clear();
        pts.add(new float[] {x, y});
        downAt = lastMoveAt = t;
        okHeld = keepMoving = false;
    }

    void move(float x, float y, long t) {
        if (okHeld || keepMoving || pts.isEmpty()) return;
        float[] last = pts.get(pts.size() - 1);
        if (Math.hypot(x - last[0], y - last[1]) < step * 0.08) return; // jitter
        pts.add(new float[] {x, y});
        lastMoveAt = t;
    }

    void tick(long t) {
        if (pts.isEmpty()) return;
        if (okHeld) {
            if (t >= next) {
                out.repeat(OK, ++okRepeats);
                next += OK_REPEAT_MS;
            }
        } else if (keepMoving) {
            if (t >= next) {
                out.press(lastDir);
                next += MOVE_EVERY_MS;
            }
        } else if (extent() < step * 0.35) {
            if (t - downAt >= LONG_PRESS_MS) {
                okHeld = true;
                out.down(OK);
                okRepeats = 1;
                out.repeat(OK, 1); // the first repeat is what marks a long press on Android
                next = t + OK_REPEAT_MS;
            }
        } else if (extent() >= step && t - lastMoveAt >= KEEP_MOVING_AFTER_MS && Math.abs(turning()) < STRAIGHT_DEG) {
            swipe();
            keepMoving = true;
            out.press(lastDir);
            next = t + MOVE_EVERY_MS;
        }
    }

    void up(long t) {
        if (pts.isEmpty()) return;
        if (okHeld) {
            out.up(OK);
        } else if (!keepMoving) {
            if (extent() < step * 0.35) {
                out.press(OK);
            } else {
                int shape = shape();
                if (shape >= 0) out.press(shape);
                else swipe();
            }
        }
        pts.clear();
        okHeld = keepMoving = false;
    }

    /** Presses the stroke's direction once per `step` of travel (at least once). */
    private void swipe() {
        float[] a = pts.get(0), b = pts.get(pts.size() - 1);
        float dx = b[0] - a[0], dy = b[1] - a[1];
        boolean horizontal = Math.abs(dx) >= Math.abs(dy);
        lastDir = horizontal ? (dx > 0 ? RIGHT : LEFT) : (dy > 0 ? DOWN : UP);
        int n = Math.max(1, (int) (Math.abs(horizontal ? dx : dy) / step));
        for (int i = 0; i < n; i++) out.press(lastDir);
    }

    /** HOME, BACK or PLAY_PAUSE for a circle, ⊃ or C; -1 for anything else. */
    private int shape() {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] p : pts) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        if (Math.max(maxX - minX, maxY - minY) < step) return -1; // too small to be deliberate
        double turn = Math.abs(turning());
        if (turn >= CIRCLE_DEG) return HOME;
        if (turn < C_DEG) return -1;
        float mid = minX + (maxX - minX) / 2;
        float sx = pts.get(0)[0], ex = pts.get(pts.size() - 1)[0];
        if (sx < mid && ex < mid) return BACK;       // ends on the left, bulge on the right: ⊃
        if (sx > mid && ex > mid) return PLAY_PAUSE; // ends on the right, bulge on the left: C
        return -1;
    }

    /** Signed total turning of the stroke in degrees, from points spaced at least step/5 apart. */
    private double turning() {
        List<float[]> s = new ArrayList<>();
        for (float[] p : pts) {
            if (s.isEmpty() || Math.hypot(p[0] - s.get(s.size() - 1)[0], p[1] - s.get(s.size() - 1)[1]) >= step * 0.2) s.add(p);
        }
        double total = 0, prev = Double.NaN;
        for (int i = 1; i < s.size(); i++) {
            double a = Math.toDegrees(Math.atan2(s.get(i)[1] - s.get(i - 1)[1], s.get(i)[0] - s.get(i - 1)[0]));
            if (!Double.isNaN(prev)) {
                double d = a - prev;
                while (d > 180) d -= 360;
                while (d < -180) d += 360;
                total += d;
            }
            prev = a;
        }
        return total;
    }

    /** Farthest the finger got from where it touched down. */
    private double extent() {
        float[] a = pts.get(0);
        double m = 0;
        for (float[] p : pts) m = Math.max(m, Math.hypot(p[0] - a[0], p[1] - a[1]));
        return m;
    }
}
