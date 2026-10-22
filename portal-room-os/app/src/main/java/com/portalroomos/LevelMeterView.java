package com.portalroomos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * A small scrolling waveform beside the Record chip: proof, at a glance, that
 * the microphones hear the room. It draws the recorder's recent sound levels
 * as bars mirrored about the midline, newest on the right, so a voice shows as
 * bars leaping and silence as a thin line. The bars turn the accent colour
 * while something is being heard and stay muted when the room is quiet, so the
 * difference reads from across the room.
 *
 * The levels come from a {@link LevelRing} written by a capture thread, the
 * recorder's unless told otherwise; this view only reads them, on its own
 * 50 ms redraw while active.
 */
public class LevelMeterView extends View {

    private static final int REDRAW_MS = 50;
    /** Above this (about -48 dBFS), the bars are lit: a voice, not the room's hum. */
    private static final float HEARD = 0.25f;

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int quietColor, heardColor;
    private boolean active;
    private LevelRing source = RecorderService.LEVELS;

    public LevelMeterView(Context context) {
        this(context, null);
    }

    public LevelMeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        quietColor = context.getColor(R.color.muted);
        heardColor = context.getColor(R.color.success);
        bar.setStrokeCap(Paint.Cap.ROUND);
        bar.setStyle(Paint.Style.STROKE);
    }

    /** Which microphone to show. */
    void setSource(LevelRing ring) {
        source = ring;
        invalidate();
    }

    /** Redraws itself while active; a single static frame otherwise. */
    void setActive(boolean on) {
        if (active == on) return;
        active = on;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float[] levels = source.levels;
        int n = levels.length;
        int pos = source.pos;   // next write slot; the oldest sample
        float w = getWidth(), h = getHeight();
        float gap = w / n;
        float stroke = Math.max(2f, gap * 0.55f);
        bar.setStrokeWidth(stroke);
        float mid = h / 2f;
        float maxHalf = (h - stroke) / 2f;
        for (int i = 0; i < n; i++) {
            float level = levels[(pos + i) % n];
            float half = Math.max(stroke / 2f, level * maxHalf);
            float x = gap * i + gap / 2f;
            bar.setColor(level > HEARD ? heardColor : quietColor);
            c.drawLine(x, mid - half, x, mid + half, bar);
        }
        if (active) postInvalidateDelayed(REDRAW_MS);
    }
}
