// SPDX-License-Identifier: GPL-3.0-or-later
// Prompt 4B: Wronski/IPOL anisotropic kernel precision per Bayer-quad pixel.
// Mirrors RawSrKernelCovariance (Jamy-L Alg. 5): separable 2x2 gradients, 2x2
// structure-tensor window, analytic eigendecomposition, kernel radii from the
// resolved tuning, packed 2x2 precision output. One invocation per quad pixel;
// no per-pixel host objects. Packing matches the SkyKing alterCov consumer:
// RGBA texel (p00, p01, p10, p11) read back as mat2(v.x, v.y, v.z, v.w).
// u_kernel_type: 0 steerable, 1 iso (covariance = kDetail, Jamy-L quirk).
// u_selection_law: 0 linear, 1 hard threshold (A > 1.95, strict).
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_gray;
uniform ivec2 u_size;
uniform float u_k_detail;
uniform float u_k_denoise;
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
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
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
            // Stable major-axis solve mirroring the oracle: v carries O(|T|)
            // components, never a small residual of cancelled giants.
            // Cancellation-free row selection (4E precision fix, mirror of the
            // oracle): the (l1-t11, t01) row is the same eigenvector in exact
            // arithmetic; the larger row keeps O(|T|) components on both paths.
            vec2 v = abs(l1 - t11) >= abs(l1 - t00) ? vec2(l1 - t11, t01) : vec2(t01, l1 - t00);
            if (v.x == 0.0 && v.y == 0.0) {
                e1 = vec2(1.0, 0.0);
                e2 = vec2(0.0, 1.0);
            } else if (v.x == 0.0) {
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
        if (finite(k1) && finite(k2) && k1 > 0.0 && k2 > 0.0) {
            k1Sq = k1 * k1;
            k2Sq = k2 * k2;
        } else {
            e1 = vec2(1.0, 0.0);
            e2 = vec2(0.0, 1.0);
        }
    }
    float i1 = 1.0 / k1Sq;
    float i2 = 1.0 / k2Sq;
    vec4 packed = vec4(
        i1 * e1.x * e1.x + i2 * e2.x * e2.x,
        i1 * e1.x * e1.y + i2 * e2.x * e2.y,
        i1 * e1.x * e1.y + i2 * e2.x * e2.y,
        i1 * e1.y * e1.y + i2 * e2.y * e2.y);
    imageStore(img_cov, p, packed);
}
