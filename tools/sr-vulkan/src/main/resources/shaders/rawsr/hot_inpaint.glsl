// SPDX-License-Identifier: GPL-3.0-or-later
// Hot-tap inpaint for normalized CFA planes. Masked taps take the mean of
// their unmasked finite same-colour 5x5 ring taps (even offsets preserve CFA
// phase, so every ring tap shares the center's colour; the colour check stays
// as a guard). Taps whose whole ring is masked or out of range keep their
// original sample. Mirrors RawSrHotPixel.inpaintNormalized: replacements read
// the original plane, so clustered hot taps never feed each other —
// implemented here by sampling u_cfa (never img_out) for every tap.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_cfa;
uniform highp usampler2D u_hot;
uniform ivec2 u_size;
uniform ivec4 u_fc;
layout(binding = 0, r32f) writeonly uniform highp image2D img_out;
bool finite(float x) { return !isnan(x) && !isinf(x); }
int channelOf(ivec2 t) { return u_fc[((t.y & 1) << 1) | (t.x & 1)]; }
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    float original = texelFetch(u_cfa, p, 0).r;
    if (texelFetch(u_hot, p, 0).r == 0u) {
        imageStore(img_out, p, vec4(original));
        return;
    }
    int want = channelOf(p);
    float sum = 0.0;
    int taps = 0;
    for (int oy = -2; oy <= 2; oy += 2) {
        for (int ox = -2; ox <= 2; ox += 2) {
            if (ox == 0 && oy == 0) continue;
            ivec2 t = p + ivec2(ox, oy);
            if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, u_size))) continue;
            if (channelOf(t) != want) continue;
            if (texelFetch(u_hot, t, 0).r != 0u) continue;
            float v = texelFetch(u_cfa, t, 0).r;
            if (!finite(v)) continue;
            sum += v;
            taps++;
        }
    }
    float outValue = taps > 0 ? sum / float(taps) : original;
    imageStore(img_out, p, vec4(outValue));
}
