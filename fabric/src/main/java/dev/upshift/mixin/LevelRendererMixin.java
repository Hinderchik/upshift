package dev.upshift.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.upshift.render.UpscaleManager;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the depth buffer immediately after the world has been rendered
 * (opaque + translucent + particles) but BEFORE the hand, always-on-top,
 * and screen-effect passes that clear the depth.
 */
@Mixin(value={LevelRenderer.class})
public abstract class LevelRendererMixin {
    @Inject(method={"render"}, at={@At(value="RETURN")})
    private void upshift$captureDepth(
            com.mojang.blaze3d.resource.GraphicsResourceAllocator allocator,
            net.minecraft.client.DeltaTracker tracker,
            boolean outline,
            net.minecraft.client.renderer.state.level.CameraRenderState camera,
            org.joml.Matrix4fc projection,
            com.mojang.blaze3d.buffers.GpuBufferSlice fogBuffer,
            org.joml.Vector4f viewVolume,
            boolean hasClouds,
            CallbackInfo ci) {
        UpscaleManager um = UpscaleManager.get();
        if (um.activeUpscaler() != dev.upshift.config.Upscaler.TAAU) {
            return;
        }
        // The mainRenderTarget at this point is the internal target (swapped by GameRendererMixin).
        // Its depth still contains the world depth before hands/always-on-top.
        RenderTarget main = um.getWorldTargetForDepthCapture();
        if (main != null) {
            um.captureDepth(main);
        }
    }
}