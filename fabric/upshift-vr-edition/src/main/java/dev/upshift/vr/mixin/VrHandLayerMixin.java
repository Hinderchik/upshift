package dev.upshift.vr.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.upshift.vr.VrRuntime;
import dev.upshift.vr.config.VrConfig;
import dev.upshift.vr.render.HandLayerSnapshot;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures hand/controller render target after the hand pass for native-rate compositing.
 * In Vivecraft, hands are typically rendered in a separate pass after the world.
 */
@Mixin(value = {GameRenderer.class})
public abstract class VrHandLayerMixin {
    @Unique
    private boolean vrHandPassActive = false;

    @Inject(method = {"renderHand"}, at = {@At(value = "HEAD")})
    private void upshiftVr$beginHandPass(DeltaTracker tick, CallbackInfo ci) {
        VrRuntime vr = VrRuntime.get();
        if (vr.isStereo() && dev.upshift.vr.config.VrConfig.get().vrFrameGenHandNative) {
            this.vrHandPassActive = true;
        }
    }

    @Inject(method = {"renderHand"}, at = {@At(value = "RETURN")})
    private void upshiftVr$endHandPass(DeltaTracker tick, CallbackInfo ci) {
        if (this.vrHandPassActive) {
            this.vrHandPassActive = false;
            // The main render target now contains the hand layer
            // We need to capture it per-eye
            // Note: In Vivecraft, renderHand might be called once per eye or once for both
            // We'll capture for the current eye
            VrRuntime vr = VrRuntime.get();
            int eye = vr.isStereo() ? 0 : -1; // Simplified - would need proper eye tracking
            if (eye >= 0) {
                Minecraft minecraft = Minecraft.getInstance();
                RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
                if (target != null) {
                    vr.captureHandLayer(eye, target);
                }
            }
        }
    }
}