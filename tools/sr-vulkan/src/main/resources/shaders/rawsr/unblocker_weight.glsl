// SPDX-License-Identifier: GPL-3.0-or-later
// Unblocker weight (Sabre UnblockerWeightCalculation analogue). One invocation
// per quad on the quad grid: the noise-gated ratio of surviving signal
// variance after 2x2 boxing. Mirrors RawSrUnblocker.computeFrame exactly:
// full 3x3 variance vs half-grid 3x3 variance at q/2, with the expected noise
// variance Vn = A*gray + B from the green normalized coefficients.
//
// Regimes (identical to the oracle): non-finite measurements, negative noise
// estimates, and noise-dominated quads (Vfull <= 2*Vn) all keep weight 1 —
// NaN fails every comparison below, so the default stands with no explicit
// isnan chain. Grid-aligned step edges preserve variance under boxing and
// correctly keep weight 1; only sub-half-cell structure attenuates.
// Invalid model (u_model_valid == 0) disables the gate: weight 1 everywhere.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_gray;
uniform sampler2D u_half;
uniform ivec2 u_size;
uniform ivec2 u_half_size;
uniform vec2 u_ab;
uniform int u_model_valid;
layout(binding = 0, r32f) writeonly uniform highp image2D img_weight;
const float NOISE_GATE = 2.0;
float var3(sampler2D field, ivec2 size, ivec2 p) {
    float sum = 0.0;
    float squares = 0.0;
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            ivec2 t = clamp(p + ivec2(j, i), ivec2(0), size - ivec2(1));
            float v = texelFetch(field, t, 0).r;
            sum += v;
            squares += v * v;
        }
    }
    float mean = sum / 9.0;
    return max(squares / 9.0 - mean * mean, 0.0);
}
void main() {
    ivec2 q = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(q, u_size))) return;
    float outValue = 1.0;
    if (u_model_valid != 0) {
        float g = texelFetch(u_gray, q, 0).r;
        float full = var3(u_gray, u_size, q);
        float noise = u_ab.x * max(g, 0.0) + u_ab.y;
        // full <= 2*noise keeps 1 (noise-dominated or silent); the strict
        // comparisons below also reject NaN on every path, like the oracle.
        if (full >= 0.0 && noise >= 0.0 && full > NOISE_GATE * noise) {
            ivec2 h = clamp(q / 2, ivec2(0), u_half_size - ivec2(1));
            float low = var3(u_half, u_half_size, h);
            if (low >= 0.0) {
                // Positive by construction on this branch (full > 2*noise
                // with noise >= 0 forces full - noise > 0), so no epsilon
                // floor may skew the ratio — exactly like the oracle.
                float den = full - noise;
                outValue = clamp(max(low - noise * 0.25, 0.0) / den, 0.0, 1.0);
            }
        }
    }
    imageStore(img_weight, q, vec4(outValue));
}
