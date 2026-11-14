package dev.upshift.vr.perf;

import dev.upshift.Upshift;
import dev.upshift.config.FsrConfig;
import dev.upshift.config.Upscaler;
import dev.upshift.perf.CpuTuner;
import dev.upshift.vr.VrRuntime;
import net.minecraft.client.Minecraft;

/**
 * VR auto-resolution that scales both eyes symmetrically based on combined
 * stereo work measurement.
 */
public final class VrAutoResolution {
    private static final VrAutoResolution INSTANCE = new VrAutoResolution();
    private long lastAdjustmentNs;
    private static final long ADJUSTMENT_INTERVAL_NS = 2_000_000_000L; // 2 seconds

    private VrAutoResolution() {
    }

    public static VrAutoResolution get() {
        return INSTANCE;
    }

    public void update() {
        if (!dev.upshift.vr.config.VrConfig.get().vrAutoResolution) {
            return;
        }
        if (!VrRuntime.get().isStereo()) {
            return;
        }

        long now = System.nanoTime();
        if (now - this.lastAdjustmentNs < ADJUSTMENT_INTERVAL_NS) {
            return;
        }
        this.lastAdjustmentNs = now;

        FsrConfig config = FsrConfig.get();
        if (config.upscaler == Upscaler.OFF || !config.dynamicScale) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.gui.screen() != null || !minecraft.isWindowActive()) {
            return;
        }

        // Use combined work from both eyes (CpuTuner measures GPU work which includes both passes)
        double workMs = CpuTuner.get().workMs();
        if (workMs <= 0.0) {
            return;
        }

        int targetFps = dev.upshift.vr.config.VrConfig.get().vrTargetFps;
        double targetMs = 1000.0 / (double) targetFps;
        float next = config.customScale;
        int direction = 0;

        if (workMs > targetMs * 1.15) { // Slightly more aggressive in VR
            next = Math.max(0.3f, next - 0.05f);
            direction = -1;
        } else if (workMs < targetMs * 0.75) { // More conservative headroom
            next = Math.min(1.0f, next + 0.05f);
            direction = 1;
        }

        if (direction != 0 && Math.abs(next - config.customScale) >= 0.025f) {
            config.customScale = next;
            config.save();
            Upshift.LOG.info("VR auto-res: scale -> {}% (work {}ms target {}ms)",
                Math.round(next * 100), String.format("%.1f", workMs), String.format("%.1f", targetMs));
        }
    }
}