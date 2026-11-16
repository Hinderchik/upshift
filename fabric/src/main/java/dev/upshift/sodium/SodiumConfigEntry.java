package dev.upshift.sodium;

import dev.upshift.Upshift;
import dev.upshift.config.FrameGen;
import dev.upshift.config.FsrConfig;
import dev.upshift.config.QualityMode;
import dev.upshift.config.Upscaler;
import dev.upshift.gpu.AnrDetector;
import dev.upshift.gpu.AnrDownloader;
import dev.upshift.gpu.Capabilities;
import dev.upshift.gpu.CapabilityReport;
import dev.upshift.overlay.OverlayMode;
import dev.upshift.perf.CpuTuner;
import dev.upshift.perf.PerfProfile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

public final class SodiumConfigEntry
implements ConfigEntryPoint {
    private static final Identifier UPSCALER_ID = Identifier.parse((String)"upshift:upscaler");
    private static final Identifier CUSTOM_SCALE_ID = Identifier.parse((String)"upshift:custom_scale");
    private static final Identifier QUALITY_ID = Identifier.parse((String)"upshift:quality");
    private static final Identifier SHARPNESS_ID = Identifier.parse((String)"upshift:sharpness");
    private static final Identifier FULL_EASU_ID = Identifier.parse((String)"upshift:full_easu");
    private static final Identifier AUTOFALLBACK_ID = Identifier.parse((String)"upshift:auto_fallback");
    private static final Identifier EXPERIMENTAL_ID = Identifier.parse((String)"upshift:experimental_backends");
    private static final Identifier FRAMEGEN_ID = Identifier.parse((String)"upshift:frame_gen");
    private static final Identifier FG_MULTIPLIER_ID = Identifier.parse((String)"upshift:fg_multiplier");
    private static final Identifier ANR_DOWNLOAD_ID = Identifier.parse((String)"upshift:anr_download");
    private static final Identifier ANR_FOLDER_ID = Identifier.parse((String)"upshift:anr_folder");
    private static final Identifier OVERLAY_ID = Identifier.parse((String)"upshift:debug_overlay");
    private static final Identifier PROFILE_ID = Identifier.parse((String)"upshift:profile");
    private static final Identifier REPORT_ID = Identifier.parse((String)"upshift:report");
    private static final Identifier FG_FORCE_ID = Identifier.parse((String)"upshift:fg_force");
    private static final Identifier MENU_BOOST_ID = Identifier.parse((String)"upshift:menu_boost");
    private static final Identifier SHARPENING_ONLY_ID = Identifier.parse((String)"upshift:sharpening_only");
    private static final Identifier ENTITY_CULL_ID = Identifier.parse((String)"upshift:entity_cull");
    private static final Identifier INVISIBLE_CULL_ID = Identifier.parse((String)"upshift:cull_invisible");
    private static final Identifier PARTICLE_CULL_ID = Identifier.parse((String)"upshift:particle_limit");
    private static final Identifier PARTICLE_DISTANCE_ID = Identifier.parse((String)"upshift:particle_distance");
    private static final Identifier PARTICLE_BUDGET_ID = Identifier.parse((String)"upshift:particle_budget");
    private static final Identifier BLOCK_ENTITY_CULL_ID = Identifier.parse((String)"upshift:block_entity_cull");
    private static final Identifier FREEZE_ANIMATIONS_ID = Identifier.parse((String)"upshift:freeze_animations");
    private static final Identifier ANIMATION_SKIP_ID = Identifier.parse((String)"upshift:animation_skip");
    private static final Identifier ANIMATION_WHITELIST_ID = Identifier.parse((String)"upshift:animation_whitelist");
    private static final Identifier CACHE_TEXT_ID = Identifier.parse((String)"upshift:cache_text");
    private static final Identifier THROTTLE_AI_ID = Identifier.parse((String)"upshift:throttle_ai");
    private static final Identifier AI_DISTANCE_ID = Identifier.parse((String)"upshift:ai_distance");
    private static final Identifier AI_INTERVAL_ID = Identifier.parse((String)"upshift:ai_interval");

    public void registerConfigLate(ConfigBuilder builder) {
        FsrConfig config = FsrConfig.get();
        CapabilityReport report = Capabilities.report();
        List<Upscaler> upscalers = report.offeredUpscalers();
        List<FrameGen> frameGens = report.offeredFrameGenerators();
        if (!upscalers.contains((Object)config.upscaler)) {
            config.upscaler = upscalers.contains((Object)Upscaler.FSR1) ? Upscaler.FSR1 : upscalers.get(0);
        }
        if (!frameGens.contains((Object)config.frameGen)) {
            config.frameGen = frameGens.contains((Object)FrameGen.INTERNAL) ? FrameGen.INTERNAL : frameGens.get(0);
        }
        ModOptionsBuilder modOptions = builder.registerOwnModOptions().setName("Upshift");
        modOptions.addPage(builder.createOptionPage()
                .setName(Component.translatable("upshift.page.preset"))
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Component.translatable("upshift.group.preset"))
                        .addOption(builder.createIntegerOption(PROFILE_ID)
                                .setName(Component.translatable("upshift.option.preset"))
                                .setTooltip(index -> {
                                    PerfProfile preset = PerfProfile.values()[clamp(index, PerfProfile.values().length)];
                                    return Component.translatable("upshift.tooltip.preset." + preset.id());
                                })
                                .setDefaultValue(Integer.valueOf(PerfProfile.BALANCED.ordinal()))
                                .setRange(0, PerfProfile.values().length - 1, 1)
                                .setValueFormatter(index -> Component.translatable("upshift.preset." + PerfProfile.values()[clamp(index, PerfProfile.values().length)].id()))
                                .setBinding(index -> CpuTuner.get().apply(PerfProfile.values()[clamp(index, PerfProfile.values().length)]), () -> config.profile.ordinal())
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.HIGH))));
        modOptions.addPage(builder.createOptionPage()
                .setName(Component.translatable("upshift.page.upscaling"))
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Component.translatable("upshift.group.upscaler"))
                        .addOption(builder.createIntegerOption(UPSCALER_ID)
                                .setName(Component.translatable("upshift.option.upscaler"))
                                .setTooltip(Component.literal(report.gpu().describe()))
                                .setDefaultValue(Integer.valueOf(0))
                                .setRange(0, Math.max(0, upscalers.size() - 1), 1)
                                .setValueFormatter(names(upscalers, Upscaler::id))
                                .setBinding(index -> {
                                    config.upscaler = upscalers.get(clamp(index, upscalers.size()));
                                }, () -> Math.max(0, upscalers.indexOf(config.upscaler)))
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.HIGH))
                        .addOption(builder.createIntegerOption(QUALITY_ID)
                                .setName(Component.translatable("upshift.option.quality"))
                                .setTooltip(Component.translatable("upshift.tooltip.quality"))
                                .setDefaultValue(Integer.valueOf(0))
                                .setRange(0, Math.max(0, QualityMode.forUpscaler(config.upscaler).size() - 1), 1)
                                .setValueFormatter(index -> {
                                    List<QualityMode> presets = QualityMode.forUpscaler(currentUpscaler());
                                    QualityMode mode = presets.get(clamp(index, presets.size()));
                                    return Component.literal(mode.label() + "  " + Math.round(mode.scale() * 100.0f) + "%");
                                })
                                .setBinding(index -> {
                                    List<QualityMode> presets = QualityMode.forUpscaler(currentUpscaler());
                                    config.applyQuality(presets.get(clamp(index, presets.size())));
                                }, () -> config.qualityIndex(currentUpscaler()))
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.HIGH)))
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Component.translatable("upshift.group.resolution"))
                        .addOption(builder.createIntegerOption(CUSTOM_SCALE_ID)
                                .setName(Component.translatable("upshift.option.scale"))
                                .setTooltip(Component.translatable("upshift.tooltip.scale"))
                                .setDefaultValue(Integer.valueOf(67))
                                .setRange(25, 100, 1)
                                .setValueFormatter(percent())
                                .setBinding(value -> {
                                    config.customScale = (float)value / 100.0f;
                                }, () -> Math.round(config.renderScale() * 100.0f))
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.HIGH))
                        .addOption(builder.createBooleanOption(FULL_EASU_ID)
                                .setName(Component.translatable("upshift.option.full_easu"))
                                .setTooltip(Component.translatable("upshift.tooltip.full_easu"))
                                .setDefaultValue(Boolean.valueOf(false))
                                .setBinding(value -> {
                                    config.fastUpscale = !value.booleanValue();
                                }, () -> !config.fastUpscale)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.MEDIUM))
                        .addOption(builder.createIntegerOption(SHARPNESS_ID)
                                .setName(Component.translatable("upshift.option.sharpness"))
                                .setTooltip(Component.translatable("upshift.tooltip.sharpness"))
                                .setDefaultValue(Integer.valueOf(0))
                                .setRange(0, 200, 5)
                                .setValueFormatter(stops())
                                .setBinding(value -> {
                                    config.sharpness = (float)value / 100.0f;
                                }, () -> Math.round(config.sharpness * 100.0f))
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.LOW))
                        .addOption(builder.createBooleanOption(SHARPENING_ONLY_ID)
                                .setName(Component.translatable("upshift.option.sharpening_only"))
                                .setTooltip(Component.translatable("upshift.tooltip.sharpening_only"))
                                .setDefaultValue(Boolean.valueOf(false))
                                .setBinding(value -> {
                                    config.sharpeningOnly = value;
                                }, () -> config.sharpeningOnly)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.MEDIUM)))
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Component.translatable("upshift.group.behaviour"))
                        .addOption(builder.createBooleanOption(AUTOFALLBACK_ID)
                                .setName(Component.translatable("upshift.option.auto_fallback"))
                                .setTooltip(Component.translatable("upshift.tooltip.auto_fallback"))
                                .setDefaultValue(Boolean.valueOf(true))
                                .setBinding(value -> {
                                    config.autoFallback = value;
                                }, () -> config.autoFallback)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.LOW))
                        .addOption(builder.createBooleanOption(EXPERIMENTAL_ID)
                                .setName(Component.translatable("upshift.option.experimental"))
                                .setTooltip(Component.translatable("upshift.tooltip.experimental"))
                                .setDefaultValue(Boolean.valueOf(true))
                                .setBinding(value -> {
                                    config.allowExperimentalBackends = value;
                                }, () -> config.allowExperimentalBackends)
                                .setStorageHandler(() -> {
                                    config.save();
                                    Capabilities.invalidate();
                                })
                                .setImpact(OptionImpact.MEDIUM))
                        .addOption(builder.createBooleanOption(MENU_BOOST_ID)
                                .setName(Component.translatable("upshift.option.menu_boost"))
                                .setTooltip(Component.translatable("upshift.tooltip.menu_boost"))
                                .setDefaultValue(Boolean.valueOf(true))
                                .setBinding(value -> {
                                    config.menuBoost = value;
                                    if (value.booleanValue()) {
                                        CpuTuner.get().dropMenuBlur();
                                    }
                                }, () -> config.menuBoost)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.HIGH))
                        .addOption(builder.createIntegerOption(OVERLAY_ID)
                                .setName(Component.translatable("upshift.option.overlay"))
                                .setTooltip(Component.translatable("upshift.tooltip.overlay"))
                                .setDefaultValue(Integer.valueOf(OverlayMode.FULL.ordinal()))
                                .setRange(0, OverlayMode.values().length - 1, 1)
                                .setValueFormatter(index -> Component.literal(overlayLabel(OverlayMode.values()[clamp(index, OverlayMode.values().length)])))
                                .setBinding(index -> {
                                    config.overlay = OverlayMode.values()[clamp(index, OverlayMode.values().length)];
                                }, () -> config.overlay.ordinal())
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.LOW))));
        // Split frame generators into available (runnable) and unavailable
        List<FrameGen> runnableFrameGens = report.runnableFrameGenerators();
        final List<FrameGen> effectiveRunnableFrameGens = runnableFrameGens.isEmpty()
            ? List.of(FrameGen.INTERNAL)
            : runnableFrameGens;
        List<FrameGen> unavailableFrameGens = new ArrayList<>(report.offeredFrameGenerators());
        unavailableFrameGens.removeAll(effectiveRunnableFrameGens);

        // Group 1: Available frame generators (runnable)
        OptionGroupBuilder availableFgGroup = builder.createOptionGroup()
                .setName(Component.translatable("upshift.group.frame_gen_available"))
                .addOption(builder.createIntegerOption(FRAMEGEN_ID)
                        .setName(Component.translatable("upshift.option.frame_gen"))
                        .setTooltip(Component.translatable("upshift.tooltip.frame_gen"))
                        .setDefaultValue(Integer.valueOf(0))
                        .setRange(0, Math.max(0, effectiveRunnableFrameGens.size() - 1), 1)
                        .setValueFormatter(names(effectiveRunnableFrameGens, FrameGen::id))
                        .setBinding(index -> {
                            config.frameGen = effectiveRunnableFrameGens.get(clamp(index, effectiveRunnableFrameGens.size()));
                        }, () -> Math.max(0, effectiveRunnableFrameGens.indexOf(config.frameGen)))
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.HIGH));

        // Group 2: Unavailable frame generators (disabled, with reason)
        OptionGroupBuilder unavailableFgGroup = builder.createOptionGroup()
                .setName(Component.translatable("upshift.group.frame_gen_unavailable"));
        for (FrameGen fg : unavailableFrameGens) {
            CapabilityReport.Row<FrameGen> row = report.row(fg);
            String reason = row.detail().isEmpty() ? row.state().name() : row.detail();
            Component name = Component.literal(frameGenLabel(fg));
            Component tooltip = Component.translatable("upshift.tooltip.fg_unavailable", reason);
            String optId = "upshift:fg_unavailable_" + fg.id();
            unavailableFgGroup.addOption(builder.createIntegerOption(Identifier.parse(optId))
                    .setName(name)
                    .setTooltip(tooltip)
                    .setDefaultValue(Integer.valueOf(0))
                    .setRange(0, 0, 1)
                    .setEnabled(false)
                    .setValueFormatter(idx -> name));
        }
        if (unavailableFrameGens.isEmpty()) {
            unavailableFgGroup.addOption(builder.createBooleanOption(Identifier.parse("upshift:fg_none_unavailable"))
                    .setName(Component.translatable("upshift.option.fg_none_unavailable"))
                    .setTooltip(Component.translatable("upshift.tooltip.fg_none_unavailable"))
                    .setDefaultValue(Boolean.valueOf(false))
                    .setEnabled(false));
        }

        // Group 3: Frame generation settings
        OptionGroupBuilder fgSettingsGroup = builder.createOptionGroup()
                .setName(Component.translatable("upshift.group.frame_gen_settings"))
                .addOption(builder.createIntegerOption(FG_MULTIPLIER_ID)
                        .setName(Component.translatable("upshift.option.fg_multiplier"))
                        .setTooltip(Component.translatable("upshift.tooltip.fg_multiplier"))
                        .setDefaultValue(Integer.valueOf(2))
                        .setRange(1, 4, 1)
                        .setValueFormatter(value -> Component.literal(value + "x"))
                        .setBinding(value -> {
                            config.frameGenMultiplier = value;
                        }, () -> Math.clamp(config.frameGenMultiplier, 1, 4))
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.MEDIUM))
                .addOption(builder.createBooleanOption(FG_FORCE_ID)
                        .setName(Component.translatable("upshift.option.fg_force"))
                        .setTooltip(Component.translatable("upshift.tooltip.fg_force"))
                        .setDefaultValue(Boolean.valueOf(false))
                        .setBinding(value -> {
                            config.frameGenForce = value;
                        }, () -> config.frameGenForce)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.MEDIUM))
                .addOption(builder.createExternalButtonOption(REPORT_ID)
                        .setName(Component.translatable("upshift.option.report"))
                        .setTooltip(Component.translatable("upshift.tooltip.report"))
                        .setScreenConsumer(screen -> this.dumpReport()));

        // Group 4: ANR specific (only when GPU supported)
        OptionGroupBuilder anrGroup = builder.createOptionGroup()
                .setName(Component.translatable("upshift.group.anr"));
        if (AnrDetector.isSupported()) {
            Upshift.LOG.info("ANR FG offered: {}", AnrDetector.getReason());
            if (AnrDownloader.isInstalled()) {
                if (AnrDownloader.isLayerInstalled()) {
                    anrGroup.addOption(builder.createExternalButtonOption(ANR_FOLDER_ID)
                            .setName(Component.translatable("upshift.option.anr_layer_active"))
                            .setTooltip(Component.translatable("upshift.tooltip.anr_layer_active",
                                    AnrDownloader.installedVersion()))
                            .setScreenConsumer(screen -> this.openAnrFolder()));
                    anrGroup.addOption(builder.createExternalButtonOption(ANR_FOLDER_ID)
                            .setName(Component.translatable("upshift.option.anr_uninstall"))
                            .setTooltip(Component.translatable("upshift.tooltip.anr_uninstall"))
                            .setScreenConsumer(screen -> {
                                AnrDownloader.uninstallLayer();
                                this.notifyPlayer(Component.translatable("upshift.anr.uninstalled").withStyle(ChatFormatting.YELLOW));
                            }));
                } else {
                    anrGroup.addOption(builder.createExternalButtonOption(ANR_DOWNLOAD_ID)
                            .setName(Component.translatable("upshift.option.anr_install_layer"))
                            .setTooltip(Component.translatable("upshift.tooltip.anr_install_layer"))
                            .setScreenConsumer(this::askAnrConsent));
                }
            } else {
                anrGroup.addOption(builder.createExternalButtonOption(ANR_DOWNLOAD_ID)
                        .setName(Component.translatable("upshift.option.anr_download"))
                        .setTooltip(Component.translatable("upshift.tooltip.anr_download"))
                        .setScreenConsumer(this::askAnrConsent));
            }
        } else {
            anrGroup.addOption(builder.createExternalButtonOption(ANR_DOWNLOAD_ID)
                    .setName(Component.translatable("upshift.option.anr_unsupported"))
                    .setTooltip(Component.translatable("upshift.tooltip.anr_unsupported",
                            AnrDetector.getReason()))
                    .setEnabled(false));
        }

        modOptions.addPage(builder.createOptionPage()
                .setName(Component.translatable("upshift.page.frame_gen"))
                .addOptionGroup(availableFgGroup)
                .addOptionGroup(unavailableFgGroup)
                .addOptionGroup(fgSettingsGroup)
                .addOptionGroup(anrGroup));
        modOptions.addPage(builder.createOptionPage()
                .setName(Component.translatable("upshift.page.optimizations"))
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Component.translatable("upshift.group.culling"))
                        .addOption(builder.createIntegerOption(ENTITY_CULL_ID)
                                .setName(Component.translatable("upshift.option.entity_cull"))
                                .setTooltip(Component.translatable("upshift.tooltip.entity_cull"))
                                .setDefaultValue(Integer.valueOf(128))
                                .setRange(0, 256, 16)
                                .setValueFormatter(value -> Component.literal(value <= 0 ? "Vanilla distance" : value + " blocks"))
                                .setBinding(value -> {
                                    config.entityCullDistance = value;
                                }, () -> config.entityCullDistance)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.MEDIUM))
                        .addOption(builder.createBooleanOption(INVISIBLE_CULL_ID)
                                .setName(Component.translatable("upshift.option.cull_invisible"))
                                .setTooltip(Component.translatable("upshift.tooltip.cull_invisible"))
                                .setDefaultValue(Boolean.valueOf(false))
                                .setBinding(value -> {
                                    config.cullInvisibleMobs = value;
                                }, () -> config.cullInvisibleMobs)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.LOW))
                        .addOption(builder.createIntegerOption(BLOCK_ENTITY_CULL_ID)
                                .setName(Component.translatable("upshift.option.block_entity_cull"))
                                .setTooltip(Component.translatable("upshift.tooltip.block_entity_cull"))
                                .setDefaultValue(Integer.valueOf(64))
                                .setRange(0, 256, 16)
                                .setValueFormatter(value -> Component.literal(value <= 0 ? "Vanilla distance" : value + " blocks"))
                                .setBinding(value -> {
                                    config.blockEntityDistance = value;
                                }, () -> config.blockEntityDistance)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.MEDIUM)))
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Component.translatable("upshift.group.particles"))
                        .addOption(builder.createBooleanOption(PARTICLE_CULL_ID)
                                .setName(Component.translatable("upshift.option.particle_limit"))
                                .setTooltip(Component.translatable("upshift.tooltip.particle_limit"))
                                .setDefaultValue(Boolean.valueOf(true))
                                .setBinding(value -> {
                                    config.limitParticles = value;
                                }, () -> config.limitParticles)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.MEDIUM))
                        .addOption(builder.createIntegerOption(PARTICLE_DISTANCE_ID)
                                .setName(Component.translatable("upshift.option.particle_distance"))
                                .setTooltip(Component.translatable("upshift.tooltip.particle_distance"))
                                .setDefaultValue(Integer.valueOf(32))
                                .setRange(8, 128, 8)
                                .setValueFormatter(value -> Component.literal(value + " blocks"))
                                .setBinding(value -> {
                                    config.particleDistance = value;
                                }, () -> config.particleDistance)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.LOW))
                        .addOption(builder.createIntegerOption(PARTICLE_BUDGET_ID)
                                .setName(Component.translatable("upshift.option.particle_budget"))
                                .setTooltip(Component.translatable("upshift.tooltip.particle_budget"))
                                .setDefaultValue(Integer.valueOf(1500))
                                .setRange(100, 5000, 100)
                                .setValueFormatter(value -> Component.literal(value + " / tick"))
                                .setBinding(value -> {
                                    config.particleTickBudget = value;
                                }, () -> config.particleTickBudget)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.LOW)))
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Component.translatable("upshift.group.textures"))
                        .addOption(builder.createBooleanOption(FREEZE_ANIMATIONS_ID)
                                .setName(Component.translatable("upshift.option.freeze_animations"))
                                .setTooltip(Component.translatable("upshift.tooltip.freeze_animations"))
                                .setDefaultValue(Boolean.valueOf(true))
                                .setBinding(value -> {
                                    config.freezeAnimationsUnfocused = value;
                                }, () -> config.freezeAnimationsUnfocused)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.LOW))
                        .addOption(builder.createIntegerOption(ANIMATION_SKIP_ID)
                                .setName(Component.translatable("upshift.option.animation_skip"))
                                .setTooltip(Component.translatable("upshift.tooltip.animation_skip"))
                                .setDefaultValue(Integer.valueOf(1))
                                .setRange(1, 4, 1)
                                .setValueFormatter(value -> Component.literal(value <= 1 ? "Every frame" : "Every " + value + " frames"))
                                .setBinding(value -> {
                                    config.animationFrameSkip = value;
                                }, () -> config.animationFrameSkip)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.LOW))
                        .addOption(builder.createExternalButtonOption(ANIMATION_WHITELIST_ID)
                                .setName(Component.translatable("upshift.option.animation_whitelist"))
                                .setTooltip(Component.translatable("upshift.tooltip.animation_whitelist"))
                                .setScreenConsumer(screen -> {
                                    try {
                                        java.awt.Desktop.getDesktop().edit(FsrConfig.path().toFile());
                                    } catch (Exception e) {
                                        try {
                                            net.minecraft.util.Util.getPlatform().openFile(FsrConfig.path().toFile());
                                        } catch (Exception ex) {
                                            Upshift.LOG.warn("could not open animation whitelist config", ex);
                                        }
                                    }
                                })))
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Component.translatable("upshift.group.text"))
                        .addOption(builder.createBooleanOption(CACHE_TEXT_ID)
                                .setName(Component.translatable("upshift.option.cache_text"))
                                .setTooltip(Component.translatable("upshift.tooltip.cache_text"))
                                .setDefaultValue(Boolean.valueOf(true))
                                .setBinding(value -> {
                                    config.cacheText = value;
                                }, () -> config.cacheText)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.LOW)))
                .addOptionGroup(builder.createOptionGroup()
                        .setName(Component.translatable("upshift.group.ai"))
                        .addOption(builder.createBooleanOption(THROTTLE_AI_ID)
                                .setName(Component.translatable("upshift.option.throttle_ai"))
                                .setTooltip(Component.translatable("upshift.tooltip.throttle_ai"))
                                .setDefaultValue(Boolean.valueOf(true))
                                .setBinding(value -> {
                                    config.throttleAi = value;
                                }, () -> config.throttleAi)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.MEDIUM))
                        .addOption(builder.createIntegerOption(AI_DISTANCE_ID)
                                .setName(Component.translatable("upshift.option.ai_distance"))
                                .setTooltip(Component.translatable("upshift.tooltip.ai_distance"))
                                .setDefaultValue(Integer.valueOf(48))
                                .setRange(0, 128, 16)
                                .setValueFormatter(value -> Component.literal(value <= 0 ? "Off" : value + " blocks"))
                                .setBinding(value -> {
                                    config.aiThrottleDistance = value;
                                }, () -> config.aiThrottleDistance)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.MEDIUM))
                        .addOption(builder.createIntegerOption(AI_INTERVAL_ID)
                                .setName(Component.translatable("upshift.option.ai_interval"))
                                .setTooltip(Component.translatable("upshift.tooltip.ai_interval"))
                                .setDefaultValue(Integer.valueOf(4))
                                .setRange(2, 20, 2)
                                .setValueFormatter(value -> Component.literal("Every " + value + " ticks"))
                                .setBinding(value -> {
                                    config.aiThrottleInterval = value;
                                }, () -> config.aiThrottleInterval)
                                .setStorageHandler(config::save)
                                .setImpact(OptionImpact.MEDIUM))));
    }

    private static Upscaler currentUpscaler() {
        return FsrConfig.get().upscaler;
    }

    private static int clamp(int index, int size) {
        return Math.max(0, Math.min(size - 1, index));
    }

    private static ControlValueFormatter percent() {
        return value -> Component.literal(value + "%");
    }

    private static ControlValueFormatter stops() {
        return value -> Component.literal(String.format(Locale.ROOT, "%.2f", Float.valueOf((float)value / 100.0f)));
    }

    private static <T> ControlValueFormatter names(List<T> values, Function<T, String> id) {
        return index -> {
            if (index < 0 || index >= values.size()) {
                return Component.literal("-");
            }
            Object value = values.get(index);
            if (value instanceof Upscaler) {
                Upscaler upscaler = (Upscaler)value;
                CapabilityReport.Row<Upscaler> row = Capabilities.report().row(upscaler);
                if (row.state() == CapabilityReport.State.BLOCKED) {
                    return Component.literal(upscalerLabel(upscaler) + " - needs native core");
                }
                return Component.literal(upscalerLabel(upscaler));
            }
            if (value instanceof FrameGen) {
                FrameGen frameGen = (FrameGen)value;
                CapabilityReport.Row<FrameGen> row = Capabilities.report().row(frameGen);
                if (row.state() == CapabilityReport.State.BLOCKED) {
                    String suffix;
                    if (frameGen == FrameGen.ANR) {
                        suffix = AnrDownloader.isInstalled() ? " - package staged" : " - download needed";
                    } else {
                        suffix = " - needs native core";
                    }
                    return Component.literal(frameGenLabel(frameGen) + suffix);
                }
                return Component.literal(frameGenLabel(frameGen));
            }
            @SuppressWarnings("unchecked")
            T typed = (T)value;
            return Component.literal(id.apply(typed));
        };
    }

    private static String overlayLabel(OverlayMode mode) {
        return switch (mode) {
            case OFF -> "Hidden";
            case COMPACT -> "Compact";
            case FULL -> "Full + graph";
        };
    }

    private static String profileLabel(PerfProfile profile) {
        return switch (profile) {
            case BALANCED -> "Balanced";
            case CPU_FIRST -> "CPU first";
            case GPU_FIRST -> "GPU first";
            case INTEGRATED -> "Integrated graphics";
        };
    }

    private static String upscalerLabel(Upscaler upscaler) {
        return switch (upscaler) {
            case OFF -> "Off";
            case FSR1 -> "FSR 1";
            case NIS -> "NIS (NVIDIA Image Scaling)";
            case TAAU -> "Temporal (TAAU, this mod)";
            case SHARPENING -> "RCAS Sharpening (native res)";
            case FSR2 -> "FSR 2";
            case FSR31 -> "FSR 3.1";
            case FSR4 -> "FSR 4";
            case XESS -> "XeSS";
            case DLSS -> "DLSS";
        };
    }

    private static String frameGenLabel(FrameGen frameGen) {
        return switch (frameGen) {
            case OFF -> "Off";
            case INTERNAL -> "Built-in extrapolation";
            case FSR_FG -> "FSR frame generation";
            case DLSS_FG -> "DLSS frame generation";
            case XESS_FG -> "XeSS frame generation";
            case ANR -> "ANR FG (external add-on)";
        };
    }

    /**
     * Shows the consent screen before anything is downloaded. The player has to press
     * "Download" in that screen; closing it or pressing "Cancel" leaves the mod untouched.
     */
    private void askAnrConsent(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        ConfirmScreen consent = new ConfirmScreen(accepted -> {
            minecraft.setScreenAndShow(parent);
            if (!accepted) {
                return;
            }
            AnrDownloader.giveConsent();
            this.notifyPlayer(Component.translatable("upshift.anr.started").withStyle(ChatFormatting.AQUA));
            AnrDownloader.ensureDownloaded().thenAccept(success -> minecraft.execute(() -> {
                if (success) {
                    this.notifyPlayer(Component.translatable("upshift.anr.done", AnrDownloader.getInstallDirectory().toString()).withStyle(ChatFormatting.GREEN));
                } else {
                    this.notifyPlayer(Component.translatable("upshift.anr.failed", AnrDownloader.getLastError()).withStyle(ChatFormatting.RED));
                }
            }));
        },
            Component.translatable("upshift.anr.consent.title"),
            Component.translatable("upshift.anr.consent.message"),
            Component.translatable("upshift.anr.consent.yes"),
            Component.translatable("upshift.anr.consent.no"));
        // setDelay() must be called AFTER init() (which creates yesButton/noButton),
        // but we can't hook into that here. Skip it; the explicit consent is sufficient.
        minecraft.setScreenAndShow(consent);
    }

    private void openAnrFolder() {
        Path directory = AnrDownloader.getInstallDirectory();
        try {
            Util.getPlatform().openPath(directory);
        } catch (Throwable t) {
            Upshift.LOG.warn("could not open the ANR folder {}", directory, t);
        }
    }

    private void notifyPlayer(Component message) {
        Upshift.LOG.info(message.getString());
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }

    private void dumpReport() {
        CapabilityReport report = Capabilities.report();
        LocalPlayer player = Minecraft.getInstance().player;
        Upshift.LOG.info("--- FSR + DLSS capability report ---");
        if (player != null) {
            player.sendSystemMessage(Component.literal("FSR + DLSS: capability report").withStyle(ChatFormatting.AQUA));
        }
        for (String line : report.summaryLines()) {
            Upshift.LOG.info(line);
            if (player == null) continue;
            ChatFormatting colour = line.contains(": READY") ? ChatFormatting.GREEN : (line.contains(": BLOCKED") ? ChatFormatting.YELLOW : (line.contains(": UNSUPPORTED") ? ChatFormatting.RED : ChatFormatting.GRAY));
            player.sendSystemMessage(Component.literal(line).withStyle(colour));
        }
    }
}
