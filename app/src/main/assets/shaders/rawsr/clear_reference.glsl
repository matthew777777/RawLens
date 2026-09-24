// SPDX-License-Identifier: GPL-3.0-or-later
precision highp float;
precision highp int;
layout(local_size_x = 8, local_size_y = 8) in;
uniform ivec2 u_size;
layout(binding = 0, rgba32f) writeonly uniform highp image2D numerator;
layout(binding = 1, rgba32f) writeonly uniform highp image2D denominator;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    imageStore(numerator, p, vec4(0.0));
    imageStore(denominator, p, vec4(0.0));
}
