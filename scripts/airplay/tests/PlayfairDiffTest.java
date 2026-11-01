/* CleanFrame AirPlay receiver tests (desktop, see ../test.sh). SPDX-License-Identifier: GPL-3.0-or-later */
package com.cleanframe.firetv.airplay;

import java.io.*;
import java.util.Arrays;

/** Compares the Java playfair port with vectors produced by the original C code. */
public final class PlayfairDiffTest {
    public static void main(String[] args) throws Exception {
        Playfair pf;
        try (InputStream in = new FileInputStream(args[0])) { pf = Playfair.load(in); }
        DataInputStream v = new DataInputStream(new BufferedInputStream(new FileInputStream(args[1])));
        int[] counts = new int[6], fails = new int[6];
        int type;
        while ((type = v.read()) >= 0) {
            counts[type]++;
            boolean ok;
            switch (type) {
                case 1: {
                    int[] a = read(v, 64), exp = read(v, 16), out = new int[16];
                    Playfair.sapHash(a, 0, out); ok = Arrays.equals(out, exp); break;
                }
                case 2: {
                    int[] a = read(v, 64), key = read(v, 16), exp = read(v, 16), out = new int[16];
                    Playfair.modifiedMd5(a, 0, key, out); ok = Arrays.equals(out, exp); break;
                }
                case 3: {
                    int[] a = read(v, 164), exp = read(v, 128), out = new int[128];
                    pf.decryptMessage(a, out); ok = Arrays.equals(out, exp); break;
                }
                case 4: {
                    int[] key = read(v, 16), block = read(v, 16), exp = read(v, 16);
                    pf.cycle(block, 0, pf.generateKeySchedule(key, 0)); ok = Arrays.equals(block, exp); break;
                }
                case 5: {
                    byte[] msg = bytes(v, 164), ekey = bytes(v, 72), exp = bytes(v, 16);
                    ok = Arrays.equals(pf.decrypt(msg, ekey), exp); break;
                }
                default: throw new IOException("bad vector type " + type);
            }
            if (!ok && fails[type]++ < 3) System.out.println("mismatch in type " + type + " case " + counts[type]);
        }
        String[] names = {"", "sap_hash/garble", "modified_md5", "decryptMessage", "key schedule + cycle", "playfair_decrypt"};
        boolean all = true;
        for (int t = 1; t <= 5; t++) { System.out.println(names[t] + ": " + (counts[t] - fails[t]) + "/" + counts[t] + " identical"); all &= fails[t] == 0; }
        System.exit(all ? 0 : 1);
    }
    static int[] read(DataInputStream in, int n) throws IOException { byte[] b = bytes(in, n); int[] r = new int[n]; for (int i = 0; i < n; i++) r[i] = b[i] & 0xff; return r; }
    static byte[] bytes(DataInputStream in, int n) throws IOException { byte[] b = new byte[n]; in.readFully(b); return b; }
}
