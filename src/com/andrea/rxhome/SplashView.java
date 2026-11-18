package com.andrea.rxhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * Cold-start splash over the home screen: a red power ring draws itself, the stem drops in with a glow,
 * "rx" + red "home" rises, then it all fades out. The home screen loads underneath meanwhile.
 */
final class SplashView extends View {

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private float t;

    SplashView(Context c) {
        super(c);
        setBackgroundColor(Ui.BG);
        setClickable(true); // nothing underneath reacts while it plays
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setColor(Ui.RED);
        text.setTypeface(Ui.font(c, true));
        text.setTextAlign(Paint.Align.LEFT);
        setContentDescription("rxhome");
    }

    /** Adds itself over `parent`, plays, fades out and removes itself. */
    void play(ViewGroup parent) {
        parent.addView(this, new ViewGroup.LayoutParams(-1, -1));
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(Splash.DRAW_MS);
        a.setInterpolator(new LinearInterpolator()); // Splash eases each part itself
        a.addUpdateListener(v -> {
            t = (float) v.getAnimatedValue();
            invalidate();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) {
                animate().alpha(0f).setDuration(Splash.FADE_MS).withEndAction(() -> parent.removeView(SplashView.this));
            }
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() * 0.42f, r = Ui.dp(getContext(), 46);
        float ringP = Splash.ring(t), nameP = Splash.name(t);

        // Glow comes up with the ring.
        glow.setShader(new RadialGradient(cx, cy, r * 3, (int) (0x55 * ringP) << 24 | 0xFF1E3C, 0x00FF1E3C, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 3, glow);

        // The power symbol: a ring open at the top (sweeps from the gap round), then the stem.
        ring.setStrokeWidth(Ui.dp(getContext(), 7));
        arc.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(arc, -60, 300 * ringP, false, ring);
        float stem = Math.max(0f, (ringP - 0.5f) * 2f);
        if (stem > 0) c.drawLine(cx, cy - r * 1.1f, cx, cy - r * 1.1f + r * 0.95f * stem, ring);

        // "rx" + red "home", rising into place.
        if (nameP > 0) {
            text.setTextSize(Ui.dp(getContext(), 40));
            float rx = text.measureText("rx"), w = rx + text.measureText("home");
            float x = cx - w / 2, y = cy + r * 2.4f + Ui.dp(getContext(), 24) * (1 - nameP);
            int a = (int) (255 * nameP);
            text.setColor(Ui.TEXT);
            text.setAlpha(a);
            c.drawText("rx", x, y, text);
            text.setColor(Ui.RED);
            text.setAlpha(a);
            c.drawText("home", x + rx, y, text);
        }
    }
}
