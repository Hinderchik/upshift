package dev.upshift.render;

import org.junit.jupiter.api.Test;
import org.joml.Quaternionf;

import static org.junit.jupiter.api.Assertions.*;

class FrameGenPresenterTest {

    @Test
    void stepAngle_zeroRotation_returnsZero() {
        Quaternionf q = new Quaternionf();
        float angle = FrameGenMath.stepAngle(q);
        assertEquals(0.0f, angle, 1e-6f);
    }

    @Test
    void stepAngle_smallRotation_returnsSmallAngle() {
        Quaternionf q = new Quaternionf().rotateX(0.01f); // ~0.57 degrees
        float angle = FrameGenMath.stepAngle(q);
        assertTrue(angle > 0.0f && angle < 0.02f);
    }

    @Test
    void stepAngle_largeRotation_returnsLargeAngle() {
        Quaternionf q = new Quaternionf().rotateY((float)Math.PI * 0.25f); // 45 degrees
        float angle = FrameGenMath.stepAngle(q);
        assertTrue(angle > 0.7f && angle < 0.8f);
    }

    @Test
    void stepAngle_inverseRotation_sameAsForward() {
        Quaternionf q = new Quaternionf().rotateZ(0.5f);
        Quaternionf qInv = new Quaternionf(q).conjugate();
        float angle1 = FrameGenMath.stepAngle(q);
        float angle2 = FrameGenMath.stepAngle(qInv);
        assertEquals(angle1, angle2, 1e-6f);
    }

    @Test
    void stepAngle_negativeW_handledCorrectly() {
        // Quaternion with negative w should give same angle as positive w
        Quaternionf q1 = new Quaternionf(0.1f, 0.2f, 0.3f, 0.9f);
        Quaternionf q2 = new Quaternionf(-0.1f, -0.2f, -0.3f, -0.9f);
        float angle1 = FrameGenMath.stepAngle(q1);
        float angle2 = FrameGenMath.stepAngle(q2);
        assertEquals(angle1, angle2, 1e-6f);
    }

    @Test
    void stepAngle_identity_quaternion_isZero() {
        Quaternionf q = new Quaternionf(0.0f, 0.0f, 0.0f, 1.0f);
        assertEquals(0.0f, FrameGenMath.stepAngle(q), 1e-6f);
    }

    @Test
    void stepAngle_fullTurn_foldsToZero() {
        // A quaternion with w = -1 still represents the identity rotation.
        Quaternionf q = new Quaternionf(0.0f, 0.0f, 0.0f, -1.0f);
        assertEquals(0.0f, FrameGenMath.stepAngle(q), 1e-6f);
    }
}
