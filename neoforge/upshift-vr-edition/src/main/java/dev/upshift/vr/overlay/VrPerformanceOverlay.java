package dev.upshift.vr.overlay;

import dev.upshift.Upshift;
import dev.upshift.config.FrameGen;
import dev.upshift.config.FsrConfig;
import dev.upshift.overlay.HardwareMonitor;
import dev.upshift.overlay.OverlayMode;
import dev.upshift.overlay.PerformanceOverlay;
import dev.upshift.overlay.PerfStats;
import dev.upshift.render.UpscaleManager;
import dev.upshift.vr.VrRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * VR overlay rendering - hides flat panel in stereo, optionally projects to wrist/menu later.
 */
public final class VrPerformanceOverlay {
    private static final VrPerformanceOverlay INSTANCE = new VrPerformanceOverlay();
    private static final long MIN_REBUILD_NS = 100_000_000L;
    private long builtAtNs;
    private String[] cachedLines = new String[0];

    private VrPerformanceOverlay() {
    }

    public static VrPerformanceOverlay get() {
        return INSTANCE;
    }

    /**
     * Called from the base PerformanceOverlay.render() when in VR mode.
     */
    public void render(GuiGraphicsExtractor graphics) {
        VrRuntime vr = VrRuntime.get();
        if (!vr.isStereo()) {
            return; // Not in VR, flat overlay handles it
        }

        if (dev.upshift.vr.config.VrConfig.get().vrHideFlatOverlay) {
            return; // Config says hide flat overlay in VR
        }

        // In VR, show a minimal diagnostic line: "VR gen: off / stereo"
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        int x = 4;
        int y = 4;
        String genStatus = FsrConfig.get().frameGen == FrameGen.INTERNAL ? "on (disabled in VR)" : "off";
        String text = "VR: stereo  gen=" + genStatus + "  " + UpscaleManager.get().activeUpscaler().id();
        graphics.text(font, text, x, y, -1, false);
    }

    /**
     * Returns a diagnostic string for the VR frame generation state.
     */
    public static String frameGenDiagnostic() {
        VrRuntime vr = VrRuntime.get();
        FsrConfig config = FsrConfig.get();
        if (!vr.isStereo()) {
            return config.frameGen.id();
        }
        if (config.frameGen == FrameGen.INTERNAL) {
            return "off (VR: headset reprojection)";
        }
        return config.frameGen.id() + " (VR)";
    }
}