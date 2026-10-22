package com.portalroomos;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import java.io.ByteArrayOutputStream;

/**
 * Push-to-talk for the agents: the Portal's mics into memory, from the tap
 * that opens them to the tap that sends, then handed over as one WAV for
 * Whisper on the Room Brain. Short by nature, so nothing touches disk.
 *
 * The same MIC path the meeting recorder uses: Facebook's far-field voice DSP
 * gates and cleans speech, which is what Whisper wants here. Android 9 lets one
 * app capture at a time, so this cannot open while the recorder is running.
 */
final class VoiceNote {
    private static final String TAG = "VoiceNote";
    static final int SAMPLE_RATE = 16_000;
    /** A spoken instruction, not a meeting; stop listening after this. */
    static final int MAX_SECONDS = 90;

    /** A push-to-talk microphone is open; the agents' voice keeps quiet meanwhile. */
    static volatile boolean listeningNow;

    final LevelRing levels = new LevelRing(40);
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private volatile boolean stopRequested;
    private volatile String error;
    private Thread thread;
    private long startedAtMs;

    /** Opens the mic at once; returns false, with {@link #error()}, if it cannot. */
    boolean start() {
        if (RecorderService.running()) {
            error = "The recorder has the microphone; stop it to talk";
            return false;
        }
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, SAMPLE_RATE));
        } catch (Exception e) {
            error = "Microphone unavailable: " + e.getMessage();
            return false;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            error = "Microphone not granted · adb shell pm grant com.portalroomos android.permission.RECORD_AUDIO";
            return false;
        }
        levels.clear();
        stopRequested = false;
        startedAtMs = System.currentTimeMillis();
        thread = new Thread(() -> capture(rec), "voice-note");
        listeningNow = true;
        thread.start();
        return true;
    }

    private void capture(AudioRecord rec) {
        byte[] buf = new byte[SAMPLE_RATE * 2 / 10]; // 100 ms
        long max = (long) MAX_SECONDS * SAMPLE_RATE * 2;
        try {
            rec.startRecording();
            while (!stopRequested && pcm.size() < max) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) throw new IllegalStateException("read returned " + n);
                if (n == 0) continue;
                pcm.write(buf, 0, n);
                levels.push(buf, n, SAMPLE_RATE);
            }
        } catch (Exception e) {
            error = "Microphone stopped: " + e.getMessage();
            Log.w(TAG, error);
        } finally {
            try { rec.stop(); } catch (Exception ignored) {}
            rec.release();
            listeningNow = false;
        }
    }

    /** Still listening (false once the length cap is reached). */
    boolean listening() {
        return thread != null && thread.isAlive();
    }

    long elapsedMs() {
        return System.currentTimeMillis() - startedAtMs;
    }

    /** Closes the mic and returns what it heard as a WAV, or null if it heard nothing. */
    byte[] finish() {
        stopRequested = true;
        if (thread != null) {
            try { thread.join(1500); } catch (InterruptedException ignored) {}
        }
        if (pcm.size() == 0) return null;
        return WavFile.fromPcm16(pcm.toByteArray(), SAMPLE_RATE);
    }

    /** Closes the mic and forgets it. */
    void cancel() {
        stopRequested = true;
        pcm.reset();
    }

    String error() {
        return error;
    }
}
