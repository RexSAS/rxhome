package com.andrea.rxhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/** A round colour picker: around the rim = colour, towards the centre = whiter (see Wheel). */
final class ColorWheelView extends View {

    interface Listener {
        /** While dragging (done = false) and once on release (done = true). */
        void picked(int hue, int sat, boolean done);
    }

    Listener listener;
    private int hue, sat = 100;
    private float cx, cy, r;
    private final Paint hues = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint whites = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);

    ColorWheelView(Context c) {
        super(c);
        setContentDescription("Colour wheel: around the edge picks the colour, towards the centre makes it whiter");
    }

    void setColor(int hue, int sat) {
        this.hue = hue;
        this.sat = sat;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), Ui.dp(getContext(), 280));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        cx = w / 2f;
        cy = h / 2f;
        r = Math.min(cx, cy) - Ui.dp(getContext(), 16);
        int[] ring = new int[7];
        for (int i = 0; i < 7; i++) ring[i] = Color.HSVToColor(new float[] {i * 60 % 360, 1f, 1f});
        hues.setShader(new SweepGradient(cx, cy, ring, null));
        whites.setShader(new RadialGradient(cx, cy, r, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawCircle(cx, cy, r, hues);
        c.drawCircle(cx, cy, r, whites);
        float[] p = Wheel.point(hue, sat, r);
        float mx = cx + p[0], my = cy + p[1], m = Ui.dp(getContext(), 14);
        marker.setStyle(Paint.Style.FILL);
        marker.setColor(Color.HSVToColor(new float[] {hue, sat / 100f, 1f}));
        c.drawCircle(mx, my, m, marker);
        marker.setStyle(Paint.Style.STROKE);
        marker.setStrokeWidth(Ui.dp(getContext(), 3));
        marker.setColor(0xFFFFFFFF);
        c.drawCircle(mx, my, m, marker);
        marker.setStrokeWidth(Ui.dp(getContext(), 1));
        marker.setColor(Ui.BG);
        c.drawCircle(mx, my, m + Ui.dp(getContext(), 2), marker);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_MOVE && a != MotionEvent.ACTION_UP) return true;
        if (a == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        int[] hs = Wheel.hueSat(e.getX() - cx, e.getY() - cy, r);
        hue = hs[0];
        sat = hs[1];
        invalidate();
        if (listener != null) listener.picked(hue, sat, a == MotionEvent.ACTION_UP);
        return true;
    }
}
