package com.portalroomos;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the beat machine is playing: tempo, loop length, swing, and eight
 * tracks. A step track plays one sound on a 16-step grid that repeats every
 * bar; a loop track holds a take from the microphone, a whole number of bars
 * long, that repeats on its own length.
 *
 * The UI thread edits this and the audio thread reads it, without locks: a
 * step flipped half a block late is inaudible, and the audio arrays are swapped
 * whole through volatile fields so the engine never sees half a take.
 *
 * It all survives leaving the screen and restarting the app: the settings and
 * patterns as JSON, each take as a WAV beside it, in files/beats.
 */
final class BeatSession {

    private static final String TAG = "Beats";
    static final int TRACKS = 8, STEPS = 16;
    static final int MIN_BPM = 50, MAX_BPM = 200;
    static final int[] BAR_CHOICES = { 1, 2, 4, 8 };
    /** How late the off-beat sixteenths land, as a fraction of a sixteenth. A third is a triplet shuffle. */
    static final double[] SWING = { 0, 0.1, 0.2, 1.0 / 3 };
    static final String[] SWING_NAMES = { "Straight", "Light swing", "Swing", "Shuffle" };
    /** Velocity of a plain step; an accented one plays at full. */
    static final float NORMAL_HIT = 0.7f;

    static final class Track {
        boolean loop;
        /** "kit:kick" for a built-in sound, otherwise the path of a WAV. Step tracks only. */
        String sound = "";
        String name = "";
        final boolean[] steps = new boolean[STEPS];
        final boolean[] accents = new boolean[STEPS];
        volatile int volume = 75;
        volatile boolean mute, solo;
        /** Step tracks: the hit, at the engine's rate. */
        volatile float[] sample;
        /** Loop tracks: the take, exactly its own number of bars long. */
        volatile float[] audio;
        /** The take before the last one, for Undo. */
        float[] undo;

        /** Squared, so the slider feels even to the ear. */
        float gain() {
            float v = volume / 100f;
            return v * v;
        }
    }

    static final class Sound {
        final String ref, name;
        Sound(String ref, String name) { this.ref = ref; this.name = name; }
    }

    static final class Groove {
        final String name;
        final int bpm, swing;
        /** kit id, then its 16 steps: x a hit, X an accent, . a rest. */
        final String[] parts;
        Groove(String name, int bpm, int swing, String... parts) {
            this.name = name; this.bpm = bpm; this.swing = swing; this.parts = parts;
        }
    }

    /** Starting points for playing along. A groove only rewrites step tracks that use the kit sounds it names. */
    static final Groove[] GROOVES = {
        new Groove("Rock", 100, 0,
            "kick", "X.......x.x.....",
            "snare", "....X.......X...",
            "hat", "X.x.x.x.X.x.x.x."),
        new Groove("Four on the floor", 120, 0,
            "kick", "X...X...X...X...",
            "clap", "....X.......X...",
            "hat", "x.x.x.x.x.x.x.x.",
            "openhat", "..x...x...x...x."),
        new Groove("Funk", 98, 1,
            "kick", "X..x......x..x..",
            "snare", "....X..x.x..X...",
            "hat", "x.xXx.xXx.xXx.xX"),
        new Groove("Hip hop", 88, 2,
            "kick", "X.....x...x..x..",
            "snare", "....X.......X...",
            "hat", "x.x.x.x.x.x.x.x."),
        new Groove("Half-time", 140, 0,
            "kick", "X.........x.....",
            "snare", "........X.......",
            "hat", "x.x.x.x.x.x.x.x."),
        new Groove("Blues shuffle", 84, 3,
            "kick", "X.......x.......",
            "snare", "....X.......X...",
            "hat", "X.x.x.x.X.x.x.x."),
        new Groove("Reggae one drop", 76, 1,
            "kick", "........X.......",
            "snare", "........x.......",
            "hat", "..x...x...x...x."),
        new Groove("Empty", 0, 0),
    };

