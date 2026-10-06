#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// Jamy-L Alg. 5 kernel covariance per Bayer-quad pixel
// (`kernels.py::estimate_kernels`, `linalg.py`): separable 2x2 gradients, 2x2
// structure-tensor window, analytic eigendecomposition, kernel radii from the
// resolved tuning, packed 2x2 COVARIANCE output. One invocation per quad
// pixel; no per-pixel host objects. Packing: RGBA texel
// (c00, c01, c10, c11), read back as mat2(v.x, v.y, v.z, v.w). The merge
// interpolates this field and inverts per pixel (Alg. 4); this pass never
// pre-inverts. Mirrors RawSrKernelCovariance exactly.
// u_kernel_type: 0 steerable, 1 iso (covariance = kDetail, Jamy-L quirk).
// u_selection_law: 0 linear (reference default), 1 hard threshold (A > 1.95, strict).
// u_use_flat: 0 coupled (flat radius = kDetail * kDenoise, reference), 1 decoupled
// (flat radius = u_k_flat; edges stay kDetail-sharp). Mirrors RawSrKernelCovariance.
// u_detail_floor: minimum radius along either axis (0 = off, reference). Keeps
// razor across-edge settings from latching onto single taps (zipper).
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_gray;
uniform ivec2 u_size;
uniform float u_k_detail;
uniform float u_k_denoise;
uniform float u_k_flat;
uniform int u_use_flat;
uniform float u_detail_floor;
uniform float u_d_th;
uniform float u_d_tr;
uniform float u_k_stretch;
uniform float u_k_shrink;
uniform int u_kernel_type;
uniform int u_selection_law;
layout(binding = 0, rgba32f) writeonly uniform highp image2D img_cov;
bool finite(float x) { return !isnan(x) && !isinf(x); }
// Wronski 2x2 gradient at grad position g; (0,0) outside the valid (size-1) range,
// which adds nothing to the structure-tensor outer-product sum.
vec2 gradientAt(ivec2 g) {
    if (g.x < 0 || g.y < 0 || g.x + 1 >= u_size.x || g.y + 1 >= u_size.y) return vec2(0.0);
    float a = texelFetch(u_gray, g, 0).r;
    float b = texelFetch(u_gray, g + ivec2(1, 0), 0).r;
    float c = texelFetch(u_gray, g + ivec2(0, 1), 0).r;
    float d = texelFetch(u_gray, g + ivec2(1, 1), 0).r;
    return vec2(0.25 * (-a + b - c + d), 0.25 * (-a - b + c + d));
}
void main() {
    ivec2 p = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(p, u_size))) return;
    float t00 = 0.0;
    float t01 = 0.0;
    float t11 = 0.0;
    for (int i = 0; i < 2; i++) {
        for (int j = 0; j < 2; j++) {
            vec2 g = gradientAt(p - 1 + ivec2(j, i));
            t00 += g.x * g.x;
            t01 += g.x * g.y;
            t11 += g.y * g.y;
        }
    }
    float kIso = u_k_detail * u_k_denoise;
    if (u_use_flat == 1) {
        kIso = u_k_flat;
    }
    float k1Sq = kIso * kIso;
    float k2Sq = k1Sq;
    vec2 e1 = vec2(1.0, 0.0);
    vec2 e2 = vec2(0.0, 1.0);
    if (u_kernel_type == 1) {
        // ISO: covariance is kDetail on the diagonal (Jamy-L quirk: linear,
        // not squared; kDenoise ignored), exactly like the oracle fast path.
        k1Sq = u_k_detail;
        k2Sq = u_k_detail;
    } else if (finite(t00) && finite(t01) && finite(t11)) {
        float b = -(t00 + t11);
        float c = t00 * t11 - t01 * t01;
        float root = sqrt(max(b * b - 4.0 * c, 0.0));
        float r1 = (-b + root) * 0.5;
        float r2 = (-b - root) * 0.5;
        float l1 = r1;
        float l2 = r2;
        if (abs(r2) > abs(r1)) {
            l1 = r2;
            l2 = r1;
        }
        if (t01 != 0.0 || t00 != t11) {
            // Reference get_eigen_vect_2x2 verbatim: the major axis is the
            // (T - l2*I)*(1,1) residual off the smaller-magnitude eigenvalue.
            vec2 v = vec2(t00 + t01 - l2, t01 + t11 - l2);
            if (v.x == 0.0) {
                e1 = vec2(0.0, 1.0);
                e2 = vec2(1.0, 0.0);
            } else if (v.y == 0.0) {
                e1 = vec2(1.0, 0.0);
                e2 = vec2(0.0, 1.0);
            } else {
                e1 = v / length(v);
                e2 = vec2(-e1.y * sign(e1.x), abs(e1.x));
            }
        }
        // Exact-flat stabilization: 0/0 anisotropy is isotropic, matching the oracle.
        float ratio = (l1 - l2) / (l1 + l2);
        float anisotropy = 1.0 + (ratio >= 0.0 ? sqrt(ratio) : 0.0);
        float detail = l1 > 0.0 ? sqrt(l1) : 0.0;
        float denoise = clamp(1.0 - detail / u_d_tr + u_d_th, 0.0, 1.0);
        float axis1;
        float axis2;
        if (u_selection_law == 1) {
            if (anisotropy > 1.95) {
                axis1 = 1.0 / u_k_shrink;
                axis2 = u_k_stretch;
            } else {
                axis1 = 1.0;
                axis2 = 1.0;
            }
        } else {
            axis1 = (2.0 - anisotropy) + (anisotropy - 1.0) / u_k_shrink;
            axis2 = (2.0 - anisotropy) + (anisotropy - 1.0) * u_k_stretch;
        }
        float k1 = u_k_detail * ((1.0 - denoise) * axis1 + denoise * u_k_denoise);
        float k2 = u_k_detail * ((1.0 - denoise) * axis2 + denoise * u_k_denoise);
        if (u_use_flat == 1) {
            k1 = (1.0 - denoise) * u_k_detail * axis1 + denoise * u_k_flat;
            k2 = (1.0 - denoise) * u_k_detail * axis2 + denoise * u_k_flat;
        }
        k1 = max(k1, u_detail_floor);
        k2 = max(k2, u_detail_floor);
        if (finite(k1) && finite(k2) && k1 > 0.0 && k2 > 0.0) {
            k1Sq = k1 * k1;
            k2Sq = k2 * k2;
        } else {
            e1 = vec2(1.0, 0.0);
            e2 = vec2(0.0, 1.0);
        }
    }
    vec4 packed = vec4(
        k1Sq * e1.x * e1.x + k2Sq * e2.x * e2.x,
        k1Sq * e1.x * e1.y + k2Sq * e2.x * e2.y,
        k1Sq * e1.x * e1.y + k2Sq * e2.x * e2.y,
        k1Sq * e1.y * e1.y + k2Sq * e2.y * e2.y);
    imageStore(img_cov, p, packed);
}
