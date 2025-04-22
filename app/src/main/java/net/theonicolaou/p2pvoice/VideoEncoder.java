package net.theonicolaou.p2pvoice;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

public class VideoEncoder {
    public static class UnsupportedFormat extends Exception {}
    public static class EncoderFailed extends Exception {}

    public interface Callback {
        void onOutputFrameAvailable(byte[] frame);
    }

    private static final String TAG = "VideoEncoder";

    MediaCodec encoder;
    private HandlerThread thread;
    private Handler handler;
    private MediaFormat format;
    private String codec;
    private int width, height, fps, queue_capacity;
    private long timestamp = 0, timestamp_interval;
    private ArrayBlockingQueue<byte[]> queue_input;
    private Callback upstream_callback;
    private Handler upstream_handler;

    private MediaCodec.Callback encoder_callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec encoder, int i) {
            // Warning: holding onto this buffer may stall the decoder
            byte[] frame;
            // Grab frame from queue
            try {
                frame = queue_input.take();
            } catch (InterruptedException e) {
                // Stopping
                return;
            }

            // Put it into the decoder's buffer
            ByteBuffer buffer = encoder.getInputBuffer(i);
            buffer.put(frame);
            encoder.queueInputBuffer(i, 0, frame.length, timestamp, 0);
            timestamp += timestamp_interval;
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec encoder, int i, @NonNull MediaCodec.BufferInfo bufferInfo) {
            byte[] frame = new byte[bufferInfo.size];
            ByteBuffer buffer = encoder.getOutputBuffer(i);
            buffer.position(bufferInfo.offset);
            buffer.get(frame, 0, bufferInfo.size);
            encoder.releaseOutputBuffer(i, false);

            handler.post(() -> {
                upstream_callback.onOutputFrameAvailable(frame);
            });
        }

        @Override
        public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
            Log.e(TAG, "Error from MediaCodec: " + e.getClass() + ": " + e.getMessage());
            // TODO: Should make callback that notifies upstream
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
            Log.d(TAG, "MediaCodec changed output format to " + mediaFormat);
            // I don't expect this to happen
        }
    };

    VideoEncoder(String mime, int width, int height, int fps, int bitrate, int queue_capacity, Callback callback, Looper callback_looper) throws UnsupportedFormat, EncoderFailed {
        Log.d(TAG, "Initialized with mime=" + mime + " width=" + width + " height=" + height + " fps=" + fps + " bitrate=" + bitrate + " queue_capacity=" + queue_capacity);
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.timestamp_interval = 1000000/fps; // microseconds
        this.queue_capacity = queue_capacity;
        this.upstream_callback = callback;
        this.upstream_handler = new Handler(callback_looper);
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        codec = list.findEncoderForFormat(format);
        if (codec == null) {
            throw new UnsupportedFormat();
        }
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, fps);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, fps);

        thread = new HandlerThread(TAG);
        thread.start();
        handler = new Handler(thread.getLooper());

        try {
            encoder = MediaCodec.createByCodecName(codec);
            encoder.setCallback(encoder_callback, handler);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (IOException e) {
            throw new EncoderFailed();
        }

        queue_input = new ArrayBlockingQueue<>(queue_capacity);
    }

    public void start() {
        Log.d(TAG, "Starting");
        encoder.start();
    }

    public void stop() {
        Log.d(TAG, "Stopping");
        encoder.stop();
        thread.quit();
        thread.interrupt(); // Interrupts thread in case it's waiting on an empty queue
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        encoder.release();
    }

    public void pushFrame(byte[] frame) {
        // NOTE: Frame will be dropped if input queue is full
        queue_input.offer(frame);
    }
}
