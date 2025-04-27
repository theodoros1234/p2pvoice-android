package net.theonicolaou.p2pvoice;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEncoder {
    public static class UnsupportedFormat extends Exception {}
    public static class EncoderFailed extends Exception {}

    private static final String TAG = "VideoEncoder";
    private static final MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);

    private final MediaCodec encoder;
    private Thread thread;
    private final MediaFormat format;
    private final Surface input_surface;
    private boolean configured = false, started = false, released = false;
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

    VideoEncoder(String mime, int width, int height, int fps, int bitrate, @NonNull Surface input_surface) throws UnsupportedFormat, EncoderFailed {
        Log.d(TAG, "Initialized with mime=" + mime + " width=" + width + " height=" + height + " fps=" + fps + " bitrate=" + bitrate + " input from surface");
        this.input_surface = input_surface;
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
                }
            }
            Log.i(TAG, "Output buffer thread has finished.");
        });

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
