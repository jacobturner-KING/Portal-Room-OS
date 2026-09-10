package com.portalroomos;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.Html;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Calendar screen: day / 3-day / week time grids, a month grid, and a year overview,
 * with tap-to-open event detail and an edit form. All data comes from the Room Brain.
 */
public class CalendarActivity extends Activity
        implements DayHeaderView.Listener, TimeGridView.Listener, MonthGridView.Listener, YearView.Listener {

    private enum Mode { DAY, THREE, WEEK, MONTH, YEAR }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US);
    private static final DateTimeFormatter MON_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);
    private static final DateTimeFormatter EDIT_DATE = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US);

    private RoomBrainClient brain;
    private final ZoneId zone = ZoneId.systemDefault();
    private Mode mode = Mode.WEEK;
    private LocalDate anchor = LocalDate.now();
    private boolean canEdit = false;
    private int requestSeq = 0;
    private boolean needMorningScroll = true;

    private TextView rangeTitle, statusText;
    private View gridContainer;
    private ScrollView gridScroll;
    private DayHeaderView dayHeader;
    private TimeGridView timeGrid;
    private MonthGridView monthGrid;
    private YearView yearView;
    private Button[] modeButtons;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_calendar);
        hideSystemUi();

        brain = new RoomBrainClient(Config.ROOM_BRAIN_URL, Config.APP_TOKEN);
        rangeTitle = findViewById(R.id.rangeTitle);
        statusText = findViewById(R.id.statusText);
        gridContainer = findViewById(R.id.gridContainer);
        gridScroll = findViewById(R.id.gridScroll);
        dayHeader = findViewById(R.id.dayHeader);
        timeGrid = findViewById(R.id.timeGrid);
        monthGrid = findViewById(R.id.monthGrid);
        yearView = findViewById(R.id.yearView);
        dayHeader.setListener(this);
        timeGrid.setListener(this);
        monthGrid.setListener(this);
        yearView.setListener(this);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.prevBtn).setOnClickListener(v -> shift(-1));
        findViewById(R.id.nextBtn).setOnClickListener(v -> shift(1));
        findViewById(R.id.todayBtn).setOnClickListener(v -> { anchor = LocalDate.now(); load(); });
        modeButtons = new Button[] {
            findViewById(R.id.modeDay), findViewById(R.id.modeThree), findViewById(R.id.modeWeek),
            findViewById(R.id.modeMonth), findViewById(R.id.modeYear) };
        Mode[] modes = Mode.values();
        for (int i = 0; i < modeButtons.length; i++) {
            final Mode m = modes[i];
            modeButtons[i].setOnClickListener(v -> setMode(m));
        }

        String startMode = getIntent().getStringExtra("mode");
        if (startMode != null) {
            try { mode = Mode.valueOf(startMode); } catch (Exception ignored) {}
        }

        brain.getCalendars((r, err) -> runOnUiThread(() -> {
            if (err == null && r != null) canEdit = r.canEdit;
        }));
        load();
    }

    // ---- range + navigation ----
    private static boolean isGrid(Mode m) { return m == Mode.DAY || m == Mode.THREE || m == Mode.WEEK; }
    private int dayCount() { return mode == Mode.DAY ? 1 : mode == Mode.THREE ? 3 : 7; }

    private void setMode(Mode m) {
        if (isGrid(m) && !isGrid(mode)) needMorningScroll = true;
        mode = m;
        load();
    }

    private LocalDate rangeStart() {
        switch (mode) {
            case DAY: case THREE: return anchor;
            case WEEK: return CalUi.startOfWeek(anchor);
            case MONTH: return CalUi.startOfWeek(YearMonth.from(anchor).atDay(1));
            default: return LocalDate.of(anchor.getYear(), 1, 1);
        }
    }

    private LocalDate rangeEnd() { // exclusive
        switch (mode) {
            case DAY: return anchor.plusDays(1);
            case THREE: return anchor.plusDays(3);
            case WEEK: return CalUi.startOfWeek(anchor).plusDays(7);
            case MONTH: return rangeStart().plusDays(42);
            default: return LocalDate.of(anchor.getYear() + 1, 1, 1);
        }
    }

    private void shift(int dir) {
        switch (mode) {
            case DAY: anchor = anchor.plusDays(dir); break;
            case THREE: anchor = anchor.plusDays(3L * dir); break;
            case WEEK: anchor = anchor.plusWeeks(dir); break;
            case MONTH: anchor = anchor.plusMonths(dir); break;
            default: anchor = anchor.plusYears(dir); break;
        }
        load();
    }

    private String title() {
        LocalDate s = rangeStart();
        switch (mode) {
            case DAY: return anchor.format(LONG_DAY);
            case THREE: case WEEK: {
                LocalDate last = rangeEnd().minusDays(1);
                String tail = s.getMonth() == last.getMonth()
                    ? String.valueOf(last.getDayOfMonth()) : last.format(MON_DAY);
                return s.format(MON_DAY) + " – " + tail + ", " + last.getYear();
            }
            case MONTH: return YearMonth.from(anchor).format(MONTH_YEAR);
            default: return String.valueOf(anchor.getYear());
        }
    }

    private void showViewsForMode() {
        gridContainer.setVisibility(isGrid(mode) ? View.VISIBLE : View.GONE);
        monthGrid.setVisibility(mode == Mode.MONTH ? View.VISIBLE : View.GONE);
        yearView.setVisibility(mode == Mode.YEAR ? View.VISIBLE : View.GONE);
        Mode[] modes = Mode.values();
        for (int i = 0; i < modeButtons.length; i++) {
            boolean sel = modes[i] == mode;
            // Selection is carried by colour, not alpha: fading the whole button
            // composites its label toward the photo and loses AAA contrast.
            modeButtons[i].setTextColor(sel ? CalUi.ACCENT : CalUi.MUTED);
        }
    }

    private void load() {
        final int seq = ++requestSeq;
        LocalDate s = rangeStart(), e = rangeEnd();
        rangeTitle.setText(title());
        showViewsForMode();
        statusText.setText("Loading…");
        String startIso = s.atStartOfDay(zone).format(ISO);
        String endIso = e.atStartOfDay(zone).format(ISO);

        if (mode == Mode.YEAR) {
            yearView.setYear(anchor.getYear());
            brain.getBusyDays(startIso, endIso, zone.getId(), (days, err) -> runOnUiThread(() -> {
                if (seq != requestSeq) return;
                if (err != null || days == null) { statusText.setText("Couldn't load calendar"); return; }
                yearView.setCounts(days);
                int total = 0;
                for (int n : days.values()) total += n;
                statusText.setText(total + " one-off events in " + anchor.getYear() + " · repeating events not counted · tap a day or month");
            }));
            return;
        }

        if (mode == Mode.MONTH) {
            monthGrid.setMonth(YearMonth.from(anchor));
        } else {
            dayHeader.setDays(s, dayCount());
            timeGrid.setDays(s, dayCount());
        }
        brain.getEvents(startIso, endIso, (events, err) -> runOnUiThread(() -> {
            if (seq != requestSeq) return;
            if (err != null || events == null) {
                statusText.setText("Couldn't load calendar" + (err != null ? " · " + err.getMessage() : ""));
                return;
            }
            if (mode == Mode.MONTH) {
                monthGrid.setEvents(events);
            } else {
                dayHeader.setEvents(events);
                timeGrid.setEvents(events);
                if (needMorningScroll) {
                    needMorningScroll = false;
                    gridScroll.post(() -> gridScroll.scrollTo(0, (int) (timeGrid.hourHeight() * 7)));
                }
            }
            String n = events.size() == 1 ? "1 event" : events.size() + " events";
            statusText.setText(n + (canEdit ? " · tap one to view or edit" : " · view only until Google is re-linked with edit access"));
        }));
    }

    // ---- taps from the views ----
    @Override public void onEventTap(CalEvent e) { showEvent(e); }
    @Override public void onDayTap(LocalDate d) { anchor = d; setMode(Mode.DAY); }
    @Override public void onMonthTap(YearMonth m) { anchor = m.atDay(1); setMode(Mode.MONTH); }

    // ---- event detail ----
    private static String plain(String s) {
        if (s == null) return "";
        return s.contains("<") ? Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT).toString().trim() : s.trim();
    }

    private void showEvent(final CalEvent e) {
        View v = getLayoutInflater().inflate(R.layout.dialog_event, null);
        ((TextView) v.findViewById(R.id.evTitle)).setText(e.title);
        ((TextView) v.findViewById(R.id.evWhen)).setText(e.whenLabel());
        TextView where = v.findViewById(R.id.evWhere);
        if (e.location == null || e.location.trim().isEmpty()) where.setVisibility(View.GONE);
        else where.setText(e.location);
        ((TextView) v.findViewById(R.id.evCalendar)).setText(e.calendar + (e.recurring ? " · repeats" : ""));
        TextView desc = v.findViewById(R.id.evDescription);
        String d = plain(e.description);
        if (d.isEmpty()) desc.setVisibility(View.GONE); else desc.setText(d);
        TextView att = v.findViewById(R.id.evAttendees);
        if (e.attendees.isEmpty()) att.setVisibility(View.GONE);
        else att.setText("With: " + String.join(", ", e.attendees));

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setView(v)
            .setNegativeButton("Close", null)
            .setNeutralButton("Open day", (dlg, w) -> { anchor = e.startDate; setMode(Mode.DAY); });
        if (canEdit && e.editable) b.setPositiveButton("Edit", (dlg, w) -> editEvent(e));
        b.show();
    }

    private void editEvent(final CalEvent e) {
        View v = getLayoutInflater().inflate(R.layout.dialog_event_edit, null);
        final EditText title = v.findViewById(R.id.edTitle);
        final EditText location = v.findViewById(R.id.edLocation);
        final EditText description = v.findViewById(R.id.edDescription);
        final CheckBox allDayBox = v.findViewById(R.id.edAllDay);
        final Button sDateBtn = v.findViewById(R.id.edStartDate);
        final Button sTimeBtn = v.findViewById(R.id.edStartTime);
        final Button eDateBtn = v.findViewById(R.id.edEndDate);
        final Button eTimeBtn = v.findViewById(R.id.edEndTime);

        final String origTitle = e.title, origLocation = e.location == null ? "" : e.location, origDesc = plain(e.description);
        title.setText(origTitle);
        location.setText(origLocation);
        description.setText(origDesc);

        final LocalDate[] sDate = { e.startDate };
        final LocalDate[] eDate = { e.allDay ? e.endDate.minusDays(1) : Instant.ofEpochMilli(e.endMs).atZone(zone).toLocalDate() };
        final LocalTime[] sTime = { e.allDay ? LocalTime.of(9, 0) : e.start().toLocalTime() };
        final LocalTime[] eTime = { e.allDay ? LocalTime.of(10, 0) : e.end().toLocalTime() };
        final boolean[] allDay = { e.allDay };

        final Runnable refresh = () -> {
            sDateBtn.setText(sDate[0].format(EDIT_DATE));
            eDateBtn.setText(eDate[0].format(EDIT_DATE));
            sTimeBtn.setText(sTime[0].format(CalUi.TIME));
            eTimeBtn.setText(eTime[0].format(CalUi.TIME));
            int vis = allDay[0] ? View.GONE : View.VISIBLE;
            sTimeBtn.setVisibility(vis);
            eTimeBtn.setVisibility(vis);
        };
        allDayBox.setChecked(allDay[0]);
        allDayBox.setOnCheckedChangeListener((btn, checked) -> { allDay[0] = checked; refresh.run(); });
        sDateBtn.setOnClickListener(x -> new DatePickerDialog(this, (p, y, m, d) -> {
            sDate[0] = LocalDate.of(y, m + 1, d);
            if (eDate[0].isBefore(sDate[0])) eDate[0] = sDate[0];
            refresh.run();
        }, sDate[0].getYear(), sDate[0].getMonthValue() - 1, sDate[0].getDayOfMonth()).show());
        eDateBtn.setOnClickListener(x -> new DatePickerDialog(this, (p, y, m, d) -> {
            eDate[0] = LocalDate.of(y, m + 1, d);
            refresh.run();
        }, eDate[0].getYear(), eDate[0].getMonthValue() - 1, eDate[0].getDayOfMonth()).show());
        sTimeBtn.setOnClickListener(x -> new TimePickerDialog(this, (p, h, m) -> {
            LocalTime old = sTime[0];
            sTime[0] = LocalTime.of(h, m);
            // keep the duration when the start moves
            long minutes = java.time.Duration.between(old, eTime[0]).toMinutes();
            if (eDate[0].equals(sDate[0]) && minutes > 0) eTime[0] = sTime[0].plusMinutes(Math.min(minutes, 23 * 60));
            refresh.run();
        }, sTime[0].getHour(), sTime[0].getMinute(), false).show());
        eTimeBtn.setOnClickListener(x -> new TimePickerDialog(this, (p, h, m) -> {
            eTime[0] = LocalTime.of(h, m);
            refresh.run();
        }, eTime[0].getHour(), eTime[0].getMinute(), false).show());
        refresh.run();

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("Edit event")
            .setView(v)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(btn -> {
            JSONObject body = new JSONObject();
            try {
                body.put("calendarId", e.calendarId).put("id", e.id);
                String t = title.getText().toString().trim();
                String l = location.getText().toString().trim();
                String dsc = description.getText().toString().trim();
                if (!t.equals(origTitle)) body.put("title", t);
                if (!l.equals(origLocation)) body.put("location", l);
                if (!dsc.equals(origDesc)) body.put("description", dsc);
                boolean timeChanged = allDay[0] != e.allDay
                    || !sDate[0].equals(e.startDate)
                    || (allDay[0] ? !eDate[0].equals(e.endDate.minusDays(1))
                                  : (!sTime[0].equals(e.start().toLocalTime()) || !eTime[0].equals(e.end().toLocalTime())
                                     || !eDate[0].equals(Instant.ofEpochMilli(e.endMs).atZone(zone).toLocalDate())));
                if (timeChanged) {
                    body.put("allDay", allDay[0]);
                    if (allDay[0]) {
                        if (eDate[0].isBefore(sDate[0])) { toast("End must be on or after start"); return; }
                        body.put("start", sDate[0].toString());
                        body.put("end", eDate[0].plusDays(1).toString());
                    } else {
                        ZonedDateTime st = ZonedDateTime.of(sDate[0], sTime[0], zone);
                        ZonedDateTime en = ZonedDateTime.of(eDate[0], eTime[0], zone);
                        if (!en.isAfter(st)) { toast("End must be after start"); return; }
                        body.put("start", st.format(ISO));
                        body.put("end", en.format(ISO));
                    }
                }
            } catch (Exception ex) {
                toast("Couldn't build the update");
                return;
            }
            if (body.length() <= 2) { dlg.dismiss(); return; } // nothing changed
            statusText.setText("Saving…");
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            brain.updateEvent(body, (r, err) -> runOnUiThread(() -> {
                if (err != null || r == null) {
                    statusText.setText("Save failed · Room Brain unreachable");
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    return;
                }
                if (!r.ok) {
                    statusText.setText(r.message);
                    toast(r.message);
                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    return;
                }
                dlg.dismiss();
                toast("Saved");
                load();
            }));
        }));
        dlg.show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
