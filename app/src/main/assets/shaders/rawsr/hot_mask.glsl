// SPDX-License-Identifier: GPL-3.0-or-later
// Hot-pixel pre-mask (Sabre suppress_hot_pixels_bayer analogue).
// One invocation per RAW pixel on the full-res code plane. Flags defective
// taps by comparing each code against its 5x5 same-phase ring: the eight taps
// at even offsets share the center's CFA phase and colour (a 3x3 window holds
// no same-colour neighbour under a 2x2 Bayer repeat), so the comparison is
// phase-exact for any Bayer pattern or crop origin.
//
// Gate mirrors RawSrHotPixel.detect exactly, both directions: stuck-high
// when the tap clears the gate against BOTH its ring mean and its ring
// maximum, stuck-low when it clears against the mean and the ring minimum
// (a stuck tap is the local extremum of its colour plane; texture and step
// edges routinely clear a mean-only gate on one side). An invalid model
// (u_model_valid == 0) or non-positive variance disables the gate.
// Exact-corner pixels see fewer than MIN_RING_TAPS ring taps and are never
// flagged. Output is 1 (defective) or 0 in an R32UI texture.
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform highp usampler2D u_raw;
uniform ivec2 u_size;
uniform float u_white;
uniform vec4 u_slope;
uniform vec4 u_offset;
uniform int u_model_valid;
layout(binding = 0, r32ui) writeonly uniform highp uimage2D img_mask;
const float HOT_SIGMA = 6.0;
const float HOT_ABS_FLOOR = 0.01;
const int MIN_RING_TAPS = 4;
int phaseOf(ivec2 p) { return ((p.y & 1) << 1) | (p.x & 1); }
float slopeOf(int phase) {
    return phase == 0 ? u_slope.x : phase == 1 ? u_slope.y : phase == 2 ? u_slope.z : u_slope.w;
}
float offsetOf(int phase) {
    return phase == 0 ? u_offset.x : phase == 1 ? u_offset.y : phase == 2 ? u_offset.z : u_offset.w;
}
bool finite(float x) { return !isnan(x) && !isinf(x); }
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    uint outMask = 0u;
    if (u_model_valid != 0) {
        float code = float(texelFetch(u_raw, p, 0).r);
        int phase = phaseOf(p);
        float variance = slopeOf(phase) * code + offsetOf(phase);
        if (finite(code) && finite(variance) && variance > 0.0) {
            float sigma = sqrt(variance);
            float sum = 0.0;
            float peak = -1e30;
            float vale = 1e30;
            int count = 0;
            for (int oy = -2; oy <= 2; oy += 2) {
                for (int ox = -2; ox <= 2; ox += 2) {
                    if (ox == 0 && oy == 0) continue;
                    ivec2 t = p + ivec2(ox, oy);
                    if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, u_size))) continue;
                    float v = float(texelFetch(u_raw, t, 0).r);
                    sum += v;
                    peak = max(peak, v);
                    vale = min(vale, v);
                    count++;
                }
            }
            if (count >= MIN_RING_TAPS) {
                float gate = max(HOT_SIGMA * sigma, HOT_ABS_FLOOR * u_white);
                float mean = sum / float(count);
                if (code - mean > gate && code - peak > gate) outMask = 1u;
                else if (mean - code > gate && vale - code > gate) outMask = 1u;
            }
        }
    }
    imageStore(img_mask, p, uvec4(outMask, 0u, 0u, 0u));
}
