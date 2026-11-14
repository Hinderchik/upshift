package dev.upshift.optim;

import dev.upshift.config.FsrConfig;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.locale.Language;
import net.minecraft.util.FormattedCharSequence;

/**
 * Layout cache for plain strings on their way to the HUD.
 *
 * <p>Vanilla runs {@code Language.getVisualOrder} for every string every frame,
 * which decomposes the string into styled code points and allocates a
 * {@code FormattedChar} for each one. HUD strings such as item counts, the
 * scoreboard, the debug screen and mod overlays rarely change, so the result is
 * kept here and reused until the language itself changes.</p>
 */
public final class TextCache {
    /** Hard cap so a server sending many distinct strings cannot grow this without bound. */
    private static final int MAX_ENTRIES = 4096;
    /** Long strings (chat, books) are cheap to re-layout compared to holding them forever. */
    private static final int MAX_CACHED_LENGTH = 512;

    private static final Map<String, FormattedCharSequence> VISUAL_ORDER = new HashMap<>(256);
    private static Language cachedLanguage;

    private TextCache() {
    }

    public static boolean cacheable(String text) {
        return text != null && text.length() <= MAX_CACHED_LENGTH && FsrConfig.get().cacheText;
    }

    /**
     * @return the cached layout, or null when it has to be computed
     */
    public static FormattedCharSequence lookup(Language language, String text) {
        if (!cacheable(text)) {
            return null;
        }
        if (language != cachedLanguage) {
            VISUAL_ORDER.clear();
            cachedLanguage = language;
        }
        return VISUAL_ORDER.get(text);
    }

    public static void store(Language language, String text, FormattedCharSequence sequence) {
        if (sequence == null || !cacheable(text)) {
            return;
        }
        if (language != cachedLanguage) {
            VISUAL_ORDER.clear();
            cachedLanguage = language;
        }
        if (VISUAL_ORDER.size() >= MAX_ENTRIES) {
            VISUAL_ORDER.clear();
        }
        VISUAL_ORDER.put(text, sequence);
    }

    /** Only used by the width mixin; the layout cache has its own bounds. */
    public static boolean cacheWidths() {
        return FsrConfig.get().cacheText;
    }
}
