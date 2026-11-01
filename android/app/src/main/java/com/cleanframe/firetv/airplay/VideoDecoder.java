/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Hardware H.264 decoding of the mirror stream. The decoder always runs, from the first picture,
 * on a hidden surface; the mirror screen attaches its own surface when it opens (and detaches
 * when it closes) with setOutputSurface, so no picture data is lost while the screen is not
 * shown and nothing waits for the sender's next key frame.
 */
package com.cleanframe.firetv.airplay;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

final class VideoDecoder implements Sinks.Video {
    interface Events {
        void videoSize(int width, int height);

        void videoPaused(boolean paused);
    }

    private static final String TAG = "CleanFrameAirPlay";
    private static final String AVC = "video/avc";

    private final Events events;
    private final SurfaceTexture hiddenTexture;
    private final Surface hidden;
    private final Object lock = new Object();
    private MediaCodec codec;
    private Thread drain;
    private Surface display;
    private byte[] sps, pps;
    private int width, height;
    private long baseNanos = -1;
    private boolean released, paused;
    private int dropped;

    VideoDecoder(Events events) {
        this.events = events;
        hiddenTexture = Build.VERSION.SDK_INT >= 26 ? new SurfaceTexture(false) : new SurfaceTexture(0);
        hidden = new Surface(hiddenTexture);
    }

    /** The mirror screen's surface, or null when it goes away. Called on the main thread. */
    void setDisplay(Surface surface) {
        synchronized (lock) {
            Surface next = surface != null && surface.isValid() ? surface : null;
            if (next == display) return;
            display = next;
            if (codec == null) return;
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    codec.setOutputSurface(display != null ? display : hidden);
                    return;
                } catch (RuntimeException e) {
                    Log.w(TAG, "setOutputSurface failed, restarting the decoder", e);
                }
            }
            // Android 5 cannot switch surfaces: start again (the picture recovers at the next key frame).
            if (sps != null) createCodec();
        }
    }

    boolean hasPicture() {
        synchronized (lock) {
            return codec != null && baseNanos >= 0;
        }
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    @Override
    public void configure(byte[] newSps, byte[] newPps, int w, int h) {
        synchronized (lock) {
            if (released) return;
            if (paused) {
                paused = false;
                events.videoPaused(false);
            }
            if (codec != null && Arrays.equals(newSps, sps) && Arrays.equals(newPps, pps)) return;
            sps = newSps.clone();
            pps = newPps.clone();
            width = w > 0 ? w : 1920;
            height = h > 0 ? h : 1080;
            createCodec();
        }
        events.videoSize(width, height);
    }

    private void createCodec() {
        releaseCodec();
        try {
            MediaFormat f = MediaFormat.createVideoFormat(AVC, width, height);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)));
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 << 20);
            if (Build.VERSION.SDK_INT >= 23) f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            MediaCodec c = MediaCodec.createDecoderByType(AVC);
            try {
                c.configure(f, display != null ? display : hidden, null, 0);
            } catch (RuntimeException lowLatencyRefused) {
                if (Build.VERSION.SDK_INT < 30) throw lowLatencyRefused;
                f.setInteger(MediaFormat.KEY_LOW_LATENCY, 0);
                c.release();
                c = MediaCodec.createDecoderByType(AVC);
                c.configure(f, display != null ? display : hidden, null, 0);
            }
            c.start();
            codec = c;
            final MediaCodec running = c;
            drain = new Thread(new Runnable() {
                @Override
                public void run() {
                    drainLoop(running);
                }
            }, "AirPlay-video-out");
            drain.start();
            Log.i(TAG, "video decoder " + width + "x" + height);
        } catch (Exception e) {
            Log.e(TAG, "could not start the H.264 decoder", e);
            codec = null;
        }
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] b = new byte[nal.length + 4];
        b[3] = 1;
        System.arraycopy(nal, 0, b, 4, nal.length);
        return b;
    }

    @Override
    public void frame(byte[] annexB, long arrivalNanos) {
        MediaCodec c;
        synchronized (lock) {
            c = codec;
            if (c == null || released) return;
            if (baseNanos < 0) baseNanos = arrivalNanos;
        }
        try {
            int index = c.dequeueInputBuffer(100000);
            if (index < 0) {
                if (++dropped % 30 == 1) Log.w(TAG, "decoder busy, dropped " + dropped + " pictures");
                return;
            }
            ByteBuffer in = Build.VERSION.SDK_INT >= 21 ? c.getInputBuffer(index) : null;
            if (in == null || in.capacity() < annexB.length) {
                c.queueInputBuffer(index, 0, 0, 0, 0);
                Log.w(TAG, "picture larger than the decoder's buffer: " + annexB.length);
                return;
            }
            in.clear();
            in.put(annexB);
            c.queueInputBuffer(index, 0, annexB.length, (arrivalNanos - baseNanos) / 1000, 0);
        } catch (IllegalStateException e) {
            // the codec was replaced or released meanwhile
        }
    }

    private void drainLoop(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            synchronized (lock) {
                if (codec != c) return;
            }
            int index;
            try {
                index = c.dequeueOutputBuffer(info, 20000);
            } catch (IllegalStateException e) {
                return;
            }
            if (index >= 0) {
                boolean render;
                synchronized (lock) {
                    render = display != null;
                }
                try {
                    c.releaseOutputBuffer(index, render);
                } catch (IllegalStateException e) {
                    return;
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    MediaFormat f = c.getOutputFormat();
                    int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey("crop-right") && f.containsKey("crop-left")) w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                    if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                    width = w;
                    height = h;
                    events.videoSize(w, h);
                } catch (RuntimeException ignored) {
                    // keep the size from the stream header
                }
            }
        }
    }

    @Override
    public void pause() {
        synchronized (lock) {
            if (paused) return;
            paused = true;
        }
        events.videoPaused(true);
    }

    @Override
    public void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
            releaseCodec();
        }
        hidden.release();
        hiddenTexture.release();
    }

    private void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        baseNanos = -1;
        if (c == null) return;
        try {
            c.stop();
        } catch (RuntimeException ignored) {
            // already stopped
        }
        c.release();
    }
}
