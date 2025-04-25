package net.theonicolaou.p2pvoice;


import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import java.util.ArrayList;

public class CallCamera {
    private static final String TAG = "CallCamera";

    private final Context context;
    private String[] camera_list;
    private final SurfaceHolder preview_surface;
    private final Surface encoder_surface;
    private final CameraManager camera_manager;
    private CameraDevice camera_current;
    private CameraCaptureSession camera_session;

    private boolean surface_ready = false, camera_ready = false;
    private boolean started = false, start_requested = false, stop_requested = false;
    private int width, height;

    private SurfaceHolder.Callback preview_surface_callback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
            Log.d(TAG, "Callback in: preview surface created");
            surface_ready = true;
            start_if_ready();
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int i, int i1, int i2) {
            Log.d(TAG, "Callback in: preview surface changed");
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
            Log.d(TAG, "Callback in: preview surface destroyed");
            surface_ready = false;
            stop_if_unready();
        }
    };

    private CameraDevice.StateCallback camera_device_callback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.d(TAG, "Callback in: camera device opened");
            camera_ready = true;
            camera_current = cameraDevice;
            start_if_ready();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.d(TAG, "Callback in: camera device disconnected");
            camera_ready = false;
            camera_current = null;  // Crashes otherwise
            stop_if_unready();
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            Log.e(TAG, "Callback in: camera device error");
            camera_ready = false;
            camera_current = null;  // Crashes otherwise
            stop_if_unready();
        }
    };

    private CameraCaptureSession.StateCallback camera_session_state_callback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            if (!started) {
                Log.d(TAG, "Callback in: Camera capture session configured (ignoring cause not started)");
                return;
            }
            Log.d(TAG, "Callback in: Camera capture session configured");
            camera_session = cameraCaptureSession;

            // Start preview capture requests
            CaptureRequest.Builder capture_request;
            try {
                capture_request = camera_current.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                capture_request.addTarget(preview_surface.getSurface());
                capture_request.addTarget(encoder_surface);
                camera_session.setRepeatingRequest(capture_request.build(), null, null);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Callback in (continued): Failed to set capture request: CameraAccessException: " + e.getMessage());
                camera_ready = false;
                stop_if_unready();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.e(TAG, "Callback in: Failed to configure camera capture session");
            camera_ready = false;
            stop_if_unready();
        }
    };

    CallCamera(Context context, int width, int height, SurfaceHolder preview_surface, Surface encoder_surface) throws CameraAccessException, SecurityException {
        this.context = context;
        this.preview_surface = preview_surface;
        this.width = width;
        this.height = height;
        this.encoder_surface = encoder_surface;
        preview_surface.addCallback(preview_surface_callback);
        camera_manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        // Find primary front and back cameras
        // TODO: Make it actually find the back camera
        String[] full_camera_list = camera_manager.getCameraIdList();
        boolean found_front = false, found_back = false;
        for (String camera : full_camera_list) {
            CameraCharacteristics cr = camera_manager.getCameraCharacteristics(camera);
            int facing = cr.get(CameraCharacteristics.LENS_FACING);
            if (!found_front && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                Log.d(TAG, "Found front camera id=" + camera);
                camera_list = new String[]{camera};
                found_front = true;
            }

            if (found_front)
                break;
        }
        // TODO: Exception if no cameras were found
        preview_surface.setFixedSize(width, height);
        camera_manager.openCamera(camera_list[0], camera_device_callback, null);
    }

    private void start_if_ready() {
        if (started || !start_requested || !surface_ready || !camera_ready)
            return;

        started = true;
        Log.d(TAG, "Starting");

        ArrayList<OutputConfiguration> output_configs = new ArrayList<>();
        output_configs.add(new OutputConfiguration(preview_surface.getSurface()));
        output_configs.add(new OutputConfiguration(encoder_surface));
        SessionConfiguration session_config = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                output_configs,
                context.getMainExecutor(),  // TODO: should run on another thread
                camera_session_state_callback
        );

        try {
            camera_current.createCaptureSession(session_config);
        } catch (CameraAccessException e) {
            // TODO: Callback to parent for toast message
            Log.d(TAG, "Failed to create capture session: CameraAccessException: " + e.getMessage());
            camera_ready = false;
        }
    }

    private void stop_if_unready() {
        if (!started || (!stop_requested && surface_ready && camera_ready))
            return;

        Log.d(TAG, "Stopping");

        if (camera_session != null && camera_current != null) {
            try {
                camera_session.stopRepeating();
                camera_session.close();
            } catch (CameraAccessException ignored) {}
        }
        camera_session = null;

        stop_requested = false;
        started = false;
    }

    public void start() {
        Log.d(TAG, "Start requested");
        start_requested = true;
        start_if_ready();
    }

    public void stop() {
        Log.d(TAG, "Stop requested");
        stop_requested = true;
        start_requested = false;
        stop_if_unready();
    }

    public void close() {
        Log.d(TAG, "Close requested");
        if (started)
            stop();
        if (camera_current != null)
            camera_current.close();
        camera_current = null;
    }
}
