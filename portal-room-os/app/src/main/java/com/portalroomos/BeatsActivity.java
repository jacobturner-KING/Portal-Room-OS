package com.portalroomos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A beat machine to play guitar to. Eight tracks: step tracks play a sound on
 * a 16-step grid (the built-in kit, a sound caught from the mic, or a WAV
 * pushed over adb), loop tracks hold takes recorded through the Portal's mics
 * in time with the beat. Each track mutes, solos and has its own volume; the
 * transport has tempo (with tap tempo), loop length, swing and a click.
 *
 * The sound is all in {@link BeatEngine}; this screen edits the
 * {@link BeatSession} it plays and redraws the playhead thirty times a second.
 * Leaving the screen stops the beat and frees the mic, and everything is still
 * here on return, takes included.
 */
public class BeatsActivity extends Activity implements BeatEngine.Listener {

    private static final int MIC_REQUEST = 7;
    private static final long TICK_MS = 33;

    private final Handler handler = new Handler();
    private BeatEngine engine;
    private BeatSession session;
    private float dp;

    private Button playBtn, bpmText, swingBtn, clickBtn;
    private TextView posText, status;
    private final Button[] barBtns = new Button[BeatSession.BAR_CHOICES.length];
    private final Row[] rows = new Row[BeatSession.TRACKS];
    private MicMeter meter;

    // ---- pads ----
    private boolean padsMode;
    private Button stepsBtn, padsBtn, recTapsBtn, undoTapsBtn;
    private final PadView[] pads = new PadView[BeatSession.TRACKS];
    /** Every step track's pattern as it was before the last Rec or Clear, for Undo. */
    private boolean[][] undoSteps, undoAccents;
    private long tapRecSince;
    private final List<Long> taps = new ArrayList<>();
    /** What to do once the mic permission comes back. */
    private Runnable afterMic;

    private static final class Row {
        Button name, mute, solo, rec;
        SeekBar volume;
        StepRowView steps;
        LoopWaveView wave;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_beats);
        hideSystemUi();
        dp = getResources().getDisplayMetrics().density;

        engine = BeatEngine.shared(this);
        session = engine.session;

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        playBtn = findViewById(R.id.playBtn);
        playBtn.setOnClickListener(v -> {
            if (engine.playing) engine.stopTransport(); else engine.play();
        });
        posText = findViewById(R.id.posText);
        status = findViewById(R.id.status);

        bpmText = findViewById(R.id.bpmText);
        bpmText.setOnClickListener(v -> tapTempo());
        Button down = findViewById(R.id.bpmDown), up = findViewById(R.id.bpmUp);
        down.setOnClickListener(v -> nudgeBpm(-1));
        up.setOnClickListener(v -> nudgeBpm(1));
        down.setOnLongClickListener(v -> { nudgeBpm(-5); return true; });
        up.setOnLongClickListener(v -> { nudgeBpm(5); return true; });

