package dev.upshift.vr.render;

/**
 * Parameters for foveated rendering (future implementation).
 * <p>
 * In VR, the optical center of each lens has highest acuity.
 * Foveated rendering reduces resolution/shading in the periphery.
 * This is a stub for future implementation - stores parameters only.
 */
public final class FoveationParams {
    public boolean enabled = false;
    public float focalRadiusDegrees = 15.0f; // Degrees from center with full resolution
    public float transitionWidthDegrees = 10.0f; // Transition zone width
    public float minPeripheralScale = 0.3f; // Minimum scale at edge (0.0-1.0)
    public int layerCount = 3; // Number of concentric resolution layers
    public boolean useVariableRateShading = false; // VRS if supported
    public boolean useGeometryReduction = false; // Reduce triangle density in periphery

    // Per-eye foveation center (in NDC, typically slightly nasal)
    public float leftFoveationX = 0.0f;
    public float leftFoveationY = 0.0f;
    public float rightFoveationX = 0.0f;
    public float rightFoveationY = 0.0f;

    // Runtime-calculated scale factors per layer
    public float[] layerScales;
    public float[] layerRadii;

    public void computeLayers() {
        if (this.layerScales == null || this.layerScales.length != this.layerCount) {
            this.layerScales = new float[this.layerCount];
            this.layerRadii = new float[this.layerCount];
        }
        for (int i = 0; i < this.layerCount; i++) {
            float t = (float) i / (float) Math.max(1, this.layerCount - 1);
            this.layerRadii[i] = this.focalRadiusDegrees + t * this.transitionWidthDegrees;
            this.layerScales[i] = 1.0f - t * (1.0f - this.minPeripheralScale);
        }
    }

    /**
     * Returns the render scale for a given angle from foveation center (in degrees).
     */
    public float getScaleAtAngle(float angleDegrees) {
        if (angleDegrees <= this.focalRadiusDegrees) return 1.0f;
        if (angleDegrees >= this.focalRadiusDegrees + this.transitionWidthDegrees) return this.minPeripheralScale;
        float t = (angleDegrees - this.focalRadiusDegrees) / this.transitionWidthDegrees;
        return 1.0f - t * (1.0f - this.minPeripheralScale);
    }
}