package dev.upshift.optim;

import net.minecraft.resources.Identifier;

/**
 * Interface implemented by {@link net.minecraft.client.renderer.texture.SpriteContents$AnimationState}
 * via mixin to expose the sprite name for whitelist matching.
 */
public interface UpshiftAnimationState {
    Identifier upshift$getSpriteName();
    void upshift$setSpriteName(Identifier name);
}