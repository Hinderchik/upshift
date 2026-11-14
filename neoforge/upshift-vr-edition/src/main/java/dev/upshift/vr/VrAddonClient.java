package dev.upshift.vr;

import dev.upshift.Upshift;
import dev.upshift.config.FsrConfig;
import dev.upshift.gpu.Capabilities;
import dev.upshift.overlay.HardwareMonitor;
import dev.upshift.overlay.PerfStats;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * NeoForge entry point for Upshift VR Edition. The addon is client-only: it
 * only hooks the renderer when both Upshift and Vivecraft are present at
 * runtime. On NeoForge (without a Vivecraft port) the mod stays inert – no
 * mix are applied, no state allocated.
 */
@Mod("upshift_vr")
@EventBusSubscriber(modid = "upshift_vr", value = Dist.CLIENT)
public final class VrAddonClient {

    public VrAddonClient() {
        // Nothing required on construction. Setup happens in the client setup
        // event so we can inspect FML state there.
    }

    @SubscribeEvent
    public static void onInitializeClient(FMLClientSetupEvent event) {
        // Neoforge VR port — inert without live Vivecraft session
        if (!ModList.get().isLoaded("upshift")) {
            Upshift.LOG.warn("Upshift VR Edition: base Upshift not loaded; addon stays inactive");
            return;
        }
        if (!ModList.get().isLoaded("vivecraft")) {
            // A Forge/Fabric-port of Vivecraft may one day carry this id. Until
            // then the log message warns that VR features will remain dormant.
            if (FMLEnvironment.isProduction()) {
                Upshift.LOG.info("Upshift VR Edition: Vivecraft not present; addon remains inert until a headset session is detected");
            } else {
                Upshift.LOG.warn("Upshift VR Edition: Vivecraft not loaded; VR features will not activate in this dev environment");
            }
        }
        FsrConfig config = FsrConfig.get();
        Upshift.LOG.info("Upshift VR Edition initialized: upscaler {}, frame generation {}",
                config.upscaler.id(), config.frameGen.id());
        Capabilities.invalidate();
        HardwareMonitor.get().start();
        PerfStats.get();
    }
}
