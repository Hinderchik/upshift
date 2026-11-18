#version 330

// Interpolation pass for the built-in frame generator.
//
// When motion vectors are available they warp the previous frame per pixel,
// which handles both camera and object motion. Otherwise the pass falls back to
// the camera-rotation homography, which needs no depth buffer.
//
// The HUD is not part of InSampler. It is taken from the finished current frame
// and kept un-warped; comparing that frame with the current world-only capture
// isolates the HUD pixels.

uniform sampler2D InSampler;      // previous frame, world only (warp source)
uniform sampler2D GuiSampler;     // current frame, world + HUD
uniform sampler2D WorldSampler;   // current frame, world only (HUD mask)
uniform sampler2D MotionSampler;  // motion vectors in UV space (RGBA16_FLOAT, alpha = valid)

layout(std140) uniform WarpParams {
    mat4 InvWarp;   // current clip -> previous clip, rotation-only fallback
    vec4 Params;    // x: use motion vectors, y: interpolation fraction (0..1)
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 hudFrame = texture(GuiSampler, texCoord).rgb;
    vec3 worldNow = texture(WorldSampler, texCoord).rgb;

    vec2 uv;
    if (Params.x > 0.5) {
        vec4 motionSample = texture(MotionSampler, texCoord);
        // At fraction 0 the generated frame is the previous frame, at 1 the
        // current frame. Sample the previous image along the path from its
        // position to the current one.
        vec2 motionUv = texCoord + motionSample.rg * Params.y;

        vec2 ndc = vec2(texCoord.x * 2.0 - 1.0, 1.0 - texCoord.y * 2.0);
        vec4 warped = InvWarp * vec4(ndc, 0.0, 1.0);
        vec2 src = warped.xy / warped.w;
        vec2 rotationUv = vec2(src.x * 0.5 + 0.5, 0.5 - src.y * 0.5);

        // Alpha marks pixels with a usable depth-based vector. Sky and other
        // depth holes keep the rotation-only motion so they still move.
        uv = mix(rotationUv, motionUv, motionSample.a);
    } else {
        vec2 ndc = vec2(texCoord.x * 2.0 - 1.0, 1.0 - texCoord.y * 2.0);
        vec4 warped = InvWarp * vec4(ndc, 0.0, 1.0);
        vec2 src = warped.xy / warped.w;
        uv = vec2(src.x * 0.5 + 0.5, 0.5 - src.y * 0.5);
    }

    // Motion can reveal pixels the previous frame never contained. Fill those
    // with the current world at the same screen position instead of stretching
    // the border into long streaks.
    vec2 outside = max(-uv, uv - vec2(1.0));
    float uncovered = smoothstep(0.0, 0.012, max(outside.x, outside.y));
    vec3 world = mix(texture(InSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb, worldNow, uncovered);

    // Pixels that differ between the finished frame and the world-only capture are the HUD.
    // They are copied from the finished frame so the crosshair, hotbar and chat never move.
    float diff = max(max(abs(hudFrame.r - worldNow.r), abs(hudFrame.g - worldNow.g)), abs(hudFrame.b - worldNow.b));
    float hud = smoothstep(8.0 / 255.0, 16.0 / 255.0, diff);

    fragColor = vec4(mix(world, hudFrame, hud), 1.0);
}
