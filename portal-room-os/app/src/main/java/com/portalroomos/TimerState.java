package com.portalroomos;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Pomodoro model, kept out of any one screen so the timer screen and the
 * dashboard cannot end up disagreeing about it.
 *
 * The running phase is pinned to a wall-clock instant rather than counted down
 * in memory, which is what lets both screens read the same truth: whoever is in
 * front loads this, walks it over any boundary that has already passed, and
 * writes it back. Only one screen is resumed at a time, so a phase is credited
 * and rung exactly once.
 */
final class TimerState {

    enum Phase { IDLE, FOCUS, BREAK }

    static final String PREFS = "timer";
    /** {focus minutes, break minutes} for the two fixed intervals. */
    static final int[][] FIXED = {{25, 5}, {50, 10}};
    static final int CUSTOM = 2;
    static final String[] PRESET_NAMES = {"Standard", "Deep Work", "Custom"};
    static final int FOCUS_STEP = 5, FOCUS_FLOOR = 5, FOCUS_CEIL = 180;
    static final int BREAK_STEP = 1, BREAK_FLOOR = 1, BREAK_CEIL = 60;
    static final int MAX_SESSIONS = 40, MAX_DISTRACTIONS = 40;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    Phase phase = Phase.IDLE;
    boolean running = false;
    long totalMs = 0;
    long endsAtWall = 0;
    long remainingMs = 0;          // authoritative while paused

    int preset = 0;
    /** Set when an interval is picked mid-run; applied at the next clean boundary. */
    int pendingPreset = -1;
    int customFocus = 45, customBreak = 15;

    /** Sticks to each focus session started until it is changed. */
    String sessionName = "";
    /** Completed focus sessions today, as "name|minutes|when". */
    final List<String> sessions = new ArrayList<>();
    /** Interruptions logged today, as "when|where". */
    final List<String> distractions = new ArrayList<>();
    int focusCompleted = 0;
    String statsDate = "";

    // ------------------------------------------------------------- intervals

    int focusMin() { return preset == CUSTOM ? customFocus : FIXED[preset][0]; }

    int breakMin() { return preset == CUSTOM ? customBreak : FIXED[preset][1]; }

    String presetName() { return PRESET_NAMES[preset]; }

    static long minutes(int m) { return m * 60_000L; }

    static int clamp(int v, int low, int high) { return Math.max(low, Math.min(high, v)); }

    /** Milliseconds left in the current phase. */
    long remaining() {
        return running ? Math.max(0, endsAtWall - System.currentTimeMillis()) : remainingMs;
    }

    /** Whether there is anything worth showing on the dashboard. */
    boolean showable() { return running || phase != Phase.IDLE; }

    float progress() {
        return totalMs <= 0 ? 1f : (float) remaining() / (float) totalMs;
    }

    // -------------------------------------------------------------- movement

    void reset() {
        running = false;
        phase = Phase.IDLE;
        applyPendingPreset();
        totalMs = minutes(focusMin());
        remainingMs = totalMs;
    }

    void startOrPause() {
        if (running) {
            remainingMs = remaining();
            running = false;
        } else {
            if (phase == Phase.IDLE) {
                phase = Phase.FOCUS;
                totalMs = minutes(focusMin());
                remainingMs = totalMs;
            }
            endsAtWall = System.currentTimeMillis() + remainingMs;
            running = true;
        }
    }

    void applyPendingPreset() {
        if (pendingPreset >= 0) {
            preset = pendingPreset;
            pendingPreset = -1;
        }
    }

    /**
     * Walks the Pomodoro forward over any boundary that has already passed.
     * Focus rolls into its break; the break lands on idle and stops. Because
     * idle stops the walk, being away for hours can never credit more than the
     * one focus session that was actually under way.
     *
     * @return how many boundaries were crossed
     */
    int advance() {
        if (!running) return 0;
        int transitions = 0;
        long now = System.currentTimeMillis();
        while (running && now >= endsAtWall) {
            transitions++;
            if (phase == Phase.FOCUS) {
                // Stamp the session with the boundary it actually crossed, not
                // with the moment we noticed.
                recordSession(endsAtWall);
                phase = Phase.BREAK;
                totalMs = minutes(breakMin());
                endsAtWall = endsAtWall + totalMs;
            } else {
                phase = Phase.IDLE;
                running = false;
                applyPendingPreset();
                totalMs = minutes(focusMin());
                remainingMs = totalMs;
            }
        }
        return transitions;
    }

