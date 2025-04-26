package net.theonicolaou.p2pvoice;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;

import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioEncoder {
    public static class UnsupportedFormat extends Exception {}
    public static class EncoderFailed extends Exception {}
    public static class MicFailed extends Exception {}

    public interface Callback {
        void onFrameAvailable(byte[] data);
    }

    private static final String TAG = "AudioEncoder";
    private static final int sample_rate = 44100;
    private static final int recorder_buffer_size_wanted = sample_rate * 2; // 1s of audio data
    private static final int encoder_input_frame_size = sample_rate / 20;   // 50ms
    private static final MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);

    private MediaCodec encoder = null;
    private AudioRecord recorder = null;
    private final int recorder_buffer_size;
    private HandlerThread thread;
    private Handler handler;
    private final Callback upstream_callback;
    private final MediaFormat format;
    private boolean started = false, eof_send, eof_sent, muted = false;

    private final MediaCodec.Callback encoder_callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int i) {
            if (eof_sent)
                return;

            if (eof_send) {
                encoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                eof_sent = true;
            } else {
                ByteBuffer buffer = encoder.getInputBuffer(i);
                if (buffer != null) {
                    buffer.rewind();
                    int bytes_written = recorder.read(buffer, Math.min(encoder_input_frame_size, buffer.capacity()));
                    if (bytes_written < 0) {
                        // TODO: Make this cause a mic reconfig
                        Log.e(TAG, "Mic error " + bytes_written + " on read, stopping.");
                        encoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eof_sent = true;
                    } else {
                        encoder.queueInputBuffer(i, 0, bytes_written, 0, 0);
                    }
                }
            }
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int i, @NonNull MediaCodec.BufferInfo bufferInfo) {
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                ByteBuffer buffer = encoder.getOutputBuffer(i);
                if (buffer != null) {
                    byte[] frame = new byte[bufferInfo.size];
                    buffer.position(bufferInfo.offset);
                    buffer.get(frame, 0, bufferInfo.size);
                    encoder.releaseOutputBuffer(i, false);
                    upstream_callback.onFrameAvailable(frame);
                }
            } else {
                // End of stream, quit thread.
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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    AudioEncoder(String mime, int bitrate, Callback callback) throws UnsupportedFormat, EncoderFailed, MicFailed {
        Log.d(TAG, "Creating for mimetype=" + mime + " bitrate=" + bitrate);
        this.upstream_callback = callback;
        format = MediaFormat.createAudioFormat(mime, sample_rate, 1);
        String codec = list.findEncoderForFormat(format);
        if (codec == null)
            throw new UnsupportedFormat();
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        try {
            encoder = MediaCodec.createByCodecName(codec);
        } catch (IOException e) {
            throw new EncoderFailed();
        }

        recorder_buffer_size = Math.max(
                recorder_buffer_size_wanted,
                AudioRecord.getMinBufferSize(
                        sample_rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                )
        );
        recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sample_rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recorder_buffer_size
        );
        if (recorder.getState() == AudioRecord.STATE_UNINITIALIZED)
            throw new MicFailed();
    }

    public void start() {
        Log.i(TAG, "Starting");
        eof_send = false;
        eof_sent = false;
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        thread = new HandlerThread(TAG);
        thread.start();
        handler = new Handler(thread.getLooper());
        encoder.setCallback(encoder_callback, handler);
        encoder.start();
        recorder.startRecording();
        started = true;
        Log.i(TAG, "Start sequence finished");
    }

    public void stop() {
        Log.i(TAG, "Stopping");
        handler.post(() -> eof_send = true);
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        recorder.stop();
        encoder.stop();
        encoder.reset();
        started = false;
        Log.i(TAG, "Stop sequence finished");
    }

    public void release() {
        Log.d(TAG, "Releasing resources");
        encoder.release();
        recorder.release();
    }
}
