package dev.upshift.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
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
import dev.upshift.config.FrameGen;
import dev.upshift.config.FsrConfig;
import dev.upshift.gpu.AnrDetector;
import dev.upshift.gpu.AnrDownloader;
import dev.upshift.overlay.PerfStats;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.lwjgl.system.MemoryUtil;

/**
 * Generates and presents extra frames between two rendered ones.
 *
 * <p>Every finished frame keeps a world-only copy of itself. When the next real frame is
 * about to be shown, the world copy of the previous frame is warped along the camera
 * rotation that separates the two frames and presented once for each requested step. The
 * pose is therefore interpolated between two known frames instead of extrapolated past the
 * newest one, so the generated images never overshoot the real motion.</p>
 *
 * <p>A pure camera rotation is a homography in clip space, which is why a single texture
 * read is enough and no depth buffer is needed. The HUD is copied un-warped from the real
 * frame so the crosshair, hotbar and chat stay fixed, and areas outside the warped image
 * fall back to the un-warped world instead of stretching the border.</p>
 */
public final class FrameGenPresenter {
    private static final FrameGenPresenter INSTANCE = new FrameGenPresenter();
    private static final int OUTPUT_USAGE = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
    private static final int WORLD_USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
    private static final long RETRY_DELAY_NS = 5_000_000_000L;
    private static final long LOG_INTERVAL_NS = 10_000_000_000L;
    private static RenderPipeline pipeline;

    private final GpuTexture[] world = new GpuTexture[2];
    private final GpuTextureView[] worldView = new GpuTextureView[2];
    private final long[] worldFrame = {-1L, -1L};
    private final Quaternionf[] worldRotation = {new Quaternionf(), new Quaternionf()};
    private final Matrix4f[] worldProjection = {new Matrix4f(), new Matrix4f()};
    private int worldW = -1;
    private int worldH = -1;
    private int slot = -1;
    private long frame;

    private GpuTexture output;
    private GpuTextureView outputView;
    private int outputW = -1;
    private int outputH = -1;

    private GpuBuffer warpBuffer;
    private final ByteBuffer matrixData = MemoryUtil.memAlloc(64);
    private final Matrix4f matrix = new Matrix4f();
    private final Matrix4f source = new Matrix4f();
    private final Matrix4f sourceInverse = new Matrix4f();
    private final Matrix4f stepInverse = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f projectionInverse = new Matrix4f();
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

    private FrameGenPresenter() {
    }

    public static FrameGenPresenter get() {
        return INSTANCE;
    }

