#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// Prompt 4C local minimum (IPOL Alg. 9): per-quad minimum of raw robustness over
// a 5x5 clamp-to-edge window. Flags are untouched: they stay own-quad while the
// weight spreads spatially.
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_raw;
uniform ivec2 u_size;
layout(binding = 0, r32f) writeonly uniform highp image2D img_r;
void main() {
    ivec2 q = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(q, u_size))) return;
    float minimum = 1e30;
    for (int i = -2; i <= 2; i++) {
        for (int j = -2; j <= 2; j++) {
            ivec2 t = ivec2(clamp(vec2(q) + vec2(float(j), float(i)), vec2(0.0), vec2(u_size) - vec2(1.0)));
            minimum = min(minimum, texelFetch(u_raw, t, 0).r);
        }
    }
    imageStore(img_r, q, vec4(minimum));
}
