/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Plays the AirPlay audio stream: AAC-ELD (screen mirroring) and AAC-LC through Android's
 * MediaCodec, Apple Lossless (music) through AlacDecoder, raw PCM directly. One thread decodes
 * and writes to an AudioTrack; a short queue in front of it drops the oldest frames when it
 * falls behind, so sound stays close to the picture.
 */
package com.cleanframe.firetv.airplay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class AudioPlayer implements Sinks.Audio {
    private static final String TAG = "CleanFrameAirPlay";
    private static final int RATE = 44100;
    private static final byte[] ELD_CONFIG = {(byte) 0xf8, (byte) 0xe8, 0x50, 0x00};
    private static final byte[] LC_CONFIG = {0x12, 0x10};
    private static final byte[] FLUSH = new byte[0];

    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<byte[]>(400);
    private volatile boolean running;
    private volatile float gain = 1f;
    private int compression, samplesPerFrame;
    private Thread thread;

    @Override
    public void start(int ct, int spf) {
        compression = ct;
        samplesPerFrame = spf > 0 ? spf : ct == 8 ? 480 : ct == 2 ? 352 : 1024;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                try {
                    loop();
                } catch (RuntimeException e) {
                    Log.e(TAG, "audio stopped", e);
                }
            }
        }, "AirPlay-audio-out");
        thread.start();
    }

    @Override
    public void frame(byte[] data, long rtpTimestamp) {
        if (!running) return;
        while (!queue.offer(data)) queue.poll();
    }

    @Override
    public void volume(float db) {
        gain = db <= -100f ? 0f : (float) Math.min(1.0, Math.pow(10.0, db / 20.0));
        Log.i(TAG, "volume " + db + " dB -> gain " + gain);
    }

    @Override
    public void flush() {
        queue.clear();
        queue.offer(FLUSH);
    }

    @Override
    public void release() {
        running = false;
        queue.clear();
        if (thread != null) thread.interrupt();
    }

    /* ---------- the playback thread ---------- */

    private AudioTrack track;
    private int trackChannels;
    private float appliedGain = -1f;
    private MediaCodec codec;
    private int codecChoice;          // index into DECODERS of the decoder in use
    private long inputs, outputs, ptsUs;
    private AlacDecoder alac;
    private short[] pcm;
    private byte[] pcmBytes;
    /** Android's own (Fraunhofer) decoder handles AAC-ELD; some vendor decoders do not. null = the default. */
    private static final String[] DECODERS = {"c2.android.aac.decoder", "OMX.google.aac.decoder", null};

    private void loop() {
        try {
            if (compression == 8 || compression == 4) startCodec(0);
            else if (compression == 2) alac = new AlacDecoder(alacCookie(samplesPerFrame));
            // the most frames that may wait before older ones are dropped: about 0.3 s
            int maxQueued = Math.max(4, RATE * 3 / 10 / samplesPerFrame);
            while (running) {
                byte[] f;
                try {
                    f = queue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (f == null) continue;
                if (f == FLUSH) {
                    flushOutput();
                    continue;
                }
                if (queue.size() > maxQueued) {
                    while (queue.size() > maxQueued / 3) queue.poll();
                }
                decode(f);
            }
        } finally {
            releaseCodec();
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                } catch (RuntimeException ignored) {
                    // released below
                }
                track.release();
            }
        }
    }

    private static byte[] alacCookie(int frameLength) {
        byte[] c = AlacDecoder.AIRPLAY_COOKIE.clone();
        c[0] = (byte) (frameLength >>> 24);
        c[1] = (byte) (frameLength >>> 16);
        c[2] = (byte) (frameLength >>> 8);
        c[3] = (byte) frameLength;
        return c;
    }

    /** Starts the first AAC decoder from DECODERS[from] on that accepts the stream. */
    private void startCodec(int from) {
        releaseCodec();
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
        f.setByteBuffer("csd-0", ByteBuffer.wrap(compression == 8 ? ELD_CONFIG : LC_CONFIG));
        f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
        for (int i = from; i < DECODERS.length; i++) {
            String name = DECODERS[i];
            MediaCodec c = null;
            try {
                c = name != null ? MediaCodec.createByCodecName(name) : MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                c.configure(f, null, null, 0);
                c.start();
                codec = c;
                codecChoice = i;
                inputs = 0;
                outputs = 0;
                Log.i(TAG, "audio decoder " + c.getName() + " for ct=" + compression);
                return;
            } catch (Exception notThisOne) {
                Log.w(TAG, "audio decoder " + name + " refused ct=" + compression + ": " + notThisOne);
                if (c != null) c.release();
            }
        }
        Log.e(TAG, "no AAC decoder for ct=" + compression);
    }

    private void releaseCodec() {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // already stopped
        }
        codec.release();
        codec = null;
    }

    private void decode(byte[] f) {
        if (compression == 2 && alac != null) {
            int need = alac.frameLength() * alac.channels();
            if (pcm == null || pcm.length < need) pcm = new short[need];
            int n = alac.decode(f, 0, f.length, pcm);
            if (n > 0) writeShorts(pcm, n * alac.channels(), alac.channels());
        } else if (compression == 1) {
            // 16-bit big-endian PCM
            int n = f.length / 2;
            if (pcm == null || pcm.length < n) pcm = new short[n];
            for (int i = 0; i < n; i++) pcm[i] = (short) (((f[2 * i] & 0xff) << 8) | (f[2 * i + 1] & 0xff));
            writeShorts(pcm, n, 2);
        } else if (codec != null) {
            decodeAac(f);
        }
    }

    private void decodeAac(byte[] f) {
        try {
            int in = codec.dequeueInputBuffer(20000);
            if (in >= 0) {
                ByteBuffer b = codec.getInputBuffer(in);
                b.clear();
                int n = Math.min(f.length, b.remaining());
                b.put(f, 0, n);
                codec.queueInputBuffer(in, 0, n, ptsUs, 0);
                ptsUs += samplesPerFrame * 1000000L / RATE;
                inputs++;
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int out = codec.dequeueOutputBuffer(info, 0);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    int channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    Log.i(TAG, "audio output " + of.getInteger(MediaFormat.KEY_SAMPLE_RATE) + " Hz, " + channels + " channels");
                    if (channels != trackChannels) openTrack(channels);
                    continue;
                }
                if (out < 0) break;
                ByteBuffer o = codec.getOutputBuffer(out);
                if (o != null && info.size > 0) {
                    if (pcmBytes == null || pcmBytes.length < info.size) pcmBytes = new byte[info.size];
                    o.position(info.offset);
                    o.get(pcmBytes, 0, info.size);
                    if (outputs++ == 0) Log.i(TAG, "first decoded audio: " + info.size + " bytes after " + inputs + " frames");
                    writeBytes(pcmBytes, info.size, trackChannels == 0 ? 2 : trackChannels);
                }
                codec.releaseOutputBuffer(out, false);
            }
            if (outputs == 0 && inputs == 50) {
                // This decoder takes the frames but returns no sound: try the next one.
                Log.w(TAG, codec.getName() + " produced no audio from 50 frames; trying another decoder");
                if (codecChoice + 1 < DECODERS.length) startCodec(codecChoice + 1);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "AAC frame", e);
        }
    }

    private void flushOutput() {
        // A decoder flushed before its first output loses the stream configuration (csd), so the
        // codec is started afresh instead of flushed.
        if (codec != null) startCodec(codecChoice);
        if (track != null) {
            track.pause();
            track.flush();
            track.play();
        }
    }

    /**
     * Bytes for the AudioTrack buffer: at least 125 ms and twice the device minimum, rounded to
     * whole sample frames (Fire OS rejects a size that is not, with "Invalid audio buffer size").
     */
    static int bufferBytes(int deviceMinimum, int channels) {
        int frame = 2 * channels;
        int size = Math.max(deviceMinimum > 0 ? 2 * deviceMinimum : 0, RATE * frame / 8);
        return (size + frame - 1) / frame * frame;
    }

    private long trackFailedAt;

    /** Opens the output for this channel count; false (and a pause of 2 s before trying again) if the device refuses. */
    @SuppressWarnings("deprecation") // the plain constructor for Android 5 and as a fallback
    private boolean openTrack(int channels) {
        if (track != null) {
            track.release();
            track = null;
        }
        trackChannels = 0;
        if (System.currentTimeMillis() - trackFailedAt < 2000) return false;
        int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int size = bufferBytes(AudioTrack.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_16BIT), channels);
        AudioTrack t = null;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            } catch (RuntimeException e) {
                Log.w(TAG, "AudioTrack.Builder refused " + size + " bytes: " + e.getMessage());
            }
        }
        if (t == null) {
            try {
                t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, mask, AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
            } catch (RuntimeException e) {
                Log.w(TAG, "AudioTrack refused " + size + " bytes: " + e.getMessage());
            }
        }
        if (t == null || t.getState() != AudioTrack.STATE_INITIALIZED) {
            if (t != null) t.release();
            trackFailedAt = System.currentTimeMillis();
            Log.e(TAG, "no audio output for " + channels + " channels");
            return false;
        }
        track = t;
        trackChannels = channels;
        appliedGain = -1f;
        track.play();
        Log.i(TAG, "audio track " + RATE + " Hz, " + channels + " channels, buffer " + size + " bytes");
        return true;
    }

    private void applyGain() {
        float g = gain;
        if (g != appliedGain) {
            appliedGain = g;
            track.setVolume(g);
        }
    }

    private void writeShorts(short[] s, int count, int channels) {
        if ((track == null || trackChannels != channels) && !openTrack(channels)) return;
        applyGain();
        track.write(s, 0, count);
    }

    private void writeBytes(byte[] b, int count, int channels) {
        if ((track == null || trackChannels != channels) && !openTrack(channels)) return;
        applyGain();
        track.write(b, 0, count);
    }
}
