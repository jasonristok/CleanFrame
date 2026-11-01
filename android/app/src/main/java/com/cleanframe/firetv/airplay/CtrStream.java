/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.cleanframe.firetv.airplay;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-128 in counter mode as one continuous keystream across calls of any length, the way the
 * mirror stream is encrypted. Built on AES/ECB so it behaves the same with every provider
 * (some buffer partial blocks in CTR mode); counter blocks are encrypted in bulk.
 */
final class CtrStream {
    private final Cipher ecb;
    private final byte[] counter;
    private final byte[] left = new byte[16];
    private int leftCount;
    private byte[] counters = new byte[0], keystream = new byte[0];

    CtrStream(byte[] key, byte[] iv) {
        try {
            ecb = Cipher.getInstance("AES/ECB/NoPadding");
            ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, 0, 16, "AES"));
        } catch (Exception e) {
            throw new IllegalStateException("AES", e);
        }
        counter = java.util.Arrays.copyOf(iv, 16);
    }

    /** XORs data[off, off+len) with the next len keystream bytes, in place. */
    void apply(byte[] data, int off, int len) {
        int i = 0;
        while (i < len && leftCount > 0) {
            data[off + i] ^= left[16 - leftCount];
            leftCount--;
            i++;
        }
        int rest = len - i;
        if (rest <= 0) return;
        int blocks = (rest + 15) / 16;
        int bytes = blocks * 16;
        if (counters.length < bytes) {
            counters = new byte[bytes];
            keystream = new byte[bytes];
        }
        for (int b = 0; b < blocks; b++) {
            System.arraycopy(counter, 0, counters, b * 16, 16);
            increment();
        }
        try {
            int n = ecb.update(counters, 0, bytes, keystream, 0);
            if (n != bytes) throw new IllegalStateException("AES/ECB returned " + n + " of " + bytes);
        } catch (javax.crypto.ShortBufferException e) {
            throw new IllegalStateException(e);
        }
        for (int k = 0; k < rest; k++) data[off + i + k] ^= keystream[k];
        int used = rest % 16;
        if (used != 0) {
            System.arraycopy(keystream, bytes - 16, left, 0, 16);
            leftCount = 16 - used;
        }
    }

    private void increment() {
        for (int k = 15; k >= 0; k--) {
            if (++counter[k] != 0) break;
        }
    }
}
