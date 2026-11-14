package dev.upshift.vr;

import dev.upshift.Upshift;
import dev.upshift.config.FsrConfig;
import dev.upshift.gpu.Capabilities;
import dev.upshift.overlay.HardwareMonitor;
import dev.upshift.overlay.PerfStats;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class VrAddonClient implements ClientModInitializer {
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("upshift")) {
            Upshift.LOG.warn("Upshift VR Edition: Upshift not loaded; addon stays inactive");
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded("vivecraft")) {
            Upshift.LOG.warn("Upshift VR Edition: Vivecraft not loaded; VR features will not activate");
        }
        FsrConfig config = FsrConfig.get();
        Upshift.LOG.info("Upshift VR Edition initialized: upscaler {}, frame generation {}", config.upscaler.id(), config.frameGen.id());
        Capabilities.invalidate();
        HardwareMonitor.get().start();
        PerfStats.get();
    }
}