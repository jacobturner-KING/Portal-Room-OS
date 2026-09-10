package com.portalroomos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Timer screen: the Pomodoro from {@link TimerState} and a stopwatch behind one
 * mode switch, sharing the radial dial in {@link TimerProgressView}.
 *
 * The two use different clocks on purpose. The Pomodoro pins its phase to a
 * wall-clock instant, so twenty-five minutes means twenty-five minutes of real
 * time whether or not this screen is on top, and the dashboard reads the same
 * model. The stopwatch measures on the monotonic clock, which is what timing an
 * interval actually wants, and persists a wall-clock anchor only so it can be
 * restored.
 *
 * Everything runs off one Handler that ticks while either side is running and
 * stops itself when neither is, so an idle screen costs nothing.
 */
public class TimerActivity extends Activity {

    private enum Mode { TIMER, STOPWATCH }

    private static final long TICK_MS = 50;
    private static final int MAX_LAPS = 50;

    private final Handler handler = new Handler();
    private final SingingBowl bowl = SingingBowl.shared();

    private TimerState state = new TimerState();
    private Mode mode = Mode.TIMER;

    private boolean swRunning = false;
    private long swAccumulatedMs = 0;
    private long swRunSinceElapsed = 0;
    private final List<Long> laps = new ArrayList<>();

