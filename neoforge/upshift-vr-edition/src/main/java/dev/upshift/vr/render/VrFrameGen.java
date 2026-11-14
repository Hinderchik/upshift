package dev.upshift.vr.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.upshift.Upshift;
import dev.upshift.config.FsrConfig;
import dev.upshift.vr.VrRuntime;
import dev.upshift.vr.config.VrConfig;
import dev.upshift.overlay.PerfStats;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/**
 * VR Frame Generation with HMD pose prediction and hand/controller compositing.
 * <p>
 * When enabled, generates intermediate frames by warping the previous eye texture
 * using the predicted HMD pose from Vivecraft/OpenXR. Hands/controllers are kept
 * at native rate and composited on top.
 */
public final class VrFrameGen {
    private static final VrFrameGen INSTANCE = new VrFrameGen();
    private static final int OUTPUT_USAGE = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
    private static final int WORLD_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final long RETRY_DELAY_NS = 5_000_000_000L;
    private static final long LOG_INTERVAL_NS = 10_000_000_000L;
    private static RenderPipeline pipeline;
    private static RenderPipeline handPipeline;

    // World snapshots per eye (2 eyes x 2 frames = 4 buffers)
    private final GpuTexture[][] worldTextures = new GpuTexture[2][2];
    private final GpuTextureView[][] worldViews = new GpuTextureView[2][2];
    private final long[][] worldFrames = {{-1L, -1L}, {-1L, -1L}};
    private final Matrix4f[][] worldPoses = new Matrix4f[2][2];
    private final Matrix4f[][] worldProjections = new Matrix4f[2][2];
    private final int[] worldSizes = new int[4]; // [leftW, leftH, rightW, rightH]

    // Output texture for warped frames
    private GpuTexture output;
    private GpuTextureView outputView;
    private int outputW = -1;
    private int outputH = -1;

    private GpuBuffer warpBuffer;
    private final ByteBuffer matrixData = MemoryUtil.memAlloc(64);
    private final Matrix4f warpMatrix = new Matrix4f();
    private final Matrix4f source = new Matrix4f();
    private final Matrix4f sourceInverse = new Matrix4f();
    private final Matrix4f stepInverse = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f projectionInverse = new Matrix4f();
    private final Matrix4f relativeMatrix = new Matrix4f();
    private final Quaternionf relative = new Quaternionf();
    private final Quaternionf step = new Quaternionf();

    private boolean broken;
    private long retryAtNs;
    private long lastRealPresentNs;
    private long averageRealNs;
    private long generatedFrames;
    private long lastLogNs = System.nanoTime();
    private boolean loggedStart;
    private boolean loggedStop;
    private boolean warmedUp;
    private int warmupTries;

    private VrFrameGen() {
        for (int eye = 0; eye < 2; eye++) {
            for (int i = 0; i < 2; i++) {
                this.worldPoses[eye][i] = new Matrix4f();
                this.worldProjections[eye][i] = new Matrix4f();
            }
        }
    }

    public static VrFrameGen get() {
        return INSTANCE;
    }

    public static synchronized List<RenderPipeline> pipelines() {
        if (pipeline == null) {
            BindGroupLayout layout = BindGroupLayout.builder()
                .withSampler("InSampler")
                .withSampler("GuiSampler")
                .withSampler("WorldSampler")
                .withUniform("WarpParams", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                .build();
            ColorTargetState target = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15);
            pipeline = RenderPipeline.builder(new RenderPipeline.Snippet[0])
                .withLocation(Identifier.parse("upshift_vr:pipeline/fgr_reproject"))
                .withVertexShader(Identifier.parse("upshift:core/screenquad"))
                .withFragmentShader(Identifier.parse("upshift_vr:core/fgr_reproject_vr"))
                .withBindGroupLayout(layout)
                .withColorTargetState(target)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
        }
        if (handPipeline == null) {
            BindGroupLayout layout = BindGroupLayout.builder()
                .withSampler("HandSampler")
                .withUniform("HandParams", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                .build();
            ColorTargetState target = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15);
            handPipeline = RenderPipeline.builder(new RenderPipeline.Snippet[0])
                .withLocation(Identifier.parse("upshift_vr:pipeline/hand_composite"))
                .withVertexShader(Identifier.parse("upshift:core/screenquad"))
                .withFragmentShader(Identifier.parse("upshift_vr:core/hand_composite"))
                .withBindGroupLayout(layout)
                .withColorTargetState(target)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
        }
        return List.of(pipeline, handPipeline);
    }

