// SPDX-License-Identifier: GPL-3.0-or-later
// Prompt 4D merge finalization (docs/raw-sr-merge.md sections 6, 9, 10).
// Per output pixel, adds the reference-last contribution into the moving-frame
// accumulators, normalizes each channel independently with eps = 1e-8, and
// applies the burst-nearest fallback: any channel whose total denominator is
// still under eps takes the robustness-weighted mean of per-frame nearest
// matching-phase samples (no kernel smear), falling back to the reference-only
// A/B value only where even the nearest path has no support — or where the
// reference channel is clipped (saturation guard, SATURATED_REF).
// Additionally,
// any pixel whose quad accumulated less than u_min_support of moving-frame
// robustness (Stacker accumulated-robustness overwrite) takes the fallback
// value on all channels. The fallback mask records every pixel where at
// least one channel fell back or the support overwrite applied. Non-finite
// outputs reset to zero, mirroring the RawSrBayerMerge oracle.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_num;
uniform sampler2D u_den;
uniform sampler2D u_ref_num;
uniform sampler2D u_ref_den;
uniform sampler2D u_near_num;
uniform sampler2D u_near_den;
uniform sampler2D u_rc;
uniform ivec2 u_size;
uniform float u_min_support;
layout(binding = 0, rgba32f) writeonly uniform highp image2D img_out;
layout(binding = 1, r32f) writeonly uniform highp image2D img_fallback;
const float EPS = 1e-8;
// Saturation censor level: a normalised tap at or above this level carries
// no trustworthy signal (SkyKing CENSORED_UNKNOWN_CHROMA) — mirrors the
// oracle exactly (same 0.99 boundary; the oracle decides in float64, the GPU
// in float32 with no float32 value strictly between the two spellings, and
// the 4E Q1 gate excuses quotients within 1e-4 of the boundary).
const float SATURATED_REF = 0.99;
bool finite(float x) { return !isnan(x) && !isinf(x); }
float pick(float totalNum, float totalDen, float nearNum, float nearDen,
           float refNum, float refDen, float rc, bool unsupported) {
    if (!unsupported && totalDen > EPS) {
        float v = totalNum / max(totalDen, EPS);
        return finite(v) ? v : 0.0;
    }
    float refQuotient = refNum / max(refDen, EPS);
    if (refQuotient >= SATURATED_REF) {
        return finite(refQuotient) ? refQuotient : 0.0;
    }
    // Zero moving support at this quad: the burst contributes nothing, so
    // the reference quotient stands (shared-weight mean stays achromatic
    // where per-channel nearest picks would straddle) — mirrors the oracle.
    if (!(rc > EPS)) {
        return finite(refQuotient) ? refQuotient : 0.0;
    }
    // Burst-nearest fallback: where the kernel merge is untrustworthy, the
    // robustness-weighted mean of per-frame nearest matching-phase samples
    // (no kernel smear, no covariance). Nested ref-only fallback where even
    // the nearest path has no support — mirrors the oracle exactly.
    if (nearDen > EPS) {
        float v = nearNum / max(nearDen, EPS);
        return finite(v) ? v : 0.0;
    }
    return finite(refQuotient) ? refQuotient : 0.0;
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    // Rc lives on the quad grid; every pixel in the quad shares the verdict.
    bool unsupported = u_min_support > 0.0 &&
        texelFetch(u_rc, p / 2, 0).r < u_min_support;
    float rc = texelFetch(u_rc, p / 2, 0).r;
    // Censored-site white: the reference tap at this site is clipped, so the
    // honest output is the measured clip for all three channels (highlight
    // desaturation to white) — mirrors the oracle's site-direct branch. The
    // tap rides the refNumerator .w lane (written by the reference pass).
    float siteTap = texelFetch(u_ref_num, p, 0).w;
    bool siteWhite = siteTap >= SATURATED_REF;
    vec4 num = texelFetch(u_num, p, 0) + texelFetch(u_ref_num, p, 0);
    vec4 den = texelFetch(u_den, p, 0) + texelFetch(u_ref_den, p, 0);
    vec4 nearNum = texelFetch(u_near_num, p, 0);
    vec4 nearDen = texelFetch(u_near_den, p, 0);
    vec4 refNum = texelFetch(u_ref_num, p, 0);
    vec4 refDen = texelFetch(u_ref_den, p, 0);
    float siteOut = finite(siteTap) ? siteTap : 0.0;
    float r = siteWhite ? siteOut : pick(num.x, den.x, nearNum.x, nearDen.x, refNum.x, refDen.x, rc, unsupported);
    float g = siteWhite ? siteOut : pick(num.y, den.y, nearNum.y, nearDen.y, refNum.y, refDen.y, rc, unsupported);
    float b = siteWhite ? siteOut : pick(num.z, den.z, nearNum.z, nearDen.z, refNum.z, refDen.z, rc, unsupported);
    float fellBack = (siteWhite || unsupported || den.x <= EPS || den.y <= EPS || den.z <= EPS) ? 1.0 : 0.0;
    imageStore(img_out, p, vec4(r, g, b, 1.0));
    imageStore(img_fallback, p, vec4(fellBack));
}
