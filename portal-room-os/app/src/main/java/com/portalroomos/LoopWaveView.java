package com.portalroomos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/**
 * One loop track's take as a waveform across the row, with a line per bar and
 * the playhead sweeping it. Empty, it shows how long a take will be; while
 * its take is being recorded it fills red, bar by bar, so you can see how
 * much is left from across the room.
 */
public class LoopWaveView extends View {

    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp;
    private final int waveColor, mutedWave, ruleColor, headColor, recColor, ground;
    private BeatEngine engine;
    private int index;
    private float[] peaksFor;
    private float[] peaks;
    private long heard = Long.MIN_VALUE;

    public LoopWaveView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        waveColor = c.getColor(R.color.success);
        mutedWave = c.getColor(R.color.muted);
        ruleColor = c.getColor(R.color.rule);
        headColor = c.getColor(R.color.text);
        recColor = c.getColor(R.color.alert);
        ground = c.getColor(R.color.button);
        line.setStrokeWidth(dp);
        wave.setStrokeWidth(Math.max(1, dp));
        text.setColor(c.getColor(R.color.muted));
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15, c.getResources().getDisplayMetrics()));
    }

    void bind(BeatEngine e, int trackIndex) {
        engine = e;
        index = trackIndex;
        invalidate();
    }

    /** The transport position being heard, or Long.MIN_VALUE when stopped. */
    void setHeard(long pos) {
        heard = pos;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (engine == null) return;
        BeatSession s = engine.session;
        BeatSession.Track t = s.tracks[index];
        float w = getWidth(), h = getHeight(), rad = 10 * dp;
        r.set(0, 0, w, h);
        fill.setColor(ground);
        c.drawRoundRect(r, rad, rad, fill);

        boolean recording = engine.recTrack == index && engine.rec != BeatEngine.Rec.IDLE;
        float[] a = t.audio;
        long len = recording ? engine.recLength : a != null ? a.length : s.loopFrames();
        int bar = s.barFrames();
        int bars = (int) Math.max(1, Math.round((double) len / bar));

        if (recording) {
            long done = heard == Long.MIN_VALUE ? 0 : heard - engine.recStart;
            float p = engine.rec == BeatEngine.Rec.FINISHING ? 1f : Math.max(0, Math.min(1, (float) done / len));
            fill.setColor((recColor & 0x00FFFFFF) | 0x66000000);
            r.set(0, 0, w * p, h);
            c.drawRoundRect(r, rad, rad, fill);
        }

        if (a != null && !recording) {
            if (peaksFor != a || peaks == null || peaks.length != (int) w) buildPeaks(a, (int) w);
            wave.setColor(s.audible(t) ? waveColor : mutedWave);
            float mid = h / 2;
            for (int x = 0; x < peaks.length; x++) {
                float half = Math.max(dp / 2, peaks[x] * (h / 2 - 4 * dp));
                c.drawLine(x, mid - half, x, mid + half, wave);
            }
        }

        line.setColor(ruleColor);
        for (int b = 1; b < bars; b++) {
            float x = w * b / bars;
            c.drawLine(x, 6 * dp, x, h - 6 * dp, line);
        }

        String label = null;
        if (recording) {
            switch (engine.rec) {
                case WAITING:
                    label = heard != Long.MIN_VALUE && heard < 0 ? "Count-in… " + (1 + (int) ((heard + bar) * 4 / bar)) : "Starts on the next bar";
                    break;
                case RECORDING:
                    long done = Math.max(0, heard - engine.recStart);
                    label = "● Recording · bar " + Math.min(bars, 1 + done / bar) + " of " + bars;
                    break;
                case FINISHING:
                    label = "Cleaning up the take…";
                    break;
                default:
            }
        } else if (a == null) {
            label = "Empty · tap ● to record " + bars + (bars == 1 ? " bar" : " bars");
        }
        if (label != null) {
            text.setColor(recording ? headColor : mutedWave);
            c.drawText(label, 14 * dp, h / 2 + text.getTextSize() / 3, text);
        }

        if (heard != Long.MIN_VALUE && heard >= 0 && a != null && !recording) {
            float x = w * Math.floorMod(heard, (long) a.length) / a.length;
            line.setColor(headColor);
            line.setStrokeWidth(2 * dp);
            c.drawLine(x, 2 * dp, x, h - 2 * dp, line);
            line.setStrokeWidth(dp);
        }
    }

    private void buildPeaks(float[] a, int columns) {
        peaksFor = a;
        peaks = new float[Math.max(1, columns)];
        float max = 0;
        for (int x = 0; x < peaks.length; x++) {
            int from = (int) ((long) a.length * x / peaks.length);
            int to = (int) ((long) a.length * (x + 1) / peaks.length);
            float p = 0;
            for (int i = from; i < to; i++) p = Math.max(p, Math.abs(a[i]));
            peaks[x] = p;
            max = Math.max(max, p);
        }
        if (max > 0) for (int x = 0; x < peaks.length; x++) peaks[x] /= max;
    }
}
