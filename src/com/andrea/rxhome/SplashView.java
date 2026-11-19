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
import android.view.animation.AccelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * Cold-start splash over the home screen (timeline in Splash): red sparks spiral in, the power ring sweeps
 * round with a comet head, the stem springs down with a shockwave, "rx" + red "home" spring up letter by
 * letter, the glow breathes, and it all zooms gently forward as it fades. The home screen loads underneath.
 */
final class SplashView extends View {

    private static final int SPARKS = 12;
    private static final String NAME = "rxhome";

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private float t;

    SplashView(Context c) {
        super(c);
        setBackgroundColor(Ui.BG);
        setClickable(true); // nothing underneath reacts while it plays
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(Ui.RED);
        fill.setColor(Ui.RED);
        text.setTypeface(Ui.font(c, true));
        text.setTextAlign(Paint.Align.LEFT);
        setContentDescription("rxhome");
    }

    /** Adds itself over `parent`, plays, zooms forward as it fades, and removes itself. */
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
                animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).setDuration(Splash.FADE_MS)
                    .setInterpolator(new AccelerateInterpolator(1.5f))
                    .withEndAction(() -> parent.removeView(SplashView.this));
            }
        });
        a.start();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() * 0.42f, r = dp(46);
        float sp = Splash.sparks(t), rp = Splash.ring(t), st = Splash.stem(t), wv = Splash.wave(t);

        // The glow: blooms with the sparks and the ring, then breathes once.
        float breathe = t > 0.8f ? (float) Math.sin((t - 0.8f) / 0.2f * Math.PI) * 0.35f : 0f;
        float g = Math.min(1f, sp * 0.5f + rp * 0.5f) + breathe;
        glow.setShader(new RadialGradient(cx, cy, r * 3.2f, (int) (0x60 * Math.min(1.3f, g)) << 24 | 0xFF1E3C,
            0x00FF1E3C, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 3.2f, glow);

        // Sparks spiral in from far out and merge into where the ring will be.
        if (sp < 1f) {
            float fade = sp > 0.75f ? 1f - (sp - 0.75f) / 0.25f : Math.min(1f, sp * 6f);
            stroke.setStrokeWidth(dp(2));
            for (int i = 0; i < SPARKS; i++) {
                double ang = Math.toRadians(i * 360.0 / SPARKS + (1 - sp) * 260);
                double back = ang - Math.toRadians(14 * (1 - sp)); // a short trail behind each
                float rad = r * 3.4f + (r - r * 3.4f) * sp, radBack = rad + r * 0.35f * (1 - sp);
                float x = cx + (float) Math.cos(ang) * rad, y = cy + (float) Math.sin(ang) * rad;
                stroke.setAlpha((int) (140 * fade));
                c.drawLine(cx + (float) Math.cos(back) * radBack, cy + (float) Math.sin(back) * radBack, x, y, stroke);
                fill.setAlpha((int) (255 * fade));
                c.drawCircle(x, y, dp(2.6f), fill);
            }
        }

        // The power ring: open at the top, swept round from the gap, a bright comet head while it draws.
        stroke.setAlpha(255);
        stroke.setStrokeWidth(dp(7));
        arc.set(cx - r, cy - r, cx + r, cy + r);
        if (rp > 0f) c.drawArc(arc, -60, 300 * rp, false, stroke);
        if (rp > 0f && rp < 1f) {
            double head = Math.toRadians(-60 + 300 * rp);
            float hx = cx + (float) Math.cos(head) * r, hy = cy + (float) Math.sin(head) * r;
            glow.setShader(new RadialGradient(hx, hy, dp(16), 0xCCFF6A7E, 0x00FF1E3C, Shader.TileMode.CLAMP));
            c.drawCircle(hx, hy, dp(16), glow);
            fill.setColor(0xFFFFD6DC);
            fill.setAlpha(255);
            c.drawCircle(hx, hy, dp(4), fill);
            fill.setColor(Ui.RED);
        }

        // The stem springs down from the gap.
        if (st > 0f) c.drawLine(cx, cy - r * 1.1f, cx, cy - r * 1.1f + r * 0.95f * st, stroke);

        // One shockwave as the stem lands.
        if (wv > 0f && wv < 1f) {
            stroke.setStrokeWidth(dp(3) * (1 - wv) + dp(0.5f));
            stroke.setAlpha((int) (170 * (1 - wv)));
            c.drawCircle(cx, cy, r * (1.15f + 2.2f * wv), stroke);
            stroke.setAlpha(255);
        }

        // "rx" + red "home", letter by letter, each springing up into place.
        text.setTextSize(dp(40));
        float w = text.measureText(NAME), x = cx - w / 2, base = cy + r * 2.4f;
        for (int i = 0; i < NAME.length(); i++) {
            String ch = NAME.substring(i, i + 1);
            float p = Splash.letter(t, i, NAME.length());
            if (p > 0f) {
                text.setColor(i < 2 ? Ui.TEXT : Ui.RED);
                text.setAlpha((int) (255 * Math.min(1f, p)));
                c.drawText(ch, x, base + dp(28) * (1 - p), text);
            }
            x += text.measureText(ch);
        }
    }
}
