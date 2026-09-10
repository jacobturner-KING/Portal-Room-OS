package com.portalroomos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.LoudnessEnhancer;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.InputStream;
import java.util.Locale;

/**
 * Runs the bundled librespot binary so the Portal appears as a Spotify Connect
 * speaker ("Portal") and plays audio through its own speaker. librespot streams
 * raw PCM on stdout (pipe backend); we pump that into an AudioTrack, which paces
 * playback to real time and back-pressures the stream.
 *
 * Started from MainActivity and from BootReceiver, so the speaker is available
 * whenever the Portal is powered on.
 */
public class LibrespotService extends Service {
    private static final String TAG = "Librespot";
    private static final String CHANNEL_ID = "librespot";
    private static final String DEVICE_NAME = "Portal";
    private static final int SAMPLE_RATE = 44100;   // librespot's pipe output is always 44.1 kHz
    private static final int FRAME_BYTES = 4;       // stereo * 16-bit
    private static final long STATS_INTERVAL_NS = 30_000_000_000L;

    // Speaker gain on top of full-scale PCM, applied by Android's LoudnessEnhancer
    // (gain + limiter, so louder without hard clipping). Persisted; tune without a
    // rebuild through GainReceiver:
    //   adb shell am broadcast -a com.portalroomos.SET_GAIN --ei gain_db 10 -n com.portalroomos/.GainReceiver
    public static final String EXTRA_GAIN_DB = "gain_db";
    private static final String PREFS = "librespot";
    private static final String PREF_GAIN_DB = "gain_db";
    private static final int DEFAULT_GAIN_DB = 8;
    /**
     * Where the Spotify slider for "Portal" starts on a fresh install, as a
     * percentage. Only a default: librespot caches the last volume in its system
     * cache and that wins on every later start, so turning it up in Spotify
     * sticks. Delete `files/librespot/volume` to fall back to this again.
     */
    private static final int DEFAULT_VOLUME_PCT = 25;
    private static final int MAX_GAIN_DB = 20;

