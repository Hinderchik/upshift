package dev.upshift.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.upshift.config.FsrConfig;
import dev.upshift.input.ToggleKey;
import dev.upshift.optim.ClientOptimizations;
import dev.upshift.overlay.PerfStats;
import dev.upshift.render.FrameGenPresenter;
import dev.upshift.render.UpscaleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class)
public abstract class MinecraftMixin {
    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"))
    private void upshift$presentWithGeneratedFrames(GpuSurface surface, CommandEncoder encoder, GpuTextureView view, Operation<Void> original) {
        FrameGenPresenter.get().presentRealFrame(surface, encoder, view, original::call);
    }

    @Inject(method = "renderFrame", at = @At(value = "HEAD"))
    private void upshift$beginFrame(boolean tick, CallbackInfo ci) {
        PerfStats.get().beginRenderFrame();
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"))
    private void upshift$measurePresent(GpuSurface surface, Operation<Void> original) {
        original.call(surface);
        PerfStats.get().endRenderFrame();
    }

    @Inject(method = "renderFrame", at = @At(value = "RETURN"))
    private void upshift$afterFrame(boolean tick, CallbackInfo ci) {
        FrameGenPresenter.get().afterFrame();
    }

    /**
     * Polls the toggle key at the start of each client tick and handles upscaling pause.
     * Also calls {@link ClientOptimizations#beginClientTick()} for the global animation counter.
     */
    @Inject(method = "tick", at = @At(value = "HEAD"))
    private void upshift$pollToggleKey(CallbackInfo ci) {
        ClientOptimizations.beginClientTick();

        if (ToggleKey.MAPPING.consumeClick()) {
            FsrConfig config = FsrConfig.get();
            config.upscalingPaused = !config.upscalingPaused;
            config.save();

            String state = config.upscalingPaused ? "paused" : "active";
            dev.upshift.Upshift.LOG.info("toggle upscaling: {}", state);

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                Component msg = Component.translatable(
                    config.upscalingPaused ? "upshift.toggle.paused" : "upshift.toggle.active"
                );
                minecraft.gui.hud.setOverlayMessage(msg, false);
            }

            // If we're in a world, force a re-evaluation on next frame
            if (minecraft.level != null) {
                UpscaleManager.get().active = dev.upshift.config.Upscaler.OFF;
            }
        }
    }
}