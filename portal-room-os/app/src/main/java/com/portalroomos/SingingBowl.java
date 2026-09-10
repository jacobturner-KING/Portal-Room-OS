package com.portalroomos;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * A struck singing bowl, synthesised rather than sampled so the repo carries no
 * audio blob and the tone stays tunable in source.
 *
 * What makes a bowl sound like metal rather than an organ is that its partials
 * are inharmonic: they sit at roughly 1, 2.7, 5.2, 8.4 and 12.4 times the
 * fundamental instead of whole multiples, and the high ones die away first, so
 * the strike is bright and the tail is pure. The slow wobble is real too. A bowl
 * is never perfectly round, so each partial actually rings as two frequencies a
 * fraction of a hertz apart, and their drift in and out of phase is the shimmer.
 * Each partial here is therefore a pair detuned by `beat` Hz.
 *
 * `tools/bowl_preview.py` reads the table below straight out of this file and
 * renders the same waveform to a WAV, so the tone can be auditioned on a laptop
 * without a rebuild and cannot drift from what the Portal plays.
 *
 * One bowl is shared by the whole process: both the timer screen and the
 * dashboard can strike it, and neither should pay to synthesise it twice.
 */
final class SingingBowl {

    private static SingingBowl instance;

    static synchronized SingingBowl shared() {
        if (instance == null) instance = new SingingBowl();
        return instance;
    }

    private SingingBowl() {}

    static final int SAMPLE_RATE = 44100;
    static final double SECONDS = 9.0;
    static final double F0 = 216.0;
    /** ratio to F0, gain, decay seconds, beat Hz */
    static final double[][] PARTIALS = {
        { 1.00, 1.00, 4.00, 0.5 },
        { 2.71, 0.52, 2.30, 0.9 },
        { 5.18, 0.26, 1.25, 1.4 },
        { 8.35, 0.12, 0.65, 1.9 },
        {12.40, 0.05, 0.30, 2.4 },
    };
    private static final double ATTACK_SECONDS = 0.006;
    private static final double FADE_SECONDS = 1.0;
    private static final double PEAK = 0.82;
    /**
     * Playback gain, applied by the track rather than baked into the samples so
     * the waveform keeps its full resolution. 0.5 is half amplitude, about 6 dB
     * below the media stream it rides on, so a struck bell sits under music rather
     * than on top of it. Raise toward 1.0 for a louder bell; tools/bowl_preview.py
     * folds the same figure in.
     */
    static final float VOLUME = 0.5f;

    private volatile short[] sample;
    private AudioTrack track;

    /** Build the waveform off the UI thread, well before anything needs it. */
    void prepareAsync() {
        if (sample != null) return;
        new Thread(() -> sample = render(), "singing-bowl").start();
    }

    void ring() {
        short[] ready = sample;
        if (ready != null) {
            play(ready);
        } else {
            // Not built yet: render and ring slightly late rather than not at all.
            new Thread(() -> {
                short[] built = render();
                sample = built;
                play(built);
            }, "singing-bowl").start();
        }
    }

    static short[] render() {
        int n = (int) (SECONDS * SAMPLE_RATE);
        double[] buffer = new double[n];

        for (double[] partial : PARTIALS) {
            double frequency = F0 * partial[0];
            double gain = partial[1];
            double decay = partial[2];
            double beat = partial[3];
            // The detuned pair that gives the partial its shimmer.
            double lowStep = 2 * Math.PI * (frequency - beat / 2) / SAMPLE_RATE;
            double highStep = 2 * Math.PI * (frequency + beat / 2) / SAMPLE_RATE;
            for (int i = 0; i < n; i++) {
                double t = (double) i / SAMPLE_RATE;
                double envelope = gain * Math.exp(-t / decay);
                buffer[i] += envelope * (Math.sin(lowStep * i) + Math.sin(highStep * i)) * 0.5;
            }
        }

        int attack = (int) (ATTACK_SECONDS * SAMPLE_RATE);
        for (int i = 0; i < attack && i < n; i++) {
            // Raised cosine in, so the strike does not start on a click.
            buffer[i] *= 0.5 - 0.5 * Math.cos(Math.PI * i / attack);
        }
        int fade = (int) (FADE_SECONDS * SAMPLE_RATE);
        for (int i = Math.max(0, n - fade); i < n; i++) {
            double p = (double) (n - i) / fade;
            buffer[i] *= 0.5 - 0.5 * Math.cos(Math.PI * p);
        }

        double loudest = 0;
        for (int i = 0; i < n; i++) loudest = Math.max(loudest, Math.abs(buffer[i]));
        double scale = loudest > 0 ? PEAK * Short.MAX_VALUE / loudest : 0;

        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = (short) Math.round(buffer[i] * scale);
        return out;
    }

    private synchronized void play(short[] data) {
        try {
            if (track == null) {
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
            } else {
                track.stop();
                track.reloadStaticData();
            }
            track.setVolume(VOLUME);
            track.play();
        } catch (Exception e) {
            // The speaker may be busy or the build may refuse the track; the
            // screen still shows the phase change, so stay quiet and carry on.
        }
    }
}
