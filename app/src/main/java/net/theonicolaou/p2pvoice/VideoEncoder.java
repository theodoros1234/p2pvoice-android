package net.theonicolaou.p2pvoice;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEncoder {
    public static class UnsupportedFormat extends Exception {}
    public static class EncoderFailed extends Exception {}

    public abstract static class StatsListener {
        public abstract void onBitrateChange(int bitrate);
    }

    private static final String TAG = "VideoEncoder";
    private static final MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
    private static final int min_bitrate = 400000, max_bitrate = 6000000, bitrate_step = 100000;

    private final MediaCodec encoder;
    private final Handler upstream_thread;
    private final StatsListener stats_listener;
    private Thread thread;
    private final MediaFormat format;
    private final Surface input_surface;
    private boolean configured = false, started = false, released = false;
    private int bitrate;
    private ConnectionMessagePipe pipe_out = null;

    public static boolean checkInputSurfaceCompatibility(String mime, int width, int height) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        String codec = list.findEncoderForFormat(format);
        MediaCodecInfo codec_info = null;
        for (MediaCodecInfo i : list.getCodecInfos()) {
            if (i.getName().equals(codec)) {
                codec_info = i;
                break;
            }
        }
        if (codec_info == null)
            throw new RuntimeException("Failed to check input surface compatibility");

        for (int color_format : codec_info.getCapabilitiesForType(mime).colorFormats)
            if (color_format == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                return true;

        return false;
    }

    VideoEncoder(String mime, int width, int height, int fps, int bitrate, @NonNull Surface input_surface, @NonNull StatsListener stats_listener) throws UnsupportedFormat, EncoderFailed {
        Log.d(TAG, "Initialized with mime=" + mime + " width=" + width + " height=" + height + " fps=" + fps + " bitrate=" + bitrate + " input from surface");
        this.input_surface = input_surface;
        this.bitrate = bitrate;
        this.stats_listener = stats_listener;
        this.upstream_thread = new Handler(Looper.getMainLooper());
        format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        String codec = list.findEncoderForFormat(format);
        if (codec == null) {
            throw new UnsupportedFormat();
        }
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, fps);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, fps);
        try {
            encoder = MediaCodec.createByCodecName(codec);
        } catch (IOException e) {
            throw new EncoderFailed();
        }

        configure();
    }

    private void configure() {
        thread = new Thread(() -> {
            Log.i(TAG, "Output buffer thread is running.");
            while (true) {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int index = encoder.dequeueOutputBuffer(info, -1);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                    break;
                if (index < 0)
                    continue;

                ByteBuffer buffer = encoder.getOutputBuffer(index);
                if (buffer != null) {
                    byte[] frame = new byte[info.size];
                    buffer.position(info.offset);
                    buffer.get(frame, 0, info.size);
                    encoder.releaseOutputBuffer(index, false);

                    if (!pipe_out.send(Connection.DATA_VIDEO, frame)) {
                        Log.d(TAG, "Pipe closed, finishing early.");
                        break;
                    }

                    switch (pipe_out.getRateHint()) {
                        case ConnectionMessagePipe.RATE_OVERFLOW:
                            if (bitrate > min_bitrate) {
                                bitrate = Math.max(bitrate - bitrate_step, min_bitrate);
                                Bundle new_param = new Bundle();
                                new_param.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
                                encoder.setParameters(new_param);
                                upstream_thread.post(() -> stats_listener.onBitrateChange(bitrate));
                            }
                            break;

                        case ConnectionMessagePipe.RATE_UNDERFLOW:
                            if (bitrate < max_bitrate) {
                                bitrate = Math.min(bitrate + bitrate_step, max_bitrate);
                                Bundle new_param = new Bundle();
                                new_param.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
                                encoder.setParameters(new_param);
                                upstream_thread.post(() -> stats_listener.onBitrateChange(bitrate));
                            }
                            break;
                    }
                }
            }
            Log.i(TAG, "Output buffer thread has finished.");
        });

        bitrate = format.getInteger(MediaFormat.KEY_BIT_RATE);
        upstream_thread.post(() -> stats_listener.onBitrateChange(bitrate));
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.setInputSurface(input_surface);
        configured = true;
    }

    public void start() {
        if (released) {
            Log.e(TAG, "Can't start, resources were released");
            return;
        }
        if (started) {
            Log.e(TAG, "Already started");
            return;
        }

        Log.d(TAG, "Starting");
        if (!configured)
            configure();
        encoder.start();
        thread.start();
        started = true;
    }

    public void stop() {
        if (!started) {
            Log.e(TAG, "Already stopped");
            return;
        }

        Log.d(TAG, "Stopping");
        encoder.signalEndOfInputStream();
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        encoder.reset();
        configured = false;
        started = false;
    }

    public void release() {
        Log.d(TAG, "Releasing resources");
        if (started)
            stop();
        encoder.release();
        released = true;
    }

    public void setOutgoingMessagePipe(ConnectionMessagePipe pipe) {
        this.pipe_out = pipe;
    }
}
