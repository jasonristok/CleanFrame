/* CleanFrame AirPlay receiver tests (desktop, see ../test.sh). SPDX-License-Identifier: GPL-3.0-or-later */
package com.cleanframe.firetv.airplay;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Decodes FFmpeg-encoded ALAC packets and compares every sample with FFmpeg's own decoder. */
public final class AlacTest {
    public static void main(String[] args) throws Exception {
        int fails = 0;
        for (String name : args) {
            Path dir = Paths.get(name).getParent();
            String base = Paths.get(name).getFileName().toString();
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(dir.resolve(base + ".packets"))));
            byte[] cookie = new byte[in.readInt()]; in.readFully(cookie);
            AlacDecoder dec = new AlacDecoder(cookie);
            ByteBuffer pcm = ByteBuffer.wrap(Files.readAllBytes(dir.resolve(base + ".pcm"))).order(ByteOrder.LITTLE_ENDIAN);
            short[] out = new short[dec.frameLength() * 2];
            long samples = 0, diffs = 0; int packets = 0, bad = 0;
            long t0 = System.nanoTime();
            while (in.available() > 0) {
                byte[] p = new byte[in.readInt()]; in.readFully(p);
                int n = dec.decode(p, 0, p.length, out);
                packets++;
                if (n < 0) { bad++; continue; }
                for (int i = 0; i < n * 2; i++) {
                    short want = pcm.getShort();
                    if (want != out[i]) diffs++;
                }
                samples += n;
            }
            double ms = (System.nanoTime() - t0) / 1e6;
            boolean ok = bad == 0 && diffs == 0 && !pcm.hasRemaining();
            System.out.printf("%s - %s: %d packets, %d samples, %d differing, %d damaged, %d bytes left, %.1f ms (%.0fx real time)%n",
                ok ? "ok" : "FAIL", base, packets, samples, diffs, bad, pcm.remaining(), ms, samples / 44.1 / ms);
            if (!ok) fails++;
        }
        // AirPlay's own cookie parses
        AlacDecoder air = new AlacDecoder(AlacDecoder.AIRPLAY_COOKIE);
        boolean cookieOk = air.frameLength() == 352 && air.channels() == 2;
        System.out.println((cookieOk ? "ok" : "FAIL") + " - AirPlay cookie: 352 samples, stereo");
        if (!cookieOk) fails++;
        // garbage never throws
        Random r = new Random(5); short[] out = new short[352 * 2]; int threw = 0;
        for (int i = 0; i < 20000; i++) { byte[] g = new byte[1 + r.nextInt(400)]; r.nextBytes(g); try { air.decode(g, 0, g.length, out); } catch (RuntimeException e) { threw++; } }
        System.out.println((threw == 0 ? "ok" : "FAIL") + " - 20000 random packets decode or fail without exceptions (" + threw + " threw)");
        if (threw != 0) fails++;
        System.exit(fails == 0 ? 0 : 1);
    }
}
