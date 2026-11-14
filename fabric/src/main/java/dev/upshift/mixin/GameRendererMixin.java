package dev.upshift.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.upshift.render.FrameGenPresenter;
import dev.upshift.render.UpscaleManager;
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

@Mixin(value={GameRenderer.class})
public abstract class GameRendererMixin {
    @Shadow
    @Final
    @Mutable
    private RenderTarget mainRenderTarget;
    @Unique
    private RenderTarget upshift$windowTarget;

    @Inject(method={"renderLevel"}, at={@At(value="HEAD")}, cancellable=true)
    private void upshift$beginWorld(DeltaTracker tracker, CallbackInfo ci) {
        UpscaleManager.get().probeFps();
        if (UpscaleManager.get().menuFrame(this.mainRenderTarget)) {
            ci.cancel();
            return;
        }
        RenderTarget internal = UpscaleManager.get().beginWorld();
        if (internal != null) {
            this.upshift$windowTarget = this.mainRenderTarget;
            this.mainRenderTarget = internal;
        }
    }

    @Inject(method={"renderLevel"}, at={@At(value="RETURN")})
    private void upshift$endWorld(DeltaTracker tracker, CallbackInfo ci) {
        RenderTarget window = this.upshift$windowTarget;
        if (window != null) {
            this.upshift$windowTarget = null;
            this.mainRenderTarget = window;
            UpscaleManager.get().endWorld(window);
        }
        FrameGenPresenter.get().captureWorld();
        UpscaleManager.get().recordPose();
    }
}
