#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// Jamy-L Alg. 4 Bayer-direct merge accumulation (`merge.py::accumulate`).
// One invocation per RAW output pixel on the native 1x reference grid. The
// projected source position, nearest-tile flow, covariance interpolation +
// per-pixel inversion, raw-unit exponent, 3x3 support, per-tap CFA routing,
// and gate order mirror RawSrBayerMerge exactly:
// - reference-anchored flow in quad pixels, BILINEAR lookup (flowSmooth),
//   x2 conversion, source(p) = p + 0.5 + 2*flow (zero shift when
//   u_is_reference). Tile borders stay inside alignment and never quilt.
// - robustness r is the reused weight of the quad one up-left of the output
//   pixel (never interpolated; the reference min(int(lr//2-0.5)) lookup
//   replicated verbatim); r == 0 preserves the accumulators without
//   touching the OOB counter.
// - covariance C is bilinearly interpolated at g = source*(guide/raw) - 0.5
//   with reference modf/trunc edge semantics, then inverted per pixel;
//   z = d_raw^T P d_raw clamped at 0, w = exp(-0.5 z), no floor.
// - each in-bounds tap of the 3x3 RAW support contributes only to its own
//   CFA channel: num_c += w*r*sample, den_c += w*r, with independent R/G/B
//   denominators. Samples are linear Bayer observations from u_cfa, never
//   demosaiced RGB. Every finite sample merges (no censor skip);
//   non-finite samples, weights, and robustness are skipped.
// - opt-in green-guided chroma deweight (u_use_chroma, A/B only): R/B taps
//   scale by exp(-0.5 d^2) against the kernel-weighted target green; green
//   taps and the reference pass never gate. u_use_chroma == 0 runs the
//   reference path (no extra reads).
// - black/white normalization and lens shading run exactly once upstream in
//   the normalize path; this pass consumes its output unchanged.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_cfa;
uniform sampler2D u_flow;
uniform sampler2D u_covariance;
uniform sampler2D u_r;
uniform sampler2D u_num;
uniform sampler2D u_den;
uniform sampler2D u_oob;
uniform ivec2 u_size;
uniform ivec2 u_tile_grid;
uniform int u_tile_size;
uniform ivec2 u_guide_size;
uniform ivec4 u_fc;
uniform int u_is_reference;
uniform int u_use_r;
uniform int u_use_chroma;
uniform float u_green_noise_s;
uniform float u_green_noise_o;
layout(binding = 0, rgba32f) writeonly uniform highp image2D img_num;
layout(binding = 1, rgba32f) writeonly uniform highp image2D img_den;
layout(binding = 2, r32f) writeonly uniform highp image2D img_oob;
bool finite(float x) { return !isnan(x) && !isinf(x); }
// Bilinear flow twin of RawSrAlignmentField.flowAtSmooth (same formula, same
// order): tile centers at integer lattice of u = (q + 0.5)/tile - 0.5, dx/dy
// blended. Tile borders stay inside alignment and never quilt the merge.
// Any non-finite corner falls back to the containing (nearest) tile,
// preserving invalid-flow propagation.
vec4 flowSmooth(vec2 q) {
    float ts = float(u_tile_size);
    vec2 u = (q + vec2(0.5)) / ts - vec2(0.5);
    vec2 b = floor(u);
    vec2 f = clamp(u - b, vec2(0.0), vec2(1.0));
    ivec2 lo = ivec2(clamp(b, vec2(0.0), vec2(u_tile_grid) - vec2(1.0)));
    ivec2 hi = ivec2(clamp(b + vec2(1.0), vec2(0.0), vec2(u_tile_grid) - vec2(1.0)));
    vec4 g00 = texelFetch(u_flow, ivec2(lo.x, lo.y), 0);
    vec4 g10 = texelFetch(u_flow, ivec2(hi.x, lo.y), 0);
    vec4 g01 = texelFetch(u_flow, ivec2(lo.x, hi.y), 0);
    vec4 g11 = texelFetch(u_flow, ivec2(hi.x, hi.y), 0);
    ivec2 ntile = clamp(ivec2(q) / u_tile_size, ivec2(0), u_tile_grid - ivec2(1));
    if (!finite(g00.x) || !finite(g00.y) || !finite(g10.x) || !finite(g10.y) ||
        !finite(g01.x) || !finite(g01.y) || !finite(g11.x) || !finite(g11.y)) {
        return texelFetch(u_flow, ntile, 0);
    }
    float w00 = (1.0 - f.x) * (1.0 - f.y);
    float w10 = f.x * (1.0 - f.y);
    float w01 = (1.0 - f.x) * f.y;
    float w11 = f.x * f.y;
    vec4 m = g00 * w00 + g10 * w10 + g01 * w01 + g11 * w11;
    return vec4(m.xy, m.z, m.w);
}
int channelOf(ivec2 t) { return u_fc[((t.y & 1) << 1) | (t.x & 1)]; }
// Dormant A/B chromaWeight analogue for one R/B tap: local green is the mean
// of the finite green samples in the 3x3 window around the tap (no dense
// chromaGuide exists on this path; the oracle estimates it from its own
// green taps identically). No green neighbour, or a non-finite factor,
// returns exactly 1. Mirrors RawSrBayerMerge.chromaFactor.
float chromaFactor(ivec2 t, float targetGreen) {
    float sum = 0.0;
    int n = 0;
    for (int oy = -1; oy <= 1; oy++) {
        for (int ox = -1; ox <= 1; ox++) {
            ivec2 g = t + ivec2(ox, oy);
            if (any(lessThan(g, ivec2(0))) || any(greaterThanEqual(g, u_size))) continue;
            if (channelOf(g) != 1) continue;
            float s = texelFetch(u_cfa, g, 0).r;
            if (!finite(s)) continue;
            sum += s;
            n++;
        }
    }
    if (n == 0) return 1.0;
    float localGreen = sum / float(n);
    float signal = max(max(localGreen, targetGreen), 0.0);
    float variance = max(u_green_noise_s * signal + u_green_noise_o, 0.0);
    float sigma = max(2.5 * sqrt(variance), 1.0 / 160.0);
    float d = (localGreen - targetGreen) / sigma;
    float f = exp(-0.5 * d * d);
    return finite(f) ? f : 1.0;
}
void preserve(ivec2 p, float oobBump) {
    imageStore(img_num, p, texelFetch(u_num, p, 0));
    imageStore(img_den, p, texelFetch(u_den, p, 0));
    imageStore(img_oob, p, texelFetch(u_oob, p, 0) + vec4(oobBump));
}
void main() {
    ivec2 p = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(p, u_size))) return;
    ivec2 quad = p / 2;
    float r = 1.0;
    vec2 shift = vec2(0.0);
    if (u_is_reference == 0) {
        if (u_use_r != 0) {
            // Reference bayer lookup verbatim: min(int(lr//2-0.5)) reads the
            // quad one up-left of the output pixel, edge-clamped.
            ivec2 rq = clamp(quad - ivec2(1), ivec2(0), textureSize(u_r, 0) - ivec2(1));
            r = texelFetch(u_r, rq, 0).r;
            if (!finite(r)) r = 0.0;
        }
        if (r == 0.0) return;
        // Bilinear flow sampling: the four surrounding tiles blend dx/dy so
        // tile borders never quilt the merge. Non-finite corners fall back
        // to the containing (nearest) tile inside flowSmooth.
        vec4 flow = flowSmooth(vec2(quad));
        if (!finite(flow.x) || !finite(flow.y)) {
            preserve(p, 1.0);
            return;
        }
        shift = flow.xy;
    }
    vec2 source = vec2(p) + vec2(0.5) + 2.0 * shift;
    if (!finite(source.x) || !finite(source.y)
        || any(lessThan(source, vec2(0.0))) || any(greaterThanEqual(source, vec2(u_size)))) {
        preserve(p, 1.0);
        return;
    }
    vec2 g = source * (vec2(u_guide_size) / vec2(u_size)) - vec2(0.5);
    if (!finite(g.x) || !finite(g.y)) return;
    // Reference modf/int edge semantics: the fraction keeps the sign (so the
    // sub-center edge extrapolates); truncation clips at 0. GLSL modf writes
    // the truncation to its second argument, exactly like the reference.
    float ix;
    float iy;
    vec2 f = vec2(modf(g.x, ix), modf(g.y, iy));
    ivec2 g0 = ivec2(ix, iy);
    if (g0.x < 0) g0.x = 0;
    if (g0.y < 0) g0.y = 0;
    if (g0.x >= u_guide_size.x || g0.y >= u_guide_size.y) return;
    ivec2 g1 = min(g0 + ivec2(1), u_guide_size - ivec2(1));
    vec4 c00 = texelFetch(u_covariance, g0, 0);
    vec4 c10 = texelFetch(u_covariance, ivec2(g1.x, g0.y), 0);
    vec4 c01 = texelFetch(u_covariance, ivec2(g0.x, g1.y), 0);
    vec4 c11 = texelFetch(u_covariance, g1, 0);
    if (!finite(c00.x) || !finite(c00.y) || !finite(c00.z) || !finite(c00.w)
        || !finite(c10.x) || !finite(c10.y) || !finite(c10.z) || !finite(c10.w)
        || !finite(c01.x) || !finite(c01.y) || !finite(c01.z) || !finite(c01.w)
        || !finite(c11.x) || !finite(c11.y) || !finite(c11.z) || !finite(c11.w)) return;
    vec4 cov = mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
    float det = cov.x * cov.w - cov.y * cov.y;
    if (!finite(det) || det <= 0.0) return;
    vec4 mat = vec4(cov.w / det, -cov.y / det, -cov.y / det, cov.x / det);
    ivec2 center = ivec2(floor(source));
    vec4 num = texelFetch(u_num, p, 0);
    vec4 den = texelFetch(u_den, p, 0);
    // Chroma target-green pass (mirrors the oracle): the kernel-weighted
    // green mean at the source. Skipped unless a moving frame opts in; the
    // reference pass (u_is_reference == 1) never deweights.
    float targetGreen = 0.0;
    bool hasTargetGreen = false;
    if (u_use_chroma != 0 && u_is_reference == 0) {
        float gSum = 0.0;
        float gW = 0.0;
        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                ivec2 t = center + ivec2(ox, oy);
                if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, u_size))) continue;
                if (channelOf(t) != 1) continue;
                float obs = texelFetch(u_cfa, t, 0).r;
                if (!finite(obs)) continue;
                vec2 d = vec2(t) + vec2(0.5) - source;
                float z = mat.x * d.x * d.x + (mat.y + mat.z) * d.x * d.y + mat.w * d.y * d.y;
                if (!finite(z)) continue;
                float w = exp(-0.5 * max(z, 0.0));
                if (!finite(w)) continue;
                gSum += w * r * obs;
                gW += w * r;
            }
        }
        if (gW > 0.0) {
            targetGreen = gSum / gW;
            hasTargetGreen = true;
        }
    }
    for (int oy = -1; oy <= 1; oy++) {
        for (int ox = -1; ox <= 1; ox++) {
            ivec2 t = center + ivec2(ox, oy);
            if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, u_size))) continue;
            float obs = texelFetch(u_cfa, t, 0).r;
            if (!finite(obs)) continue;
            vec2 d = vec2(t) + vec2(0.5) - source;
            float z = mat.x * d.x * d.x + (mat.y + mat.z) * d.x * d.y + mat.w * d.y * d.y;
            if (!finite(z)) continue;
            float w = exp(-0.5 * max(z, 0.0));
            if (!finite(w)) continue;
            float weighted = w * r;
            int c = channelOf(t);
            if (hasTargetGreen && c != 1) {
                weighted *= chromaFactor(t, targetGreen);
            }
            if (c == 0) {
                num.x += weighted * obs; den.x += weighted;
            } else if (c == 1) {
                num.y += weighted * obs; den.y += weighted;
            } else {
                num.z += weighted * obs; den.z += weighted;
            }
        }
    }
    imageStore(img_num, p, num);
    imageStore(img_den, p, den);
    imageStore(img_oob, p, texelFetch(u_oob, p, 0));
}
