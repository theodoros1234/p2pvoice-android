package net.theonicolaou.p2pvoice;

import android.Manifest;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.theeasiestway.opus.Constants;
import com.theeasiestway.opus.Opus;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class AudioHandler {
    public static class MicFailed extends Exception {}
    public static class PlaybackFailed extends Exception {}

    private static final String TAG = "AudioHandler";
    private static final int sample_rate = 48000;
    private static final int buffer_size_wanted = sample_rate; // in bytes, 0.5s of audio data
    private static final int frame_size = 1920;  // in bytes, 50ms
    private static final int queue_size = 20;
    private static final Constants.SampleRate opus_sample_rate = Constants.SampleRate.Companion._48000();
    private static final Constants.Channels opus_channels = Constants.Channels.Companion.mono();
    private static final Constants.Application opus_application = Constants.Application.Companion.voip();
    private static final Constants.FrameSize opus_frame_size = Constants.FrameSize.Companion._custom(frame_size / 2);   // in samples

    private final List<AudioDeviceInfo> audio_outputs = new ArrayList<>();
    private int audio_output_current = 0;
    private final AudioManager audio_manager;
    private AudioRecord recorder;
    private AudioTrack player;
    private final AudioEffect noise_suppressor, auto_gain;
    private final Opus opus = new Opus();
    private final AudioAttributes audio_attributes;
    private final AudioFormat audio_format;
    private final int buffer_size;
    private Constants.Bitrate opus_bitrate;
    private Thread thread_encoder, thread_decoder;
    private final ConnectionMessagePipe pipe_in;
    private ConnectionMessagePipe pipe_out = null;
    private boolean started_encoding = false, started_decoding, muted = false, released = false;
    private volatile boolean thread_encoder_work;

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    AudioHandler(int bitrate, AudioManager audio_manager) throws MicFailed, PlaybackFailed {
        Log.d(TAG, "Creating for bitrate=" + bitrate);
        this.audio_manager = audio_manager;

        // Make sure buffer size is above the min acceptable
        buffer_size = Math.max(
                buffer_size_wanted,
                Math.max(
                        AudioRecord.getMinBufferSize(
                                sample_rate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT
                        ), AudioTrack.getMinBufferSize(
                                sample_rate,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT
                        )
                )
        );

        recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sample_rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                buffer_size
        );
        if (recorder.getState() == AudioRecord.STATE_UNINITIALIZED)
            throw new MicFailed();

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
                buffer_size,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );
        if (player.getState() == AudioTrack.STATE_UNINITIALIZED)
            throw new PlaybackFailed();

        opus_bitrate = Constants.Bitrate.Companion.instance(bitrate);

        pipe_in = new ConnectionMessagePipe(queue_size, true);

        // Get output devices
        if (audio_manager != null) {
            AudioDeviceInfo[] devices = audio_manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
                    type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER  ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET      ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    audio_outputs.add(device);
                }
            }
        }

        if (!audio_outputs.isEmpty())
            player.setPreferredDevice(audio_outputs.get(0));

        // Apply noise suppression and auto gain to mic
        noise_suppressor = NoiseSuppressor.create(recorder.getAudioSessionId());
        auto_gain = AutomaticGainControl.create(recorder.getAudioSessionId());
    }

    public int changeOutput() {
        if (audio_outputs.isEmpty())
            return -1;

        audio_output_current++;
        if (audio_output_current >= audio_outputs.size())
            audio_output_current = 0;
        AudioDeviceInfo current = audio_outputs.get(audio_output_current);
        player.setPreferredDevice(current);
        return current.getType();
    }

    public void startEncoder() {
        if (started_encoding) {
            Log.e(TAG, "Encoder already started");
            return;
        }
        Log.i(TAG, "Starting encoder");

        opus.encoderInit(opus_sample_rate, opus_channels, opus_application);
        opus.encoderSetBitrate(opus_bitrate);
        thread_encoder_work = true;

        thread_encoder = new Thread(() -> {
            recorder.startRecording();
            if (noise_suppressor != null)
                noise_suppressor.setEnabled(true);
            if (auto_gain != null)
                auto_gain.setEnabled(true);
            ByteBuffer raw_audio_buffer = ByteBuffer.allocateDirect(frame_size);
            byte[] raw_audio_array = new byte[frame_size];
            byte[] encoded_audio;
            while (thread_encoder_work) {
                raw_audio_buffer.rewind();
                int bytes_read = recorder.read(raw_audio_buffer, frame_size, AudioRecord.READ_BLOCKING);
                if (bytes_read < 0) {
                    // TODO: try to auto-restart the recorder instead
                    Log.e(TAG, "AudioRecorder failed with code " + bytes_read);
                    break;
                }
                raw_audio_buffer.get(raw_audio_array, 0, bytes_read);
                encoded_audio = opus.encode(raw_audio_array, opus_frame_size);
                if (pipe_out != null && encoded_audio != null)
                    pipe_out.send(Connection.DATA_AUDIO, encoded_audio);
            }
            recorder.stop();
        });

        thread_encoder.start();
        started_encoding = true;
        Log.i(TAG, "Encoding started");
    }

    public void stopEncoder() {
        if (released) {
            Log.e(TAG, "Can't start, resources were released");
            return;
        }
        if (!started_encoding) {
            Log.e(TAG, "Encoder already stopped");
            return;
        }
        Log.i(TAG, "Stopping encoder");

        thread_encoder_work = false;

        try {
            thread_encoder.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        opus.encoderRelease();

        started_encoding = false;
        Log.i(TAG, "Encoder stopped");
    }

    public void startDecoder() {
        if (released) {
            Log.e(TAG, "Can't start, resources were released");
            return;
        }
        if (started_decoding) {
            Log.e(TAG, "Decoder already started");
            return;
        }
        Log.i(TAG, "Starting decoder");

        opus.decoderInit(opus_sample_rate, opus_channels);
        pipe_in.openReceiver();

        thread_decoder = new Thread(() -> {
            ConnectionMessage encoded_audio;
            byte[] raw_audio_array;
            player.play();
            while (true) {
                encoded_audio = pipe_in.receive();
                if (encoded_audio == null)
                    break;
                if (encoded_audio.type != Connection.DATA_AUDIO) {
                    Log.e(TAG, "Received frame of wrong message type " + encoded_audio.type);
                    continue;
                }
                raw_audio_array = opus.decode(encoded_audio.data, opus_frame_size);
                if (raw_audio_array != null)
                    player.write(ByteBuffer.wrap(raw_audio_array), frame_size, AudioTrack.WRITE_BLOCKING);
            }
            player.stop();
        });

        thread_decoder.start();
        started_decoding = true;
        Log.i(TAG, "Decoder started");
    }

    public void stopDecoder() {
        if (!started_decoding) {
            Log.e(TAG, "Decoder already stopped");
            return;
        }
        Log.i(TAG, "Stopping decoder");

        pipe_in.closeReceiver();
        try {
            thread_decoder.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        opus.decoderRelease();
        started_decoding = false;
        Log.i(TAG, "Decoder stopped");
    }

    public void release() {
        Log.d(TAG, "Releasing resources");
        if (started_encoding)
            stopEncoder();
        if (started_decoding)
            stopDecoder();
        recorder.release();
        player.release();
        released = true;
    }

    public void setOutgoingMessagePipe(ConnectionMessagePipe pipe) {
        this.pipe_out = pipe;
    }

    public ConnectionMessagePipe getIncomingMessagePipe() {
        return pipe_in;
    }
}
