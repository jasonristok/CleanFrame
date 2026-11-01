/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Apple Lossless (ALAC) decoder for 16-bit mono/stereo, the format of AirPlay audio from the
 * Music app and most audio apps. Android has no ALAC decoder of its own. Follows the reference
 * algorithm (adaptive Golomb-Rice coding, adaptive LPC, stereo decorrelation) as in Apple's
 * open-source ALAC decoder (Apache-2.0) and FFmpeg's alac.c; checked sample-exact against
 * FFmpeg in the desktop tests.
 */
package com.cleanframe.firetv.airplay;

final class AlacDecoder {
    /** AirPlay's stream parameters: 352 samples per frame, 16 bit, pb 40, mb 10, kb 14, stereo. */
    static final byte[] AIRPLAY_COOKIE = {0, 0, 1, 0x60, 0, 16, 40, 10, 14, 2, 0, (byte) 255, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xac, 0x44};

    private final int frameLength, bitDepth, historyMult, initialHistory, riceLimit, channels;
    private final int[][] errors, samples;
    private final short[][] coefs = new short[2][32];
    private byte[] data;
    private int bitPos, bitEnd;

    /** @param config the 24-byte ALACSpecificConfig (an "alac" atom's payload after its 12-byte header also works) */
    AlacDecoder(byte[] config) {
        int o = config.length >= 36 && config[4] == 'a' && config[5] == 'l' && config[6] == 'a' && config[7] == 'c' ? 12 : 0;
        if (config.length < o + 24) throw new IllegalArgumentException("ALAC config");
        frameLength = be32(config, o);
        bitDepth = config[o + 5] & 0xff;
        historyMult = config[o + 6] & 0xff;
        initialHistory = config[o + 7] & 0xff;
        riceLimit = config[o + 8] & 0xff;
        channels = config[o + 9] & 0xff;
        if (frameLength <= 0 || frameLength > 1 << 16 || bitDepth != 16 || channels < 1 || channels > 2) {
            throw new IllegalArgumentException("unsupported ALAC stream: " + frameLength + " samples, " + bitDepth + " bit, " + channels + " channels");
        }
        errors = new int[2][frameLength];
        samples = new int[2][frameLength];
    }

    int channels() {
        return channels;
    }

    int frameLength() {
        return frameLength;
    }

    /**
     * Decodes one packet into interleaved 16-bit samples. Returns the number of samples per
     * channel, or -1 for a damaged packet (output then holds silence).
     */
    int decode(byte[] packet, int offset, int length, short[] out) {
        data = packet;
        bitPos = offset * 8;
        bitEnd = (offset + length) * 8;
        int produced = 0, channel = 0;
        try {
            while (bitEnd - bitPos >= 3) {
                int element = bits(3);
                if (element == 7) break; // end
                if (element != 0 && element != 1 && element != 3) return fail(out);
                int count = element == 1 ? 2 : 1;
                if (channel + count > channels) return fail(out);
                int n = element(count);
                if (n < 0 || (produced != 0 && n != produced)) return fail(out);
                produced = n;
                for (int c = 0; c < count; c++) {
                    int[] s = samples[c];
                    int ch = channel + c;
                    for (int i = 0; i < n; i++) out[i * channels + ch] = (short) s[i];
                }
                channel += count;
            }
        } catch (ArrayIndexOutOfBoundsException damaged) {
            return fail(out);
        }
        return channel == 0 ? 0 : produced;
    }

    private int fail(short[] out) {
        java.util.Arrays.fill(out, 0, Math.min(out.length, frameLength * channels), (short) 0);
        return -1;
    }

