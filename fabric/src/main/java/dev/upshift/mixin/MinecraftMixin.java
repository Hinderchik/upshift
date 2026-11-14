package dev.upshift.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.upshift.overlay.PerfStats;
import dev.upshift.render.FrameGenPresenter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={Minecraft.class})
public abstract class MinecraftMixin {
    @WrapOperation(method={"renderFrame"}, at={@At(value="INVOKE", target="Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V")})
    private void upshift$presentWithGeneratedFrames(GpuSurface surface, CommandEncoder encoder, GpuTextureView view, Operation<Void> original) {
        FrameGenPresenter.get().presentRealFrame(surface, encoder, view, original::call);
    }

    @Inject(method={"renderFrame"}, at={@At(value="HEAD")})
    private void upshift$beginFrame(boolean tick, CallbackInfo ci) {
        PerfStats.get().beginRenderFrame();
    }

    @WrapOperation(method={"renderFrame"}, at={@At(value="INVOKE", target="Lcom/mojang/blaze3d/systems/GpuSurface;present()V")})
    private void upshift$measurePresent(GpuSurface surface, Operation<Void> original) {
        original.call(surface);
        PerfStats.get().endRenderFrame();
    }

    @Inject(method={"renderFrame"}, at={@At(value="RETURN")})
    private void upshift$afterFrame(boolean tick, CallbackInfo ci) {
        FrameGenPresenter.get().afterFrame();
    }
}
