package com.andrea.rxhome;

/** Which card a swipe ends on (Pager): a flick moves one card from where it started, else the nearest one. */
final class Snap {

    static final int FLICK = 800; // px/s

    private Snap() {}

    static int page(int scrollX, int width, int startPage, int velocity, int pages) {
        if (width <= 0 || pages <= 0) return startPage;
        int p = velocity > FLICK ? startPage + 1 : velocity < -FLICK ? startPage - 1 : Math.round(scrollX / (float) width);
        return Math.max(0, Math.min(pages - 1, p));
    }
}
