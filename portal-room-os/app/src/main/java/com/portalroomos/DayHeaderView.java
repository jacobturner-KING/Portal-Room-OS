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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Day labels plus the all-day strip above the time grid. Shares column geometry with TimeGridView. */
public class DayHeaderView extends View {
    public interface Listener {
        void onEventTap(CalEvent e);
        void onDayTap(LocalDate d);
    }

    private static final DateTimeFormatter DOW = DateTimeFormatter.ofPattern("EEE", Locale.US);

    private LocalDate firstDay = LocalDate.now();
    private int dayCount = 7;
    private final List<CalEvent> allDay = new ArrayList<>();
    private final List<Integer> rows = new ArrayList<>();
    private int rowCount = 0;
    private Listener listener;

    private final TextPaint dowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint numPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chipText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final List<RectF> hitRects = new ArrayList<>();
    private final List<Object> hitTargets = new ArrayList<>();

    public DayHeaderView(Context c) { this(c, null); }
    public DayHeaderView(Context c, AttributeSet a) {
        super(c, a);
        dowPaint.setColor(CalUi.MUTED);
        dowPaint.setTextSize(CalUi.dp(this, 13));
        dowPaint.setTextAlign(Paint.Align.CENTER);
        numPaint.setTextSize(CalUi.dp(this, 22));
        numPaint.setFakeBoldText(true);
        numPaint.setTextAlign(Paint.Align.CENTER);
        chipText.setTextSize(CalUi.dp(this, 13));
        line.setColor(CalUi.LINE);
    }

    public void setListener(Listener l) { listener = l; }

    public void setDays(LocalDate first, int count) {
        firstDay = first;
        dayCount = Math.max(1, count);
        requestLayout();
        invalidate();
    }

    public void setEvents(List<CalEvent> events) {
        allDay.clear();
        rows.clear();
        LocalDate last = firstDay.plusDays(dayCount);
        for (CalEvent e : events) {
            if (e.allDay && e.startDate.isBefore(last) && e.endDate.isAfter(firstDay)) allDay.add(e);
        }
        allDay.sort((a, b) -> a.startDate.compareTo(b.startDate));
        List<LocalDate> rowEnd = new ArrayList<>(); // exclusive end of the last chip in each row
        for (CalEvent e : allDay) {
            int r = 0;
            while (r < rowEnd.size() && rowEnd.get(r).isAfter(e.startDate)) r++;
            if (r == rowEnd.size()) rowEnd.add(e.endDate); else rowEnd.set(r, e.endDate);
            rows.add(r);
        }
        rowCount = rowEnd.size();
        requestLayout();
        invalidate();
    }

    private float gutter() { return CalUi.dp(this, 56); }
    private float labelH() { return CalUi.dp(this, 58); }
    private float rowH() { return CalUi.dp(this, 26); }

    @Override protected void onMeasure(int w, int h) {
        int height = (int) (labelH() + rowCount * rowH() + (rowCount > 0 ? CalUi.dp(this, 6) : 0));
        setMeasuredDimension(MeasureSpec.getSize(w), height);
    }

    @Override protected void onDraw(Canvas c) {
        hitRects.clear();
        hitTargets.clear();
        float g = gutter();
        float colW = (getWidth() - g) / dayCount;
        LocalDate today = LocalDate.now();
        for (int i = 0; i < dayCount; i++) {
            LocalDate d = firstDay.plusDays(i);
            float cx = g + colW * i + colW / 2;
            c.drawText(d.format(DOW).toUpperCase(Locale.US), cx, CalUi.dp(this, 16), dowPaint);
            if (d.equals(today)) {
                fill.setColor(CalUi.ACCENT);
                c.drawCircle(cx, CalUi.dp(this, 38), CalUi.dp(this, 17), fill);
                numPaint.setColor(CalUi.INK);
            } else {
                numPaint.setColor(CalUi.TEXT);
            }
            c.drawText(String.valueOf(d.getDayOfMonth()), cx, CalUi.dp(this, 46), numPaint);
            hitRects.add(new RectF(g + colW * i, 0, g + colW * (i + 1), labelH()));
            hitTargets.add(d);
        }
        for (int k = 0; k < allDay.size(); k++) {
            CalEvent e = allDay.get(k);
            int c0 = (int) Math.max(0, ChronoUnit.DAYS.between(firstDay, e.startDate));
            int c1 = (int) Math.min(dayCount, ChronoUnit.DAYS.between(firstDay, e.endDate));
            if (c1 <= c0) continue;
            float top = labelH() + rows.get(k) * rowH();
            RectF r = new RectF(g + colW * c0 + 2, top + 2, g + colW * c1 - 2, top + rowH() - 2);
            fill.setColor(e.color);
            c.drawRoundRect(r, CalUi.dp(this, 6), CalUi.dp(this, 6), fill);
            chipText.setColor(CalUi.textOn(e.color));
            c.drawText(CalUi.fit(chipText, e.title, r.width() - CalUi.dp(this, 12)),
                r.left + CalUi.dp(this, 6), r.bottom - CalUi.dp(this, 7), chipText);
            hitRects.add(r);
            hitTargets.add(e);
        }
        c.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1, line);
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_UP && listener != null) {
            // Chips are added after day cells; search from the end so a chip wins.
            for (int i = hitRects.size() - 1; i >= 0; i--) {
                if (hitRects.get(i).contains(ev.getX(), ev.getY())) {
                    Object t = hitTargets.get(i);
                    if (t instanceof CalEvent) listener.onEventTap((CalEvent) t);
                    else listener.onDayTap((LocalDate) t);
                    performClick();
                    return true;
                }
            }
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }
}
