package dev.upshift.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Finds mods whose metadata still uses the deprecated {@code logoFile} key
 * without providing {@code iconFile} or {@code bannerFile}.
 *
 * <p>NeoForge reports exactly this situation during development, because
 * {@code logoFile} is being replaced by the square {@code iconFile} and the
 * wider {@code bannerFile}. The warning is cosmetic - the old key is still
 * read and the icon still shows - but it pollutes the log and the loading
 * issues screen. Upshift hides that warning and reports the same information
 * once on the title screen instead, so the metadata problem stays visible
 * without nagging on every launch.</p>
 */
public final class MetadataAudit {
    /** One mod that needs its metadata refreshed by its author. */
    public record Finding(String modId, String displayName) {
    }

    private MetadataAudit() {
    }

    public static List<Finding> scan() {
        List<Finding> findings = new ArrayList<>();
        for (IModInfo info : ModList.get().getMods()) {
            String modId = info.getModId();
            // The game and the loader define the format itself.
            if ("minecraft".equals(modId) || "neoforge".equals(modId)) {
                continue;
            }
            IConfigurable fileConfig = info.getOwningFile().getConfig();
            Optional<String> logo = info.getLogoFile()
                    .or(() -> MetadataAudit.element(info.getConfig(), "logoFile"))
                    .or(() -> MetadataAudit.element(fileConfig, "logoFile"));
            if (logo.isEmpty()) {
                continue;
            }
            boolean banner = MetadataAudit.element(info.getConfig(), "bannerFile")
                    .or(() -> MetadataAudit.element(fileConfig, "bannerFile"))
                    .isPresent();
            boolean icon = MetadataAudit.element(info.getConfig(), "iconFile")
                    .or(() -> MetadataAudit.element(fileConfig, "iconFile"))
                    .isPresent();
            // A conscious multi-version mod keeps logoFile next to the new keys.
            if (banner || icon) {
                continue;
            }
            findings.add(new Finding(modId, info.getDisplayName()));
        }
        return findings;
    }

    private static Optional<String> element(IConfigurable config, String key) {
        return config.getConfigElement(key);
    }
}
