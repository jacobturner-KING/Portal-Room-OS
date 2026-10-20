package com.portalroomos;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The beat machine's sound: one thread that mixes every track into the
 * Portal's speaker block by block, and one that keeps the last minute of the
 * microphone in a ring, so a take can be cut out of it after the fact.
 *
 * Time is counted in frames. The output thread owns `written`, the frames sent
 * to the speaker since it opened, and the transport position `pos` is that
 * minus `origin`, so bar 1 beat 1 is position 0 and a count-in runs negative.
 * Steps fire at exact frames inside a block, so the groove does not wobble
 * with the block size.
 *
 * Lining a take up with the beat is the hard part. What matters is not when a
 * frame was written but when it came out of the speaker, and which microphone
 * frame was captured at that instant. Both streams report that against the
 * same monotonic clock (`getTimestamp`), so a written frame maps to a mic frame
 * through the clock. What the clocks cannot see is measured by clapping along
 * with the click (Sync), and kept in preferences.
 *
 * Every built-in mic path on the Portal is one channel out of Facebook's
 * far-field voice DSP, echo canceller always on (measured 2026-10-19 with
 * MicProbe: every source, format and channel layout; multi-channel layouts are
 * that one channel copied). So a take holds no clean copy of the beat, the
 * sync cannot listen for its own clicks, and while the beat is loud the
 * canceller chews whatever else it hears: that is the muffled, gated sound.
 * Two things help: the beat drops 12 dB while a take records (`duck`), and a
 * USB mic or interface, which skips that DSP, is used whenever one is plugged
 * in. Which built-in path is used is a setting; VOICE_RECOGNITION is the one
 * Android requires to be flat with no gain riding or noise reduction.
 *
 * UI calls are queued and run on the output thread between blocks, so the
 * mixer never needs a lock.
 */
final class BeatEngine {

    private static final String TAG = "Beats";
    /** Mic read size. */
    private static final int BLOCK = 256;
    /**
     * Output block: the HAL's own period (192 frames, 4 ms, on the Portal),
     * so a pad tapped now is in the next one. The track holds four of them.
     */
    private final int outBlock;
    private static final int OUT_BLOCKS_BUFFERED = 4;
    /** Seconds of microphone kept: the longest loop (8 bars at 50 BPM is 38 s) plus room to spare. */
    private static final int RING_SECONDS = 48;
    /** Recorded past the end of a take and crossfaded into its start, so a ringing chord crosses the seam. */
    private static final double SEAM_SECONDS = 0.04;
    private static final int VOICES = 32;
    private static final String PREFS = "beats";

    interface Listener {
        /** On the main thread. */
        void onEngineMessage(String text);
        /** On the main thread: a take landed, a sound was recorded, anything that changes rows. */
        void onSessionChanged();
    }

    enum Rec { IDLE, WAITING, RECORDING, FINISHING }

    private static BeatEngine instance;

    static synchronized BeatEngine shared(Context c) {
        if (instance == null) instance = new BeatEngine(c.getApplicationContext());
        return instance;
    }

    final BeatSession session;
    final int sr;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Listener listener;

    // ---- output thread state ----
    private Thread out;
    private volatile boolean running;
    private AudioTrack track;
    private volatile long written;
    private long origin;
    private long pos;
    private long nextStep;
    private final Voice[] voices = new Voice[VOICES];
    /** The beat's level, eased toward 1 or toward DUCK while a take records. */
    private float beatGain = 1f;
    private volatile boolean duckOn;
    private static final float DUCK = 0.25f;   // -12 dB
    private final float[] clickHi, clickLo;

    // ---- read by the UI ----
    volatile boolean playing;
    /** Recording pad taps into the pattern: the click plays while it is on. */
    volatile boolean tapRec;
    /** When each track's last hit leaves the speaker, System.nanoTime(), for the pads to flash. */
    final long[] flashNs = new long[BeatSession.TRACKS];
    /** A step a live tap has just written and already played: skip it once so it does not sound twice. */
    private final long[] skipStep = new long[BeatSession.TRACKS];
    volatile Rec rec = Rec.IDLE;
    volatile int recTrack = -1;
    volatile long recStart, recLength;
    /** 0..1, the loudest the mic heard in the last block. */
    volatile float micLevel;
    volatile boolean micOpen;
    /** "USB" or "built-in", for the meter. */
    volatile String micName = "";
    volatile boolean busy;   // calibrating or catching a sound

    // ---- the two clocks, sampled about every quarter second ----
    private final Object clockLock = new Object();
    private long outFrame, outNs, inFrame, inNs;
    private boolean outTs, inTs, outKnown, inKnown;

    // ---- microphone ----
    private Thread micThread;
    private volatile boolean micRunning;
    private AudioRecord mic;
    private final float[] ring;
    private volatile long micTotal;

