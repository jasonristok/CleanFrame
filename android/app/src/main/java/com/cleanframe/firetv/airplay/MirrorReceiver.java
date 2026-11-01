/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The screen mirroring stream: one TCP connection carrying 128-byte headers, each followed by a
 * payload. Type 1 payloads hold the H.264 parameter sets (avcC, unencrypted); type 0 payloads
 * hold AES-CTR encrypted access units whose NAL units carry 4-byte lengths instead of start codes.
 * Layout as documented in UxPlay's raop_rtp_mirror.c.
 */
package com.cleanframe.firetv.airplay;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Arrays;

final class MirrorReceiver {
    interface Events {
        /** The first picture arrived. */
        void started();

        /** The sender closed the stream. */
        void ended();

        void log(String message);
    }

    private static final int MAX_PAYLOAD = 32 << 20;
    private static final byte[] START = {0, 0, 0, 1};

    private final byte[] aesKey;
    private final String connectionId;
    private final Sinks.Video video;
    private final Events events;
    private ServerSocket server;
    private volatile Socket stream;
    private volatile boolean stopped;
    private boolean released, started, paused;

    MirrorReceiver(byte[] aesKey, String connectionId, Sinks.Video video, Events events) {
        this.aesKey = aesKey.clone();
        this.connectionId = connectionId;
        this.video = video;
        this.events = events;
    }

    /** The stream key and IV: SHA-512("AirPlayStreamKey" + id || aesKey) and the same with "AirPlayStreamIV". */
    static CtrStream cipher(byte[] aesKey, String connectionId) {
        Charset ascii = Charset.forName("US-ASCII");
        byte[] k = Curve25519.sha512(("AirPlayStreamKey" + connectionId).getBytes(ascii), aesKey);
        byte[] iv = Curve25519.sha512(("AirPlayStreamIV" + connectionId).getBytes(ascii), aesKey);
        return new CtrStream(Arrays.copyOf(k, 16), Arrays.copyOf(iv, 16));
    }

    int start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(0), 2);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "AirPlay-mirror");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    void stop() {
        stopped = true;
        AirPlayServer.closeQuietly(server);
        AirPlayServer.closeQuietly(stream);
        release();
    }

    private synchronized void release() {
        if (released) return;
        released = true;
        try {
            video.release();
        } catch (RuntimeException e) {
            events.log("video release: " + e);
        }
    }

    private void loop() {
        try {
            while (!stopped) {
                Socket s = server.accept();
                AirPlayServer.closeQuietly(stream);
                stream = s;
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(1 << 20);
                read(new BufferedInputStream(s.getInputStream(), 1 << 16), cipher(aesKey, connectionId));
                AirPlayServer.closeQuietly(s);
                if (!stopped) {
                    events.log("mirror stream closed by the sender");
                    break;
                }
            }
        } catch (IOException e) {
            if (!stopped) events.log("mirror stream: " + e.getMessage());
        } finally {
            AirPlayServer.closeQuietly(server);
            boolean wasStopped = stopped;
            release();
            if (!wasStopped) events.ended();
        }
    }

    private void read(InputStream in, CtrStream ctr) throws IOException {
        byte[] header = new byte[128];
        while (!stopped) {
            if (!readFully(in, header)) return;
            long size = le32(header, 0);
            if (size < 0 || size > MAX_PAYLOAD) throw new IOException("bad mirror payload size " + size);
            byte[] payload = new byte[(int) size];
            if (!readFully(in, payload)) return;
            int type = header[4] & 0xff;
            if (type == 0x00) {
                ctr.apply(payload, 0, payload.length);
                if (!toAnnexB(payload)) {
                    events.log("dropped an undecodable video packet");
                    continue;
                }
                video.frame(payload, System.nanoTime());
                if (!started) {
                    started = true;
                    events.started();
                }
            } else if (type == 0x01) {
                int option = header[6] & 0xff;
                if (option == 0x56 || option == 0x5e) {
                    if (!paused) {
                        paused = true;
                        video.pause();
                    }
                    continue;
                }
                paused = false;
                parameterSets(header, payload);
            }
            // 0x02 (old once-per-second packets) and 0x05 (sender statistics) carry no picture
        }
    }

    private void parameterSets(byte[] header, byte[] p) {
        if (p.length >= 8 && p[4] == 'h' && p[5] == 'v' && p[6] == 'c' && p[7] == '1') {
            events.log("HEVC stream not supported");
            return;
        }
        if (p.length < 11) return;
        int spsSize = be16(p, 6);
        if (8 + spsSize + 3 > p.length) return;
        int ppsSize = be16(p, spsSize + 9);
        if (spsSize + 11 + ppsSize > p.length) return;
        byte[] sps = Arrays.copyOfRange(p, 8, 8 + spsSize);
        byte[] pps = Arrays.copyOfRange(p, spsSize + 11, spsSize + 11 + ppsSize);
        int width = Math.round(Float.intBitsToFloat((int) le32(header, 56)));
        int height = Math.round(Float.intBitsToFloat((int) le32(header, 60)));
        events.log("video " + width + "x" + height);
        video.configure(sps, pps, width, height);
    }

    /** Replaces each 4-byte NAL length with a start code. False if the lengths do not add up. */
    static boolean toAnnexB(byte[] p) {
        int pos = 0;
        while (pos < p.length) {
            if (pos + 4 > p.length) return false;
            long n = ((p[pos] & 0xffL) << 24) | ((p[pos + 1] & 0xff) << 16) | ((p[pos + 2] & 0xff) << 8) | (p[pos + 3] & 0xff);
            if (n > p.length - pos - 4) return false;
            System.arraycopy(START, 0, p, pos, 4);
            pos += 4;
            if (n > 0 && (p[pos] & 0x80) != 0) return false; // forbidden_zero_bit
            pos += (int) n;
        }
        return pos == p.length;
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                if (off == 0) return false;
                throw new IOException("mirror stream ended inside a packet");
            }
            off += n;
        }
        return true;
    }

    static long le32(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8) | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }

    static int be16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }
}
