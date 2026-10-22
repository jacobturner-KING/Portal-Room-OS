package com.portalroomos;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * The pings an agent sends across the room: a rising pair of soft bells when
 * one finishes, the same high note twice when one needs an answer. Synthesised
 * like {@link SingingBowl}, so the repo carries no audio, and kept short and
 * high so they never read as the timer's bowl.
 */
final class AgentChime {

    enum Kind { DONE, NEEDS_YOU }

    private static final int SAMPLE_RATE = 44100;
    /** Same footing as the bowl: half amplitude, riding the media volume. */
    private static final float VOLUME = 0.5f;
    /** note Hz, start seconds; each a struck bell with a 0.5 s tail */
    private static final double[][] DONE = { { 784.0, 0.00 }, { 1174.7, 0.14 } };
    private static final double[][] NEEDS_YOU = { { 1046.5, 0.00 }, { 1046.5, 0.22 } };

    private static AgentChime instance;

    static synchronized AgentChime shared() {
        if (instance == null) instance = new AgentChime();
        return instance;
    }

    private final short[] done = render(DONE);
    private final short[] needsYou = render(NEEDS_YOU);
    private AudioTrack track;

    private AgentChime() {}

    private static short[] render(double[][] notes) {
        double length = 0;
        for (double[] n : notes) length = Math.max(length, n[1] + 0.9);
        int total = (int) (length * SAMPLE_RATE);
        double[] buf = new double[total];
        for (double[] note : notes) {
            int start = (int) (note[1] * SAMPLE_RATE);
            double step = 2 * Math.PI * note[0] / SAMPLE_RATE;
            for (int i = 0; start + i < total; i++) {
                double t = (double) i / SAMPLE_RATE;
                // A bell's bright second partial dies first; the 5 ms attack avoids a click.
                double env = Math.min(1.0, t / 0.005) * Math.exp(-t / 0.22);
                double bright = Math.exp(-t / 0.06);
                buf[start + i] += env * (Math.sin(step * i) + 0.35 * bright * Math.sin(2.76 * step * i));
            }
        }
        double peak = 0;
        for (double v : buf) peak = Math.max(peak, Math.abs(v));
        double scale = peak > 0 ? 0.8 * Short.MAX_VALUE / peak : 0;
        short[] out = new short[total];
        for (int i = 0; i < total; i++) out[i] = (short) Math.round(buf[i] * scale);
        return out;
    }

    synchronized void play(Kind kind) {
        short[] data = kind == Kind.DONE ? done : needsYou;
        try {
            if (track != null) {
                track.stop();
                track.release();
            }
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(data.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
            track.write(data, 0, data.length);
            track.setVolume(VOLUME);
            track.play();
        } catch (Exception e) {
            // A busy speaker loses the ping; the chip and banner still say it.
        }
    }
}
