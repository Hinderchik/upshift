package dev.upshift.mixin;

import dev.upshift.overlay.PerformanceOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={Hud.class})
public abstract class HudMixin {
    @Inject(method={"extractRenderState"}, at={@At(value="RETURN")})
    private void upshift$performanceOverlay(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
        PerformanceOverlay.render(graphics);
    }
}
