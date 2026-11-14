package dev.upshift.sodium;

import dev.upshift.Upshift;
import dev.upshift.config.FrameGen;
import dev.upshift.config.FsrConfig;
import dev.upshift.config.QualityMode;
import dev.upshift.config.Upscaler;
import dev.upshift.gpu.Capabilities;
import dev.upshift.gpu.CapabilityReport;
import dev.upshift.overlay.OverlayMode;
import dev.upshift.perf.CpuTuner;
import dev.upshift.perf.PerfProfile;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.PageBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

@ConfigEntryPointForge(Upshift.MOD_ID)
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
    private static final Identifier OVERLAY_ID = Identifier.parse((String)"upshift:debug_overlay");
    private static final Identifier PROFILE_ID = Identifier.parse((String)"upshift:profile");
    private static final Identifier DYNAMIC_ID = Identifier.parse((String)"upshift:dynamic_scale");
    private static final Identifier TARGET_ID = Identifier.parse((String)"upshift:target_fps");
    private static final Identifier REPORT_ID = Identifier.parse((String)"upshift:report");
    private static final Identifier FG_FORCE_ID = Identifier.parse((String)"upshift:fg_force");
    private static final Identifier MENU_BOOST_ID = Identifier.parse((String)"upshift:menu_boost");
    private static final Identifier ENTITY_CULL_ID = Identifier.parse((String)"upshift:entity_cull");
    private static final Identifier INVISIBLE_CULL_ID = Identifier.parse((String)"upshift:cull_invisible");
    private static final Identifier PARTICLE_CULL_ID = Identifier.parse((String)"upshift:particle_limit");
    private static final Identifier PARTICLE_DISTANCE_ID = Identifier.parse((String)"upshift:particle_distance");
    private static final Identifier PARTICLE_BUDGET_ID = Identifier.parse((String)"upshift:particle_budget");
    private static final Identifier BLOCK_ENTITY_CULL_ID = Identifier.parse((String)"upshift:block_entity_cull");
    private static final Identifier FREEZE_ANIMATIONS_ID = Identifier.parse((String)"upshift:freeze_animations");
    private static final Identifier ANIMATION_SKIP_ID = Identifier.parse((String)"upshift:animation_skip");
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
            Upscaler upscaler = config.upscaler = upscalers.contains((Object)Upscaler.FSR1) ? Upscaler.FSR1 : upscalers.get(0);
        }
        if (!frameGens.contains((Object)config.frameGen)) {
            config.frameGen = frameGens.contains((Object)FrameGen.INTERNAL) ? FrameGen.INTERNAL : frameGens.get(0);
        }
        ModOptionsBuilder modOptions = builder.registerOwnModOptions().setName("Upshift")
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("upshift.page.preset"))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.translatable("upshift.group.preset"))
                                .addOption(builder.createIntegerOption(PROFILE_ID)
                                        .setName(Component.translatable("upshift.option.preset"))
                                        .setTooltip(index -> {
                                            PerfProfile preset = PerfProfile.values()[SodiumConfigEntry.clamp(index, PerfProfile.values().length)];
                                            return Component.translatable("upshift.tooltip.preset." + preset.id());
                                        })
                                        .setDefaultValue(Integer.valueOf(PerfProfile.BALANCED.ordinal()))
                                        .setRange(0, PerfProfile.values().length - 1, 1)
                                        .setValueFormatter(index -> Component.translatable("upshift.preset." + PerfProfile.values()[SodiumConfigEntry.clamp(index, PerfProfile.values().length)].id()))
                                        .setBinding(index -> CpuTuner.get().apply(PerfProfile.values()[SodiumConfigEntry.clamp(index, PerfProfile.values().length)]), () -> config.profile.ordinal())
                                        .setStorageHandler(config::save)
                                        .setImpact(OptionImpact.HIGH))))
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("upshift.page.upscaling"))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.translatable("upshift.group.profile"))
                                .addOption(builder.createIntegerOption(PROFILE_ID)
                                        .setName(Component.translatable("upshift.option.profile"))
                                        .setTooltip(index -> {
                                            PerfProfile preset = PerfProfile.values()[SodiumConfigEntry.clamp(index, PerfProfile.values().length)];
                                            return Component.literal((String)preset.note());
                                        })
                                        .setDefaultValue(Integer.valueOf(PerfProfile.BALANCED.ordinal()))
                                        .setRange(0, PerfProfile.values().length - 1, 1)
                                        .setValueFormatter(index -> Component.literal((String)SodiumConfigEntry.profileLabel(PerfProfile.values()[SodiumConfigEntry.clamp(index, PerfProfile.values().length)])))
                                        .setBinding(index -> CpuTuner.get().apply(PerfProfile.values()[SodiumConfigEntry.clamp(index, PerfProfile.values().length)]), () -> config.profile.ordinal())
                                        .setStorageHandler(config::save)
                                        .setImpact(OptionImpact.HIGH))
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
                                .setImpact(OptionImpact.LOW)))
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
        return value -> Component.literal((String)(value + "%"));
    }

    private static ControlValueFormatter stops() {
        return value -> Component.literal((String)String.format(Locale.ROOT, "%.2f", Float.valueOf((float)value / 100.0f)));
    }

    private static <T> ControlValueFormatter names(List<T> values, Function<T, String> id) {
        return index -> {
            if (index < 0 || index >= values.size()) {
                return Component.literal((String)"-");
            }
            Object value = values.get(index);
            if (value instanceof Upscaler) {
                Upscaler upscaler = (Upscaler)((Object)((Object)value));
                CapabilityReport.Row<Upscaler> row = Capabilities.report().row(upscaler);
                if (row.state() == CapabilityReport.State.BLOCKED) {
                    return Component.literal((String)(SodiumConfigEntry.upscalerLabel(upscaler) + " - needs native core"));
                }
                return Component.literal((String)SodiumConfigEntry.upscalerLabel(upscaler));
            }
            if (value instanceof FrameGen) {
                FrameGen frameGen = (FrameGen)((Object)((Object)value));
                CapabilityReport.Row<FrameGen> row = Capabilities.report().row(frameGen);
                if (row.state() == CapabilityReport.State.BLOCKED) {
                    return Component.literal((String)(SodiumConfigEntry.frameGenLabel(frameGen) + " - needs native core"));
                }
                return Component.literal((String)SodiumConfigEntry.frameGenLabel(frameGen));
            }
            @SuppressWarnings("unchecked")
            T typed = (T)value;
            return Component.literal(id.apply(typed));
        };
    }

    private static String overlayLabel(OverlayMode mode) {
        return switch (mode) {
            default -> throw new AssertionError("unreachable");
            case OverlayMode.OFF -> "Hidden";
            case OverlayMode.COMPACT -> "Compact";
            case OverlayMode.FULL -> "Full + graph";
        };
    }

    private static String profileLabel(PerfProfile profile) {
        return switch (profile) {
            default -> throw new AssertionError("unreachable");
            case PerfProfile.BALANCED -> "Balanced";
            case PerfProfile.CPU_FIRST -> "CPU first";
            case PerfProfile.GPU_FIRST -> "GPU first";
            case PerfProfile.INTEGRATED -> "Integrated graphics";
        };
    }

    private static String upscalerLabel(Upscaler upscaler) {
        return switch (upscaler) {
            default -> throw new AssertionError("unreachable");
            case Upscaler.OFF -> "Off";
            case Upscaler.FSR1 -> "FSR 1";
            case Upscaler.NIS -> "NIS (NVIDIA Image Scaling)";
            case Upscaler.TAAU -> "Temporal (TAAU, this mod)";
            case Upscaler.SHARPENING -> "RCAS Sharpening (native res)";
            case Upscaler.FSR2 -> "FSR 2";
            case Upscaler.FSR31 -> "FSR 3.1";
            case Upscaler.FSR4 -> "FSR 4";
            case Upscaler.XESS -> "XeSS";
            case Upscaler.DLSS -> "DLSS";
        };
    }

    private static String frameGenLabel(FrameGen frameGen) {
        return switch (frameGen) {
            default -> throw new AssertionError("unreachable");
            case FrameGen.OFF -> "Off";
            case FrameGen.INTERNAL -> "Built-in extrapolation";
            case FrameGen.FSR_FG -> "FSR frame generation";
            case FrameGen.DLSS_FG -> "DLSS frame generation";
            case FrameGen.XESS_FG -> "XeSS frame generation";
        };
    }

    private void dumpReport() {
        CapabilityReport report = Capabilities.report();
        LocalPlayer player = Minecraft.getInstance().player;
        Upshift.LOG.info("--- FSR + DLSS capability report ---");
        if (player != null) {
            player.sendSystemMessage((Component)Component.literal((String)"FSR + DLSS: capability report").withStyle(ChatFormatting.AQUA));
        }
        for (String line : report.summaryLines()) {
            Upshift.LOG.info(line);
            if (player == null) continue;
            ChatFormatting colour = line.contains(": READY") ? ChatFormatting.GREEN : (line.contains(": BLOCKED") ? ChatFormatting.YELLOW : (line.contains(": UNSUPPORTED") ? ChatFormatting.RED : ChatFormatting.GRAY));
            player.sendSystemMessage((Component)Component.literal((String)line).withStyle(colour));
        }
    }
}
