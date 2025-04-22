package net.theonicolaou.p2pvoice;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

public class VideoDecoder {
    public static class UnsupportedFormat extends Exception {}
    public static class DecoderFailed extends Exception {}

    private static final String TAG = "VideoDecoder";

    private MediaCodec decoder;
    private HandlerThread thread;
    private Handler handler;
    private MediaFormat format;
    private String codec;
    private int width, height, fps, queue_capacity;
    private long timestamp = 0, timestamp_interval;
    private ArrayBlockingQueue<byte[]> queue_input;

    private MediaCodec.Callback decoder_callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec decoder, int i) {
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
            ByteBuffer buffer = decoder.getInputBuffer(i);
            buffer.put(frame);
            decoder.queueInputBuffer(i, 0, frame.length, timestamp, 0);
            timestamp += timestamp_interval;
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int i, @NonNull MediaCodec.BufferInfo bufferInfo) {
            // Tell decoder to render the output frame
            mediaCodec.releaseOutputBuffer(i, true);
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

    VideoDecoder(String mime, int width, int height, int fps, int queue_capacity, Surface output_surface) throws UnsupportedFormat, DecoderFailed {
        Log.d(TAG, "Initialized with mime=" + mime + " width=" + width + " height=" + height + " fps=" + fps + " queue_capacity=" + queue_capacity);
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.timestamp_interval = 1000000/fps; // microseconds
        this.queue_capacity = queue_capacity;
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        codec = list.findDecoderForFormat(format);
        if (codec == null) {
            throw new UnsupportedFormat();
        }
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, fps);
        format.setInteger(MediaFormat.KEY_PUSH_BLANK_BUFFERS_ON_STOP, 1);

        thread = new HandlerThread(TAG);
        thread.start();
        handler = new Handler(thread.getLooper());

        try {
            decoder = MediaCodec.createByCodecName(codec);
            decoder.setCallback(decoder_callback, handler);
            decoder.configure(format, output_surface, null, 0);
        } catch (IOException e) {
            throw new DecoderFailed();
        }

        queue_input = new ArrayBlockingQueue<>(queue_capacity);
    }

    public void start() {
        Log.d(TAG, "Starting");
        decoder.start();
    }

    public void stop() {
        Log.d(TAG, "Stopping");
        decoder.stop();
        thread.quit();
        thread.interrupt(); // Interrupts thread in case it's waiting on an empty queue
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        decoder.release();
    }

    public void pushFrame(byte[] frame) {
        // NOTE: Frame will be dropped if input queue is full
        queue_input.offer(frame);
    }
}