    private void recordSession(long endedAtWall) {
        focusCompleted++;
        sessions.add(clean(sessionName) + "|" + focusMin() + "|" + stamp(endedAtWall));
        while (sessions.size() > MAX_SESSIONS) sessions.remove(0);
    }

    void logDistraction() {
        String where = phase == Phase.FOCUS ? "during focus"
                : phase == Phase.BREAK ? "during break" : "while idle";
        distractions.add(stamp(System.currentTimeMillis()) + "|" + where);
        while (distractions.size() > MAX_DISTRACTIONS) distractions.remove(0);
    }

    /** Today's tallies only last the day. */
    void rollDate() {
        String today = LocalDate.now().toString();
        if (!today.equals(statsDate)) {
            statsDate = today;
            focusCompleted = 0;
            sessions.clear();
            distractions.clear();
        }
    }

    // --------------------------------------------------------------- records

    static String stamp(long wallMs) {
        return Instant.ofEpochMilli(wallMs).atZone(ZoneId.systemDefault())
                .toLocalTime().format(STAMP);
    }

    /** Records are pipe delimited, so names cannot carry the delimiter. */
    static String clean(String name) {
        return name == null ? "" : name.replace("|", " ").replace("\n", " ").trim();
    }

    /** Always three fields, however old or truncated the stored record is. */
    static String[] fields(String record) {
        String[] parts = record.split("\\|", 3);
        return new String[]{
            parts.length > 0 ? parts[0] : "",
            parts.length > 1 ? parts[1] : "?",
            parts.length > 2 ? parts[2] : "",
        };
    }

    // ----------------------------------------------------------- persistence

    static TimerState load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        TimerState s = new TimerState();
        s.phase = Phase.valueOf(p.getString("phase", Phase.IDLE.name()));
        s.running = p.getBoolean("timerRunning", false);
        s.preset = clamp(p.getInt("preset", 0), 0, PRESET_NAMES.length - 1);
        s.pendingPreset = p.getInt("pendingPreset", -1);
        if (s.pendingPreset >= PRESET_NAMES.length) s.pendingPreset = -1;
        s.customFocus = clamp(p.getInt("customFocus", 45), FOCUS_FLOOR, FOCUS_CEIL);
        s.customBreak = clamp(p.getInt("customBreak", 15), BREAK_FLOOR, BREAK_CEIL);
        s.totalMs = p.getLong("phaseTotalMs", minutes(s.focusMin()));
        s.endsAtWall = p.getLong("phaseEndsAtWall", 0);
        s.remainingMs = p.getLong("phaseRemainingMs", s.totalMs);
        s.sessionName = p.getString("sessionName", "");
        s.focusCompleted = p.getInt("focusCompleted", 0);
        s.statsDate = p.getString("statsDate", "");
        for (String line : split(p.getString("sessions", ""))) s.sessions.add(line);
        for (String line : split(p.getString("distractions", ""))) s.distractions.add(line);
        s.rollDate();
        if (s.totalMs <= 0) {
            s.totalMs = minutes(s.focusMin());
            s.remainingMs = s.totalMs;
        }
        return s;
    }

    void save(Context c) {
        SharedPreferences.Editor e =
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString("phase", phase.name());
        e.putBoolean("timerRunning", running);
        e.putInt("preset", preset);
        e.putInt("pendingPreset", pendingPreset);
        e.putInt("customFocus", customFocus);
        e.putInt("customBreak", customBreak);
        e.putLong("phaseTotalMs", totalMs);
        e.putLong("phaseEndsAtWall", endsAtWall);
        e.putLong("phaseRemainingMs", remainingMs);
        e.putString("sessionName", sessionName);
        e.putInt("focusCompleted", focusCompleted);
        e.putString("statsDate", statsDate);
        e.putString("sessions", String.join("\n", sessions));
        e.putString("distractions", String.join("\n", distractions));
        e.apply();
    }

    static String[] split(String blob) {
        if (blob == null || blob.isEmpty()) return new String[0];
        return blob.split("\n");
    }

    /** Countdown text, rounded up so a fresh phase reads 25:00 rather than 24:59. */
    static String countdown(long ms) {
        long secs = (Math.max(0, ms) + 999) / 1000;
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}
