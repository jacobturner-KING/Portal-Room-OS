package com.portalroomos;

/**
 * Recent sound levels from a microphone, 0 (silence) to 1 (loud), one per
 * 50 ms, in a ring the capture thread writes and a {@link LevelMeterView}
 * reads. Written unsynchronised on purpose: a torn frame on a meter is
 * nothing, a lock on the capture loop is not.
 *
 * The recorder and the agents' push-to-talk each own one, so a meter shows
 * whichever is listening.
 */
final class LevelRing {
    /**
     * dB below full scale that counts as silence and as loud. Measured on the
     * Portal: a quiet room sits near -55, a voice a metre off runs -36 to -17.
     */
    private static final float DB_FLOOR = -60f, DB_CEIL = -12f;

    final float[] levels;
    /** Next write slot, which is also the oldest sample. */
    volatile int pos;

    LevelRing(int size) {
        levels = new float[size];
    }

    void clear() {
        java.util.Arrays.fill(levels, 0f);
    }

    /** One level per 50 ms of 16-bit mono PCM, RMS mapped onto the dB scale. */
    void push(byte[] pcm, int n, int sampleRate) {
        int block = sampleRate * 2 / 20;
        for (int start = 0; start + block <= n; start += block) {
            double sum = 0;
            for (int i = start; i < start + block; i += 2) {
                int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                sum += (double) sample * sample;
            }
            double rms = Math.sqrt(sum / (block / 2));
            float db = (float) (20 * Math.log10(Math.max(rms, 1) / 32768.0));
            levels[pos] = Math.max(0f, Math.min(1f, (db - DB_FLOOR) / (DB_CEIL - DB_FLOOR)));
            pos = (pos + 1) % levels.length;
        }
    }

    /** The newest level. */
    float latest() {
        int p = pos;
        return levels[(p + levels.length - 1) % levels.length];
    }
}
