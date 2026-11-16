package dev.upshift.mixin;

import dev.upshift.optim.ClientOptimizations;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs before vanilla's frustum / entity-view-distance test, so a culled entity
 * never reaches the (much more expensive) render-state extraction stage.
 */
@Mixin(value={EntityRenderDispatcher.class})
public abstract class EntityCullMixin {
    @Inject(method={"shouldRender"}, at={@At(value="HEAD")}, cancellable=true)
    private void upshift$cullEntity(Entity entity, Frustum frustum, double cameraX, double cameraY, double cameraZ,
                                    CallbackInfoReturnable<Boolean> callback) {
        if (ClientOptimizations.cullEntity(entity)) {
            callback.setReturnValue(false);
        }
    }
}
