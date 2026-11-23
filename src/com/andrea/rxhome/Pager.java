package com.andrea.rxhome;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** Cards side by side, one in view: swipe for the next one (snaps; a flick moves one card). */
final class Pager extends HorizontalScrollView {

    interface OnPage {
        void changed(int page);
    }

    private final LinearLayout row;
    private final int gap;
    private int page, startPage;
    private boolean flung;
    OnPage onPage;

    Pager(Context c) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        gap = Ui.dp(c, 6);
        row = new LinearLayout(c);
        addView(row, new LayoutParams(-2, -2));
    }

    /** Half the gap between cards: give the pager this much negative side margin so cards line up with the rest. */
    int gap() {
        return gap;
    }

    void add(View card) {
        FrameLayout p = new FrameLayout(getContext());
        p.setPadding(gap, 0, gap, 0);
        p.addView(card, new FrameLayout.LayoutParams(-1, -2));
        row.addView(p, new LinearLayout.LayoutParams(Math.max(1, getWidth()), -2));
    }

    int page() {
        return page;
    }

    /** Straight to a card (no animation), e.g. the one shown last time. */
    void show(int p) {
        page = Math.max(0, Math.min(row.getChildCount() - 1, p));
        post(() -> scrollTo(page * getWidth(), 0));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).getLayoutParams().width = w;
        row.requestLayout();
        post(() -> scrollTo(page * w, 0));
    }

    // DOWN on a card's button goes to the child, never to onTouchEvent: reset the gesture here so startPage isn't stale.
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            startPage = page;
            flung = false;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean r = super.onTouchEvent(e);
        int a = e.getActionMasked();
        if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && !flung) settle(0);
        return r;
    }

    /** No free fling: a flick moves one card. */
    @Override
    public void fling(int velocityX) {
        flung = true;
        settle(velocityX);
    }

    private void settle(int velocity) {
        int p = Snap.page(getScrollX(), getWidth(), startPage, velocity, row.getChildCount());
        smoothScrollTo(p * getWidth(), 0);
        if (p != page) {
            page = p;
            if (onPage != null) onPage.changed(p);
        }
    }
}
