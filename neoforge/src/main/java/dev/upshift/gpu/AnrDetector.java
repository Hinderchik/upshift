package dev.upshift.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.upshift.Upshift;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import java.util.Locale;

/**
 * Detects if the GPU supports ANR (AMD FidelityFX Frame Generation).
 * Whitelist: NVIDIA RTX 20/30/40/50, GTX 16; AMD RX 5000/6000/7000/9000; Intel Arc A/B; Steam Deck.
 */
public final class AnrDetector {
    private static final String[] NVIDIA_SERIES = {"RTX 20", "RTX 30", "RTX 40", "RTX 50", "GTX 16"};
    private static final String[] AMD_SERIES = {"RX 5", "RX 6", "RX 7", "RX 9"};
    private static final String[] INTEL_SERIES = {"Arc A", "Arc B"};
    private static final String[] STEAM_DECK = {"Steam Deck", "Van Gogh", "Aerith"};

    private static Boolean cachedResult = null;
    private static String cachedReason = "";

    private AnrDetector() {
    }

    /**
     * Checks if the current GPU is in the ANR whitelist.
     * Caches the result on first call.
     *
     * @return true if GPU is supported, false otherwise
     */
    public static synchronized boolean isSupported() {
        if (cachedResult != null) {
            return cachedResult;
        }
        String renderer = getRendererString().toLowerCase(Locale.ROOT);
        cachedReason = "";

        // NVIDIA
        for (String series : NVIDIA_SERIES) {
            if (renderer.contains(series.toLowerCase(Locale.ROOT))) {
                cachedResult = true;
                cachedReason = "NVIDIA " + series + " detected";
                return true;
            }
        }

        // AMD
        for (String series : AMD_SERIES) {
            if (renderer.contains(series.toLowerCase(Locale.ROOT))) {
                cachedResult = true;
                cachedReason = "AMD " + series + "000 series detected";
                return true;
            }
        }

        // Intel Arc
        for (String series : INTEL_SERIES) {
            if (renderer.contains(series.toLowerCase(Locale.ROOT))) {
                cachedResult = true;
                cachedReason = "Intel " + series + " detected";
                return true;
            }
        }

        // Steam Deck
        for (String name : STEAM_DECK) {
            if (renderer.contains(name.toLowerCase(Locale.ROOT))) {
                cachedResult = true;
                cachedReason = "Steam Deck detected";
                return true;
            }
        }

        cachedResult = false;
        cachedReason = "Unsupported GPU: " + renderer;
        Upshift.LOG.info("ANR not supported: {}", cachedReason);
        return false;
    }

    /**
     * Gets the human-readable reason for support/unsupport decision.
     */
    public static String getReason() {
        if (cachedResult == null) {
            isSupported();
        }
        return cachedReason;
    }

    /**
     * Gets the GPU renderer string from the current context.
     */
    private static String getRendererString() {
        try {
            GpuDevice device = RenderSystem.getDevice();
            if (device != null) {
                com.mojang.blaze3d.systems.DeviceInfo info = device.getDeviceInfo();
                if (info != null && info.name() != null) {
                    return info.name();
                }
            }
        } catch (Throwable ignored) {}

        // Fallback to GLFW - get renderer from OpenGL context
        try {
            long window = GLFW.glfwGetCurrentContext();
            if (window != 0) {
                // Need to make context current to query GL
                GLFW.glfwMakeContextCurrent(window);
                String renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
                if (renderer != null) {
                    return renderer;
                }
            }
        } catch (Throwable ignored) {}

        return "unknown";
    }

    /**
     * Clears the cache (used for testing or after GPU change).
     */
    public static void clearCache() {
        cachedResult = null;
        cachedReason = "";
    }
}