    private int element(int count) {
        bits(4);  // element instance tag
        bits(12); // unused
        boolean hasSize = bits(1) == 1;
        int extraBits = bits(2) << 3;
        int bps = bitDepth - extraBits + count - 1;
        if (bps > 32 || extraBits != 0) return -1; // 16-bit streams carry no extra low bits
        boolean compressed = bits(1) == 0;
        int n = hasSize ? (int) bitsLong(32) : frameLength;
        if (n <= 0 || n > frameLength) return -1;

        if (!compressed) {
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < count; c++) samples[c][i] = signed(bits(bitDepth), bitDepth);
            }
            return n;
        }
        if (riceLimit == 0) return -1;
        int shift = bits(8), leftWeight = bits(8);
        if (count == 2 && leftWeight != 0 && shift > 31) return -1;
        int[] type = new int[2], quant = new int[2], mult = new int[2], order = new int[2];
        for (int c = 0; c < count; c++) {
            type[c] = bits(4);
            quant[c] = bits(4);
            mult[c] = bits(3);
            order[c] = bits(5);
            if (order[c] >= frameLength || quant[c] == 0) return -1;
            for (int i = order[c] - 1; i >= 0; i--) coefs[c][i] = (short) bits(16);
        }
        for (int c = 0; c < count; c++) {
            if (!rice(errors[c], n, bps, mult[c] * historyMult / 4)) return -1;
            if (type[c] == 15) predict(errors[c], errors[c], n, bps, null, 31, 0);
            predict(errors[c], samples[c], n, bps, coefs[c], order[c], quant[c]);
        }
        if (count == 2 && leftWeight != 0) {
            int[] l = samples[0], r = samples[1];
            for (int i = 0; i < n; i++) {
                int a = l[i], b = r[i];
                a -= (b * leftWeight) >> shift;
                b += a;
                l[i] = b;
                r[i] = a;
            }
        }
        return n;
    }

    /* ---------- adaptive Golomb-Rice ---------- */

    private boolean rice(int[] out, int n, int bps, int mult) {
        long history = initialHistory; // unsigned 32-bit arithmetic, as in the reference
        int signModifier = 0;
        for (int i = 0; i < n; ) {
            if (bitPos >= bitEnd) return false;
            int k = Math.min(log2((int) (history >>> 9) + 3), riceLimit);
            long x = ((scalar(k, bps) & 0xffffffffL) + signModifier) & 0xffffffffL;
            signModifier = 0;
            out[i] = (int) (x >>> 1) ^ -(int) (x & 1);
            if (x > 0xffff) history = 0xffff;
            else history = (history + x * mult - (((history * mult) & 0xffffffffL) >>> 9)) & 0xffffffffL;

            if (history < 128 && i + 1 < n) {
                k = Math.min(7 - log2((int) history) + (int) ((history + 16) >> 6), riceLimit);
                int block = scalar(k, 16);
                if (block > 0) {
                    if (block >= n - i) block = n - i - 1;
                    for (int z = 1; z <= block; z++) out[i + z] = 0;
                    i += block;
                }
                if (block <= 0xffff) signModifier = 1;
                history = 0;
            }
            i++;
        }
        return true;
    }

    private int scalar(int k, int bps) {
        int x = 0;
        while (x < 9 && bits(1) == 1) x++;
        if (x > 8) return (int) bitsLong(bps);
        if (k != 1) {
            int extra = peek(k);
            x = (x << k) - x;
            if (extra > 1) {
                x += extra - 1;
                bitPos += k;
            } else {
                bitPos += k - 1;
            }
        }
        return x;
    }

    /* ---------- adaptive LPC ---------- */

    private static void predict(int[] err, int[] out, int n, int bps, short[] coef, int order, int quant) {
        out[0] = err[0];
        if (n <= 1) return;
        if (order == 0) {
            System.arraycopy(err, 1, out, 1, n - 1);
            return;
        }
        if (order == 31) {
            for (int i = 1; i < n; i++) out[i] = extend(out[i - 1] + err[i], bps);
            return;
        }
        int i;
        for (i = 1; i <= order && i < n; i++) out[i] = extend(out[i - 1] + err[i], bps);
        int p = 0;
        for (; i < n; i++) {
            int errorVal = err[i];
            int d = out[p++];
            int val = 0;
            for (int j = 0; j < order; j++) val += (out[p + j] - d) * coef[j];
            val = (int) (((long) val + (1L << (quant - 1))) >> quant);
            val += d + errorVal;
            out[i] = extend(val, bps);
            int errorSign = Integer.signum(errorVal);
            if (errorSign != 0) {
                for (int j = 0; j < order && errorVal * errorSign > 0; j++) {
                    int v = d - out[p + j];
                    int sign = Integer.signum(v) * errorSign;
                    coef[j] -= (short) sign;
                    v *= sign;
                    errorVal -= (v >> quant) * (j + 1);
                }
            }
        }
    }

    private static int extend(int v, int bits) {
        return bits >= 32 ? v : (v << (32 - bits)) >> (32 - bits);
    }

    private static int signed(int v, int bits) {
        return extend(v, bits);
    }

    /* ---------- bits, most significant first ---------- */

    private int bits(int n) {
        if (n == 0) return 0;
        int v = peek(n);
        bitPos += n;
        return v;
    }

    private long bitsLong(int n) {
        if (n <= 24) return bits(n) & 0xffffffffL;
        long hi = bits(n - 16) & 0xffffffffL;
        return (hi << 16) | bits(16);
    }

    /** The next n (at most 24) bits; reading past the end throws ArrayIndexOutOfBounds. */
    private int peek(int n) {
        if (bitPos + n > bitEnd) throw new ArrayIndexOutOfBoundsException("ALAC packet too short");
        int byteIndex = bitPos >>> 3, shift = bitPos & 7;
        long w = 0;
        for (int b = 0; b < 4; b++) {
            int idx = byteIndex + b;
            w = (w << 8) | (idx < data.length ? data[idx] & 0xff : 0);
        }
        return (int) (w >>> (32 - shift - n)) & ((1 << n) - 1);
    }

    private static int log2(int v) {
        return v <= 0 ? 0 : 31 - Integer.numberOfLeadingZeros(v);
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }
}
