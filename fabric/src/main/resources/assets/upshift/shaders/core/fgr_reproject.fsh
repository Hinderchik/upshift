#version 330

// Interpolation pass for the built-in frame generator. The world of the previous frame is
// sampled with the camera rotated a fraction of the way to the current frame. Camera rotation
// is a pure homography in clip space, so no depth buffer is needed.
//
// The HUD is not part of InSampler. It is taken from the finished current frame and kept
// un-warped; comparing that frame with the current world-only capture isolates the HUD pixels.

uniform sampler2D InSampler;    // previous frame, world only (warp source)
uniform sampler2D GuiSampler;   // current frame, world + HUD
uniform sampler2D WorldSampler; // current frame, world only (HUD mask)

layout(std140) uniform WarpParams {
    mat4 InvWarp;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 hudFrame = texture(GuiSampler, texCoord).rgb;
    vec3 worldNow = texture(WorldSampler, texCoord).rgb;

    vec2 ndc = vec2(texCoord.x * 2.0 - 1.0, 1.0 - texCoord.y * 2.0);
    vec4 warped = InvWarp * vec4(ndc, 0.0, 1.0);
    vec2 src = warped.xy / warped.w;
    vec2 uv = vec2(src.x * 0.5 + 0.5, 0.5 - src.y * 0.5);

    // Rotation can reveal pixels the previous frame never contained. Fill those with the
    // current world at the same screen position instead of stretching the border into long
    // streaks; it is at most half a frame ahead and only covers a thin edge band.
    vec2 outside = max(-uv, uv - vec2(1.0));
    float uncovered = smoothstep(0.0, 0.012, max(outside.x, outside.y));
    vec3 world = mix(texture(InSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb, worldNow, uncovered);

    // Pixels that differ between the finished frame and the world-only capture are the HUD.
    // They are copied from the finished frame so the crosshair, hotbar and chat never move.
    float diff = max(max(abs(hudFrame.r - worldNow.r), abs(hudFrame.g - worldNow.g)), abs(hudFrame.b - worldNow.b));
    float hud = smoothstep(1.5 / 255.0, 4.0 / 255.0, diff);

    fragColor = vec4(mix(world, hudFrame, hud), 1.0);
}
