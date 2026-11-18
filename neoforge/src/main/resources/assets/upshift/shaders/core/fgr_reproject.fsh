#version 330

// Interpolation pass for the built-in frame generator with per-pixel motion vectors.
// Uses depth-based motion vectors to warp the previous frame accurately,
// handling both camera motion and object motion.
//
// The HUD is not part of InSampler. It is taken from the finished current frame and kept
// un-warped; comparing that frame with the current world-only capture isolates the HUD pixels.

uniform sampler2D InSampler;      // previous frame, world only (warp source)
uniform sampler2D GuiSampler;     // current frame, world + HUD
uniform sampler2D WorldSampler;   // current frame, world only (HUD mask)
uniform sampler2D MotionSampler;  // motion vectors in UV space (RG16_FLOAT)

layout(std140) uniform WarpParams {
    mat4 InvWarp;  // kept for fallback/edge cases
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 hudFrame = texture(GuiSampler, texCoord).rgb;
    vec3 worldNow = texture(WorldSampler, texCoord).rgb;
    
    // Read motion vector (stored in RG channels)
    vec2 motion = texture(MotionSampler, texCoord).rg;
    
    // Apply motion vector to get source UV in previous frame
    vec2 uv = texCoord + motion;
    
    // Rotation can reveal pixels the previous frame never contained. Fill those with the
    // current world at the same screen position instead of stretching the border into long
    // streaks; it is at most half a frame ahead and only covers a thin edge band.
    vec2 outside = max(-uv, uv - vec2(1.0));
    float uncovered = smoothstep(0.0, 0.012, max(outside.x, outside.y));
    vec3 world = mix(texture(InSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb, worldNow, uncovered);

    // Pixels that differ between the finished frame and the world-only capture are the HUD.
    // They are copied from the finished frame so the crosshair, hotbar and chat never move.
    // Higher threshold to avoid false positives from render precision differences.
    float diff = max(max(abs(hudFrame.r - worldNow.r), abs(hudFrame.g - worldNow.g)), abs(hudFrame.b - worldNow.b));
    float hud = smoothstep(8.0 / 255.0, 16.0 / 255.0, diff);

    fragColor = vec4(mix(world, hudFrame, hud), 1.0);
}
