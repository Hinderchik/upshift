#version 330

// FSR 1 RCAS (Robust Contrast Adaptive Sharpening) - fragment pass.
// Ported from the FidelityFX FSR 1 reference (MIT, Advanced Micro Devices, Inc.).

uniform sampler2D InSampler;

layout(std140) uniform RcasParams {
    vec4 con; // con.x == exp2(-sharpness)
};

in vec2 texCoord;
out vec4 fragColor;

const float RCAS_LIMIT = 0.25 - (1.0 / 16.0);

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
    float lobe = max(-RCAS_LIMIT, min(max(max(lobeRGB.r, lobeRGB.g), lobeRGB.b), 0.0)) * con.x;

    lobe *= nz;

    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    vec3 res = (lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL;

    fragColor = vec4(res, 1.0);
}
