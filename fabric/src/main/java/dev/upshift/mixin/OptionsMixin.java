package dev.upshift.mixin;

import dev.upshift.input.ToggleKey;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Appends our custom key mapping to the {@code Options.keyMappings} array
 * so it appears in the controls menu and is registered with the game.
 */
@Mixin(value = Options.class)
public abstract class OptionsMixin {
    @Shadow
    @Final
    @Mutable
    KeyMapping[] keyMappings;

    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void upshift$appendKeyMapping(CallbackInfo ci) {
        KeyMapping[] newMappings = Arrays.copyOf(this.keyMappings, this.keyMappings.length + 1);
        newMappings[newMappings.length - 1] = ToggleKey.MAPPING;
        Arrays.sort(newMappings);
        this.keyMappings = newMappings;
    }
}