    private static final class Voice {
        float[] data;
        int at;
        long delay;
        float gain;
        int trackIndex;   // -1: not a track (click, calibration); plays at its own gain
        boolean ignoreMute;   // an audition: heard even on a muted track
        int release;      // frames left of a fade-out once choked, 0 when not choked
    }

    private BeatEngine(Context c) {
        app = c;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int rate = 48000;
        try {
            rate = Integer.parseInt(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
        } catch (Exception ignored) {}
        sr = rate;
        int period = 192;
        try {
            period = Integer.parseInt(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
        } catch (Exception ignored) {}
        outBlock = Math.max(96, Math.min(512, period));
        session = new BeatSession(c, sr);
        ring = new float[RING_SECONDS * sr];
        clickHi = BeatKit.click(sr, true);
        clickLo = BeatKit.click(sr, false);
        for (int i = 0; i < VOICES; i++) voices[i] = new Voice();
        Arrays.fill(skipStep, Long.MIN_VALUE);
    }

    void setListener(Listener l) {
        listener = l;
    }

    private void say(String text) {
        Log.i(TAG, text);
        main.post(() -> { if (listener != null) listener.onEngineMessage(text); });
    }

    private void changed() {
        main.post(() -> { if (listener != null) listener.onSessionChanged(); });
    }

    // ================================================================ lifecycle

    /** Opens the speaker, and the mic if allowed. Called when the screen comes to the front. */
    synchronized void start(boolean withMic) {
        if (running) return;
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        // Pads are played by hand, so the buffer is as short as the device will
        // run clean: four HAL periods, 16 ms at 48 kHz, on the fast mixer.
        int bytes = Math.max(min, outBlock * OUT_BLOCKS_BUFFERED * 4);
        track = new AudioTrack.Builder()
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
        // The platform's minimum is about 40 ms; on the fast mixer the part in
        // play can be trimmed to a few periods, which is what a finger feels.
        int frames = track.setBufferSizeInFrames(outBlock * OUT_BLOCKS_BUFFERED);
        written = 0;
        commands.clear();
        outKnown = false;
        running = true;
        track.play();
        out = new Thread(this::outputLoop, "beats-out");
        out.start();
        duckOn = duck();
        ((AudioManager) app.getSystemService(Context.AUDIO_SERVICE)).registerAudioDeviceCallback(usbWatch, main);
        if (withMic) openMic();
        Log.i(TAG, "engine up at " + sr + " Hz, block " + outBlock + ", buffer " + frames + " of " + bytes / 4
            + " frames, fast " + (track.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) + ", mic " + micOpen);
    }

    /** Stops the transport and lets go of the speaker and the mic. Called when the screen goes. */
    synchronized void stop() {
        if (!running) return;
        running = false;
        ((AudioManager) app.getSystemService(Context.AUDIO_SERVICE)).unregisterAudioDeviceCallback(usbWatch);
        try { out.join(500); } catch (InterruptedException ignored) {}
        track.release();
        track = null;
        playing = false;
        if (rec != Rec.FINISHING) clearRec();
        closeMic();
        for (Voice v : voices) v.data = null;
        session.save();
    }

    boolean micReady() {
        return micOpen;
    }

    /** Built-in capture paths, in the order the setting offers them. */
    static final int[] SOURCES = {
        MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC,
    };
    static final String[] SOURCE_NAMES = {
        "Voice recognition: flat, no gain riding", "Camcorder: the back mic",
        "Unprocessed", "Standard mic",
    };

    int micSource() {
        return prefs().getInt("mic_source", MediaRecorder.AudioSource.VOICE_RECOGNITION);
    }

    /** Switches the built-in path, reopening the mic if it is open and no take is running. */
    void setMicSource(int source) {
        prefs().edit().putInt("mic_source", source).apply();
        reopenMic();
    }

    boolean duck() {
        return prefs().getBoolean("duck", true);
    }

    void setDuck(boolean on) {
        prefs().edit().putBoolean("duck", on).apply();
        duckOn = on;
    }

    private void reopenMic() {
        if (!micRunning || rec != Rec.IDLE || busy) return;
        closeMic();
        openMic();
    }

    /** A USB mic or interface if one is plugged in: it bypasses the Portal's voice DSP. */
    private AudioDeviceInfo usbInput() {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET) return d;
        }
        return null;
    }

    /** Reopens the mic when a USB input comes or goes, so plugging one in just works. */
    private final AudioDeviceCallback usbWatch = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { changedInputs(added); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { changedInputs(removed); }
    };

    private void changedInputs(AudioDeviceInfo[] devices) {
        for (AudioDeviceInfo d : devices) {
            int t = d.getType();
            if (d.isSource() && (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET)) {
                reopenMic();
                say(micOpen ? "Mic: " + micName : "Mic closed");
                return;
            }
        }
    }

