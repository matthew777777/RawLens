#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// Unblocker fold (Sabre `weight = min(1 - unblocker, frame_weight)`
// analogue). One invocation per quad on the quad grid: caps the
// robustness by the keep-weight AND applies the contested-warp veto,
// ahead of the second 5x5 local minimum (the fold rides its own spread,
// mirroring the CPU `applyToFrameAndSpread`; the verdict judge ran on
// the pre-fold field, so frame selection is unchanged). Mirrors
// RawSrUnblocker.applyToFrame exactly:
// r' = 0 where the 3x3 tile flow spread exceeds u_mth while the clamped
// weight is below u_veto_u (contested ghosts contribute nothing — the
// bend would render at any partial weight); otherwise
// r' = min(r, clamp(u, 0, 1)) (the keep-weight caps agreement instead of
// compounding it). Non-finite r or NaN u sanitize to 0. Flags OR
// u_unblocked where a finite weight attenuates (vetoed quads always
// qualify: u_veto_u < u_unblocked).
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
precision highp sampler2D;
precision highp image2D;
uniform sampler2D u_r;
uniform sampler2D u_u;
uniform usampler2D u_flags_in;
uniform sampler2D u_flow;
uniform ivec2 u_size;
uniform ivec2 u_tile_grid;
uniform int u_tile_size;
uniform float u_mth;
uniform float u_veto_u;
uniform int u_unblocked;
layout(binding = 0, r32f) writeonly uniform highp image2D img_r;
layout(binding = 1, r32ui) writeonly uniform highp uimage2D img_flags;
bool finite(float v) { return v == v && v != 1.0 / 0.0 && v != -1.0 / 0.0; }
void main() {
    ivec2 q = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(q, u_size))) return;
    float r = texelFetch(u_r, q, 0).r;
    float wRaw = texelFetch(u_u, q, 0).r;
    uint flags = texelFetch(u_flags_in, q, 0).r;
    // NaN (unknown attenuation) maps to full weight upstream of the cap:
    // min(r, 0) would zero trusting pixels, while the CPU coerce keeps NaN
    // out of the comparison and sanitizes the product to 0. Both read 0
    // with no flag; only the veto guard below must exclude NaN explicitly.
    float w = isnan(wRaw) ? 0.0 : clamp(wRaw, 0.0, 1.0);
    float m = (!finite(r) || isnan(wRaw)) ? 0.0 : min(r, w);
    // Contested-warp veto (RawSrCoreRobustness.flowIrregular twin):
    // 3x3 tile spread over all in-bounds tiles, reliability-blind.
    // Non-finite neighbours are skipped; with no finite tile the verdict
    // is irregular (matches the CPU: an unusable warp is contested).
    ivec2 tile = clamp((2 * q + ivec2(1)) / u_tile_size, ivec2(0), u_tile_grid - ivec2(1));
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
            irregular = sx * sx + sy * sy > u_mth * u_mth;
        }
    }
    if (irregular && !isnan(wRaw) && w < u_veto_u) {
        m = 0.0;
    }
    imageStore(img_r, q, vec4(m));
    // 0.999 = UNBLOCKED_THRESHOLD (diagnostic only; vetoed quads always
    // qualify). u_unblocked carries the flag BITS, not the threshold.
    if (!isnan(wRaw) && w < 0.999) {
        flags = flags | uint(u_unblocked);
    }
    imageStore(img_flags, q, uvec4(flags, 0u, 0u, 0u));
}
