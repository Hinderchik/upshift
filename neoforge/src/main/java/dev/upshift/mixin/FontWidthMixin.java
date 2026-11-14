package dev.upshift.mixin;

import dev.upshift.optim.TextCache;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches {@code Font.width(String)}. The vanilla implementation walks every
 * code point and allocates a helper per call; widgets ask for the same widths
 * every frame (centring, tooltips, the debug screen), so the answer is kept on
 * the font instance. A resource reload builds a new {@code Font}, which drops
 * the cache with it.
 */
@Mixin(value={Font.class})
public abstract class FontWidthMixin {
    @Unique
    private final Object2IntOpenHashMap<String> upshift$widthCache = new Object2IntOpenHashMap<>(256);

    @Inject(method={"width(Ljava/lang/String;)I"}, at={@At(value="HEAD")}, cancellable=true)
    private void upshift$cachedWidth(String text, CallbackInfoReturnable<Integer> callback) {
        if (text == null || !TextCache.cacheWidths()) {
            return;
        }
        if (this.upshift$widthCache.containsKey(text)) {
            callback.setReturnValue(this.upshift$widthCache.getInt(text));
        }
    }

    @Inject(method={"width(Ljava/lang/String;)I"}, at={@At(value="RETURN")})
    private void upshift$storeWidth(String text, CallbackInfoReturnable<Integer> callback) {
        if (text == null || text.length() > 512 || !TextCache.cacheWidths()) {
            return;
        }
        if (this.upshift$widthCache.size() >= 2048) {
            this.upshift$widthCache.clear();
        }
        this.upshift$widthCache.put(text, callback.getReturnValueI());
    }
}
