#version 330

// FSR 1 RCAS (Robust Contrast Adaptive Sharpening) - fragment pass.
// Adaptive variant: sharpness is computed per-pixel from local contrast,
// providing edge-aware sharpening that doesn't oversharpen flat areas.
// Ported from the FidelityFX FSR 1 reference (MIT, Advanced Micro Devices, Inc.).

uniform sampler2D InSampler;

layout(std140) uniform RcasParams {
    vec4 con; // con.x == base strength (exp2(-sharpness)), con.y == adaptive strength multiplier
};

in vec2 texCoord;
out vec4 fragColor;

const float RCAS_LIMIT = 0.25 - (1.0 / 16.0);

float luma(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

// Compute local contrast metric for adaptive sharpness
float computeLocalContrast(vec2 uv, vec2 texel) {
    // Sample 3x3 neighbourhood for contrast
    vec3 c00 = texture(InSampler, uv + vec2(-1.0, -1.0) / texel).rgb;
    vec3 c10 = texture(InSampler, uv + vec2(0.0, -1.0) / texel).rgb;
    vec3 c20 = texture(InSampler, uv + vec2(1.0, -1.0) / texel).rgb;
    vec3 c01 = texture(InSampler, uv + vec2(-1.0, 0.0) / texel).rgb;
    vec3 c11 = texture(InSampler, uv).rgb;
    vec3 c21 = texture(InSampler, uv + vec2(1.0, 0.0) / texel).rgb;
    vec3 c02 = texture(InSampler, uv + vec2(-1.0, 1.0) / texel).rgb;
    vec3 c12 = texture(InSampler, uv + vec2(0.0, 1.0) / texel).rgb;
    vec3 c22 = texture(InSampler, uv + vec2(1.0, 1.0) / texel).rgb;

    float l00 = luma(c00), l10 = luma(c10), l20 = luma(c20);
    float l01 = luma(c01), l11 = luma(c11), l21 = luma(c21);
    float l02 = luma(c02), l12 = luma(c12), l22 = luma(c22);

    // Max-min contrast in 3x3 window
    float maxL = max(max(max(max(l00, l10), max(l20, l01)), max(l11, l21)), max(l02, max(l12, l22)));
    float minL = min(min(min(min(l00, l10), min(l20, l01)), min(l11, l21)), min(l02, min(l12, l22)));

    return maxL - minL;
}

float luma(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void main() {
    vec2 texel = vec2(textureSize(InSampler, 0));
    vec2 sp = texCoord / texel;

    vec3 b = texture(InSampler, texCoord + vec2(0.0, -1.0) / texel).rgb;
    vec3 d = texture(InSampler, texCoord + vec2(-1.0, 0.0) / texel).rgb;
    vec3 e = texture(InSampler, texCoord).rgb;
    vec3 f = texture(InSampler, texCoord + vec2(1.0, 0.0) / texel).rgb;
    vec3 h = texture(InSampler, texCoord + vec2(0.0, 1.0) / texel).rgb;

    float bL = luma(b), dL = luma(d), eL = luma(e), fL = luma(f), hL = luma(h);

    float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;
    float nzRange = max(max(max(bL, dL), max(fL, hL)), eL) - min(min(min(bL, dL), min(fL, hL)), eL);
    nz = clamp(abs(nz) / nzRange, 0.0, 1.0);
    nz = -0.5 * nz + 1.0;

    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));

    vec3 hitMin = min(mn4, e) / (4.0 * mx4);
    vec3 hitMax = (1.0 - max(mx4, e)) / (4.0 * mn4 - 4.0);
    vec3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-RCAS_LIMIT, min(max(max(lobeRGB.r, lobeRGB.g), lobeRGB.b), 0.0));

    // Adaptive sharpness: base strength * (1 + adaptive_multiplier * local_contrast)
    float localContrast = computeLocalContrast(texCoord, texel);
    float adaptiveStrength = con.x * (1.0 + con.y * localContrast * 4.0); // contrast in [0, ~0.25], scale up
    adaptiveStrength = clamp(adaptiveStrength, 0.0, 0.5); // cap to prevent over-sharpening

    lobe *= nz;
    lobe *= adaptiveStrength;

    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    vec3 res = (lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL;

    fragColor = vec4(res, 1.0);
}