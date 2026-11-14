package dev.upshift.perf;

public enum PerfProfile {
    BALANCED("balanced", 0.67f, 0.0f, true, 60, 1.0, Particles.VANILLA, "keeps vanilla options, upscales at two thirds"),
    CPU_FIRST("cpu_first", 0.75f, 0.0f, true, 60, 0.7, Particles.MINIMAL, "cuts entity and particle work, keeps the upscale to one tap"),
    GPU_FIRST("gpu_first", 0.5f, 0.2f, true, 120, 1.0, Particles.VANILLA, "renders at half resolution, sharpens on the way up, targets 120"),
    INTEGRATED("integrated", 0.45f, 0.0f, true, 45, 0.6, Particles.DECREASED, "built for iGPUs: 45% internal, one tap, fewer entities and particles");

    private final String id;
    private final float scale;
    private final float sharpness;
    private final boolean dynamicScale;
    private final int targetFps;
    private final double entityDistance;
    private final Particles particles;
    private final String note;

    private PerfProfile(String id, float scale, float sharpness, boolean dynamicScale, int targetFps, double entityDistance, Particles particles, String note) {
        this.id = id;
        this.scale = scale;
        this.sharpness = sharpness;
        this.dynamicScale = dynamicScale;
        this.targetFps = targetFps;
        this.entityDistance = entityDistance;
        this.particles = particles;
        this.note = note;
    }

    public String id() {
        return this.id;
    }

    public float scale() {
        return this.scale;
    }

    public float sharpness() {
        return this.sharpness;
    }

    public boolean dynamicScale() {
        return this.dynamicScale;
    }

    public int targetFps() {
        return this.targetFps;
    }

    public double entityDistance() {
        return this.entityDistance;
    }

    public Particles particles() {
        return this.particles;
    }

    public String note() {
        return this.note;
    }

    public static PerfProfile byId(String id) {
        for (PerfProfile value : PerfProfile.values()) {
            if (!value.id.equals(id)) continue;
            return value;
        }
        return BALANCED;
    }

    public static enum Particles {
        VANILLA,
        DECREASED,
        MINIMAL;

    }
}
