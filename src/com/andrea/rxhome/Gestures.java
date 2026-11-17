package com.andrea.rxhome;

/**
 * Touchpad gestures → TV keys, so the remote works without looking:
 * swipe = one step per `step` px of travel (dominant axis), finger still after a swipe = keep moving,
 * tap = OK, press and hold = long-press OK (down, repeats, up). Times in ms; call tick() while the
 * finger is down (every ~30 ms).
 */
final class Gestures {

    interface Out {
        void press(int code);
        void down(int code);
        void repeat(int code, int n);
        void up(int code);
    }

    static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, OK = 23;
    static final long LONG_PRESS_MS = 500, KEEP_MOVING_AFTER_MS = 650, MOVE_EVERY_MS = 110, OK_REPEAT_MS = 90;

    private final float step;
    private final Out out;
    private float ax, ay;
    private long downAt, lastMoveAt, next;
    private boolean moved, okHeld, keepMoving;
    private int lastDir, okRepeats;

    Gestures(float step, Out out) {
        this.step = step;
        this.out = out;
    }

    void down(float x, float y, long t) {
        ax = x;
        ay = y;
        downAt = t;
        moved = okHeld = keepMoving = false;
    }

    void move(float x, float y, long t) {
        if (okHeld) return;
        float dx = x - ax, dy = y - ay;
        while (Math.max(Math.abs(dx), Math.abs(dy)) >= step) {
            int dir;
            if (Math.abs(dx) >= Math.abs(dy)) {
                dir = dx > 0 ? RIGHT : LEFT;
                ax += Math.signum(dx) * step;
            } else {
                dir = dy > 0 ? DOWN : UP;
                ay += Math.signum(dy) * step;
            }
            out.press(dir);
            moved = true;
            keepMoving = false;
            lastDir = dir;
            lastMoveAt = t;
            dx = x - ax;
            dy = y - ay;
        }
    }

    void tick(long t) {
        if (okHeld) {
            if (t >= next) {
                out.repeat(OK, ++okRepeats);
                next += OK_REPEAT_MS;
            }
        } else if (!moved) {
            if (t - downAt >= LONG_PRESS_MS) {
                okHeld = true;
                out.down(OK);
                okRepeats = 1;
                out.repeat(OK, 1); // the first repeat is what marks a long press on Android
                next = t + OK_REPEAT_MS;
            }
        } else if (!keepMoving) {
            if (t - lastMoveAt >= KEEP_MOVING_AFTER_MS) {
                keepMoving = true;
                out.press(lastDir);
                next = t + MOVE_EVERY_MS;
            }
        } else if (t >= next) {
            out.press(lastDir);
            next += MOVE_EVERY_MS;
        }
    }

    void up(long t) {
        if (okHeld) out.up(OK);
        else if (!moved) out.press(OK);
        moved = okHeld = keepMoving = false;
    }
}
