#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// HDR bracket block extremes: per-2x2-cell 3x3 max/min twin of the
// MergeSession block loop (darktable control_jobs.c border rule). One
// invocation per cell of the (w/2)x(h/2) grid. Cells past width/height-2
// contribute no envelope: they keep the fresh-buffer defaults (max 0,
// min +FLT_MAX), so the clip branch and the clipped-winner bookkeeping in
// hdr_accumulate agree per cell exactly like the CPU path.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_reg;
uniform ivec2 u_cells;
uniform ivec2 u_full;
layout(binding = 0, r32f) writeonly uniform highp image2D img_max;
layout(binding = 1, r32f) writeonly uniform highp image2D img_min;
void main() {
    ivec2 c = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(c, u_cells))) return;
    int ox = c.x * 2;
    int oy = c.y * 2;
    float mx = 0.0;
    float mn = 3.402823466e+38;
    if (ox < u_full.x - 2 && oy < u_full.y - 2) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = 0; dx <= 2; dx++) {
                float v = texelFetch(u_reg, ivec2(ox + dx, oy + dy), 0).r;
                mx = max(mx, v);
                mn = min(mn, v);
            }
        }
    }
    imageStore(img_max, c, vec4(mx));
    imageStore(img_min, c, vec4(mn));
}
