package dev.upshift.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.upshift.render.FastUpscalePass;
import dev.upshift.render.FrameGenPresenter;
import dev.upshift.render.Fsr1Pass;
import dev.upshift.render.NisPass;
import dev.upshift.render.TaauPass;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={RenderPipelines.class})
public abstract class RenderPipelinesMixin {
    @Inject(method={"getStaticPipelines"}, at={@At(value="RETURN")}, cancellable=true)
    private static void upshift$appendUpscalePipelines(CallbackInfoReturnable<List<RenderPipeline>> cir) {
        List vanilla = (List)cir.getReturnValue();
        ArrayList<RenderPipeline> ours = new ArrayList<RenderPipeline>(vanilla.size() + 4);
        ours.addAll(vanilla);
        ours.addAll(Fsr1Pass.pipelines());
        ours.addAll(FastUpscalePass.pipelines());
        ours.addAll(NisPass.pipelines());
        ours.addAll(FrameGenPresenter.pipelines());
        ours.addAll(TaauPass.pipelines());
        cir.setReturnValue(ours);
    }
}
