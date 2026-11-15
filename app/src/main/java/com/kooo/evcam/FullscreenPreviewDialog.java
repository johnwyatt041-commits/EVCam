package com.kooo.evcam;

import android.app.Dialog;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

/** Fullscreen preview reuses the existing camera without a correction pipeline. */
public class FullscreenPreviewDialog extends Dialog {
    private final String cameraPosition;
    private AutoFitTextureView textureView;
    private Surface surface;
    private SingleCamera attachedCamera;

    public FullscreenPreviewDialog(@NonNull Context context, String cameraPosition) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.cameraPosition = cameraPosition;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.dialog_fullscreen_preview);
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        textureView = findViewById(R.id.texture_fullscreen);
        TextView label = findViewById(R.id.tv_camera_label);
        label.setText("front".equals(cameraPosition) ? "前" : "back".equals(cameraPosition) ? "后" : "left".equals(cameraPosition) ? "左" : "右");
        findViewById(R.id.btn_close).setOnClickListener(v -> dismiss());
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int w, int h) {
                attach(texture);
                applyPreviewTransform();
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int w, int h) {
                applyPreviewTransform();
            }
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) { detach(); return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {}
        });
        if (textureView.isAvailable()) attach(textureView.getSurfaceTexture());
    }

    private void attach(SurfaceTexture texture) {
        detach();
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager == null) return;
        attachedCamera = manager.getCamera(cameraPosition);
        if (attachedCamera == null) return;
        android.util.Size size = attachedCamera.getPreviewSize();
        if (size != null) {
            textureView.setAspectRatio(size.getWidth(), size.getHeight());
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        }
        surface = new Surface(texture);
        attachedCamera.setFullscreenPreviewSurface(surface, texture);
    }

    private void applyPreviewTransform() {
        if (textureView == null) {
            return;
        }
        AppConfig cfg = new AppConfig(getContext());
        int rotation = "custom".equals(cfg.getCarModel()) ? cfg.getCameraRotation(cameraPosition) : 0;
        com.kooo.evcam.ui.PreviewTransformHelper.applyMainPreviewTransform(textureView, getContext(), cameraPosition, rotation);
    }

    private void detach() {
        if (attachedCamera != null) {
            attachedCamera.clearFullscreenPreviewSurface();
            attachedCamera = null;
        }
        if (surface != null) { surface.release(); surface = null; }
    }

    @Override public void dismiss() { detach(); super.dismiss(); }
}
