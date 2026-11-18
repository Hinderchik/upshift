package dev.upshift.render;

import org.joml.Quaternionf;

/**
 * Small, dependency-free helpers used by the frame generator. Kept separate from
 * {@link FrameGenPresenter} so the maths can be unit tested without booting the
 * game or touching GPU state.
 */
public final class FrameGenMath {
    private FrameGenMath() {
    }

    /**
     * Angle of a quaternion in radians, folded into the 0..PI range.
     */
    public static float stepAngle(Quaternionf quaternion) {
        float w = Math.min(1.0f, Math.abs(quaternion.w));
        return 2.0f * (float)Math.acos(w);
    }
}
