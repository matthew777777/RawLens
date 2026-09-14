// SPDX-License-Identifier: GPL-3.0-or-later
// Prompt 4D persistent merge state reset: RGB numerators, independent R/G/B
// denominators, the reference-only A/B accumulators, the per-pixel fallback
// mask, and the per-pixel out-of-bounds diagnostic counter. Rc is cleared by a
// zero upload on the host so its lifetime matches the robustness ping-pong.
precision highp float;
precision highp int;
layout(local_size_x = 8, local_size_y = 8) in;
uniform ivec2 u_size;
layout(binding = 0, rgba32f) writeonly uniform highp image2D numerator;
layout(binding = 1, rgba32f) writeonly uniform highp image2D denominator;
layout(binding = 2, rgba32f) writeonly uniform highp image2D ref_numerator;
layout(binding = 3, rgba32f) writeonly uniform highp image2D ref_denominator;
layout(binding = 4, r32f) writeonly uniform highp image2D fallback;
layout(binding = 5, r32f) writeonly uniform highp image2D oob;
layout(binding = 6, rgba32f) writeonly uniform highp image2D near_numerator;
layout(binding = 7, rgba32f) writeonly uniform highp image2D near_denominator;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    imageStore(numerator, p, vec4(0.0));
    imageStore(denominator, p, vec4(0.0));
    imageStore(ref_numerator, p, vec4(0.0));
    imageStore(ref_denominator, p, vec4(0.0));
    imageStore(fallback, p, vec4(0.0));
    imageStore(oob, p, vec4(0.0));
    imageStore(near_numerator, p, vec4(0.0));
    imageStore(near_denominator, p, vec4(0.0));
}
