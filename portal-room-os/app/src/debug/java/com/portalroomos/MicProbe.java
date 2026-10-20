package com.portalroomos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Debug builds: how good is each of the Portal's capture paths for music?
 * Records four seconds of the room from each source, encoding and channel
 * layout in turn and logs its level and how much of it is above 4 and 7 kHz
 * (a path squeezed through a 16 kHz voice pipeline has next to nothing up
 * there, which is what "muffled" sounds like). Captures go to
 * exports/probe-*.wav, channel 0 only. Close the Beats screen first; the mic
 * is one-at-a-time. Talk or play while it runs.
 *
 *   adb shell am broadcast -a com.portalroomos.MIC_PROBE -n com.portalroomos/.MicProbe
 *
 * With `--ez bleed true` it also plays a bar of drums through the speaker
 * during each capture and logs how strongly each path hears them: the paths
 * with no echo canceller in front of them are the ones that do.
 *
 * Measured 2026-10-19: the speaker is echo-cancelled out of every path.
 */
public class MicProbe extends BroadcastReceiver {

    private static final String TAG = "MicProbe";
    private static final int SR = 48000;

    @Override public void onReceive(Context c, Intent intent) {
        PendingResult done = goAsync();
        new Thread(() -> {
            try {
                run(c, intent.getBooleanExtra("bleed", false));
            } finally {
                done.finish();
            }
        }, "mic-probe").start();
    }

    private static final int MIC = MediaRecorder.AudioSource.MIC, CAM = MediaRecorder.AudioSource.CAMCORDER,
        VREC = MediaRecorder.AudioSource.VOICE_RECOGNITION, RAW = MediaRecorder.AudioSource.UNPROCESSED,
        VCOM = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    private static final int I16 = AudioFormat.ENCODING_PCM_16BIT, F32 = AudioFormat.ENCODING_PCM_FLOAT;
    private static final int MONO = AudioFormat.CHANNEL_IN_MONO, STEREO = AudioFormat.CHANNEL_IN_STEREO;

    /** name, source, encoding, channel mask, channel index mask */
    private static final Object[][] CONFIGS = {
        { "mic-16-mono", MIC, I16, MONO, 0 },
        { "raw-16-mono", RAW, I16, MONO, 0 },
        { "cam-16-mono", CAM, I16, MONO, 0 },
        { "vrec-16-mono", VREC, I16, MONO, 0 },
        { "mic-f32-mono", MIC, F32, MONO, 0 },
        { "raw-f32-mono", RAW, F32, MONO, 0 },
        { "mic-16-index6", MIC, I16, 0, 0x3F },
        { "raw-16-index6", RAW, I16, 0, 0x3F },
        { "cam-16-index6", CAM, I16, 0, 0x3F },
        { "mic-16-index4", MIC, I16, 0, 0xF },
        { "raw-16-index4", RAW, I16, 0, 0xF },
        { "cam-16-index3", CAM, I16, 0, 0x7 },
        { "raw-f32-index3", RAW, F32, 0, 0x7 },
    };

    private void run(Context c, boolean bleed) {
        File dir = c.getExternalFilesDir("exports");
        float[] drums = bleed ? drums() : null;
        for (Object[] cfg : CONFIGS) {
            String name = (String) cfg[0];
            int source = (Integer) cfg[1], enc = (Integer) cfg[2], mask = (Integer) cfg[3], index = (Integer) cfg[4];
            AudioRecord rec = null;
            try {
                AudioFormat.Builder fb = new AudioFormat.Builder().setEncoding(enc).setSampleRate(SR);
                if (index != 0) fb.setChannelIndexMask(index); else fb.setChannelMask(mask);
                rec = new AudioRecord.Builder().setAudioSource(source).setAudioFormat(fb.build())
                    .setBufferSizeInBytes(SR * 4 * 8).build();
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("not initialised");
                int ch = rec.getChannelCount();
                int frames = SR * 4;
                float[][] x = new float[ch][frames];
                AudioTrack track = drums == null ? null : play(drums);
                rec.startRecording();
                if (track != null) track.play();
                Log.i(TAG, name + ": recording");
                int got = 0;
                if (enc == F32) {
                    float[] buf = new float[1024 * ch];
                    while (got < frames) {
                        int n = rec.read(buf, 0, Math.min(buf.length, (frames - got) * ch), AudioRecord.READ_BLOCKING);
                        if (n <= 0) throw new IllegalStateException("read " + n);
                        for (int i = 0; i < n / ch; i++) for (int k = 0; k < ch; k++) x[k][got + i] = buf[i * ch + k];
                        got += n / ch;
                    }
                } else {
                    short[] buf = new short[1024 * ch];
                    while (got < frames) {
                        int n = rec.read(buf, 0, Math.min(buf.length, (frames - got) * ch));
                        if (n <= 0) throw new IllegalStateException("read " + n);
                        for (int i = 0; i < n / ch; i++) for (int k = 0; k < ch; k++) x[k][got + i] = buf[i * ch + k] / 32768f;
                        got += n / ch;
                    }
                }
                AudioDeviceInfo dev = rec.getRoutedDevice();
                rec.stop();
                if (track != null) track.release();
                StringBuilder b = new StringBuilder(name).append(": ").append(ch).append(" ch, source ")
                    .append(rec.getAudioSource()).append(", rate ").append(rec.getSampleRate())
                    .append(", device ").append(dev == null ? "?" : dev.getType() + "/" + dev.getAddress());
                for (int k = 0; k < ch; k++) b.append(" | ch").append(k).append(' ').append(stats(x[k]));
                if (ch > 1) b.append(String.format(java.util.Locale.US, " | ch0-ch1 differ by %.1f dB", differ(x[0], x[1])));
                if (drums != null) b.append(String.format(java.util.Locale.US, " | drums heard %.1f", hears(x[0], drums)));
                Log.i(TAG, b.toString());
                WavFile.write(new File(dir, "probe-" + name + ".wav"), x[0], SR);
            } catch (Exception e) {
                Log.i(TAG, name + ": unavailable (" + e.getMessage() + ")");
            } finally {
                if (rec != null) rec.release();
            }
        }
        Log.i(TAG, "done");
    }

