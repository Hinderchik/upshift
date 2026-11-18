#version 330

// Motion vector generation from depth buffer
// Computes per-pixel motion vectors by reconstructing world positions
// from current and previous frame projections.

uniform sampler2D DepthSampler;

layout(std140) uniform VelocityParams {
    mat4 CurrentProjection;
    mat4 PreviousProjection;
    mat4 CurrentView;
    mat4 PreviousView;
    mat4 CurrentProjInv;
    mat4 PreviousProjInv;
};

in vec2 texCoord;
out vec4 fragColor;

vec2 toUv(vec2 ndc) {
    return vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
}

vec3 reconstructWorldPos(vec2 uv, float depth, mat4 projectionInv, mat4 view) {
    // Convert stored depth to NDC z
    float ndcZ = depth; // assuming [0,1] for Vulkan
    vec4 clip = vec4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, ndcZ * 2.0 - 1.0, 1.0);
    vec4 viewSpace = projectionInv * clip;
    if (viewSpace.w <= 0.0) return vec3(0.0);
    vec3 pos = viewSpace.xyz / viewSpace.w;
    // Transform from view space to world space
    return (view * vec4(pos, 1.0)).xyz;
}

float sampleDepth(vec2 uv) {
    return texture(DepthSampler, uv).r;
}

void main() {
    vec2 uv = texCoord;
    float depth = sampleDepth(uv);
    
    if (depth <= 0.0 || depth >= 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    
    // Reconstruct world positions for current and previous frame
    vec3 posCurrent = reconstructWorldPos(uv, depth, CurrentProjInv, CurrentView);
    vec3 posPrevious = reconstructWorldPos(uv, depth, PreviousProjInv, PreviousView);
    
    // If positions are invalid, output zero motion
    if (length(posCurrent) < 0.001 || length(posPrevious) < 0.001) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    
    // Project both positions to current frame's clip space
    vec4 clipCurrent = CurrentProjection * CurrentView * vec4(posCurrent, 1.0);
    vec4 clipPrevious = CurrentProjection * PreviousView * vec4(posPrevious, 1.0);
    
    if (clipCurrent.w <= 0.0 || clipPrevious.w <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    
    vec2 uvCurrent = toUv(clipCurrent.xy / clipCurrent.w);
    vec2 uvPrevious = toUv(clipPrevious.xy / clipPrevious.w);
    
    // Motion vector in UV space
    vec2 motion = uvPrevious - uvCurrent;
    
    // Clamp to reasonable range
    motion = clamp(motion, vec2(-1.0), vec2(1.0));
    
    fragColor = vec4(motion, 0.0, 1.0);
}