package com.portalroomos;

import java.util.Random;

/**
 * The beat machine's built-in drum kit, synthesised the way the singing bowl
 * is, so the repo carries no audio and every sound is a few lines to retune.
 *
 * They are the classic analogue-machine recipes: a kick is a sine whose pitch
 * falls fast, a snare is a short tone under a burst of noise, hats are noise
 * plus a cluster of square waves at unrelated pitches (the 808's metal), and a
 * clap is three quick noise bursts and a tail. Noise comes from a fixed seed,
 * so a sound is identical every time it is built.
 */
final class BeatKit {

    static final String[] IDS = { "kick", "snare", "hat", "openhat", "clap", "rim", "tom", "shaker", "cowbell" };
    static final String[] NAMES = { "Kick", "Snare", "Hat", "Open hat", "Clap", "Rim", "Tom", "Shaker", "Cowbell" };

    /** The 808's six hat oscillators, in Hz. */
    private static final double[] METAL = { 205.3, 304.4, 369.6, 522.7, 540.0, 800.0 };

    private BeatKit() {}

    static String name(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return NAMES[i];
        return id;
    }

    /** The click the metronome and count-in use; the downbeat is higher. */
    static float[] click(int sr, boolean downbeat) {
        int n = (int) (0.04 * sr);
        float[] out = new float[n];
        double f = downbeat ? 2000 : 1500;
        for (int i = 0; i < n; i++) {
            double t = (double) i / sr;
            out[i] = (float) (Math.sin(2 * Math.PI * f * t) * Math.exp(-t / 0.012) * 0.8);
        }
        return out;
    }

    static float[] render(String id, int sr) {
        switch (id) {
            case "kick": return kick(sr);
            case "snare": return snare(sr);
            case "hat": return hat(sr, 0.11, 0.022);
            case "openhat": return hat(sr, 0.6, 0.22);
            case "clap": return clap(sr);
            case "rim": return rim(sr);
            case "tom": return tom(sr);
            case "shaker": return shaker(sr);
            case "cowbell": return cowbell(sr);
            default: return null;
        }
    }

    private static float[] kick(int sr) {
        int n = (int) (0.5 * sr);
        double[] b = new double[n];
        Random r = new Random(1);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / sr;
            double f = 46 + 120 * Math.exp(-t / 0.035);
            phase += 2 * Math.PI * f / sr;
            b[i] = Math.sin(phase) * Math.exp(-t / 0.2);
            if (t < 0.003) b[i] += (r.nextDouble() * 2 - 1) * 0.35 * (1 - t / 0.003);   // the beater
            b[i] = Math.tanh(b[i] * 1.6);
        }
        return finish(b, sr);
    }

    private static float[] snare(int sr) {
        int n = (int) (0.35 * sr);
        double[] b = new double[n];
        Random r = new Random(2);
        Biquad hp = Biquad.highpass(sr, 1200, 0.7);
        for (int i = 0; i < n; i++) {
            double t = (double) i / sr;
            double tone = (Math.sin(2 * Math.PI * 185 * t) + 0.5 * Math.sin(2 * Math.PI * 330 * t)) * Math.exp(-t / 0.05);
            double noise = hp.run(r.nextDouble() * 2 - 1) * Math.exp(-t / 0.11);
            b[i] = tone * 0.55 + noise;
        }
        return finish(b, sr);
    }

    private static float[] hat(int sr, double seconds, double decay) {
        int n = (int) (seconds * sr);
        double[] b = new double[n];
        Random r = new Random(3);
        Biquad hp = Biquad.highpass(sr, 7000, 0.7);
        for (int i = 0; i < n; i++) {
            double t = (double) i / sr;
            double metal = 0;
            for (double f : METAL) metal += Math.signum(Math.sin(2 * Math.PI * f * 1.6 * t));
            double x = metal / METAL.length * 0.6 + (r.nextDouble() * 2 - 1) * 0.5;
            b[i] = hp.run(x) * Math.exp(-t / decay);
        }
        return finish(b, sr);
    }

    private static float[] clap(int sr) {
        int n = (int) (0.4 * sr);
        double[] b = new double[n];
        Random r = new Random(4);
        Biquad bp = Biquad.bandpass(sr, 1200, 1.2);
        for (int i = 0; i < n; i++) {
            double t = (double) i / sr;
            double env;
            if (t < 0.03) env = Math.exp(-(t % 0.01) / 0.004);   // three hands, 10 ms apart
            else env = Math.exp(-(t - 0.03) / 0.11);
            b[i] = bp.run(r.nextDouble() * 2 - 1) * env;
        }
        return finish(b, sr);
    }

    private static float[] rim(int sr) {
        int n = (int) (0.08 * sr);
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / sr;
            b[i] = (Math.sin(2 * Math.PI * 1700 * t) + 0.6 * Math.sin(2 * Math.PI * 500 * t)) * Math.exp(-t / 0.011);
        }
        return finish(b, sr);
    }

    private static float[] tom(int sr) {
        int n = (int) (0.6 * sr);
        double[] b = new double[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = (double) i / sr;
            phase += 2 * Math.PI * (105 + 60 * Math.exp(-t / 0.05)) / sr;
            b[i] = Math.sin(phase) * Math.exp(-t / 0.22);
        }
        return finish(b, sr);
    }

    private static float[] shaker(int sr) {
        int n = (int) (0.15 * sr);
        double[] b = new double[n];
        Random r = new Random(5);
        Biquad hp = Biquad.highpass(sr, 5000, 0.7);
        for (int i = 0; i < n; i++) {
            double t = (double) i / sr;
            double env = t < 0.015 ? t / 0.015 : Math.exp(-(t - 0.015) / 0.045);
            b[i] = hp.run(r.nextDouble() * 2 - 1) * env;
        }
        return finish(b, sr);
    }

    private static float[] cowbell(int sr) {
        int n = (int) (0.4 * sr);
        double[] b = new double[n];
        Biquad bp = Biquad.bandpass(sr, 800, 1.5);
        for (int i = 0; i < n; i++) {
            double t = (double) i / sr;
            double x = Math.signum(Math.sin(2 * Math.PI * 540 * t)) + Math.signum(Math.sin(2 * Math.PI * 800 * t));
            b[i] = bp.run(x) * (0.6 * Math.exp(-t / 0.02) + 0.4 * Math.exp(-t / 0.15));
        }
        return finish(b, sr);
    }

    /** A 1 ms fade at each end so nothing starts or stops on a click, then to 0.9 peak. */
    private static float[] finish(double[] b, int sr) {
        int fade = Math.max(1, sr / 1000);
        for (int i = 0; i < fade && i < b.length; i++) b[b.length - 1 - i] *= (double) i / fade;
        double peak = 0;
        for (double x : b) peak = Math.max(peak, Math.abs(x));
        double scale = peak > 0 ? 0.9 / peak : 0;
        float[] out = new float[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (float) (b[i] * scale);
        return out;
    }

    /** RBJ cookbook filter, just the two shapes the kit needs. */
    static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0;
            this.a1 = a1 / a0; this.a2 = a2 / a0;
        }

        static Biquad highpass(int sr, double f, double q) {
            double w = 2 * Math.PI * f / sr, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
            return new Biquad((1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
        }

        static Biquad bandpass(int sr, double f, double q) {
            double w = 2 * Math.PI * f / sr, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
            return new Biquad(alpha, 0, -alpha, 1 + alpha, -2 * cos, 1 - alpha);
        }

        double run(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }
    }
}
