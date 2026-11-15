package dev.upshift.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.upshift.Upshift;
import dev.upshift.config.FsrConfig;
import dev.upshift.config.Upscaler;
import dev.upshift.gpu.Capabilities;
import dev.upshift.gpu.CapabilityReport;
import dev.upshift.overlay.PerfStats;
import dev.upshift.perf.CpuTuner;
import dev.upshift.perf.PerfProfile;
import dev.upshift.render.FastUpscalePass;
import dev.upshift.render.Fsr1Pass;
import dev.upshift.render.NisPass;
import dev.upshift.render.TaauPass;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

public final class UpscaleManager {
    private static final UpscaleManager INSTANCE = new UpscaleManager();
    public final Fsr1Pass fsr1 = new Fsr1Pass();
    public final FastUpscalePass fast = new FastUpscalePass();
    public final NisPass nis = new NisPass();
    public final TaauPass taau = new TaauPass();
    public static final float[][] JITTER = new float[][]{{0.5f, 0.333f}, {0.25f, 0.667f}, {0.75f, 0.111f}, {0.125f, 0.444f}, {0.625f, 0.778f}, {0.375f, 0.222f}, {0.875f, 0.556f}, {0.062f, 0.889f}};
    public final Matrix4f pristineProjection = new Matrix4f();
    public final Matrix4f previousRotation = new Matrix4f();
    public final Matrix4f rotationScratch = new Matrix4f();
    public final Quaternionf rotationQuat = new Quaternionf();
    public final int[] sizeScratch = new int[2];
    public boolean hasPreviousRotation;
    public boolean jitterValid;
    public boolean loggedTemporal;
    public int jitterIndex;
    public boolean hasPresented;
    public int menuReuseLeft;
    public static final int MENU_REUSE_FRAMES = 4;
    public boolean blurHandled;
    public GpuTexture intermediate;
    public GpuTextureView intermediateView;
    public int intermediateW = -1;
    public int intermediateH = -1;
    public RenderTarget worldTarget;
    public int worldW = -1;
    public int worldH = -1;
    public final Map<Long, CachedTarget> targetCache = new LinkedHashMap<Long, CachedTarget>(8, 0.75f, true);
    public final List<RenderTarget> retired = new ArrayList<RenderTarget>();
    public long frame;
    public long frameStartNs;
    public static final int CACHE_LIMIT = 10;
    public static final long RETIRE_FRAMES = 180L;
    public Upscaler active = Upscaler.OFF;
    public long lastProbeNs = System.nanoTime();
    public int measurePhase;
    public boolean warnedNotWired;
    public boolean loggedActive;

    private UpscaleManager() {
    }

    public static UpscaleManager get() {
        return INSTANCE;
    }

    public Upscaler activeUpscaler() {
        return this.active;
    }

