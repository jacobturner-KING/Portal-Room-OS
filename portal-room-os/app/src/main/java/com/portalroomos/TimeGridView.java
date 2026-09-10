package com.portalroomos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Hour-by-hour grid for 1, 3 or 7 days with timed events laid out in lanes.
 * Lives inside a ScrollView; DayHeaderView above it draws the day labels.
 */
public class TimeGridView extends View {
    public interface Listener {
        void onEventTap(CalEvent e);
    }

    private LocalDate firstDay = LocalDate.now();
    private int dayCount = 7;
    private final List<CalEvent> timed = new ArrayList<>();
    private Listener listener;

    private final TextPaint hourPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint now = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<RectF> hitRects = new ArrayList<>();
    private final List<CalEvent> hitEvents = new ArrayList<>();

    public TimeGridView(Context c) { this(c, null); }
    public TimeGridView(Context c, AttributeSet a) {
        super(c, a);
        hourPaint.setColor(CalUi.MUTED);
        hourPaint.setTextSize(CalUi.dp(this, 12));
        hourPaint.setTextAlign(Paint.Align.RIGHT);
        titlePaint.setTextSize(CalUi.dp(this, 13));
        titlePaint.setFakeBoldText(true);
        timePaint.setTextSize(CalUi.dp(this, 12));
        line.setColor(CalUi.LINE);
        now.setColor(CalUi.NOW);
        now.setStrokeWidth(CalUi.dp(this, 2));
    }

    public void setListener(Listener l) { listener = l; }
    public float hourHeight() { return CalUi.dp(this, 64); }
    private float gutter() { return CalUi.dp(this, 56); }
    private float top() { return CalUi.dp(this, 8); }

    public void setDays(LocalDate first, int count) {
        firstDay = first;
        dayCount = Math.max(1, count);
        invalidate();
    }

    public void setEvents(List<CalEvent> events) {
        timed.clear();
        ZoneId zone = ZoneId.systemDefault();
        long rangeStart = firstDay.atStartOfDay(zone).toInstant().toEpochMilli();
        long rangeEnd = firstDay.plusDays(dayCount).atStartOfDay(zone).toInstant().toEpochMilli();
        for (CalEvent e : events) {
            if (!e.allDay && e.startMs < rangeEnd && e.endMs > rangeStart) timed.add(e);
        }
        timed.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (24 * hourHeight() + top() + CalUi.dp(this, 12)));
    }

    @Override protected void onDraw(Canvas c) {
        hitRects.clear();
        hitEvents.clear();
        float g = gutter(), hh = hourHeight(), t0 = top();
        float colW = (getWidth() - g) / dayCount;
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now();

        // Today's column tint
        for (int i = 0; i < dayCount; i++) {
            if (firstDay.plusDays(i).equals(today)) {
                fill.setColor(CalUi.TODAY_TINT);
                c.drawRect(g + colW * i, t0, g + colW * (i + 1), t0 + 24 * hh, fill);
            }
        }
        // Hour lines and labels
        for (int h = 0; h <= 24; h++) {
            float y = t0 + h * hh;
            c.drawLine(g, y, getWidth(), y, line);
            if (h > 0 && h < 24) c.drawText(CalUi.hourLabel(h), g - CalUi.dp(this, 8), y + CalUi.dp(this, 4), hourPaint);
        }
        for (int i = 0; i <= dayCount; i++) {
            float x = g + colW * i;
            c.drawLine(x, t0, x, t0 + 24 * hh, line);
        }

        // Events, one day column at a time
        for (int i = 0; i < dayCount; i++) {
            LocalDate d = firstDay.plusDays(i);
            long dayStart = d.atStartOfDay(zone).toInstant().toEpochMilli();
            long dayEnd = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            List<CalEvent> evs = new ArrayList<>();
            for (CalEvent e : timed) if (e.startMs < dayEnd && e.endMs > dayStart) evs.add(e);
            int n = evs.size();
            if (n == 0) continue;

            // Lane assignment: greedy within overlap clusters.
            int[] lane = new int[n];
            int[] lanes = new int[n];
            List<Long> laneEnd = new ArrayList<>();
            int clusterStart = 0;
            long clusterMaxEnd = 0;
            for (int k = 0; k < n; k++) {
                CalEvent e = evs.get(k);
                long s = Math.max(e.startMs, dayStart);
                if (k > 0 && s >= clusterMaxEnd) {
                    for (int j = clusterStart; j < k; j++) lanes[j] = laneEnd.size();
                    laneEnd.clear();
                    clusterStart = k;
                }
                int l = 0;
                while (l < laneEnd.size() && laneEnd.get(l) > s) l++;
                if (l == laneEnd.size()) laneEnd.add(e.endMs); else laneEnd.set(l, e.endMs);
                lane[k] = l;
                clusterMaxEnd = Math.max(clusterMaxEnd, e.endMs);
            }
            for (int j = clusterStart; j < n; j++) lanes[j] = laneEnd.size();

            for (int k = 0; k < n; k++) {
                CalEvent e = evs.get(k);
                long s = Math.max(e.startMs, dayStart), en = Math.min(e.endMs, dayEnd);
                float y0 = t0 + (s - dayStart) / 3_600_000f * hh;
                float y1 = t0 + (en - dayStart) / 3_600_000f * hh;
                if (y1 - y0 < CalUi.dp(this, 18)) y1 = y0 + CalUi.dp(this, 18);
                float laneW = colW / lanes[k];
                float x0 = g + colW * i + laneW * lane[k] + 2;
                RectF r = new RectF(x0, y0 + 1, x0 + laneW - 4, y1 - 1);
                fill.setColor(CalUi.withAlpha(e.color, 0xE6));
                c.drawRoundRect(r, CalUi.dp(this, 6), CalUi.dp(this, 6), fill);
                int ink = CalUi.textOn(e.color);
                titlePaint.setColor(ink);
                timePaint.setColor(CalUi.withAlpha(ink, 0xB0));
                c.save();
                c.clipRect(r);
                float tx = r.left + CalUi.dp(this, 6);
                c.drawText(CalUi.fit(titlePaint, e.title, r.width() - CalUi.dp(this, 10)), tx, r.top + CalUi.dp(this, 15), titlePaint);
                if (r.height() >= CalUi.dp(this, 34)) {
                    String when = CalUi.shortTime(e.start()) + " – " + CalUi.shortTime(e.end());
                    c.drawText(CalUi.fit(timePaint, when, r.width() - CalUi.dp(this, 10)), tx, r.top + CalUi.dp(this, 30), timePaint);
                }
                c.restore();
                hitRects.add(r);
                hitEvents.add(e);
            }
        }

        // Now line
        for (int i = 0; i < dayCount; i++) {
            if (firstDay.plusDays(i).equals(today)) {
                LocalTime lt = LocalTime.now();
                float y = t0 + (lt.getHour() + lt.getMinute() / 60f) * hh;
                float x = g + colW * i;
                c.drawLine(x, y, x + colW, y, now);
                c.drawCircle(x, y, CalUi.dp(this, 4), now);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_UP && listener != null) {
            for (int i = hitRects.size() - 1; i >= 0; i--) {
                if (hitRects.get(i).contains(ev.getX(), ev.getY())) {
                    listener.onEventTap(hitEvents.get(i));
                    performClick();
                    return true;
                }
            }
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }
}
