#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// Jamy-L Alg. 6 robustness (`robustness.py::compute_robustness`): per-quad
// photometric agreement between the reference sqrt guide and the Dogson-warped
// moving 3x3 means, over measured reference variance with the optional
// measured-LUT noise correction, scaled by the s1/s2 flow-irregularity Gate
// and thresholded. Mirrors RawSrRobustness.evaluate gate order exactly:
// invalid flow, bounds, photo term, threshold, finite guard. Outputs raw R
// (before the 5x5 local minimum) and own-quad flags (OOB / invalid-flow only;
// the reference defines no other gate).
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_ref_lin;
uniform sampler2D u_mov_lin;
uniform sampler2D u_flow;
uniform ivec2 u_size;
uniform ivec2 u_tile_grid;
uniform int u_tile_size;
uniform float u_t;
uniform float u_s1;
uniform float u_s2;
uniform float u_mth_quad;
// Measured noise LUT: bins x 1 RGBA32F texel (R = sigma_sq, G = d_sq).
// Disabled by u_lut_enabled = 0 (a 1x1 zero dummy stays bound); bin selection
// is floor(b * (bins - 1) + 0.5), exactly the oracle's RawSrNoiseLut.sample.
uniform sampler2D u_lut;
uniform int u_lut_bins;
uniform int u_lut_enabled;
layout(binding = 0, r32f) writeonly uniform highp image2D img_r;
layout(binding = 1, r32ui) writeonly uniform highp uimage2D img_flags;
// Flag values match RawSrRobustness constants.
const uint FLAG_OUT_OF_BOUNDS = 8u;
const uint FLAG_INVALID_FLOW = 16u;
bool finite(float x) { return !isnan(x) && !isinf(x); }
vec2 clampTap(vec2 p) { return clamp(p, vec2(0.0), vec2(u_size) - vec2(1.0)); }
// Reference dogson_quadratic_kernel (utils_image.py).
float dogsonQuadratic(float x) {
    float a = abs(x);
    if (a <= 0.5) return -2.0 * a * a + 1.0;
    if (a <= 1.5) return a * a - 2.5 * a + 1.5;
    return 0.0;
}
// Clamp-to-edge 3x3 box mean of the moving guide at quad p: the oracle's
// movMean table. The Dogson warp below interpolates these means, never raw texels.
vec3 movMeanAt(ivec2 p) {
    vec3 m = vec3(0.0);
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            m += texelFetch(u_mov_lin, ivec2(clampTap(vec2(p) + vec2(float(j), float(i)))), 0).rgb;
        }
    }
    return m / 9.0;
}
void main() {
    ivec2 q = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(q, u_size))) return;
    ivec2 tile = clamp(q / u_tile_size, ivec2(0), u_tile_grid - ivec2(1));
    vec4 flow = texelFetch(u_flow, tile, 0);
    if (!finite(flow.x) || !finite(flow.y)) {
        imageStore(img_r, q, vec4(0.0));
        imageStore(img_flags, q, uvec4(FLAG_INVALID_FLOW, 0u, 0u, 0u));
        return;
    }
    vec2 center = vec2(q) + flow.xy;
    if (any(lessThan(center, vec2(0.0))) || any(greaterThanEqual(center, vec2(u_size)))) {
        imageStore(img_r, q, vec4(0.0));
        imageStore(img_flags, q, uvec4(FLAG_OUT_OF_BOUNDS, 0u, 0u, 0u));
        return;
    }
    // Reference 3x3 statistics, clamp-to-edge taps.
    float refMean[3];
    float refVar[3];
    refMean[0] = 0.0; refMean[1] = 0.0; refMean[2] = 0.0;
    refVar[0] = 0.0; refVar[1] = 0.0; refVar[2] = 0.0;
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            ivec2 t = ivec2(clampTap(vec2(q) + vec2(float(j), float(i))));
            vec4 ref = texelFetch(u_ref_lin, t, 0);
            refMean[0] += ref.r; refMean[1] += ref.g; refMean[2] += ref.b;
            refVar[0] += ref.r * ref.r; refVar[1] += ref.g * ref.g; refVar[2] += ref.b * ref.b;
        }
    }
    for (int c = 0; c < 3; c++) {
        refMean[c] /= 9.0;
        refVar[c] = max(refVar[c] / 9.0 - refMean[c] * refMean[c], 0.0);
    }
    // Dogson-biquadratic warp of the moving 3x3 means (reference
    // cuda_warp_dogson): rounded, edge-clamped 3x3 window, normalized by the
    // summed weights. GLSL round() is half-to-even, like the reference.
    ivec2 rc = ivec2(round(center));
    float wAcc = 0.0;
    float warped[3];
    warped[0] = 0.0; warped[1] = 0.0; warped[2] = 0.0;
    for (int i = -1; i <= 1; i++) {
        int yy = clamp(rc.y + i, 0, u_size.y - 1);
        float wy = dogsonQuadratic(float(yy) - center.y);
        for (int j = -1; j <= 1; j++) {
            int xx = clamp(rc.x + j, 0, u_size.x - 1);
            float w = wy * dogsonQuadratic(float(xx) - center.x);
            vec3 m = movMeanAt(ivec2(xx, yy));
            warped[0] += m.x * w; warped[1] += m.y * w; warped[2] += m.z * w;
            wAcc += w;
        }
    }
    warped[0] /= wAcc; warped[1] /= wAcc; warped[2] /= wAcc;
    float e0 = refMean[0] - warped[0];
    float e1 = refMean[1] - warped[1];
    float e2 = refMean[2] - warped[2];
    float d2 = e0 * e0 + e1 * e1 + e2 * e2;
    float sigma2 = refVar[0] + refVar[1] + refVar[2];
    // Measured noise correction (oracle twin): sigma2 floors at the LUT
    // value and d2 shrinks by (d2/(d2+dLut))^2 at the measured reference
    // brightness.
    if (u_lut_enabled != 0) {
        float brightness = clamp((refMean[0] + refMean[1] + refMean[2]) / 3.0, 0.0, 1.0);
        int lutIdx = int(clamp(floor(brightness * float(u_lut_bins - 1) + 0.5), 0.0, float(u_lut_bins - 1)));
        vec2 lut = texelFetch(u_lut, ivec2(lutIdx, 0), 0).rg;
        sigma2 = max(sigma2, lut.x);
        if (d2 > 0.0) {
            float shrink = d2 / (d2 + lut.y);
            d2 *= shrink * shrink;
        }
    }
    // Reference cuda_compute_s: 3x3 tile flow spread over all in-bounds
    // tiles, reliability-blind. Non-finite neighbours are missing data and
    // skipped; with no finite tile the verdict is irregular.
    bool irregular = true;
    {
        float minX = 1e30;
        float minY = 1e30;
        float maxX = -1e30;
        float maxY = -1e30;
        bool ok = false;
        for (int k = 0; k < 9; k++) {
            ivec2 t = tile + ivec2(k % 3 - 1, k / 3 - 1);
            if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, u_tile_grid))) continue;
            vec4 g = texelFetch(u_flow, t, 0);
            if (!finite(g.x) || !finite(g.y)) continue;
            ok = true;
            minX = min(minX, g.x); minY = min(minY, g.y);
            maxX = max(maxX, g.x); maxY = max(maxY, g.y);
        }
        if (ok) {
            float sx = maxX - minX;
            float sy = maxY - minY;
            irregular = sx * sx + sy * sy > u_mth_quad * u_mth_quad;
        }
    }
    float scale = irregular ? u_s1 : u_s2;
    float value = clamp(scale * exp(-d2 / sigma2) - u_t, 0.0, 1.0);
    if (!finite(value)) value = 0.0;
    imageStore(img_r, q, vec4(value));
    imageStore(img_flags, q, uvec4(0u, 0u, 0u, 0u));
}
