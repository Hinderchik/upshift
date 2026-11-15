package dev.upshift.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.upshift.optim.ClientOptimizations;
import dev.upshift.optim.UpshiftAnimationState;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@code SpriteContents$AnimationState} that gates animation advancement
 * via {@link ClientOptimizations#shouldAdvanceAnimation(ResourceLocation)}.
 *
 * <p>Sodium's {@code SpriteContentsTickerMixin} injects at {@code HEAD} of {@code tick}
 * to capture the active state and at {@code HEAD} of {@code needsToDraw} (via
 * {@code CallbackInfoReturnable}) to optionally suppress drawing when
 * {@code animateOnlyVisibleTextures} is on. By cancelling our {@code needsToDraw}
 * injection we prevent the frame from being uploaded, which is exactly what Sodium
 * expects: the ticker still runs, the sprite remains "inactive" for this tick, and
 * Sodium's tracking continues to work correctly.</p>
 */
@Mixin(value = SpriteContents.AnimationState.class)
public abstract class AnimationStateMixin implements UpshiftAnimationState {
    @Unique
    private Identifier upshift$spriteName;

    @Override
    public Identifier upshift$getSpriteName() {
        return this.upshift$spriteName;
    }

    @Override
    public void upshift$setSpriteName(Identifier name) {
        this.upshift$spriteName = name;
    }

    /**
     * Cancels the {@code needsToDraw} call when the sprite should not animate this tick.
     * This preserves Sodium's activity tracking because the {@code tick} method
     * still runs (Sodium's {@code captureActiveState} injects at HEAD of {@code tick}).
     */
    @Inject(method = "needsToDraw", at = @At(value = "HEAD"), cancellable = true)
    private void upshift$gateNeedsToDraw(CallbackInfoReturnable<Boolean> cir) {
        if (this.upshift$spriteName != null) {
            if (ClientOptimizations.shouldAdvanceAnimation(this.upshift$spriteName)) {
                cir.setReturnValue(true);
                cir.cancel();
            }
        }
    }
}