#version 330

// NVIDIA Image Scaling (NIS) v1.0.2 - scaler and sharpener, fragment port.
// Algorithm and filter tables: Copyright (c) 2022 NVIDIA CORPORATION & AFFILIATES, MIT licence,
// from NVIDIAImageScaling/NIS/NIS_Scaler.h. The reference is a compute shader that keeps a tile
// in shared memory; this port loads the same 6x6 support per output pixel into registers, which
// is the only structural difference - the filter bank, the edge detection and the sharpening
// curve are the reference values.

uniform sampler2D InSampler;

layout(std140) uniform NisParams {
    vec4 con;      // x,y: source/destination scale, z,w: source size in texels
    vec4 sharp;    // x: strength min, y: strength scale, z: strength range, w: limit min
    vec4 sharp2;   // x: limit scale, y: limit range, z: sharpen slider, w: unused
    vec4 coefScaler[128]; // 64 phases, two vec4 each
    vec4 coefUsm[128];
};

in vec2 texCoord;
out vec4 fragColor;

const float kPhaseCount = 64.0;
const float kDetectRatio = 2.0 * 1127.0 / 1024.0;
const float kDetectThres = 64.0 / 1024.0;
const float kMinContrastRatio = 2.0;
const float kMaxContrastRatio = 10.0;
const float kSharpStartY = 0.45;
const float kSharpEndY = 0.9;
const float kRatioNorm = 1.0 / (kMaxContrastRatio - kMinContrastRatio);
const float kSharpScaleY = 1.0 / (kSharpEndY - kSharpStartY);
const float kEps = 1.0;
const vec3 kLuma = vec3(0.2126, 0.7152, 0.0722);

float luma(vec3 c) {
    return dot(c, kLuma);
}

void filterTaps(int phase, out vec4 a, out vec4 b) {
    a = coefScaler[phase * 2];
    b = coefScaler[phase * 2 + 1];
}

float evalPoly6(float pxl[6], int phase) {
    vec4 a = coefScaler[phase * 2];
    vec4 b = coefScaler[phase * 2 + 1];
    float y = dot(vec4(pxl[0], pxl[1], pxl[2], pxl[3]), a) + pxl[4] * b.x + pxl[5] * b.y;

    vec4 ua = coefUsm[phase * 2];
    vec4 ub = coefUsm[phase * 2 + 1];
    float yUsm = dot(vec4(pxl[0], pxl[1], pxl[2], pxl[3]), ua) + pxl[4] * ub.x + pxl[5] * ub.y;

    // Sharpen as a function of luma: highlights are sharpened less than midtones.
    float yScale = 1.0 - clamp((y - kSharpStartY) * kSharpScaleY, 0.0, 1.0);
    float ySharpness = yScale * sharp.z + sharp.x;
    yUsm *= ySharpness;

    float yLimit = (yScale * sharp2.x + sharp.w) * y;
    yUsm = clamp(yUsm, -yLimit, yLimit);

    // Ringing control: compare the contrast on both sides of the tap phase.
    bool selector = (float(phase) <= kPhaseCount * 0.5);
    float selA = selector ? pxl[0] : pxl[3];
    float aMin = min(min(pxl[1], pxl[2]), selA);
    float aMax = max(max(pxl[1], pxl[2]), selA);
    float selB = selector ? pxl[2] : pxl[5];
    float bMin = min(min(pxl[3], pxl[4]), selB);
    float bMax = max(max(pxl[3], pxl[4]), selB);

    float aCont = aMax - aMin;
    float bCont = bMax - bMin;
    float contRatio = max(aCont, bCont) / (min(aCont, bCont) + kEps);
    float lti = 1.0 - clamp((contRatio - kMinContrastRatio) * kRatioNorm, 0.0, 1.0);
    yUsm *= lti;

    return y + yUsm;
}

