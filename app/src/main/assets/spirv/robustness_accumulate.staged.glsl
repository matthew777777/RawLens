#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// Prompt 4C Rc accumulation: Rc_out = Rc_in + r_n, exactly once per quad for
// each accepted non-reference frame. Rejected quads carry r = 0 and contribute
// nothing. 1 + Rc is a robustness-based frame-support estimate for diagnostics,
// not a statistically exact effective sample count.
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_rc;
uniform sampler2D u_r;
uniform ivec2 u_size;
layout(binding = 0, r32f) writeonly uniform highp image2D img_rc;
void main() {
    ivec2 q = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(q, u_size))) return;
    float rc = texelFetch(u_rc, q, 0).r + texelFetch(u_r, q, 0).r;
    imageStore(img_rc, q, vec4(rc));
}
