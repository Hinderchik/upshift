package dev.upshift.optim;

import net.neoforged.fml.ModList;

/**
 * Detects other mods that duplicate Upshift's optimization features.
 *
 * <p>When such a mod is loaded, Upshift hands the overlapping work back to it
 * instead of running two competing implementations at once. The decision is
 * made once at class-load time and cached, so the per-frame hot paths never
 * pay for a {@link ModList} lookup.</p>
 */
public final class ModCompat {

    /** Mods that cull entities / block entities before render-state extraction. */
    public static final String ENTITY_CULLING = "entityculling";
    /** Mod that throttles AI and freezes animations. */
    public static final String LITHIUM = "lithium";
    /** Mod that applies general performance tweaks, including AI throttling. */
    public static final String MODERN_FIX = "modernfix";
    /** Memory-optimization mod that can conflict with texture-animation freezing. */
    public static final String FERRITE_CORE = "ferritecore";

    private static final boolean ENTITY_CULLING_PRESENT;
    private static final boolean LITHIUM_PRESENT;
    private static final boolean MODERN_FIX_PRESENT;
    private static final boolean FERRITE_CORE_PRESENT;

    static {
        ModList list = ModList.get();
        ENTITY_CULLING_PRESENT = list.isLoaded(ENTITY_CULLING);
        LITHIUM_PRESENT = list.isLoaded(LITHIUM);
        MODERN_FIX_PRESENT = list.isLoaded(MODERN_FIX);
        FERRITE_CORE_PRESENT = list.isLoaded(FERRITE_CORE);
    }

    private ModCompat() {
    }

    /** @return true when a dedicated entity/block-entity culling mod is active. */
    public static boolean entityCullingPresent() {
        return ENTITY_CULLING_PRESENT;
    }

    /** @return true when Lithium is active (AI throttle, animation freeze, particle cap). */
    public static boolean lithiumPresent() {
        return LITHIUM_PRESENT;
    }

    /** @return true when ModernFix is active (general performance tweaks). */
    public static boolean modernFixPresent() {
        return MODERN_FIX_PRESENT;
    }

    /** @return true when FerriteCore is active (memory optimization, may conflict with animation freeze). */
    public static boolean ferriteCorePresent() {
        return FERRITE_CORE_PRESENT;
    }

    /** @return true when Upshift should skip its own entity culling entirely. */
    public static boolean skipEntityCull() {
        return ENTITY_CULLING_PRESENT || LITHIUM_PRESENT || MODERN_FIX_PRESENT;
    }

    /** @return true when Upshift should skip its own particle culling entirely. */
    public static boolean skipParticleCull() {
        return LITHIUM_PRESENT || MODERN_FIX_PRESENT;
    }

    /** @return true when Upshift should skip its own block-entity culling entirely. */
    public static boolean skipBlockEntityCull() {
        return ENTITY_CULLING_PRESENT || LITHIUM_PRESENT || MODERN_FIX_PRESENT;
    }

    /** @return true when Upshift should skip its own texture-animation freezing. */
    public static boolean skipAnimationFreeze() {
        return LITHIUM_PRESENT || MODERN_FIX_PRESENT || FERRITE_CORE_PRESENT;
    }

    /** @return true when Upshift should skip its own AI throttling entirely. */
    public static boolean skipAiThrottle() {
        return LITHIUM_PRESENT || MODERN_FIX_PRESENT;
    }
}