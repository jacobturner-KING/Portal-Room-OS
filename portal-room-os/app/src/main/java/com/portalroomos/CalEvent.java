package com.portalroomos;

import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One calendar event as served by the Room Brain (/api/calendar/*), resolved to local time. */
public class CalEvent {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);

    public String id, calendarId, calendar, title, location, description, status, htmlLink;
    public String startRaw, endRaw;
    public int color = CalUi.ACCENT;
    public boolean allDay, recurring, editable;
    public long startMs, endMs;          // instants; all-day events use local midnight bounds (end exclusive)
    public LocalDate startDate, endDate; // local dates; endDate is exclusive
    public final List<String> attendees = new ArrayList<>();

    public static CalEvent fromJson(JSONObject o) {
        CalEvent e = new CalEvent();
        e.id = o.optString("id", "");
        e.calendarId = o.optString("calendarId", "");
        e.calendar = o.optString("calendar", "");
        e.title = o.optString("title", "(No title)");
        e.location = o.isNull("location") ? null : o.optString("location", null);
        e.description = o.isNull("description") ? null : o.optString("description", null);
        e.status = o.optString("status", "confirmed");
        e.htmlLink = o.isNull("htmlLink") ? null : o.optString("htmlLink", null);
        e.allDay = o.optBoolean("allDay", false);
        e.recurring = o.optBoolean("recurring", false);
        e.editable = o.optBoolean("editable", false);
        e.startRaw = o.optString("start", "");
        e.endRaw = o.optString("end", "");
        try { e.color = Color.parseColor(o.optString("color", "#8AA4FF")); } catch (Exception ignored) {}
        JSONArray a = o.optJSONArray("attendees");
        if (a != null) for (int i = 0; i < a.length(); i++) e.attendees.add(a.optString(i));

        ZoneId zone = ZoneId.systemDefault();
        try {
            if (e.allDay) {
                e.startDate = LocalDate.parse(e.startRaw);
                e.endDate = e.endRaw.isEmpty() ? e.startDate.plusDays(1) : LocalDate.parse(e.endRaw);
                if (!e.endDate.isAfter(e.startDate)) e.endDate = e.startDate.plusDays(1);
                e.startMs = e.startDate.atStartOfDay(zone).toInstant().toEpochMilli();
                e.endMs = e.endDate.atStartOfDay(zone).toInstant().toEpochMilli();
            } else {
                e.startMs = OffsetDateTime.parse(e.startRaw).toInstant().toEpochMilli();
                e.endMs = e.endRaw.isEmpty() ? e.startMs + 30 * 60_000L
                                             : OffsetDateTime.parse(e.endRaw).toInstant().toEpochMilli();
                if (e.endMs <= e.startMs) e.endMs = e.startMs + 30 * 60_000L;
                e.startDate = Instant.ofEpochMilli(e.startMs).atZone(zone).toLocalDate();
                e.endDate = Instant.ofEpochMilli(e.endMs - 1).atZone(zone).toLocalDate().plusDays(1);
            }
        } catch (Exception ex) {
            e.startDate = LocalDate.now();
            e.endDate = e.startDate.plusDays(1);
            e.startMs = e.startDate.atStartOfDay(zone).toInstant().toEpochMilli();
            e.endMs = e.endDate.atStartOfDay(zone).toInstant().toEpochMilli();
        }
        return e;
    }

    public ZonedDateTime start() { return Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()); }
    public ZonedDateTime end() { return Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault()); }

    /** Does this event touch the local calendar day d? */
    public boolean coversDay(LocalDate d) { return !d.isBefore(startDate) && d.isBefore(endDate); }

    public String whenLabel() {
        if (allDay) {
            LocalDate last = endDate.minusDays(1);
            return last.isAfter(startDate)
                ? startDate.format(DAY) + " – " + last.format(DAY) + " · All day"
                : startDate.format(DAY) + " · All day";
        }
        ZonedDateTime s = start(), en = end();
        boolean sameDay = s.toLocalDate().equals(en.toLocalDate())
            || (en.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) && en.toLocalDate().equals(s.toLocalDate().plusDays(1)));
        if (sameDay) return s.format(DAY) + " · " + s.format(CalUi.TIME) + " – " + en.format(CalUi.TIME);
        return s.format(DAY) + " " + s.format(CalUi.TIME) + " – " + en.format(DAY) + " " + en.format(CalUi.TIME);
    }
}
