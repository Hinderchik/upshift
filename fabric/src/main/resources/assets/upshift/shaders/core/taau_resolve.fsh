#version 330

// Temporal resolve with optional depth-aware reprojection.
// When depthTexture is provided, reconstruct world-space positions to disambiguate
// motion of overlapping surfaces (fixes ghosting on thin edges and particles).

uniform sampler2D ColorSampler;
uniform sampler2D HistorySampler;
uniform sampler2D DepthSampler;

layout(std140) uniform TaauParams {
    mat4 HistoryWarp;   // current clip space -> previous clip space
    vec4 Params;        // x: weight of current frame, y: depthAware flag, z: zZeroToOne, w: hasHistory
};

layout(std140) uniform ProjectionInverse {
    mat4 ProjectionInv;
};

in vec2 texCoord;
out vec4 fragColor;

vec2 toUv(vec2 ndc) {
    return vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
}

vec3 reconstructWorldPos(vec2 uv, float depth) {
    // Convert stored depth to NDC z. For Vulkan ([0,1]) use depth directly,
    // for OpenGL ([-1,1]) use depth*2-1.
    float ndcZ = mix(depth * 2.0 - 1.0, depth, Params.z);
    vec4 clip = vec4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, ndcZ, 1.0);
    vec4 view = ProjectionInv * clip;
    if (view.w <= 0.0) {
        return vec3(0.0); // behind camera or degenerate
    }
    return view.xyz / view.w;
}

float sampleDepth(vec2 uv) {
    return texture(DepthSampler, uv).r;
}

float linearDepth(vec2 uv) {
    vec3 pos = reconstructWorldPos(uv, sampleDepth(uv));
    return length(pos); // distance from camera
}

bool depthConsistent(vec2 uv, vec2 prevUv, float threshold) {
    float dHere = linearDepth(uv);
    float dThere = linearDepth(prevUv);
    // Compare in linear view-space distance; allow a small fraction of the
    // distance as tolerance (helps with reversed-Z and near-plane nonlinearity).
    return abs(dHere - dThere) < max(threshold, dHere * 0.03);
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
        fragColor = vec4(current, 1.0);
        return;
    }

    // Depth-aware ghosting rejection
    if (Params.y > 0.5) {
        // Check depth consistency between current and warped positions
        if (!depthConsistent(uv, prevUv, 0.005)) {
            fragColor = vec4(current, 1.0);
            return;
        }
    }

    vec2 texel = 1.0 / vec2(textureSize(ColorSampler, 0));

    // Plus-shaped neighbourhood at 1.5 pixels
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

    vec2 motion = abs(prevUv - uv) * vec2(textureSize(ColorSampler, 0));
    float speed = clamp((motion.x + motion.y) * 0.15, 0.0, 1.0);
    float weight = mix(Params.x, 0.55, speed);

    fragColor = vec4(clamp(mix(history, current, weight), vec3(0.0), vec3(1.0)), 1.0);
}