package com.andrea.rxhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** The no-look touchpad: swipe to move, tap for OK, hold for long-press OK (see Gestures). */
final class TouchpadView extends View {

    private static final long TICK_MS = 30, FLASH_MS = 260;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private final RectF box = new RectF();
    private final Gestures gestures;
    private boolean touching;
    private float fx, fy;
    private int flashCode = -1;
    private long flashAt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!touching) return;
            gestures.tick(SystemClock.uptimeMillis());
            main.postDelayed(this, TICK_MS);
        }
    };

    TouchpadView(Context c, Gestures.Out keys) {
        super(c);
        gestures = new Gestures(Ui.dp(c, 80), new Gestures.Out() {
            @Override public void press(int code) { feel(code, HapticFeedbackConstants.VIRTUAL_KEY); keys.press(code); }
            @Override public void down(int code) { feel(code, HapticFeedbackConstants.LONG_PRESS); keys.down(code); }
            @Override public void repeat(int code, int n) { keys.repeat(code, n); }
            @Override public void up(int code) { keys.up(code); }
        });
        line.setStyle(Paint.Style.STROKE);
        text.setTypeface(Ui.font(c, false));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Ui.DIM);
        text.setTextSize(Ui.dp(c, 13));
        setContentDescription("Touchpad: swipe to move, tap for OK, hold for a long press");
    }

    private void feel(int code, int haptic) {
        performHapticFeedback(haptic);
        flashCode = code;
        flashAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = Ui.dp(getContext(), 18);
        box.set(1, 1, w - 1, h - 1);
        fill.setColor(Ui.TILE);
        c.drawRoundRect(box, r, r, fill);
        line.setColor(Ui.RED);
        line.setStrokeWidth(Ui.dp(getContext(), 1.5f));
        c.drawRoundRect(box, r, r, line);
        // Faint crosshair, a hint of where "centre" is.
        line.setColor(Ui.BUTTON);
        line.setStrokeWidth(Ui.dp(getContext(), 1));
        c.drawLine(w / 2, h * 0.2f, w / 2, h * 0.8f, line);
        c.drawLine(w * 0.2f, h / 2, w * 0.8f, h / 2, line);
        if (!touching) {
            c.drawText("Swipe to move · Tap for OK · Hold for long press", w / 2, h - Ui.dp(getContext(), 18), text);
        }

        if (touching) {
            fill.setColor(0x33ff1e3c);
            c.drawCircle(fx, fy, Ui.dp(getContext(), 44), fill);
            fill.setColor(0x88ff1e3c);
            c.drawCircle(fx, fy, Ui.dp(getContext(), 14), fill);
        }

        long age = SystemClock.uptimeMillis() - flashAt;
        if (flashCode >= 0 && age < FLASH_MS) {
            int alpha = (int) (255 * (1 - age / (float) FLASH_MS));
            drawFlash(c, w / 2, h / 2, Math.min(w, h) * 0.16f, alpha);
            postInvalidateOnAnimation();
        }
    }

    /** A red chevron pointing the way the TV moved, or a red ring for OK. */
    private void drawFlash(Canvas c, float cx, float cy, float s, int alpha) {
        line.setColor(Ui.RED);
        line.setAlpha(alpha);
        line.setStrokeWidth(Ui.dp(getContext(), 5));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        if (flashCode == Gestures.OK) {
            c.drawCircle(cx, cy, s * 0.7f, line);
        } else {
            float ux = flashCode == Gestures.RIGHT ? 1 : flashCode == Gestures.LEFT ? -1 : 0;
            float uy = flashCode == Gestures.DOWN ? 1 : flashCode == Gestures.UP ? -1 : 0;
            chevron.reset();
            chevron.moveTo(cx - ux * s * 0.4f - uy * s, cy - uy * s * 0.4f - ux * s);
            chevron.lineTo(cx + ux * s * 0.6f, cy + uy * s * 0.6f);
            chevron.lineTo(cx - ux * s * 0.4f + uy * s, cy - uy * s * 0.4f + ux * s);
            c.drawPath(chevron, line);
        }
        line.setAlpha(255);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        long t = SystemClock.uptimeMillis();
        fx = e.getX();
        fy = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                gestures.down(fx, fy, t);
                main.postDelayed(tick, TICK_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                gestures.move(fx, fy, t);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                main.removeCallbacks(tick);
                gestures.up(t);
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        main.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
