package dev.upshift.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.upshift.Upshift;
import dev.upshift.config.FrameGen;
import dev.upshift.config.QualityMode;
import dev.upshift.config.Upscaler;
import dev.upshift.overlay.OverlayMode;
import dev.upshift.perf.PerfProfile;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import net.neoforged.fml.loading.FMLPaths;

public final class FsrConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("upshift.json");
    private static final FsrConfig INSTANCE = FsrConfig.load();
    private transient boolean savePending;
    public int schema = 8;
    public Upscaler upscaler = Upscaler.TAAU;
    public float customScale = 0.67f;
    public float sharpness = 0.0f;
    public boolean sharpeningOnly = false;
    public boolean fastUpscale = true;
    public boolean autoFallback = true;
    public boolean allowExperimentalBackends = true;
    public FrameGen frameGen = FrameGen.OFF;
    public int frameGenMultiplier = 2;
    public boolean frameGenForce = false;
    public boolean menuBoost = true;
    public PerfProfile profile = PerfProfile.BALANCED;
    public boolean dynamicScale = true;
    public int targetFps = 60;
    public QualityMode quality = QualityMode.QUALITY;
    public transient boolean profileApplied;
    public OverlayMode overlay = OverlayMode.FULL;
    public int entityCullDistance = 128;
    public boolean cullInvisibleMobs = false;
    public boolean limitParticles = true;
    public int particleDistance = 32;
    public int particleTickBudget = 1500;
    public int particleTypeBudget = 400;
    public int blockEntityDistance = 64;
    public boolean freezeAnimationsUnfocused = true;
    public int animationFrameSkip = 1;
    public boolean cacheText = true;
    public boolean throttleAi = true;
    public int aiThrottleDistance = 48;
    public int aiThrottleInterval = 4;
    // Signature of the mods whose outdated icon metadata was already reported.
    public String metadataNoticeSignature = "";

    private FsrConfig() {
        this.startFlusher();
    }

    public static FsrConfig get() {
        return INSTANCE;
    }

    public void applyQuality(QualityMode mode) {
        this.quality = mode;
        this.customScale = mode.scale();
    }

    public int qualityIndex(Upscaler upscaler) {
        List<QualityMode> presets = QualityMode.forUpscaler(upscaler);
        int best = 0;
        float bestDelta = Float.MAX_VALUE;
        for (int i = 0; i < presets.size(); ++i) {
            float delta = Math.abs(presets.get(i).scale() - this.customScale);
            if (!(delta < bestDelta)) continue;
            bestDelta = delta;
            best = i;
        }
        return best;
    }

    public float renderScale() {
        return Math.clamp(this.customScale, 0.25f, 1.0f);
    }

    public void save() {
        this.savePending = true;
    }

    public synchronized void flush() {
        this.savePending = false;
        try {
            Files.createDirectories(PATH.getParent(), new FileAttribute[0]);
            try (BufferedWriter writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson((Object)this, (Appendable)writer);
            }
        }
        catch (IOException e) {
            Upshift.LOG.error("could not write {}", (Object)PATH, (Object)e);
        }
    }

    private void startFlusher() {
        Thread flusher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000L);
                }
                catch (InterruptedException e) {
                    return;
                }
                if (!this.savePending) continue;
                this.flush();
            }
        }, "upshift-config");
        flusher.setDaemon(true);
        flusher.setPriority(1);
        flusher.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "upshift-config-final"));
    }

    private static FsrConfig load() {
        if (!Files.isRegularFile(PATH, new LinkOption[0])) {
            return new FsrConfig();
        }
        try (BufferedReader reader = Files.newBufferedReader(PATH)) {
            FsrConfig loaded = (FsrConfig)GSON.fromJson((Reader)reader, FsrConfig.class);
            if (loaded == null) {
                FsrConfig fsrConfig2 = new FsrConfig();
                return fsrConfig2;
            }
            FsrConfig fsrConfig = loaded.sanitised();
            return fsrConfig;
        }
        catch (Exception e) {
            Upshift.LOG.warn("could not read {}, using defaults", (Object)PATH, (Object)e);
            return new FsrConfig();
        }
    }

    private FsrConfig sanitised() {
        if (this.schema < 2) {
            if (this.upscaler == Upscaler.OFF) {
                this.upscaler = Upscaler.FSR1;
            }
            if (this.frameGen == FrameGen.OFF || this.frameGen == FrameGen.FSR_FG || this.frameGen == FrameGen.DLSS_FG || this.frameGen == FrameGen.XESS_FG) {
                this.frameGen = FrameGen.INTERNAL;
            }
            this.schema = 2;
        }
        if (this.schema < 4) {
            this.entityCullDistance = 128;
            this.cullInvisibleMobs = false;
            this.limitParticles = true;
            this.particleDistance = 32;
            this.particleTickBudget = 1500;
            this.particleTypeBudget = 400;
            this.schema = 4;
        }
        if (this.schema < 5) {
            this.blockEntityDistance = 64;
            this.freezeAnimationsUnfocused = true;
            this.animationFrameSkip = 1;
            this.schema = 5;
        }
        if (this.schema < 6) {
            this.cacheText = true;
            this.schema = 6;
        }
        if (this.schema < 7) {
            this.throttleAi = true;
            this.aiThrottleDistance = 48;
            this.aiThrottleInterval = 4;
            this.schema = 7;
        }
        if (this.schema < 8) {
            this.frameGenMultiplier = 2;
            this.schema = 8;
        }
        if (this.upscaler == null) {
            this.upscaler = Upscaler.FSR1;
        }
        if (this.frameGen == null) {
            this.frameGen = FrameGen.INTERNAL;
        }
        this.customScale = Math.clamp(this.customScale, 0.25f, 1.0f);
        if (this.profile == null) {
            this.profile = PerfProfile.BALANCED;
        }
        if (this.overlay == null) {
            this.overlay = OverlayMode.OFF;
        }
        if (this.quality == null) {
            this.quality = QualityMode.nearest(this.customScale);
        }
        this.targetFps = Math.clamp((long)this.targetFps, 20, 1000);
        this.sharpness = Math.clamp(this.sharpness, 0.0f, 3.0f);
        this.entityCullDistance = Math.clamp(this.entityCullDistance, 0, 512);
        this.particleDistance = Math.clamp(this.particleDistance, 0, 128);
        this.particleTickBudget = Math.clamp(this.particleTickBudget, 100, 20000);
        this.particleTypeBudget = Math.clamp(this.particleTypeBudget, 10, 5000);
        this.blockEntityDistance = Math.clamp(this.blockEntityDistance, 0, 256);
        this.animationFrameSkip = Math.clamp(this.animationFrameSkip, 1, 4);
        this.aiThrottleDistance = Math.clamp(this.aiThrottleDistance, 0, 128);
        this.aiThrottleInterval = Math.clamp(this.aiThrottleInterval, 1, 20);
        this.frameGenMultiplier = Math.clamp(this.frameGenMultiplier, 1, 4);
        if (this.metadataNoticeSignature == null) {
            this.metadataNoticeSignature = "";
        }
        // Sharpening-only mode only makes sense at 100% scale
        if (this.sharpeningOnly && this.customScale < 1.0f) {
            this.sharpeningOnly = false;
        }
        return this;
    }
}