    /** Kick on every beat at 120 BPM, a hat between, from 0.5 s: eight beats in four seconds. */
    private static float[] drums() {
        float[] s = new float[SR * 4];
        float[] kick = BeatKit.render("kick", SR), hat = BeatKit.render("hat", SR);
        for (int k = 0; k < 14; k++) {
            float[] x = k % 2 == 0 ? kick : hat;
            int at = SR / 2 + k * SR / 4;
            for (int i = 0; i < x.length && at + i < s.length; i++) s[at + i] += x[i] * 0.8f;
        }
        return s;
    }

    private static AudioTrack play(float[] s) {
        AudioTrack t = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(s.length * 4).setTransferMode(AudioTrack.MODE_STATIC).build();
        t.write(s, 0, s.length, AudioTrack.WRITE_BLOCKING);
        return t;
    }

    /**
     * How clearly the capture holds the drum pattern: the peak of the
     * correlation between the two loudness envelopes (1 ms steps, any lag up
     * to half a second) in standard deviations above its median. Above about 6
     * the speaker is plainly there; 1 to 3 is noise.
     */
    private static double hears(float[] x, float[] played) {
        double[] a = envelope(played), b = envelope(x);
        int lags = 500;
        double[] v = new double[lags];
        for (int lag = 0; lag < lags; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < b.length && i < a.length; i++) sum += a[i] * b[i + lag];
            v[lag] = sum;
        }
        double[] sorted = v.clone();
        java.util.Arrays.sort(sorted);
        double med = sorted[lags / 2], var = 0, max = sorted[lags - 1];
        for (double d : v) var += (d - med) * (d - med);
        return (max - med) / Math.sqrt(var / lags + 1e-30);
    }

    /** Mean-removed per-millisecond energy of the first difference (so voice rumble counts for less than hits). */
    private static double[] envelope(float[] x) {
        int blk = SR / 1000;
        double[] e = new double[x.length / blk];
        double mean = 0;
        for (int i = 0; i < e.length; i++) {
            double s = 0;
            for (int j = Math.max(1, i * blk); j < (i + 1) * blk; j++) { double d = x[j] - x[j - 1]; s += d * d; }
            e[i] = s;
            mean += s;
        }
        mean /= e.length;
        for (int i = 0; i < e.length; i++) e[i] -= mean;
        return e;
    }

    /** Energy of ch0 minus ch1 relative to ch0: very negative means the same signal twice. */
    private static double differ(float[] a, float[] b) {
        double d = 0, e = 0;
        for (int i = 0; i < a.length; i++) { double t = a[i] - b[i]; d += t * t; e += a[i] * a[i]; }
        return 10 * Math.log10(Math.max(d, 1e-30) / Math.max(e, 1e-30));
    }

    /** RMS and peak in dBFS, then energy above 4 kHz and above 7 kHz relative to the whole, in dB. */
    private static String stats(float[] x) {
        double all = 0, peak = 0;
        for (float v : x) { all += v * v; peak = Math.max(peak, Math.abs(v)); }
        if (all == 0) return "silent";
        double h4 = highEnergy(x, 4000), h7 = highEnergy(x, 7000);
        return String.format(java.util.Locale.US, "rms %.1f peak %.1f >4k %.1f >7k %.1f",
            10 * Math.log10(all / x.length), 20 * Math.log10(Math.max(peak, 1e-9)),
            10 * Math.log10(Math.max(h4, 1e-20) / all), 10 * Math.log10(Math.max(h7, 1e-20) / all));
    }

    private static double highEnergy(float[] x, double f) {
        BeatKit.Biquad a = BeatKit.Biquad.highpass(SR, f, 0.707), b = BeatKit.Biquad.highpass(SR, f, 0.707);
        double e = 0;
        for (float v : x) { double y = b.run(a.run(v)); e += y * y; }
        return e;
    }
}
