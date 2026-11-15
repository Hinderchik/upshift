package dev.upshift;

import dev.upshift.Upshift;
import dev.upshift.compat.MetadataAudit;
import dev.upshift.compat.MetadataNotice;
import dev.upshift.config.FsrConfig;
import dev.upshift.gpu.Capabilities;
import dev.upshift.input.ToggleKey;
import dev.upshift.optim.ClientOptimizations;
import dev.upshift.overlay.HardwareMonitor;
import dev.upshift.overlay.PerfStats;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = Upshift.MOD_ID, value = Dist.CLIENT)
public final class UpshiftClient {
    private UpshiftClient() {
    }

    @SubscribeEvent
    public static void onInitializeClient(FMLClientSetupEvent event) {
        FsrConfig config = FsrConfig.get();
        boolean sodium = ModList.get().isLoaded("sodium");
        Upshift.LOG.info("render scale ready: {}%, upscaler {}, frame generation {}", new Object[]{Math.round(config.renderScale() * 100.0f), config.upscaler.id(), config.frameGen.id()});
        if (sodium) {
            Upshift.LOG.info("Sodium detected; settings will appear in its video settings screen");
        } else {
            Upshift.LOG.warn("Sodium is not installed. The renderer still scales and upscales, but there is no settings page until Sodium is present.");
        }
        // NeoForge's logoFile warnings only exist in development; the same
        // audit runs here so the title-screen note stays a developer tool.
        if (!FMLEnvironment.isProduction()) {
            UpshiftClient.auditMetadata(config);
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

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ToggleKey.MAPPING);
    }

    private static void auditMetadata(FsrConfig config) {
        List<MetadataAudit.Finding> findings = MetadataAudit.scan();
        String signature = findings.stream()
                .map(MetadataAudit.Finding::modId)
                .sorted()
                .collect(Collectors.joining(","));
        if (findings.isEmpty()) {
            if (!config.metadataNoticeSignature.isEmpty()) {
                config.metadataNoticeSignature = "";
                config.save();
            }
            return;
        }
        String names = findings.stream()
                .map(MetadataAudit.Finding::displayName)
                .distinct()
                .collect(Collectors.joining(", "));
        if (signature.equals(config.metadataNoticeSignature)) {
            Upshift.LOG.info("metadata check: {} mod(s) still use the deprecated logoFile key ({}); already reported", findings.size(), names);
            return;
        }
        config.metadataNoticeSignature = signature;
        config.save();
        MetadataNotice.arm(findings);
        Upshift.LOG.info("metadata check: {} mod(s) use the deprecated logoFile key ({}); NeoForge warnings hidden, notice armed", findings.size(), names);
    }
}
