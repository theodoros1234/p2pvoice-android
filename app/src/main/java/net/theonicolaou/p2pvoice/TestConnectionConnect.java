package net.theonicolaou.p2pvoice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
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
    private TestConnectionSocket socket;

    private final TestConnectionSocket.StatusListener socket_status_listener = fd -> {
        Log.d(this.getClass().getName(), "Connection ready, starting video.");
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
//        videoStop();

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
}