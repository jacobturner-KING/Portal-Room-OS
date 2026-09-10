package com.portalroomos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Radial countdown ring for the timer screen. A dim full-circle track sits under
 * a sweeping arc that starts at twelve o'clock and shrinks clockwise as the phase
 * runs down. TimerActivity hands in the arc colour so the ring follows the phase:
 * amber while focusing, green on a break, accent when idle or running the
 * stopwatch. The ring draws nothing else, so the time reads on the panel behind
 * it rather than on the arc.
 */
public class TimerProgressView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();

    /** 1 = the phase has all of its time left, 0 = spent. */
    private float progress = 1f;

    public TimerProgressView(Context context) {
        this(context, null);
    }

    public TimerProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(context.getColor(R.color.rule));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(context.getColor(R.color.accent));
    }

    public void setProgress(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        // The screen ticks 20 times a second; only redraw once the head of the
        // arc would actually land on a different pixel.
        if (Math.abs(clamped - progress) < 0.0005f) return;
        progress = clamped;
        invalidate();
    }

    public void setArcColor(int color) {
        if (arc.getColor() == color) return;
        arc.setColor(color);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        float size = Math.min(width, height);
        if (size <= 0f) return;

        float stroke = Math.max(14f * getResources().getDisplayMetrics().density, size * 0.036f);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);

        float radius = (size - stroke) / 2f;
        float cx = getPaddingLeft() + width / 2f;
        float cy = getPaddingTop() + height / 2f;
        ring.set(cx - radius, cy - radius, cx + radius, cy + radius);

        canvas.drawArc(ring, 0f, 360f, false, track);
        // -90 puts the head at twelve o'clock; a positive sweep runs clockwise.
        if (progress > 0f) canvas.drawArc(ring, -90f, 360f * progress, false, arc);
    }
}