    volatile int bpm = 100, bars = 2, swing = 0;
    volatile boolean click;
    final Track[] tracks = new Track[TRACKS];
    final int sr;
    final File dir, soundsDir, importDir;
    private final Map<String, float[]> kitCache = new HashMap<>();

    BeatSession(Context c, int sr) {
        this.sr = sr;
        dir = new File(c.getFilesDir(), "beats");
        soundsDir = new File(dir, "sounds");
        // Push WAVs here with adb; no permission needed, and the app's own.
        importDir = c.getExternalFilesDir("samples");
        dir.mkdirs();
        soundsDir.mkdirs();
        if (importDir != null) importDir.mkdirs();
        for (int i = 0; i < TRACKS; i++) tracks[i] = new Track();
        if (!load()) reset();
    }

    /** Five drums on a rock beat, three empty loops: press play and there is something to play to. */
    void reset() {
        String[] kit = { "kick", "snare", "hat", "openhat", "clap" };
        for (int i = 0; i < TRACKS; i++) {
            Track t = tracks[i];
            t.volume = 75;
            t.mute = t.solo = false;
            Arrays.fill(t.steps, false);
            Arrays.fill(t.accents, false);
            t.undo = null;
            if (i < kit.length) {
                t.loop = false;
                t.audio = null;
                setSound(t, "kit:" + kit[i], BeatKit.name(kit[i]));
            } else {
                t.loop = true;
                t.sound = "";
                t.sample = null;
                t.audio = null;
                t.name = "Loop " + (i - kit.length + 1);
                new File(dir, "loop-" + i + ".wav").delete();
            }
        }
        bpm = 100;
        bars = 2;
        swing = 0;
        click = false;
        applyGroove(GROOVES[0]);
        bpm = GROOVES[0].bpm;
        save();
    }

    int barFrames() {
        return (int) Math.round(sr * 240.0 / bpm);
    }

    int loopFrames() {
        return bars * barFrames();
    }

    boolean anyLoopAudio() {
        for (Track t : tracks) if (t.loop && t.audio != null) return true;
        return false;
    }

    boolean audible(Track t) {
        if (t.mute) return false;
        if (t.solo) return true;
        for (Track o : tracks) if (o.solo) return false;
        return true;
    }

    /** Rewrites the step tracks the groove names, and the swing. The tempo is the caller's to set. */
    void applyGroove(Groove g) {
        for (Track t : tracks) {
            if (t.loop) continue;
            Arrays.fill(t.steps, false);
            Arrays.fill(t.accents, false);
            String id = t.sound.startsWith("kit:") ? t.sound.substring(4) : "";
            for (int p = 0; p + 1 < g.parts.length; p += 2) {
                if (!g.parts[p].equals(id)) continue;
                String pattern = g.parts[p + 1];
                for (int s = 0; s < STEPS && s < pattern.length(); s++) {
                    char ch = pattern.charAt(s);
                    t.steps[s] = ch == 'x' || ch == 'X';
                    t.accents[s] = ch == 'X';
                }
            }
        }
        if (g.bpm > 0) swing = g.swing;
    }

    // ---------------------------------------------------------------- sounds

    List<Sound> sounds() {
        List<Sound> out = new ArrayList<>();
        for (int i = 0; i < BeatKit.IDS.length; i++) out.add(new Sound("kit:" + BeatKit.IDS[i], BeatKit.NAMES[i]));
        addWavs(out, soundsDir);
        addWavs(out, importDir);
        return out;
    }

