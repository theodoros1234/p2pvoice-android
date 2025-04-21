package net.theonicolaou.p2pvoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.CamcorderProfile;
import android.media.MediaCodecInfo;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.Buffer;

public class TestConnectionConnect extends AppCompatActivity {
    private static final int port = 8798;
    private static final int bitrate_video = 2000000;
    private static final int bitrate_audio = 192000;

    private TextView info;
    private String host_address;
    private Boolean is_server;
    private SurfaceView preview_remote;
    private FrameLayout preview_local_container;
    private TestConnectionCameraPreview preview_local;
    private Camera camera;
    private boolean started = false;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private TestConnectionSocket socket;

    private final TestConnectionSocket.StatusListener socket_status_listener = fd -> {
        Log.d(this.getClass().getName(), "Connection ready, starting video.");
        /*
        BufferedReader in = new BufferedReader(new FileReader(fd));
        FileOutputStream out = new FileOutputStream(fd);
        for (int i=0; i<20; i++) {
            try {
                out.write(("Hi from " + android.os.Build.MODEL + "\n").getBytes());
            } catch (IOException e) {
                Log.d(this.getClass().getName(), "IOException when test writing to socket");
            }
        }
        for (int i=0; i<20; i++) {
            try {
                Log.d(this.getClass().getName(), "Received: " + in.readLine());
            } catch (IOException e) {
                Log.d(this.getClass().getName(), "IOException when test reading from socket");
            }
        }
        */
        // Socket connected, start video transmission
        if (started && recorder == null && player == null)
            videoStart(fd);
    };

    private final MediaRecorder.OnErrorListener recorder_error = (mr, what, extra) -> {
        Log.w(this.getClass().getName(), "MediaRecorder error what=" + what + " extra=" + extra);
        // Video failed, stop and reconnect (if not done already)
        if (started && recorder != null) {
            videoStop();
            socket.reconnect();
        }
    };

    private final MediaPlayer.OnErrorListener player_error = (mp, what, extra) -> {
        Log.w(this.getClass().getName(), "MediaPlayer error what=" + what + " extra=" + extra);
        // Video failed, stop and reconnect (if not done already)
        if (started && player != null) {
            videoStop();
            socket.reconnect();
        }
        return true;
    };

    private ActivityResultLauncher<String> permission_launcher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
        if (granted) {
            // Start connection
            callStart();
        } else {
            Toast.makeText(this, R.string.test_call_permission_error, Toast.LENGTH_SHORT).show();
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_connection_connect);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        Button button_mute = findViewById(R.id.button_mute);
        Button button_camera_toggle = findViewById(R.id.button_camera_toggle);
        preview_local_container = findViewById(R.id.preview_local);
        preview_remote = findViewById(R.id.preview_remote);

        // Enable back button
        ActionBar action_bar = getSupportActionBar();
        if (action_bar != null) {
            action_bar.setHomeButtonEnabled(true);
            action_bar.setDisplayHomeAsUpEnabled(true);
        }