    public void probeFps() {
        CpuTuner.get().onFrame();
        PerfStats.get().onFrame();
        long now = System.nanoTime();
        if (now - this.lastProbeNs < 5000000000L) {
            return;
        }
        this.lastProbeNs = now;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (Boolean.getBoolean("upshift.measure") || Files.exists(Path.of("upshift-measure.flag", new String[0]), new LinkOption[0])) {
            int phase = this.measurePhase++ % 3;
            FsrConfig config = FsrConfig.get();
            config.upscaler = phase == 0 ? Upscaler.OFF : Upscaler.FSR1;
            config.fastUpscale = phase != 2;
            Upshift.LOG.info("measure phase {}: {}", (Object)phase, (Object)(phase == 0 ? "off" : (phase == 1 ? "fast single pass" : "full EASU + RCAS")));
        }
        int[] internal = this.internalSize(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        Upshift.LOG.info("fps={} frame={}ms work={}ms profile={} upscaler={} scale={}x{}", new Object[]{minecraft.getFps(), String.format(Locale.ROOT, "%.1f", CpuTuner.get().averageFrameMs()), String.format(Locale.ROOT, "%.1f", CpuTuner.get().workMs()), FsrConfig.get().profile, FsrConfig.get().upscaler, internal[0], internal[1]});
    }

    public int[] internalSize(int windowWidth, int windowHeight) {
        float scale = FsrConfig.get().renderScale();
        this.sizeScratch[0] = Math.max(1, Math.round((float)windowWidth * scale));
        this.sizeScratch[1] = Math.max(1, Math.round((float)windowHeight * scale));
        return this.sizeScratch;
    }

    private void applyJitter(int width, int height) {
        this.jitterValid = false;
        CameraRenderState camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (camera == null || !camera.initialized) {
            return;
        }
        this.pristineProjection.set((Matrix4fc)camera.projectionMatrix);
        float[] offset = JITTER[this.jitterIndex++ & 7];
        float x = (offset[0] - 0.5f) * 2.0f / (float)width;
        float y = (offset[1] - 0.5f) * 2.0f / (float)height;
        float w = camera.projectionMatrix.m32();
        camera.projectionMatrix.m20(camera.projectionMatrix.m20() + x * w);
        camera.projectionMatrix.m21(camera.projectionMatrix.m21() + y * w);
        this.jitterValid = true;
    }

    public boolean scalingActive() {
        FsrConfig config = FsrConfig.get();
        if (config.upscalingPaused) {
            return false;
        }
        if (config.sharpeningOnly && config.customScale >= 1.0f && config.upscaler == Upscaler.SHARPENING) {
            return true;
        }
        return config.upscaler != Upscaler.OFF && config.renderScale() < 1.0f;
    }

    public RenderTarget beginWorld() {
        this.frameStartNs = System.nanoTime();
        FsrConfig config = FsrConfig.get();
        if (!config.profileApplied) {
            config.profileApplied = true;
            if (config.schema < 3) {
                PerfProfile chosen = CpuTuner.firstRunProfile(Capabilities.report().gpu());
                config.schema = 3;
                CpuTuner.get().apply(chosen);
                Upshift.LOG.info("first launch on {}: {} profile applied", (Object)Capabilities.report().gpu().describe(), (Object)chosen.id());
            }
        }
        // If upscaling is paused via hotkey, disable it immediately
        if (config.upscalingPaused) {
            this.active = Upscaler.OFF;
            this.hasPresented = false;
            this.hasPreviousRotation = false;
            return null;
        }
        this.active = this.resolve(config);
        if (!this.active.isSpatial() || !this.scalingActive()) {
            return null;
        }
        if (config.menuBoost && !this.blurHandled) {
            this.blurHandled = true;
            CpuTuner.get().dropMenuBlur();
        }
        Window window = Minecraft.getInstance().getWindow();
        int[] internal = this.internalSize(window.getWidth(), window.getHeight());
        try {
            this.ensureWorldTarget(internal[0], internal[1]);
            if (this.active == Upscaler.TAAU) {
                this.applyJitter(internal[0], internal[1]);
            }
            return this.worldTarget;
        }
        catch (Throwable t) {
            Upshift.LOG.error("could not allocate the internal render target; staying at native resolution", t);
            this.active = Upscaler.OFF;
            return null;
        }
    }

    public boolean menuFrame(RenderTarget window) {
        if (!(FsrConfig.get().menuBoost && this.hasPresented && this.worldTarget != null && this.active.isSpatial() && this.scalingActive())) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.gui.screen() == null) {
            this.menuReuseLeft = 0;
            return false;
        }
        if (this.menuReuseLeft <= 0) {
            double work = CpuTuner.get().workMs();
            this.menuReuseLeft = (work > 6.0 ? 2 : 4) - 1;
            return false;
        }
        GpuTextureView source = this.active == Upscaler.TAAU && this.taau.lastOutput() != null ? this.taau.lastOutput() : this.worldTarget.getColorTextureView();
        try {
            if (!this.present(window, source)) {
                this.menuReuseLeft = 0;
                return false;
            }
            --this.menuReuseLeft;
            PerfStats.get().onMenuFrame();
            return true;
        }
        catch (Throwable t) {
            this.menuReuseLeft = 0;
            Upshift.LOG.error("could not reuse the world image behind a screen; menus render normally", t);
            return false;
        }
    }

    public void recordPose() {
        long start = this.frameStartNs;
        this.frameStartNs = 0L;
        if (start == 0L) {
            return;
        }
        double workMs = (double)(System.nanoTime() - start) / 1000000.0;
        CpuTuner.get().recordWork(workMs);
    }

