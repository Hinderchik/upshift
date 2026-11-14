#version 330

// Hand/Controller Layer Composite Shader
// Composites hand/controller render at native rate over warped frame.

uniform sampler2D HandSampler;    // Hand layer texture (with alpha)

layout(std140) uniform HandParams {
    vec4 Params;                  // x: hand opacity, y: eye index, z: unused, w: hasHand
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    // This shader is used in a separate pass to blend hands over the warped output
    // The base warped frame is already in the render target
    if (Params.w < 0.5) {
        discard; // No hand layer
    }

    vec4 hand = texture(HandSampler, texCoord);
    if (hand.a < 0.01) {
        discard;
    }

    // Premultiplied alpha blend over existing frame
    fragColor = vec4(hand.rgb * hand.a * Params.x, hand.a * Params.x);
}