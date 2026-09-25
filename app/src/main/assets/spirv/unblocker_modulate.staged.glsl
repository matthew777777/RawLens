#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// Unblocker fold: r' = clamp(u, 0, 1) * r into the robustness accumulator
// chain, running after robustness_min and before robustness_accumulate.
// Mirrors RawSrUnblocker.applyToFrame exactly: non-finite products sanitize
// to zero, and FLAG_UNBLOCKED marks quads where the weight attenuates.
// NaN weights zero the product with no flag (no trustworthy attenuation to
// report); out-of-range weights clamp like coerceIn (NaN stays NaN through
// the clamp on every real driver, and the explicit isnan below pins the
// contract regardless).
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_r;
uniform sampler2D u_u;
uniform highp usampler2D u_flags_in;
uniform ivec2 u_size;
layout(binding = 0, r32f) writeonly uniform highp image2D img_r;
layout(binding = 1, r32ui) writeonly uniform highp uimage2D img_flags;
const uint FLAG_UNBLOCKED = 1024u;
const float UNBLOCKED_THRESHOLD = 0.999;
bool finite(float x) { return !isnan(x) && !isinf(x); }
void main() {
    ivec2 q = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(q, u_size))) return;
    float r = texelFetch(u_r, q, 0).r;
    float wRaw = texelFetch(u_u, q, 0).r;
    float w = isnan(wRaw) ? 0.0 : clamp(wRaw, 0.0, 1.0);
    float scaled = r * w;
    if (!finite(scaled)) scaled = 0.0;
    uint flag = texelFetch(u_flags_in, q, 0).r;
    if (!isnan(wRaw) && w < UNBLOCKED_THRESHOLD) flag = flag | FLAG_UNBLOCKED;
    imageStore(img_r, q, vec4(scaled));
    imageStore(img_flags, q, uvec4(flag, 0u, 0u, 0u));
}
