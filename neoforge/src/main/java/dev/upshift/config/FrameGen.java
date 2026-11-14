package dev.upshift.config;

public enum FrameGen {
    OFF("off", "none"),
    INTERNAL("internal", "built-in interpolation between rendered frames"),
    FSR_FG("fsr_fg", "FidelityFX FrameInterpolation (FidelityFX SDK)"),
    DLSS_FG("dlss_fg", "NGX DLSS-G / DLSS multi frame generation"),
    XESS_FG("xess_fg", "XeSS-FG (XeFG 2.x)");

    private final String id;
    private final String requirement;

    private FrameGen(String id, String requirement) {
        this.id = id;
        this.requirement = requirement;
    }

    public String id() {
        return this.id;
    }

    public String requirement() {
        return this.requirement;
    }

    public static FrameGen byId(String id) {
        for (FrameGen value : FrameGen.values()) {
            if (!value.id.equals(id)) continue;
            return value;
        }
        return OFF;
    }
}
