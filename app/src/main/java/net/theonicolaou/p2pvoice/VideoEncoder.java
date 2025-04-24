package net.theonicolaou.p2pvoice;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEncoder {
    public static class UnsupportedFormat extends Exception {}
    public static class EncoderFailed extends Exception {}

    public interface Callback {
        void onOutputFrameAvailable(byte[] frame);
    }

    private static final String TAG = "VideoEncoder";
    private static final MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);

    MediaCodec encoder;
    private HandlerThread thread;
    private Handler handler;
    private MediaFormat format;
    private String codec;
    private int width, height, fps;
    private long timestamp = 0, timestamp_interval;
    private Callback upstream_callback;
    private Handler upstream_handler;

    private MediaCodec.Callback encoder_callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec encoder, int i) {
            /* Code used as a workaround for old devices, not needed. Keeping just in case.
            if (image_reader == null)
                return;

            ByteBuffer encoder_buffer = encoder.getInputBuffer(i);
            Image.Plane[] image_planes = image_reader.acquireNextImage().getPlanes();
            ByteBuffer planeY = image_planes[0].getBuffer();
            ByteBuffer planeU = image_planes[1].getBuffer();
            ByteBuffer planeV = image_planes[2].getBuffer();
            encoder_buffer.put(planeY);
            encoder.queueInputBuffer(i, 0, planeY.capacity(), timestamp, 0);
            timestamp += timestamp_interval;

            /*
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
            timestamp += timestamp_interval;*/
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

    VideoEncoder(String mime, int width, int height, int fps, int bitrate, @NonNull Surface input_surface, @NonNull Callback callback, @NonNull Looper callback_looper) throws UnsupportedFormat, EncoderFailed {
        Log.d(TAG, "Initialized with mime=" + mime + " width=" + width + " height=" + height + " fps=" + fps + " bitrate=" + bitrate + " input from surface");
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.timestamp_interval = 1000000/fps; // microseconds
        this.upstream_callback = callback;
        this.upstream_handler = new Handler(callback_looper);
        format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
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
            encoder.setInputSurface(input_surface);
        } catch (IOException e) {
            throw new EncoderFailed();
        }
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
}
