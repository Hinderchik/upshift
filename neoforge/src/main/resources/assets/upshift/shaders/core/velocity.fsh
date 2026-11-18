#version 330

// Per-pixel motion vectors from the depth buffer.
//
// The current pixel is reconstructed into world space using the current
// projection and camera transform, then moved into the previous view and
// projected with the previous projection. The resulting UV offset points from
// the current pixel to where that surface was in the previous frame.

uniform sampler2D DepthSampler;

layout(std140) uniform VelocityParams {
    mat4 CurrentProjectionInverse;
    mat4 PreviousProjection;
    mat4 CurrentRotation;
    mat4 PreviousRotation;
    vec4 CurrentCameraPos;
    vec4 PreviousCameraPos;
    vec4 Params;               // x: zZeroToOne, yzw: reserved
};

in vec2 texCoord;
out vec4 fragColor;

vec2 toUv(vec2 ndc) {
    return vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
}

vec3 reconstructWorld(vec2 uv, float depth) {
    // Vulkan uses [0,1] NDC z directly; OpenGL needs [-1,1].
    float ndcZ = mix(depth * 2.0 - 1.0, depth, Params.x);
    vec4 clip = vec4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, ndcZ, 1.0);
    vec4 view = CurrentProjectionInverse * clip;
    if (abs(view.w) < 1.0e-7) {
        return vec3(0.0);
    }
    vec3 viewPos = view.xyz / view.w;
    // Rotation matrices are orthonormal, so transpose() is the inverse.
    return transpose(mat3(CurrentRotation)) * viewPos + CurrentCameraPos.xyz;
}

void main() {
    vec2 uv = texCoord;
    float depth = texture(DepthSampler, uv).r;

    // Sky / cleared depth has no reliable motion. Alpha=0 tells the frame
    // generator to use the rotation-only fallback for this pixel.
    if (depth <= 0.0 || depth >= 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 0.0);
        return;
    }

    vec3 world = reconstructWorld(uv, depth);
    vec3 previousView = mat3(PreviousRotation) * (world - PreviousCameraPos.xyz);
    vec4 previousClip = PreviousProjection * vec4(previousView, 1.0);

    if (previousClip.w <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 0.0);
        return;
    }

    vec2 previousUv = toUv(previousClip.xy / previousClip.w);
    vec2 motion = previousUv - uv;

    // Reject absurd values from depth precision noise / invalid projection.
    if (any(isnan(motion)) || any(isinf(motion))) {
        motion = vec2(0.0);
    }
    motion = clamp(motion, vec2(-1.5), vec2(1.5));

    fragColor = vec4(motion, 0.0, 1.0);
}
