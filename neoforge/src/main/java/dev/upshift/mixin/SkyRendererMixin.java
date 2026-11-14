package dev.upshift.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value={SkyRenderer.class})
public abstract class SkyRendererMixin {
    @Redirect(method={"renderSkyDisc"}, at={@At(value="FIELD", target="Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")})
    private RenderTarget upshift$skyDiscTarget(SkyRenderer self) {
        return SkyRendererMixin.live();
    }

    @Redirect(method={"renderDarkDisc"}, at={@At(value="FIELD", target="Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")})
    private RenderTarget upshift$darkDiscTarget(SkyRenderer self) {
        return SkyRendererMixin.live();
    }

    @Redirect(method={"renderSun"}, at={@At(value="FIELD", target="Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")})
    private RenderTarget upshift$sunTarget(SkyRenderer self) {
        return SkyRendererMixin.live();
    }

    @Redirect(method={"renderMoon"}, at={@At(value="FIELD", target="Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")})
    private RenderTarget upshift$moonTarget(SkyRenderer self) {
        return SkyRendererMixin.live();
    }

    @Redirect(method={"renderStars"}, at={@At(value="FIELD", target="Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")})
    private RenderTarget upshift$starsTarget(SkyRenderer self) {
        return SkyRendererMixin.live();
    }

    @Redirect(method={"renderSunriseAndSunset"}, at={@At(value="FIELD", target="Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")})
    private RenderTarget upshift$sunriseTarget(SkyRenderer self) {
        return SkyRendererMixin.live();
    }

    @Redirect(method={"renderEndSky"}, at={@At(value="FIELD", target="Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")})
    private RenderTarget upshift$endSkyTarget(SkyRenderer self) {
        return SkyRendererMixin.live();
    }

    @Redirect(method={"renderEndFlash"}, at={@At(value="FIELD", target="Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;")})
    private RenderTarget upshift$endFlashTarget(SkyRenderer self) {
        return SkyRendererMixin.live();
    }

    private static RenderTarget live() {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }
}
