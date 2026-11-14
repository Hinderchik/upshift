package dev.upshift.gpu;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record GpuProfile(Vendor vendor, Family family, String marketingName, boolean tensorCores, boolean rayTracing, boolean dp4a, boolean ngxPlatform, boolean integrated) {
    private static final Pattern NVIDIA_MODEL = Pattern.compile("(RTX|GTX|MX|TITAN)[\\s-]*(\\d{3,4})");
    private static final Pattern AMD_MODEL = Pattern.compile("RX[\\s-]*(\\d{3,4})");

    public static GpuProfile detect(String deviceName, String vendorName, boolean linux, boolean windows) {
        String name = deviceName == null ? "" : deviceName.toUpperCase(Locale.ROOT);
        String vendorText = vendorName == null ? "" : vendorName.toUpperCase(Locale.ROOT);
        Vendor vendor = GpuProfile.classifyVendor(name, vendorText);
        return switch (vendor.ordinal()) {
            case 0 -> GpuProfile.nvidia(name, deviceName, windows);
            case 1 -> GpuProfile.amd(name, deviceName);
            case 2 -> GpuProfile.intel(name, deviceName);
            case 3 -> new GpuProfile(Vendor.APPLE, Family.APPLE_SILICON, deviceName, false, false, true, false, true);
            default -> new GpuProfile(vendor, Family.OTHER, deviceName, false, false, false, false, false);
        };
    }

    private static Vendor classifyVendor(String name, String vendorText) {
        if (name.contains("NVIDIA") || vendorText.contains("NVIDIA")) {
            return Vendor.NVIDIA;
        }
        if (name.contains("RADEON") || name.contains("AMD") || vendorText.contains("AMD") || vendorText.contains("ATI")) {
            return Vendor.AMD;
        }
        if (name.contains("INTEL") || vendorText.contains("INTEL")) {
            return Vendor.INTEL;
        }
        if (name.contains("APPLE") || vendorText.contains("APPLE")) {
            return Vendor.APPLE;
        }
        if (name.contains("QUALCOMM") || name.contains("ADRENO")) {
            return Vendor.QUALCOMM;
        }
        return Vendor.OTHER;
    }

    private static GpuProfile nvidia(String name, String original, boolean windows) {
        Family family;
        boolean ngx = windows;
        Matcher matcher = NVIDIA_MODEL.matcher(name);
        int model = matcher.find() ? Integer.parseInt(matcher.group(2)) : -1;
        boolean tensor = false;
        boolean rt = false;
        if (model < 0) {
            if (name.contains("TITAN V")) {
                family = Family.TURING;
                tensor = true;
            } else {
                family = Family.NVIDIA_UNKNOWN;
            }
        } else if (name.contains("RTX") && model >= 5000) {
            family = Family.BLACKWELL;
            tensor = true;
            rt = true;
        } else if (name.contains("RTX") && model >= 4000) {
            family = Family.ADA;
            tensor = true;
            rt = true;
        } else if (name.contains("RTX") && model >= 3000) {
            family = Family.AMPERE;
            tensor = true;
            rt = true;
        } else if (name.contains("RTX") && model >= 2000) {
            family = Family.TURING;
            tensor = true;
            rt = true;
        } else {
            family = name.contains("GTX") && model / 100 == 16 ? Family.TURING : (model / 100 == 10 || model == 1080 || model == 1070 || model == 1060 ? Family.PASCAL : (name.contains("GTX") && (model / 100 == 9 || model / 100 == 7) ? Family.MAXWELL : (name.contains("GTX") && model / 100 == 6 ? Family.KEPLER : Family.NVIDIA_UNKNOWN)));
        }
        boolean dp4a = family != Family.KEPLER;
        return new GpuProfile(Vendor.NVIDIA, family, original, tensor, rt, dp4a, ngx, false);
    }

    private static GpuProfile amd(String name, String original) {
        String model = name;
        Matcher matcher = AMD_MODEL.matcher(model);
        Family family = Family.AMD_UNKNOWN;
        boolean rt = false;
        if (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (number >= 9000) {
                family = Family.RDNA4;
                rt = true;
            } else if (number >= 7000) {
                family = Family.RDNA3;
                rt = true;
            } else if (number >= 6000) {
                family = Family.RDNA2;
                rt = true;
            } else {
                family = number >= 5000 ? Family.RDNA1 : Family.GCN;
            }
        } else if (name.contains("VEGA")) {
            family = Family.GCN;
        }
        boolean integrated = !matcher.find() && (name.contains("GRAPHICS") || name.contains("APU") || name.contains("VEGA"));
        return new GpuProfile(Vendor.AMD, family, original, false, rt, true, false, integrated);
    }

    private static GpuProfile intel(String name, String original) {
        boolean integrated;
        Family family;
        if (name.contains("BATTLEMAGE") || name.contains("ARC B") || name.contains("140V") || name.contains("130V")) {
            family = Family.XE2;
            integrated = name.contains("140V") || name.contains("130V");
        } else if (name.contains("ARC")) {
            family = Family.XE_HPG;
            integrated = false;
        } else if (name.contains("IRIS") || name.contains("UHD") || name.contains("HD GRAPHICS")) {
            family = Family.XE_LP;
            integrated = true;
        } else {
            family = Family.INTEL_UNKNOWN;
            integrated = true;
        }
        boolean xmx = family == Family.XE2 || family == Family.XE_HPG;
        return new GpuProfile(Vendor.INTEL, family, original, xmx, xmx, true, false, integrated);
    }

    public boolean supportsDlssSr() {
        return this.vendor == Vendor.NVIDIA && this.tensorCores && this.ngxPlatform;
    }

    public boolean supportsDlssFg() {
        return this.vendor == Vendor.NVIDIA && this.ngxPlatform && (this.family == Family.ADA || this.family == Family.BLACKWELL);
    }

    public boolean supportsDlssMfg() {
        return this.vendor == Vendor.NVIDIA && this.ngxPlatform && this.family == Family.BLACKWELL;
    }

    public boolean supportsFsr2Family() {
        return this.dp4a || this.vendor == Vendor.INTEL || this.vendor == Vendor.AMD;
    }

    public boolean supportsFsr4() {
        return this.vendor == Vendor.AMD && this.family == Family.RDNA4;
    }

    public boolean supportsFsr4Experimental() {
        return this.vendor == Vendor.AMD && this.family == Family.RDNA3;
    }

    public boolean supportsXessXmx() {
        return this.vendor == Vendor.INTEL && (this.family == Family.XE2 || this.family == Family.XE_HPG);
    }

    public boolean supportsXessDp4a() {
        return this.dp4a;
    }

    public String describe() {
        return this.marketingName + " (" + String.valueOf((Object)this.vendor) + " " + String.valueOf((Object)this.family) + (this.integrated ? ", integrated" : "") + ")";
    }

    public static enum Vendor {
        NVIDIA,
        AMD,
        INTEL,
        APPLE,
        QUALCOMM,
        OTHER;

    }

    public static enum Family {
        BLACKWELL,
        ADA,
        AMPERE,
        TURING,
        PASCAL,
        MAXWELL,
        KEPLER,
        NVIDIA_UNKNOWN,
        RDNA4,
        RDNA3,
        RDNA2,
        RDNA1,
        GCN,
        AMD_UNKNOWN,
        XE2,
        XE_HPG,
        XE_LP,
        INTEL_UNKNOWN,
        APPLE_SILICON,
        OTHER;

    }
}
