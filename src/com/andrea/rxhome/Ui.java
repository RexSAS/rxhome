package com.andrea.rxhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The "Stealth" look: matte black, one red accent, big flat tiles, Chakra Petch. */
final class Ui {

    static final int BG = 0xff0c0c0e, TILE = 0xff18181c, BUTTON = 0xff232328, RED = 0xffff1e3c,
        TEXT = 0xffececf0, DIM = 0xff9a9aa6;

    private static Typeface regular, semibold;

    private Ui() {}

    static void window(Activity a) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        a.getWindow().getDecorView().setBackgroundColor(BG);
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static Typeface font(Context c, boolean bold) {
        if (regular == null) {
            regular = c.getResources().getFont(R.font.chakra_petch_regular);
            semibold = c.getResources().getFont(R.font.chakra_petch_semibold);
        }
        return bold ? semibold : regular;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font(c, bold));
        return t;
    }

    /** A tile: flat dark panel with the red rail on its left edge. */
    static LinearLayout tile(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rail = new GradientDrawable();
        rail.setColor(RED);
        GradientDrawable body = new GradientDrawable();
        body.setColor(TILE);
        LayerDrawable bg = new LayerDrawable(new android.graphics.drawable.Drawable[] {rail, body});
        bg.setLayerInset(1, dp(c, 3), 0, 0, 0);
        l.setBackground(bg);
        int p = dp(c, 14);
        l.setPadding(p + dp(c, 3), p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        animateLayout(l);
        return l;
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, label, 14, primary ? Color.WHITE : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setLetterSpacing(0.06f);
        // Pressed: red for normal buttons, a darker red for the red one.
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_pressed}, flat(primary ? 0xffb3142b : RED));
        bg.addState(new int[] {}, flat(primary ? RED : BUTTON));
        b.setBackground(bg);
        int p = dp(c, 12);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        b.setFocusable(true);
        press(b);
        return b;
    }

    /** The round red power button. */
    static TextView power(Context c) {
        TextView b = new TextView(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(RED);
        // The fonts have no ⏻ glyph, so the icon is a vector layered on the red disc.
        LayerDrawable bg = new LayerDrawable(new android.graphics.drawable.Drawable[] {d, c.getDrawable(R.drawable.ic_power)});
        int inset = dp(c, 12);
        bg.setLayerInset(1, inset, inset, inset, inset);
        b.setBackground(bg);
        b.setElevation(dp(c, 6));
        int s = dp(c, 46);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        b.setClickable(true);
        b.setContentDescription("Power");
        press(b);
        return b;
    }

    static GradientDrawable flat(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        return d;
    }

    static void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** Grey = unreachable. */
    /** Grey = unreachable; fades between the two once on screen. */
    static void reachable(View v, boolean yes) {
        float to = yes ? 1f : 0.35f;
        if (v.isAttachedToWindow()) v.animate().alpha(to).setDuration(200);
        else v.setAlpha(to);
    }

    /** A screen's cards slide up and fade in one after another, starting after `delayMs`. */
    static void cascade(android.view.ViewGroup g, long delayMs) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        float rise = dp(g.getContext(), 24);
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(rise);
            v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs + i * 55L).setDuration(340)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
        }
    }

    /** A red ring ripples out from a (power) button. */
    static void burst(View v) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || !(v.getParent() instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup parent = (android.view.ViewGroup) v.getParent();
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(dp(v.getContext(), 3), RED);
        ring.setBounds(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
        parent.getOverlay().add(ring);
        float cx = (v.getLeft() + v.getRight()) / 2f, cy = (v.getTop() + v.getBottom()) / 2f, w = v.getWidth() / 2f;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(450);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator());
        a.addUpdateListener(x -> {
            float f = (float) x.getAnimatedValue(), r = w * (1f + 0.9f * f);
            ring.setBounds((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r));
            ring.setAlpha((int) (255 * (1f - f)));
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) {
                parent.getOverlay().remove(ring);
            }
        });
        a.start();
    }

    /** While something runs: a red shimmer sweeps across the view, over and over. Returns the animator to cancel. */
    static android.animation.ValueAnimator shimmer(View v) {
        GradientDrawable sweep = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {0x00FF1E3C, 0x66FF1E3C, 0x00FF1E3C});
        v.getOverlay().add(sweep);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(-0.4f, 1f).setDuration(900);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.addUpdateListener(x -> {
            int w = v.getWidth(), x0 = (int) (w * (float) x.getAnimatedValue());
            sweep.setBounds(x0, 0, x0 + (int) (w * 0.4f), v.getHeight());
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) {
                v.getOverlay().remove(sweep);
            }
            @Override public void onAnimationCancel(android.animation.Animator x) {
                v.getOverlay().remove(sweep);
            }
        });
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) a.start();
        return a;
    }

    /** A quick "done" pop. */
    static void pop(View v) {
        v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).withEndAction(() ->
            v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(3f)));
    }

    /** Pressed: shrinks a touch and springs back on release. */
    static void press(View v) {
        android.animation.StateListAnimator s = new android.animation.StateListAnimator();
        s.addState(new int[] {android.R.attr.state_pressed}, scale(v, 0.96f, 90, new android.view.animation.DecelerateInterpolator()));
        s.addState(new int[] {}, scale(v, 1f, 220, new android.view.animation.OvershootInterpolator(2.5f)));
        v.setStateListAnimator(s);
    }

    private static android.animation.Animator scale(View v, float to, long ms, android.animation.TimeInterpolator i) {
        android.animation.AnimatorSet set = new android.animation.AnimatorSet();
        set.playTogether(android.animation.ObjectAnimator.ofFloat(v, "scaleX", to), android.animation.ObjectAnimator.ofFloat(v, "scaleY", to));
        set.setDuration(ms);
        set.setInterpolator(i);
        return set;
    }

    /** Children appearing, disappearing or resizing animate instead of jumping. */
    static void animateLayout(android.view.ViewGroup g) {
        android.animation.LayoutTransition t = new android.animation.LayoutTransition();
        t.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        t.setDuration(220);
        g.setLayoutTransition(t);
    }

    /** New text fades in (nothing happens if it's the same). */
    static void setText(TextView t, CharSequence s) {
        if (android.text.TextUtils.equals(t.getText(), s)) return;
        t.setText(s);
        if (t.isAttachedToWindow()) {
            t.setAlpha(0.2f);
            t.animate().alpha(1f).setDuration(200);
        }
    }
}
