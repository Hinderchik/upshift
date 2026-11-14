package dev.upshift.mixin;

import dev.upshift.optim.ClientOptimizations;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skips the render-state extraction of distant block entities. Extraction is
 * the expensive half: it creates a state object and copies model data before
 * anything is submitted, so a chest two hundred blocks away costs almost as
 * much as a visible one until it is rejected here.
 *
 * <p>The camera position comes from vanilla's own {@code prepare} call, which
 * already runs once per frame, so this adds no allocation and no extra
 * camera lookup.</p>
 */
@Mixin(value={BlockEntityRenderDispatcher.class})
public abstract class BlockEntityCullMixin {
    @Inject(method={"prepare"}, at={@At(value="HEAD")})
    private void upshift$captureCamera(Vec3 position, CallbackInfo callback) {
        ClientOptimizations.setCameraPosition(position);
    }

    @Inject(method={"tryExtractRenderState"}, at={@At(value="HEAD")}, cancellable=true)
    private void upshift$cullBlockEntity(BlockEntity blockEntity, float tickProgress,
                                         ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
                                         boolean offScreen,
                                         CallbackInfoReturnable<BlockEntityRenderState> callback) {
        if (ClientOptimizations.cullBlockEntity(blockEntity)) {
            callback.setReturnValue(null);
        }
    }
}
