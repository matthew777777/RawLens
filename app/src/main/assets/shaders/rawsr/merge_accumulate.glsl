// SPDX-License-Identifier: GPL-3.0-or-later
// Jamy-L Alg. 4 Bayer-direct merge accumulation (`merge.py::accumulate`).
// One invocation per RAW output pixel on the 1x or shared-√2 SR output grid. The
// projected source position, SMOOTH-bilinear flow, NEAREST-quad robustness,
// covariance interpolation + per-pixel inversion, raw-unit exponent, 3x3
// support, per-tap CFA routing, chroma latch guard, and gate order mirror
// RawSrBayerMerge exactly (via RawSrCoreSampling / RawSrCoreKernel, same
// formulas, same order, float32):
// - reference-anchored flow in RAW pixels on the RAW lattice (the alignment
//   1:1 lattice), bilinear blend of the four surrounding tiles at the raw
//   source (RawSrAlignmentField.flowAtSmoothInto twin: u = (raw + 0.5) /
//   tile - 0.5, floor-corner fetches, edge-clamped, bilinear weights),
//   applied verbatim with no unit conversion,
//   source(p) = (p + 0.5) / u_upscale + flow (zero shift when
//   u_is_reference). The warp is C0-continuous, so no tile tears can
//   form; mistakes are rejected per-pixel by r (computed from the
//   same bilinear warp), not by flow vetoes. Non-finite corners fall
//   back to the containing tile; a non-finite result skips the pixel
//   with oob++ (invalid-flow propagation).
//   u_size is the OUTPUT grid (source size x u_upscale);
//   taps, clamps, and the guide scale stay source-anchored via u_cfa.
// - robustness r is the NEAREST quad with the reference one-quad shift
//   (RawSrCoreSampling.sampleRobustness: floor(s) at the source pixel center
//   s = (p + 0.5) / u_upscale / 2 - 1, edge-clamped); r == 0 preserves the
//   accumulators without touching the OOB counter. Non-finite fetches
//   sanitize to 0.
// - covariance C is bilinearly interpolated at g = source*(guide/raw) - 0.5
//   with reference modf/trunc edge semantics (sign-preserving fractions,
//   truncation clipped at 0, row-then-column lerp), then inverted per pixel
//   UNCONDITIONALLY (no PD gate: edge extrapolation may go indefinite and
//   the reference still merges it); z = d_raw^T P d_raw clamped at 0,
//   w = exp(-0.5 z), no floor.
// - each in-bounds tap of the 3x3 RAW support contributes only to its own
//   CFA channel: num_c += w*r*sample, den_c += w*r, with independent R/G/B
//   denominators. Samples are linear Bayer observations from u_cfa, never
//   demosaiced RGB. Every finite sample merges (no censor skip);
//   non-finite samples, weights, and robustness are skipped.
// - chroma latch guard (base path, always on): R/B taps see the widened
//   kernel z * u_chroma_z_scale (1/s^2 with s = CHROMA_SIGMA_MPY = 2.0,
//   Nyquist-matched to their 2px lattices); green keeps the unscaled z, so
//   the luma lane is bitwise reference-verbatim.
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
uniform float u_upscale;
uniform ivec2 u_guide_size;
uniform ivec4 u_fc;
uniform int u_is_reference;
uniform int u_use_r;
uniform int u_use_chroma;
uniform float u_green_noise_s;
uniform float u_green_noise_o;
uniform float u_chroma_z_scale;
layout(binding = 0, rgba32f) writeonly uniform highp image2D img_num;
layout(binding = 1, rgba32f) writeonly uniform highp image2D img_den;
layout(binding = 2, r32f) writeonly uniform highp image2D img_oob;
bool finite(float x) { return !isnan(x) && !isinf(x); }
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
            if (any(lessThan(g, ivec2(0))) || any(greaterThanEqual(g, textureSize(u_cfa, 0)))) continue;
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
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    // Output pixel p centers on source (p + 0.5) / u_upscale
    // (RawSrCoreSampling.sourceCenter); u_size is the OUTPUT grid while
    // taps/clamps stay source-anchored via u_cfa.
    float us = u_upscale;
    ivec2 srcSizeI = textureSize(u_cfa, 0);
    vec2 srcSize = vec2(srcSizeI);
    vec2 raw = (vec2(p) + vec2(0.5)) / us;
    float r = 1.0;
    vec2 shift = vec2(0.0);
    if (u_is_reference == 0) {
        if (u_use_r != 0) {
            // Reference `cpu_accumulate` robustness fetch verbatim
            // (RawSrCoreSampling.sampleRobustness): nearest quad with the
            // one-quad shift, floor(s) at the source pixel center
            // s = (p + 0.5) / u_upscale / 2 - 1, edge-clamped.
            vec2 rs = (vec2(p) + vec2(0.5)) / us / 2.0 - vec2(1.0);
            ivec2 rq = clamp(ivec2(floor(rs)), ivec2(0), textureSize(u_r, 0) - ivec2(1));
            r = texelFetch(u_r, rq, 0).r;
            if (!finite(r)) r = 0.0;
        }
        if (r == 0.0) return;
        // Bilinear smooth flow (CPU flowAtSmoothInto twin, Sabre-style
        // dense gather): the four surrounding tiles blend with bilinear
        // weights at the raw source position, so the warp is
        // C0-continuous and no tile tears can form. Mistakes are
        // rejected per-pixel by r (computed from the same bilinear
        // warp), not by flow vetoes. Non-finite corners fall back to
        // the containing tile; a non-finite result skips the pixel
        // with oob++, preserving invalid-flow propagation.
        ivec2 tile = clamp(ivec2(floor(raw)) / u_tile_size, ivec2(0), u_tile_grid - ivec2(1));
        vec4 flow = texelFetch(u_flow, tile, 0);
        vec2 uu = (raw + vec2(0.5)) / float(u_tile_size) - vec2(0.5);
        vec2 ff = clamp(uu - floor(uu), vec2(0.0), vec2(1.0));
        ivec2 gg = u_tile_grid - ivec2(1);
        ivec2 i0 = ivec2(floor(uu));
        ivec2 ta = clamp(i0, ivec2(0), gg);
        ivec2 tb = clamp(i0 + ivec2(1), ivec2(0), gg);
        vec4 f00 = texelFetch(u_flow, ivec2(ta.x, ta.y), 0);
        vec4 f10 = texelFetch(u_flow, ivec2(tb.x, ta.y), 0);
        vec4 f01 = texelFetch(u_flow, ivec2(ta.x, tb.y), 0);
        vec4 f11 = texelFetch(u_flow, ivec2(tb.x, tb.y), 0);
        if (finite(f00.x) && finite(f00.y) && finite(f10.x) && finite(f10.y) &&
            finite(f01.x) && finite(f01.y) && finite(f11.x) && finite(f11.y)) {
            float w00 = (1.0 - ff.x) * (1.0 - ff.y);
            float w10 = ff.x * (1.0 - ff.y);
            float w01 = (1.0 - ff.x) * ff.y;
            float w11 = ff.x * ff.y;
            flow.x = f00.x * w00 + f10.x * w10 + f01.x * w01 + f11.x * w11;
            flow.y = f00.y * w00 + f10.y * w10 + f01.y * w01 + f11.y * w11;
        }
        if (!finite(flow.x) || !finite(flow.y)) {
            preserve(p, 1.0);
            return;
        }
        shift = flow.xy;
    }
    vec2 source = raw + shift;
    if (!finite(source.x) || !finite(source.y)
        || any(lessThan(source, vec2(0.0))) || any(greaterThanEqual(source, srcSize))) {
        preserve(p, 1.0);
        return;
    }
    // Source-anchored covariance lookup coordinate
    // (RawSrCoreSampling.covarianceGuideCoord).
    vec2 g = source * (vec2(u_guide_size) / srcSize) - vec2(0.5);
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
    // Row-then-column lerp (RawSrCoreSampling.interpolateCovariance).
    vec4 cov = mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
    // The reference inverts unconditionally (`inv_det = 1/det`, no PD check):
    // sub-center edge extrapolation (negative modf fractions) can yield a
    // non-PD lerp, and the reference still merges it (z clamps at 0, so
    // indefinite quads read w = 1, singular quads read w = 0 off center).
    // Skipping here would black the left/top border where the reference
    // merges. A zero determinant reads ±Inf (SPIR-V IEEE, no trap) and the
    // tap loop's max(z, 0) + finite checks keep the weights safe exactly
    // like the reference clamp.
    float det = cov.x * cov.w - cov.y * cov.y;
    vec4 mat = vec4(cov.w / det, -cov.y / det, -cov.y / det, cov.x / det);
    // One cross term per pixel, not per tap: same operands, same sum
    // (RawSrCoreKernel.tapZ).
    float cross = mat.y + mat.z;
    ivec2 center = ivec2(floor(source));
    vec4 num = texelFetch(u_num, p, 0);
    vec4 den = texelFetch(u_den, p, 0);
    // Chroma target-green pass (mirrors the oracle): the kernel-weighted
    // green mean at the source, with the same spatial weights x r as the
    // main loop and the unscaled z (green never deweights). Skipped unless
    // a moving frame opts in; the reference pass (u_is_reference == 1)
    // never deweights.
    float targetGreen = 0.0;
    bool hasTargetGreen = false;
    if (u_use_chroma != 0 && u_is_reference == 0) {
        float gSum = 0.0;
        float gW = 0.0;
        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                ivec2 t = center + ivec2(ox, oy);
                if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, srcSizeI))) continue;
                if (channelOf(t) != 1) continue;
                float obs = texelFetch(u_cfa, t, 0).r;
                if (!finite(obs)) continue;
                vec2 d = vec2(t) + vec2(0.5) - source;
                float z = mat.x * d.x * d.x + cross * d.x * d.y + mat.w * d.y * d.y;
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
            if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, srcSizeI))) continue;
            float obs = texelFetch(u_cfa, t, 0).r;
            if (!finite(obs)) continue;
            vec2 d = vec2(t) + vec2(0.5) - source;
            float z = mat.x * d.x * d.x + cross * d.x * d.y + mat.w * d.y * d.y;
            // Phase ordinals ARE the channel indices (R=0 G=1 B=2).
            int c = channelOf(t);
            // Chroma latch guard (RawSrCoreKernel.scaleChromaZ): R/B taps see
            // the widened kernel (z / s^2); green keeps the unscaled z, so
            // the luma lane is bitwise-identical with or without the guard.
            float zc = (c == 1) ? z : z * u_chroma_z_scale;
            if (!finite(zc)) continue;
            float w = exp(-0.5 * max(zc, 0.0));
            if (!finite(w)) continue;
            float weighted = w * r;
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
