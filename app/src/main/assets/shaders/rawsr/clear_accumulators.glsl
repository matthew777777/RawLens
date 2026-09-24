// SPDX-License-Identifier: GPL-3.0-or-later
// Moving-frame and nearest-support accumulators plus the OOB diagnostic counter.
// Reference-only accumulators are allocated/cleared just before the reference pass.
// Final outputs are fully overwritten by merge_finalize and need no clear.
precision highp float;
precision highp int;
layout(local_size_x = 8, local_size_y = 8) in;
uniform ivec2 u_size;
layout(binding = 0, rgba32f) writeonly uniform highp image2D numerator;
layout(binding = 1, rgba32f) writeonly uniform highp image2D denominator;
layout(binding = 5, r32f) writeonly uniform highp image2D oob;
layout(binding = 6, rgba32f) writeonly uniform highp image2D near_numerator;
layout(binding = 7, rgba32f) writeonly uniform highp image2D near_denominator;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    imageStore(numerator, p, vec4(0.0));
    imageStore(denominator, p, vec4(0.0));
    imageStore(oob, p, vec4(0.0));
    imageStore(near_numerator, p, vec4(0.0));
    imageStore(near_denominator, p, vec4(0.0));
}
