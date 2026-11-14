package dev.upshift.mixin;

import dev.upshift.optim.ClientOptimizations;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops animated textures from being advanced when nobody can see them (the
 * window lost focus) and optionally lowers their update rate. Every skipped
 * frame saves the per-sprite animation tick as well as the GPU uploads that
 * follow it.
 */
@Mixin(value={TextureAtlas.class})
public abstract class TextureAtlasMixin {
    @Unique
    private int upshift$frameCounter;

    @Inject(method={"tick"}, at={@At(value="HEAD")}, cancellable=true)
    private void upshift$throttleAnimation(CallbackInfo callback) {
        if (ClientOptimizations.shouldSkipAtlasTick(this.upshift$frameCounter++)) {
            callback.cancel();
        }
    }
}