    public static synchronized List<RenderPipeline> pipelines() {
        if (pipeline == null) {
            BindGroupLayout layout = BindGroupLayout.builder()
                .withSampler("InSampler")
                .withSampler("GuiSampler")
                .withSampler("WorldSampler")
                .withUniform("WarpParams", UniformType.UNIFORM_BUFFER)
                .build();
            ColorTargetState target = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 15);
            pipeline = RenderPipeline.builder(new RenderPipeline.Snippet[0])
                .withLocation(Identifier.parse("upshift:pipeline/fgr_reproject"))
                .withVertexShader(Identifier.parse("upshift:core/screenquad"))
                .withFragmentShader(Identifier.parse("upshift:core/fgr_reproject"))
                .withBindGroupLayout(layout)
                .withColorTargetState(target)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
        }
        return List.of(pipeline);
    }

    /**
     * Copies the just-finished world image (before the HUD is drawn) into one of two
     * snapshots and stores the camera pose that belongs to it.
     */
    public void captureWorld() {
        if (!this.generationEnabled()) {
            this.slot = -1;
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
            if (target == null || target.width <= 0 || target.height <= 0 || target.getColorTexture() == null) {
                this.slot = -1;
                return;
            }
            CameraRenderState camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            if (camera == null || !camera.initialized) {
                this.slot = -1;
                return;
            }
            if (!this.ensureWorld(target.width, target.height)) {
                this.slot = -1;
                return;
            }
            int next = this.slot < 0 ? 0 : 1 - this.slot;
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToTexture(target.getColorTexture(), this.world[next], 0, 0, 0, 0, 0, target.width, target.height);
            this.worldRotation[next].setFromNormalized((Matrix4fc)camera.viewRotationMatrix);
            this.worldProjection[next].set((Matrix4fc)camera.projectionMatrix);
            this.worldFrame[next] = this.frame++;
            this.slot = next;
        }
        catch (Throwable t) {
            this.slot = -1;
            this.fail("could not snapshot the world image for frame generation", t);
        }
    }

    /**
     * Presents the generated frames and then the real frame on the window surface.
     *
     * <p>This replaces the single surface blit the game performs after rendering a frame.
     * The surface image the game acquired is used for the first generated frame, every
     * following one acquires its own image, and the real frame is finally blitted and left
     * acquired so the game can submit and present it normally.</p>
     */
    public void presentRealFrame(GpuSurface surface, CommandEncoder encoder, GpuTextureView realView, Blit blit) {
        int extras;
        try {
            extras = this.plannedExtras(realView);
        }
        catch (Throwable t) {
            this.fail("could not plan generated frames; presenting the real frame only", t);
            extras = 0;
        }
        if (extras <= 0) {
            blit.blit(surface, encoder, realView);
            return;
        }
        boolean frameReady = false;
        try {
            int width = realView.texture().getWidth(0);
            int height = realView.texture().getHeight(0);
            int previous = 1 - this.slot;
            GpuTextureView sourceView = this.worldView[previous];
            GpuTextureView maskView = this.worldView[this.slot];
            if (sourceView == null || maskView == null || !this.ensureOutput(width, height)) {
                blit.blit(surface, encoder, realView);
                return;
            }
            this.ensureBuffer();
            FrameGenPresenter.pipelines();
            if (pipeline == null) {
                blit.blit(surface, encoder, realView);
                return;
            }
            this.relative.set((Quaternionfc)this.worldRotation[previous]).conjugate().mul((Quaternionfc)this.worldRotation[this.slot]);
            if (stepAngle(this.relative) < 0.001f) {
                blit.blit(surface, encoder, realView);
                return;
            }
            GpuDevice device = RenderSystem.getDevice();
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            long period = periodNs();
            long now = System.nanoTime();
            long base = this.lastRealPresentNs;
            if (base == 0L || now - base > 4L * (long)(extras + 1) * period) {
                base = now - period;
            }
            boolean paced = !blocks(surface);
            this.source.rotation((Quaternionfc)this.worldRotation[previous]);
            this.sourceInverse.set((Matrix4fc)this.source).invert();
            this.projection.set((Matrix4fc)this.worldProjection[this.slot]);
            this.projectionInverse.set((Matrix4fc)this.projection).invert();
            for (int index = 1; index <= extras; ++index) {
                float fraction = (float)index / (float)(extras + 1);
                this.step.identity().slerp((Quaternionfc)this.relative, fraction);
                this.stepInverse.rotation((Quaternionfc)this.step).invert();
                this.matrix.set((Matrix4fc)this.projection).mul((Matrix4fc)this.source).mul((Matrix4fc)this.stepInverse).mul((Matrix4fc)this.sourceInverse).mul((Matrix4fc)this.projectionInverse);
                this.matrixData.clear();
                this.matrix.get(0, this.matrixData);
                this.matrixData.position(0).limit(64);

                if (paced) {
                    long deadline = base + (long)index * period;
                    long now2 = System.nanoTime();
                    if (now2 > deadline + 2_000_000L) {
                        // Running late — skip this frame
                        continue;
                    }
                    // Sleep BEFORE rendering to hit VSync
                    long waitNs = deadline - System.nanoTime();
                    if (waitNs > 0L) {
                        long millis = waitNs / 1_000_000L;
                        if (millis > 2L) millis = 2L;
                        try {
                            Thread.sleep(millis, (int)(waitNs % 1_000_000L));
                        } catch (InterruptedException ignored) {}
                    }
                }
                CommandEncoder frameEncoder = device.createCommandEncoder();
                frameEncoder.writeToBuffer(this.warpBuffer.slice(), this.matrixData);
                try (RenderPass pass = frameEncoder.createRenderPass(() -> "upshift/fgr", this.outputView, Optional.empty())) {
                    pass.setPipeline(pipeline);
                    pass.bindTexture("InSampler", sourceView, sampler);
                    pass.bindTexture("GuiSampler", realView, sampler);
                    pass.bindTexture("WorldSampler", maskView, sampler);
                    pass.setUniform("WarpParams", this.warpBuffer);
                    pass.draw(3, 1, 0, 0);
                }
                if (!surface.isAcquired()) {
                    surface.acquireNextTexture();
                }
                surface.blitFromTexture(frameEncoder, this.outputView);
                frameEncoder.submit();
                surface.present();
                PerfStats.get().onGeneratedFrame();
                ++this.generatedFrames;
            }
            if (paced) {
                sleepUntil(base + (long)(extras + 1) * period);
            }
            if (!surface.isAcquired()) {
                surface.acquireNextTexture();
            }
            blit.blit(surface, encoder, realView);
            frameReady = true;
            if (!this.loggedStart) {
                this.loggedStart = true;
                Upshift.LOG.info("built-in frame generation on: {}x, up to {} interpolated frames per rendered frame", Math.clamp(FsrConfig.get().frameGenMultiplier, 1, 4), Math.max(0, FsrConfig.get().frameGenMultiplier - 1));
            }
            this.logStats();
        }
        catch (Throwable t) {
            this.fail("generated frame presentation failed; the built-in generator pauses and will retry", t);
            if (!frameReady) {
                try {
                    if (!surface.isAcquired()) {
                        surface.acquireNextTexture();
                    }
                    blit.blit(surface, encoder, realView);
                }
                catch (Throwable fallback) {
                    Upshift.LOG.warn("could not present the real frame after a frame generation failure", fallback);
                }
            }
        }
    }

    /**
     * Called after the game presented its real frame. The present time drives the pacing of
     * the generated frames of the next frame.
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
            Upshift.LOG.info("frame generation: {} interpolated frames presented, {} ms between real frames", this.generatedFrames, this.averageRealNs / 1_000_000L);
        }
    }

    public void reset() {
        this.slot = -1;
        this.frame = 0L;
        this.broken = false;
        this.retryAtNs = 0L;
        this.lastRealPresentNs = 0L;
        this.averageRealNs = 0L;
        this.generatedFrames = 0L;
        this.loggedStart = false;
        this.loggedStop = false;
    }

    private int plannedExtras(GpuTextureView realView) {
        if (!this.generationEnabled() || this.slot < 0) {
            return 0;
        }
        int previous = 1 - this.slot;
        if (this.worldView[previous] == null || this.worldFrame[previous] != this.worldFrame[this.slot] - 1L) {
            return 0;
        }
        if (realView.texture().getWidth(0) != this.worldW || realView.texture().getHeight(0) != this.worldH) {
            return 0;
        }
        int count = Math.clamp(FsrConfig.get().frameGenMultiplier, 1, 4) - 1;
        if (count <= 0) {
            return 0;
        }
        if (!FsrConfig.get().frameGenForce && this.lastRealPresentNs != 0L) {
            long period = periodNs();
            if (System.nanoTime() - this.lastRealPresentNs > (long)(count + 2) * period) {
                return 0;
            }
        }
        return count;
    }

    private boolean generationEnabled() {
        FsrConfig config = FsrConfig.get();

        // Built-in generator
        if (config.frameGen == FrameGen.INTERNAL) {
            if (this.broken) {
                if (this.retryAtNs == 0L || System.nanoTime() < this.retryAtNs) {
                    return false;
                }
                this.broken = false;
                this.retryAtNs = 0L;
                this.slot = -1;
                Upshift.LOG.info("retrying built-in frame generation");
            }
            if (config.frameGenMultiplier <= 1) {
                return false;
            }
        }
        // ANR (on-demand download)
        else if (config.frameGen == FrameGen.ANR) {
            if (!AnrDetector.isSupported()) {
                Upshift.LOG.info("ANR selected but GPU not supported: {}", AnrDetector.getReason());
                return false;
            }
            // Check if downloaded and ready
            if (AnrDownloader.getState() != dev.upshift.gpu.AnrDownloader.DownloadState.COMPLETED) {
                // Auto-start download if user consented
                if (AnrDownloader.hasUserConsented()) {
                    AnrDownloader.ensureDownloaded();
                }
                return false; // Not ready yet
            }
            // TODO: Integrate with native ANR when library is available
            return false;
        }
        // Other external generators (FSR_FG, DLSS_FG, XESS_FG) - handled by native runtime
        else if (config.frameGen.needsNativeRuntime()) {
            return false; // Handled elsewhere
        }
        else {
            return false;
        }

        if (config.frameGenMultiplier <= 1) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.isGameLoadFinished() || minecraft.isPaused()) {
            return false;
        }
        if (minecraft.getWindow().isMinimized()) {
            return false;
        }
        return minecraft.gui.screen() == null;
    }

    private void fail(String message, Throwable t) {
        this.broken = true;
        this.retryAtNs = System.nanoTime() + RETRY_DELAY_NS;
        this.slot = -1;
        if (!this.loggedStop) {
            this.loggedStop = true;
            Upshift.LOG.warn(message, t);
        }
    }

    private void warmUp() {
        if (this.warmedUp) {
            return;
        }
        try {
            ShaderManager shaders = Minecraft.getInstance().getShaderManager();
            if (shaders == null) {
                return;
            }
            if (shaders.getShader(Identifier.parse("upshift:core/screenquad"), ShaderType.VERTEX) == null
                || shaders.getShader(Identifier.parse("upshift:core/fgr_reproject"), ShaderType.FRAGMENT) == null) {
                return;
            }
            FrameGenPresenter.pipelines();
            if (pipeline == null) {
                return;
            }
            CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(pipeline);
            this.warmedUp = true;
            if (compiled.isValid()) {
                Upshift.LOG.info("frame generation pipeline compiled and ready");
            } else {
                Upshift.LOG.warn("frame generation pipeline failed to compile; the built-in generator stays off");
            }
        }
        catch (Throwable t) {
            if (++this.warmupTries >= 5) {
                this.warmedUp = true;
                Upshift.LOG.warn("frame generation pipeline could not be precompiled; it will be compiled on first use", t);
            }
        }
    }

    private void logStats() {
        long now = System.nanoTime();
        if (now - this.lastLogNs > LOG_INTERVAL_NS) {
            this.lastLogNs = now;
            Upshift.LOG.info("frame generation: {} interpolated frames presented, {} ms between real frames", this.generatedFrames, this.averageRealNs / 1_000_000L);
        }
    }

    private static boolean blocks(GpuSurface surface) {
        try {
            return surface.currentConfiguration().map(configuration -> configuration.presentMode() == GpuSurface.PresentMode.FIFO || configuration.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED).orElse(true);
        }
        catch (Throwable ignored) {
            return true;
        }
    }

    private static void sleepUntil(long deadlineNs) {
        long wait;
        while ((wait = deadlineNs - System.nanoTime()) > 0L) {
            long millis = wait / 1_000_000L;
            if (millis <= 0L) {
                return;
            }
            // Cap sleep to 2ms to avoid missing VSync deadline on slow frames
            if (millis > 2L) {
                millis = 2L;
            }
            try {
                Thread.sleep(millis, (int)(wait % 1_000_000L));
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static float stepAngle(Quaternionf quaternion) {
        float w = Math.min(1.0f, Math.abs(quaternion.w));
        return 2.0f * (float)Math.acos(w);
    }

    private boolean ensureOutput(int width, int height) {
        if (this.outputView != null && this.outputW == width && this.outputH == height) {
            return true;
        }
        this.closeOutput();
        try {
            GpuDevice device = RenderSystem.getDevice();
            this.output = device.createTexture("upshift/fgr-frame", OUTPUT_USAGE, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            this.outputView = device.createTextureView(this.output);
            this.outputW = width;
            this.outputH = height;
            return true;
        }
        catch (Throwable t) {
            this.fail("could not allocate the generated frame target; frame generation pauses and will retry", t);
            return false;
        }
    }

    private boolean ensureWorld(int width, int height) {
        if (this.worldW == width && this.worldH == height && this.worldView[0] != null && this.worldView[1] != null) {
            return true;
        }
        this.closeWorld();
        try {
            GpuDevice device = RenderSystem.getDevice();
            for (int index = 0; index < 2; ++index) {
                this.world[index] = device.createTexture("upshift/fgr-world-" + index, WORLD_USAGE, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
                this.worldView[index] = device.createTextureView(this.world[index]);
            }
            this.worldW = width;
            this.worldH = height;
            this.worldFrame[0] = -1L;
            this.worldFrame[1] = -1L;
            return true;
        }
        catch (Throwable t) {
            this.fail("could not allocate the world snapshot textures; frame generation pauses and will retry", t);
            return false;
        }
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

    private void closeWorld() {
        for (int index = 0; index < 2; ++index) {
            if (this.worldView[index] != null) {
                this.worldView[index].close();
                this.worldView[index] = null;
            }
            if (this.world[index] != null) {
                this.world[index].close();
                this.world[index] = null;
            }
            this.worldFrame[index] = -1L;
        }
        this.worldW = -1;
        this.worldH = -1;
        this.slot = -1;
    }

    private void ensureBuffer() {
        if (this.warpBuffer != null) {
            return;
        }
        ByteBuffer empty = MemoryUtil.memAlloc(64);
        empty.putFloat(1.0f).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
        empty.putFloat(0.0f).putFloat(1.0f).putFloat(0.0f).putFloat(0.0f);
        empty.putFloat(0.0f).putFloat(0.0f).putFloat(1.0f).putFloat(0.0f);
        empty.putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).putFloat(1.0f);
        empty.flip();
        this.warpBuffer = RenderSystem.getDevice().createBuffer(() -> "upshift/fgr-params", 136, empty);
        MemoryUtil.memFree(empty);
    }

    private static long periodNs() {
        int refresh = refreshRate();
        return refresh > 0 ? 1_000_000_000L / (long)refresh : 16_666_667L;
    }

    private static int refreshRate() {
        try {
            Window window = Minecraft.getInstance().getWindow();
            return window.getRefreshRate();
        }
        catch (Throwable ignored) {
            return 0;
        }
    }

    public void close() {
        this.closeOutput();
        this.closeWorld();
        if (this.warpBuffer != null) {
            this.warpBuffer.close();
            this.warpBuffer = null;
        }
        MemoryUtil.memFree(this.matrixData);
        this.reset();
    }

    /** The surface blit the game would normally perform for the real frame. */
    @FunctionalInterface
    public interface Blit {
        void blit(GpuSurface surface, CommandEncoder encoder, GpuTextureView view);
    }
}
