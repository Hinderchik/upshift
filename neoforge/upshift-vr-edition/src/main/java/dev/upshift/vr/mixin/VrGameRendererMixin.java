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

/**
 * VR-aware wrapper around GameRenderer.renderLevel. Two HEAD/RETURN hooks run
 * around every world render pass. When a live Vivecraft session is detected the
 * addon handles both eyes itself and skips the flat path so the base mod never
 * runs its own beginWorld / endWorld for stereo passes. Without a VR session
 * the addon acts as an exact pass-through of the base GameRendererMixin.
 */
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
            // No active VR session – replicate the base GameRendererMixin flat
            // path exactly so TAAU jitter and temporal state advance normally.
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

        // VR path: stash the window target and enter per-eye mode. The base
        // GameRendererMixin would also call beginWorld here, but we short-circuit
        // it by cancelling; vrEye inside VrUpscaleManagerMixin will stay -1 so
        // its injects are skipped too.
        this.upshiftVr$currentEye = eye;
        UpscaleManager mgr = UpscaleManager.get();
        if (mgr.activeUpscaler().isSpatial() && mgr.scalingActive()) {
            vr.taauForEye(eye).invalidate();
        }
    }

    @Inject(method = {"renderLevel"}, at = {@At(value = "RETURN")})
    private void upshiftVr$endEyePass(DeltaTracker tracker, CallbackInfo ci) {
        int eye = this.upshiftVr$currentEye;
        if (eye < 0) {
            // Flat path handled by base mod; restore window target the same way.
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

        // VR path: undo what beginEyePass stashed. The per-eye temporal resolve
        // (and optional hand-frame gen capture) lives inside VrUpscaleManagerMixin
        // because there we can access the shadowed fields from the base UpscaleManager.
        RenderTarget window = this.upshiftVr$windowTarget;
        if (window != null) {
            this.upshiftVr$windowTarget = null;
            this.mainRenderTarget = window;
            UpscaleManager.get().endWorld(window);
        }
        dev.upshift.render.FrameGenPresenter.get().captureWorld();
        UpscaleManager.get().recordPose();
        VrRuntime.get().endEyePass(eye);
        this.upshiftVr$currentEye = -1;
    }
}
