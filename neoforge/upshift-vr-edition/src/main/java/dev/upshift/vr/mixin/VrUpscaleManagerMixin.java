package dev.upshift.vr.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.upshift.config.Upscaler;
import dev.upshift.render.UpscaleManager;
import dev.upshift.vr.VrRuntime;
import dev.upshift.vr.render.VrFrameGen;
import dev.upshift.vr.render.VrTaauPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-eye override for UpscaleManager.beginWorld/endWorld when a VR session is
 * active. The base GameRendererMixin sets vrEye through VrRuntime and expects
 * this mixin to intercept only on the eye renders that come after it. When
 * vrEye remains {@code -1} (the flat path or non-VR), all injections bail out
 * immediately and the normal single-pass path runs unchanged.
 */
@Mixin(value = {UpscaleManager.class})
public abstract class VrUpscaleManagerMixin {

    @Unique
    private int vrEye = -1;

    @Inject(method = {"beginWorld"}, at = {@At(value = "HEAD")}, cancellable = true)
    private void upshiftVr$beginWorld(CallbackInfoReturnable<RenderTarget> cir) {
        VrRuntime vr = VrRuntime.get();
        int eye = vr.beginEyePass();
        if (eye < 0) return; // Flat path – let base UpscaleManager.handle it.
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
            cir.setReturnValue(mgr.worldTarget);
        } catch (Throwable t) {
            dev.upshift.Upshift.LOG.error("VR could not allocate internal render target for eye " + eye, t);
            mgr.active = Upscaler.OFF;
            cir.setReturnValue(null);
        }
    }

    @Inject(method = {"endWorld"}, at = {@At(value = "HEAD")}, cancellable = true)
    private void upshiftVr$endWorld(RenderTarget main, CallbackInfo ci) {
        if (this.vrEye < 0) return; // Flat path – let base handle it.
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

            if (mgr.activeUpscaler() == Upscaler.TAAU && mgr.jitterValid
                    && (camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState) != null) {
                VrTaauPass eyeTaau = vr.taauForEye(eye);
                Matrix4f prevRotation = vr.previousRotationForEye(eye);
                boolean hasPrev = vr.hasPreviousRotation(eye);

                mgr.rotationScratch.rotation((Quaternionfc) mgr.rotationQuat.setFromNormalized((Matrix4fc) camera.viewRotationMatrix));

                if (!hasPrev) {
                    prevRotation.set((Matrix4fc) mgr.rotationScratch);
                    vr.setPreviousRotation(eye, mgr.rotationScratch);
                }

                float vrClampWeight = vr.vrClampWeight();
                com.mojang.blaze3d.systems.CommandEncoder enc = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
                source = eyeTaau.resolve(
                        enc,
                        source,
                        mgr.worldTarget.width,
                        mgr.worldTarget.height,
                        mgr.pristineProjection,
                        mgr.rotationScratch,
                        prevRotation,
                        vrClampWeight
                );

                vr.setPreviousRotation(eye, mgr.rotationScratch);

                if (eyeTaau.isBroken()) {
                    mgr.active = Upscaler.OFF;
                    vr.endEyePass(eye);
                    return;
                }
            }

            mgr.jitterValid = false;
            if (mgr.present(main, source)) {
                mgr.hasPresented = true;
            }
        } catch (Throwable t) {
            dev.upshift.Upshift.LOG.error("VR upscale failed for eye " + eye, t);
            mgr.active = Upscaler.OFF;
        }
        vr.endEyePass(eye);

        if (vr.isStereo() && dev.upshift.vr.config.VrConfig.get().vrFrameGenEnabled) {
            VrFrameGen.get().captureWorld(eye);
        }
    }

    @Unique
    private void applyVrJitter(int width, int height) {
        UpscaleManager mgr = (UpscaleManager) (Object) this;
        mgr.jitterValid = false;
        CameraRenderState camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (camera == null || !camera.initialized) return;
        mgr.pristineProjection.set((Matrix4fc) camera.projectionMatrix);
        int idx = VrRuntime.get().jitterIndex() & 7;
        float[] offset = UpscaleManager.JITTER[idx];
        float x = (offset[0] - 0.5f) * 2.0f / (float) width;
        float y = (offset[1] - 0.5f) * 2.0f / (float) height;
        float w = camera.projectionMatrix.m32();
        camera.projectionMatrix.m20(camera.projectionMatrix.m20() + x * w);
        camera.projectionMatrix.m21(camera.projectionMatrix.m21() + y * w);
        mgr.jitterValid = true;
    }
}
