package com.portalroomos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Records the room through the Portal's own microphones and hands the audio to
 * the Room Brain in chunks as it goes. Whisper there turns each chunk into text
 * the moment it lands, so when the chip is tapped again the transcript is all
 * but done; the Room Brain then names it and it appears in Meetings.
 *
 * The recording starts the instant the chip is tapped, under an id made here,
 * so the network never gates the first word. Chunks sit on disk until the Room
 * Brain has acknowledged them, and an upload that fails is retried with a
 * growing pause, so a Wi-Fi hiccup costs nothing. Audio never stays anywhere:
 * a chunk is deleted once its text is on the Worker.
 *
 * The far side of a call is captured only as far as the room hears it: a call
 * on speaker, yes; one in an earpiece, no.
 */
public class RecorderService extends Service {
    private static final String TAG = "Recorder";
    private static final String CHANNEL_ID = "recorder";
    private static final int NOTIFICATION_ID = 2;
    static final String ACTION_START = "com.portalroomos.RECORD_START";
    static final String ACTION_STOP = "com.portalroomos.RECORD_STOP";

    /** Whisper works at 16 kHz mono; anything more is bytes for nothing. */
    private static final int SAMPLE_RATE = 16_000;
    /** Three minutes: long enough for sentences to stay whole, small enough to upload in one go. */
    static final int CHUNK_SECONDS = 180;
    private static final int MAX_UPLOAD_TRIES = 12;

    enum State { IDLE, RECORDING, FINISHING, SAVED, FAILED }

    // ---- what the dashboard reads, once a second ----
    static volatile State state = State.IDLE;
    static volatile long startedAtMs;
    static volatile int chunksPending;
    static volatile String savedTitle = "";
    static volatile String savedId = "";
    static volatile String lastError = "";
    /**
     * Recent sound levels, 0 (silence) to 1 (loud), one per 50 ms, a ring the
     * capture thread writes and the level meter reads. Written unsynchronised
     * on purpose: a torn frame on a meter is nothing, a lock on the capture
     * loop is not.
     */
    static final float[] levels = new float[40];
    static volatile int levelPos;
    /**
     * dB below full scale that counts as silence and as loud, for the meter.
     * Measured on the Portal: a quiet room sits near -55, a voice a metre off
     * runs -36 to -17.
     */
    private static final float DB_FLOOR = -60f, DB_CEIL = -12f;

    private static final SimpleDateFormat ISO = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    static { ISO.setTimeZone(TimeZone.getTimeZone("UTC")); }

    private final ExecutorService uploads = Executors.newSingleThreadExecutor();
    private RoomBrainClient brain;
    private PowerManager.WakeLock wakeLock;
    private Thread recorder;
    private volatile boolean stopRequested;
    private String id;
    private String startedAtIso;
    private File dir;

    static boolean running() {
        return state == State.RECORDING || state == State.FINISHING;
    }

    static void start(Context c) {
        Intent i = new Intent(c, RecorderService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) {
        c.startService(new Intent(c, RecorderService.class).setAction(ACTION_STOP));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        brain = new RoomBrainClient(Config.ROOM_BRAIN_URL, Config.APP_TOKEN);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        // Every start-foreground-service call must be answered with startForeground.
        startForeground(NOTIFICATION_ID, buildNotification("Recording"));
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            if (recorder == null || !recorder.isAlive()) done(); // nothing to stop; do not linger
            return START_NOT_STICKY;
        }
        if (recorder != null && recorder.isAlive()) return START_NOT_STICKY; // already going
        begin();
        return START_NOT_STICKY;
    }

