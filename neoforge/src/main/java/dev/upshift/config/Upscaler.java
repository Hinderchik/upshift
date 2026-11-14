package dev.upshift.config;

public enum Upscaler {
    OFF("off", false, false),
    FSR1("fsr1", false, false),
    NIS("nis", false, false),
    TAAU("taau", false, true),
    SHARPENING("sharpening", false, false),
    FSR2("fsr2", true, true),
    FSR31("fsr31", true, true),
    FSR4("fsr4", true, true),
    XESS("xess", true, true),
    DLSS("dlss", true, true);

    private final String id;
    private final boolean needsVulkan;
    private final boolean temporal;

    private Upscaler(String id, boolean needsVulkan, boolean temporal) {
        this.id = id;
        this.needsVulkan = needsVulkan;
        this.temporal = temporal;
    }

    public String id() {
        return this.id;
    }

    public boolean needsVulkan() {
        return this.needsVulkan;
    }

    public boolean temporal() {
        return this.temporal;
    }

    public boolean isSpatial() {
        return this == FSR1 || this == NIS || this == TAAU || this == SHARPENING;
    }

    public boolean isImplementedHere() {
        return this == FSR1 || this == NIS || this == TAAU || this == SHARPENING;
    }

    public static Upscaler byId(String id) {
        for (Upscaler value : Upscaler.values()) {
            if (!value.id.equals(id)) continue;
            return value;
        }
        return OFF;
    }
}
