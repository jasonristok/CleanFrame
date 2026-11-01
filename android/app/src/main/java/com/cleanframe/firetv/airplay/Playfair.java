/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Java port of "playfair" (FairPlay SAP decryption, reverse-engineered by EstebanKubata),
 * as distributed in UxPlay lib/playfair (GPL-3.0). The large lookup tables are loaded from
 * airplay/playfair.bin, which scripts/airplay/dump_tables.c produces from UxPlay's omg_hax.h.
 * Structure and names follow the C original so the two can be compared line by line; the
 * desktop test runs both on random inputs and requires identical output.
 *
 * C semantics to keep in mind: buffers are unsigned bytes (held here as 0..255 ints, stored
 * through u8()), 32-bit words are little-endian views of those bytes, and "unsigned int"
 * variables use unsigned division and logical shifts.
 */
package com.cleanframe.firetv.airplay;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public final class Playfair {
    private final int[] messageKey, messageIv, zKey, xKey, tKey;
    private final int[] s1, s2, s3, s4, s5, s6, s7, s8, s9, s10;

    private static final int[] SAP_IV = {0x2B, 0x84, 0xFB, 0x79, 0xDA, 0x75, 0xB9, 0x04, 0x6C, 0x24, 0x73, 0xF7, 0xD1, 0xC4, 0xAB, 0x0E,
        0x2B, 0x84, 0xFB, 0x79, 0x75, 0xB9, 0x04, 0x6C, 0x24, 0x73};
    private static final int[] INDEX_MANGLE = {0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36, 0x6C};
    private static final int[] INITIAL_SESSION_KEY = {0xDC, 0xDC, 0xF3, 0xB9, 0x0B, 0x74, 0xDC, 0xFB, 0x86, 0x7F, 0xF7, 0x60, 0x16, 0x72, 0x90, 0x51};
    private static final int[] STATIC_SOURCE_1 = {0xFA, 0x9C, 0xAD, 0x4D, 0x4B, 0x68, 0x26, 0x8C, 0x7F, 0xF3, 0x88, 0x99, 0xDE, 0x92, 0x2E, 0x95, 0x1E};
    private static final int[] STATIC_SOURCE_2 = {0xEC, 0x4E, 0x27, 0x5E, 0xFD, 0xF2, 0xE8, 0x30, 0x97, 0xAE, 0x70, 0xFB, 0xE0, 0x00, 0x3F, 0x1C,
        0x39, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x09, 0x00, 0x0, 0x00, 0x00, 0x00, 0x00};
    private static final int[] DEFAULT_SAP = {
        0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79,
        0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79,
        0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79,
        0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79, 0x79,
        0x79, 0x79, 0x79, 0x79, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x03, 0x02, 0x53,
        0x00, 0x01, 0xcc, 0x34, 0x2a, 0x5e, 0x5b, 0x1a, 0x67, 0x73, 0xc2, 0x0e, 0x21, 0xb8, 0x22, 0x4d,
        0xf8, 0x62, 0x48, 0x18, 0x64, 0xef, 0x81, 0x0a, 0xae, 0x2e, 0x37, 0x03, 0xc8, 0x81, 0x9c, 0x23,
        0x53, 0x9d, 0xe5, 0xf5, 0xd7, 0x49, 0xbc, 0x5b, 0x7a, 0x26, 0x6c, 0x49, 0x62, 0x83, 0xce, 0x7f,
        0x03, 0x93, 0x7a, 0xe1, 0xf6, 0x16, 0xde, 0x0c, 0x15, 0xff, 0x33, 0x8c, 0xca, 0xff, 0xb0, 0x9e,
        0xaa, 0xbb, 0xe4, 0x0f, 0x5d, 0x5f, 0x55, 0x8f, 0xb9, 0x7f, 0x17, 0x31, 0xf8, 0xf7, 0xda, 0x60,
        0xa0, 0xec, 0x65, 0x79, 0xc3, 0x3e, 0xa9, 0x83, 0x12, 0xc3, 0xb6, 0x71, 0x35, 0xa6, 0x69, 0x4f,
        0xf8, 0x23, 0x05, 0xd9, 0xba, 0x5c, 0x61, 0x5f, 0xa2, 0x54, 0xd2, 0xb1, 0x83, 0x45, 0x83, 0xce,
        0xe4, 0x2d, 0x44, 0x26, 0xc8, 0x35, 0xa7, 0xa5, 0xf6, 0xc8, 0x42, 0x1c, 0x0d, 0xa3, 0xf1, 0xc7,
        0x00, 0x50, 0xf2, 0xe5, 0x17, 0xf8, 0xd0, 0xfa, 0x77, 0x8d, 0xfb, 0x82, 0x8d, 0x40, 0xc7, 0x8e,
        0x94, 0x1e, 0x1e, 0x1e};
    /* floor(|sin(i + 1)| * 2^32), the MD5 constants the C code computes at run time */
    private static final int[] MD5_K = {
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
        0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
        0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
        0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
        0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
        0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
        0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
        0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391};
    private static final int[] MD5_SHIFT = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};

    private Playfair(Map<String, int[]> t) {
        messageKey = need(t, "message_key", 576); messageIv = need(t, "message_iv", 64);
        zKey = need(t, "z_key", 16); xKey = need(t, "x_key", 16); tKey = need(t, "t_key", 16);
        s5 = need(t, "table_s5", 256); s6 = need(t, "table_s6", 256); s7 = need(t, "table_s7", 256); s8 = need(t, "table_s8", 256);
        s1 = need(t, "table_s1", 10240); s3 = need(t, "table_s3", 4096); s4 = need(t, "table_s4", 36864);
        s2 = need(t, "table_s2", 36864); s9 = need(t, "table_s9", 1024); s10 = need(t, "table_s10", 4096);
    }

    private static int[] need(Map<String, int[]> t, String name, int length) {
        int[] v = t.get(name);
        if (v == null || v.length != length) throw new IllegalArgumentException("playfair table " + name);
        return v;
    }

    /** Reads airplay/playfair.bin. */
    public static Playfair load(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != 'P' || magic[1] != 'L' || magic[2] != 'Y' || magic[3] != 'F') throw new IOException("not a playfair table file");
        Map<String, int[]> tables = new HashMap<String, int[]>();
        int n;
        while ((n = in.read()) > 0) {
            byte[] name = new byte[n];
            in.readFully(name);
            int size = in.readUnsignedByte();
            int count = Integer.reverseBytes(in.readInt());
            if (count < 0 || count > 1 << 20 || (size != 1 && size != 4)) throw new IOException("bad playfair table");
            int[] values = new int[count];
            for (int i = 0; i < count; i++) values[i] = size == 1 ? in.readUnsignedByte() : Integer.reverseBytes(in.readInt());
            tables.put(new String(name, "US-ASCII"), values);
        }
        return new Playfair(tables);
    }

    /** playfair_decrypt: message3 is the 164-byte fp-setup message, cipherText the 72-byte ekey. */
    public byte[] decrypt(byte[] message3, byte[] cipherText) {
        if (message3.length != 164 || cipherText.length != 72) throw new IllegalArgumentException("FairPlay input size");
        int[] msg = ints(message3), ct = ints(cipherText);
        int[] blockIn = new int[16], sapKey = new int[16], keyOut = new int[16];
        generateSessionKey(DEFAULT_SAP, msg, sapKey);
        int[][] keySchedule = generateKeySchedule(sapKey, 0);
        zXor(ct, 56, blockIn, 0, 1);
        cycle(blockIn, 0, keySchedule);
        for (int i = 0; i < 16; i++) keyOut[i] = blockIn[i] ^ ct[16 + i];
        xXor(keyOut, 0, keyOut, 0, 1);
        zXor(keyOut, 0, keyOut, 0, 1);
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) out[i] = (byte) keyOut[i];
        return out;
    }

    /* ---------- helpers for C semantics ---------- */
    static int u8(int v) { return v & 0xff; }
    static int udiv(int a, int b) { return Integer.divideUnsigned(a, b); }
    static int urem(int a, int b) { return Integer.remainderUnsigned(a, b); }
    /* index arithmetic on an int that C converts to unsigned 32 bits before "%" */
    static int umod(int a, int m) { return (int) ((a & 0xffffffffL) % m); }
    static int[] ints(byte[] b) { int[] v = new int[b.length]; for (int i = 0; i < b.length; i++) v[i] = b[i] & 0xff; return v; }
    static int getLe(int[] b, int off) { return b[off] | b[off + 1] << 8 | b[off + 2] << 16 | b[off + 3] << 24; }
    static void putLe(int[] b, int off, int v) { b[off] = v & 0xff; b[off + 1] = (v >>> 8) & 0xff; b[off + 2] = (v >>> 16) & 0xff; b[off + 3] = (v >>> 24) & 0xff; }

    /* ---------- omg_hax.c ---------- */
    private void zXor(int[] in, int inOff, int[] out, int outOff, int blocks) {
        for (int j = 0; j < blocks; j++) for (int i = 0; i < 16; i++) out[outOff + j * 16 + i] = in[inOff + j * 16 + i] ^ zKey[i];
    }
    private void xXor(int[] in, int inOff, int[] out, int outOff, int blocks) {
        for (int j = 0; j < blocks; j++) for (int i = 0; i < 16; i++) out[outOff + j * 16 + i] = in[inOff + j * 16 + i] ^ xKey[i];
    }
    private static int tableIndex(int i) { return ((31 * i) % 0x28) << 8; }
    private static int messageTableIndex(int i) { return (97 * i % 144) << 8; }
    private static int permuteTable2(int i) { return ((71 * i) % 144) << 8; }

    private void permuteBlock1(int[] block, int o) {
        block[o] = s3[block[o]];
        block[o + 4] = s3[0x400 + block[o + 4]];
        block[o + 8] = s3[0x800 + block[o + 8]];
        block[o + 12] = s3[0xc00 + block[o + 12]];
        int tmp = block[o + 13];
        block[o + 13] = s3[0x100 + block[o + 9]];
        block[o + 9] = s3[0xd00 + block[o + 5]];
        block[o + 5] = s3[0x900 + block[o + 1]];
        block[o + 1] = s3[0x500 + tmp];
        tmp = block[o + 2];
        block[o + 2] = s3[0xa00 + block[o + 10]];
        block[o + 10] = s3[0x200 + tmp];
        tmp = block[o + 6];
        block[o + 6] = s3[0xe00 + block[o + 14]];
        block[o + 14] = s3[0x600 + tmp];
        tmp = block[o + 3];
        block[o + 3] = s3[0xf00 + block[o + 7]];
        block[o + 7] = s3[0x300 + block[o + 11]];
        block[o + 11] = s3[0x700 + block[o + 15]];
        block[o + 15] = s3[0xb00 + tmp];
    }

    private void permuteBlock2(int[] block, int o, int round) {
        int r = round * 16;
        block[o] = s4[permuteTable2(r) + block[o]];
        block[o + 4] = s4[permuteTable2(r + 4) + block[o + 4]];
        block[o + 8] = s4[permuteTable2(r + 8) + block[o + 8]];
        block[o + 12] = s4[permuteTable2(r + 12) + block[o + 12]];
        int tmp = block[o + 13];
        block[o + 13] = s4[permuteTable2(r + 13) + block[o + 9]];
        block[o + 9] = s4[permuteTable2(r + 9) + block[o + 5]];
        block[o + 5] = s4[permuteTable2(r + 5) + block[o + 1]];
        block[o + 1] = s4[permuteTable2(r + 1) + tmp];
        tmp = block[o + 2];
        block[o + 2] = s4[permuteTable2(r + 2) + block[o + 10]];
        block[o + 10] = s4[permuteTable2(r + 10) + tmp];
        tmp = block[o + 6];
        block[o + 6] = s4[permuteTable2(r + 6) + block[o + 14]];
        block[o + 14] = s4[permuteTable2(r + 14) + tmp];
        tmp = block[o + 3];
        block[o + 3] = s4[permuteTable2(r + 3) + block[o + 7]];
        block[o + 7] = s4[permuteTable2(r + 7) + block[o + 11]];
        block[o + 11] = s4[permuteTable2(r + 11) + block[o + 15]];
        block[o + 15] = s4[permuteTable2(r + 15) + tmp];
    }

    int[][] generateKeySchedule(int[] keyMaterial, int off) {
        int[][] ks = new int[11][4];
        int[] buffer = new int[16];
        for (int i = 0; i < 16; i++) buffer[i] = keyMaterial[off + i] ^ tKey[i];
        int ti = 0;
        for (int round = 0; round < 11; round++) {
            ks[round][0] = getLe(buffer, 0);
            int t1 = tableIndex(ti), t2 = tableIndex(ti + 1), t3 = tableIndex(ti + 2), t4 = tableIndex(ti + 3);
            ti += 4;
            buffer[0] ^= s1[t1 + buffer[0x0d]] ^ INDEX_MANGLE[round];
            buffer[1] ^= s1[t2 + buffer[0x0e]];
            buffer[2] ^= s1[t3 + buffer[0x0f]];
            buffer[3] ^= s1[t4 + buffer[0x0c]];
            ks[round][1] = getLe(buffer, 4);
            putLe(buffer, 4, getLe(buffer, 4) ^ getLe(buffer, 0));
            ks[round][2] = getLe(buffer, 8);
            putLe(buffer, 8, getLe(buffer, 8) ^ getLe(buffer, 4));
            ks[round][3] = getLe(buffer, 12);
            putLe(buffer, 12, getLe(buffer, 12) ^ getLe(buffer, 8));
        }
        return ks;
    }

    private static int keyByte(int word, int index) { return (word >>> (8 * index)) & 0xff; }

    void cycle(int[] block, int o, int[][] ks) {
        for (int k = 0; k < 4; k++) putLe(block, o + 4 * k, getLe(block, o + 4 * k) ^ ks[10][k]);
        permuteBlock1(block, o);
        for (int round = 0; round < 9; round++) {
            int key0 = ks[9 - round][0], key1 = ks[9 - round][1], key2 = ks[9 - round][2], key3 = ks[9 - round][3];
            int ptr1 = s5[block[o + 3] ^ keyByte(key0, 3)];
            int ptr2 = s6[block[o + 2] ^ keyByte(key0, 2)];
            int ptr3 = s8[block[o] ^ keyByte(key0, 0)];
            int ptr4 = s7[block[o + 1] ^ keyByte(key0, 1)];
            putLe(block, o, ptr1 ^ ptr2 ^ ptr3 ^ ptr4);
            ptr2 = s5[block[o + 7] ^ keyByte(key1, 3)];
            ptr1 = s6[block[o + 6] ^ keyByte(key1, 2)];
            ptr4 = s7[block[o + 5] ^ keyByte(key1, 1)];
            ptr3 = s8[block[o + 4] ^ keyByte(key1, 0)];
            putLe(block, o + 4, ptr1 ^ ptr2 ^ ptr3 ^ ptr4);
            putLe(block, o + 8, s5[block[o + 11] ^ keyByte(key2, 3)] ^ s6[block[o + 10] ^ keyByte(key2, 2)]
                ^ s7[block[o + 9] ^ keyByte(key2, 1)] ^ s8[block[o + 8] ^ keyByte(key2, 0)]);
            putLe(block, o + 12, s5[block[o + 15] ^ keyByte(key3, 3)] ^ s6[block[o + 14] ^ keyByte(key3, 2)]
                ^ s7[block[o + 13] ^ keyByte(key3, 1)] ^ s8[block[o + 12] ^ keyByte(key3, 0)]);
            permuteBlock2(block, o, 8 - round);
        }
        for (int k = 0; k < 4; k++) putLe(block, o + 4 * k, getLe(block, o + 4 * k) ^ ks[0][k]);
    }

    void decryptMessage(int[] messageIn, int[] decryptedMessage) {
        int[] buffer = new int[16];
        int mode = messageIn[12];
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("FairPlay mode " + mode);
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 16; j++) {
                if (mode == 3) buffer[j] = messageIn[(0x80 - 0x10 * i) + j];
                else buffer[j] = messageIn[(0x10 * (i + 1)) + j];
            }
            for (int j = 0; j < 9; j++) {
                int base = 0x80 - 0x10 * j, mk = mode * 144 + base;
                buffer[0x0] = s2[messageTableIndex(base) + buffer[0x0]] ^ messageKey[mk];
                buffer[0x4] = s2[messageTableIndex(base + 0x4) + buffer[0x4]] ^ messageKey[mk + 0x4];
                buffer[0x8] = s2[messageTableIndex(base + 0x8) + buffer[0x8]] ^ messageKey[mk + 0x8];
                buffer[0xc] = s2[messageTableIndex(base + 0xc) + buffer[0xc]] ^ messageKey[mk + 0xc];
                int tmp = buffer[0x0d];
                buffer[0xd] = s2[messageTableIndex(base + 0xd) + buffer[0x9]] ^ messageKey[mk + 0xd];
                buffer[0x9] = s2[messageTableIndex(base + 0x9) + buffer[0x5]] ^ messageKey[mk + 0x9];
                buffer[0x5] = s2[messageTableIndex(base + 0x5) + buffer[0x1]] ^ messageKey[mk + 0x5];
                buffer[0x1] = s2[messageTableIndex(base + 0x1) + tmp] ^ messageKey[mk + 0x1];
                tmp = buffer[0x02];
                buffer[0x2] = s2[messageTableIndex(base + 0x2) + buffer[0xa]] ^ messageKey[mk + 0x2];
                buffer[0xa] = s2[messageTableIndex(base + 0xa) + tmp] ^ messageKey[mk + 0xa];
                tmp = buffer[0x06];
                buffer[0x6] = s2[messageTableIndex(base + 0x6) + buffer[0xe]] ^ messageKey[mk + 0x6];
                buffer[0xe] = s2[messageTableIndex(base + 0xe) + tmp] ^ messageKey[mk + 0xe];
                tmp = buffer[0x3];
                buffer[0x3] = s2[messageTableIndex(base + 0x3) + buffer[0x7]] ^ messageKey[mk + 0x3];
                buffer[0x7] = s2[messageTableIndex(base + 0x7) + buffer[0xb]] ^ messageKey[mk + 0x7];
                buffer[0xb] = s2[messageTableIndex(base + 0xb) + buffer[0xf]] ^ messageKey[mk + 0xb];
                buffer[0xf] = s2[messageTableIndex(base + 0xf) + tmp] ^ messageKey[mk + 0xf];
                int w0 = s9[buffer[0x0]] ^ s9[0x100 + buffer[0x1]] ^ s9[0x200 + buffer[0x2]] ^ s9[0x300 + buffer[0x3]];
                putLe(buffer, 0, w0);
                int w1 = s9[buffer[0x4]] ^ s9[0x100 + buffer[0x5]] ^ s9[0x200 + buffer[0x6]] ^ s9[0x300 + buffer[0x7]];
                putLe(buffer, 4, w1);
                int w2 = s9[buffer[0x8]] ^ s9[0x100 + buffer[0x9]] ^ s9[0x200 + buffer[0xa]] ^ s9[0x300 + buffer[0xb]];
                putLe(buffer, 8, w2);
                int w3 = s9[buffer[0xc]] ^ s9[0x100 + buffer[0xd]] ^ s9[0x200 + buffer[0xe]] ^ s9[0x300 + buffer[0xf]];
                putLe(buffer, 12, w3);
            }
            buffer[0x0] = s10[(0x0 << 8) + buffer[0x0]];
            buffer[0x4] = s10[(0x4 << 8) + buffer[0x4]];
            buffer[0x8] = s10[(0x8 << 8) + buffer[0x8]];
            buffer[0xc] = s10[(0xc << 8) + buffer[0xc]];
            int tmp = buffer[0x0d];
            buffer[0xd] = s10[(0xd << 8) + buffer[0x9]];
            buffer[0x9] = s10[(0x9 << 8) + buffer[0x5]];
            buffer[0x5] = s10[(0x5 << 8) + buffer[0x1]];
            buffer[0x1] = s10[(0x1 << 8) + tmp];
            tmp = buffer[0x02];
            buffer[0x2] = s10[(0x2 << 8) + buffer[0xa]];
            buffer[0xa] = s10[(0xa << 8) + tmp];
            tmp = buffer[0x06];
            buffer[0x6] = s10[(0x6 << 8) + buffer[0xe]];
            buffer[0xe] = s10[(0xe << 8) + tmp];
            tmp = buffer[0x3];
            buffer[0x3] = s10[(0x3 << 8) + buffer[0x7]];
            buffer[0x7] = s10[(0x7 << 8) + buffer[0xb]];
            buffer[0xb] = s10[(0xb << 8) + buffer[0xf]];
            buffer[0xf] = s10[(0xf << 8) + tmp];
            if (mode != 3) {
                for (int k = 0; k < 16; k++) decryptedMessage[0x10 * i + k] = buffer[k] ^ (i > 0 ? messageIn[0x10 * i + k] : messageIv[mode * 16 + k]);
            } else {
                int outOff = 0x70 - 0x10 * i;
                for (int k = 0; k < 16; k++) decryptedMessage[outOff + k] = buffer[k] ^ (i < 7 ? messageIn[0x70 - 0x10 * i + k] : messageIv[mode * 16 + k]);
            }
        }
    }

    void generateSessionKey(int[] oldSap, int[] messageIn, int[] sessionKey) {
        int[] decryptedMessage = new int[128], newSap = new int[320], md5 = new int[16];
        decryptMessage(messageIn, decryptedMessage);
        System.arraycopy(STATIC_SOURCE_1, 0, newSap, 0x000, 0x11);
        System.arraycopy(decryptedMessage, 0, newSap, 0x011, 0x80);
        System.arraycopy(oldSap, 0x80, newSap, 0x091, 0x80);
        System.arraycopy(STATIC_SOURCE_2, 0, newSap, 0x111, 0x2f);
        System.arraycopy(INITIAL_SESSION_KEY, 0, sessionKey, 0, 16);
        for (int round = 0; round < 5; round++) {
            int base = round * 64;
            modifiedMd5(newSap, base, sessionKey, md5);
            sapHash(newSap, base, sessionKey);
            for (int i = 0; i < 4; i++) putLe(sessionKey, 4 * i, getLe(sessionKey, 4 * i) + getLe(md5, 4 * i));
        }
        for (int i = 0; i < 16; i += 4) {
            int a = sessionKey[i], b = sessionKey[i + 1];
            sessionKey[i] = sessionKey[i + 3]; sessionKey[i + 3] = a;
            sessionKey[i + 1] = sessionKey[i + 2]; sessionKey[i + 2] = b;
        }
        for (int i = 0; i < 16; i++) sessionKey[i] ^= 121;
    }

    /* ---------- modified_md5.c ---------- */
    static void modifiedMd5(int[] original, int off, int[] keyIn, int[] keyOut) {
        int[] blockIn = new int[64];
        System.arraycopy(original, off, blockIn, 0, 64);
        int a = getLe(keyIn, 0), b = getLe(keyIn, 4), c = getLe(keyIn, 8), d = getLe(keyIn, 12);
        for (int i = 0; i < 64; i++) {
            int j;
            if (i < 16) j = i;
            else if (i < 32) j = (5 * i + 1) % 16;
            else if (i < 48) j = (3 * i + 5) % 16;
            else j = 7 * i % 16;
            int input = blockIn[4 * j] << 24 | blockIn[4 * j + 1] << 16 | blockIn[4 * j + 2] << 8 | blockIn[4 * j + 3];
            int z = a + input + MD5_K[i];
            int f;
            if (i < 16) f = (b & c) | (~b & d);
            else if (i < 32) f = (b & d) | (c & ~d);
            else if (i < 48) f = b ^ c ^ d;
            else f = c ^ (b | ~d);
            z = Integer.rotateLeft(z + f, MD5_SHIFT[i]);
            z = z + b;
            int tmp = d;
            d = c; c = b; b = z; a = tmp;
            if (i == 31) {
                swapWords(blockIn, a & 15, b & 15);
                swapWords(blockIn, c & 15, d & 15);
                swapWords(blockIn, (a & (15 << 4)) >>> 4, (b & (15 << 4)) >>> 4);
                swapWords(blockIn, (a & (15 << 8)) >>> 8, (b & (15 << 8)) >>> 8);
                swapWords(blockIn, (a & (15 << 12)) >>> 12, (b & (15 << 12)) >>> 12);
            }
        }
        putLe(keyOut, 0, getLe(keyIn, 0) + a);
        putLe(keyOut, 4, getLe(keyIn, 4) + b);
        putLe(keyOut, 8, getLe(keyIn, 8) + c);
        putLe(keyOut, 12, getLe(keyIn, 12) + d);
    }

    private static void swapWords(int[] block, int x, int y) {
        for (int k = 0; k < 4; k++) { int t = block[4 * x + k]; block[4 * x + k] = block[4 * y + k]; block[4 * y + k] = t; }
    }

    /* ---------- sap_hash.c ---------- */
    static int rol8(int input, int count) { input &= 0xff; return ((input << count) & 0xff) | input >> (8 - count); }
    static int rol8x(int input, int count) { input &= 0xff; return (input << count) | input >> (8 - count); }

    static void sapHash(int[] blockIn, int off, int[] keyOut) {
        int[] buffer0 = {0x96, 0x5F, 0xC6, 0x53, 0xF8, 0x46, 0xCC, 0x18, 0xDF, 0xBE, 0xB2, 0xF8, 0x38, 0xD7, 0xEC, 0x22, 0x03, 0xD1, 0x20, 0x8F};
        int[] buffer1 = new int[210];
        int[] buffer2 = {0x43, 0x54, 0x62, 0x7A, 0x18, 0xC3, 0xD6, 0xB3, 0x9A, 0x56, 0xF6, 0x1C, 0x14, 0x3F, 0x0C, 0x1D, 0x3B, 0x36, 0x83, 0xB1,
            0x39, 0x51, 0x4A, 0xAA, 0x09, 0x3E, 0xFE, 0x44, 0xAF, 0xDE, 0xC3, 0x20, 0x9D, 0x42, 0x3A};
        int[] buffer3 = new int[132];
        int[] buffer4 = {0xED, 0x25, 0xD1, 0xBB, 0xBC, 0x27, 0x9F, 0x02, 0xA2, 0xA9, 0x11, 0x00, 0x0C, 0xB3, 0x52, 0xC0, 0xBD, 0xE3, 0x1B, 0x49, 0xC7};
        int[] i0Index = {18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30};
        for (int i = 0; i < 210; i++) {
            int word = (i % 64) >> 2;
            buffer1[i] = blockIn[off + 4 * word + (3 - (i % 4))];
        }
        for (int i = 0; i < 840; i++) {
            int x = buffer1[umod(i - 155, 210)];
            int y = buffer1[umod(i - 57, 210)];
            int z = buffer1[umod(i - 13, 210)];
            int w = buffer1[umod(i, 210)];
            buffer1[i % 210] = (rol8(y, 5) + (rol8(z, 3) ^ w) - rol8(x, 7)) & 0xff;
        }
        garble(buffer0, buffer1, buffer2, buffer3, buffer4);
        for (int i = 0; i < 16; i++) keyOut[i] = 0xE1;
        for (int i = 0; i < 11; i++) {
            if (i == 3) keyOut[i] = 0x3d;
            else keyOut[i] = (keyOut[i] + buffer3[i0Index[i] * 4]) & 0xff;
        }
        for (int i = 0; i < 20; i++) keyOut[i % 16] ^= buffer0[i];
        for (int i = 0; i < 35; i++) keyOut[i % 16] ^= buffer2[i];
        for (int i = 0; i < 210; i++) keyOut[i % 16] ^= buffer1[i];
        for (int j = 0; j < 16; j++) {
            for (int i = 0; i < 16; i++) {
                int x = keyOut[umod(i - 7, 16)];
                int y = keyOut[i % 16];
                int z = keyOut[umod(i - 37, 16)];
                int w = keyOut[umod(i - 177, 16)];
                keyOut[i] = rol8(x, 1) ^ y ^ rol8(z, 6) ^ rol8(w, 5);
            }
        }
    }

    /* ---------- hand_garble.c ---------- */
    static int weird_ror8(int input, int count) { input &= 0xff; if (count == 0) return 0; return ((input >> count) & 0xff) | (input & 0xff) << (8 - count); }
    static int weird_rol8(int input, int count) { input &= 0xff; if (count == 0) return 0; return ((input << count) & 0xff) | (input & 0xff) >> (8 - count); }
    static int weird_rol32(int input, int count) { input &= 0xff; if (count == 0) return 0; return (input << count) ^ (input >> (8 - count)); }

    @SuppressWarnings("UnusedAssignment")
    static void garble(int[] b0, int[] b1, int[] b2, int[] b3, int[] b4) {
        int tmp, tmp2, tmp3;
        int A, B, C, D, E, M, J, G, F, H, K, R, S, T, U, V, W, X, Y, Z;
        b2[12] = u8(0x14 + (((b1[64] & 92) | ((b1[99] / 3) & 35)) & b4[rol8x(b4[(b1[206] % 21)],4) % 21]));
        b1[4] = u8((b1[99] / 5) * (b1[99] / 5) * 2);
        b2[34] = u8(0xb8);
        b1[153] = u8(b1[153] ^ ((b2[b1[203] % 35] * b2[b1[203] % 35] * b1[190])));
        b0[3] = u8(b0[3] - ((((b4[b1[205] % 21]>>1) & 80) | 0xe6440)));
        b0[16] = u8(0x93);
        b0[13] = u8(0x62);
        b1[33] = u8(b1[33] - ((b4[b1[36] % 21] & 0xf6)));
        tmp2 = b2[b1[67] % 35];
        b2[12] = u8(0x07);
        tmp = b0[b1[181] % 20];
        b1[2] = u8(b1[2] - (3136));
        b0[19] = u8(b4[b1[58] % 21]);
        b3[0] = u8(92 - b2[b1[32] % 35]);
        b3[4] = u8(b2[b1[15] % 35] + 0x9e);
        b1[34] = u8(b1[34] + ((b4[((b2[b1[15] % 35] + 0x9e) & 0xff) % 21] / 5)));
        b0[19] = u8(b0[19] + (0xfffffee6 - ((b0[b3[4] % 20]>>1) & 102)));
        b1[15] = u8((3*(((b1[72] >> (b4[b1[190] % 21] & 7)) ^ (b1[72] << ((7 - (b4[b1[190] % 21]-1)&7)))) - (3*b4[b1[126] % 21]))) ^ b1[15]);
        b0[15] = u8(b0[15] ^ (b2[b1[181] % 35] * b2[b1[181] % 35] * b2[b1[181] % 35]));
        b2[4] = u8(b2[4] ^ (b1[202]/3));
        A = 92 - b0[b3[0] % 20];
        E = (A & 0xc6) | (~b1[105] & 0xc6) | (A & (~b1[105]));
        b2[1] = u8(b2[1] + ((E*E*E)));
        b0[19] = u8(b0[19] ^ (((224 | (b4[b1[92] % 21] & 27)) * b2[b1[41] % 35]) / 3));
        b1[140] = u8(b1[140] + (weird_ror8(92, b1[5] & 7)));
        b2[12] = u8(b2[12] + (((((~b1[4]) ^ b2[b1[12] % 35]) | b1[182]) & 192) | (((~b1[4]) ^ b2[b1[12] % 35]) & b1[182])));
        b1[36] = u8(b1[36] + (125));
        b1[124] = u8(rol8x((((74 & b1[138]) | ((74 | b1[138]) & b0[15])) & b0[b1[43] % 20]) | (((74 & b1[138]) | ((74 | b1[138]) & b0[15]) | b0[b1[43] % 20]) & 95), 4));
        b3[8] = u8(((((b0[b3[4] % 20] & 95)) & ((b4[b1[68] % 21] & 46) << 1)) | 16) ^ 92);
        A = b1[177] + b4[b1[79] % 21];
        D = (((A >>> 1) | ((3 * b1[148]) / 5)) & b2[1]) | ((A >>> 1) & ((3 * b1[148])/5));
        b3[12] = u8(((-34 - D)));
        A = 8 - ((b2[22] & 7));
        B = (b1[33] >> (A & 7));
        C = b1[33] << (b2[22] & 7);
        b2[16] = u8(b2[16] + (((b2[b3[0] % 35] & 159) | b0[b3[4] % 20] | 8) - ((B^C) | 128)));
        b0[14] = u8(b0[14] ^ (b2[b3[12] % 35]));
        A = weird_rol8(b4[b0[b1[201] % 20] % 21], ((b2[b1[112] % 35] << 1) & 7));
        D = (b0[b1[208] % 20] & 131) | (b0[b1[164] % 20] & 124);
        b1[19] = u8(b1[19] + ((A & udiv(D, 5)) | ((A | udiv(D, 5)) & 37)));
        b2[8] = u8(weird_ror8(140, ((b4[b1[45] % 21] + 92) * (b4[b1[45] % 21] + 92)) & 7));
        b1[190] = u8(56);
        b2[8] = u8(b2[8] ^ (b3[0]));
        b1[53] = u8(~((b0[b1[83] % 20] | 204)/5));
        b0[13] = u8(b0[13] + (b0[b1[41] % 20]));
        b0[10] = u8(((b2[b3[0] % 35] & b1[2]) | ((b2[b3[0] % 35] | b1[2]) & b3[12])) / 15);
        A = (((56 | (b4[b1[2] % 21] & 68)) | b2[b3[8] % 35]) & 42) | (((b4[b1[2] % 21] & 68) | 56) & b2[b3[8] % 35]);
        b3[16] = u8((A*A) + 110);
        b3[20] = u8(202 - b3[16]);
        b3[24] = u8(b1[151]);
        b2[13] = u8(b2[13] ^ (b4[b3[0] % 21]));
        B = ((b2[b1[179] % 35] - 38) & 177) | (b3[12] & 177);
        C = ((b2[b1[179] % 35] - 38)) & b3[12];
        b3[28] = u8(30 + ((B | C) * (B | C)));
        b3[32] = u8(b3[28] + 62);
        A = ((b3[20] + (b3[0] & 74)) | ~b4[b3[0] % 21]) & 121;
        B = ((b3[20] + (b3[0] & 74)) & ~b4[b3[0] % 21]);
        tmp3 = (A|B);
        C = ((((A|B) ^ 0xffffffa6) | b3[0]) & 4) | (((A|B) ^ 0xffffffa6) & b3[0]);
        b1[47] = u8((b2[b1[89] % 35] + C) ^ b1[47]);
        b3[36] = u8(((rol8((tmp & 179) + 68, 2) & b0[3]) | (tmp2 & ~b0[3])) - 15);
        b1[123] = u8(b1[123] ^ (221));
        A = ((b4[b3[0] % 21]) / 3) - b2[b3[4] % 35];
        C = (((b3[0] & 163) + 92) & 246) | (b3[0] & 92);
        E = ((C | b3[24]) & 54) | (C & b3[24]);
        b3[40] = u8(A - E);
        b3[44] = u8(tmp3 ^ 81 ^ (((b3[0] >> 1) & 101) + 26));
        b3[48] = u8(b2[b3[4] % 35] & 27);
        b3[52] = u8(27);
        b3[56] = u8(199);
        b3[64] = u8(b3[4] + (((((((b3[40] | b3[24]) & 177) | (b3[40] & b3[24])) & ((((b4[b3[0] % 20] & 177) | 176)) | ((b4[b3[0] % 21]) & ~3))) | ((((b3[40] & b3[24]) | ((b3[40] | b3[24]) & 177)) & 199) | ((((b4[b3[0] % 21] & 1) + 176) | (b4[b3[0] % 21] & ~3)) & b3[56]))) & (~b3[52])) | b3[48]));
        b2[33] = u8(b2[33] ^ (b1[26]));
        b1[106] = u8(b1[106] ^ (b3[20] ^ 133));
        b2[30] = u8(((b3[64] / 3) - (275 | (b3[0] & 247))) ^ b0[b1[122] % 20]);
        b1[22] = u8((b2[b1[90] % 35] & 95) | 68);
        A = (b4[b3[36] % 21] & 184) | (b2[b3[44] % 35] & ~184);
        b2[18] = u8(b2[18] + (((A*A*A) >>> 1)));
        b2[5] = u8(b2[5] - (b4[b1[92] % 21]));
        A = (((b1[41] & ~24)|(b2[b1[183] % 35] & 24)) & (b3[16] + 53)) | (b3[20] & b2[b3[20] % 35]);
        B = (b1[17] & (~b3[44])) | (b0[b1[59] % 20] & b3[44]);
        b2[18] = u8(b2[18] ^ ((A*B)));
        A = weird_ror8(b1[11], b2[b1[28] % 35] & 7) & 7;
        B = (((b0[b1[93] % 20] & ~b0[14]) | (b0[14] & 150)) & ~28) | (b1[7] & 28);
        b2[22] = u8((((((B | weird_rol8(b2[b3[0] % 35], A)) & b2[33]) | (B & weird_rol8(b2[b3[0] % 35], A))) + 74) & 0xff));
        A = b4[(b0[b1[39] % 20] ^ 217) % 21];
        b0[15] = u8(b0[15] - (((((b3[20] | b3[0]) & 214) | (b3[20] & b3[0])) & A) | ((((b3[20] | b3[0]) & 214) | (b3[20] & b3[0]) | A) & b3[32])));
        B = (((b2[b1[57] % 35] & b0[b3[64] % 20]) | ((b0[b3[64] % 20] | b2[b1[57] % 35]) & 95) | (b3[64] & 45) | 82) & 32);
        C = ((b2[b1[57] % 35] & b0[b3[64] % 20]) | ((b2[b1[57] % 35] | b0[b3[64] % 20]) & 95)) & ((b3[64] & 45) | 82);
        D = ((((b3[0]/3) - (b3[64]|b1[22]))) ^ (b3[28] + 62) ^ ((B|C)));
        T = b0[(D & 0xff) % 20];
        b3[68] = u8((b0[b1[99] % 20] * b0[b1[99] % 20] * b0[b1[99] % 20] * b0[b1[99] % 20]) | b2[b3[64] % 35]);
        U = b0[b1[50] % 20];
        W = b2[b1[138] % 35];
        X = b4[b1[39] % 21];
        Y = b0[b1[4] % 20];
        Z = b4[b1[202] % 21];
        V = b0[b1[151] % 20];
        S = b2[b1[14] % 35];
        R = b0[b1[145] % 20];
        A = (b2[b3[68] % 35] & b0[b1[209] % 20]) | ((b2[b3[68] % 35] | b0[b1[209] % 20]) & 24);
        B = weird_rol8(b4[b1[127] % 21], b2[b3[68] % 35] & 7);
        C = (A & b0[10]) | (B & ~b0[10]);
        D = 7 ^ (b4[b2[b3[36] % 35] % 21] << 1);
        b3[72] = u8((C & 71) | (D & ~71));
        b2[2] = u8(b2[2] + ((((b0[b3[20] % 20] << 1) & 159) | (b4[b1[190] % 21] & ~159)) & ((((b4[b3[64] % 21] & 110) | (b0[b1[25] % 20] & ~110)) & ~150) | (b1[25] & 150))));
        b2[14] = u8(b2[14] - (((b2[b3[20] % 35] & (b3[72] ^ b2[b1[100] % 35])) & ~34) | (b1[97] & 34)));
        b0[17] = u8(115);
        b1[23] = u8(b1[23] ^ (((((((b4[b1[17] % 21] | b0[b3[20] % 20]) & b3[72]) | (b4[b1[17] % 21] & b0[b3[20] % 20])) & (b1[50]/3)) | ((((b4[b1[17] % 21] | b0[b3[20] % 20]) & b3[72]) | (b4[b1[17] % 21] & b0[b3[20] % 20]) | (b1[50] / 3)) & 246)) << 1)));
        b0[13] = u8(((((((b0[b3[40] % 20] | b1[10]) & 82) | (b0[b3[40] % 20] & b1[10])) & 209) | ((b0[b1[39] % 20] << 1) & 46)) >> 1));
        b2[33] = u8(b2[33] - (b1[113] & 9));
        b2[28] = u8(b2[28] - (((((2 | (b1[110] & 222)) >> 1) & ~223) | (b3[20] & 223))));
        J = weird_rol8((V | Z), (U & 7));
        A = (b2[16] & T) | (W & (~b2[16]));
        B = (b1[33] & 17) | (X & ~17);
        E = ((Y | udiv(A+B, 5)) & 147) | (Y & udiv(A+B, 5));
        M = (b3[40] & b4[((b3[8] + J + E) & 0xff) % 21]) | ((b3[40] | b4[((b3[8] + J + E) & 0xff) % 21]) & b2[23]);
        b0[15] = u8((((b4[b3[20] % 21] - 48) & (~b1[184])) | ((b4[b3[20] % 21] - 48) & 189) | (189 & ~b1[184])) & (M*M*M));
        b2[22] = u8(b2[22] + (b1[183]));
        b3[76] = u8((3 * b4[b1[1] % 21]) ^ b3[0]);
        A = b2[((b3[8] + (J + E)) & 0xff) % 35];
        F = (((b4[b1[178] % 21] & A) | ((b4[b1[178] % 21] | A) & 209)) * b0[b1[13] % 20]) * (b4[b1[26] % 21] >> 1);
        G = (F + 0x733ffff9) * 198 - (((F + 0x733ffff9) * 396 + 212) & 212) + 85;
        b3[80] = u8(b3[36] + (G ^ 148) + ((G ^ 107) << 1) - 127);
        b3[84] = u8(((b2[b3[64] % 35]) & 245) | (b2[b3[20] % 35] & 10));
        A = b0[b3[68] % 20] | 81;
        b2[18] = u8(b2[18] - (((A*A*A) & ~b0[15]) | ((b3[80] / 15) & b0[15])));
        b3[88] = u8(b3[8] + J + E - b0[b1[160] % 20] + (b4[b0[((b3[8] + J + E) & 255) % 20] % 21] / 3));
        B = ((R ^ b3[72]) & ~198) | ((S * S) & 198);
        F = (b4[b1[69] % 21] & b1[172]) | ((b4[b1[69] % 21] | b1[172] ) & ((b3[12] - B) + 77));
        b0[16] = u8(147 - ((b3[72] & ((F & 251) | 1)) | (((F & 250) | b3[72]) & 198)));
        C = (b4[b1[168] % 21] & b0[b1[29] % 20] & 7) | ((b4[b1[168] % 21] | b0[b1[29] % 20]) & 6);
        F = (b4[b1[155] % 21] & b1[105]) | ((b4[b1[155] % 21] | b1[105]) & 141);
        b0[3] = u8(b0[3] - (b4[weird_rol32(F, C) % 21]));
        b1[5] = u8(weird_ror8(b0[12], ((b0[b1[61] % 20] / 5) & 7)) ^ udiv(~b2[b3[84] % 35], 5));
        b1[198] = u8(b1[198] + (b1[3]));
        A = (162 | b2[b3[64] % 35]);
        b1[164] = u8(b1[164] + (((A*A)/5)));
        G = weird_ror8(139, (b3[80] & 7));
        C = ((b4[b3[64] % 21] * b4[b3[64] % 21] * b4[b3[64] % 21]) & 95) | (b0[b3[40] % 20] & ~95);
        b3[92] = u8((G & 12) | (b0[b3[20] % 20] & 12) | (G & b0[b3[20] % 20]) | C);
        b2[12] = u8(b2[12] + (((b1[103] & 32) | (b3[92] & ((b1[103] | 60))) | 16)/3));
        b3[96] = u8(b1[143]);
        b3[100] = u8(27);
        b3[104] = u8((((b3[40] & ~b2[8]) | (b1[35] & b2[8])) & b3[64]) ^ 119);
        b3[108] = u8(238 & ((((b3[40] & ~b2[8]) | (b1[35] & b2[8])) & b3[64]) << 1));
        b3[112] = u8((~b3[64] & (b3[84] / 3)) ^ 49);
        b3[116] = u8(98 & ((~b3[64] & (b3[84] / 3)) << 1));
        A = (b1[35] & b2[8]) | (b3[40] & ~b2[8]);
        B = (A & b3[64]) | (((b3[84] / 3) & ~b3[64]));
        b1[143] = u8(b3[96] - ((B & (86 + ((b1[172] & 64) >> 1))) | (((((b1[172] & 65) >> 1) ^ 86) | ((~b3[64] & (b3[84] / 3)) | (((b3[40] & ~b2[8]) | (b1[35] & b2[8])) & b3[64]))) & b3[100])));
        b2[29] = u8(162);
        A = ((((b4[b3[88] % 21]) & 160) | (b0[b1[125] % 20] & 95)) >> 1);
        B = b2[b1[149] % 35] ^ (b1[43] * b1[43]);
        b0[15] = u8(b0[15] + ((B&A) | ((A|B) & 115)));
        b3[120] = u8(b3[64] - b0[b3[40] % 20]);
        b1[95] = u8(b4[b3[20] % 21]);
        A = weird_ror8(b2[b3[80] % 35], (b2[b1[17] % 35] * b2[b1[17] % 35] * b2[b1[17] % 35]) & 7);
        b0[7] = u8(b0[7] - ((A*A)));
        b2[8] = u8(b2[8] - b1[184] + (b4[b1[202] % 21] * b4[b1[202] % 21] * b4[b1[202] % 21]));
        b0[16] = u8((b2[b1[102] % 35] << 1) & 132);
        b3[124] = u8((b4[b3[40] % 21] >> 1) ^ b3[68]);
        b0[7] = u8(b0[7] - ((b0[b1[191] % 20] - (((b4[b1[80] % 21] << 1) & ~177) | (b4[b4[b3[88] % 21] % 21] & 177)))));
        b0[6] = u8(b0[b1[119] % 20]);
        A = (b4[b1[190] % 21] & ~209) | (b1[118] & 209);
        B = b0[b3[120] % 20] * b0[b3[120] % 20];
        b0[12] = u8((b0[b3[84] % 20] ^ (b2[b1[71] % 35] + b2[b1[15] % 35])) & ((A & B) | ((A | B) & 27)));
        B = (b1[32] & b2[b3[88] % 35]) | ((b1[32] | b2[b3[88] % 35]) & 23);
        D = (((b4[b1[57] % 21] * 231) & 169) | (B & 86));
        F = (((b0[b1[82] % 20] & ~29) | (b4[b3[124] % 21] & 29)) & 190) | (b4[urem(udiv(D, 5), 21)] & ~190);
        H = b0[b3[40] % 20] * b0[b3[40] % 20] * b0[b3[40] % 20];
        K = (H & b1[82]) | (H & 92) | (b1[82] & 92);
        b3[128] = u8(((F & K) | ((F | K) & 192)) ^ udiv(D, 5));
        b2[25] = u8(b2[25] ^ (((b0[b3[120] % 20] << 1) * b1[5]) - (weird_rol8(b3[76], (b4[b3[124] % 21] & 7)) & (b3[20] + 110))));
    }
}