        // Handle intent
        Intent intent = getIntent();
        host_address = intent.getStringExtra(getPackageName() + ".HostAddress");
        is_server = intent.getBooleanExtra(getPackageName() + ".IsServer", false);
        // Cancel if info missing from intent
        if (host_address == null)
            finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        callStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
        callStop();
    }

    private boolean acquirePermission(String permission) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)
            return true;
        else
            permission_launcher.launch(permission);
        return false;
    }

    private void callStart() {
        Log.d(this.getClass().getName(), "Starting call");
        // Don't do anything if already started
        if (started)
            return;

        // Don't start yet if we don't have permissions
        if (!acquirePermission(Manifest.permission.CAMERA) || !acquirePermission(Manifest.permission.RECORD_AUDIO))
            return;

        // Don't start if there's no camera
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(this, R.string.test_call_camera_missing, Toast.LENGTH_SHORT).show();
            return;
        }

        // Get camera
        try {
            camera = Camera.open(1);
        } catch (Exception e) {
            Toast.makeText(this, R.string.test_call_camera_error, Toast.LENGTH_SHORT).show();
            return;
        }

        started = true;

        // Camera preview
        camera.setDisplayOrientation(90);
        preview_local = new TestConnectionCameraPreview(this, camera);
        preview_local_container.addView(preview_local);

        // Start network connection
        if (is_server)
            socket = new TestConnectionSocketServer(this, socket_status_listener, host_address, port);
        else
            socket = new TestConnectionSocketClient(this, socket_status_listener, host_address, port);
        socket.start();
        // Video encoding/decoding will be started later when connection is active
    }

    private void callStop() {
        Log.d(this.getClass().getName(), "Stopping call");
        videoStop();

        // Stop network
        socket.shutdown();
        try {
            socket.join();
        } catch (InterruptedException ignored) {}
        socket = null;

        // Stop camera
        preview_local_container.removeAllViews();
        preview_local = null;
        camera.release();
        camera = null;

        started = false;
    }

    // NOTE: Might set some of these to return booleans
    private void videoStart(FileDescriptor fd) {
        if (started && videoOutStart(fd)) {
            if (!videoInStart(fd)) {
                videoOutStop();
                if (socket != null)
                    socket.reconnect();
            }
        }
    }

    private void videoStop() {
        videoOutStop();
        videoInStop();
    }

    private boolean videoOutStart(FileDescriptor fd) {
        Log.d(this.getClass().getName(), "Starting video out");
        if (recorder != null)
            return false;

        recorder = new MediaRecorder();
        recorder.setOnErrorListener(recorder_error);
//        camera.unlock();
//        recorder.setCamera(camera);
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
//        recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
//        recorder.setProfile(CamcorderProfile.get(1, CamcorderProfile.QUALITY_LOW));
        recorder.setAudioChannels(1);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(48000);
//        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
//        recorder.setVideoSize(720, 1280);
//        recorder.setVideoFrameRate(30);
        recorder.setAudioEncodingBitRate(bitrate_audio);
//        recorder.setVideoEncodingBitRate(bitrate_video);
//        ParcelFileDescriptor[] test;
//        try {
//            test = ParcelFileDescriptor.createPipe();
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//        fd = test[1].getFileDescriptor();
//        recorder.setOutputFile(fd);
        recorder.setOutputFile("/dev/null");
//        recorder.setPreviewDisplay(preview_local.getHolder().getSurface()); // may not be needed?
        try {
            recorder.prepare();
            recorder.start();
        } catch (IllegalStateException e) {
            Log.e(this.getClass().getName(), "IllegalStateException when starting MediaRecorder: " + e.getMessage());
            Toast.makeText(this, R.string.test_call_media_error, Toast.LENGTH_SHORT).show();
            recorder.release();
            recorder = null;
            return false;
        } catch (IOException e) {
            Log.e(this.getClass().getName(), "IOException when starting MediaRecorder: " + e.getMessage());
            Toast.makeText(this, R.string.test_call_media_error, Toast.LENGTH_SHORT).show();
            recorder.release();
            recorder = null;
            return false;
        }

        return true;
    }

    private void videoOutStop() {
        Log.d(this.getClass().getName(), "Stopping video out");
        if (recorder == null)
            return;

        recorder.stop();
        recorder.release();
        recorder = null;
//        camera.lock();
    }

    private boolean videoInStart(FileDescriptor fd) {
        Log.d(this.getClass().getName(), "Starting video in");
        if (player != null)
            return false;

        player = new MediaPlayer();
        player.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
        );
//        player.setPreferredDevice();
//        player.setScreenOnWhilePlaying(true);
//        player.setDisplay(preview_remote.getHolder());
        player.setOnErrorListener(player_error);

        try {
            player.setDataSource(fd);
            player.prepare();
        } catch (IOException e) {
            Log.e(this.getClass().getName(), "IOException when starting MediaPlayer: " + e.getMessage());
            Toast.makeText(this, R.string.test_call_media_error, Toast.LENGTH_SHORT).show();
            player.release();
            player = null;
            return false;
        }

        // Start
        player.start();

        return true;
    }

    private void videoInStop() {
        Log.d(this.getClass().getName(), "Stopping video in");
        if (player == null)
            return;

        player.stop();
        player.release();
        player = null;
    }
}