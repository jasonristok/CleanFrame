/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.cleanframe.firetv.airplay;

/** Where decrypted media goes. Android implements these with MediaCodec; tests write files. */
public final class Sinks {
    private Sinks() {}

    /** H.264 in Annex-B form (start codes), one access unit per call. */
    public interface Video {
        /** Sequence and picture parameter sets without start codes, plus the picture size from the stream header. */
        void configure(byte[] sps, byte[] pps, int width, int height);

        void frame(byte[] annexB, long arrivalNanos);

        /** The sender paused the stream (for example, the phone's screen locked). */
        void pause();

        void release();
    }

    /** Compressed audio frames: ct 8 = AAC-ELD (mirroring), 2 = ALAC, 4 = AAC-LC; 44.1 kHz stereo. */
    public interface Audio {
        void start(int compressionType, int samplesPerFrame);

        void frame(byte[] data, long rtpTimestamp);

        /** AirPlay volume in dB: -144 is mute, otherwise -30 (quiet) to 0 (full). */
        void volume(float db);

        void flush();

        void release();
    }

    public interface Factory {
        Video video();

        Audio audio();
    }
}
