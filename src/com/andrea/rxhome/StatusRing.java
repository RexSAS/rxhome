package com.andrea.rxhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** A small empty circle next to a status: green = on, red = off, grey spinning = starting, grey = unknown. */
final class StatusRing extends View {

    static final int UNKNOWN = 0, ON = 1, OFF = 2, BUSY = 3;
    private static final int GREY = 0xFF6E6E7A;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private int mode = UNKNOWN;
    private float spin;
    private ValueAnimator anim;

    StatusRing(Context c) {
        super(c);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(Ui.dp(c, 2));
    }

    void set(int m) {
        if (m == mode) return;
        mode = m;
        setContentDescription(m == ON ? "On" : m == OFF ? "Off" : m == BUSY ? "Starting" : "Unknown");
        if (m == BUSY) start();
        else stop();
        // A small pop when the state changes.
        if (isAttachedToWindow()) {
            setScaleX(0.6f);
            setScaleY(0.6f);
            animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(3f));
        }
        invalidate();
    }

    private void start() {
        if (anim != null || !ValueAnimator.areAnimatorsEnabled()) return;
        anim = ValueAnimator.ofFloat(0f, 360f).setDuration(900);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> {
            spin = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private void stop() {
        if (anim != null) anim.cancel();
        anim = null;
    }

    @Override
    protected void onMeasure(int w, int h) {
        int s = Ui.dp(getContext(), 14);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float inset = p.getStrokeWidth();
        box.set(inset, inset, getWidth() - inset, getHeight() - inset);
        p.setColor(mode == ON ? Ui.GREEN : mode == OFF ? Ui.RED : GREY);
        if (mode == BUSY) c.drawArc(box, spin, 270, false, p);
        else c.drawOval(box, p);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mode == BUSY) start();
    }
}
