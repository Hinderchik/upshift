package dev.upshift.vr.render;

import dev.upshift.Upshift;
import dev.upshift.vr.VrRuntime;

/**
 * Optimizations for mobile VR (QuestCraft, standalone headsets).
 * Adreno GPUs are sensitive to texture fetches and memory bandwidth.
 */
public final class VrMobileOptimizer {
    private static final VrMobileOptimizer INSTANCE = new VrMobileOptimizer();

    private boolean mobileDetected;
    public boolean lightweightMode;
    public boolean mobileDetected() { return this.mobileDetected; }
    private int textureFetchBudget = 8; // Max texture fetches per fragment
    private boolean useBilinearOnly = false;
    private boolean disableFsrRcas = false;
    private boolean reduceTemporalSamples = false;

    private VrMobileOptimizer() {
    }

    public static VrMobileOptimizer get() {
        return INSTANCE;
    }

    public void detect() {
        if (this.mobileDetected) return;
        this.mobileDetected = true;

        // Check for QuestCraft mod
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            java.lang.reflect.Method isModLoaded = loader.getMethod("isModLoaded", String.class);
            boolean isQuestCraft = (Boolean) isModLoaded.invoke(instance, "questcraft");
            boolean isAndroid = System.getProperty("os.name").toLowerCase().contains("android");
            boolean isArm = System.getProperty("os.arch").toLowerCase().contains("aarch64") ||
                            System.getProperty("os.arch").toLowerCase().contains("arm64");

            if (isQuestCraft || (isAndroid && isArm)) {
                this.enableLightweightMode();
                Upshift.LOG.info("VR Mobile optimizer: QuestCraft/standalone detected, enabling lightweight mode");
            }
        } catch (Throwable t) {
            // Not mobile
        }
    }

    private void enableLightweightMode() {
        this.lightweightMode = true;
        this.textureFetchBudget = 4;
        this.useBilinearOnly = true;
        this.disableFsrRcas = true;
        this.reduceTemporalSamples = true;

        // Adjust VR config
        dev.upshift.vr.config.VrConfig config = dev.upshift.vr.config.VrConfig.get();
        config.vrMobileLightweight = true;
        config.save();
    }

    public boolean isLightweightMode() {
        return this.lightweightMode;
    }

    public int getTextureFetchBudget() {
        return this.textureFetchBudget;
    }

    public boolean useBilinearOnly() {
        return this.useBilinearOnly;
    }

    public boolean shouldDisableFsrRcas() {
        return this.disableFsrRcas;
    }

    public boolean shouldReduceTemporalSamples() {
        return this.reduceTemporalSamples;
    }

    /**
     * Gets recommended internal resolution scale for mobile.
     * Mobile VR typically runs at lower resolutions anyway.
     */
    public float getRecommendedScale(float currentScale) {
        if (!this.lightweightMode) return currentScale;
        // On mobile, don't go below 0.5 to avoid excessive upscaling artifacts
        return Math.max(0.5f, currentScale * 0.9f);
    }

    /**
     * Called when VR session starts to apply mobile optimizations.
     */
    public void applySessionOptimizations() {
        if (!this.lightweightMode) return;
        Upshift.LOG.info("VR Mobile: textureFetchBudget={}, bilinearOnly={}, disableRcas={}",
            this.textureFetchBudget, this.useBilinearOnly, this.disableFsrRcas);
    }
}