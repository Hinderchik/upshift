package dev.upshift;

import dev.upshift.Upshift;
import dev.upshift.config.FsrConfig;
import dev.upshift.gpu.Capabilities;
import dev.upshift.overlay.HardwareMonitor;
import dev.upshift.overlay.PerfStats;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;

public final class UpshiftClient
implements ClientModInitializer {
    public void onInitializeClient() {
        FsrConfig config = FsrConfig.get();
        boolean sodium = FabricLoader.getInstance().isModLoaded("sodium");
        Upshift.LOG.info("render scale ready: {}%, upscaler {}, frame generation {}", new Object[]{Math.round(config.renderScale() * 100.0f), config.upscaler.id(), config.frameGen.id()});
        if (sodium) {
            Upshift.LOG.info("Sodium detected; settings will appear in its video settings screen");
        } else {
            Upshift.LOG.warn("Sodium is not installed. The renderer still scales and upscales, but there is no settings page until Sodium is present.");
        }
        Capabilities.invalidate();
        HardwareMonitor.get().start();
        PerfStats.get();
        if (Boolean.getBoolean("upshift.testMenu") || Files.exists(Path.of("upshift-menutest.flag", new String[0]), new LinkOption[0])) {
            Thread opener = new Thread(() -> {
                try {
                    Thread.sleep(45000L);
                }
                catch (InterruptedException e) {
                    return;
                }
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.execute(() -> {
                    if (minecraft.player == null) {
                        Upshift.LOG.info("test menu: no player yet, skipping");
                        return;
                    }
                    minecraft.gui.setScreen((Screen)new InventoryScreen((Player)minecraft.player));
                    Upshift.LOG.info("test menu: inventory opened for measurement");
                });
            }, "upshift-test-menu");
            opener.setDaemon(true);
            opener.start();
        }
    }
}
