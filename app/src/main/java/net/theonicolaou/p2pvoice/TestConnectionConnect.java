package net.theonicolaou.p2pvoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class TestConnectionConnect extends AppCompatActivity {
    private static final String TAG = "TestConnectionConnect";

    private static final int port = 8798;
    private static final int bitrate_video = 4000000;
    private static final int bitrate_audio = 192000;
    private static final String video_format = "video/avc";
    private static final int camera_width = 1280, camera_height = 720, camera_fps = 30;

    private TextView info;
    private String host_address;
    private Boolean is_server;
    private SurfaceView preview_remote, preview_local;
    private boolean started = false;
    private TestConnectionSocket socket;
    private VideoEncoder video_encoder;
    private VideoDecoder video_decoder;
    private CallCamera camera;
    private Surface encoder_surface = null;

    private final TestConnectionSocket.StatusListener socket_status_listener = fd -> {
        Log.d(TAG, "Connection ready, starting video.");
        // Socket connected, start video transmission
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

    private VideoEncoder.Callback video_encoder_callback = (frame) -> {
        if (video_decoder != null)
            video_decoder.pushFrame(frame);
    };

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
        preview_local = findViewById(R.id.preview_local);
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

        // Prepare encoder's input
        if (VideoEncoder.checkInputSurfaceCompatibility(video_format, camera_width, camera_height)) {
            Log.d(TAG, "Encoder supports input surfaces");
            encoder_surface = MediaCodec.createPersistentInputSurface();
        } else {
            Log.d(TAG, "Encoder doesn't support input surfaces. This could work using a workaround, but it isn't implemented currently.");
            Toast.makeText(this, R.string.test_call_media_error, Toast.LENGTH_SHORT);
        }

        // Initialize camera
        if (encoder_surface != null) {
            try {
                camera = new CallCamera(this, camera_width, camera_height, preview_local.getHolder(), encoder_surface);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to initialize camera handler: CameraAccessException " + e.getMessage());
                Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_SHORT).show();
            }
        }

        // TEST
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaCodecInfo[] info = list.getCodecInfos();
        Log.i("GET_CODEC_INFO", "Codec list:");
        for (MediaCodecInfo i : info) {
            Log.i("GET_CODEC_INFO", i.getName() + " isEncoder=" + i.isEncoder());
        }
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (camera != null)
            camera.close();
        if (encoder_surface != null)
            encoder_surface.release();
    }

    private boolean acquirePermission(String permission) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)
            return true;
        else
            permission_launcher.launch(permission);
        return false;
    }

    private void callStart() {
        Log.d(TAG, "Starting call");
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

        started = true;

        // Start network connection
//        if (is_server)
//            socket = new TestConnectionSocketServer(this, socket_status_listener, host_address, port);
//        else
//            socket = new TestConnectionSocketClient(this, socket_status_listener, host_address, port);
//        socket.start();
        // Video encoding/decoding will be started later when connection is active

        try {
            if (encoder_surface != null) {
                video_encoder = new VideoEncoder(video_format, camera_width, camera_height, camera_fps, bitrate_video, encoder_surface, video_encoder_callback, getMainLooper());
                video_encoder.start();
            }
        } catch (VideoEncoder.UnsupportedFormat e) {
            Toast.makeText(this, R.string.test_call_video_encode_unsupported, Toast.LENGTH_SHORT).show();
        } catch (VideoEncoder.EncoderFailed e) {
            Toast.makeText(this, R.string.test_call_video_encode_failed, Toast.LENGTH_SHORT).show();
        }

        preview_remote.getHolder().setFixedSize(camera_width, camera_height);
        preview_remote.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
                if (video_decoder == null) {
                    try {
                        video_decoder = new VideoDecoder(video_format, camera_width, camera_height, camera_fps, 15, preview_remote.getHolder().getSurface());
                        video_decoder.start();
                    } catch (VideoDecoder.UnsupportedFormat e) {
                        Toast.makeText(TestConnectionConnect.this, R.string.test_call_video_decode_unsupported, Toast.LENGTH_SHORT).show();
                    } catch (VideoDecoder.DecoderFailed e) {
                        Toast.makeText(TestConnectionConnect.this, R.string.test_call_video_decode_failed, Toast.LENGTH_SHORT).show();
                    }
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int i, int i1, int i2) {
                surfaceDestroyed(surfaceHolder);
                surfaceCreated(surfaceHolder);
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
                if (video_decoder != null) {
                    video_decoder.stop();
                    video_decoder = null;
                }
            }
        });

        if (camera != null)
            camera.start();
    }

    private void callStop() {
        Log.d(TAG, "Stopping call");

        if (camera != null)
            camera.stop();

        // Not needed cause it's stopped by the surface callbacks and this causes a crash
//        if (video_decoder != null) {
//            video_decoder.stop();
//            video_decoder = null;
//        }

        if (video_encoder != null) {
            video_encoder.stop();
            video_encoder = null;
        }

        // Stop network
//        socket.shutdown();
//        try {
//            socket.join();
//        } catch (InterruptedException ignored) {}
//        socket = null;

        started = false;
    }
}