    private void begin() {
        id = "rec_" + Long.toString(System.currentTimeMillis(), 36);
        startedAtMs = System.currentTimeMillis();
        startedAtIso = ISO.format(new Date(startedAtMs));
        dir = new File(getFilesDir(), "rec/" + id);
        if (!dir.mkdirs() && !dir.isDirectory()) {
            fail("cannot create " + dir);
            return;
        }
        stopRequested = false;
        chunksPending = 0;
        savedTitle = "";
        lastError = "";
        java.util.Arrays.fill(levels, 0f);
        state = State.RECORDING;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "portalroomos:recorder");
            wakeLock.acquire();
        }
        recorder = new Thread(this::record, "recorder");
        recorder.start();
        Log.i(TAG, "recording " + id);
    }

    /** The capture loop: PCM to a chunk file, a new file every CHUNK_SECONDS. */
    private void record() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(minBuf, SAMPLE_RATE * 2); // a second's worth, at least
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
        } catch (Exception e) {
            fail("microphone unavailable: " + e.getMessage());
            return;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            fail("microphone not granted");
            return;
        }

        byte[] buf = new byte[SAMPLE_RATE * 2 / 5]; // 200 ms
        long chunkBytesMax = (long) CHUNK_SECONDS * SAMPLE_RATE * 2;
        int seq = 0;
        long written = 0;
        OutputStream out = null;
        try {
            rec.startRecording();
            out = new FileOutputStream(chunkFile(seq));
            while (!stopRequested) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) {
                    if (n < 0) throw new IOException("read returned " + n);
                    continue;
                }
                out.write(buf, 0, n);
                meter(buf, n);
                written += n;
                if (written >= chunkBytesMax) {
                    out.close();
                    enqueue(seq);
                    seq++;
                    written = 0;
                    out = new FileOutputStream(chunkFile(seq));
                }
            }
            out.close();
            out = null;
            if (written > 0) enqueue(seq); else chunkFile(seq).delete();
        } catch (Exception e) {
            Log.w(TAG, "capture stopped: " + e.getMessage());
            lastError = e.getMessage() == null ? "capture failed" : e.getMessage();
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            if (written > 0) enqueue(seq);
        } finally {
            try { rec.stop(); } catch (Exception ignored) {}
            rec.release();
        }
        state = State.FINISHING;
        long durationSec = (System.currentTimeMillis() - startedAtMs) / 1000;
        uploads.execute(() -> finish(durationSec));
    }

    /** Pushes one level per 50 ms of the buffer just read, RMS mapped onto a dB scale. */
    private static void meter(byte[] pcm, int n) {
        int block = SAMPLE_RATE * 2 / 20; // 50 ms of 16-bit mono
        for (int start = 0; start + block <= n; start += block) {
            double sum = 0;
            for (int i = start; i < start + block; i += 2) {
                int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                sum += (double) sample * sample;
            }
            double rms = Math.sqrt(sum / (block / 2));
            float db = (float) (20 * Math.log10(Math.max(rms, 1) / 32768.0));
            float level = Math.max(0f, Math.min(1f, (db - DB_FLOOR) / (DB_CEIL - DB_FLOOR)));
            levels[levelPos] = level;
            levelPos = (levelPos + 1) % levels.length;
        }
    }

    private File chunkFile(int seq) {
        return new File(dir, "chunk-" + seq + ".pcm");
    }

    private void enqueue(int seq) {
        chunksPending++;
        uploads.execute(() -> upload(seq));
    }

    /** One chunk up, retried with a growing pause; the file goes once the Worker has its text. */
    private void upload(int seq) {
        File f = chunkFile(seq);
        for (int attempt = 1; attempt <= MAX_UPLOAD_TRIES; attempt++) {
            try {
                byte[] wav = wav(f);
                brain.uploadChunkSync(id, seq, startedAtIso, wav);
                f.delete();
                chunksPending--;
                Log.i(TAG, "chunk " + seq + " transcribed (" + wav.length / 1024 + " KB)");
                return;
            } catch (Exception e) {
                lastError = "upload " + seq + ": " + e.getMessage();
                Log.w(TAG, lastError + " (try " + attempt + ")");
                try { Thread.sleep(Math.min(60_000, 2_000L * attempt)); } catch (InterruptedException ie) { return; }
            }
        }
        // Left on disk; the transcript will have a hole where this chunk was.
        chunksPending--;
    }

    private void finish(long durationSec) {
        for (int attempt = 1; attempt <= MAX_UPLOAD_TRIES; attempt++) {
            try {
                savedTitle = brain.finishRecordingSync(id, durationSec);
                savedId = id;
                state = State.SAVED;
                Log.i(TAG, "saved as \"" + savedTitle + "\"");
                done();
                return;
            } catch (Exception e) {
                lastError = "finish: " + e.getMessage();
                Log.w(TAG, lastError + " (try " + attempt + ")");
                try { Thread.sleep(Math.min(60_000, 2_000L * attempt)); } catch (InterruptedException ie) { return; }
            }
        }
        fail("could not reach the Room Brain to finish");
    }

    private void fail(String why) {
        lastError = why;
        state = State.FAILED;
        Log.w(TAG, why);
        done();
    }

    private void done() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        stopRequested = true;
        uploads.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    /** The chunk's PCM behind a 44-byte header: what Whisper accepts as a file. */
    private static byte[] wav(File pcm) throws IOException {
        int len = (int) pcm.length();
        byte[] out = new byte[44 + len];
        ByteBuffer h = ByteBuffer.wrap(out, 0, 44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + len).put("WAVE".getBytes())
         .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
         .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16)
         .put("data".getBytes()).putInt(len);
        try (FileInputStream in = new FileInputStream(pcm)) {
            int off = 44, n;
            while (off < out.length && (n = in.read(out, off, out.length - off)) > 0) off += n;
        }
        return out;
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return b.setContentTitle("Portal Room OS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build();
    }
}
