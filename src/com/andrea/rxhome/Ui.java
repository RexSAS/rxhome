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
