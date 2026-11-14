package dev.upshift.vr.mixin;

import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.upshift.render.FrameGenPresenter;
import dev.upshift.overlay.PerfStats;
import dev.upshift.vr.VrRuntime;
import dev.upshift.vr.overlay.VrPerformanceOverlay;
import dev.upshift.vr.render.VrFrameGen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

@Mixin(value = {Minecraft.class})
public abstract class VrMinecraftMixin {
    @Inject(method = {"renderFrame"}, at = {@At(value = "HEAD")})
    private void upshiftVr$beginFrame(boolean tick, CallbackInfo ci) {
        VrRuntime vr = VrRuntime.get();
        vr.beginFrame();
        vr.updateHmdPose();
        PerfStats.get().beginRenderFrame();
    }

    @WrapOperation(method = {"renderFrame"}, at = {@At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V")})
    private void upshiftVr$presentWithGeneratedFrames(GpuSurface surface, CommandEncoder encoder, com.mojang.blaze3d.textures.GpuTextureView view, Operation<Void> original) {
        VrRuntime vr = VrRuntime.get();
        // In VR mode, use VR frame generation with HMD pose
        if (vr.isStereo()) {
            // We need both eye views - they're presented separately in Vivecraft
            // For now, delegate to VrFrameGen which handles both eyes
            VrFrameGen.get().presentGeneratedFrames(surface, encoder, 
                Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView(),
                view // This is the second eye or combined view
            );
            return;
        }
        FrameGenPresenter.get().presentRealFrame(surface, encoder, view, original::call);
    }

    @WrapOperation(method = {"renderFrame"}, at = {@At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V")})
    private void upshiftVr$measurePresent(GpuSurface surface, Operation<Void> original) {
        original.call(surface);
        PerfStats.get().endRenderFrame();
        VrRuntime.get().recordVrPresent();
    }

    @Inject(method = {"renderFrame"}, at = {@At(value = "RETURN")})
    private void upshiftVr$afterFrame(boolean tick, CallbackInfo ci) {
        VrRuntime.get().endFrame();
        VrFrameGen.get().afterFrame();
        FrameGenPresenter.get().afterFrame();
    }
}