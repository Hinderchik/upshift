#version 330

// VR Frame Generation Reprojection Shader
// Warps previous frame to predicted HMD pose, with HUD mask and hand layer slots.

uniform sampler2D InSampler;      // Previous warped world frame
uniform sampler2D GuiSampler;     // Current frame (for HUD)
uniform sampler2D WorldSampler;   // Current world frame (for edge fallback)

layout(std140) uniform WarpParams {
    mat4 WarpMatrix;              // Current clip space -> previous clip space
    vec4 Params;                  // x: warp strength, y: hand layer alpha, z: unused, w: hasHistory
};

in vec2 texCoord;
out vec4 fragColor;

vec2 toUv(vec2 ndc) {
    return vec2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
}

void main() {
    vec2 uv = texCoord;

    // Sample current frame for HUD mask
    vec4 current = texture(GuiSampler, uv);
    vec3 currentRgb = current.rgb;

    // If no history, just show current frame
    if (Params.w < 0.5) {
        fragColor = current;
        return;
    }

    // Warp current UV to previous frame space
    vec2 ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    vec4 warped = WarpMatrix * vec4(ndc, 0.0, 1.0);
    if (abs(warped.w) < 1.0e-6) {
        fragColor = current;
        return;
    }
    vec2 prevUv = toUv(warped.xy / warped.w);

    // Check bounds
    if (any(lessThan(prevUv, vec2(0.0))) || any(greaterThan(prevUv, vec2(1.0)))) {
        // Out of bounds - use current world (not HUD)
        fragColor = vec4(texture(WorldSampler, uv).rgb, 1.0);
        return;
    }

    // Sample warped previous frame
    vec3 warpedWorld = texture(InSampler, prevUv).rgb;

    // HUD mask: where current frame differs significantly from world, keep current (HUD)
    vec3 worldCurrent = texture(WorldSampler, uv).rgb;
    vec3 diff = abs(currentRgb - worldCurrent);
    float hudMask = step(0.05, diff.r + diff.g + diff.b);

    // Blend: use warped world where no HUD, current where HUD
    vec3 result = mix(warpedWorld, currentRgb, hudMask);

    // Edge fallback: if warped sample is near edge, blend with current world
    float edgeDist = min(min(prevUv.x, 1.0 - prevUv.x), min(prevUv.y, 1.0 - prevUv.y));
    float edgeBlend = smoothstep(0.0, 0.02, edgeDist);
    result = mix(worldCurrent, result, edgeBlend);

    fragColor = vec4(result, 1.0);
}