    public void endWorld(RenderTarget main) {
        if (!this.active.isSpatial() || this.worldTarget == null || main == null) {
            return;
        }
        if (this.active == Upscaler.FSR1 && this.fsr1.isBroken()) {
            this.active = Upscaler.OFF;
            return;
        }
        try {
            CameraRenderState camera;
            GpuTextureView source = this.worldTarget.getColorTextureView();
            if (this.active == Upscaler.TAAU && this.jitterValid && (camera = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState) != null) {
                this.rotationScratch.rotation((Quaternionfc)this.rotationQuat.setFromNormalized((Matrix4fc)camera.viewRotationMatrix));
                if (!this.hasPreviousRotation) {
                    this.previousRotation.set((Matrix4fc)this.rotationScratch);
                    this.hasPreviousRotation = true;
                }
                source = this.taau.resolve(RenderSystem.getDevice().createCommandEncoder(), source, this.worldTarget.width, this.worldTarget.height, this.pristineProjection, this.rotationScratch, this.previousRotation, 0.12f);
                this.previousRotation.set((Matrix4fc)this.rotationScratch);
                if (this.taau.isBroken()) {
                    this.active = Upscaler.OFF;
                    return;
                }
            }
            this.jitterValid = false;
            if (this.present(main, source)) {
                this.hasPresented = true;
            }
        }
        catch (Throwable t) {
            Upshift.LOG.error("upscale failed; the world stays at internal resolution this frame", t);
            this.active = Upscaler.OFF;
        }
    }