    private volatile boolean running = false;
    private Thread worker;
    private Process process;
    private volatile AudioTrack audioTrack;
    private volatile LoudnessEnhancer loudness;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        acquireLocks();
        startForeground(1, buildNotification());
        running = true;
        worker = new Thread(this::runLoop, "librespot-runner");
        worker.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_GAIN_DB)) {
            int db = Math.max(0, Math.min(MAX_GAIN_DB, intent.getIntExtra(EXTRA_GAIN_DB, DEFAULT_GAIN_DB)));
            prefs().edit().putInt(PREF_GAIN_DB, db).apply();
            applyGain(loudness, db);
            Log.i(TAG, "speaker gain set to +" + db + " dB");
        }
        // Every start-foreground-service call must be answered with startForeground.
        startForeground(1, buildNotification());
        return START_STICKY;
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private int gainDb() { return prefs().getInt(PREF_GAIN_DB, DEFAULT_GAIN_DB); }

    private void applyGain(LoudnessEnhancer le, int db) {
        if (le == null) return;
        try {
            le.setTargetGain(db * 100); // millibels
            le.setEnabled(db > 0);
        } catch (Exception e) {
            Log.w(TAG, "loudness gain failed", e);
        }
    }

    private void runLoop() {
        String bin = getApplicationInfo().nativeLibraryDir + "/liblibrespot.so";
        // Credentials + volume persist in the app's files dir (survives cache purges) so
        // librespot logs itself back in on every start and "Portal" is always listed as a
        // Connect device. Downloaded audio is cached separately, size-capped, in cache/.
        File systemCache = new File(getFilesDir(), "librespot");
        File audioCache = new File(getCacheDir(), "librespot");
        // librespot buffers decrypted audio in a temp file via TMPDIR; the default
        // (/data/local/tmp) isn't writable by our app uid, so point it at our cache dir.
        File tmp = new File(getCacheDir(), "lrs");
        systemCache.mkdirs();
        audioCache.mkdirs();
        tmp.mkdirs();

        long backoffMs = 2000;
        while (running) {
            long startedAt = SystemClock.elapsedRealtime();
            try {
                Log.i(TAG, "starting librespot: " + bin);
                ProcessBuilder pb = new ProcessBuilder(
                    bin,
                    "-n", DEVICE_NAME,
                    "-B", "pipe",
                    "--bitrate", "320",
                    // Cubic curve: the Spotify slider's middle is ~10 dB louder than
                    // with the default log curve.
                    "--volume-ctrl", "cubic",
                    "--initial-volume", String.valueOf(DEFAULT_VOLUME_PCT),
                    "--system-cache", systemCache.getAbsolutePath(),
                    "--cache", audioCache.getAbsolutePath(),
                    "--cache-size-limit", "1G");
                pb.directory(tmp);
                pb.environment().put("TMPDIR", tmp.getAbsolutePath());
                pb.redirectErrorStream(false); // stdout = PCM, stderr = logs
                process = pb.start();
                drainLogs(process.getErrorStream());
                pump(process.getInputStream());
                int code = process.waitFor();
                Log.w(TAG, "librespot exited code=" + code);
            } catch (Exception e) {
                Log.e(TAG, "librespot run failed", e);
            }
            if (!running) break;
            // A run that lasted a while was healthy: restart quickly. Rapid crash loops
            // (e.g. Wi-Fi not up yet right after boot) back off up to 30s.
            boolean wasHealthy = SystemClock.elapsedRealtime() - startedAt > 60_000;
            backoffMs = wasHealthy ? 2000 : Math.min(backoffMs * 2, 30_000);
            try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) { return; }
        }
    }

    /** Read PCM (S16LE, 44.1kHz, stereo) from librespot stdout into AudioTrack. */
    private void pump(InputStream in) throws Exception {
        // Audio-priority scheduling keeps UI work and network callbacks from starving the pump.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

        int minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 4, SAMPLE_RATE * FRAME_BYTES); // ~1s of audio, underrun-resistant
        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
        audioTrack = track;
        Log.i(TAG, "audio track ready: " + track.getBufferSizeInFrames() + " frames buffered ("
            + (track.getBufferSizeInFrames() * 1000 / SAMPLE_RATE) + " ms), min " + minBuf + " bytes");
        // The Portal is a speaker: keep Android's media volume pinned at max so the
        // Spotify volume slider (librespot's softvol) is the one volume control.
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (cur < max) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
                Log.i(TAG, "media volume " + cur + " -> " + max);
            }
        }
        try {
            LoudnessEnhancer le = new LoudnessEnhancer(track.getAudioSessionId());
            applyGain(le, gainDb());
            loudness = le;
            Log.i(TAG, "loudness enhancer on session " + track.getAudioSessionId()
                + ": +" + gainDb() + " dB");
        } catch (Exception e) {
            Log.w(TAG, "LoudnessEnhancer unavailable; playing at 0 dB", e);
        }
        track.play();

        byte[] buf = new byte[16384];
        int leftover = 0;              // carry 0..3 bytes that don't complete a frame
        long bytesSinceStats = 0;
        long lastStatsNs = System.nanoTime();
        int lastUnderruns = track.getUnderrunCount();
        try {
            while (running) {
                int n = in.read(buf, leftover, buf.length - leftover);
                if (n < 0) break;
                int avail = leftover + n;
                int writable = avail - (avail % FRAME_BYTES); // whole frames only
                int off = 0;
                while (off < writable) {
                    int w = track.write(buf, off, writable - off); // blocks -> real-time pacing
                    if (w < 0) throw new Exception("AudioTrack.write error " + w);
                    off += w;
                }
                leftover = avail - writable;
                if (leftover > 0) System.arraycopy(buf, writable, buf, 0, leftover);

                // Periodic health line while audio is flowing: `adb logcat -s Librespot`.
                bytesSinceStats += writable;
                long now = System.nanoTime();
                if (now - lastStatsNs >= STATS_INTERVAL_NS) {
                    int underruns = track.getUnderrunCount();
                    Log.i(TAG, String.format(Locale.US,
                        "audio: %.1fs streamed in last %.0fs, underruns +%d (total %d)",
                        bytesSinceStats / (double) (SAMPLE_RATE * FRAME_BYTES),
                        (now - lastStatsNs) / 1e9, underruns - lastUnderruns, underruns));
                    lastUnderruns = underruns;
                    bytesSinceStats = 0;
                    lastStatsNs = now;
                }
            }
        } finally {
            LoudnessEnhancer le = loudness;
            loudness = null;
            if (le != null) { try { le.release(); } catch (Exception ignored) {} }
            try { track.stop(); } catch (Exception ignored) {}
            track.release();
            audioTrack = null;
        }
    }

    private void drainLogs(InputStream err) {
        Thread t = new Thread(() -> {
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(err));
                String line;
                while ((line = r.readLine()) != null) Log.i(TAG, line);
            } catch (Exception ignored) {}
        }, "librespot-logs");
        t.setDaemon(true);
        t.start();
    }

    private void acquireLocks() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("librespot-mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "librespot-wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "portalroomos:librespot");
            wakeLock.acquire();
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Spotify Connect", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return b.setContentTitle("Portal audio")
            .setContentText("Ready as a Spotify Connect speaker · +" + gainDb() + " dB")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build();
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        if (process != null) process.destroy();
        AudioTrack t = audioTrack;
        try { if (t != null) { t.stop(); t.release(); } } catch (Exception ignored) {}
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
