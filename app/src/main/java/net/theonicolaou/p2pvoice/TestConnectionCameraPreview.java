package net.theonicolaou.p2pvoice;

import android.content.Context;
import android.hardware.Camera;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.IOException;

public class TestConnectionCameraPreview extends SurfaceView implements SurfaceHolder.Callback {
    private Camera camera;
    private Context context;

    public TestConnectionCameraPreview(Context context, Camera camera) {
        super(context);
        this.context = context;
        this.camera = camera;
        getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        try {
            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (IOException e) {
            Toast.makeText(context, R.string.test_call_camera_preview_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (holder.getSurface() == null)
            return;

        try {
            camera.stopPreview();
        } catch (Exception ignored) {}

        try {
            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (IOException e) {
            Toast.makeText(context, R.string.test_call_camera_preview_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (holder.getSurface() == null)
            return;

        try {
            camera.stopPreview();
        } catch (Exception ignored) {}
    }
}
