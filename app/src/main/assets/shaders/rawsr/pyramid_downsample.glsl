// SPDX-License-Identifier: GPL-3.0-or-later
precision highp float;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_source;
uniform ivec2 u_size;
layout(binding = 0, r32f) writeonly uniform highp image2D img_level;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    ivec2 q = p * 2;
    float value = texelFetch(u_source, q, 0).r + texelFetch(u_source, q + ivec2(1, 0), 0).r
        + texelFetch(u_source, q + ivec2(0, 1), 0).r + texelFetch(u_source, q + ivec2(1), 0).r;
    imageStore(img_level, p, vec4(value * 0.25));
}