        LinearLayout bars = findViewById(R.id.barsGroup);
        for (int i = 0; i < barBtns.length; i++) {
            int n = BeatSession.BAR_CHOICES[i];
            Button b = smallButton(String.valueOf(n));
            b.setOnClickListener(v -> setBars(n));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (46 * dp), LinearLayout.LayoutParams.MATCH_PARENT);
            if (i > 0) lp.setMarginStart((int) (4 * dp));
            bars.addView(b, lp);
            barBtns[i] = b;
        }
        TextView barsLabel = new TextView(this);
        barsLabel.setText("bars");
        barsLabel.setTextColor(getColor(R.color.muted));
        barsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        barsLabel.setPadding((int) (8 * dp), 0, 0, 0);
        bars.addView(barsLabel);

        swingBtn = findViewById(R.id.swingBtn);
        swingBtn.setOnClickListener(v -> {
            session.swing = (session.swing + 1) % BeatSession.SWING.length;
            refreshTransport();
        });
        clickBtn = findViewById(R.id.clickBtn);
        clickBtn.setOnClickListener(v -> {
            session.click = !session.click;
            refreshTransport();
        });
        findViewById(R.id.moreBtn).setOnClickListener(v -> showMore());

        meter = new MicMeter(this);
        ((android.widget.FrameLayout) findViewById(R.id.meterSlot)).addView(meter);
        meter.setOnClickListener(v -> {
            if (!engine.micReady()) withMic(() -> say("Mic open"));
        });

        LinearLayout mode = findViewById(R.id.modeGroup);
        stepsBtn = smallButton("Steps");
        padsBtn = smallButton("Pads");
        stepsBtn.setOnClickListener(v -> setPadsMode(false));
        padsBtn.setOnClickListener(v -> setPadsMode(true));
        mode.addView(stepsBtn, fixed(76, 0));
        mode.addView(padsBtn, fixed(70, 4));

        buildRows();
        buildPads();
        setPadsMode(getSharedPreferences("beats", MODE_PRIVATE).getBoolean("pads_view", true));
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        engine.setListener(this);
        engine.start(hasMic());
        refreshAll();
        warnIfQuiet();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onPause() {
        setTapRec(false);
        handler.removeCallbacks(tick);
        engine.setListener(null);
        engine.stop();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    // ================================================================= rows

    private void buildRows() {
        LinearLayout list = findViewById(R.id.trackList);
        list.removeAllViews();
        for (int i = 0; i < BeatSession.TRACKS; i++) {
            int index = i;
            BeatSession.Track t = session.tracks[i];
            Row row = new Row();
            rows[i] = row;

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setBackgroundResource(R.drawable.panel_flat_bg);
            int pad = (int) (6 * dp);
            line.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
            lineLp.topMargin = (int) (6 * dp);
            list.addView(line, lineLp);

            Button menu = smallButton("⋯");
            menu.setOnClickListener(v -> showTrackMenu(index));
            line.addView(menu, fixed(46, 0));

            row.name = smallButton(t.name);
            row.name.setSingleLine(true);
            row.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.name.setOnClickListener(v -> {
                if (session.tracks[index].loop) showTrackMenu(index);
                else engine.audition(index);
            });
            line.addView(row.name, fixed(132, 6));

            row.mute = smallButton("M");
            row.mute.setOnClickListener(v -> {
                BeatSession.Track tr = session.tracks[index];
                tr.mute = !tr.mute;
                refreshRows();
            });
            line.addView(row.mute, fixed(50, 6));

            row.solo = smallButton("S");
            row.solo.setOnClickListener(v -> {
                BeatSession.Track tr = session.tracks[index];
                tr.solo = !tr.solo;
                refreshRows();
            });
            line.addView(row.solo, fixed(50, 6));

            row.volume = new SeekBar(this);
            row.volume.setMax(100);
            row.volume.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            row.volume.setThumbTintList(ColorStateList.valueOf(getColor(R.color.text)));
            row.volume.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.rule)));
            row.volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (user) session.tracks[index].volume = p;
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            LinearLayout.LayoutParams volLp = fixed(150, 4);
            volLp.height = LinearLayout.LayoutParams.MATCH_PARENT;
            line.addView(row.volume, volLp);

            if (t.loop) {
                row.rec = smallButton("●");
                row.rec.setTextColor(getColor(R.color.alert));
                row.rec.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                row.rec.setOnClickListener(v -> record(index));
                line.addView(row.rec, fixed(64, 6));
                row.wave = new LoopWaveView(this);
                row.wave.bind(engine, index);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lp.setMarginStart((int) (8 * dp));
                line.addView(row.wave, lp);
            } else {
                row.steps = new StepRowView(this);
                row.steps.bind(t, () -> {});
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lp.setMarginStart((int) (10 * dp));
                line.addView(row.steps, lp);
            }
        }
        refreshRows();
    }

    // ================================================================= pads

    private void setPadsMode(boolean on) {
        padsMode = on;
        findViewById(R.id.trackList).setVisibility(on ? View.GONE : View.VISIBLE);
        findViewById(R.id.padArea).setVisibility(on ? View.VISIBLE : View.GONE);
        setOn(padsBtn, on, R.color.accent);
        setOn(stepsBtn, !on, R.color.accent);
        if (!on) {
            setTapRec(false);
            refreshRows();
        }
        getSharedPreferences("beats", MODE_PRIVATE).edit().putBoolean("pads_view", on).apply();
        say(on ? "Tap the pads to play · ● Rec taps to keep them in the loop"
            : "Tap a pad to add a hit · hold it to accent · ● records a loop from the mic");
    }

    /**
     * A pad per step track, as square-ish as the count allows (up to four
     * across, then two rows), plus an "Add pad" slot while an empty loop track
     * is left to turn into one. Under them, the tap-recording controls.
     */
    private void buildPads() {
        LinearLayout area = findViewById(R.id.padArea);
        area.removeAllViews();
        java.util.Arrays.fill(pads, null);
        List<Integer> stepTracks = new ArrayList<>();
        for (int i = 0; i < BeatSession.TRACKS; i++) if (!session.tracks[i].loop) stepTracks.add(i);
        int spare = spareLoopTrack();
        int slots = stepTracks.size() + (spare >= 0 ? 1 : 0);
        int cols = slots <= 4 ? Math.max(1, slots) : (slots + 1) / 2;

        LinearLayout row = null;
        for (int k = 0; k < slots; k++) {
            if (k % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
                rlp.topMargin = (int) (10 * dp);
                area.addView(row, rlp);
            }
            View cell;
            if (k < stepTracks.size()) {
                int t = stepTracks.get(k);
                PadView pad = new PadView(this);
                pad.bind(engine, t, nanos -> engine.hit(t, nanos));
                pad.setOnLongClickListener(v -> { showTrackMenu(t); return true; });
                pads[t] = pad;
                cell = pad;
            } else {
                Button add = smallButton("+ Add a pad");
                add.setTextColor(getColor(R.color.muted));
                add.setOnClickListener(v -> addPad());
                cell = add;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            if (k % cols > 0) lp.setMarginStart((int) (10 * dp));
            row.addView(cell, lp);
        }
        // A short last row keeps its pads the same width as the rows above.
        if (row != null && slots % cols != 0) {
            for (int k = slots % cols; k < cols; k++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lp.setMarginStart((int) (10 * dp));
                row.addView(new View(this), lp);
            }
        }

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setBackgroundResource(R.drawable.bar_bg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (64 * dp));
        slp.topMargin = (int) (10 * dp);
        area.addView(strip, slp);

        recTapsBtn = smallButton("● Rec taps");
        recTapsBtn.setOnClickListener(v -> setTapRec(!engine.tapRec));
        strip.addView(recTapsBtn, fixed(140, 0));
        undoTapsBtn = smallButton("↶ Undo");
        undoTapsBtn.setOnClickListener(v -> undoTaps());
        strip.addView(undoTapsBtn, fixed(96, 8));
        Button clear = smallButton("Clear the pattern");
        clear.setOnClickListener(v -> {
            snapshotSteps();
            for (BeatSession.Track t : session.tracks) {
                if (t.loop) continue;
                java.util.Arrays.fill(t.steps, false);
                java.util.Arrays.fill(t.accents, false);
            }
            session.save();
            say("Pattern cleared · ↶ Undo brings it back");
        });
        strip.addView(clear, fixed(170, 8));
        TextView hint = new TextView(this);
        hint.setText("Tap to play · Rec taps writes them into the loop, on the nearest 16th · hold a pad for its sound");
        hint.setTextColor(getColor(R.color.muted));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hint.setPadding((int) (14 * dp), 0, 0, 0);
        hint.setMaxLines(2);
        strip.addView(hint, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        refreshTapControls();
    }

    /** An empty loop track that could become a pad, or -1. */
    private int spareLoopTrack() {
        for (int i = 0; i < BeatSession.TRACKS; i++) {
            BeatSession.Track t = session.tracks[i];
            if (t.loop && t.audio == null && engine.recTrack != i) return i;
        }
        return -1;
    }

    private void addPad() {
        int i = spareLoopTrack();
        if (i < 0) return;
        changeType(i, false);
        engine.audition(i);
        say("New pad: " + session.tracks[i].name + " · hold it to pick another sound");
    }

    /**
     * Rec taps: the click comes on, and if the beat is stopped it starts after
     * a bar of count-in. The patterns as they were are kept for Undo.
     */
    private void setTapRec(boolean on) {
        if (engine == null || engine.tapRec == on) {
            refreshTapControls();
            return;
        }
        if (on) {
            snapshotSteps();
            engine.tapRec = true;
            tapRecSince = System.currentTimeMillis();
            if (!engine.playing) engine.playWithCountIn();
            say("Recording taps: play the pads in time and they stay in the loop");
        } else {
            engine.tapRec = false;
            session.save();
        }
        refreshTapControls();
    }

    private void refreshTapControls() {
        if (recTapsBtn == null) return;
        boolean on = engine.tapRec;
        recTapsBtn.setText(on ? "■ Stop rec" : "● Rec taps");
        setOn(recTapsBtn, on, R.color.alert);
        if (!on) recTapsBtn.setTextColor(getColor(R.color.alert));
        undoTapsBtn.setEnabled(undoSteps != null);
        undoTapsBtn.setTextColor(getColor(undoSteps != null ? R.color.text : R.color.muted));
    }

    private void snapshotSteps() {
        undoSteps = new boolean[BeatSession.TRACKS][];
        undoAccents = new boolean[BeatSession.TRACKS][];
        for (int i = 0; i < BeatSession.TRACKS; i++) {
            undoSteps[i] = session.tracks[i].steps.clone();
            undoAccents[i] = session.tracks[i].accents.clone();
        }
        refreshTapControls();
    }

    /** Swaps the patterns with the snapshot, so a second Undo is a redo. */
    private void undoTaps() {
        if (undoSteps == null) return;
        boolean[][] s = new boolean[BeatSession.TRACKS][], a = new boolean[BeatSession.TRACKS][];
        for (int i = 0; i < BeatSession.TRACKS; i++) {
            BeatSession.Track t = session.tracks[i];
            s[i] = t.steps.clone();
            a[i] = t.accents.clone();
            System.arraycopy(undoSteps[i], 0, t.steps, 0, BeatSession.STEPS);
            System.arraycopy(undoAccents[i], 0, t.accents, 0, BeatSession.STEPS);
        }
        undoSteps = s;
        undoAccents = a;
        session.save();
        refreshRows();
        say("Undone · ↶ again to put it back");
    }

    private LinearLayout.LayoutParams fixed(int widthDp, int startDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (widthDp * dp), LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMarginStart((int) (startDp * dp));
        return lp;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(getColor(R.color.text));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setBackgroundResource(R.drawable.button_bg);
        b.setPadding((int) (6 * dp), 0, (int) (6 * dp), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        return b;
    }

    private void setOn(Button b, boolean on, int onColor) {
        b.setBackgroundResource(on ? R.drawable.button_on_bg : R.drawable.button_bg);
        b.setTextColor(getColor(on ? onColor : R.color.text));
    }

    private void refreshRows() {
        for (int i = 0; i < BeatSession.TRACKS; i++) {
            BeatSession.Track t = session.tracks[i];
            Row r = rows[i];
            r.name.setText(t.name);
            setOn(r.mute, t.mute, R.color.amber);
            setOn(r.solo, t.solo, R.color.accent);
            r.volume.setProgress(t.volume);
            if (r.steps != null) r.steps.invalidate();
            if (r.wave != null) r.wave.invalidate();
        }
    }

    private void refreshTransport() {
        bpmText.setText(session.bpm + " BPM");
        bpmText.setTextColor(getColor(tempoLocked() ? R.color.muted : R.color.text));
        for (int i = 0; i < barBtns.length; i++) setOn(barBtns[i], session.bars == BeatSession.BAR_CHOICES[i], R.color.accent);
        swingBtn.setText(BeatSession.SWING_NAMES[session.swing]);
        setOn(swingBtn, session.swing > 0, R.color.accent);
        setOn(clickBtn, session.click, R.color.accent);
    }

    private void refreshAll() {
        refreshTransport();
        refreshRows();
        for (PadView p : pads) if (p != null) p.invalidate();
    }

    /** The Portal's volume buttons set the media stream; at the bottom of it the beat is a whisper. */
    private void warnIfQuiet() {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        int v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        if (v * 4 < max) say("Volume is " + v + " of " + max + ": turn it up with the Portal's volume buttons");
    }

    // ============================================================ transport

    private boolean tempoLocked() {
        return session.anyLoopAudio() || engine.rec != BeatEngine.Rec.IDLE;
    }

    private boolean checkTempo() {
        if (!tempoLocked()) return true;
        say(engine.rec != BeatEngine.Rec.IDLE ? "The tempo stays put while a take is recording"
            : "The loops set the tempo. Clear them (⋯ on each loop) to change it.");
        return false;
    }

    private void nudgeBpm(int by) {
        if (!checkTempo()) return;
        int bpm = Math.max(BeatSession.MIN_BPM, Math.min(BeatSession.MAX_BPM, session.bpm + by));
        engine.setBpm(bpm);
        showBpm(bpm);
    }

    /** The engine takes the new tempo between blocks; show it now rather than a frame later. */
    private void showBpm(int bpm) {
        refreshTransport();
        bpmText.setText(bpm + " BPM");
    }

    /** Tap it on the beat: the average of the last few taps, reset after two seconds' pause. */
    private void tapTempo() {
        if (!checkTempo()) return;
        long now = System.currentTimeMillis();
        if (!taps.isEmpty() && now - taps.get(taps.size() - 1) > 2000) taps.clear();
        taps.add(now);
        while (taps.size() > 5) taps.remove(0);
        if (taps.size() < 2) {
            say("Tap tempo: keep tapping on the beat");
            return;
        }
        double gap = (taps.get(taps.size() - 1) - taps.get(0)) / (double) (taps.size() - 1);
        int bpm = (int) Math.round(60000 / gap);
        bpm = Math.max(BeatSession.MIN_BPM, Math.min(BeatSession.MAX_BPM, bpm));
        engine.setBpm(bpm);
        showBpm(bpm);
        say("Tap tempo: " + bpm + " BPM");
    }

    private void setBars(int n) {
        if (engine.rec != BeatEngine.Rec.IDLE) {
            say("The loop length stays put while a take is recording");
            return;
        }
        session.bars = n;
        refreshAll();
        if (session.anyLoopAudio()) say("New takes will be " + n + (n == 1 ? " bar" : " bars") + "; existing loops keep their own length");
    }

    // ================================================================ mic

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Runs `then` with the mic open, asking for it first if need be. */
    private void withMic(Runnable then) {
        if (engine.micReady()) {
            then.run();
            return;
        }
        if (!hasMic()) {
            afterMic = then;
            say("Allow the mic, or once over adb: pm grant com.portalroomos android.permission.RECORD_AUDIO");
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, MIC_REQUEST);
            return;
        }
        engine.openMic();
        if (engine.micReady()) then.run();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != MIC_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            engine.openMic();
            Runnable r = afterMic;
            afterMic = null;
            if (r != null && engine.micReady()) r.run();
        } else {
            say("No mic, so no loops or caught sounds. Step tracks still play.");
        }
    }

    private void record(int t) {
        if (engine.rec != BeatEngine.Rec.IDLE && engine.recTrack == t) {
            engine.record(t);   // cancels
            return;
        }
        withMic(() -> engine.record(t));
    }

    // =============================================================== menus

    private void showTrackMenu(int i) {
        BeatSession.Track t = session.tracks[i];
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (t.loop) {
            items.add("● Record a take");
            actions.add(() -> record(i));
            if (t.undo != null) {
                items.add("↶ Undo the last take");
                actions.add(() -> { engine.undo(i); refreshRows(); });
            }
            if (t.audio != null) {
                items.add("Clear this loop");
                actions.add(() -> { engine.clearLoop(i); refreshAll(); });
            }
            items.add("Make this a step track");
            actions.add(() -> changeType(i, false));
        } else {
            for (BeatSession.Sound s : session.sounds()) {
                items.add((s.ref.equals(t.sound) ? "✓ " : "    ") + s.name);
                actions.add(() -> {
                    if (session.setSound(t, s.ref, s.name)) {
                        engine.audition(i);
                        session.save();
                        refreshRows();
                    } else {
                        say("Could not read " + s.name);
                    }
                });
            }
            items.add("● Catch a sound from the mic");
            actions.add(() -> withMic(() -> engine.catchSound(i)));
            items.add("Clear the steps");
            actions.add(() -> {
                java.util.Arrays.fill(t.steps, false);
                java.util.Arrays.fill(t.accents, false);
                refreshRows();
            });
            items.add("Make this a loop track");
            actions.add(() -> changeType(i, true));
        }
        String title = t.loop ? t.name : t.name + " · pick a sound";
        pick(title, items, actions);
    }

    private void changeType(int i, boolean loop) {
        if (engine.recTrack == i) {
            say("That track is recording");
            return;
        }
        BeatSession.Track t = session.tracks[i];
        if (t.loop == loop) return;
        t.loop = loop;
        if (loop) {
            t.sample = null;
            t.sound = "";
            t.audio = null;
            t.undo = null;
            int n = 1;
            for (BeatSession.Track o : session.tracks) if (o != t && o.loop) n++;
            t.name = "Loop " + n;
        } else {
            t.audio = null;
            t.undo = null;
            session.saveLoop(i);
            java.util.Arrays.fill(t.steps, false);
            java.util.Arrays.fill(t.accents, false);
            String id = unusedKitSound();
            session.setSound(t, "kit:" + id, BeatKit.name(id));
        }
        session.save();
        buildRows();
        buildPads();
        refreshTransport();
    }

    /** The first built-in sound no step track is using yet, so a new pad sounds new. */
    private String unusedKitSound() {
        for (String id : BeatKit.IDS) {
            boolean used = false;
            for (BeatSession.Track t : session.tracks) if (!t.loop && ("kit:" + id).equals(t.sound)) used = true;
            if (!used) return id;
        }
        return "rim";
    }

    private void showMore() {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add("Grooves to start from…");
        actions.add(this::showGrooves);
        items.add((engine.calibrated() ? "✓ " : "") + "Sync takes to the beat: clap along with the click");
        actions.add(() -> withMic(() -> {
            if (engine.playing) engine.stopTransport();
            handler.postDelayed(() -> engine.calibrate(null), 200);
        }));
        int src = engine.micSource();
        String srcName = "";
        for (int i = 0; i < BeatEngine.SOURCES.length; i++) if (BeatEngine.SOURCES[i] == src) srcName = BeatEngine.SOURCE_NAMES[i];
        items.add("Mic: " + ("USB".equals(engine.micName) ? "USB (plugged in)" : srcName) + " ›");
        actions.add(this::showMicSources);
        boolean duck = engine.duck();
        items.add((duck ? "✓ " : "") + "Beat 12 dB quieter while a take records");
        actions.add(() -> {
            engine.setDuck(!duck);
            say(duck ? "The beat stays at full level during takes"
                : "The beat drops 12 dB during takes, so the Portal's echo canceller leaves more of you");
        });
        items.add("Export the loop as a WAV");
        actions.add(engine::export);
        items.add("New beat (clears everything)");
        actions.add(() -> pick("Clear every track and take?", listOf("Yes, start over"), listOf(() -> {
            engine.stopTransport();
            handler.postDelayed(() -> {
                session.reset();
                buildRows();
                refreshAll();
                say("Fresh beat");
            }, 100);
        })));
        pick("Beats", items, actions);
    }

    /**
     * The Portal's built-in mics all go through its voice DSP; these are the
     * paths into it, and they sound different. Record the same phrase on each
     * and keep the one that sounds least muffled. A USB mic overrides this.
     */
    private void showMicSources() {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int cur = engine.micSource();
        for (int i = 0; i < BeatEngine.SOURCES.length; i++) {
            int src = BeatEngine.SOURCES[i];
            String name = BeatEngine.SOURCE_NAMES[i];
            items.add((src == cur ? "✓ " : "") + name);
            actions.add(() -> {
                engine.setMicSource(src);
                say("Mic: " + name + ("USB".equals(engine.micName) ? " (once the USB mic is unplugged)" : ""));
            });
        }
        pick("Built-in mic path (a USB mic overrides this)", items, actions);
    }

    private void showGrooves() {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (BeatSession.Groove g : BeatSession.GROOVES) {
            items.add(g.bpm > 0 ? g.name + " · " + g.bpm + " BPM" : g.name);
            actions.add(() -> {
                session.applyGroove(g);
                boolean tempo = g.bpm > 0 && !tempoLocked();
                if (tempo) engine.setBpm(g.bpm);
                session.save();
                refreshAll();
                if (tempo) showBpm(g.bpm);
                say(g.bpm > 0 && !tempo ? g.name + " (tempo kept: the loops set it)" : g.name);
            });
        }
        pick("Grooves (step tracks only; loops stay)", items, actions);
    }

    private static <T> List<T> listOf(T one) {
        List<T> l = new ArrayList<>();
        l.add(one);
        return l;
    }

    /** A list of big rows on the dark dialog ground; tapping one runs it and closes. */
    private void pick(String title, List<String> items, List<Runnable> actions) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        col.setPadding(pad, pad, pad, (int) (8 * dp));
        TextView head = new TextView(this);
        head.setText(title);
        head.setTextColor(getColor(R.color.text));
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
        head.setPadding(0, 0, 0, (int) (10 * dp));
        col.addView(head);

        AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < items.size(); i++) {
            Runnable action = actions.get(i);
            TextView row = new TextView(this);
            row.setText(items.get(i));
            row.setTextColor(getColor(R.color.text));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight((int) (52 * dp));
            row.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
            row.setBackgroundResource(R.drawable.button_bg);
            row.setOnClickListener(v -> {
                dlg[0].dismiss();
                action.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (6 * dp);
            col.addView(row, lp);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);

        dlg[0] = new AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .create();
        if (dlg[0].getWindow() != null) dlg[0].getWindow().setBackgroundDrawable(getDrawable(R.drawable.dialog_bg));
        dlg[0].setOnShowListener(d -> {
            dlg[0].getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.muted));
            if (dlg[0].getWindow() != null) {
                dlg[0].getWindow().setLayout((int) (560 * dp), WindowManager.LayoutParams.WRAP_CONTENT);
            }
        });
        dlg[0].setOnDismissListener(d -> hideSystemUi());
        dlg[0].show();
    }

    // ========================================================= engine events

    @Override public void onEngineMessage(String text) {
        say(text);
    }

    @Override public void onSessionChanged() {
        refreshAll();
    }

    private void say(String text) {
        status.setText(text);
    }

    // ================================================================ tick

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean playing = engine.playing;
            long heard = playing ? engine.heardPos() : Long.MIN_VALUE;
            int bar = session.barFrames();
            int step = -1;
            if (!playing) {
                posText.setText("");
            } else if (heard < 0) {
                posText.setText("in " + (1 + (int) ((heard + bar) * 4 / bar)));
            } else {
                long inLoop = heard % session.loopFrames();
                posText.setText(String.format(Locale.US, "%d.%d", 1 + inLoop / bar, 1 + (inLoop % bar) * 4 / bar));
                step = (int) ((heard % bar) * BeatSession.STEPS / bar);
            }
            playBtn.setText(playing ? "■ Stop" : "▶ Play");
            // Stopping the beat ends a tap take (after the count-in has had time to start it).
            if (engine.tapRec && !playing && System.currentTimeMillis() - tapRecSince > 500) setTapRec(false);
            if (padsMode) for (PadView p : pads) if (p != null) p.setStep(step);
            for (int i = 0; i < BeatSession.TRACKS; i++) {
                Row r = rows[i];
                if (r.steps != null) r.steps.setPlaying(step);
                if (r.wave != null) r.wave.setHeard(heard);
                if (r.rec != null) {
                    boolean armed = engine.recTrack == i && engine.rec != BeatEngine.Rec.IDLE;
                    r.rec.setText(armed ? "■" : "●");
                    r.rec.setBackgroundResource(armed ? R.drawable.button_on_bg : R.drawable.button_bg);
                }
            }
            meter.invalidate();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /**
     * What the mic hears, as a bar: green above a voice-at-a-metre level, so
     * you can check the guitar is reaching it before you record. Tap it to open
     * the mic if it is closed.
     */
    private final class MicMeter extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        MicMeter(Context c) {
            super(c);
            p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics()));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            boolean open = engine.micReady();
            p.setColor(getColor(R.color.muted));
            String label = !open ? "MIC OFF · tap" : "USB".equals(engine.micName) ? "USB MIC" : "MIC";
            float tw = p.measureText(label);
            c.drawText(label, 0, h / 2 + p.getTextSize() / 3, p);
            if (!open) return;
            float left = tw + 10 * dp, top = h / 2 - 7 * dp, bottom = h / 2 + 7 * dp;
            r.set(left, top, w, bottom);
            p.setColor(getColor(R.color.button));
            c.drawRoundRect(r, 7 * dp, 7 * dp, p);
            float level = engine.micLevel;
            double db = 20 * Math.log10(Math.max(level, 1e-5));
            float frac = (float) Math.max(0, Math.min(1, (db + 60) / 60));
            r.set(left, top, left + (w - left) * frac, bottom);
            p.setColor(getColor(db > -40 ? (db > -3 ? R.color.alert : R.color.success) : R.color.muted));
            c.drawRoundRect(r, 7 * dp, 7 * dp, p);
        }
    }
}
