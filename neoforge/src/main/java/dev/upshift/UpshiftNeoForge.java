package dev.upshift;

import dev.upshift.Upshift;
import dev.upshift.compat.MetadataNotice;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge entry point. The mod is client-only: it hooks into the renderer and
 * exposes its settings through Sodium, so nothing is loaded on a dedicated
 * server (where the mod may still sit in the mods folder).
 *
 * <p>{@link UpshiftClient} subscribes to the client setup event and starts the
 * runtime pieces once the game is ready for them.</p>
 */
@Mod(value = Upshift.MOD_ID, dist = Dist.CLIENT)
public final class UpshiftNeoForge {
    public UpshiftNeoForge() {
        NeoForge.EVENT_BUS.addListener(MetadataNotice::onScreenRender);
    }
}
