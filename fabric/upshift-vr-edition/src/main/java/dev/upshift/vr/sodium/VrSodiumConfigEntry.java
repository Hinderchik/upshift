package dev.upshift.vr.sodium;

import dev.upshift.Upshift;
import dev.upshift.vr.VrRuntime;
import dev.upshift.vr.config.VrConfig;
import dev.upshift.vr.perf.VrAutoResolution;
import dev.upshift.vr.render.VrMobileOptimizer;
import dev.upshift.vr.render.VrVulkanOptimizer;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.PageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class VrSodiumConfigEntry implements ConfigEntryPoint {
    private static final Identifier VR_ENABLED_ID = Identifier.parse("upshift_vr:vr_enabled");
    private static final Identifier VR_TEMPORAL_ID = Identifier.parse("upshift_vr:vr_temporal");
    private static final Identifier VR_TEMPORAL_CLAMP_ID = Identifier.parse("upshift_vr:vr_temporal_clamp");
    private static final Identifier VR_AUTO_RES_ID = Identifier.parse("upshift_vr:vr_auto_res");
    private static final Identifier VR_TARGET_FPS_ID = Identifier.parse("upshift_vr:vr_target_fps");
    private static final Identifier VR_FRAMEGEN_ID = Identifier.parse("upshift_vr:vr_framegen");
    private static final Identifier VR_FRAMEGEN_HMD_ID = Identifier.parse("upshift_vr:vr_framegen_hmd");
    private static final Identifier VR_FRAMEGEN_HANDS_ID = Identifier.parse("upshift_vr:vr_framegen_hands");
    private static final Identifier VR_FRAMEGEN_WARP_ANGLE_ID = Identifier.parse("upshift_vr:vr_framegen_warp_angle");
    private static final Identifier VR_HIDE_OVERLAY_ID = Identifier.parse("upshift_vr:vr_hide_overlay");
    private static final Identifier VR_PROJECT_OVERLAY_ID = Identifier.parse("upshift_vr:vr_project_overlay");
    private static final Identifier VR_OVERLAY_MODE_ID = Identifier.parse("upshift_vr:vr_overlay_mode");
    private static final Identifier VR_FOVEATED_ID = Identifier.parse("upshift_vr:vr_foveated");
    private static final Identifier VR_FOVEATED_RADIUS_ID = Identifier.parse("upshift_vr:vr_foveated_radius");
    private static final Identifier VR_FOVEATED_TRANSITION_ID = Identifier.parse("upshift_vr:vr_foveated_transition");
    private static final Identifier VR_FOVEATED_MIN_SCALE_ID = Identifier.parse("upshift_vr:vr_foveated_min_scale");
    private static final Identifier VR_VULKAN_SAFE_ID = Identifier.parse("upshift_vr:vr_vulkan_safe");
    private static final Identifier VR_MOBILE_LIGHT_ID = Identifier.parse("upshift_vr:vr_mobile_light");
    private static final Identifier VR_REPORT_ID = Identifier.parse("upshift_vr:vr_report");

    public void registerConfigLate(ConfigBuilder builder) {
        VrConfig config = VrConfig.get();
        ModOptionsBuilder modOptions = builder.registerOwnModOptions()
            .setName("Upshift VR Edition")
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("upshift_vr.page.general"))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("upshift_vr.group.core"))
                    .addOption(builder.createBooleanOption(VR_ENABLED_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_enabled"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_enabled"))
                        .setDefaultValue(Boolean.TRUE)
                        .setBinding(value -> { config.vrEnabled = value; }, () -> config.vrEnabled)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.HIGH))
                    .addOption(builder.createBooleanOption(VR_TEMPORAL_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_temporal"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_temporal"))
                        .setDefaultValue(Boolean.TRUE)
                        .setBinding(value -> { config.vrTemporalEnabled = value; }, () -> config.vrTemporalEnabled)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.HIGH))
                    .addOption(builder.createIntegerOption(VR_TEMPORAL_CLAMP_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_temporal_clamp"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_temporal_clamp"))
                        .setDefaultValue(Integer.valueOf(8))
                        .setRange(2, 25, 1)
                        .setValueFormatter(value -> Component.literal(String.format("%.2f", value / 100.0f)))
                        .setBinding(value -> { config.vrTemporalClampWeight = value.intValue() / 100.0f; config.save(); }, () -> Math.round(config.vrTemporalClampWeight * 100))
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.HIGH))
                    .addOption(builder.createBooleanOption(VR_AUTO_RES_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_auto_res"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_auto_res"))
                        .setDefaultValue(Boolean.TRUE)
                        .setBinding(value -> { config.vrAutoResolution = value; }, () -> config.vrAutoResolution)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.HIGH))
                    .addOption(builder.createIntegerOption(VR_TARGET_FPS_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_target_fps"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_target_fps"))
                        .setDefaultValue(Integer.valueOf(72))
                        .setRange(30, 144, 6)
                        .setValueFormatter(value -> Component.literal(value + " fps"))
                        .setBinding(value -> { config.vrTargetFps = value; }, () -> config.vrTargetFps)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.MEDIUM))))
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("upshift_vr.page.frame_gen"))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("upshift_vr.group.frame_gen"))
                    .addOption(builder.createBooleanOption(VR_FRAMEGEN_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_framegen"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_framegen"))
                        .setDefaultValue(Boolean.FALSE)
                        .setBinding(value -> { config.vrFrameGenEnabled = value; }, () -> config.vrFrameGenEnabled)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.HIGH))
                    .addOption(builder.createBooleanOption(VR_FRAMEGEN_HMD_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_framegen_hmd"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_framegen_hmd"))
                        .setDefaultValue(Boolean.TRUE)
                        .setBinding(value -> { config.vrFrameGenHmdPose = value; }, () -> config.vrFrameGenHmdPose)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.MEDIUM))
                    .addOption(builder.createBooleanOption(VR_FRAMEGEN_HANDS_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_framegen_hands"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_framegen_hands"))
                        .setDefaultValue(Boolean.TRUE)
                        .setBinding(value -> { config.vrFrameGenHandNative = value; }, () -> config.vrFrameGenHandNative)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.MEDIUM))
                    .addOption(builder.createIntegerOption(VR_FRAMEGEN_WARP_ANGLE_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_framegen_warp_angle"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_framegen_warp_angle"))
                        .setDefaultValue(Integer.valueOf(15))
                        .setRange(1, 45, 1)
                        .setValueFormatter(value -> Component.literal(value + "°"))
                        .setBinding(value -> { config.vrFrameGenMaxWarpAngle = value.intValue(); }, () -> Math.round(config.vrFrameGenMaxWarpAngle))
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.MEDIUM))))
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("upshift_vr.page.overlay"))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("upshift_vr.group.overlay"))
                    .addOption(builder.createBooleanOption(VR_HIDE_OVERLAY_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_hide_overlay"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_hide_overlay"))
                        .setDefaultValue(Boolean.TRUE)
                        .setBinding(value -> { config.vrHideFlatOverlay = value; }, () -> config.vrHideFlatOverlay)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.MEDIUM))
                    .addOption(builder.createBooleanOption(VR_PROJECT_OVERLAY_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_project_overlay"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_project_overlay"))
                        .setDefaultValue(Boolean.FALSE)
                        .setBinding(value -> { config.vrProjectOverlayToWrist = value; }, () -> config.vrProjectOverlayToWrist)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.LOW))
                    .addOption(builder.createIntegerOption(VR_OVERLAY_MODE_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_overlay_mode"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_overlay_mode"))
                        .setDefaultValue(Integer.valueOf(0))
                        .setRange(0, 2, 1)
                        .setValueFormatter(VrSodiumConfigEntry.overlayModeFormatter())
                        .setBinding(value -> { config.vrOverlayMode = value; }, () -> config.vrOverlayMode)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.LOW))))
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("upshift_vr.page.foveated"))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("upshift_vr.group.foveated"))
                    .addOption(builder.createBooleanOption(VR_FOVEATED_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_foveated"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_foveated"))
                        .setDefaultValue(Boolean.FALSE)
                        .setBinding(value -> { config.vrFoveatedEnabled = value; }, () -> config.vrFoveatedEnabled)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.LOW))
                    .addOption(builder.createIntegerOption(VR_FOVEATED_RADIUS_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_foveated_radius"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_foveated_radius"))
                        .setDefaultValue(Integer.valueOf(15))
                        .setRange(5, 40, 1)
                        .setValueFormatter(value -> Component.literal(value + "°"))
                        .setBinding(value -> { config.vrFoveatedFocalRadius = value.intValue(); }, () -> Math.round(config.vrFoveatedFocalRadius))
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.LOW))
                    .addOption(builder.createIntegerOption(VR_FOVEATED_TRANSITION_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_foveated_transition"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_foveated_transition"))
                        .setDefaultValue(Integer.valueOf(10))
                        .setRange(2, 30, 1)
                        .setValueFormatter(value -> Component.literal(value + "°"))
                        .setBinding(value -> { config.vrFoveatedTransition = value.intValue(); }, () -> Math.round(config.vrFoveatedTransition))
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.LOW))
                    .addOption(builder.createIntegerOption(VR_FOVEATED_MIN_SCALE_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_foveated_min_scale"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_foveated_min_scale"))
                        .setDefaultValue(Integer.valueOf(30))
                        .setRange(10, 80, 5)
                        .setValueFormatter(value -> Component.literal(String.format("%.0f%%", (float)value)))
                        .setBinding(value -> { config.vrFoveatedMinScale = value.intValue() / 100.0f; }, () -> Math.round(config.vrFoveatedMinScale * 100))
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.LOW))))
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("upshift_vr.page.advanced"))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("upshift_vr.group.vulkan"))
                    .addOption(builder.createBooleanOption(VR_VULKAN_SAFE_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_vulkan_safe"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_vulkan_safe"))
                        .setDefaultValue(Boolean.TRUE)
                        .setBinding(value -> { config.vrVulkanSafeMode = value; }, () -> config.vrVulkanSafeMode)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.MEDIUM))
                    .addOption(builder.createBooleanOption(VR_MOBILE_LIGHT_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_mobile_light"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_mobile_light"))
                        .setDefaultValue(Boolean.FALSE)
                        .setBinding(value -> { config.vrMobileLightweight = value; }, () -> config.vrMobileLightweight)
                        .setStorageHandler(config::save)
                        .setImpact(OptionImpact.MEDIUM)))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("upshift_vr.group.diagnostics"))
                    .addOption(builder.createExternalButtonOption(VR_REPORT_ID)
                        .setName(Component.translatable("upshift_vr.option.vr_report"))
                        .setTooltip(Component.translatable("upshift_vr.tooltip.vr_report"))
                        .setScreenConsumer(screen -> this.dumpReport()))));
    }

    private static ControlValueFormatter overlayModeFormatter() {
        return index -> {
            return switch (index) {
                case 0 -> Component.translatable("upshift_vr.overlay.off");
                case 1 -> Component.translatable("upshift_vr.overlay.flat");
                case 2 -> Component.translatable("upshift_vr.overlay.projected");
                default -> Component.literal("-");
            };
        };
    }

    private void dumpReport() {
        VrConfig config = VrConfig.get();
        VrRuntime vr = VrRuntime.INSTANCE;
        Upshift.LOG.info("--- Upshift VR Edition diagnostic report ---");
        Upshift.LOG.info("VR Enabled: {}", config.vrEnabled);
        Upshift.LOG.info("Stereo Active: {}", vr.isStereo());
        Upshift.LOG.info("Vulkan Backend: {}", vr.isVulkanBackend());
        Upshift.LOG.info("Mobile VR: {}", vr.isMobileVR());
        Upshift.LOG.info("Frame Gen: enabled={}, HMD pose={}, hands native={}", 
            config.vrFrameGenEnabled, config.vrFrameGenHmdPose, config.vrFrameGenHandNative);
        Upshift.LOG.info("Auto-res: {}, target FPS: {}", config.vrAutoResolution, config.vrTargetFps);
        Upshift.LOG.info("Foveated: enabled={}, radius={}°, transition={}°, minScale={}%", 
            config.vrFoveatedEnabled, config.vrFoveatedFocalRadius, config.vrFoveatedTransition, 
            Math.round(config.vrFoveatedMinScale * 100));
        Upshift.LOG.info("Vulkan Safe Mode: {}", config.vrVulkanSafeMode);
        Upshift.LOG.info("Mobile Lightweight: {}", config.vrMobileLightweight);
        Upshift.LOG.info("VrVulkanOptimizer: initialized={}", VrVulkanOptimizer.get().vulkanInitialized);
        Upshift.LOG.info("VrMobileOptimizer: detected={}, lightweight={}", 
            VrMobileOptimizer.get().mobileDetected(), VrMobileOptimizer.get().isLightweightMode());
    }
}