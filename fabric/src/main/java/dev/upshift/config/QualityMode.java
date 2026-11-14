package dev.upshift.config;

import dev.upshift.config.Upscaler;
import java.util.List;

public enum QualityMode {
    ULTRA_PERFORMANCE("ultra_performance", 0.33333334f),
    PERFORMANCE("performance", 0.5f),
    BALANCED("balanced", 0.58823526f),
    QUALITY("quality", 0.6666667f),
    ULTRA_QUALITY("ultra_quality", 0.7692308f),
    NATIVE("native", 1.0f);

    private final String id;
    private final float scale;

    private QualityMode(String id, float scale) {
        this.id = id;
        this.scale = scale;
    }

    public String id() {
        return this.id;
    }

    public float scale() {
        return this.scale;
    }

    public String label() {
        return switch (this.ordinal()) {
            default -> throw new AssertionError("unreachable");
            case 0 -> "Ultra performance";
            case 1 -> "Performance";
            case 2 -> "Balanced";
            case 3 -> "Quality";
            case 4 -> "Ultra quality";
            case 5 -> "Native (no upscale)";
        };
    }

    public static QualityMode nearest(float scale) {
        QualityMode best = QUALITY;
        float bestDelta = Float.MAX_VALUE;
        for (QualityMode mode : QualityMode.values()) {
            float delta = Math.abs(mode.scale - scale);
            if (!(delta < bestDelta)) continue;
            bestDelta = delta;
            best = mode;
        }
        return best;
    }

    public static QualityMode byId(String id) {
        for (QualityMode mode : QualityMode.values()) {
            if (!mode.id.equals(id)) continue;
            return mode;
        }
        return QUALITY;
    }

    public static List<QualityMode> forUpscaler(Upscaler upscaler) {
        return switch (upscaler) {
            default -> throw new AssertionError("unreachable");
            case Upscaler.FSR1, Upscaler.NIS -> List.of(ULTRA_QUALITY, QUALITY, BALANCED, PERFORMANCE, NATIVE);
            case Upscaler.DLSS, Upscaler.XESS, Upscaler.FSR2, Upscaler.FSR31, Upscaler.FSR4, Upscaler.TAAU -> List.of(ULTRA_PERFORMANCE, PERFORMANCE, BALANCED, QUALITY, NATIVE);
            case Upscaler.OFF -> List.of(QUALITY, BALANCED, PERFORMANCE, NATIVE);
        };
    }
}
