#version 330

// Temporal resolve: the current jittered frame blended with the previous one, warped to where
// the camera pointed a frame ago. History is clamped to the local neighbourhood of the current
// frame before it is allowed in, which is what stops ghosting behind moving geometry. Cost is
// five taps of the current frame and one of the history, all at internal resolution.

uniform sampler2D ColorSampler;
uniform sampler2D HistorySampler;

layout(std140) uniform TaauParams {
    mat4 HistoryWarp;   // current clip space -> previous clip space
    vec4 Params;        // x: weight of the current frame, w: 1 when a previous frame exists
};

in vec2 texCoord;
out vec4 fragColor;

vec2 toUv(vec2 ndc) {
    return vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
}

void main() {
    vec2 uv = texCoord;
    vec3 current = texture(ColorSampler, uv).rgb;
    if (Params.w < 0.5) {
        fragColor = vec4(current, 1.0);
        return;
    }

    vec2 ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    vec4 warped = HistoryWarp * vec4(ndc, 0.0, 1.0);
    if (abs(warped.w) < 1.0e-6) {
        fragColor = vec4(current, 1.0);
        return;
    }
    vec2 prevUv = toUv(warped.xy / warped.w);
    if (any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)))) {
        // The camera turned far enough that this pixel had no history: take the frame as it is.
        fragColor = vec4(current, 1.0);
        return;
    }

    vec2 texel = 1.0 / vec2(textureSize(ColorSampler, 0));

    // Plus-shaped neighbourhood at one and a half pixels: wide enough to cover the
    // reprojection error of a rotation-only warp, tight enough to keep thin edges sharp.
    vec3 mn = current;
    vec3 mx = current;
    for (int i = 0; i < 4; i++) {
        vec2 offset = vec2(i == 0 ? 1.0 : (i == 1 ? -1.0 : 0.0),
                           i == 2 ? 1.0 : (i == 3 ? -1.0 : 0.0));
        vec3 sampleColour = texture(ColorSampler, uv + offset * texel * 1.5).rgb;
        mn = min(mn, sampleColour);
        mx = max(mx, sampleColour);
    }

    vec3 history = clamp(texture(HistorySampler, prevUv).rgb, mn, mx);

    // The faster the view moves, the less the previous frame is worth: at rest the history
    // carries about seven eighths of the pixel, under a fast turn barely half of it.
    vec2 motion = abs(prevUv - uv) * vec2(textureSize(ColorSampler, 0));
    float speed = clamp((motion.x + motion.y) * 0.15, 0.0, 1.0);
    float weight = mix(Params.x, 0.55, speed);

    fragColor = vec4(clamp(mix(history, current, weight), vec3(0.0), vec3(1.0)), 1.0);
}
