package dev.upshift.gpu;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.upshift.config.FrameGen;
import dev.upshift.config.FsrConfig;
import dev.upshift.config.Upscaler;
import dev.upshift.gpu.Backend;
import dev.upshift.gpu.CapabilityReport;
import dev.upshift.gpu.GpuProfile;
import dev.upshift.gpu.NativeLibraries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class Capabilities {
    private static volatile CapabilityReport cached;

    private Capabilities() {
    }

    public static CapabilityReport report() {
        CapabilityReport local = cached;
        if (local == null) {
            cached = local = Capabilities.probe();
        }
        return local;
    }

    public static void invalidate() {
        cached = null;
    }

    private static CapabilityReport probe() {
        Backend backend = Backend.current();
        GpuDevice device = RenderSystem.tryGetDevice();
        DeviceInfo info = device == null ? null : Capabilities.safeInfo(device);
        String deviceName = info == null ? "unknown" : Capabilities.safe(() -> ((DeviceInfo)info).name(), "unknown");
        String vendor = info == null ? "unknown" : Capabilities.safe(() -> ((DeviceInfo)info).vendorName(), "unknown");
        String driver = info == null ? "unknown" : Capabilities.safe(() -> ((DeviceInfo)info).driverInfo(), "unknown");
        boolean windows = NativeLibraries.isWindows();
        boolean linux = NativeLibraries.isLinux();
        GpuProfile gpu = GpuProfile.detect(deviceName, vendor, linux, windows);
        boolean vulkan = backend == Backend.VULKAN;
        boolean core = NativeLibraries.corePresent();
        boolean experimental = FsrConfig.get().allowExperimentalBackends;
        ArrayList<CapabilityReport.Row<Upscaler>> upscalers = new ArrayList<CapabilityReport.Row<Upscaler>>();
        upscalers.add(new CapabilityReport.Row<Upscaler>(Upscaler.OFF, CapabilityReport.State.READY, "no scaling; the game renders at window resolution"));
        upscalers.add(new CapabilityReport.Row<Upscaler>(Upscaler.FSR1, CapabilityReport.State.READY, "EASU + RCAS on " + backend.label() + ", any shader-capable GPU"));
        upscalers.add(new CapabilityReport.Row<Upscaler>(Upscaler.NIS, CapabilityReport.State.READY, "NVIDIA Image Scaling (MIT filter bank) on " + backend.label()));
        upscalers.add(new CapabilityReport.Row<Upscaler>(Upscaler.TAAU, CapabilityReport.State.READY, "temporal accumulation + bilinear sharpened resize on " + backend.label()));
        upscalers.add(Capabilities.spatialOnly(Upscaler.FSR2, gpu, backend, core, NativeLibraries.fidelityFxPresent(), vulkan, "FidelityFX FSR 2"));
        upscalers.add(Capabilities.spatialOnly(Upscaler.FSR31, gpu, backend, core, NativeLibraries.fidelityFxPresent(), vulkan, "FidelityFX FSR 3.1"));
        if (gpu.supportsFsr4()) {
            upscalers.add(Capabilities.spatialOnly(Upscaler.FSR4, gpu, backend, core, NativeLibraries.fidelityFxPresent(), vulkan, "FidelityFX FSR 4"));
        } else if (experimental && gpu.supportsFsr4Experimental()) {
            upscalers.add(Capabilities.spatialOnly(Upscaler.FSR4, gpu, backend, core, NativeLibraries.fidelityFxPresent(), vulkan, "FidelityFX FSR 4 (RDNA 3, reduced model)"));
        } else {
            upscalers.add(new CapabilityReport.Row<Upscaler>(Upscaler.FSR4, CapabilityReport.State.UNSUPPORTED, "FSR 4 needs RDNA 4 machine-learning hardware; this GPU is " + String.valueOf((Object)gpu.family())));
        }
        if (gpu.supportsXessXmx()) {
            upscalers.add(Capabilities.spatialOnly(Upscaler.XESS, gpu, backend, core, NativeLibraries.xessPresent(), vulkan, "XeSS on XMX"));
        } else if (gpu.supportsXessDp4a()) {
            upscalers.add(Capabilities.spatialOnly(Upscaler.XESS, gpu, backend, core, NativeLibraries.xessPresent(), vulkan, "XeSS on the DP4a shader path"));
        } else {
            upscalers.add(new CapabilityReport.Row<Upscaler>(Upscaler.XESS, CapabilityReport.State.UNSUPPORTED, "XeSS needs DP4a or XMX instructions; this GPU has neither"));
        }
        if (gpu.supportsDlssSr()) {
            upscalers.add(Capabilities.spatialOnly(Upscaler.DLSS, gpu, backend, core, NativeLibraries.ngxPresent(), vulkan, "NGX DLSS"));
        } else {
            String why = gpu.vendor() == GpuProfile.Vendor.NVIDIA ? "DLSS needs tensor cores, which arrive with Turing (RTX 20 series); this GPU is " + String.valueOf((Object)gpu.family()) : "DLSS is NVIDIA NGX only; this GPU is " + String.valueOf((Object)gpu.vendor()) + " " + String.valueOf((Object)gpu.family());
            upscalers.add(new CapabilityReport.Row<Upscaler>(Upscaler.DLSS, CapabilityReport.State.UNSUPPORTED, why));
        }
        ArrayList<CapabilityReport.Row<FrameGen>> frameGens = new ArrayList<CapabilityReport.Row<FrameGen>>();
        frameGens.add(new CapabilityReport.Row<FrameGen>(FrameGen.OFF, CapabilityReport.State.READY, "one rendered frame per presented frame"));
        frameGens.add(new CapabilityReport.Row<FrameGen>(FrameGen.INTERNAL, CapabilityReport.State.READY, "built-in extrapolation: predicted frames are presented between rendered frames, any GPU"));
        frameGens.add(Capabilities.spatialOnlyFg(FrameGen.FSR_FG, gpu.supportsFsr2Family(), gpu, backend, core, NativeLibraries.fidelityFxPresent(), vulkan, "FidelityFX FrameInterpolation"));
        if (gpu.supportsDlssFg()) {
            frameGens.add(Capabilities.spatialOnlyFg(FrameGen.DLSS_FG, true, gpu, backend, core, NativeLibraries.ngxPresent(), vulkan, "NGX DLSS-G"));
        } else {
            frameGens.add(new CapabilityReport.Row<FrameGen>(FrameGen.DLSS_FG, CapabilityReport.State.UNSUPPORTED, "DLSS frame generation needs Ada or newer (RTX 40 series); this GPU is " + String.valueOf((Object)gpu.vendor()) + " " + String.valueOf((Object)gpu.family())));
        }
        if (gpu.supportsXessXmx()) {
            frameGens.add(Capabilities.spatialOnlyFg(FrameGen.XESS_FG, true, gpu, backend, core, NativeLibraries.xessPresent(), vulkan, "XeSS-FG"));
        } else {
            frameGens.add(new CapabilityReport.Row<FrameGen>(FrameGen.XESS_FG, CapabilityReport.State.UNSUPPORTED, "XeSS frame generation needs Intel XMX hardware; this GPU is " + String.valueOf((Object)gpu.vendor()) + " " + String.valueOf((Object)gpu.family())));
        }
        // ANR FG is experimental-only and not offered on CurseForge builds.
        return new CapabilityReport(gpu, backend, deviceName, vendor, driver, List.copyOf(upscalers), List.copyOf(frameGens));
    }

    private static CapabilityReport.Row<Upscaler> spatialOnly(Upscaler upscaler, GpuProfile gpu, Backend backend, boolean core, boolean vendorLib, boolean vulkan, String family) {
        if (!gpu.supportsFsr2Family() && upscaler != Upscaler.XESS && upscaler != Upscaler.DLSS) {
            return new CapabilityReport.Row<Upscaler>(upscaler, CapabilityReport.State.UNSUPPORTED, family + " needs a GPU with compute shaders");
        }
        return Capabilities.wire(upscaler, backend, core, vendorLib, vulkan, family);
    }

    private static CapabilityReport.Row<FrameGen> spatialOnlyFg(FrameGen frameGen, boolean hardware, GpuProfile gpu, Backend backend, boolean core, boolean vendorLib, boolean vulkan, String family) {
        if (!hardware) {
            return new CapabilityReport.Row<FrameGen>(frameGen, CapabilityReport.State.UNSUPPORTED, family + " is not supported by " + String.valueOf((Object)gpu.vendor()) + " " + String.valueOf((Object)gpu.family()));
        }
        if (!vulkan) {
            return new CapabilityReport.Row<FrameGen>(frameGen, CapabilityReport.State.BLOCKED, family + " needs the Vulkan backend; the game is on " + backend.label());
        }
        if (!core) {
            return new CapabilityReport.Row<FrameGen>(frameGen, CapabilityReport.State.BLOCKED, family + " needs the native core; build it with scripts/build-native.sh");
        }
        if (!vendorLib) {
            return new CapabilityReport.Row<FrameGen>(frameGen, CapabilityReport.State.BLOCKED, family + " runtime is not installed");
        }
        return new CapabilityReport.Row<FrameGen>(frameGen, CapabilityReport.State.READY, family + " via Vulkan");
    }

    private static CapabilityReport.Row<Upscaler> wire(Upscaler upscaler, Backend backend, boolean core, boolean vendorLib, boolean vulkan, String family) {
        if (!vulkan) {
            return new CapabilityReport.Row<Upscaler>(upscaler, CapabilityReport.State.BLOCKED, family + " needs the Vulkan backend; the game is on " + backend.label() + ". Switch the renderer in video settings to unlock it.");
        }
        if (!core) {
            return new CapabilityReport.Row<Upscaler>(upscaler, CapabilityReport.State.BLOCKED, family + " needs the native core; build it with scripts/build-native.sh");
        }
        if (!vendorLib) {
            return new CapabilityReport.Row<Upscaler>(upscaler, CapabilityReport.State.BLOCKED, family + " runtime is not installed on this machine");
        }
        return new CapabilityReport.Row<Upscaler>(upscaler, CapabilityReport.State.READY, family + " via Vulkan");
    }

    private static DeviceInfo safeInfo(GpuDevice device) {
        try {
            return device.getDeviceInfo();
        }
        catch (Throwable t) {
            return null;
        }
    }

    private static String safe(Supplier<String> supplier, String fallback) {
        try {
            String value = supplier.get();
            return value == null || value.isBlank() ? fallback : value;
        }
        catch (Throwable t) {
            return fallback;
        }
    }
}
