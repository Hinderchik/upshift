package dev.upshift.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Loaded before NeoForge constructs its own mods.
 *
 * <p>NeoForge ships a development-only warning for mods that still use the
 * deprecated {@code logoFile} metadata key. That warning is cosmetic: the key
 * is still honoured, and players never see it because it is skipped in
 * production. Upshift audits the same metadata itself and shows one short
 * notice on the title screen, so the repeated warnings can be switched off
 * here without losing the information.</p>
 */
public final class UpshiftMixinPlugin implements IMixinConfigPlugin {
    private static final String HIDE_LOGO_WARNINGS = "neoforge.warnings.logofile.hide";

    @Override
    public void onLoad(String mixinPackage) {
        System.setProperty(HIDE_LOGO_WARNINGS, "true");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
