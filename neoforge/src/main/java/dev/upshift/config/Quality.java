package dev.upshift.config;

public enum Quality {
    NATIVE("native", 1.0f),
    ULTRA_QUALITY("ultra_quality", 0.77f),
    QUALITY("quality", 0.67f),
    BALANCED("balanced", 0.59f),
    PERFORMANCE("performance", 0.5f),
    ULTRA_PERFORMANCE("ultra_performance", 0.3333f),
    CUSTOM("custom", 0.75f);

    private final String id;
    private final float scale;

    private Quality(String id, float scale) {
        this.id = id;
        this.scale = scale;
    }

    public String id() {
        return this.id;
    }

    public float scale() {
        return this.scale;
    }

    public static Quality byId(String id) {
        for (Quality value : Quality.values()) {
            if (!value.id.equals(id)) continue;
            return value;
        }
        return QUALITY;
    }
}
