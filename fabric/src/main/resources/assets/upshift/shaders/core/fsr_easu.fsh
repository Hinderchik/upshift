#version 330

// FSR 1 EASU (Efficient Accelerated Super-resolution Upscaler) - fragment pass.
// Ported from the FidelityFX FSR 1 reference (MIT, Advanced Micro Devices, Inc.).
// The reference relies on textureGather; this port taps the same 12 texels with
// plain fetches at texel centres, which is bit-identical and works on GL 3.3.

uniform sampler2D InSampler;

layout(std140) uniform FsrParams {
    vec4 con0; // 1/scale, offset
    vec4 con1; // 1/inputSize, first gather offset
    vec4 con2;
    vec4 con3;
};

in vec2 texCoord;
out vec4 fragColor;

vec3 tap(vec2 base, vec2 off) {
    return texture(InSampler, (base + off + vec2(0.5)) * con1.xy).rgb;
}

float luma(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void easuSet(inout vec2 dir, inout float len, vec2 pp,
             float lA, float lB, float lC, float lD, float lE, float w) {
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(abs(dc), abs(cb));
    lenX = 1.0 / lenX;
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;

    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(abs(ec), abs(ca));
    lenY = 1.0 / lenY;
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len,
             float lob, float clp, vec3 c) {
    vec2 v;
    v.x = off.x * dir.x + off.y * dir.y;
    v.y = off.x * (-dir.y) + off.y * dir.x;
    v *= len;
    float d2 = v.x * v.x + v.y * v.y;
    d2 = min(d2, clp);
    // Lanczos-2 approximation with a movable negative lobe.
    float wB = (2.0 / 5.0) * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void main() {
    vec2 pp = gl_FragCoord.xy * con0.xy + con0.zw;
    vec2 fp = floor(pp);
    pp -= fp;

    vec3 b = tap(fp, vec2( 0.0, -1.0));
    vec3 c = tap(fp, vec2( 1.0, -1.0));
    vec3 e = tap(fp, vec2(-1.0,  0.0));
    vec3 f = tap(fp, vec2( 0.0,  0.0));
    vec3 g = tap(fp, vec2( 1.0,  0.0));
    vec3 h = tap(fp, vec2( 2.0,  0.0));
    vec3 i = tap(fp, vec2(-1.0,  1.0));
    vec3 j = tap(fp, vec2( 0.0,  1.0));
    vec3 k = tap(fp, vec2( 1.0,  1.0));
    vec3 l = tap(fp, vec2( 2.0,  1.0));
    vec3 n = tap(fp, vec2( 0.0,  2.0));
    vec3 o = tap(fp, vec2( 1.0,  2.0));

    float bL = luma(b), cL = luma(c), eL = luma(e), fL = luma(f), gL = luma(g);
    float hL = luma(h), iL = luma(i), jL = luma(j), kL = luma(k), lL = luma(l);
    float nL = luma(n), oL = luma(o);

    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, pp, bL, eL, fL, gL, jL, (1.0 - pp.x) * (1.0 - pp.y));
    easuSet(dir, len, pp, cL, fL, gL, hL, kL,           pp.x  * (1.0 - pp.y));
    easuSet(dir, len, pp, fL, iL, jL, kL, nL, (1.0 - pp.x) *           pp.y );
    easuSet(dir, len, pp, gL, jL, kL, lL, oL,           pp.x  *           pp.y );

    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < (1.0 / 32768.0);
    dirR = zro ? 1.0 : inversesqrt(dirR);
    dir = zro ? vec2(1.0, 0.0) : dir * dirR;

    len *= 0.5;
    len *= len;

    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;

    vec3 min4 = min(min(min(i, k), j), l);
    vec3 max4 = max(max(max(i, k), j), l);

    vec3 aC = vec3(0.0);
    float aW = 0.0;
    easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);
    easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);
    easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);
    easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);
    easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);
    easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);
    easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);
    easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);
    easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);
    easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);
    easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, o);
    easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);

    fragColor = vec4(min(max4, max(min4, aC / aW)), 1.0);
}
