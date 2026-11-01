/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The audio stream: RTP over UDP, each payload AES-128-CBC encrypted with the session key and
 * IV (whole 16-byte blocks only). AAC-ELD packets (mirroring) arrive up to three times; a small
 * reorder buffer delivers each sequence number once and in order, and asks the sender to resend
 * gaps through the control port. Layout as in UxPlay's raop_rtp.c and raop_buffer.c.
 */
package com.cleanframe.firetv.airplay;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class AudioReceiver {
    private static final int SLOTS = 512;
    private static final byte[] NO_DATA = {0x00, 0x68, 0x34, 0x00};

    private final SecretKeySpec key;
    private final IvParameterSpec iv;
    private final int compression, samplesPerFrame;
    private final InetAddress remote;
    private final int remoteControlPort;
    private final Sinks.Audio sink;
    private final AirPlayServer log;
    private final Cipher cbc;

    private DatagramSocket data, control;
    private volatile boolean stopped;
    private boolean released;

    /* reorder buffer, guarded by this */
    private final byte[][] slot = new byte[SLOTS][];
    private final int[] slotSeq = new int[SLOTS];
    private final long[] slotTime = new long[SLOTS];
    private int expected = -1, buffered, requestedUpTo = -1, ourSeq;
    private long delivered, received, lastReport;

    AudioReceiver(byte[] aesKey, byte[] aesIv, int compression, int samplesPerFrame, InetAddress remote, int remoteControlPort,
                  Sinks.Audio sink, AirPlayServer log) {
        this.key = new SecretKeySpec(Arrays.copyOf(aesKey, 16), "AES");
        this.iv = new IvParameterSpec(Arrays.copyOf(aesIv, 16));
        this.compression = compression;
        this.samplesPerFrame = samplesPerFrame;
        this.remote = remote;
        this.remoteControlPort = remoteControlPort;
        this.sink = sink;
        this.log = log;
        try {
            cbc = Cipher.getInstance("AES/CBC/NoPadding");
        } catch (Exception e) {
            throw new IllegalStateException("AES/CBC", e);
        }
    }

    /** Returns {dataPort, controlPort}. */
    int[] start() throws IOException {
        data = bind();
        control = bind();
        sink.start(compression, samplesPerFrame);
        thread(data, false, "AirPlay-audio");
        thread(control, true, "AirPlay-audio-control");
        return new int[] {data.getLocalPort(), control.getLocalPort()};
    }

    private static DatagramSocket bind() throws IOException {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setReceiveBufferSize(1 << 18);
        s.bind(new InetSocketAddress(0));
        return s;
    }

    private void thread(final DatagramSocket socket, final boolean isControl, String name) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[16384];
                while (!stopped) {
                    DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(p);
                    } catch (IOException e) {
                        break;
                    }
                    try {
                        if (isControl) controlPacket(p.getData(), p.getLength());
                        else packet(p.getData(), 0, p.getLength());
                    } catch (RuntimeException e) {
                        log.log("audio packet: " + e);
                    }
                }
            }
        }, name);
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    void stop() {
        stopped = true;
        if (data != null) data.close();
        if (control != null) control.close();
        synchronized (this) {
            if (released) return;
            released = true;
        }
        sink.release();
    }

    void volume(float db) {
        sink.volume(db);
    }

    synchronized void flush(int nextSeq) {
        Arrays.fill(slot, null);
        buffered = 0;
        expected = nextSeq >= 0 ? nextSeq & 0xffff : -1;
        requestedUpTo = -1;
        sink.flush();
    }

    synchronized long delivered() {
        return delivered;
    }

    private void controlPacket(byte[] d, int len) {
        if (len < 4) return;
        int type = d[1] & 0x7f;
        if (type == 0x56 && len >= 16) {
            packet(d, 4, len - 4); // a resent audio packet follows the 4-byte header
        }
        // 0x54 sync packets map RTP time to the sender's clock; media plays as it arrives.
    }

    private synchronized void packet(byte[] d, int off, int len) {
        if (stopped || len < 12) return;
        int seq = ((d[off + 2] & 0xff) << 8) | (d[off + 3] & 0xff);
        long now = System.currentTimeMillis();
        if (received++ == 0) {
            lastReport = now;
            log.log("first audio packet: seq " + seq + ", " + len + " bytes, ct=" + compression);
        } else if (now - lastReport >= 10000) {
            lastReport = now;
            log.log("audio: " + received + " packets received, " + delivered + " frames played");
        }
        long ts = ((d[off + 4] & 0xffL) << 24) | ((d[off + 5] & 0xff) << 16) | ((d[off + 6] & 0xff) << 8) | (d[off + 7] & 0xff);
        if (compression == 2 && len == 44) return; // ALAC format-only packets at the start
        byte[] payload;
        if (len == 12) {
            payload = NO_DATA;
        } else {
            payload = Arrays.copyOfRange(d, off + 12, off + len);
            int encrypted = payload.length / 16 * 16;
            if (encrypted > 0) {
                try {
                    cbc.init(Cipher.DECRYPT_MODE, key, iv);
                    cbc.doFinal(payload, 0, encrypted, payload, 0);
                } catch (Exception e) {
                    throw new IllegalStateException("audio decrypt", e);
                }
            }
        }
        if (expected < 0) expected = seq;
        int ahead = diff(seq, expected);
        if (ahead < 0 || ahead >= SLOTS) {
            if (ahead < -SLOTS / 2 || ahead >= SLOTS) {
                // the sender jumped (new song, seek): start over at this packet
                Arrays.fill(slot, null);
                buffered = 0;
                expected = seq;
            } else {
                return; // a copy of something already played
            }
        }
        int i = seq % SLOTS;
        if (slot[i] == null || slotSeq[i] != seq) {
            if (slot[i] == null) buffered++;
            slot[i] = payload;
            slotSeq[i] = seq;
            slotTime[i] = ts;
        }
        drain();
        if (buffered > 0 && slot[expected % SLOTS] == null) {
            requestResend();
            int limit = compression == 8 ? 24 : 12; // about 0.25 s of ELD, 0.1 s of ALAC
            while (buffered > limit) {
                expected = (expected + 1) & 0xffff;
                drain();
            }
        }
    }

    private void drain() {
        while (true) {
            int i = expected % SLOTS;
            if (slot[i] == null || slotSeq[i] != expected) return;
            byte[] p = slot[i];
            long ts = slotTime[i];
            slot[i] = null;
            buffered--;
            expected = (expected + 1) & 0xffff;
            if (p.length == 4 && Arrays.equals(p, NO_DATA)) continue;
            delivered++;
            sink.frame(p, ts);
        }
    }

    private boolean present(int seq) {
        int i = seq % SLOTS;
        return slot[i] != null && slotSeq[i] == seq;
    }

    /** Asks the sender once for the run of missing packets that blocks playback. */
    private void requestResend() {
        if (remoteControlPort <= 0 || control == null) return;
        int from = expected;
        if (requestedUpTo >= 0 && diff(requestedUpTo, from) >= 0) return; // already asked
        int count = 0;
        while (count < 64 && !present((from + count) & 0xffff)) count++;
        if (count == 0 || count >= 64) return;
        byte[] r = new byte[8];
        r[0] = (byte) 0x80;
        r[1] = (byte) (0x55 | 0x80);
        r[2] = (byte) (ourSeq >> 8);
        r[3] = (byte) ourSeq;
        ourSeq = (ourSeq + 1) & 0xffff;
        r[4] = (byte) (from >> 8);
        r[5] = (byte) from;
        r[6] = (byte) (count >> 8);
        r[7] = (byte) count;
        requestedUpTo = (from + count - 1) & 0xffff;
        try {
            control.send(new DatagramPacket(r, r.length, remote, remoteControlPort));
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Signed distance a - b between 16-bit sequence numbers. */
    static int diff(int a, int b) {
        int d = (a - b) & 0xffff;
        return d >= 0x8000 ? d - 0x10000 : d;
    }
}
