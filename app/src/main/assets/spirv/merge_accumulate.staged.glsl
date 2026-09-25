#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// Prompt 4D Bayer-direct merge accumulation (docs/raw-sr-merge.md, normative).
// One invocation per RAW output pixel on the native 1x reference grid. The
// projected source position, source-anchored precision interpolation,
// quad-unit exponent, 3x3 support, per-tap CFA routing, and gate order mirror
// RawSrBayerMerge exactly:
// - reference-anchored flow in quad pixels, bilinear lookup (flowSmooth),
//   x2 conversion, source(p) = p + 0.5 + 2*flow (zero shift when u_is_reference).
// - robustness r is the reused nearest-quad weight (never interpolated);
//   r == 0 preserves the accumulators without touching the OOB counter.
// - precision P is bilinearly interpolated at g = source*(guide/raw) - 0.5
//   (clamped); z = d_quad^T P d_quad clamped at 0, w = exp(-0.5 z), no floor.
// - each in-bounds tap of the 3x3 RAW support contributes only to its own
//   CFA channel: num_c += w*r*sample, den_c += w*r, with independent R/G/B
//   denominators. Samples are linear Bayer observations from u_cfa, never
//   demosaiced RGB. Non-finite samples, weights, and robustness are skipped.
//   Censored taps (>= SATURATED_REF) are skipped like non-finite ones in
//   both the kernel and the nearest loops: clipped values must not bleed
//   through kernel means, not even from the reference at r = 1 (SkyKing
//   CENSORED_UNKNOWN_CHROMA), and a clipped white tap must not become a
//   fallback value (deliberate deviation from Stacker's finite-only
//   nearest rule, pinned by nearestSkipsCensoredTaps).
// - the reference pass additionally records its center tap per pixel into
//   the num .w lane (the site's direct sample; the reference runs with zero
//   shift; call-site discipline: img_num is refNumerator here). Moving
//   passes leave that texture untouched. Cleared texels read 0.0.
// - opt-in green-guided chroma deweight (u_use_chroma, Sabre chromaWeight
//   analogue): R/B taps scale by exp(-0.5 d^2) against the kernel-weighted
//   target green; green taps, the reference pass, and burst-nearest never
//   gate. u_use_chroma == 0 runs the legacy path (no extra reads).
// - black/white normalization and lens shading run exactly once upstream in
//   the normalize path; this pass consumes its output unchanged.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_cfa;
uniform sampler2D u_flow;
uniform sampler2D u_precision;
uniform sampler2D u_r;
uniform sampler2D u_num;
uniform sampler2D u_den;
uniform sampler2D u_oob;
uniform sampler2D u_near_num;
uniform sampler2D u_near_den;
uniform ivec2 u_size;
uniform ivec2 u_tile_grid;
uniform int u_tile_size;
uniform float u_motion_edge;
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
layout(binding = 3, rgba32f) writeonly uniform highp image2D img_near_num;
layout(binding = 4, rgba32f) writeonly uniform highp image2D img_near_den;
bool finite(float x) { return !isnan(x) && !isinf(x); }
// Censor boundary shared with the kernel loop below and the oracle's
// SATURATED_REF_GUARD: clipped taps carry no trustworthy signal anywhere.
const float SATURATED_REF = 0.99;
// Bilinear flow twin of RawSrAlignmentField.flowAtSmooth (same formula, same
// order): tile centers at integer lattice of u = (p + 0.5)/tile - 0.5, dx/dy
// blended. Residual stays nearest (containing tile): the residual gate is a
// threshold and must keep its exact old boundary on both sides. Blended
// reliability gates at w >= 0.5. Tile borders stay inside alignment.
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
    return vec4(m.xy, texelFetch(u_flow, ntile, 0).z, m.w >= 0.5 ? 1.0 : 0.0);
}
int channelOf(ivec2 t) { return u_fc[((t.y & 1) << 1) | (t.x & 1)]; }
// Sabre chromaWeight analogue for one R/B tap: local green is the mean of
// the finite uncensored green samples in the 3x3 window around the tap
// (no dense chromaGuide exists on this path; the oracle estimates it from
// its own green taps identically). No green neighbour, or a non-finite
// factor, returns exactly 1. Mirrors RawSrBayerMerge.chromaFactor.
float chromaFactor(ivec2 t, float targetGreen) {
    float sum = 0.0;
    int n = 0;
    for (int oy = -1; oy <= 1; oy++) {
        for (int ox = -1; ox <= 1; ox++) {
            ivec2 g = t + ivec2(ox, oy);
            if (any(lessThan(g, ivec2(0))) || any(greaterThanEqual(g, u_size))) continue;
            if (channelOf(g) != 1) continue;
            float s = texelFetch(u_cfa, g, 0).r;
            if (!finite(s) || s >= SATURATED_REF) continue;
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
    imageStore(img_near_num, p, texelFetch(u_near_num, p, 0));
    imageStore(img_near_den, p, texelFetch(u_near_den, p, 0));
}
void main() {
    ivec2 p = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(p, u_size))) return;
    ivec2 quad = p / 2;
    float r = 1.0;
    vec2 shift = vec2(0.0);
    if (u_is_reference == 0) {
        if (u_use_r != 0) {
            r = texelFetch(u_r, quad, 0).r;
            if (!finite(r)) r = 0.0;
        }
        if (r == 0.0) return;
        vec4 flow = flowSmooth(vec2(quad));
        if (!finite(flow.x) || !finite(flow.y)) {
            preserve(p, 1.0);
            return;
        }
        // Motion-edge stop (mirrors the oracle's flowDisagrees gate at
        // MOTION_EDGE_QUAD): 3x3 tile spread around the containing tile, over
        // in-bounds RELIABLE tiles only. Neighbouring tiles disagreeing by
        // more than a quad pixel mean the bilinear flow blends two motions
        // (water, occlusion boundaries) and every splat would misregister —
        // skip like r == 0 (accumulators keep prior values → reference-only
        // downstream), with no OOB bump. Only demonstrated disagreement
        // vetoes: non-finite or unreliable neighbours are missing data, not
        // motion — those pixels merge, with the robustness gates (residual,
        // photo term, s1 scaling) as backstop.
        {
            ivec2 tile = clamp(quad / u_tile_size, ivec2(0), u_tile_grid - ivec2(1));
            float minX = 1e30;
            float minY = 1e30;
            float maxX = -1e30;
            float maxY = -1e30;
            int finiteCount = 0;
            for (int k = 0; k < 9; k++) {
                ivec2 t = tile + ivec2(k % 3 - 1, k / 3 - 1);
                if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, u_tile_grid))) continue;
                vec4 tg = texelFetch(u_flow, t, 0);
                if (!finite(tg.x) || !finite(tg.y)) continue;
                if (tg.w < 0.5) continue;
                finiteCount++;
                minX = min(minX, tg.x); minY = min(minY, tg.y);
                maxX = max(maxX, tg.x); maxY = max(maxY, tg.y);
            }
            if (finiteCount >= 2) {
                float spX = maxX - minX;
                float spY = maxY - minY;
                if (spX * spX + spY * spY > u_motion_edge * u_motion_edge) return;
            }
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
    vec2 gc = clamp(g, vec2(0.0), vec2(u_guide_size) - vec2(1.0));
    ivec2 g0 = ivec2(floor(gc));
    ivec2 g1 = min(g0 + ivec2(1), u_guide_size - ivec2(1));
    vec2 f = gc - vec2(g0);
    vec4 p00 = texelFetch(u_precision, g0, 0);
    vec4 p10 = texelFetch(u_precision, ivec2(g1.x, g0.y), 0);
    vec4 p01 = texelFetch(u_precision, ivec2(g0.x, g1.y), 0);
    vec4 p11 = texelFetch(u_precision, g1, 0);
    if (!finite(p00.x) || !finite(p00.y) || !finite(p00.z) || !finite(p00.w)
        || !finite(p10.x) || !finite(p10.y) || !finite(p10.z) || !finite(p10.w)
        || !finite(p01.x) || !finite(p01.y) || !finite(p01.z) || !finite(p01.w)
        || !finite(p11.x) || !finite(p11.y) || !finite(p11.z) || !finite(p11.w)) return;
    vec4 mat = mix(mix(p00, p10, f.x), mix(p01, p11, f.x), f.y);
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
                if (obs >= SATURATED_REF) continue;
                vec2 d = (vec2(t) + vec2(0.5) - source) * 0.5;
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
            if (obs >= SATURATED_REF) continue;
            vec2 d = (vec2(t) + vec2(0.5) - source) * 0.5;
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
    // Burst-nearest accumulation (Stacker delta-kernel rule): per channel,
    // the nearest finite uncensored sample of the channel's colour in the
    // same floor-centered 3x3 window, weighted by robustness. Censored taps
    // must not become fallback values (mirrors the oracle). Nearest by
    // texel-center distance squared with a strictly-less update, so the
    // oy-outer/ox-inner loop order deterministically breaks ties exactly
    // like the oracle's accumulateNearest.
    vec4 nearNum = texelFetch(u_near_num, p, 0);
    vec4 nearDen = texelFetch(u_near_den, p, 0);
    float bestD2[3];
    float bestS[3];
    bestD2[0] = 1e30; bestD2[1] = 1e30; bestD2[2] = 1e30;
    bestS[0] = 0.0; bestS[1] = 0.0; bestS[2] = 0.0;
    for (int oy = -1; oy <= 1; oy++) {
        for (int ox = -1; ox <= 1; ox++) {
            ivec2 t = center + ivec2(ox, oy);
            if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, u_size))) continue;
            int c = channelOf(t);
            if (c < 0 || c > 2) continue;
            float obs = texelFetch(u_cfa, t, 0).r;
            if (!finite(obs) || obs >= SATURATED_REF) continue;
            vec2 e = vec2(t) + vec2(0.5) - source;
            float d2 = dot(e, e);
            // Unrolled by channel (no dynamic array indexing): strictly-less
            // update keeps the loop-order tie-break.
            if (c == 0 && d2 < bestD2[0]) {
                bestD2[0] = d2;
                bestS[0] = obs;
            } else if (c == 1 && d2 < bestD2[1]) {
                bestD2[1] = d2;
                bestS[1] = obs;
            } else if (c == 2 && d2 < bestD2[2]) {
                bestD2[2] = d2;
                bestS[2] = obs;
            }
        }
    }
    // A channel with no finite matching tap keeps +inf distance and
    // contributes nothing, mirroring the oracle's NaN-sample skip.
    if (bestD2[0] < 1e29) {
        nearNum.x += r * bestS[0]; nearDen.x += r;
    }
    if (bestD2[1] < 1e29) {
        nearNum.y += r * bestS[1]; nearDen.y += r;
    }
    if (bestD2[2] < 1e29) {
        nearNum.z += r * bestS[2]; nearDen.z += r;
    }
    if (u_is_reference == 1) {
        // The reference pass records its center tap (the site's direct
        // sample; the reference runs with zero shift) in the .w lane for the
        // censored-site rule. Call-site discipline: img_num is the
        // refNumerator texture here, which moving passes never touch;
        // cleared texels read 0.0 = uncensored. Finalize reads the tap back
        // from u_ref_num.w — no extra texture (Mali allows 8 compute images
        // and the clear pass already binds 8).
        num.w = texelFetch(u_cfa, p, 0).r;
    }
    imageStore(img_num, p, num);
    imageStore(img_den, p, den);
    imageStore(img_oob, p, texelFetch(u_oob, p, 0));
    imageStore(img_near_num, p, nearNum);
    imageStore(img_near_den, p, nearDen);
}
