package dev.upshift.vr.render;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4f;

/**
 * Snapshot of a hand/controller layer for native-rate compositing.
 * Captured per-eye, per-frame to avoid hand reprojection artifacts.
 */
public final class HandLayerSnapshot {
    public int eye; // 0 = left, 1 = right
    public GpuTexture texture;
    public GpuTextureView view;
    public int width;
    public int height;
    public Matrix4f pose; // Controller pose at capture time
    public long frameIndex;
    public long captureTimeNs;

    public void close() {
        if (this.view != null) {
            this.view.close();
            this.view = null;
        }
        // Note: texture is owned by the render target, don't close it
    }

    public boolean isValid() {
        return this.texture != null && this.view != null && this.width > 0 && this.height > 0;
    }
}