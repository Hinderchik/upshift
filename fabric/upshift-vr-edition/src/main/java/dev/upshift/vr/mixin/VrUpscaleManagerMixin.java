package dev.upshift.vr.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.upshift.config.Upscaler;
import dev.upshift.render.UpscaleManager;
import dev.upshift.vr.VrRuntime;
import dev.upshift.vr.render.VrFrameGen;
import dev.upshift.vr.render.VrTaauPass;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;

@Mixin(value = {UpscaleManager.class})
public abstract class VrUpscaleManagerMixin {
    @Shadow
    @Final
    @Mutable
    private dev.upshift.render.TaauPass taau;
    @Shadow
    @Final
    @Mutable
    private Matrix4f pristineProjection;
    @Shadow
    @Final
    @Mutable
    private Matrix4f previousRotation;
    @Shadow
    @Final
    @Mutable
    private Matrix4f rotationScratch;
    @Shadow
    @Final
    @Mutable
    private Quaternionf rotationQuat;
    @Shadow
    @Final
    @Mutable
    private int[] sizeScratch;
    @Shadow
    @Mutable
    private boolean hasPreviousRotation;
    @Shadow
    @Mutable
    private boolean jitterValid;
    @Shadow
    @Mutable
    private int jitterIndex;
    @Shadow
    @Final
    @Mutable
    private RenderTarget worldTarget;
    @Shadow
    @Mutable
    private dev.upshift.config.Upscaler active;
    @Shadow
    @Final
    @Mutable
    private dev.upshift.render.Fsr1Pass fsr1;
    @Shadow
    @Final
    @Mutable
    private dev.upshift.render.FastUpscalePass fast;
    @Shadow
    @Final
    @Mutable
    private dev.upshift.render.NisPass nis;
    @Shadow
    @Mutable
    private boolean hasPresented;

    @Unique
    private int vrEye = -1;

    @Inject(method = {"beginWorld"}, at = {@At(value = "HEAD")}, cancellable = true)
    private void upshiftVr$beginWorld(CallbackInfoReturnable<RenderTarget> cir) {
        VrRuntime vr = VrRuntime.get();
        int eye = vr.beginEyePass();
        if (eye < 0) {
            return; // Flat path
        }
        this.vrEye = eye;

        UpscaleManager mgr = (UpscaleManager) (Object) this;
        dev.upshift.config.FsrConfig config = dev.upshift.config.FsrConfig.get();
        dev.upshift.Upshift.LOG.debug("VR beginWorld eye={}", eye);

        int[] internal = mgr.internalSize(Minecraft.getInstance().getWindow().getWidth(), Minecraft.getInstance().getWindow().getHeight());
        try {
            mgr.ensureWorldTarget(internal[0], internal[1]);
            if (mgr.activeUpscaler() == Upscaler.TAAU) {
                this.applyVrJitter(internal[0], internal[1]);
            }
            cir.setReturnValue(this.worldTarget);
        } catch (Throwable t) {
            dev.upshift.Upshift.LOG.error("VR could not allocate internal render target for eye " + eye, t);
            mgr.active = Upscaler.OFF;
            cir.setReturnValue(null);
        }
    }

    @Inject(method = {"endWorld"}, at = {@At(value = "HEAD")}, cancellable = true)
    private void upshiftVr$endWorld(RenderTarget main, CallbackInfo ci) {
        if (this.vrEye < 0) {
            return; // Flat path
        }
        int eye = this.vrEye;
        this.vrEye = -1;

        VrRuntime vr = VrRuntime.get();
        UpscaleManager mgr = (UpscaleManager) (Object) this;

        if (!mgr.activeUpscaler().isSpatial() || mgr.worldTarget == null || main == null) {
            vr.endEyePass(eye);
            return;
        }

        if (mgr.activeUpscaler() == Upscaler.FSR1 && mgr.fsr1.isBroken()) {
            mgr.active = Upscaler.OFF;
            vr.endEyePass(eye);
            return;
        }

        try {
            CameraRenderState camera;
            GpuTextureView source = mgr.worldTarget.getColorTextureView();

            if (mgr.activeUpscaler() == Upscaler.TAAU && this.jitterValid
                    && (camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState) != null) {
                // Per-eye temporal resolve using VR pass
                VrTaauPass eyeTaau = vr.taauForEye(eye);
                Matrix4f prevRotation = vr.previousRotationForEye(eye);
                boolean hasPrev = vr.hasPreviousRotation(eye);

                this.rotationScratch.rotation((Quaternionfc) this.rotationQuat.setFromNormalized((Matrix4fc) camera.viewRotationMatrix));

                if (!hasPrev) {
                    prevRotation.set((Matrix4fc) this.rotationScratch);
                    vr.setPreviousRotation(eye, this.rotationScratch);
                }

                float vrClampWeight = vr.vrClampWeight();
                source = eyeTaau.resolve(
                    RenderSystem.getDevice().createCommandEncoder(),
                    source,
                    mgr.worldTarget.width,
                    mgr.worldTarget.height,
                    this.pristineProjection,
                    this.rotationScratch,
                    prevRotation,
                    vrClampWeight
                );

                vr.setPreviousRotation(eye, this.rotationScratch);

                if (eyeTaau.isBroken()) {
                    mgr.active = Upscaler.OFF;
                    vr.endEyePass(eye);
                    return;
                }
            }

            this.jitterValid = false;
            if (mgr.present(main, source)) {
                mgr.hasPresented = true;
            }
        } catch (Throwable t) {
            dev.upshift.Upshift.LOG.error("VR upscale failed for eye " + eye, t);
            mgr.active = Upscaler.OFF;
        }
        vr.endEyePass(eye);

        // Capture world for VR frame generation (after present, before hand render)
        if (VrRuntime.get().isStereo() && dev.upshift.vr.config.VrConfig.get().vrFrameGenEnabled) {
            VrFrameGen.get().captureWorld(eye);
        }
    }

    @Unique
    private void applyVrJitter(int width, int height) {
        this.jitterValid = false;
        CameraRenderState camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (camera == null || !camera.initialized) {
            return;
        }
        this.pristineProjection.set((Matrix4fc) camera.projectionMatrix);
        int idx = VrRuntime.get().jitterIndex() & 7;
        float[] offset = dev.upshift.render.UpscaleManager.JITTER[idx];
        float x = (offset[0] - 0.5f) * 2.0f / (float) width;
        float y = (offset[1] - 0.5f) * 2.0f / (float) height;
        float w = camera.projectionMatrix.m32();
        camera.projectionMatrix.m20(camera.projectionMatrix.m20() + x * w);
        camera.projectionMatrix.m21(camera.projectionMatrix.m21() + y * w);
        this.jitterValid = true;
    }
}