package net.theonicolaou.p2pvoice;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoDecoder {
    public static class UnsupportedFormat extends Exception {}
    public static class DecoderFailed extends Exception {}

    private static final String TAG = "VideoDecoder";

    private final MediaCodec decoder;
    private HandlerThread thread;
    private final MediaFormat format;
    private final SurfaceHolder output_surface;
    private long timestamp = 0;
    private final long timestamp_interval;
    private final ConnectionMessagePipe pipe_in;
    private boolean start_requested = false, released = false;
    private boolean started = false, surface_ready, eof_sent = false;
    private int rotation = 270;

    private final MediaCodec.Callback decoder_callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec decoder, int i) {
            // Ignore anything after an EOF
            if (eof_sent)
                return;

            // Warning: holding onto this buffer may stall the decoder
            ConnectionMessage frame;
            // Grab frame from queue
            frame = pipe_in.receive();

            if (frame == null) {
                // EOS, stopping
                eof_sent = true;
                decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            } else {
                if (frame.type != Connection.DATA_VIDEO) {
                    Log.e(TAG, "Received frame of wrong message type " + frame.type);
                    decoder.queueInputBuffer(i, 0, 0, 0, 0);
                    return;
                }
                // Put it into the decoder's buffer
                ByteBuffer buffer = decoder.getInputBuffer(i);
                if (buffer != null) {
                    buffer.put(frame.data);
                    try {
                        decoder.queueInputBuffer(i, 0, frame.data.length, timestamp, 0);
                    } catch (IllegalStateException e) {
                        Log.d(TAG, "onInputBufferAvailable: IllegalStateException: " + e.getMessage());
                        eof_sent = true;
                        thread.quit();
                    }
                    timestamp += timestamp_interval;
                }
            }
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int i, @NonNull MediaCodec.BufferInfo bufferInfo) {
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                // Tell decoder to render the output frame
                mediaCodec.releaseOutputBuffer(i, true);
            } else {
                // EOS, stop thread
                mediaCodec.releaseOutputBuffer(i, false);
                thread.quit();
            }
        }

        @Override
        public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
            Log.e(TAG, "Error from MediaCodec: " + e.getClass() + ": " + e.getMessage());
            thread.quit();
            // TODO: Should make callback that notifies upstream
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
            Log.d(TAG, "MediaCodec changed output format to " + mediaFormat);
            // I don't expect this to happen
        }
    };

    private final SurfaceHolder.Callback surface_callback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
            Log.d(TAG, "Callback in: output surface created");
            surface_ready = true;
            startIfReady();
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int i, int i1, int i2) {
            Log.d(TAG, "Callback in: output surface created");
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
            Log.d(TAG, "Callback in: output surface destroyed");
            surface_ready = false;
            stopIfUnready();
        }
    };

    VideoDecoder(String mime, int width, int height, int fps, int queue_capacity, SurfaceHolder output_surface) throws UnsupportedFormat, DecoderFailed {
        Log.d(TAG, "Initialized with mime=" + mime + " width=" + width + " height=" + height + " fps=" + fps + " queue_capacity=" + queue_capacity);
        this.timestamp_interval = 1000000/fps; // microseconds
        this.output_surface = output_surface;
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        format = MediaFormat.createVideoFormat(mime, width, height);
        String codec = list.findDecoderForFormat(format);
        if (codec == null) {
            throw new UnsupportedFormat();
        }
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, fps);
        format.setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1);
        format.setInteger(MediaFormat.KEY_ROTATION, rotation);

        try {
            decoder = MediaCodec.createByCodecName(codec);
        } catch (IOException e) {
            throw new DecoderFailed();
        }

        output_surface.setFixedSize(width, height);
        Surface surface_check = output_surface.getSurface();
        output_surface.addCallback(surface_callback);
        surface_ready = (surface_check != null) && surface_check.isValid();
        pipe_in = new ConnectionMessagePipe(queue_capacity, true);
    }

    private void startIfReady() {
        if (started || !start_requested || !surface_ready)
            return;
        if (released) {
            Log.e(TAG, "Can't start, resources were released");
            return;
        }

        Log.i(TAG, "Starting");
        pipe_in.openReceiver();
        eof_sent = false;
        timestamp = 0;
        thread = new HandlerThread(TAG);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        decoder.setCallback(decoder_callback, handler);
        decoder.configure(format, output_surface.getSurface(), null, 0);
        decoder.start();
        started = true;
        Log.i(TAG, "Start sequence finished");
    }

    private void stopIfUnready() {
        if (!started)
            return;

        Log.i(TAG, "Stopping");
        pipe_in.closeReceiver();  // Should send an EOS to the codec
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        decoder.stop();
        decoder.reset();

        started = false;
        Log.i(TAG, "Stop sequence finished");
    }

    public void start() {
        Log.d(TAG, "Start requested");
        start_requested = true;
        pipe_in.openReceiver();     // Needed to not miss initial frames from network
        startIfReady();
    }

    public void stop() {
        Log.d(TAG, "Stop requested");
        start_requested = false;
        stopIfUnready();
    }

    public void release() {
        Log.d(TAG, "Releasing resources");
        start_requested = false;
        stopIfUnready();
        output_surface.removeCallback(surface_callback);
        decoder.release();
        released = true;
    }

    public ConnectionMessagePipe getIncomingMessagePipe() {
        return pipe_in;
    }

    public void setRotation(int rotation) {
        format.setInteger(MediaFormat.KEY_ROTATION, rotation);
    }
}
