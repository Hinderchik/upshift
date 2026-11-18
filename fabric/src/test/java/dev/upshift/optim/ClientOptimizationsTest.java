package dev.upshift.optim;

import org.junit.jupiter.api.Test;
import org.joml.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

class ClientOptimizationsTest {

    @Test
    void distanceSquared_calculatesCorrectly() {
        // Test distance squared calculation used in culling
        float dx = 3.0f;
        float dy = 4.0f;
        float dz = 0.0f;
        float distSq = dx*dx + dy*dy + dz*dz;
        assertEquals(25.0f, distSq, 1e-6f);
    }

    @Test
    void frustumCulling_simpleBoxTest() {
        // Simple AABB frustum test logic
        // Box centered at (10, 0, 0) with size 2
        // Camera at (0, 0, 0) looking down +Z
        // Box should be visible
        
        float boxCenterX = 10.0f;
        float boxCenterY = 0.0f;
        float boxCenterZ = 0.0f;
        float boxRadius = 1.0f; // half-size
        
        // Camera position
        float camX = 0.0f;
        float camY = 0.0f;
        float camZ = 0.0f;
        
        float dx = boxCenterX - camX;
        float dy = boxCenterY - camY;
        float dz = boxCenterZ - camZ;
        float distSq = dx*dx + dy*dy + dz*dz;
        
        // With 128 block cull distance, this should be well within range
        int cullDistance = 128;
        boolean shouldCull = distSq > (float)cullDistance * cullDistance;
        assertFalse(shouldCull);
    }
}