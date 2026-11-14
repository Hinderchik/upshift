package dev.upshift.vr.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.upshift.render.UpscaleManager;
import dev.upshift.vr.VrRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = {GameRenderer.class})
public abstract class VrGameRendererMixin {
    @Shadow
    @Final
    @Mutable
    private RenderTarget mainRenderTarget;

    @Unique
    private RenderTarget upshiftVr$windowTarget;
    @Unique
    private int upshiftVr$currentEye = -1;

    @Inject(method = {"renderLevel"}, at = {@At(value = "HEAD")}, cancellable = true)
    private void upshiftVr$beginEyePass(DeltaTracker tracker, CallbackInfo ci) {
        VrRuntime vr = VrRuntime.get();
        int eye = vr.beginEyePass();
        if (eye < 0) {
            // Not VR, let base mod handle it
            UpscaleManager.get().probeFps();
            if (UpscaleManager.get().menuFrame(this.mainRenderTarget)) {
                ci.cancel();
                return;
            }
            RenderTarget internal = UpscaleManager.get().beginWorld();
            if (internal != null) {
                this.upshiftVr$windowTarget = this.mainRenderTarget;
                this.mainRenderTarget = internal;
            }
            this.upshiftVr$currentEye = -1;
            return;
        }

        this.upshiftVr$currentEye = eye;
        // VR path: the base mod already set up internal target in beginWorld
        // We just need to apply per-eye jitter and rotation
        UpscaleManager mgr = UpscaleManager.get();
        if (mgr.activeUpscaler().isSpatial() && mgr.scalingActive()) {
            vr.taauForEye(eye).invalidate(); // Will be re-ensured in endWorld
        }
    }

    @Inject(method = {"renderLevel"}, at = {@At(value = "RETURN")})
    private void upshiftVr$endEyePass(DeltaTracker tracker, CallbackInfo ci) {
        int eye = this.upshiftVr$currentEye;
        if (eye < 0) {
            // Flat path handled by base mod
            RenderTarget window = this.upshiftVr$windowTarget;
            if (window != null) {
                this.upshiftVr$windowTarget = null;
                this.mainRenderTarget = window;
                UpscaleManager.get().endWorld(window);
            }
            dev.upshift.render.FrameGenPresenter.get().captureWorld();
            UpscaleManager.get().recordPose();
            return;
        }

        // VR path: upscale this eye separately
        UpscaleManager mgr = UpscaleManager.get();
        VrRuntime vr = VrRuntime.get();
        if (mgr.activeUpscaler() == dev.upshift.config.Upscaler.TAAU && mgr.scalingActive() && mgr.worldTarget != null) {
            try {
                dev.upshift.vr.render.VrTaauPass eyeTaau = vr.taauForEye(eye);
                // The base UpscaleManager.endWorld does the resolve; we need to hook it per-eye
                // For now, we defer to base mod and add per-eye history swap
            } catch (Throwable t) {
                dev.upshift.Upshift.LOG.error("VR per-eye upscale failed for eye " + eye, t);
            }
        }
        vr.endEyePass(eye);
    }
}