    private static void addWavs(List<Sound> out, File folder) {
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            String n = f.getName();
            if (!n.toLowerCase().endsWith(".wav")) continue;
            out.add(new Sound(f.getAbsolutePath(), n.substring(0, n.length() - 4)));
        }
    }

    /** Points a step track at a sound and builds the hit. False (and nothing changed) if it cannot be read. */
    boolean setSound(Track t, String ref, String name) {
        float[] s = loadSound(ref);
        if (s == null) return false;
        t.sound = ref;
        t.name = name;
        t.sample = s;
        return true;
    }

    private float[] loadSound(String ref) {
        if (ref.startsWith("kit:")) {
            String id = ref.substring(4);
            synchronized (kitCache) {
                float[] s = kitCache.get(id);
                if (s == null) {
                    s = BeatKit.render(id, sr);
                    if (s != null) kitCache.put(id, s);
                }
                return s;
            }
        }
        try {
            float[] s = WavFile.read(new File(ref), sr);
            // Ten seconds is plenty for a hit, and keeps a stray song from filling memory.
            return s.length > 10 * sr ? Arrays.copyOf(s, 10 * sr) : s;
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + ref, e);
            return null;
        }
    }

    File newSoundFile() {
        for (int i = 1; ; i++) {
            File f = new File(soundsDir, "Sound " + i + ".wav");
            if (!f.exists()) return f;
        }
    }

    // ----------------------------------------------------------- persistence

    void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("bpm", bpm).put("bars", bars).put("swing", swing).put("click", click).put("sr", sr);
            JSONArray ts = new JSONArray();
            for (Track t : tracks) {
                JSONObject j = new JSONObject();
                j.put("loop", t.loop).put("sound", t.sound).put("name", t.name)
                    .put("volume", t.volume).put("mute", t.mute).put("solo", t.solo);
                StringBuilder steps = new StringBuilder();
                for (int s = 0; s < STEPS; s++) steps.append(t.accents[s] ? 'X' : t.steps[s] ? 'x' : '.');
                j.put("steps", steps.toString());
                ts.put(j);
            }
            o.put("tracks", ts);
            File tmp = new File(dir, "session.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString(1).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(dir, "session.json"))) Log.w(TAG, "session rename failed");
        } catch (Exception e) {
            Log.w(TAG, "session not saved", e);
        }
    }

    /** Writes one loop track's take (or removes it), separately from the JSON because it is large. */
    void saveLoop(int i) {
        File f = new File(dir, "loop-" + i + ".wav");
        float[] a = tracks[i].audio;
        if (a == null) {
            f.delete();
            return;
        }
        try {
            WavFile.write(f, a, sr);
        } catch (IOException e) {
            Log.w(TAG, "loop " + i + " not saved", e);
        }
    }

    private boolean load() {
        File f = new File(dir, "session.json");
        if (!f.exists()) return false;
        try {
            byte[] raw = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int got = 0;
                while (got < raw.length) {
                    int n = in.read(raw, got, raw.length - got);
                    if (n < 0) break;
                    got += n;
                }
            }
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            bpm = Math.max(MIN_BPM, Math.min(MAX_BPM, o.optInt("bpm", 100)));
            bars = o.optInt("bars", 2);
            swing = Math.max(0, Math.min(SWING.length - 1, o.optInt("swing", 0)));
            click = o.optBoolean("click", false);
            JSONArray ts = o.getJSONArray("tracks");
            for (int i = 0; i < TRACKS && i < ts.length(); i++) {
                JSONObject j = ts.getJSONObject(i);
                Track t = tracks[i];
                t.loop = j.optBoolean("loop");
                t.name = j.optString("name", "Track " + (i + 1));
                t.volume = j.optInt("volume", 75);
                t.mute = j.optBoolean("mute");
                t.solo = j.optBoolean("solo");
                String steps = j.optString("steps", "");
                for (int s = 0; s < STEPS && s < steps.length(); s++) {
                    char ch = steps.charAt(s);
                    t.steps[s] = ch == 'x' || ch == 'X';
                    t.accents[s] = ch == 'X';
                }
                if (t.loop) {
                    File w = new File(dir, "loop-" + i + ".wav");
                    if (w.exists()) t.audio = WavFile.read(w, sr);
                } else if (!setSound(t, j.optString("sound", "kit:kick"), t.name)) {
                    setSound(t, "kit:kick", "Kick");   // the file went away; keep the row usable
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "session unreadable; starting fresh", e);
            return false;
        }
    }
}
