package com.portalroomos;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Just enough WAV for the beat machine: read whatever a laptop is likely to
 * hand over (8, 16, 24 or 32-bit integer, 32-bit float, any channel count,
 * any rate), folded to mono at the engine's rate; write 16-bit mono. The one
 * place the 16-bit mono header is written, for the recorder and push-to-talk too.
 */
final class WavFile {

    private WavFile() {}

    static float[] read(File f, int targetRate) throws IOException {
        byte[] all = new byte[(int) f.length()];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            in.readFully(all);
        }
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        if (all.length < 12 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) {   // "RIFF", "WAVE"
            throw new IOException("not a WAV file");
        }
        int format = 0, channels = 0, rate = 0, bits = 0, dataAt = -1, dataLen = 0;
        int pos = 12;
        while (pos + 8 <= all.length) {
            int id = b.getInt(pos), len = b.getInt(pos + 4);
            if (len < 0) break;
            if (id == 0x20746d66) {   // "fmt "
                format = b.getShort(pos + 8) & 0xffff;
                channels = b.getShort(pos + 10) & 0xffff;
                rate = b.getInt(pos + 12);
                bits = b.getShort(pos + 22) & 0xffff;
                if (format == 0xFFFE && len >= 26) format = b.getShort(pos + 32) & 0xffff;   // extensible: the GUID's first two bytes
            } else if (id == 0x61746164) {   // "data"
                dataAt = pos + 8;
                dataLen = Math.min(len, all.length - dataAt);
                break;
            }
            pos += 8 + len + (len & 1);
        }
        if (dataAt < 0 || channels == 0 || rate == 0) throw new IOException("WAV has no audio");
        if (format != 1 && format != 3) throw new IOException("compressed WAV (format " + format + ")");

        int bytes = bits / 8;
        int frames = dataLen / (bytes * channels);
        float[] mono = new float[frames];
        for (int i = 0; i < frames; i++) {
            double sum = 0;
            for (int c = 0; c < channels; c++) {
                int at = dataAt + (i * channels + c) * bytes;
                sum += sample(b, at, format, bits);
            }
            mono[i] = (float) (sum / channels);
        }
        return resample(mono, rate, targetRate);
    }

    private static double sample(ByteBuffer b, int at, int format, int bits) {
        if (format == 3) return bits == 64 ? b.getDouble(at) : b.getFloat(at);
        switch (bits) {
            case 8: return ((b.get(at) & 0xff) - 128) / 128.0;
            case 16: return b.getShort(at) / 32768.0;
            case 24: return ((b.get(at) & 0xff) | ((b.get(at + 1) & 0xff) << 8) | (b.get(at + 2) << 16)) / 8388608.0;
            case 32: return b.getInt(at) / 2147483648.0;
            default: return 0;
        }
    }

    /** Linear interpolation. Coarse, but these are drum hits and room takes. */
    static float[] resample(float[] in, int from, int to) {
        if (from == to || in.length == 0) return in;
        int n = (int) ((long) in.length * to / from);
        float[] out = new float[n];
        double step = (double) from / to;
        for (int i = 0; i < n; i++) {
            double x = i * step;
            int j = (int) x;
            double frac = x - j;
            float a = in[Math.min(j, in.length - 1)], c = in[Math.min(j + 1, in.length - 1)];
            out[i] = (float) (a + (c - a) * frac);
        }
        return out;
    }

    static final int HEADER_BYTES = 44;

    /** The 44-byte header of 16-bit mono PCM, written over the start of {@code out}. */
    static void putHeader(byte[] out, int dataLen, int rate) {
        ByteBuffer h = ByteBuffer.wrap(out, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0x46464952).putInt(36 + dataLen).putInt(0x45564157);
        h.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        h.putInt(0x61746164).putInt(dataLen);
    }

    /** 16-bit mono PCM behind its header, as one WAV file's bytes. */
    static byte[] fromPcm16(byte[] pcm, int rate) {
        byte[] out = new byte[HEADER_BYTES + pcm.length];
        putHeader(out, pcm.length, rate);
        System.arraycopy(pcm, 0, out, HEADER_BYTES, pcm.length);
        return out;
    }

    static void write(File f, float[] data, int rate) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        int dataLen = data.length * 2;
        byte[] header = new byte[HEADER_BYTES];
        putHeader(header, dataLen, rate);
        ByteBuffer body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : data) {
            float c = Math.max(-1f, Math.min(1f, x));
            body.putShort((short) Math.round(c * 32767));
        }
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
            out.write(header);
            out.write(body.array());
        }
    }
}