    synchronized void openMic() {
        if (micRunning) return;
        if (RecorderService.running()) {
            say("The room recorder has the mic. Stop it to record loops.");
            return;
        }
        int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            say("The mic does not run at " + sr + " Hz on this device");
            return;
        }
        try {
            int bytes = Math.max(min * 2, sr / 5 * 2);
            AudioDeviceInfo usb = usbInput();
            // On USB the source only picks a preset; UNPROCESSED asks for no processing at all.
            int source = usb != null ? MediaRecorder.AudioSource.UNPROCESSED : micSource();
            mic = openSource(source, bytes);
            if (mic == null) mic = openSource(MediaRecorder.AudioSource.MIC, bytes);
            if (mic == null) throw new IllegalStateException("no source would open");
            if (usb != null) mic.setPreferredDevice(usb);
            mic.startRecording();
            AudioDeviceInfo routed = mic.getRoutedDevice();
            boolean onUsb = routed != null && (routed.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                || routed.getType() == AudioDeviceInfo.TYPE_USB_HEADSET);
            micName = onUsb ? "USB" : "built-in";
            micKey = (onUsb ? "usb" : "s" + mic.getAudioSource());
            Log.i(TAG, "mic source " + mic.getAudioSource() + " on " + micName
                + (routed == null ? "" : " (" + routed.getProductName() + ")"));
        } catch (Exception e) {
            Log.w(TAG, "mic unavailable", e);
            if (mic != null) mic.release();
            mic = null;
            say("Mic unavailable: " + e.getMessage());
            return;
        }
        micTotal = 0;
        inKnown = false;
        micRunning = true;
        micOpen = true;
        micThread = new Thread(this::micLoop, "beats-mic");
        micThread.start();
    }

    private synchronized void closeMic() {
        if (!micRunning) return;
        micRunning = false;
        try { micThread.join(500); } catch (InterruptedException ignored) {}
        mic.stop();
        mic.release();
        mic = null;
        micOpen = false;
        micLevel = 0;
    }

    /** Which input the sync offset belongs to: each path has its own delay. */
    private volatile String micKey = "";

    /**
     * Null if the source is refused. The Portal opens UNPROCESSED although
     * PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED says it does not have it.
     */
    private AudioRecord openSource(int source, int bytes) {
        try {
            AudioRecord r = new AudioRecord(source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes);
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Exception e) {
            Log.i(TAG, "mic source " + source + " refused: " + e.getMessage());
        }
        return null;
    }

    // ============================================================ UI commands

    void play() {
        commands.add(() -> startTransport(false));
    }

    void stopTransport() {
        commands.add(() -> {
            playing = false;
            if (rec == Rec.WAITING || rec == Rec.RECORDING) {
                clearRec();
                say("Take cancelled");
            }
            for (Voice v : voices) if (v.data != null && v.trackIndex >= 0) choke(v);
        });
    }

    /** Plays a track's sound now, at its volume, muted or not. */
    void audition(int t) {
        commands.add(() -> {
            BeatSession.Track tr = session.tracks[t];
            if (tr.sample != null) trigger(tr.sample, 1f, t, 0, true);
        });
    }

    /**
     * A pad. Plays the track's sound in the next block, and if taps are being
     * recorded and the beat is running, writes it into the pattern at the
     * sixteenth nearest to when the finger landed (`touchNanos`, the touch
     * event's own time, so the UI thread's lag does not count against you).
     * A tap a little ahead of its step has already sounded, so that step is
     * skipped once rather than played twice.
     */
    void hit(int t, long touchNanos) {
        commands.add(() -> {
            BeatSession.Track tr = session.tracks[t];
            if (tr.loop || tr.sample == null) return;
            trigger(tr.sample, BeatSession.NORMAL_HIT + 0.15f, t, 0, true);
            flashNs[t] = heardNanos(written);
            if (!tapRec || !playing) return;
            long p = heardPosAt(touchNanos);
            if (p < -session.barFrames() / 32) return;   // in the count-in
            double stepLen = session.barFrames() / (double) BeatSession.STEPS;
            double swing = BeatSession.SWING[session.swing] * stepLen;
            long base = (long) Math.floor(p / stepLen), best = base;
            double bestErr = Double.MAX_VALUE;
            for (long k = base - 1; k <= base + 1; k++) {
                double at = Math.floor(k * stepLen) + ((k & 1) != 0 ? swing : 0);
                double err = Math.abs(at - p);
                if (err < bestErr) { bestErr = err; best = k; }
            }
            int idx = (int) Math.floorMod(best, (long) BeatSession.STEPS);
            tr.steps[idx] = true;
            if (best >= nextStep) skipStep[t] = best;
        });
    }

    /** Starts the beat after a bar of count-in clicks. */
    void playWithCountIn() {
        commands.add(() -> startTransport(true));
    }

    /**
     * Tempo change. Made on the output thread, between blocks, so the step
     * clock and the position change together and a running beat keeps its place.
     */
    void setBpm(int bpm) {
        int to = Math.max(BeatSession.MIN_BPM, Math.min(BeatSession.MAX_BPM, bpm));
        if (!running) {
            session.bpm = to;
            return;
        }
        commands.add(() -> {
            int before = session.barFrames();
            session.bpm = to;
            if (playing) {
                pos = Math.round(pos * (double) session.barFrames() / before);
                origin = written - pos;
                nextStep = (long) Math.ceil(pos * BeatSession.STEPS / (double) session.barFrames());
            }
        });
    }

    /**
     * Arms a loop track. Stopped: a bar of count-in, then the take. Playing: the
     * take starts on the bar you are in if the tap came within a sixteenth of its
     * downbeat (the mic already has it), otherwise on the next one. Tapping the
     * armed track again cancels.
     */
    void record(int t) {
        commands.add(() -> {
            if (rec != Rec.IDLE) {
                if (recTrack == t && rec != Rec.FINISHING) {
                    clearRec();
                    say("Take cancelled");
                } else {
                    say("One take at a time");
                }
                return;
            }
            if (!micOpen) {
                say("The mic is not open, so there is nothing to record");
                return;
            }
            long bar = session.barFrames();
            long start;
            if (!playing) {
                startTransport(true);
                start = 0;
            } else {
                long heard = heardPos();
                long floor = Math.floorDiv(heard, bar) * bar;
                start = heard - floor < bar / 16 ? floor : floor + bar;
                start = Math.max(start, 0);
            }
            recTrack = t;
            recStart = start;
            recLength = session.loopFrames();
            rec = pos >= start ? Rec.RECORDING : Rec.WAITING;
            say(playing && start > 0 ? "Recording " + session.tracks[t].name + " from the next bar"
                : "Recording " + session.tracks[t].name + " after the count-in");
        });
    }

    private void startTransport(boolean countIn) {
        if (playing) return;
        pos = countIn ? -session.barFrames() : 0;
        nextStep = countIn ? -BeatSession.STEPS : 0;
        origin = written - pos;
        Arrays.fill(skipStep, Long.MIN_VALUE);
        playing = true;
    }

    private void clearRec() {
        rec = Rec.IDLE;
        recTrack = -1;
    }

    // ================================================================ mixing

    private void outputLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int block = outBlock;
        float[] mix = new float[block];
        int blocks = 0;
        AudioTimestamp ts = new AudioTimestamp();
        while (running) {
            Runnable r;
            while ((r = commands.poll()) != null) {
                try { r.run(); } catch (Exception e) { Log.w(TAG, "command failed", e); }
            }
            Arrays.fill(mix, 0f);
            render(mix, block);
            for (int i = 0; i < block; i++) mix[i] = soft(mix[i]);
            int n = track.write(mix, 0, block, AudioTrack.WRITE_BLOCKING);
            if (n < 0) {
                Log.w(TAG, "speaker write failed: " + n);
                break;
            }
            written += block;
            if (++blocks % 24 == 0) sampleOutputClock(ts);
        }
    }

    /** Above about -6 dBFS, round the peaks off rather than clip them: layers add up. */
    private static float soft(float x) {
        float a = Math.abs(x);
        if (a < 0.5f) return x;
        float y = 0.5f + 0.5f * (float) Math.tanh((a - 0.5f) / 0.5f);
        return Math.copySign(y, x);
    }

    private void render(float[] mix, int n) {
        BeatSession s = session;
        // Ease over ~25 ms blocks rather than jump, so the duck does not click.
        float target = duckOn && rec == Rec.RECORDING ? DUCK : 1f;
        float duck = beatGain = beatGain + (target - beatGain) * 0.3f;
        if (playing) {
            double stepLen = s.barFrames() / (double) BeatSession.STEPS;
            double swing = BeatSession.SWING[s.swing] * stepLen;
            while (true) {
                long at = (long) Math.floor(nextStep * stepLen) + ((nextStep & 1) != 0 ? (long) swing : 0);
                if (at >= pos + n) break;
                step(nextStep, Math.max(0, at - pos));
                nextStep++;
            }
            for (int t = 0; t < BeatSession.TRACKS; t++) {
                BeatSession.Track tr = s.tracks[t];
                float[] a = tr.audio;
                if (!tr.loop || a == null || a.length == 0 || !s.audible(tr)) continue;
                if (t == recTrack && rec != Rec.IDLE) continue;   // a track being re-recorded goes quiet
                float g = tr.gain() * duck;
                int from = (int) Math.max(0, -pos);
                int len = a.length;
                int j = (int) Math.floorMod(pos + from, (long) len);
                for (int i = from; i < n; i++) {
                    mix[i] += a[j] * g;
                    if (++j == len) j = 0;
                }
            }
            pos += n;
            if (rec == Rec.WAITING && pos >= recStart) rec = Rec.RECORDING;
            if (rec == Rec.RECORDING && pos >= recStart + recLength + (long) (SEAM_SECONDS * sr)) {
                rec = Rec.FINISHING;
                long startE = recStart + origin;
                int t = recTrack;
                int len = (int) recLength;
                // The take began on some bar; this is where in the loop that bar sits.
                int phase = (int) Math.floorMod(recStart, (long) len);
                worker.execute(() -> finishTake(t, startE, len, phase));
            }
        }
        for (Voice v : voices) {
            if (v.data == null) continue;
            int i = 0;
            if (v.delay > 0) {
                if (v.delay >= n) {
                    v.delay -= n;
                    continue;
                }
                i = (int) v.delay;
                v.delay = 0;
            }
            float g = v.gain;
            if (v.trackIndex >= 0) {
                BeatSession.Track tr = s.tracks[v.trackIndex];
                g *= tr.gain() * duck;
                if (!v.ignoreMute && !s.audible(tr)) g = 0;   // muted mid-hit: cut it
            }
            float[] d = v.data;
            for (; i < n && v.at < d.length; i++, v.at++) {
                float x = d[v.at] * g;
                if (v.release > 0) {
                    x *= v.release / 64f;
                    if (--v.release == 0) { v.at = d.length; break; }
                }
                mix[i] += x;
            }
            if (v.at >= d.length) v.data = null;
        }
    }

    private void step(long k, long offset) {
        BeatSession s = session;
        if (Math.floorMod(k, 4) == 0 && (k < 0 || s.click || tapRec)) {
            trigger(Math.floorMod(k, 16) == 0 ? clickHi : clickLo, k < 0 ? 0.9f : 0.6f, -1, offset, false);
        }
        if (k < 0) return;
        int idx = (int) (k % BeatSession.STEPS);
        for (int t = 0; t < BeatSession.TRACKS; t++) {
            BeatSession.Track tr = s.tracks[t];
            if (tr.loop || !tr.steps[idx] || tr.sample == null || !s.audible(tr)) continue;
            if (skipStep[t] == k) continue;
            trigger(tr.sample, tr.accents[idx] ? 1f : BeatSession.NORMAL_HIT, t, offset, false);
            flashNs[t] = heardNanos(written + offset);
        }
    }

    /**
     * A track plays one hit at a time: a new one chokes the last over 64 frames,
     * the way a real hat closes, and stops a fast pattern piling up.
     */
    private void trigger(float[] data, float gain, int t, long delay, boolean audition) {
        if (t >= 0) {
            for (Voice v : voices) if (v.data != null && v.trackIndex == t && v.release == 0 && v.delay <= delay) choke(v);
        }
        Voice free = null;
        for (Voice v : voices) if (v.data == null) { free = v; break; }
        if (free == null) return;
        free.data = data;
        free.at = 0;
        free.delay = delay;
        free.gain = gain;
        free.ignoreMute = audition;
        free.trackIndex = t;
        free.release = 0;
    }

    private static void choke(Voice v) {
        if (v.delay > 0) v.data = null;
        else v.release = 64;
    }

    // ================================================================ clocks

    private void sampleOutputClock(AudioTimestamp ts) {
        AudioTrack t = track;
        if (t == null) return;
        boolean ok = t.getTimestamp(ts);
        synchronized (clockLock) {
            if (ok) {
                outFrame = ts.framePosition;
                outNs = ts.nanoTime;
                outTs = true;
            } else {
                // No presentation time: the playhead is what the mixer has taken,
                // a fixed distance from the speaker that calibration absorbs.
                outFrame = t.getPlaybackHeadPosition() & 0xffffffffL;
                outNs = System.nanoTime();
                outTs = false;
            }
            outKnown = true;
        }
    }

    private void sampleInputClock(AudioTimestamp ts, long total) {
        boolean ok = mic.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS;
        synchronized (clockLock) {
            if (ok) {
                inFrame = ts.framePosition;
                inNs = ts.nanoTime;
                inTs = true;
            } else {
                inFrame = total;
                inNs = System.nanoTime();
                inTs = false;
            }
            inKnown = true;
        }
    }

    /** The transport position that was coming out of the speaker at `nanos` (System.nanoTime). */
    private long heardPosAt(long nanos) {
        synchronized (clockLock) {
            if (!outKnown) return pos;
            return outFrame + (nanos - outNs) * sr / 1_000_000_000L - origin;
        }
    }

    /** When output frame `e` leaves the speaker, in System.nanoTime. */
    private long heardNanos(long e) {
        synchronized (clockLock) {
            if (!outKnown) return System.nanoTime();
            return outNs + (e - outFrame) * 1_000_000_000L / sr;
        }
    }

    /** The transport position coming out of the speaker now: what the playhead on screen should show. */
    long heardPos() {
        synchronized (clockLock) {
            if (!outKnown) return pos;
            long e = outFrame + (System.nanoTime() - outNs) * sr / 1_000_000_000L;
            return Math.min(e - origin, pos);
        }
    }

    /** The mic frame captured as output frame `e` left the speaker, by the clocks alone. Long.MIN_VALUE if unknown. */
    private long micFrameFor(long e) {
        synchronized (clockLock) {
            if (!outKnown || !inKnown) return Long.MIN_VALUE;
            double t = outNs + (e - outFrame) * 1e9 / sr;
            return inFrame + Math.round((t - inNs) * sr / 1e9);
        }
    }

    private String clockKey() {
        synchronized (clockLock) {
            return "offset_" + (outTs ? "t" : "h") + (inTs ? "t" : "h") + "_" + sr + "_" + micKey;
        }
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean calibrated() {
        return prefs().contains(clockKey());
    }

    // ============================================================ microphone

    private void micLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] buf = new short[BLOCK];
        AudioTimestamp ts = new AudioTimestamp();
        int blocks = 0;
        int len = ring.length;
        while (micRunning) {
            int n = mic.read(buf, 0, BLOCK);
            if (n <= 0) {
                Log.w(TAG, "mic read failed: " + n);
                break;
            }
            long total = micTotal;
            float peak = 0;
            int j = (int) (total % len);
            for (int i = 0; i < n; i++) {
                float x = buf[i] / 32768f;
                ring[j] = x;
                if (++j == len) j = 0;
                peak = Math.max(peak, Math.abs(x));
            }
            micTotal = total + n;
            micLevel = Math.max(peak, micLevel * 0.9f);
            if (++blocks % 32 == 1) sampleInputClock(ts, micTotal);
        }
    }

    /** Copies mic frames [from, from+n) out of the ring; null if they have already been overwritten. */
    private float[] micCopy(long from, int n) {
        long total = micTotal;
        if (from < 0 || from < total - ring.length || from + n > total) return null;
        float[] outBuf = new float[n];
        int len = ring.length;
        int j = (int) (from % len);
        for (int i = 0; i < n; i++) {
            outBuf[i] = ring[j];
            if (++j == len) j = 0;
        }
        return outBuf;
    }

    private boolean waitForMic(long until, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (micTotal < until) {
            if (System.currentTimeMillis() > deadline || !micRunning) return false;
            try { Thread.sleep(20); } catch (InterruptedException e) { return false; }
        }
        return true;
    }

    // ================================================================ takes

    private void finishTake(int t, long startE, int len, int phase) {
        try {
            long m = micFrameFor(startE);
            if (m == Long.MIN_VALUE) throw new IOException("the mic clock never settled");
            m += prefs().getLong(clockKey(), 0);
            int seam = (int) (SEAM_SECONDS * sr);
            if (!waitForMic(m + len + seam, 3000)) throw new IOException("the mic stopped");
            float[] raw = micCopy(m, len + seam);
            if (raw == null) throw new IOException("the mic fell behind");

            // Crossfade what was played just past the end into the start.
            float[] take = Arrays.copyOf(raw, len);
            for (int i = 0; i < seam; i++) {
                float f = (float) i / seam;
                take[i] = take[i] * f + raw[len + i] * (1 - f);
            }
            float gain = normalise(take, 0.7f, 8f);

            // Store it so index i is loop position i.
            float[] loop = new float[len];
            for (int i = 0; i < len; i++) loop[(phase + i) % len] = take[i];

            BeatSession.Track tr = session.tracks[t];
            tr.undo = tr.audio;
            tr.audio = loop;
            session.saveLoop(t);
            session.save();
            int bars = Math.round((float) len / session.barFrames());
            say(String.format(Locale.US, "%s recorded: %d bar%s, +%.0f dB%s", tr.name,
                bars, bars == 1 ? "" : "s", 20 * Math.log10(gain), calibrated() ? "" : " · ⋯ → Sync to tighten timing"));
            changed();
        } catch (Exception e) {
            Log.w(TAG, "take lost", e);
            say("Take lost: " + e.getMessage());
        } finally {
            commands.add(this::clearRec);
            if (!running) clearRec();
        }
    }

    /** Brings the peak up to `target`, by at most `maxGain`; the room mic is quiet. Returns the gain used. */
    private static float normalise(float[] a, float target, float maxGain) {
        float peak = 0;
        for (float x : a) peak = Math.max(peak, Math.abs(x));
        if (peak <= 0) return 1;
        float g = Math.min(maxGain, target / peak);
        for (int i = 0; i < a.length; i++) a[i] *= g;
        return g;
    }

    void undo(int t) {
        BeatSession.Track tr = session.tracks[t];
        if (tr.undo == null) return;
        float[] cur = tr.audio;
        tr.audio = tr.undo;
        tr.undo = cur;
        session.saveLoop(t);
    }

    void clearLoop(int t) {
        BeatSession.Track tr = session.tracks[t];
        if (tr.audio != null) tr.undo = tr.audio;
        tr.audio = null;
        session.saveLoop(t);
    }

    // ======================================================= catch a sound

    /**
     * Listens for the next sound loud enough to be a hit, keeps it from just
     * before its onset until it dies away (a second and a half at most), and
     * makes it this step track's sound, saved for every other track to use too.
     */
    void catchSound(int t) {
        if (!micOpen) {
            say("The mic is not open");
            return;
        }
        if (busy) return;
        if (playing) {
            say("Stop the beat first, or the mic will catch a drum");
            return;
        }
        busy = true;
        say("Listening… make the sound now");
        worker.execute(() -> {
            try {
                long start = micTotal;
                float floor = rms(start - sr / 4, sr / 4);
                float threshold = Math.max(0.03f, floor * 8);
                long onset = -1;
                long scanned = start;
                long deadline = System.currentTimeMillis() + 10_000;
                while (onset < 0) {
                    if (System.currentTimeMillis() > deadline) throw new IOException("heard nothing for ten seconds");
                    long total = micTotal;
                    float[] chunk = micCopy(scanned, (int) (total - scanned));
                    if (chunk != null) {
                        for (int i = 0; i < chunk.length; i++) {
                            if (Math.abs(chunk[i]) > threshold) { onset = scanned + i; break; }
                        }
                    }
                    scanned = total;
                    if (onset < 0) Thread.sleep(20);
                }
                long from = onset - sr / 200;   // 5 ms before, so the attack is whole
                int max = sr * 3 / 2;
                if (!waitForMic(from + max, 3000)) throw new IOException("the mic stopped");
                float[] s = micCopy(from, max);
                if (s == null) throw new IOException("the mic fell behind");
                // End where it has been quiet for 150 ms.
                int end = s.length, quiet = 0, hold = sr * 3 / 20;
                float tail = Math.max(floor * 3, threshold / 4);
                for (int i = sr / 20; i < s.length; i++) {
                    quiet = Math.abs(s[i]) < tail ? quiet + 1 : 0;
                    if (quiet >= hold) { end = i - hold + sr / 50; break; }
                }
                s = Arrays.copyOf(s, Math.max(sr / 20, end));
                int fade = Math.min(s.length / 4, sr / 50);
                for (int i = 0; i < fade; i++) s[s.length - 1 - i] *= (float) i / fade;
                normalise(s, 0.9f, 30f);

                File f = session.newSoundFile();
                WavFile.write(f, s, sr);
                String name = f.getName().substring(0, f.getName().length() - 4);
                BeatSession.Track tr = session.tracks[t];
                session.setSound(tr, f.getAbsolutePath(), name);
                session.save();
                say(String.format(Locale.US, "Got it: %s (%.2f s)", name, (double) s.length / sr));
                changed();
                audition(t);
            } catch (Exception e) {
                say("No sound caught: " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    private float rms(long from, int n) {
        float[] a = micCopy(Math.max(0, from), n);
        if (a == null) return 0.002f;
        double sum = 0;
        for (float x : a) sum += x * x;
        return (float) Math.sqrt(sum / Math.max(1, a.length));
    }

    // ================================================================= sync

    /** Clicks in a sync: the first four count you in, the rest you clap on. */
    private static final int SYNC_CLICKS = 12, SYNC_LEAD = 4;

    /**
     * Clap along: twelve clicks at 100 BPM, clap on each from the fifth. The
     * Portal's mics cannot hear its own speaker, so this listens for the claps
     * instead, and stores the median gap between where the clocks put each
     * click and where the clap landed. That folds in whatever the clocks miss
     * and how you place a beat, which is what a take needs to sit on the grid
     * the way you played it.
     */
    void calibrate(Runnable then) {
        if (!micOpen || busy) {
            if (!micOpen) say("The mic is not open");
            return;
        }
        if (playing) {
            say("Stop the beat first");
            return;
        }
        busy = true;
        say("Sync: four clicks count in, then clap on each of the next eight");
        worker.execute(() -> {
            try {
                long settle = System.currentTimeMillis() + 3000;
                while (micFrameFor(0) == Long.MIN_VALUE) {
                    if (System.currentTimeMillis() > settle) throw new IOException("the clocks never settled");
                    Thread.sleep(50);
                }
                final long[] at = new long[SYNC_CLICKS];
                int gap = sr * 6 / 10;
                CountDownLatch scheduled = new CountDownLatch(1);
                commands.add(() -> {
                    for (int i = 0; i < at.length; i++) {
                        at[i] = written + sr / 2 + (long) i * gap;
                        trigger(i % 4 == 0 ? clickHi : clickLo, 1f, -1, at[i] - written, false);
                    }
                    scheduled.countDown();
                });
                if (!scheduled.await(2, TimeUnit.SECONDS)) throw new IOException("the speaker is not running");
                long last = micFrameFor(at[at.length - 1]);
                if (!waitForMic(last + gap, 15000)) throw new IOException("the mic stopped");
                if (BuildConfig.DEBUG) dumpCalibration(at);

                // The room during the count-in is the noise floor to beat.
                float floor = rms(micFrameFor(at[0]), (int) (micFrameFor(at[SYNC_LEAD - 1]) - micFrameFor(at[0])));
                List<Long> offsets = new ArrayList<>();
                int before = gap / 3, span = gap * 5 / 6;   // -200 ms to +300 ms around each click
                for (int i = SYNC_LEAD; i < at.length; i++) {
                    long m = micFrameFor(at[i]);
                    float[] w = micCopy(m - before, span);
                    if (w == null) continue;
                    float peak = 0;
                    for (float x : w) peak = Math.max(peak, Math.abs(x));
                    if (peak < Math.max(0.02f, floor * 8)) continue;
                    float threshold = Math.max(floor * 6, peak * 0.3f);
                    for (int k = 0; k < w.length; k++) {
                        if (Math.abs(w[k]) > threshold) { offsets.add((long) (k - before)); break; }
                    }
                }
                Log.i(TAG, "sync claps (frames from each click) " + offsets + ", floor " + floor);
                if (offsets.size() < 5) {
                    throw new IOException("heard " + offsets.size() + " of 8 claps. Clap louder, nearer the Portal");
                }
                Collections.sort(offsets);
                long median = offsets.get(offsets.size() / 2);
                // Middle half of the claps, so one early or late clap does not make it look sloppy.
                long spread = offsets.get(offsets.size() * 3 / 4) - offsets.get(offsets.size() / 4);
                prefs().edit().putLong(clockKey(), median).apply();
                say(String.format(Locale.US, "Synced: takes shift %+.0f ms (%d claps, within %.0f ms of each other)",
                    median * 1000.0 / sr, offsets.size(), spread * 1000.0 / sr));
                if (then != null) main.post(then);
            } catch (Exception e) {
                say("Sync failed: " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    /** Debug builds: the mic around the sync clicks, to pull and look at, with where the clocks put each click. */
    private void dumpCalibration(long[] at) {
        try {
            long from = micFrameFor(at[0]) - sr * 3 / 10;
            int n = (int) (micFrameFor(at[at.length - 1]) + sr * 6 / 10 - from);
            float[] w = micCopy(from, n);
            if (w == null) return;
            StringBuilder b = new StringBuilder("sync window: clicks at");
            for (long a : at) b.append(' ').append(micFrameFor(a) - from);
            Log.i(TAG, b.toString());
            WavFile.write(new File(app.getExternalFilesDir("exports"), "sync.wav"), w, sr);
        } catch (Exception e) {
            Log.w(TAG, "sync dump failed", e);
        }
    }

    // =============================================================== export

    /**
     * Renders one pass of the loop, every audible track, to a WAV that loops
     * seamlessly (hits that ring past the end wrap round to the start), and
     * says where it went. Pull it with adb.
     */
    void export() {
        worker.execute(() -> {
            BeatSession s = session;
            int len = s.loopFrames();
            float[] mix = new float[len];
            double stepLen = s.barFrames() / (double) BeatSession.STEPS;
            double swing = BeatSession.SWING[s.swing] * stepLen;
            for (BeatSession.Track tr : s.tracks) {
                if (!s.audible(tr)) continue;
                float g = tr.gain();
                if (tr.loop) {
                    float[] a = tr.audio;
                    if (a == null) continue;
                    for (int i = 0; i < len; i++) mix[i] += a[i % a.length] * g;
                    continue;
                }
                float[] smp = tr.sample;
                if (smp == null) continue;
                for (int k = 0; k < s.bars * BeatSession.STEPS; k++) {
                    int idx = k % BeatSession.STEPS;
                    if (!tr.steps[idx]) continue;
                    long at = (long) Math.floor(k * stepLen) + ((k & 1) != 0 ? (long) swing : 0);
                    float v = g * (tr.accents[idx] ? 1f : BeatSession.NORMAL_HIT);
                    for (int j = 0; j < smp.length; j++) mix[(int) ((at + j) % len)] += smp[j] * v;
                }
            }
            for (int i = 0; i < len; i++) mix[i] = soft(mix[i]);
            File dir = app.getExternalFilesDir("exports");
            String name = "beat-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".wav";
            try {
                File f = new File(dir, name);
                WavFile.write(f, mix, sr);
                say("Exported " + name + " · adb pull " + f.getAbsolutePath());
            } catch (Exception e) {
                say("Export failed: " + e.getMessage());
            }
        });
    }
}
