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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Twelve mini months; days shaded by how many events they hold. Tap a day or a month. */
public class YearView extends View {
    public interface Listener {
        void onMonthTap(YearMonth m);
        void onDayTap(LocalDate d);
    }

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.US);
    private static final String[] DOW = { "S", "M", "T", "W", "T", "F", "S" };

    private int year = LocalDate.now().getYear();
    private Map<String, Integer> counts = new HashMap<>();
    private Listener listener;

    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint dowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint dayPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<RectF> dayRects = new ArrayList<>();
    private final List<LocalDate> dayDates = new ArrayList<>();
    private final List<RectF> monthRects = new ArrayList<>();
    private final List<YearMonth> monthKeys = new ArrayList<>();

    public YearView(Context c) { this(c, null); }
    public YearView(Context c, AttributeSet a) {
        super(c, a);
        titlePaint.setColor(CalUi.TEXT);
        titlePaint.setTextSize(CalUi.dp(this, 15));
        titlePaint.setFakeBoldText(true);
        dowPaint.setColor(CalUi.MUTED);
        dowPaint.setTextSize(CalUi.dp(this, 9));
        dowPaint.setTextAlign(Paint.Align.CENTER);
        dayPaint.setTextSize(CalUi.dp(this, 11));
        dayPaint.setTextAlign(Paint.Align.CENTER);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(CalUi.dp(this, 1.5f));
        ring.setColor(CalUi.ACCENT);
    }

    public void setListener(Listener l) { listener = l; }

    public void setYear(int y) {
        year = y;
        counts = new HashMap<>();
        invalidate();
    }

    public void setCounts(Map<String, Integer> c) {
        counts = c != null ? c : new HashMap<>();
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        dayRects.clear();
        dayDates.clear();
        monthRects.clear();
        monthKeys.clear();
        float pad = CalUi.dp(this, 10);
        float boxW = (getWidth() - pad * 5) / 4f;
        float boxH = (getHeight() - pad * 4) / 3f;
        float titleH = CalUi.dp(this, 22), dowH = CalUi.dp(this, 14);
        float cellW = boxW / 7f;
        float cellH = (boxH - titleH - dowH) / 6f;
        float radius = Math.min(cellW, cellH) / 2f - 1;
        LocalDate today = LocalDate.now();

        for (int m = 1; m <= 12; m++) {
            int col = (m - 1) % 4, row = (m - 1) / 4;
            float x = pad + (boxW + pad) * col, y = pad + (boxH + pad) * row;
            YearMonth ym = YearMonth.of(year, m);
            monthRects.add(new RectF(x, y, x + boxW, y + boxH));
            monthKeys.add(ym);
            c.drawText(ym.format(MONTH), x + CalUi.dp(this, 4), y + CalUi.dp(this, 15), titlePaint);
            for (int i = 0; i < 7; i++) c.drawText(DOW[i], x + cellW * i + cellW / 2, y + titleH + CalUi.dp(this, 9), dowPaint);

            LocalDate first = ym.atDay(1);
            int offset = first.getDayOfWeek().getValue() % 7;
            for (int d = 1; d <= ym.lengthOfMonth(); d++) {
                int idx = offset + d - 1;
                int r = idx / 7, cc = idx % 7;
                float cx = x + cellW * cc + cellW / 2, cy = y + titleH + dowH + cellH * r + cellH / 2;
                LocalDate date = ym.atDay(d);
                Integer n = counts.get(date.toString());
                boolean busy = n != null && n > 0;
                if (busy) {
                    int alpha = n >= 4 ? 0xD0 : n == 3 ? 0xA8 : n == 2 ? 0x80 : 0x55;
                    fill.setColor(CalUi.withAlpha(CalUi.ACCENT, alpha));
                    c.drawCircle(cx, cy, radius, fill);
                }
                if (date.equals(today)) c.drawCircle(cx, cy, radius, ring);
                dayPaint.setColor(busy && n >= 3 ? CalUi.INK : CalUi.TEXT);
                c.drawText(String.valueOf(d), cx, cy + CalUi.dp(this, 4), dayPaint);
                dayRects.add(new RectF(cx - cellW / 2, cy - cellH / 2, cx + cellW / 2, cy + cellH / 2));
                dayDates.add(date);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_UP && listener != null) {
            for (int i = 0; i < dayRects.size(); i++) {
                if (dayRects.get(i).contains(ev.getX(), ev.getY())) {
                    listener.onDayTap(dayDates.get(i));
                    performClick();
                    return true;
                }
            }
            for (int i = 0; i < monthRects.size(); i++) {
                if (monthRects.get(i).contains(ev.getX(), ev.getY())) {
                    listener.onMonthTap(monthKeys.get(i));
                    performClick();
                    return true;
                }
            }
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }
}
