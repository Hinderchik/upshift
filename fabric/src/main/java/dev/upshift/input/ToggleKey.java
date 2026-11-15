package dev.upshift.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Toggle key for upscaling (F9 by default).
 */
public final class ToggleKey {
    public static final KeyMapping MAPPING = new KeyMapping(
        "key.upshift.toggle_upscaling",
        InputConstants.Type.KEYSYM,
        InputConstants.KEY_F9,
        KeyMapping.Category.register(Identifier.parse("upshift:controls"))
    );

    private ToggleKey() {
    }
}