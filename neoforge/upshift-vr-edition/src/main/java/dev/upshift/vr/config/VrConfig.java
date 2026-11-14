package dev.upshift.vr.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.upshift.Upshift;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import net.neoforged.fml.loading.FMLPaths;

/**
 * VR-specific configuration, separate from the base Upshift config.
 * Stored in upshift_vr.json in the config directory.
 */
public final class VrConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("upshift_vr.json");
    private static final VrConfig INSTANCE = VrConfig.load();
    private transient boolean savePending;

    // VR-specific settings
    public boolean vrEnabled = true;
    public boolean vrTemporalEnabled = true;
    public float vrTemporalClampWeight = 0.08f; // Stricter than flat 0.12f
    public boolean vrAutoResolution = true;
    public int vrTargetFps = 72; // Typical headset refresh rate
    public boolean vrFrameGenEnabled = false; // Built-in frame gen off in VR by default
    public boolean vrHideFlatOverlay = true; // Hide 2D overlay in VR
    public boolean vrProjectOverlayToWrist = false; // Future: project panel to wrist/menu
    public int vrOverlayMode = 0; // 0 = off, 1 = flat HUD, 2 = projected (future)

    // Frame generation with HMD pose
    public boolean vrFrameGenHmdPose = true; // Use predicted HMD pose for warp
    public boolean vrFrameGenHandNative = true; // Keep hands/controllers at native rate
    public float vrFrameGenMaxWarpAngle = 15.0f; // Max warp angle before fallback (degrees)

    // Foveated rendering (stub)
    public boolean vrFoveatedEnabled = false; // Off by default, future feature
    public float vrFoveatedFocalRadius = 15.0f; // Degrees
    public float vrFoveatedTransition = 10.0f; // Degrees
    public float vrFoveatedMinScale = 0.3f; // Min peripheral scale
    public int vrFoveatedLayerCount = 3;

    // Vulkan/QuestCraft
    public boolean vrVulkanSafeMode = true; // Avoid descriptor/swapchain deadlocks
    public boolean vrMobileLightweight = false; // Auto-detected for QuestCraft

    private VrConfig() {
        this.startFlusher();
    }

    public static VrConfig get() {
        return INSTANCE;
    }

    public void save() {
        this.savePending = true;
    }

    public synchronized void flush() {
        this.savePending = false;
        try {
            Files.createDirectories(PATH.getParent(), (FileAttribute<?>[]) new FileAttribute[0]);
            try (BufferedWriter writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson((Object) this, (Appendable) writer);
            }
        } catch (IOException e) {
            Upshift.LOG.error("could not write {}", (Object) PATH, (Object) e);
        }
    }

    private void startFlusher() {
        Thread flusher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (!this.savePending) continue;
                this.flush();
            }
        }, "upshift-vr-config");
        flusher.setDaemon(true);
        flusher.setPriority(1);
        flusher.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "upshift-vr-config-final"));
    }

    private static VrConfig load() {
        if (!Files.isRegularFile(PATH, new LinkOption[0])) {
            return new VrConfig();
        }
        try (BufferedReader reader = Files.newBufferedReader(PATH)) {
            VrConfig loaded = (VrConfig) GSON.fromJson((Reader) reader, VrConfig.class);
            if (loaded == null) {
                return new VrConfig();
            }
            return loaded.sanitised();
        } catch (Exception e) {
            Upshift.LOG.warn("could not read {}, using defaults", (Object) PATH, (Object) e);
            return new VrConfig();
        }
    }

    private VrConfig sanitised() {
        this.vrTemporalClampWeight = Math.clamp(this.vrTemporalClampWeight, 0.02f, 0.25f);
        this.vrTargetFps = Math.clamp(this.vrTargetFps, 30, 144);
        this.vrFrameGenMaxWarpAngle = Math.clamp(this.vrFrameGenMaxWarpAngle, 1.0f, 45.0f);
        this.vrFoveatedFocalRadius = Math.clamp(this.vrFoveatedFocalRadius, 5.0f, 40.0f);
        this.vrFoveatedTransition = Math.clamp(this.vrFoveatedTransition, 2.0f, 30.0f);
        this.vrFoveatedMinScale = Math.clamp(this.vrFoveatedMinScale, 0.1f, 0.8f);
        this.vrFoveatedLayerCount = Math.clamp(this.vrFoveatedLayerCount, 2, 6);
        return this;
    }
}
