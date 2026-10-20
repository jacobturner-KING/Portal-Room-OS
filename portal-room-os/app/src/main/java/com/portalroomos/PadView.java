package com.portalroomos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * One drum pad: a big target that plays its track the instant a finger lands.
 * It flashes when the track sounds, whether from your finger or the pattern,
 * and carries the track's sixteen steps as a strip of dots along the bottom,
 * so taps being recorded can be seen landing.
 *
 * It answers the touch-down, not the release, and every finger that lands on
 * it counts, so two fingers on one pad is a flam. The hit goes to the engine
 * with the touch event's own time, which is when the finger landed rather than
 * when the UI thread got round to it.
 */
public class PadView extends View {

    interface OnHit { void onHit(long touchNanos); }

    private static final long FLASH_NS = 140_000_000L;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;
    private final int ground, lit, accent, text, muted, rule, now;
    private BeatEngine engine;
    private int index;
    private OnHit onHit;
    private long pressedNs;
    private int step = -1;

    public PadView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        ground = c.getColor(R.color.button);
        lit = c.getColor(R.color.button_pressed);
        accent = c.getColor(R.color.accent);
        text = c.getColor(R.color.text);
        muted = c.getColor(R.color.muted);
        rule = c.getColor(R.color.rule);
        now = c.getColor(R.color.amber);
        edge.setStyle(Paint.Style.STROKE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24, c.getResources().getDisplayMetrics()));
    }

    void bind(BeatEngine e, int trackIndex, OnHit hit) {
        engine = e;
        index = trackIndex;
        onHit = hit;
        invalidate();
    }

    /** The step being heard, or -1 when stopped; the pad's dots mark it. */
    void setStep(int s) {
        step = s;
        invalidate();
    }

    /** Held still, alone, this long: the pad's menu (its sound) rather than a drum. */
    private static final long HOLD_MS = 800;
    private final Runnable hold = this::performLongClick;

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            long nanos = e.getEventTime() * 1_000_000L;   // uptimeMillis: CLOCK_MONOTONIC, as System.nanoTime
            pressedNs = System.nanoTime();
            if (onHit != null) onHit.onHit(nanos);
            removeCallbacks(hold);
            if (a == MotionEvent.ACTION_DOWN) postDelayed(hold, HOLD_MS);
            invalidate();
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) removeCallbacks(hold);
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        if (engine == null) return;
        BeatSession.Track t = engine.session.tracks[index];
        float w = getWidth(), h = getHeight(), rad = 22 * dp;
        long nowNs = System.nanoTime();
        long since = Math.min(nowNs - pressedNs, Math.max(0, nowNs - engine.flashNs[index]));
        boolean flashing = since >= 0 && since < FLASH_NS;
        float glow = flashing ? 1f - (float) since / FLASH_NS : 0f;

        r.set(0, 0, w, h);
        fill.setColor(ground);
        c.drawRoundRect(r, rad, rad, fill);
        if (glow > 0) {
            fill.setColor((accent & 0x00FFFFFF) | ((int) (glow * 0xB0) << 24));
            c.drawRoundRect(r, rad, rad, fill);
        }
        edge.setColor(flashing ? accent : rule);
        edge.setStrokeWidth((flashing ? 3 : 1) * dp);
        r.inset(edge.getStrokeWidth() / 2, edge.getStrokeWidth() / 2);
        c.drawRoundRect(r, rad, rad, edge);

        boolean audible = engine.session.audible(t);
        label.setColor(glow > 0.5f ? 0xFF101218 : audible ? text : muted);
        c.drawText(t.name + (t.mute ? " (muted)" : ""), w / 2, h / 2 - 6 * dp, label);

        // The pattern, one dot a step, the beat-starting ones larger.
        float rowY = h - 22 * dp, span = w - 48 * dp, x0 = 24 * dp;
        for (int i = 0; i < BeatSession.STEPS; i++) {
            float x = x0 + span * (i + 0.5f) / BeatSession.STEPS;
            float radius = (i % 4 == 0 ? 5.5f : 4f) * dp;
            if (i == step) {
                dot.setColor(now);
                c.drawCircle(x, rowY, radius + 3 * dp, dot);
            }
            dot.setColor(t.steps[i] ? (t.accents[i] ? text : accent) : rule);
            c.drawCircle(x, rowY, radius, dot);
        }
    }
}
