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
        return l;
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, label, 14, primary ? Color.WHITE : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setLetterSpacing(0.06f);
        b.setBackground(flat(primary ? RED : BUTTON));
        int p = dp(c, 12);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        b.setFocusable(true);
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
    static void reachable(View v, boolean yes) {
        v.setAlpha(yes ? 1f : 0.35f);
    }
}
