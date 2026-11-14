package dev.upshift.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.upshift.optim.TextCache;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reuses the visual-order layout of repeated strings. This is the call the
 * vanilla string overload makes right before creating the render state, so
 * skipping it removes the per-character decomposition without changing how the
 * text is drawn.
 */
@Mixin(value={GuiGraphicsExtractor.class})
public abstract class TextCacheMixin {
    @WrapOperation(
            method={"text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"},
            at={@At(value="INVOKE",
                    target="Lnet/minecraft/locale/Language;getVisualOrder(Lnet/minecraft/network/chat/FormattedText;)Lnet/minecraft/util/FormattedCharSequence;")})
    private FormattedCharSequence upshift$cachedVisualOrder(Language language, FormattedText text,
                                                            Operation<FormattedCharSequence> original) {
        String key = text.getString();
        FormattedCharSequence cached = TextCache.lookup(language, key);
        if (cached != null) {
            return cached;
        }
        FormattedCharSequence sequence = original.call(language, text);
        TextCache.store(language, key, sequence);
        return sequence;
    }
}
