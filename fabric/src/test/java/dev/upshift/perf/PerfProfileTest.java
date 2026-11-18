package dev.upshift.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PerfProfileTest {

    @Test
    void allProfilesHaveUniqueIds() {
        PerfProfile[] profiles = PerfProfile.values();
        for (int i = 0; i < profiles.length; i++) {
            for (int j = i + 1; j < profiles.length; j++) {
                assertNotEquals(profiles[i].id(), profiles[j].id());
            }
        }
    }

    @Test
    void eachProfileHasValidScale() {
        for (PerfProfile profile : PerfProfile.values()) {
            float scale = profile.scale();
            assertTrue(scale > 0.0f && scale <= 1.0f, 
                "Profile " + profile.id() + " has invalid scale: " + scale);
        }
    }

    @Test
    void balancedProfileHasExpectedScale() {
        // Balanced profile should be around 67% (0.67f)
        assertEquals(0.67f, PerfProfile.BALANCED.scale(), 0.01f);
    }

    @Test
    void cpuFirstProfileHasHigherScale() {
        // CPU-first should use higher internal resolution (less upscaling)
        assertTrue(PerfProfile.CPU_FIRST.scale() > PerfProfile.BALANCED.scale());
    }

    @Test
    void gpuFirstProfileHasLowerScale() {
        // GPU-first should use lower internal resolution (more upscaling)
        assertTrue(PerfProfile.GPU_FIRST.scale() < PerfProfile.BALANCED.scale());
    }

    @Test
    void integratedProfileHasLowestScale() {
        // Integrated graphics should use the lowest scale
        assertTrue(PerfProfile.INTEGRATED.scale() < PerfProfile.GPU_FIRST.scale());
    }
}