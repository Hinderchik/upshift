#version 330

// Fast upscale: hardware bilinear for the resize, then one contrast-adaptive sharpening
// kernel in the same pass. One fullscreen draw, five texture taps, no intermediate texture.
// Softer than the EASU path at high scale factors, but a fraction of the cost.

uniform sampler2D InSampler;

layout(std140) uniform FastParams {
    vec4 Params;   // x: sharpening weight, yz: 1/internal width, 1/internal height
};

in vec2 texCoord;
out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 texel = Params.yz;
    vec3 c = texture(InSampler, texCoord).rgb;
    if (Params.x <= 0.0) {
        // Sharpening off: one fetch, pure bilinear. The cheapest an upscaler can be.
        fragColor = vec4(c, 1.0);
        return;
    }
    vec3 n = texture(InSampler, texCoord + vec2(0.0, -texel.y)).rgb;
    vec3 s = texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb;
    vec3 w = texture(InSampler, texCoord + vec2(-texel.x, 0.0)).rgb;
    vec3 e = texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb;

    float lc = luma(c);
    float mn = min(min(min(luma(n), luma(s)), min(luma(w), luma(e))), lc);
    float mx = max(max(max(luma(n), luma(s)), max(luma(w), luma(e))), lc);

    // Same idea as RCAS: throttle the kernel where contrast is already at the extremes, so
    // ringing does not appear on flat colour or on hard edges.
    float amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, 1.0e-5), 0.0, 1.0));
    float weight = amp * -Params.x;
    vec3 sharp = (n + s + w + e) * weight + c;
    sharp /= (1.0 + 4.0 * weight);

    fragColor = vec4(clamp(sharp, vec3(0.0), vec3(1.0)), 1.0);
}