    public boolean present(RenderTarget main, GpuTextureView source) {
        int inW = this.worldTarget.width;
        int inH = this.worldTarget.height;
        int outW = main.width;
        int outH = main.height;
        if (inW == outW && inH == outH && this.active != Upscaler.SHARPENING) {
            return false;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        if (this.active == Upscaler.TAAU) {
            this.fast.upscale(encoder, source, inW, inH, main.getColorTextureView(), FsrConfig.get().sharpness);
            if (this.fast.isBroken()) {
                this.active = Upscaler.OFF;
                return false;
            }
            if (!this.loggedTemporal) {
                this.loggedTemporal = true;
                Upshift.LOG.info("temporal resolve active: {}x{} internal -> {}x{} window, history reprojected by camera rotation", new Object[]{inW, inH, outW, outH});
            }
        } else if (this.active == Upscaler.NIS) {
            this.nis.upscale(encoder, source, inW, inH, main.getColorTextureView(), outW, outH, FsrConfig.get().sharpness);
            if (this.nis.isBroken()) {
                this.active = Upscaler.FSR1;
            }
        } else if (FsrConfig.get().fastUpscale) {
            this.fast.upscale(encoder, source, inW, inH, main.getColorTextureView(), FsrConfig.get().sharpness);
            if (this.fast.isBroken()) {
                this.active = Upscaler.OFF;
                return false;
            }
        } else if (this.active == Upscaler.SHARPENING) {
            this.fsr1.rcas(encoder, source, main.getColorTextureView(), FsrConfig.get().sharpness);
            if (this.fsr1.isBroken()) {
                this.active = Upscaler.OFF;
                return false;
            }
            if (!this.loggedActive) {
                this.loggedActive = true;
                Upshift.LOG.info("RCAS sharpening active at native resolution: sharpness={}", FsrConfig.get().sharpness);
            }
            return true;
        } else {
            this.ensureIntermediate(outW, outH);
            this.fsr1.easu(encoder, source, inW, inH, this.intermediateView, outW, outH);
            if (this.fsr1.isBroken()) {
                this.active = Upscaler.OFF;
                return false;
            }
            this.fsr1.rcas(encoder, this.intermediateView, main.getColorTextureView(), FsrConfig.get().sharpness);
            if (this.fsr1.isBroken()) {
                this.active = Upscaler.OFF;
                return false;
            }
        }
        if (!this.loggedActive && this.active != Upscaler.SHARPENING) {
            this.loggedActive = true;
            Upshift.LOG.info("FSR 1 active: {}x{} internal -> {}x{} window", new Object[]{inW, inH, outW, outH});
        }
        return true;
    }

    private Upscaler resolve(FsrConfig config) {
        Upscaler requested = config.upscaler;
        if (requested == Upscaler.OFF) {
            return Upscaler.OFF;
        }
        if (requested == Upscaler.SHARPENING) {
            if (config.customScale < 1.0f) {
                Upshift.LOG.warn("SHARPENING upscaler requires 100% scale, falling back to FSR1");
                return Upscaler.FSR1;
            }
            return Upscaler.SHARPENING;
        }
        CapabilityReport report = Capabilities.report();
        List<Upscaler> runnable = report.runnableUpscalers();
        if (runnable.contains((Object)requested)) {
            if (!requested.isImplementedHere() && !this.warnedNotWired) {
                this.warnedNotWired = true;
                Upshift.LOG.warn("{} is runnable on this device but its native pass is not wired yet; resolving with FSR 1 for now", (Object)requested);
            }
            return requested;
        }
        if (!config.autoFallback) {
            return Upscaler.OFF;
        }
        for (Upscaler candidate : new Upscaler[]{Upscaler.FSR31, Upscaler.FSR2, Upscaler.NIS, Upscaler.FSR1}) {
            if (!runnable.contains((Object)candidate)) continue;
            return candidate;
        }
        return Upscaler.OFF;
    }

    public void ensureWorldTarget(int width, int height) {
        ++this.frame;
        long key = (long)width << 32 | (long)height & 0xFFFFFFFFL;
        CachedTarget cached = this.targetCache.get(key);
        if (cached == null) {
            cached = new CachedTarget((RenderTarget)new TextureTarget("upshift/world", width, height, true, GpuFormat.RGBA8_UNORM));
            this.targetCache.put(key, cached);
        }
        cached.lastFrame = this.frame;
        if (this.worldTarget != cached.target) {
            this.worldTarget = cached.target;
            this.worldW = width;
            this.worldH = height;
        }
        this.retireUnused(key);
    }

    private void retireUnused(long currentKey) {
        if (this.targetCache.size() <= 10) {
            return;
        }
        Iterator<Map.Entry<Long, CachedTarget>> iterator = this.targetCache.entrySet().iterator();
        while (iterator.hasNext() && this.targetCache.size() > 10) {
            Map.Entry<Long, CachedTarget> entry = iterator.next();
            CachedTarget candidate = entry.getValue();
            if (entry.getKey() == currentKey || this.frame - candidate.lastFrame < 180L) continue;
            this.retired.add(candidate.target);
            iterator.remove();
        }
    }

    private void ensureIntermediate(int width, int height) {
        if (this.intermediateView != null && this.intermediateW == width && this.intermediateH == height) {
            return;
        }
        this.closeIntermediate();
        GpuDevice device = RenderSystem.getDevice();
        int usage = 12;
        this.intermediate = device.createTexture("upshift/easu", usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        this.intermediateView = device.createTextureView(this.intermediate);
        this.intermediateW = width;
        this.intermediateH = height;
    }

    private void closeIntermediate() {
        if (this.intermediateView != null) {
            this.intermediateView.close();
            this.intermediateView = null;
        }
        if (this.intermediate != null) {
            this.intermediate.close();
            this.intermediate = null;
        }
        this.intermediateW = -1;
        this.intermediateH = -1;
    }

    public void close() {
        this.closeIntermediate();
        this.taau.close();
        this.hasPreviousRotation = false;
        this.hasPresented = false;
        this.menuReuseLeft = 0;
        this.fsr1.close();
        this.fast.close();
        if (this.worldTarget != null) {
            this.worldTarget.destroyBuffers();
            this.worldTarget = null;
        }
        for (CachedTarget cached : this.targetCache.values()) {
            cached.target.destroyBuffers();
        }
        this.targetCache.clear();
        for (RenderTarget target : this.retired) {
            target.destroyBuffers();
        }
        this.retired.clear();
        this.worldW = -1;
        this.worldH = -1;
    }

    private static final class CachedTarget {
        private final RenderTarget target;
        private long lastFrame;

        private CachedTarget(RenderTarget target) {
            this.target = target;
        }
    }
}
