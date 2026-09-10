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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Six-week month grid with per-day event chips. Tap a chip for the event, a cell for the day. */
public class MonthGridView extends View {
    public interface Listener {
        void onEventTap(CalEvent e);
        void onDayTap(LocalDate d);
    }

    private static final String[] DOW = { "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT" };

    private YearMonth month = YearMonth.now();
    private LocalDate gridStart = CalUi.startOfWeek(month.atDay(1));
    private final Map<LocalDate, List<CalEvent>> byDay = new HashMap<>();
    private Listener listener;

    private final TextPaint dowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint numPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chipPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint morePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<RectF> hitRects = new ArrayList<>();
    private final List<Object> hitTargets = new ArrayList<>();

    public MonthGridView(Context c) { this(c, null); }
    public MonthGridView(Context c, AttributeSet a) {
        super(c, a);
        dowPaint.setColor(CalUi.MUTED);
        dowPaint.setTextSize(CalUi.dp(this, 12));
        dowPaint.setTextAlign(Paint.Align.CENTER);
        numPaint.setTextSize(CalUi.dp(this, 15));
        numPaint.setFakeBoldText(true);
        chipPaint.setTextSize(CalUi.dp(this, 12));
        morePaint.setTextSize(CalUi.dp(this, 11));
        morePaint.setColor(CalUi.MUTED);
        line.setColor(CalUi.LINE);
    }

    public void setListener(Listener l) { listener = l; }
    public LocalDate gridStart() { return gridStart; }
    public LocalDate gridEnd() { return gridStart.plusDays(42); }

    public void setMonth(YearMonth m) {
        month = m;
        gridStart = CalUi.startOfWeek(m.atDay(1));
        byDay.clear();
        invalidate();
    }

    public void setEvents(List<CalEvent> events) {
        byDay.clear();
        LocalDate end = gridEnd();
        for (CalEvent e : events) {
            LocalDate d = e.startDate.isBefore(gridStart) ? gridStart : e.startDate;
            LocalDate last = e.endDate.isAfter(end) ? end : e.endDate;
            for (; d.isBefore(last); d = d.plusDays(1)) {
                List<CalEvent> l = byDay.get(d);
                if (l == null) { l = new ArrayList<>(); byDay.put(d, l); }
                l.add(e);
            }
        }
        for (List<CalEvent> l : byDay.values()) {
            l.sort((a, b) -> a.allDay != b.allDay ? (a.allDay ? -1 : 1) : Long.compare(a.startMs, b.startMs));
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        hitRects.clear();
        hitTargets.clear();
        float headerH = CalUi.dp(this, 26);
        float cellW = getWidth() / 7f;
        float cellH = (getHeight() - headerH) / 6f;
        float chipH = CalUi.dp(this, 20);
        float pad = CalUi.dp(this, 4);
        LocalDate today = LocalDate.now();

        for (int i = 0; i < 7; i++) c.drawText(DOW[i], cellW * i + cellW / 2, CalUi.dp(this, 17), dowPaint);
        c.drawLine(0, headerH, getWidth(), headerH, line);

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 7; col++) {
                LocalDate d = gridStart.plusDays(row * 7L + col);
                float x = cellW * col, y = headerH + cellH * row;
                RectF cell = new RectF(x, y, x + cellW, y + cellH);
                boolean inMonth = YearMonth.from(d).equals(month);
                if (d.equals(today)) {
                    fill.setColor(CalUi.TODAY_TINT);
                    c.drawRect(cell, fill);
                }
                c.drawLine(x, y, x, y + cellH, line);
                c.drawLine(x, y + cellH, x + cellW, y + cellH, line);

                float nx = x + pad + CalUi.dp(this, 4), ny = y + CalUi.dp(this, 18);
                if (d.equals(today)) {
                    fill.setColor(CalUi.ACCENT);
                    c.drawCircle(nx + CalUi.dp(this, 7), ny - CalUi.dp(this, 5), CalUi.dp(this, 12), fill);
                    numPaint.setColor(CalUi.INK);
                    numPaint.setTextAlign(Paint.Align.CENTER);
                    c.drawText(String.valueOf(d.getDayOfMonth()), nx + CalUi.dp(this, 7), ny, numPaint);
                    numPaint.setTextAlign(Paint.Align.LEFT);
                } else {
                    numPaint.setColor(inMonth ? CalUi.TEXT : CalUi.withAlpha(CalUi.MUTED, 0x70));
                    c.drawText(String.valueOf(d.getDayOfMonth()), nx, ny, numPaint);
                }
                hitRects.add(cell);
                hitTargets.add(d);

                List<CalEvent> evs = byDay.get(d);
                if (evs == null || evs.isEmpty()) continue;
                int maxChips = (int) ((cellH - CalUi.dp(this, 26)) / chipH);
                int n = evs.size();
                int show = n > maxChips ? Math.max(0, maxChips - 1) : n;
                float cy = y + CalUi.dp(this, 24);
                for (int k = 0; k < show; k++) {
                    CalEvent e = evs.get(k);
                    RectF r = new RectF(x + pad, cy, x + cellW - pad, cy + chipH - 3);
                    if (e.allDay) {
                        fill.setColor(e.color);
                        c.drawRoundRect(r, CalUi.dp(this, 4), CalUi.dp(this, 4), fill);
                        chipPaint.setColor(CalUi.textOn(e.color));
                        c.drawText(CalUi.fit(chipPaint, e.title, r.width() - CalUi.dp(this, 8)),
                            r.left + CalUi.dp(this, 4), r.bottom - CalUi.dp(this, 5), chipPaint);
                    } else {
                        fill.setColor(e.color);
                        c.drawCircle(r.left + CalUi.dp(this, 5), r.centerY(), CalUi.dp(this, 3.5f), fill);
                        chipPaint.setColor(CalUi.TEXT);
                        String label = CalUi.shortTime(e.start()) + " " + e.title;
                        c.drawText(CalUi.fit(chipPaint, label, r.width() - CalUi.dp(this, 16)),
                            r.left + CalUi.dp(this, 12), r.bottom - CalUi.dp(this, 5), chipPaint);
                    }
                    hitRects.add(r);
                    hitTargets.add(e);
                    cy += chipH;
                }
                if (n > show) {
                    c.drawText("+" + (n - show) + " more", x + pad + CalUi.dp(this, 4), cy + CalUi.dp(this, 13), morePaint);
                }
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_UP && listener != null) {
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
