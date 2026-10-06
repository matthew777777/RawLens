// SPDX-License-Identifier: GPL-3.0-or-later
// Jamy-L merge finalization (`utils.divide`): per output pixel, adds the
// reference-last contribution into the moving-frame accumulators and
// normalizes each channel independently with the exact-zero gate
// (RawSrCoreKernel.divide/divideFallback, EPS = 0.0): only exactly-zero
// support divides to 0 (the reference NaN blacked downstream); tiny but
// nonzero support divides to its finite weighted mean like the reference.
// The fallback mask records every pixel where at least one channel had no
// support or the quotient was non-finite. Non-finite outputs reset to zero,
// mirroring the RawSrBayerMerge oracle.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_num;
uniform sampler2D u_den;
uniform sampler2D u_ref_num;
uniform sampler2D u_ref_den;
uniform ivec2 u_size;
layout(binding = 0, rgba32f) writeonly uniform highp image2D img_out;
layout(binding = 1, r32f) writeonly uniform highp image2D img_fallback;
const float EPS = 0.0;
bool finite(float x) { return !isnan(x) && !isinf(x); }
float pick(float totalNum, float totalDen) {
    if (!(totalDen > EPS)) return 0.0;
    float v = totalNum / max(totalDen, EPS);
    return finite(v) ? v : 0.0;
}
bool fellBack(float totalNum, float totalDen) {
    if (!(totalDen > EPS)) return true;
    float v = totalNum / max(totalDen, EPS);
    return !finite(v);
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    vec4 num = texelFetch(u_num, p, 0) + texelFetch(u_ref_num, p, 0);
    vec4 den = texelFetch(u_den, p, 0) + texelFetch(u_ref_den, p, 0);
    float r = pick(num.x, den.x);
    float g = pick(num.y, den.y);
    float b = pick(num.z, den.z);
    float fb = (fellBack(num.x, den.x) || fellBack(num.y, den.y) || fellBack(num.z, den.z)) ? 1.0 : 0.0;
    imageStore(img_out, p, vec4(r, g, b, 1.0));
    imageStore(img_fallback, p, vec4(fb));
}