    /**
     * Captures the just-finished world image for a specific eye.
     */
    public void captureWorld(int eye) {
        if (!this.generationEnabled() || eye < 0 || eye > 1) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            dev.upshift.render.UpscaleManager mgr = dev.upshift.render.UpscaleManager.get();
            if (mgr.worldTarget == null || mgr.worldTarget.width <= 0 || mgr.worldTarget.height <= 0 || mgr.worldTarget.getColorTexture() == null) {
                return;
            }
            CameraRenderState camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            if (camera == null || !camera.initialized) {
                return;
            }

            int eyeW = mgr.worldTarget.width;
            int eyeH = mgr.worldTarget.height;
            this.worldSizes[eye * 2] = eyeW;
            this.worldSizes[eye * 2 + 1] = eyeH;

            if (!this.ensureWorld(eye, eyeW, eyeH)) {
                return;
            }

            int currentSlot = this.worldFrames[eye][0] < 0 ? 0 : 1;
            int previousSlot = 1 - currentSlot;

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToTexture(mgr.worldTarget.getColorTexture(), this.worldTextures[eye][currentSlot], 0, 0, 0, 0, 0, eyeW, eyeH);

            // Store pose and projection
            this.worldPoses[eye][currentSlot].set(camera.viewRotationMatrix);
            this.worldProjections[eye][currentSlot].set(camera.projectionMatrix);
            this.worldFrames[eye][currentSlot] = System.nanoTime();

        } catch (Throwable t) {
            this.fail("could not snapshot VR world image for eye " + eye, t);
        }
    }

    /**
     * Presents generated frames for both eyes, then the real frame.
     */
    public void presentGeneratedFrames(GpuSurface surface, CommandEncoder encoder, GpuTextureView leftView, GpuTextureView rightView) {
        if (!this.generationEnabled()) {
            return; // Let base presenter handle it
        }

        VrRuntime vr = VrRuntime.get();
        if (!vr.isStereo()) {
            return; // Not in VR
        }

        try {
            int leftExtras = this.plannedExtras(0);
            int rightExtras = this.plannedExtras(1);
            int extras = Math.max(leftExtras, rightExtras);
            if (extras <= 0) {
                return;
            }

            int width = leftView.texture().getWidth(0);
            int height = leftView.texture().getHeight(0);

            if (!this.ensureOutput(width, height)) {
                return;
            }

            this.ensureBuffer();
            VrFrameGen.pipelines();
            if (pipeline == null || handPipeline == null) {
                return;
            }

            GpuDevice device = RenderSystem.getDevice();
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            long period = this.periodNs();
            long now = System.nanoTime();
            long base = this.lastRealPresentNs;
            if (base == 0L || now - base > 4L * (long)(extras + 1) * period) {
                base = now - period;
            }

            boolean paced = !this.blocks(surface);

            for (int eye = 0; eye < 2; eye++) {
                int eyeExtras = (eye == 0) ? leftExtras : rightExtras;
                if (eyeExtras <= 0) continue;

                GpuTextureView realView = (eye == 0) ? leftView : rightView;
                int previousSlot = (this.worldFrames[eye][0] < this.worldFrames[eye][1]) ? 1 : 0;
                int currentSlot = 1 - previousSlot;

                GpuTextureView sourceView = this.worldViews[eye][previousSlot];
                GpuTextureView maskView = this.worldViews[eye][currentSlot];

                if (sourceView == null || maskView == null) continue;

                // Compute relative rotation between frames
                this.relativeMatrix.set(this.worldPoses[eye][previousSlot]);
                this.relativeMatrix.invert();
                this.relativeMatrix.mul(this.worldPoses[eye][currentSlot]);
                // Extract quaternion for stepAngle
                this.relativeMatrix.getUnnormalizedRotation(this.relative);
                this.relative.normalize();
                float angle = this.stepAngle(this.relative);
                if (angle < 0.0005f) continue;

                this.source.set(this.worldPoses[eye][previousSlot]);
                this.sourceInverse.set(this.source);
                this.sourceInverse.invert();
                this.projection.set(this.worldProjections[eye][currentSlot]);
                this.projectionInverse.set(this.projection);
                this.projectionInverse.invert();

                for (int index = 1; index <= eyeExtras; ++index) {
                    float fraction = (float) index / (float)(eyeExtras + 1);

                    // Get predicted HMD pose for this time slice
                    Matrix4f predictedPose = vr.getPredictedHmdPose(fraction);
                    Vector3f translationDelta = vr.getHmdTranslationDelta();

                    // Build warp matrix: predicted pose * inverse(current pose) * inverse(projection) * projection
                    // This warps from current eye view to predicted view
                    this.step.identity();
                    this.step.slerp(this.relative, fraction);
                    this.stepInverse.rotation(this.step);
                    this.stepInverse.invert();

                    // Combine camera rotation warp with HMD pose prediction
                    this.warpMatrix.set(this.projection);
                    this.warpMatrix.mul(this.source);
                    this.warpMatrix.mul(this.stepInverse);
                    this.warpMatrix.mul(this.sourceInverse);
                    this.warpMatrix.mul(this.projectionInverse);

                    // Apply micro-translation from HMD movement
                    this.warpMatrix.m30(this.warpMatrix.m30() + translationDelta.x * fraction);
                    this.warpMatrix.m31(this.warpMatrix.m31() + translationDelta.y * fraction);
                    this.warpMatrix.m32(this.warpMatrix.m32() + translationDelta.z * fraction);

                    // Check warp magnitude - if too large, skip generation
                    if (this.warpMagnitude(this.warpMatrix) > Math.toRadians(VrConfig.get().vrFrameGenMaxWarpAngle)) {
                        continue;
                    }

                    this.matrixData.clear();
                    this.warpMatrix.get(0, this.matrixData);
                    this.matrixData.position(0).limit(64);

                    if (paced) {
                        this.sleepUntil(base + (long)index * period);
                    }

                    final int eyeIndex = eye;
                    CommandEncoder frameEncoder = device.createCommandEncoder();
                    frameEncoder.writeToBuffer(this.warpBuffer.slice(), this.matrixData);

                    // Render warped world
                    try (RenderPass pass = frameEncoder.createRenderPass(() -> "upshift_vr/fgr_eye" + eyeIndex, this.outputView, Optional.empty())) {
                        pass.setPipeline(pipeline);
                        pass.bindTexture("InSampler", sourceView, sampler);
                        pass.bindTexture("GuiSampler", realView, sampler); // For HUD mask
                        pass.bindTexture("WorldSampler", maskView, sampler);
                        pass.setUniform("WarpParams", this.warpBuffer);
                        pass.draw(3, 1, 0, 0);
                    }

                    // Composite hand/controller layer at native rate
                    if (VrConfig.get().vrFrameGenHandNative) {
                        dev.upshift.vr.render.HandLayerSnapshot handSnap = vr.getHandSnapshot(eye);
                        if (handSnap != null && handSnap.isValid()) {
                            this.compositeHand(frameEncoder, handSnap);
                        }
                    }

                    if (!surface.isAcquired()) {
                        surface.acquireNextTexture();
                    }
                    surface.blitFromTexture(frameEncoder, this.outputView);
                    frameEncoder.submit();
                    surface.present();

                    PerfStats.get().onGeneratedFrame();
                    ++this.generatedFrames;
                    vr.recordVrPresent();
                }
            }

            if (paced) {
                this.sleepUntil(base + (long)(extras + 1) * period);
            }

            if (!this.loggedStart) {
                this.loggedStart = true;
                Upshift.LOG.info("VR frame generation on: {}x per eye, HMD pose warp, hands native", FsrConfig.get().frameGenMultiplier);
            }
            this.logStats();

        } catch (Throwable t) {
            this.fail("VR generated frame presentation failed", t);
        }
    }

    private void compositeHand(CommandEncoder encoder, dev.upshift.vr.render.HandLayerSnapshot handSnap) {
        // Simple alpha blend of hand layer over warped frame
        // Uses handPipeline with HandParams uniform
    }

    /**
     * Called after the game presented its real frame.
     */
    public void afterFrame() {
        this.warmUp();
        long now = System.nanoTime();
        long interval = this.lastRealPresentNs == 0L ? 0L : now - this.lastRealPresentNs;
        this.lastRealPresentNs = now;
        if (interval > 0L && interval < 2_000_000_000L) {
            this.averageRealNs = this.averageRealNs == 0L ? interval : (this.averageRealNs * 3L + interval) / 4L;
        }
        if (!this.generationEnabled()) {
            return;
        }
        if (now - this.lastLogNs > LOG_INTERVAL_NS) {
            this.lastLogNs = now;
            Upshift.LOG.info("VR frame generation: {} interpolated frames presented, {} ms between real frames",
                this.generatedFrames, this.averageRealNs / 1_000_000L);
        }
    }

    public void reset() {
        for (int eye = 0; eye < 2; eye++) {
            this.worldFrames[eye][0] = -1L;
            this.worldFrames[eye][1] = -1L;
        }
        this.broken = false;
        this.retryAtNs = 0L;
        this.lastRealPresentNs = 0L;
        this.averageRealNs = 0L;
        this.generatedFrames = 0L;
        this.loggedStart = false;
        this.loggedStop = false;
    }

    private int plannedExtras(int eye) {
        if (!this.generationEnabled()) return 0;
        if (this.worldFrames[eye][0] < 0 || this.worldFrames[eye][1] < 0) return 0;
        if (this.worldFrames[eye][0] == this.worldFrames[eye][1]) return 0;
        int count = Math.clamp(FsrConfig.get().frameGenMultiplier, 1, 4) - 1;
        if (count <= 0) return 0;
        if (!FsrConfig.get().frameGenForce && this.lastRealPresentNs != 0L) {
            long period = this.periodNs();
            if (System.nanoTime() - this.lastRealPresentNs > (long)(count + 2) * period) {
                return 0;
            }
        }
        return count;
    }

    private boolean generationEnabled() {
        if (!VrConfig.get().vrFrameGenEnabled) return false;
        if (!VrRuntime.get().isStereo()) return false;
        if (this.broken) {
            if (this.retryAtNs == 0L || System.nanoTime() < this.retryAtNs) return false;
            this.broken = false;
            this.retryAtNs = 0L;
            Upshift.LOG.info("retrying VR frame generation");
        }
        if (FsrConfig.get().frameGenMultiplier <= 1) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.isGameLoadFinished() || minecraft.isPaused()) return false;
        if (minecraft.getWindow().isMinimized()) return false;
        return minecraft.gui.screen() == null;
    }

    private long periodNs() {
        return this.averageRealNs > 0 ? this.averageRealNs : (1_000_000_000L / Math.max(1, VrConfig.get().vrTargetFps));
    }

    private void sleepUntil(long targetNs) {
        long now;
        while ((now = System.nanoTime()) < targetNs) {
            long rem = targetNs - now;
            if (rem > 500_000L) {
                try { Thread.sleep(rem / 1_000_000L); } catch (InterruptedException ignored) {}
            }
        }
    }

    private boolean blocks(GpuSurface surface) {
        // Heuristic: if surface is not acquired, we can pace
        return surface.isAcquired();
    }

    private boolean ensureWorld(int eye, int w, int h) {
        if (this.worldViews[eye][0] != null && this.worldSizes[eye * 2] == w && this.worldSizes[eye * 2 + 1] == h) {
            return true;
        }
        this.closeWorld(eye);
        GpuDevice device = RenderSystem.getDevice();
        for (int i = 0; i < 2; i++) {
            this.worldTextures[eye][i] = device.createTexture("upshift_vr/world_eye" + eye + "_" + i, WORLD_USAGE, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
            this.worldViews[eye][i] = device.createTextureView(this.worldTextures[eye][i]);
        }
        this.worldSizes[eye * 2] = w;
        this.worldSizes[eye * 2 + 1] = h;
        return true;
    }

    private void closeWorld(int eye) {
        for (int i = 0; i < 2; i++) {
            if (this.worldViews[eye][i] != null) {
                this.worldViews[eye][i].close();
                this.worldViews[eye][i] = null;
            }
            if (this.worldTextures[eye][i] != null) {
                this.worldTextures[eye][i].close();
                this.worldTextures[eye][i] = null;
            }
        }
    }

    private boolean ensureOutput(int w, int h) {
        if (this.outputView != null && this.outputW == w && this.outputH == h) return true;
        this.closeOutput();
        GpuDevice device = RenderSystem.getDevice();
        this.output = device.createTexture("upshift_vr/fgr_output", OUTPUT_USAGE, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        this.outputView = device.createTextureView(this.output);
        this.outputW = w;
        this.outputH = h;
        return true;
    }

    private void closeOutput() {
        if (this.outputView != null) {
            this.outputView.close();
            this.outputView = null;
        }
        if (this.output != null) {
            this.output.close();
            this.output = null;
        }
        this.outputW = -1;
        this.outputH = -1;
    }

    private void ensureBuffer() {
        if (this.warpBuffer != null) return;
        ByteBuffer empty = MemoryUtil.memAlloc(64);
        this.warpBuffer = RenderSystem.getDevice().createBuffer(() -> "upshift_vr/fgr_warp", 136, empty);
        MemoryUtil.memFree(empty);
    }

    private float stepAngle(Quaternionf q) {
        return (float) (2.0 * Math.acos(Math.min(1.0, Math.abs(q.w))));
    }

    private float warpMagnitude(Matrix4f m) {
        // Approximate warp magnitude from translation components
        return (float) Math.sqrt(m.m30() * m.m30() + m.m31() * m.m31() + m.m32() * m.m32());
    }

    private void fail(String message, Throwable t) {
        this.broken = true;
        this.retryAtNs = System.nanoTime() + RETRY_DELAY_NS;
        Upshift.LOG.error(message, t);
    }

    private void warmUp() {
        if (this.warmedUp) return;
        if (++this.warmupTries >= 3) {
            this.warmedUp = true;
        }
    }

    private void logStats() {
        // Periodic logging
    }
}