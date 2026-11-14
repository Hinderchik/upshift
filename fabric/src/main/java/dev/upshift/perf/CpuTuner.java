package dev.upshift.perf;

import dev.upshift.Upshift;
import dev.upshift.config.FsrConfig;
import dev.upshift.gpu.GpuProfile;
import dev.upshift.perf.PerfProfile;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.server.level.ParticleStatus;

public final class CpuTuner {
    private static final CpuTuner INSTANCE = new CpuTuner();
    private long lastFrameNs;
    private long windowStartNs = System.nanoTime();
    private long frames;
    private double averageMs;
    private long lastSaveNs;
    private boolean priorityRaised;
    private double workSumMs;
    private long workFrames;
    private double workAverageMs;
    private int lastDirection;
    private long lastReverseNs;

    private CpuTuner() {
    }

    public static CpuTuner get() {
        return INSTANCE;
    }

    public void apply(PerfProfile profile) {
        FsrConfig config = FsrConfig.get();
        config.profile = profile;
        config.customScale = profile.scale();
        config.sharpness = profile.sharpness();
        config.dynamicScale = profile.dynamicScale();
        config.targetFps = profile.targetFps();
        config.save();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options != null) {
            minecraft.options.entityDistanceScaling().set(profile.entityDistance());
            OptionInstance optionInstance = minecraft.options.particles();
            optionInstance.set((Object)(switch (profile.particles()) {
                default -> throw new AssertionError("unreachable");
                case PerfProfile.Particles.VANILLA -> ParticleStatus.ALL;
                case PerfProfile.Particles.DECREASED -> ParticleStatus.DECREASED;
                case PerfProfile.Particles.MINIMAL -> ParticleStatus.MINIMAL;
            }));
            minecraft.options.save();
        }
        if (FsrConfig.get().menuBoost) {
            this.dropMenuBlur();
        }
        try {
            if (minecraft.options != null) {
                minecraft.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
                minecraft.options.save();
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        Upshift.LOG.info("performance profile set to {}: {}", (Object)profile.id(), (Object)profile.note());
    }

    public void dropMenuBlur() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.options == null) {
                return;
            }
            if ((Integer)minecraft.options.menuBackgroundBlurriness().get() != 0) {
                minecraft.options.menuBackgroundBlurriness().set(0);
                Upshift.LOG.info("menu background blur off: the screens draw their own background now");
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    public static PerfProfile firstRunProfile(GpuProfile gpu) {
        if (gpu.integrated()) {
            return PerfProfile.INTEGRATED;
        }
        return PerfProfile.BALANCED;
    }

    public void onFrame() {
        float scale;
        double ms;
        long now = System.nanoTime();
        if (this.lastFrameNs != 0L) {
            ++this.frames;
        }
        this.lastFrameNs = now;
        if (!this.priorityRaised) {
            this.priorityRaised = true;
            try {
                Thread.currentThread().setPriority(10);
            }
            catch (Throwable throwable) {
                // empty catch block
            }
        }
        if (now - this.windowStartNs < 500000000L) {
            return;
        }
        this.averageMs = ms = (double)(now - this.windowStartNs) / 1000000.0 / (double)Math.max(1L, this.frames);
        this.workAverageMs = this.workFrames == 0L ? ms : this.workSumMs / (double)this.workFrames;
        this.workSumMs = 0.0;
        this.workFrames = 0L;
        this.windowStartNs = now;
        this.frames = 0L;
        FsrConfig config = FsrConfig.get();
        if (!config.dynamicScale) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.gui.screen() != null || !minecraft.isWindowActive()) {
            return;
        }
        int target = Math.clamp((long)config.targetFps, 20, 1000);
        double targetMs = 1000.0 / (double)target;
        double budgetMs = this.workAverageMs > 0.0 ? this.workAverageMs : ms;
        float next = scale = config.customScale;
        int direction = 0;
        if (budgetMs > targetMs * 1.1) {
            next = scale - 0.1f;
            direction = -1;
        } else if (budgetMs < targetMs * 0.82) {
            next = scale + 0.1f;
            direction = 1;
        }
        next = (float)Math.round(Math.clamp(next, 0.3f, 1.0f) * 10.0f) / 10.0f;
        if (direction != 0 && this.lastDirection != 0 && direction != this.lastDirection) {
            if (now - this.lastReverseNs < 1500000000L) {
                return;
            }
            this.lastReverseNs = now;
        }
        if (direction != 0) {
            this.lastDirection = direction;
        }
        if (Math.abs(next - scale) >= 0.049f) {
            config.customScale = next;
            if (now - this.lastSaveNs > 10000000000L) {
                this.lastSaveNs = now;
                config.save();
            }
        }
    }

    public double averageFrameMs() {
        return this.averageMs;
    }

    public double workMs() {
        return this.workAverageMs;
    }

    public void recordWork(double workMs) {
        if (workMs <= 0.0 || workMs > 1000.0) {
            return;
        }
        this.workSumMs += workMs;
        ++this.workFrames;
    }
}
