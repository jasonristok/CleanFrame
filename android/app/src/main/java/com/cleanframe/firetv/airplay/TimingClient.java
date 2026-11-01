/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * AirPlay 1 timing: the receiver sends NTP-style requests (0xd2) to the sender's timing port
 * and the sender replies (0xd3) with its clock. Senders expect the requests; CleanFrame plays
 * media as it arrives, so the measured offset is only kept for diagnostics.
 */
package com.cleanframe.firetv.airplay;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

final class TimingClient {
    private static final long SECONDS_1900_TO_1970 = 2208988800L;

    private final InetAddress remote;
    private final int remotePort;
    private DatagramSocket socket;
    private volatile boolean stopped;
    private volatile long offsetNanos;
    private volatile int replies;

    TimingClient(InetAddress remote, int remotePort) {
        this.remote = remote;
        this.remotePort = remotePort;
    }

    int start() throws IOException {
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(0));
        socket.setSoTimeout(300);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "AirPlay-timing");
        t.setDaemon(true);
        t.start();
        return socket.getLocalPort();
    }

    void stop() {
        stopped = true;
        if (socket != null) socket.close();
        synchronized (this) {
            notifyAll();
        }
    }

    long offsetNanos() {
        return offsetNanos;
    }

    int replies() {
        return replies;
    }

    private void loop() {
        byte[] request = new byte[32];
        request[0] = (byte) 0x80;
        request[1] = (byte) 0xd2;
        request[3] = 0x07;
        byte[] buffer = new byte[128];
        int sent = 0;
        try {
            while (!stopped) {
                if (remotePort > 0) {
                    putNtp(request, 24, System.currentTimeMillis() * 1000000L);
                    socket.send(new DatagramPacket(request, request.length, remote, remotePort));
                    sent++;
                }
                long until = System.currentTimeMillis() + (sent <= 8 ? 250 : 3000);
                while (!stopped && System.currentTimeMillis() < until) {
                    DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(p);
                    } catch (SocketTimeoutException timeout) {
                        continue;
                    }
                    handle(p);
                }
            }
        } catch (IOException e) {
            // socket closed by stop()
        }
    }

    private void handle(DatagramPacket p) throws IOException {
        byte[] d = p.getData();
        if (p.getLength() < 32) return;
        int type = d[1] & 0x7f;
        long now = System.currentTimeMillis() * 1000000L;
        if (type == 0x53) {
            long t1 = ntpToNanos(d, 8), t2 = ntpToNanos(d, 16), t3 = ntpToNanos(d, 24);
            offsetNanos = ((t2 - t1) + (t3 - now)) / 2;
            replies++;
        } else if (type == 0x52) {
            // Some senders also ask us for the time: answer like an NTP server.
            byte[] reply = new byte[32];
            reply[0] = (byte) 0x80;
            reply[1] = (byte) 0xd3;
            reply[3] = 0x07;
            System.arraycopy(d, 24, reply, 8, 8);
            putNtp(reply, 16, now);
            putNtp(reply, 24, System.currentTimeMillis() * 1000000L);
            socket.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
        }
    }

    static void putNtp(byte[] b, int off, long nanosSince1970) {
        long seconds = nanosSince1970 / 1000000000L + SECONDS_1900_TO_1970;
        long fraction = ((nanosSince1970 % 1000000000L) << 32) / 1000000000L;
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (seconds >>> (24 - 8 * i));
        for (int i = 0; i < 4; i++) b[off + 4 + i] = (byte) (fraction >>> (24 - 8 * i));
    }

    static long ntpToNanos(byte[] b, int off) {
        long seconds = 0, fraction = 0;
        for (int i = 0; i < 4; i++) seconds = (seconds << 8) | (b[off + i] & 0xff);
        for (int i = 0; i < 4; i++) fraction = (fraction << 8) | (b[off + 4 + i] & 0xff);
        return (seconds - SECONDS_1900_TO_1970) * 1000000000L + ((fraction * 1000000000L) >>> 32);
    }
}
