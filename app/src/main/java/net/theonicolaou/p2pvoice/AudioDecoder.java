package net.theonicolaou.p2pvoice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

public class AudioDecoder {
    public static class UnsupportedFormat extends Exception {}
    public static class DecoderFailed extends Exception {}
    public static class PlaybackFailed extends Exception {}

    private static final String TAG = "AudioDecoder";
    private static final int sample_rate = 44100;
    private static final int playback_buffer_size_wanted = sample_rate * 2; // 1s of audio data
    private static final MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);

    private MediaCodec decoder = null;
    private AudioTrack player = null;
    private final AudioAttributes audio_attributes;
    private final AudioFormat audio_format;
    private final int playback_buffer_size;
    private HandlerThread thread;
    private Handler handler;
    private final MediaFormat format;
    private final ArrayBlockingQueue<byte[]> queue_input;
    private boolean started = false, eof_sent, earpiece_used = false;

    private final MediaCodec.Callback decoder_callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int i) {
            // Ignore anything after an EOF
            if (eof_sent)
                return;

            byte[] frame;
            try {
                frame = queue_input.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            if (frame.length == 0) {
                // EOS, stopping
                eof_sent = true;
                decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            } else {
                // Put it into the decoder's buffer
                ByteBuffer buffer = decoder.getInputBuffer(i);
                if (buffer != null) {
                    // May drop samples if buffer has less capacity than the incoming frame
                    int copy_amount = Math.min(frame.length, buffer.capacity());
                    buffer.rewind();
                    buffer.put(frame, 0, copy_amount);
                    decoder.queueInputBuffer(i, 0, copy_amount, 0, 0);
                    Log.d(TAG, "queued input buffer, copy_amount=" + copy_amount);
                }
            }
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int i, @NonNull MediaCodec.BufferInfo bufferInfo) {
            Log.d(TAG, "onOutputBufferAvailable i=" + i);
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                thread.quit();
            if (bufferInfo.size > 0) {
                ByteBuffer buffer = decoder.getOutputBuffer(i);
                if (buffer != null) {
                    int copy_amount = Math.min(playback_buffer_size, bufferInfo.size);
                    copy_amount = (copy_amount / 2) * 2;    // Rounds down to a multiple of 2
                    int bytes_written = player.write(buffer, copy_amount, AudioTrack.WRITE_BLOCKING);
                    if (bytes_written < 0) {
                        // TODO: Make this cause a track reconfig
                        Log.e(TAG, "Playback error " + bytes_written + " on read, stopping.");
                        thread.quit();
                    }
                }
            }
            decoder.releaseOutputBuffer(i, false);
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

    AudioDecoder(String mime) throws UnsupportedFormat, DecoderFailed, PlaybackFailed {
        Log.d(TAG, "Creating for mimetype=" + mime);
        format = MediaFormat.createAudioFormat(mime, sample_rate, 1);
        String codec = list.findDecoderForFormat(format);
        if (codec == null)
            throw new UnsupportedFormat();
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        format.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0);
        format.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0);
        try {
            decoder = MediaCodec.createByCodecName(codec);
        } catch (IOException e) {
            throw new DecoderFailed();
        }

        playback_buffer_size = Math.max(
                playback_buffer_size_wanted,
                AudioTrack.getMinBufferSize(
                        sample_rate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                )
        );
        audio_attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        audio_format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sample_rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        player = new AudioTrack(
                audio_attributes,
                audio_format,
                playback_buffer_size,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );
        if (player.getState() == AudioTrack.STATE_UNINITIALIZED)
            throw new PlaybackFailed();

        queue_input = new ArrayBlockingQueue<>(playback_buffer_size_wanted);
    }

    public void start() {
        Log.i(TAG, "Starting");
        queue_input.clear();
        eof_sent = false;
        decoder.configure(format, null, null, 0);
        thread = new HandlerThread(TAG);
        thread.start();
        handler = new Handler(thread.getLooper());
        decoder.setCallback(decoder_callback, handler);
        player.play();
        decoder.start();
        started = true;
        Log.i(TAG, "Start sequence finished");
    }

    public void stop() {
        Log.i(TAG, "Stopping");
        try {
            queue_input.put(new byte[0]);
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        player.stop();
        decoder.stop();
        decoder.reset();
        queue_input.clear();
        started = false;
        Log.i(TAG, "Stop sequence finished");
    }

    public void pushFrame(byte[] frame) {
        queue_input.offer(frame);
    }

    public void release() {
        Log.d(TAG, "Releasing resources");
        decoder.release();
        player.release();
    }
}