vec4 edgeMap(float p[36], int i, int j) {
    float g0 = abs(p[i * 6 + j] + p[i * 6 + j + 1] + p[i * 6 + j + 2]
                 - p[(i + 2) * 6 + j] - p[(i + 2) * 6 + j + 1] - p[(i + 2) * 6 + j + 2]);
    float g45 = abs(p[(i + 1) * 6 + j] + p[i * 6 + j] + p[i * 6 + j + 1]
                   - p[(i + 2) * 6 + j + 1] - p[(i + 2) * 6 + j + 2] - p[(i + 1) * 6 + j + 2]);
    float g90 = abs(p[i * 6 + j] + p[(i + 1) * 6 + j] + p[(i + 2) * 6 + j]
                   - p[i * 6 + j + 2] - p[(i + 1) * 6 + j + 2] - p[(i + 2) * 6 + j + 2]);
    float g135 = abs(p[(i + 1) * 6 + j] + p[(i + 2) * 6 + j] + p[(i + 2) * 6 + j + 1]
                    - p[i * 6 + j + 1] - p[i * 6 + j + 2] - p[(i + 1) * 6 + j + 2]);

    float g090max = max(g0, g90);
    float g090min = min(g0, g90);
    float g45135max = max(g45, g135);
    float g45135min = min(g45, g135);

    if (g090max + g45135max == 0.0) {
        return vec4(0.0);
    }

    float e090 = min(g090max / (g090max + g45135max), 1.0);
    float e45135 = 1.0 - e090;

    bool c090 = (g090max > (g090min * kDetectRatio)) && (g090max > kDetectThres)
            && (g090max > g45135min);
    bool c45135 = (g45135max > (g45135min * kDetectRatio)) && (g45135max > kDetectThres)
            && (g45135max > g090min);
    bool cg090 = g090max == g0;
    bool cg45135 = g45135max == g45;

    float f090 = (c090 && c45135) ? e090 : 1.0;
    float f45135 = (c090 && c45135) ? e45135 : 1.0;

    float weight0 = (c090 && cg090) ? f090 : 0.0;
    float weight90 = (c090 && !cg090) ? f090 : 0.0;
    float weight45 = (c45135 && cg45135) ? f45135 : 0.0;
    float weight135 = (c45135 && !cg45135) ? f45135 : 0.0;

    return vec4(weight0, weight90, weight45, weight135);
}

float addDirFilters(float p[36], float fx, float fy, int fxInt, int fyInt, vec4 w) {
    float f = 0.0;
    if (w.x > 0.0) {
        float interp0[6];
        for (int i = 0; i < 6; ++i) {
            interp0[i] = mix(p[i * 6 + 2], p[i * 6 + 3], fx);
        }
        f += evalPoly6(interp0, fyInt) * w.x;
    }
    if (w.y > 0.0) {
        float interp90[6];
        for (int i = 0; i < 6; ++i) {
            interp90[i] = mix(p[2 * 6 + i], p[3 * 6 + i], fy);
        }
        f += evalPoly6(interp90, fxInt) * w.y;
    }
    if (w.z > 0.0) {
        float pphaseB45 = 0.5 + 0.5 * (fx - fy);
        float temp45[7];
        temp45[1] = mix(p[2 * 6 + 1], p[1 * 6 + 2], pphaseB45);
        temp45[3] = mix(p[3 * 6 + 2], p[2 * 6 + 3], pphaseB45);
        temp45[5] = mix(p[4 * 6 + 3], p[3 * 6 + 4], pphaseB45);
        {
            pphaseB45 -= 0.5;
            float a = (pphaseB45 >= 0.0) ? p[0 * 6 + 2] : p[2 * 6 + 0];
            float b = (pphaseB45 >= 0.0) ? p[1 * 6 + 3] : p[3 * 6 + 1];
            float c = (pphaseB45 >= 0.0) ? p[2 * 6 + 4] : p[4 * 6 + 2];
            float d = (pphaseB45 >= 0.0) ? p[3 * 6 + 5] : p[5 * 6 + 3];
            temp45[0] = mix(p[1 * 6 + 1], a, abs(pphaseB45));
            temp45[2] = mix(p[2 * 6 + 2], b, abs(pphaseB45));
            temp45[4] = mix(p[3 * 6 + 3], c, abs(pphaseB45));
            temp45[6] = mix(p[4 * 6 + 4], d, abs(pphaseB45));
        }
        float interp45[6];
        float pphaseP45 = fx + fy;
        if (pphaseP45 >= 1.0) {
            for (int i = 0; i < 6; i++) {
                interp45[i] = temp45[i + 1];
            }
            pphaseP45 -= 1.0;
        } else {
            for (int i = 0; i < 6; i++) {
                interp45[i] = temp45[i];
            }
        }
        f += evalPoly6(interp45, int(pphaseP45 * 64.0)) * w.z;
    }
    if (w.w > 0.0) {
        float pphaseB135 = 0.5 * (fx + fy);
        float temp135[7];
        temp135[1] = mix(p[3 * 6 + 1], p[4 * 6 + 2], pphaseB135);
        temp135[3] = mix(p[2 * 6 + 2], p[3 * 6 + 3], pphaseB135);
        temp135[5] = mix(p[1 * 6 + 3], p[2 * 6 + 4], pphaseB135);
        {
            pphaseB135 -= 0.5;
            float a = (pphaseB135 >= 0.0) ? p[5 * 6 + 2] : p[3 * 6 + 0];
            float b = (pphaseB135 >= 0.0) ? p[4 * 6 + 3] : p[2 * 6 + 1];
            float c = (pphaseB135 >= 0.0) ? p[3 * 6 + 4] : p[1 * 6 + 2];
            float d = (pphaseB135 >= 0.0) ? p[2 * 6 + 5] : p[0 * 6 + 3];
            temp135[0] = mix(p[4 * 6 + 1], a, abs(pphaseB135));
            temp135[2] = mix(p[3 * 6 + 2], b, abs(pphaseB135));
            temp135[4] = mix(p[2 * 6 + 3], c, abs(pphaseB135));
            temp135[6] = mix(p[1 * 6 + 4], d, abs(pphaseB135));
        }
        float interp135[6];
        float pphaseP135 = 1.0 + (fx - fy);
        if (pphaseP135 >= 1.0) {
            for (int i = 0; i < 6; ++i) {
                interp135[i] = temp135[i + 1];
            }
            pphaseP135 -= 1.0;
        } else {
            for (int i = 0; i < 6; ++i) {
                interp135[i] = temp135[i];
            }
        }
        f += evalPoly6(interp135, int(pphaseP135 * 64.0)) * w.w;
    }
    return f;
}