    private TimerProgressView progress;
    private TextView bigTime, fraction, phaseLabel, phaseSub, headerHint, screenTitle;
    private TextView nameChip, sessionsHeader, sessionsEmpty, distractionHeader;
    private TextView lapSummary, statusText;
    private LinearLayout timerPanel, stopwatchPanel, sessionList, distractionList, lapList;
    private ScrollView sessionScroll, distractionScroll, lapScroll;
    private Button primaryBtn, resetBtn, extraBtn, presetStandard, presetDeep, presetCustom;
    private Button modeTimerBtn, modeStopwatchBtn, clearBtn;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_timer);
        hideSystemUi();

        progress = findViewById(R.id.progress);
        bigTime = findViewById(R.id.bigTime);
        fraction = findViewById(R.id.fraction);
        phaseLabel = findViewById(R.id.phaseLabel);
        phaseSub = findViewById(R.id.phaseSub);
        headerHint = findViewById(R.id.headerHint);
        screenTitle = findViewById(R.id.screenTitle);
        nameChip = findViewById(R.id.nameChip);
        sessionsHeader = findViewById(R.id.sessionsHeader);
        sessionsEmpty = findViewById(R.id.sessionsEmpty);
        distractionHeader = findViewById(R.id.distractionHeader);
        lapSummary = findViewById(R.id.lapSummary);
        statusText = findViewById(R.id.statusText);
        timerPanel = findViewById(R.id.timerPanel);
        stopwatchPanel = findViewById(R.id.stopwatchPanel);
        sessionList = findViewById(R.id.sessionList);
        distractionList = findViewById(R.id.distractionList);
        lapList = findViewById(R.id.lapList);
        sessionScroll = findViewById(R.id.sessionScroll);
        distractionScroll = findViewById(R.id.distractionScroll);
        lapScroll = findViewById(R.id.lapScroll);
        primaryBtn = findViewById(R.id.primaryBtn);
        resetBtn = findViewById(R.id.resetBtn);
        extraBtn = findViewById(R.id.extraBtn);
        presetStandard = findViewById(R.id.presetStandard);
        presetDeep = findViewById(R.id.presetDeep);
        presetCustom = findViewById(R.id.presetCustom);
        modeTimerBtn = findViewById(R.id.modeTimer);
        modeStopwatchBtn = findViewById(R.id.modeStopwatch);
        clearBtn = findViewById(R.id.clearDistractions);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        modeTimerBtn.setOnClickListener(v -> setMode(Mode.TIMER));
        modeStopwatchBtn.setOnClickListener(v -> setMode(Mode.STOPWATCH));
        presetStandard.setOnClickListener(v -> choosePreset(0));
        presetDeep.setOnClickListener(v -> choosePreset(1));
        // Tapping Custom always opens the editor: a long press would be
        // invisible on a wall panel, and confirming selects it anyway.
        presetCustom.setOnClickListener(v -> promptInterval());
        nameChip.setOnClickListener(v -> promptName("Name this session", state.sessionName, value -> {
            state.sessionName = value;
            save();
            render();
        }));
        primaryBtn.setOnClickListener(v -> onPrimary());
        resetBtn.setOnClickListener(v -> onReset());
        extraBtn.setOnClickListener(v -> {
            if (mode == Mode.TIMER) logDistraction(); else recordLap();
        });
        clearBtn.setOnClickListener(v -> {
            state.distractions.clear();
            save();
            renderDistractions();
            render();
        });

        loadStopwatch();
        bowl.prepareAsync();   // ~400 ms of synthesis, long before it is needed
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        // Reload rather than trust memory: the dashboard owns the same model and
        // may have moved it on while this screen was away.
        state = TimerState.load(this);
        if (state.advance() > 0) state.save(this);
        renderSessions();
        renderDistractions();
        renderLaps();
        render();
        startTicking();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        save();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- ticking

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (state.advance() > 0) {
                // Only a live tick rings; a phase found already over on resume
                // is shown, not sounded.
                bowl.ring();
                renderSessions();   // a focus that just landed adds a row
                save();
            }
            render();
            if (isRunning()) handler.postDelayed(this, TICK_MS);
        }
    };

    private boolean isRunning() { return state.running || swRunning; }

    private void startTicking() {
        handler.removeCallbacks(tick);
        if (isRunning()) handler.post(tick);
    }

    // ------------------------------------------------------------- the buttons

    private void onPrimary() {
        if (mode == Mode.TIMER) {
            state.startOrPause();
        } else if (swRunning) {
            swAccumulatedMs += SystemClock.elapsedRealtime() - swRunSinceElapsed;
            swRunning = false;
        } else {
            swRunSinceElapsed = SystemClock.elapsedRealtime();
            swRunning = true;
        }
        save();
        render();
        startTicking();
    }

    private void onReset() {
        if (mode == Mode.TIMER) {
            state.reset();
        } else {
            swRunning = false;
            swAccumulatedMs = 0;
            laps.clear();
            renderLaps();
        }
        save();
        render();
        startTicking();
    }

    private void setMode(Mode next) {
        if (mode == next) return;
        mode = next;
        save();
        render();
        startTicking();
    }

    private void choosePreset(int index) {
        if (state.running) {
            // Do not tear down a session in progress on a stray tap.
            state.pendingPreset = index;
        } else {
            state.preset = index;
            state.pendingPreset = -1;
            state.phase = TimerState.Phase.IDLE;
            state.totalMs = TimerState.minutes(state.focusMin());
            state.remainingMs = state.totalMs;
        }
        save();
        render();
    }

    private void logDistraction() {
        state.logDistraction();
        save();
        renderDistractions();
        render();
    }

    private void recordLap() {
        if (swElapsed() == 0) return;
        if (laps.size() >= MAX_LAPS) return;   // renderStopwatch says so
        laps.add(swElapsed());
        save();
        renderLaps();
        render();
    }

    // ------------------------------------------------------------- the dialogs

    private void renameSession(int index) {
        String[] parts = TimerState.fields(state.sessions.get(index));
        promptName("Rename session", parts[0], value -> {
            state.sessions.set(index, TimerState.clean(value) + "|" + parts[1] + "|" + parts[2]);
            save();
            renderSessions();
        });
    }

    private void promptName(String title, String initial, Consumer<String> onSave) {
        View v = getLayoutInflater().inflate(R.layout.dialog_session_name, null);
        ((TextView) v.findViewById(R.id.nameTitle)).setText(title);
        EditText input = v.findViewById(R.id.nameInput);
        input.setText(initial);
        input.setSelection(input.getText().length());
        darkDialog(v, "Save", () -> onSave.accept(input.getText().toString().trim())).show();
    }

    private void promptInterval() {
        View v = getLayoutInflater().inflate(R.layout.dialog_interval, null);
        TextView focusValue = v.findViewById(R.id.focusValue);
        TextView breakValue = v.findViewById(R.id.breakValue);
        // One-element arrays so the steppers can mutate from a lambda.
        final int[] f = {state.customFocus}, b = {state.customBreak};
        focusValue.setText(String.valueOf(f[0]));
        breakValue.setText(String.valueOf(b[0]));

        v.findViewById(R.id.focusMinus).setOnClickListener(x -> {
            f[0] = TimerState.clamp(f[0] - TimerState.FOCUS_STEP,
                    TimerState.FOCUS_FLOOR, TimerState.FOCUS_CEIL);
            focusValue.setText(String.valueOf(f[0]));
        });
        v.findViewById(R.id.focusPlus).setOnClickListener(x -> {
            f[0] = TimerState.clamp(f[0] + TimerState.FOCUS_STEP,
                    TimerState.FOCUS_FLOOR, TimerState.FOCUS_CEIL);
            focusValue.setText(String.valueOf(f[0]));
        });
        v.findViewById(R.id.breakMinus).setOnClickListener(x -> {
            b[0] = TimerState.clamp(b[0] - TimerState.BREAK_STEP,
                    TimerState.BREAK_FLOOR, TimerState.BREAK_CEIL);
            breakValue.setText(String.valueOf(b[0]));
        });
        v.findViewById(R.id.breakPlus).setOnClickListener(x -> {
            b[0] = TimerState.clamp(b[0] + TimerState.BREAK_STEP,
                    TimerState.BREAK_FLOOR, TimerState.BREAK_CEIL);
            breakValue.setText(String.valueOf(b[0]));
        });

        darkDialog(v, "Use these", () -> {
            state.customFocus = f[0];
            state.customBreak = b[0];
            choosePreset(TimerState.CUSTOM);
        }).show();
    }

    /**
     * An AlertDialog on its own opaque ground, so its contrast does not depend
     * on the photo behind the window, with the palette applied to the buttons
     * the platform builds for us.
     */
    private AlertDialog darkDialog(View content, String positive, Runnable onPositive) {
        AlertDialog dlg = new AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(positive, (d, which) -> onPositive.run())
            .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(getDrawable(R.drawable.dialog_bg));
        }
        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.accent));
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.muted));
        });
        // The keyboard and the dialog both pull the system bars back in.
        dlg.setOnDismissListener(d -> hideSystemUi());
        return dlg;
    }

    // -------------------------------------------------------------- rendering

    private void render() {
        boolean timerMode = mode == Mode.TIMER;
        timerPanel.setVisibility(timerMode ? View.VISIBLE : View.GONE);
        stopwatchPanel.setVisibility(timerMode ? View.GONE : View.VISIBLE);
        screenTitle.setText(timerMode ? "Timer" : "Stopwatch");
        // Selection reads as colour, never as alpha: dimming a whole button
        // drags its label's contrast down with it.
        modeTimerBtn.setTextColor(getColor(timerMode ? R.color.accent : R.color.muted));
        modeStopwatchBtn.setTextColor(getColor(timerMode ? R.color.muted : R.color.accent));

        if (timerMode) renderTimer(); else renderStopwatch();
    }

    private void renderTimer() {
        long remaining = state.remaining();
        int tint = getColor(phaseColor(state));

        bigTime.setText(TimerState.countdown(remaining));
        fraction.setVisibility(View.GONE);
        progress.setArcColor(tint);
        progress.setProgress(state.progress());
        phaseLabel.setTextColor(tint);
        phaseLabel.setText(phaseWord(state));

        int focusMin = state.focusMin(), breakMin = state.breakMin();
        phaseSub.setText(state.phase == TimerState.Phase.FOCUS ? "then a " + breakMin + " minute break"
                : state.phase == TimerState.Phase.BREAK ? "then back to focus"
                : focusMin + " minute focus, " + breakMin + " minute break");

        nameChip.setVisibility(View.VISIBLE);
        nameChip.setText(state.sessionName.isEmpty() ? "Name this session" : state.sessionName);

        primaryBtn.setText(state.running ? "Pause"
                : state.phase == TimerState.Phase.IDLE ? "Start focus" : "Resume");
        extraBtn.setText("Log distraction");

        int shown = state.pendingPreset >= 0 ? state.pendingPreset : state.preset;
        presetStandard.setText(TimerState.FIXED[0][0] + " / " + TimerState.FIXED[0][1]
                + "\n" + TimerState.PRESET_NAMES[0]);
        presetDeep.setText(TimerState.FIXED[1][0] + " / " + TimerState.FIXED[1][1]
                + "\n" + TimerState.PRESET_NAMES[1]);
        presetCustom.setText(state.customFocus + " / " + state.customBreak
                + "\n" + TimerState.PRESET_NAMES[TimerState.CUSTOM]);
        presetStandard.setTextColor(getColor(shown == 0 ? R.color.accent : R.color.muted));
        presetDeep.setTextColor(getColor(shown == 1 ? R.color.accent : R.color.muted));
        presetCustom.setTextColor(getColor(
                shown == TimerState.CUSTOM ? R.color.accent : R.color.muted));

        sessionsHeader.setText(state.sessions.isEmpty() ? "SESSIONS TODAY"
                : "SESSIONS TODAY · " + state.sessions.size() + " · TAP TO RENAME");
        distractionHeader.setText(state.distractions.isEmpty() ? "DISTRACTIONS"
                : "DISTRACTIONS · " + state.distractions.size());
        headerHint.setText(state.presetName() + " · " + focusMin + " / " + breakMin);

        StringBuilder status = new StringBuilder();
        status.append(state.presetName()).append(" · ")
              .append(focusMin).append(" min focus, ").append(breakMin).append(" min break");
        if (state.pendingPreset >= 0) {
            status.append(" · ").append(TimerState.PRESET_NAMES[state.pendingPreset])
                  .append(" after this session");
        }
        statusText.setText(status.toString());
    }

    private void renderStopwatch() {
        long elapsed = swElapsed();
        int tint = getColor(swRunning ? R.color.accent : R.color.muted);

        nameChip.setVisibility(View.GONE);
        bigTime.setText(coarse(elapsed));
        if (elapsed < 3_600_000L) {
            fraction.setVisibility(View.VISIBLE);
            fraction.setText(String.format(Locale.US, ".%02d", (elapsed % 1000) / 10));
        } else {
            fraction.setVisibility(View.GONE);
        }
        progress.setArcColor(tint);
        // No end to count toward, so the ring sweeps once a minute.
        progress.setProgress((elapsed % 60_000L) / 60_000f);
        phaseLabel.setTextColor(tint);
        phaseLabel.setText(swRunning ? "RUNNING" : elapsed == 0 ? "READY" : "PAUSED");
        phaseSub.setText(laps.isEmpty() ? "" : laps.size() + (laps.size() == 1 ? " lap" : " laps"));

        primaryBtn.setText(swRunning ? "Pause" : elapsed == 0 ? "Start" : "Resume");
        extraBtn.setText("Lap");

        lapSummary.setText(laps.isEmpty() ? "Tap Lap to record a split"
                : "Fastest " + precise(fastestLap()) + " · slowest " + precise(slowestLap())
                  + (laps.size() >= MAX_LAPS ? " · list full" : ""));
        // Keep a running Pomodoro visible even while the stopwatch is on screen.
        headerHint.setText(state.running
                ? (state.phase == TimerState.Phase.BREAK ? "Break " : "Focus ")
                  + TimerState.countdown(state.remaining())
                : "");
        statusText.setText(laps.isEmpty() ? "Stopwatch ready"
                : laps.size() + (laps.size() == 1 ? " lap" : " laps") + " recorded");
    }

    private void renderSessions() {
        sessionList.removeAllViews();
        for (int i = state.sessions.size() - 1; i >= 0; i--) {
            final int index = i;
            String[] parts = TimerState.fields(state.sessions.get(i));
            View row = getLayoutInflater().inflate(R.layout.row_session, sessionList, false);
            TextView name = row.findViewById(R.id.sessionName);
            boolean unnamed = parts[0].isEmpty();
            name.setText(unnamed ? "Unnamed session" : parts[0]);
            name.setTextColor(getColor(unnamed ? R.color.muted : R.color.text));
            ((TextView) row.findViewById(R.id.sessionMeta))
                    .setText(parts[1] + " min · " + parts[2]);
            row.setOnClickListener(x -> renameSession(index));
            sessionList.addView(row);
        }
        sessionsEmpty.setVisibility(state.sessions.isEmpty() ? View.VISIBLE : View.GONE);
        sessionScroll.post(() -> sessionScroll.scrollTo(0, 0));
    }

    private void renderDistractions() {
        distractionList.removeAllViews();
        for (int i = state.distractions.size() - 1; i >= 0; i--) {
            String[] parts = state.distractions.get(i).split("\\|", 2);
            View row = getLayoutInflater().inflate(R.layout.row_distraction, distractionList, false);
            ((TextView) row.findViewById(R.id.distractionTime)).setText(parts[0]);
            ((TextView) row.findViewById(R.id.distractionWhere)).setText(parts.length > 1 ? parts[1] : "");
            distractionList.addView(row);
        }
        distractionScroll.post(() -> distractionScroll.scrollTo(0, 0));
    }

    private void renderLaps() {
        lapList.removeAllViews();
        for (int i = laps.size() - 1; i >= 0; i--) {
            long total = laps.get(i);
            long split = total - (i == 0 ? 0 : laps.get(i - 1));
            View row = getLayoutInflater().inflate(R.layout.row_lap, lapList, false);
            ((TextView) row.findViewById(R.id.lapIndex)).setText("Lap " + (i + 1));
            ((TextView) row.findViewById(R.id.lapSplit)).setText(precise(split));
            ((TextView) row.findViewById(R.id.lapTotal)).setText(precise(total));
            lapList.addView(row);
        }
        lapScroll.post(() -> lapScroll.scrollTo(0, 0));
    }

    /** Shared with the dashboard card so the two screens tint phases alike. */
    static int phaseColor(TimerState s) {
        if (!s.running && s.phase != TimerState.Phase.IDLE) return R.color.muted;
        return s.phase == TimerState.Phase.FOCUS ? R.color.amber
             : s.phase == TimerState.Phase.BREAK ? R.color.success : R.color.accent;
    }

    static String phaseWord(TimerState s) {
        if (!s.running && s.phase != TimerState.Phase.IDLE) return "PAUSED";
        return s.phase == TimerState.Phase.FOCUS ? "FOCUS"
             : s.phase == TimerState.Phase.BREAK ? "BREAK" : "READY";
    }

    // ----------------------------------------------------------------- helpers

    private long swElapsed() {
        return swAccumulatedMs
                + (swRunning ? SystemClock.elapsedRealtime() - swRunSinceElapsed : 0);
    }

    private long lapSplit(int i) {
        return laps.get(i) - (i == 0 ? 0 : laps.get(i - 1));
    }

    private long fastestLap() {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < laps.size(); i++) best = Math.min(best, lapSplit(i));
        return best;
    }

    private long slowestLap() {
        long worst = 0;
        for (int i = 0; i < laps.size(); i++) worst = Math.max(worst, lapSplit(i));
        return worst;
    }

    /** Elapsed text, rounded down the way a stopwatch counts. */
    private static String coarse(long ms) {
        long secs = Math.max(0, ms) / 1000;
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /** Lap text, to hundredths. */
    private static String precise(long ms) {
        long secs = ms / 1000, cs = (ms % 1000) / 10;
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs);
        return String.format(Locale.US, "%02d:%02d.%02d", m, s, cs);
    }

    // ------------------------------------------------------------ persistence

    private void loadStopwatch() {
        SharedPreferences p = getSharedPreferences(TimerState.PREFS, MODE_PRIVATE);
        mode = "STOPWATCH".equals(p.getString("mode", "TIMER")) ? Mode.STOPWATCH : Mode.TIMER;
        // Stored as a flat total plus the wall time of the save, so a run that
        // was going carries on across a restart or a reboot.
        long savedTotal = p.getLong("swElapsedMs", 0);
        swRunning = p.getBoolean("swRunning", false);
        long savedAt = p.getLong("swSavedAtWall", 0);
        if (swRunning && savedAt > 0) {
            savedTotal += Math.max(0, System.currentTimeMillis() - savedAt);
        }
        swAccumulatedMs = savedTotal;
        swRunSinceElapsed = SystemClock.elapsedRealtime();
        laps.clear();
        for (String s : TimerState.split(p.getString("laps", ""))) {
            try { laps.add(Long.parseLong(s)); } catch (NumberFormatException ignored) {}
        }
    }

    private void save() {
        state.save(this);
        SharedPreferences.Editor e =
                getSharedPreferences(TimerState.PREFS, MODE_PRIVATE).edit();
        e.putString("mode", mode.name());
        e.putBoolean("swRunning", swRunning);
        e.putLong("swElapsedMs", swElapsed());
        e.putLong("swSavedAtWall", System.currentTimeMillis());
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < laps.size(); i++) {
            if (i > 0) b.append('\n');
            b.append(laps.get(i));
        }
        e.putString("laps", b.toString());
        e.apply();
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
}
