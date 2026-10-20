package com.portalroomos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * One step track's sixteen steps, drawn as pads in four groups of four, one
 * group a beat. Tap a pad to turn it on or off; drag along the row to paint
 * the same change across several; hold a pad to accent it (it plays at full
 * velocity and draws brighter). The step under the playhead lights as the
 * beat goes by.
 */
public class StepRowView extends View {

    interface OnEdit { void onEdit(); }

    private static final long HOLD_MS = 450;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;
    private final int offColor, beatColor, onColor, accentColor, ruleColor, nowColor;
    private BeatSession.Track track;
    private OnEdit onEdit;
    private int playing = -1;
    private boolean paintValue;
    private int downCell = -1;
    private boolean moved, downAccent;

    public StepRowView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        offColor = c.getColor(R.color.button);
        beatColor = c.getColor(R.color.button_pressed);
        onColor = (c.getColor(R.color.accent) & 0x00FFFFFF) | 0x99000000;
        accentColor = c.getColor(R.color.accent);
        ruleColor = c.getColor(R.color.rule);
        nowColor = c.getColor(R.color.text);
        stroke.setStyle(Paint.Style.STROKE);
    }

    void bind(BeatSession.Track t, OnEdit edit) {
        track = t;
        onEdit = edit;
        invalidate();
    }

    /** The step being heard, or -1 when stopped. */
    void setPlaying(int step) {
        if (step == playing) return;
        playing = step;
        invalidate();
    }

    private float groupGap() { return 10 * dp; }
    private float gap() { return 4 * dp; }

    private float cellWidth() {
        return (getWidth() - 3 * groupGap() - 12 * gap()) / BeatSession.STEPS;
    }

    private float cellLeft(int i) {
        return i * cellWidth() + (i / 4) * groupGap() + (i - i / 4) * gap();
    }

    private int cellAt(float x) {
        for (int i = 0; i < BeatSession.STEPS; i++) {
            float l = cellLeft(i);
            if (x < l + cellWidth() + gap() / 2) return i;
        }
        return BeatSession.STEPS - 1;
    }

    @Override protected void onDraw(Canvas c) {
        if (track == null) return;
        float w = cellWidth(), h = getHeight(), rad = 8 * dp;
        for (int i = 0; i < BeatSession.STEPS; i++) {
            float l = cellLeft(i);
            r.set(l, 0, l + w, h);
            boolean on = track.steps[i], accent = on && track.accents[i];
            fill.setColor(accent ? accentColor : on ? onColor : (i % 4 == 0 ? beatColor : offColor));
            c.drawRoundRect(r, rad, rad, fill);
            boolean now = i == playing;
            stroke.setColor(now ? nowColor : ruleColor);
            stroke.setStrokeWidth(now ? 3 * dp : dp);
            r.inset(stroke.getStrokeWidth() / 2, stroke.getStrokeWidth() / 2);
            c.drawRoundRect(r, rad, rad, stroke);
        }
    }

    private final Runnable hold = () -> {
        if (downCell < 0 || moved || track == null) return;
        // A hold accents (and turns on) the pad; holding an accented pad makes it plain.
        track.steps[downCell] = true;
        track.accents[downCell] = !downAccent;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        downCell = -1;
        invalidate();
        if (onEdit != null) onEdit.onEdit();
    };

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (track == null) return false;
        int cell = cellAt(e.getX());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                downCell = cell;
                downAccent = track.accents[cell];
                moved = false;
                paintValue = !track.steps[cell];
                set(cell, paintValue);
                postDelayed(hold, HOLD_MS);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (downCell >= 0 && cell != downCell) moved = true;
                if (moved) set(cell, paintValue);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(hold);
                downCell = -1;
                if (onEdit != null) onEdit.onEdit();
                return true;
        }
        return false;
    }

    private void set(int cell, boolean on) {
        if (track.steps[cell] == on) return;
        track.steps[cell] = on;
        if (!on) track.accents[cell] = false;
        invalidate();
    }
}
