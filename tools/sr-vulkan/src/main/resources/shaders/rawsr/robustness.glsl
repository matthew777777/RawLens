// SPDX-License-Identifier: GPL-3.0-or-later
// Prompt 4C robustness (IPOL Alg. 6): per-quad photometric agreement between the
// reference linear guide and the flow-warped moving 3x3 means, combined with
// alignment residual, bounds validity, saturation, and flow-irregularity scaling.
// Mirrors RawSrRobustness.evaluate gate order exactly: invalid flow, bounds,
// saturation, residual, model/photo term, static-hypothesis wrap, finite guard.
// Outputs raw R (before the 5x5 local minimum) and own-quad rejection flags.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_ref_lin;
uniform sampler2D u_mov_lin;
uniform sampler2D u_flow;
// Full-res hot-pixel masks (hot_mask.glsl output): 1 where the tap is a
// stuck-bright outlier. Quad-projected below, mirroring
// RawSrHotPixel.quadHot (any of the quad's four taps). Reference and moving
// frames carry their own masks: alignment never warps a mask.
uniform highp usampler2D u_hot_ref;
uniform highp usampler2D u_hot_mov;
uniform ivec2 u_size;
uniform ivec2 u_tile_grid;
uniform int u_tile_size;
uniform float u_t;
uniform float u_s1;
uniform float u_s2;
uniform float u_mth_quad;
uniform float u_max_residual;
uniform vec3 u_mov_alpha;
uniform vec3 u_mov_beta;
uniform int u_ref_valid;
uniform int u_mov_valid;
// Measured noise LUT: bins x 1 RGBA32F texel (R = sigma_sq, G = d_sq).
// Disabled by u_lut_enabled = 0 (a 1x1 zero dummy stays bound); bin selection
// is floor(b * (bins - 1) + 0.5), exactly the oracle's RawSrNoiseLut.sample.
uniform sampler2D u_lut;
uniform int u_lut_bins;
uniform int u_lut_enabled;
layout(binding = 0, r32f) writeonly uniform highp image2D img_r;
layout(binding = 1, r32ui) writeonly uniform highp uimage2D img_flags;
// Flag values match RawSrRobustness constants.
const uint FLAG_FLOW_UNRELIABLE = 1u;
const uint FLAG_STATIC_HYPOTHESIS = 2u;
const uint FLAG_RESIDUAL = 4u;
const uint FLAG_OUT_OF_BOUNDS = 8u;
const uint FLAG_INVALID_FLOW = 16u;
const uint FLAG_SATURATED = 32u;
const uint FLAG_PHOTO_CONFLICT = 64u;
const uint FLAG_MODEL_MISSING = 128u;
const uint FLAG_MODEL_ZERO = 256u;
const uint FLAG_HOTPIXEL = 512u;
const uint FLAG_MOTION_IRREGULAR = 2048u;
bool finite(float x) { return !isnan(x) && !isinf(x); }
// Quad projection of a full-res hot mask: hot when any of the quad's four
// taps is masked (RawSrHotPixel.quadHot twin).
float quadHot(highp usampler2D hotMap, ivec2 q) {
    ivec2 p = q * 2;
    float h = float(texelFetch(hotMap, p, 0).r);
    h = max(h, float(texelFetch(hotMap, p + ivec2(1, 0), 0).r));
    h = max(h, float(texelFetch(hotMap, p + ivec2(0, 1), 0).r));
    h = max(h, float(texelFetch(hotMap, p + ivec2(1, 1), 0).r));
    return h;
}
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
vec2 clampTap(vec2 p) { return clamp(p, vec2(0.0), vec2(u_size) - vec2(1.0)); }
// Clamp-to-edge 3x3 box mean of the moving guide at quad p: the oracle's
// movMean table. The warp below interpolates these means, never raw texels.
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
    ivec2 q = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(q, u_size))) return;
    ivec2 tile = clamp(q / u_tile_size, ivec2(0), u_tile_grid - ivec2(1));
    vec4 flow = flowSmooth(vec2(q));
    if (!finite(flow.x) || !finite(flow.y)) {
        imageStore(img_r, q, vec4(0.0));
        imageStore(img_flags, q, uvec4(FLAG_INVALID_FLOW, 0u, 0u, 0u));
        return;
    }
    bool reliable = flow.w > 0.5;
    float residualGate = reliable && flow.z > u_max_residual ? 1.0 : 0.0;
    vec2 shift = reliable ? flow.xy : vec2(0.0);
    vec2 center = vec2(q) + shift;
    if (any(lessThan(center, vec2(0.0))) || any(greaterThanEqual(center, vec2(u_size)))) {
        imageStore(img_r, q, vec4(0.0));
        imageStore(img_flags, q, uvec4(FLAG_OUT_OF_BOUNDS, 0u, 0u, 0u));
        return;
    }
    float refRail = texelFetch(u_ref_lin, q, 0).a;
    ivec2 warpQuad = ivec2(clamp(floor(center + vec2(0.5)), vec2(0.0), vec2(u_size) - vec2(1.0)));
    float movRail = texelFetch(u_mov_lin, warpQuad, 0).a;
    // Hot quads rail through the same zero-weight gate as saturated ones,
    // but keep their own flag (stuck tap vs saturated highlight — mirrors
    // the RawSrRobustness.evaluate gate). The samples themselves are
    // already inpainted upstream; this gate zeroes the quad's weight.
    float refHot = quadHot(u_hot_ref, q);
    float movHot = quadHot(u_hot_mov, warpQuad);
    if (refRail > 0.5 || movRail > 0.5 || refHot > 0.5 || movHot > 0.5) {
        uint hotFlag = (refHot > 0.5 || movHot > 0.5) ? FLAG_HOTPIXEL : FLAG_SATURATED;
        imageStore(img_r, q, vec4(0.0));
        imageStore(img_flags, q, uvec4(hotFlag, 0u, 0u, 0u));
        return;
    }
    if (residualGate > 0.5) {
        imageStore(img_r, q, vec4(0.0));
        imageStore(img_flags, q, uvec4(FLAG_RESIDUAL, 0u, 0u, 0u));
        return;
    }
    // Reference 3x3 statistics and moving 3x3 means, clamp-to-edge taps.
    float refMean[3];
    float refVar[3];
    float movMean[3];
    refMean[0] = 0.0; refMean[1] = 0.0; refMean[2] = 0.0;
    refVar[0] = 0.0; refVar[1] = 0.0; refVar[2] = 0.0;
    movMean[0] = 0.0; movMean[1] = 0.0; movMean[2] = 0.0;
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            ivec2 t = ivec2(clampTap(vec2(q) + vec2(float(j), float(i))));
            vec4 ref = texelFetch(u_ref_lin, t, 0);
            vec4 mov = texelFetch(u_mov_lin, t, 0);
            refMean[0] += ref.r; refMean[1] += ref.g; refMean[2] += ref.b;
            refVar[0] += ref.r * ref.r; refVar[1] += ref.g * ref.g; refVar[2] += ref.b * ref.b;
            movMean[0] += mov.r; movMean[1] += mov.g; movMean[2] += mov.b;
        }
    }
    for (int c = 0; c < 3; c++) {
        refMean[c] /= 9.0;
        refVar[c] = max(refVar[c] / 9.0 - refMean[c] * refMean[c], 0.0);
        movMean[c] /= 9.0;
    }
    // Bilinear warp of the moving 3x3 means with interpolation weight
    // correction (oracle movMean); corners clamp exactly like the oracle.
    ivec2 base = ivec2(floor(center));
    vec2 f = center - vec2(base);
    float w2sum = 0.0;
    float warped[3];
    warped[0] = 0.0; warped[1] = 0.0; warped[2] = 0.0;
    for (int i = 0; i <= 1; i++) {
        for (int j = 0; j <= 1; j++) {
            ivec2 c = ivec2(clamp(vec2(base) + vec2(float(j), float(i)), vec2(0.0), vec2(u_size) - vec2(1.0)));
            float w = (j == 0 ? 1.0 - f.x : f.x) * (i == 0 ? 1.0 - f.y : f.y);
            vec3 m = movMeanAt(c);
            warped[0] += m.x * w; warped[1] += m.y * w; warped[2] += m.z * w;
            w2sum += w * w;
        }
    }
    float d2 = 0.0;
    float sigma2 = 0.0;
    float e0 = refMean[0] - warped[0];
    float e1 = refMean[1] - warped[1];
    float e2 = refMean[2] - warped[2];
    d2 = e0 * e0 + e1 * e1 + e2 * e2;
    if (u_ref_valid != 0 && u_mov_valid != 0) {
        float m0 = u_mov_alpha.x * warped[0] + u_mov_beta.x;
        float m1 = u_mov_alpha.y * warped[1] + u_mov_beta.y;
        float m2 = u_mov_alpha.z * warped[2] + u_mov_beta.z;
        sigma2 = refVar[0] + refVar[1] + refVar[2]
            + w2sum * (max(m0, 0.0) + max(m1, 0.0) + max(m2, 0.0)) / 9.0;
        // Measured noise correction (oracle twin): sigma2 floors at the LUT
        // value and d2 shrinks by (d2/(d2+dLut))^2 at the measured reference
        // brightness. Applies before the sigma2 <= 0 branch below, like the
        // oracle, and only when both models are valid (this block).
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
    }
    uint flag = 0u;
    float value;
    if (u_ref_valid == 0 || u_mov_valid == 0) {
        value = 1.0;
        flag = FLAG_MODEL_MISSING;
    } else if (sigma2 <= 0.0) {
        value = d2 == 0.0 ? 1.0 : 0.0;
        if (d2 != 0.0) flag = FLAG_PHOTO_CONFLICT;
    } else {
        // 3x3 tile flow spread, in-bounds RELIABLE tiles only; nonfinite
        // poisons (conservative: scales weights down, never forfeits).
        // Unreliable tiles carry garbage flow and must not poison their
        // neighbours' verdict — they are judged by their own per-quad
        // reliability gate instead (oracles twins in flowIrregular).
        bool irregular = true;
        {
            float minX = 1e30;
            float minY = 1e30;
            float maxX = -1e30;
            float maxY = -1e30;
            bool ok = false;
            bool poisoned = false;
            for (int k = 0; k < 9; k++) {
                ivec2 t = tile + ivec2(k % 3 - 1, k / 3 - 1);
                if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, u_tile_grid))) continue;
                vec4 g = texelFetch(u_flow, t, 0);
                if (!finite(g.x) || !finite(g.y)) {
                    poisoned = true;
                    break;
                }
                if (g.w < 0.5) continue;
                ok = true;
                minX = min(minX, g.x); minY = min(minY, g.y);
                maxX = max(maxX, g.x); maxY = max(maxY, g.y);
            }
            if (ok && !poisoned) {
                float sx = maxX - minX;
                float sy = maxY - minY;
                irregular = sx * sx + sy * sy > u_mth_quad * u_mth_quad;
            }
        }
        float scale = (!reliable || irregular) ? u_s1 : u_s2;
        value = clamp(scale * exp(-d2 / sigma2) - u_t, 0.0, 1.0);
        if (value == 0.0) flag = FLAG_PHOTO_CONFLICT;
        // Reliable-but-irregular tiles merge under the motion scale: mark
        // them for device-side support attribution (mirrors the oracle).
        if (reliable && irregular) flag = flag | FLAG_MOTION_IRREGULAR;
    }
    if (!reliable) {
        if (value > 0.0) {
            flag = flag | FLAG_STATIC_HYPOTHESIS;
        } else {
            flag = (flag & ~FLAG_PHOTO_CONFLICT) | FLAG_FLOW_UNRELIABLE;
            value = 0.0;
        }
    }
    if (!finite(value)) {
        value = 0.0;
        flag = flag | FLAG_PHOTO_CONFLICT;
    }
    imageStore(img_r, q, vec4(value));
    imageStore(img_flags, q, uvec4(flag, 0u, 0u, 0u));
}
