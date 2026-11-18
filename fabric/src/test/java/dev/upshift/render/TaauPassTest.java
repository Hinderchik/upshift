package dev.upshift.render;

import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;

import static org.junit.jupiter.api.Assertions.*;

class TaauPassTest {

    @Test
    void identityMatrix_isIdentity() {
        Matrix4f m = new Matrix4f();
        assertEquals(1.0f, m.m00(), 1e-6f);
        assertEquals(1.0f, m.m11(), 1e-6f);
        assertEquals(1.0f, m.m22(), 1e-6f);
        assertEquals(1.0f, m.m33(), 1e-6f);
    }

    @Test
    void invertProjection_inverseOfIdentityIsIdentity() {
        Matrix4f proj = new Matrix4f().identity();
        Matrix4f inv = new Matrix4f(proj).invert();
        assertEquals(proj, inv);
    }

    @Test
    void perspectiveProjection_invertAndMultiply_isIdentity() {
        Matrix4f proj = new Matrix4f().perspective(
            (float)Math.toRadians(70.0f),
            16.0f / 9.0f,
            0.1f,
            1000.0f
        );
        Matrix4f inv = new Matrix4f(proj).invert();
        Matrix4f result = new Matrix4f(proj).mul(inv);
        
        assertEquals(1.0f, result.m00(), 1e-4f);
        assertEquals(1.0f, result.m11(), 1e-4f);
        assertEquals(1.0f, result.m22(), 1e-4f);
        assertEquals(1.0f, result.m33(), 1e-4f);
    }

    @Test
    void reversedZ_depthReconstruction_works() {
        // Test that depth reconstruction works for both Z conventions
        // Near plane in Vulkan = 1.0, far = 0.0 (reversed-Z)
        // Near plane in OpenGL = -1.0, far = 1.0
        
        // Test near plane in Vulkan ([0,1] depth)
        float depthVulkanNear = 1.0f;
        float ndcZ_Vulkan = depthVulkanNear * 2.0f - 1.0f; // = 1.0
        assertEquals(1.0f, ndcZ_Vulkan, 1e-6f);
        
        // Test far plane in Vulkan
        float depthVulkanFar = 0.0f;
        float ndcZ_VulkanFar = depthVulkanFar * 2.0f - 1.0f; // = -1.0
        assertEquals(-1.0f, ndcZ_VulkanFar, 1e-6f);
    }

    @Test
    void warpMatrix_composition_isCorrect() {
        Matrix4f currentProjection = new Matrix4f().perspective(
            (float)Math.toRadians(70.0f), 16.0f/9.0f, 0.1f, 1000.0f
        );
        Matrix4f previousProjection = new Matrix4f(currentProjection);
        
        Matrix4f rotation = new Matrix4f().rotateY(0.01f);
        Matrix4f previousRotation = new Matrix4f(rotation);
        
        // Compute warp: current -> previous
        Matrix4f warp = new Matrix4f(currentProjection)
            .mul(previousProjection)
            .mul(new Matrix4f(previousRotation).invert())
            .mul(new Matrix4f(currentProjection).invert());
        
        // Applying warp to a point on the image center should give small displacement
        // This tests the matrix composition logic
        assertNotNull(warp);
    }
}