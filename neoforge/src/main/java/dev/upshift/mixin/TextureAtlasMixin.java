package dev.upshift.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.upshift.optim.UpshiftAnimationState;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tags each {@code SpriteContents$AnimationState} with its sprite's resource
 * location so {@link AnimationStateMixin} can gate animation advancement per
 * sprite.
 *
 * <p>The tagging happens at the only call site of
 * {@code TextureAtlasSprite.createAnimationState} inside
 * {@code TextureAtlas.upload}. Sodium only injects into {@code upload} at
 * {@code RETURN} (to wrap the whole method), so our {@code @WrapOperation}
 * on the internal call is safe and runs before Sodium's return injection.</p>
 *
 * <p>{@code require = 0} ensures that if Sodium ever refactors the upload path
 * the mixin degrades gracefully (whitelist becomes inert) instead of crashing
 * the game.</p>
 */
@Mixin(value = TextureAtlas.class)
public abstract class TextureAtlasMixin {
    @Unique
    private int upshift$frameCounter;

    @Inject(method = "tick", at = @At(value = "HEAD"), cancellable = true)
    private void upshift$throttleAnimation(CallbackInfo callback) {
        // The animation throttle is now handled by per-sprite gating in AnimationStateMixin.
        // This inject remains as a fallback for when the mixin fails to apply.
    }

    /**
     * Wraps the call to {@code TextureAtlasSprite.createAnimationState} inside
     * {@code TextureAtlas.upload} to tag the created {@code AnimationState}
     * with the sprite's resource location.
     */
    @WrapOperation(
        method = "upload",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;createAnimationState(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;I)Lnet/minecraft/client/renderer/texture/SpriteContents$AnimationState;",
            ordinal = 0
        ),
        require = 0
    )
    private SpriteContents.AnimationState upshift$tagAnimationState(
        TextureAtlasSprite sprite,
        com.mojang.blaze3d.buffers.GpuBufferSlice slice,
        int mipLevel,
        Operation<SpriteContents.AnimationState> original
    ) {
        SpriteContents.AnimationState state = original.call(sprite, slice, mipLevel);
        if (state instanceof UpshiftAnimationState upshiftState) {
            upshiftState.upshift$setSpriteName(sprite.contents().name());
        }
        return state;
    }
}