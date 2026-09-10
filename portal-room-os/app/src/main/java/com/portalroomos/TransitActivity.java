package com.portalroomos;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Transit board: ferries both ways with live vessel status, a passenger-only
 * water taxi, and live bus arrivals. Which terminals, stops and routes those are
 * is configured on the Worker, not here, so this screen carries no place names of
 * its own: even the title and each stop's label arrive with the data. All of it
 * comes from the Room Brain's /api/transit; this screen renders and polls every
 * minute.
 */
public class TransitActivity extends Activity {
    private static final long POLL_MS = 60_000;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm", Locale.US);
    private static final DateTimeFormatter CLOCK_AMPM = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    // Mirror of res/values/colors.xml success / amber / alert.
    private static final int GREEN = 0xFF73D19C, AMBER = 0xFFF0C674, RED = 0xFFFFAAAA;

    private final Handler handler = new Handler();
    private final ZoneId zone = ZoneId.systemDefault();
    private RoomBrainClient brain;
    private LinearLayout ferryList, busList;
    private TextView updatedText, statusText, screenTitle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_transit);
        hideSystemUi();

        brain = new RoomBrainClient(Config.ROOM_BRAIN_URL, Config.APP_TOKEN);
        ferryList = findViewById(R.id.ferryList);
        busList = findViewById(R.id.busList);
        updatedText = findViewById(R.id.updatedText);
        statusText = findViewById(R.id.statusText);
        screenTitle = findViewById(R.id.screenTitle);
        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        findViewById(R.id.refreshBtn).setOnClickListener(v -> load());
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            load();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private void load() {
        statusText.setText("Updating…");
        brain.getTransit((t, err) -> runOnUiThread(() -> {
            if (err != null || t == null) {
                statusText.setText("Room Brain unreachable · showing last update");
                return;
            }
            render(t);
        }));
    }

    // ---- rendering ----
    private void render(JSONObject t) {
        ferryList.removeAllViews();
        busList.removeAllViews();
        screenTitle.setText(t.optString("title", "Transit"));

        if (t.has("configured") && !t.optBoolean("configured", true)) {
            addNote(ferryList, "No transit configured. Set TRANSIT_CONFIG on the Room Brain "
                    + "with your terminals, stops and routes; see the Worker README.");
            updatedText.setText("");
            statusText.setText("Transit is off until the Worker is configured");
            return;
        }

        JSONObject ferries = t.optJSONObject("ferries");
        if (ferries == null) ferries = new JSONObject();

        addSection(ferryList, "Departing");
        JSONArray dep = ferries.optJSONArray("departing");
        if (dep == null || dep.length() == 0) addNote(ferryList, "No more sailings today");
        for (int i = 0; dep != null && i < dep.length(); i++) addFerryRow(dep.optJSONObject(i), true);

        addSection(ferryList, "Arriving");
        JSONArray arr = ferries.optJSONArray("arriving");
        if (arr == null || arr.length() == 0) addNote(ferryList, "No more sailings today");
        for (int i = 0; arr != null && i < arr.length(); i++) addFerryRow(arr.optJSONObject(i), false);

        JSONArray alerts = ferries.optJSONArray("alerts");
        if (alerts != null && alerts.length() > 0) {
            addSection(ferryList, "Ferry alerts");
            for (int i = 0; i < alerts.length(); i++) addNote(ferryList, "• " + alerts.optString(i));
        }

        JSONArray buses = t.optJSONArray("buses");
        for (int i = 0; buses != null && i < buses.length(); i++) {
            JSONObject stop = buses.optJSONObject(i);
            if (stop == null) continue;
            addSection(busList, stop.optString("label", stop.optString("name", "Stop")));
            JSONArray a = stop.optJSONArray("arrivals");
            if (!stop.isNull("error")) addNote(busList, "Live bus data unavailable right now · retrying");
            else if (a == null || a.length() == 0) addNote(busList, "No arrivals in the next 3 hours");
            for (int k = 0; a != null && k < a.length(); k++) addBusRow(a.optJSONObject(k));
        }

        addSection(busList, "Water taxi · passenger only");
        JSONArray wt = t.optJSONArray("waterTaxi");
        if (wt == null || wt.length() == 0) addNote(busList, "No more sailings today");
        for (int i = 0; wt != null && i < wt.length(); i++) addWaterTaxiRow(wt.optJSONObject(i));

        JSONArray errors = t.optJSONArray("errors");
        String gen = t.optString("generatedAt", "");
        String when = gen.isEmpty() ? "" : "Updated " + parse(gen).format(CLOCK_AMPM);
        updatedText.setText(when);
        statusText.setText(errors != null && errors.length() > 0
            ? "Some live data missing this refresh (" + errors.length() + ") · sources retry every minute"
            : "Ferries: WSDOT · Buses & water taxi: OneBusAway · refreshes every minute");
    }

    private void addFerryRow(JSONObject s, boolean departing) {
        if (s == null) return;
        ZonedDateTime departs = parse(s.optString("departs"));
        String other = departing ? "to " + s.optString("to") : "from " + s.optString("from");
        String vessel = s.optString("vessel", "");
        StringBuilder sub = new StringBuilder();
        JSONArray notes = s.optJSONArray("notes");
        for (int i = 0; notes != null && i < notes.length(); i++) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(notes.optString(i));
        }
        if (!s.isNull("driveUpSpaces")) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(s.optInt("driveUpSpaces")).append(" drive-up spaces");
        }
        String status = s.optString("status", "scheduled");
        int delay = s.optInt("delayMin", 0);
        String label = "";
        int color = CalUi.MUTED;
        if ("underway".equals(status)) {
            String eta = s.isNull("eta") ? null : parse(s.optString("eta")).format(CLOCK);
            label = eta != null ? "Underway · ETA " + eta : "Underway";
            color = CalUi.ACCENT;
            if (delay >= 4) { label += " · left " + delay + " min late"; color = AMBER; }
        } else if ("at dock".equals(status)) {
            if (delay >= 4) { label = "At dock · " + delay + " min late"; color = AMBER; }
            else { label = "At dock · boarding"; color = GREEN; }
        }
        addRow(ferryList, departs.format(CLOCK), departs.format(DateTimeFormatter.ofPattern("a", Locale.US)),
            other + (vessel.isEmpty() ? "" : " · " + vessel), sub.toString(), label, color);
    }

    private void addBusRow(JSONObject a) {
        if (a == null) return;
        boolean live = a.optBoolean("live", false);
        ZonedDateTime when = parse(live ? a.optString("predicted") : a.optString("scheduled"));
        long mins = Math.round((when.toInstant().toEpochMilli() - System.currentTimeMillis()) / 60_000.0);
        String big, small;
        if (live && mins <= 90) {
            big = mins <= 0 ? "Now" : mins + " min";
            small = ""; // the subtitle carries the clock time
        } else {
            big = when.format(CLOCK);
            small = when.format(DateTimeFormatter.ofPattern("a", Locale.US));
        }
        String route = a.optString("route", "");
        String title = route + " · " + a.optString("headsign", "");
        String sub = live
            ? "Arrives " + when.format(CLOCK_AMPM) + " · scheduled " + parse(a.optString("scheduled")).format(CLOCK_AMPM)
            : "Scheduled";
        addRow(busList, big, small, title, sub, live ? "LIVE" : "", live ? GREEN : CalUi.MUTED);
    }

    private void addWaterTaxiRow(JSONObject w) {
        if (w == null) return;
        boolean live = !w.isNull("predicted");
        ZonedDateTime when = parse(live ? w.optString("predicted") : w.optString("scheduled"));
        String title = w.optString("direction", "");
        String sub = "Leaves " + w.optString("leavesFrom", "") + (live ? " · live" : " · scheduled");
        addRow(busList, when.format(CLOCK), when.format(DateTimeFormatter.ofPattern("a", Locale.US)), title, sub,
            live ? "LIVE" : "", live ? GREEN : CalUi.MUTED);
    }

    private void addSection(LinearLayout list, String title) {
        TextView tv = (TextView) getLayoutInflater().inflate(R.layout.row_transit_section, list, false);
        tv.setText(title.toUpperCase(Locale.US));
        list.addView(tv);
    }

    private void addNote(LinearLayout list, String text) {
        TextView tv = (TextView) getLayoutInflater().inflate(R.layout.row_transit_note, list, false);
        tv.setText(text);
        list.addView(tv);
    }

    private void addRow(LinearLayout list, String big, String small, String title, String sub, String status, int statusColor) {
        View row = getLayoutInflater().inflate(R.layout.row_transit, list, false);
        ((TextView) row.findViewById(R.id.rowTime)).setText(big);
        TextView ampm = row.findViewById(R.id.rowAmPm);
        if (small == null || small.isEmpty()) ampm.setVisibility(View.GONE); else ampm.setText(small);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(title);
        TextView subView = row.findViewById(R.id.rowSub);
        if (sub == null || sub.isEmpty()) subView.setVisibility(View.GONE); else subView.setText(sub);
        TextView st = row.findViewById(R.id.rowStatus);
        if (status == null || status.isEmpty()) st.setVisibility(View.GONE);
        else { st.setText(status); st.setTextColor(statusColor); }
        list.addView(row);
    }

    private ZonedDateTime parse(String iso) {
        try { return OffsetDateTime.parse(iso).toInstant().atZone(zone); }
        catch (Exception e) { return Instant.now().atZone(zone); }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
