#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// Dead-lane inpaint for the Linear-RGB product (RawSrCoreFinish.inpaint
// twin): output lanes with no kernel support (total denominator at or below
// the exact-zero gate, EPS = 0.0 like RawSrBayerMerge.EPS) take the mean of
// live same-lane neighbours over rings 1..3; lanes with support pass
// through untouched, and cells with no live neighbour in range keep the
// finalize value (0). Ring enumeration and float accumulation order match
// the CPU twin exactly for bitwise agreement.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_merged;
uniform sampler2D u_den;
uniform sampler2D u_ref_den;
uniform ivec2 u_size;
layout(binding = 0, rgba32f) writeonly uniform highp image2D img_out;
const float EPS = 0.0;
const int MAX_RING = 3;
float totalDen(ivec2 p, int c) {
    vec4 d = texelFetch(u_den, p, 0) + texelFetch(u_ref_den, p, 0);
    return c == 0 ? d.x : (c == 1 ? d.y : d.z);
}
float laneOf(vec4 v, int c) {
    return c == 0 ? v.x : (c == 1 ? v.y : v.z);
}
float heal(ivec2 p, int c, float fallback) {
    for (int ring = 1; ring <= MAX_RING; ring++) {
        float sum = 0.0;
        int count = 0;
        for (int ox = -ring; ox <= ring; ox++) {
            ivec2 t0 = p + ivec2(ox, -ring);
            if (all(greaterThanEqual(t0, ivec2(0))) && all(lessThan(t0, u_size))
                && totalDen(t0, c) > EPS) {
                sum += laneOf(texelFetch(u_merged, t0, 0), c);
                count++;
            }
            ivec2 t1 = p + ivec2(ox, ring);
            if (all(greaterThanEqual(t1, ivec2(0))) && all(lessThan(t1, u_size))
                && totalDen(t1, c) > EPS) {
                sum += laneOf(texelFetch(u_merged, t1, 0), c);
                count++;
            }
        }
        for (int oy = -ring + 1; oy <= ring - 1; oy++) {
            ivec2 s0 = p + ivec2(-ring, oy);
            if (all(greaterThanEqual(s0, ivec2(0))) && all(lessThan(s0, u_size))
                && totalDen(s0, c) > EPS) {
                sum += laneOf(texelFetch(u_merged, s0, 0), c);
                count++;
            }
            ivec2 s1 = p + ivec2(ring, oy);
            if (all(greaterThanEqual(s1, ivec2(0))) && all(lessThan(s1, u_size))
                && totalDen(s1, c) > EPS) {
                sum += laneOf(texelFetch(u_merged, s1, 0), c);
                count++;
            }
        }
        if (count > 0) return sum / float(count);
    }
    return fallback;
}
void main() {
    ivec2 p = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(p, u_size))) return;
    vec4 m = texelFetch(u_merged, p, 0);
    vec4 d = texelFetch(u_den, p, 0) + texelFetch(u_ref_den, p, 0);
    float r = d.x > EPS ? m.x : heal(p, 0, m.x);
    float g = d.y > EPS ? m.y : heal(p, 1, m.y);
    float b = d.z > EPS ? m.z : heal(p, 2, m.z);
    imageStore(img_out, p, vec4(r, g, b, 1.0));
}
