package com.andrea.rxhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** The round d-pad: four arrow wedges around a red OK. Reports finger down/up so keys can be held. */
final class DpadView extends View {

    interface Listener {
        void down(int code);
        void up(int code);
    }

    static final int UP = 19, DOWN = 20, LEFT = 21, RIGHT = 22, OK = 23;
    private static final float INNER = 0.38f;

    Listener listener;
    private int pressed = -1;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final RectF oval = new RectF();

    DpadView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        label.setTypeface(Ui.font(c, true));
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(0xffffffff);
        setContentDescription("Direction pad: up, down, left, right, OK in the middle");
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), Ui.dp(getContext(), 340));
        setMeasuredDimension(MeasureSpec.getSize(w), size);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - Ui.dp(getContext(), 4);
        oval.set(cx - r, cy - r, cx + r, cy + r);

        fill.setColor(Ui.TILE);
        c.drawCircle(cx, cy, r, fill);
        if (pressed >= 0 && pressed != OK) {
            fill.setColor(0x55ff1e3c);
            c.drawArc(oval, start(pressed), 90, true, fill);
        }
        // Gaps between the wedges, and the red rim.
        line.setColor(Ui.BG);
        line.setStrokeWidth(Ui.dp(getContext(), 4));
        for (int a = 45; a < 360; a += 90) {
            double rad = Math.toRadians(a);
            c.drawLine(cx, cy, cx + (float) Math.cos(rad) * r, cy + (float) Math.sin(rad) * r, line);
        }
        line.setColor(Ui.RED);
        line.setStrokeWidth(Ui.dp(getContext(), 2));
        c.drawCircle(cx, cy, r, line);

        for (int code : new int[] {UP, RIGHT, DOWN, LEFT}) drawArrow(c, cx, cy, r, code);

        fill.setColor(pressed == OK ? Ui.RED : Ui.BUTTON);
        c.drawCircle(cx, cy, r * INNER, fill);
        c.drawCircle(cx, cy, r * INNER, line);
        label.setTextSize(r * 0.16f);
        c.drawText("OK", cx, cy - (label.descent() + label.ascent()) / 2, label);
    }

    private void drawArrow(Canvas c, float cx, float cy, float r, int code) {
        float d = r * 0.7f, s = r * 0.08f;
        double rad = Math.toRadians(start(code) + 45);
        float x = cx + (float) Math.cos(rad) * d, y = cy + (float) Math.sin(rad) * d;
        float ux = (float) Math.cos(rad), uy = (float) Math.sin(rad); // pointing outwards
        arrow.reset();
        arrow.moveTo(x + ux * s, y + uy * s);
        arrow.lineTo(x - ux * s - uy * s, y - uy * s + ux * s);
        arrow.lineTo(x - ux * s + uy * s, y - uy * s - ux * s);
        arrow.close();
        fill.setColor(pressed == code ? 0xffffffff : Ui.DIM);
        c.drawPath(arrow, fill);
    }

    /** Start angle of a wedge (0° = right, clockwise). */
    private static float start(int code) {
        switch (code) {
            case RIGHT: return -45;
            case DOWN: return 45;
            case LEFT: return 135;
            default: return 225; // UP
        }
    }

    private int zoneAt(float x, float y) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
        double dx = x - cx, dy = y - cy, dist = Math.hypot(dx, dy);
        if (dist > r) return -1;
        if (dist < r * INNER) return OK;
        double a = Math.toDegrees(Math.atan2(dy, dx));
        if (a >= -45 && a < 45) return RIGHT;
        if (a >= 45 && a < 135) return DOWN;
        if (a >= -135 && a < -45) return UP;
        return LEFT;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                int zone = zoneAt(e.getX(), e.getY());
                if (zone < 0) return false;
                pressed = zone;
                invalidate();
                if (listener != null) listener.down(zone);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pressed >= 0 && listener != null) listener.up(pressed);
                pressed = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