void main() {
    int srcW = int(con.z);
    int srcH = int(con.w);

    float srcX = gl_FragCoord.x * con.x - 0.5;
    float srcY = gl_FragCoord.y * con.y - 0.5;
    int px = int(floor(srcX));
    int py = int(floor(srcY));
    float fx = srcX - floor(srcX);
    float fy = srcY - floor(srcY);
    int fxInt = clamp(int(fx * 64.0), 0, 63);
    int fyInt = clamp(int(fy * 64.0), 0, 63);

    // The reference lays the tile out from floor(src) - 2, six texels each way.
    float l[36];
    for (int i = 0; i < 6; ++i) {
        for (int j = 0; j < 6; ++j) {
            ivec2 at = ivec2(clamp(px - 2 + j, 0, srcW - 1), clamp(py - 2 + i, 0, srcH - 1));
            l[i * 6 + j] = luma(texelFetch(InSampler, at, 0).rgb);
        }
    }

    vec4 edge00 = edgeMap(l, 0, 0);
    vec4 edge01 = edgeMap(l, 0, 1);
    vec4 edge10 = edgeMap(l, 1, 0);
    vec4 edge11 = edgeMap(l, 1, 1);
    vec4 w = mix(mix(edge00, edge01, fx), mix(edge10, edge11, fx), fy);

    float baseWeight = 1.0 - w.x - w.y - w.z - w.w;
    float opY = 0.0;

    // Normal (non-directional) 6-tap pass, separable in x and y.
    {
        vec4 ax = coefScaler[fxInt * 2];
        vec4 bx = coefScaler[fxInt * 2 + 1];
        float acc = 0.0;
        for (int j = 0; j < 6; ++j) {
            vec4 ay = coefScaler[fyInt * 2];
            vec4 by = coefScaler[fyInt * 2 + 1];
            float column = dot(vec4(l[0 * 6 + j], l[1 * 6 + j], l[2 * 6 + j], l[3 * 6 + j]), ay)
                    + l[4 * 6 + j] * by.x + l[5 * 6 + j] * by.y;
            float wx = (j == 0) ? ax.x : (j == 1) ? ax.y : (j == 2) ? ax.z : (j == 3) ? ax.w
                    : (j == 4) ? bx.x : bx.y;
            acc += column * wx;
        }
        opY += acc * baseWeight;
    }

    opY += addDirFilters(l, fx, fy, fxInt, fyInt, w);

    vec4 op = texture(InSampler, vec2(srcX + 0.5, srcY + 0.5) / vec2(float(srcW), float(srcH)));
    float corr = opY - luma(op.rgb);
    op.rgb += vec3(corr);
    fragColor = vec4(clamp(op.rgb, 0.0, 1.0), 1.0);
}
