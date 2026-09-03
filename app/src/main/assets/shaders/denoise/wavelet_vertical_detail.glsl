#version 310 es
precision highp float;
precision highp int;
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0) uniform highp sampler2D u_horizontal;
layout(binding = 1) uniform highp sampler2D u_fine;
layout(rgba32f, binding = 0) uniform writeonly highp image2D u_coarse;
layout(rgba32f, binding = 1) uniform writeonly highp image2D u_detail;
uniform ivec2 u_size;
uniform int u_step;

ivec2 clampP(ivec2 p) { return clamp(p, ivec2(0), u_size - 1); }
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    int s = u_step;
    vec4 c = texelFetch(u_horizontal, clampP(p + ivec2(0,-2*s)), 0)
           + 4.0 * texelFetch(u_horizontal, clampP(p + ivec2(0,-s)), 0)
           + 6.0 * texelFetch(u_horizontal, p, 0)
           + 4.0 * texelFetch(u_horizontal, clampP(p + ivec2(0,s)), 0)
           + texelFetch(u_horizontal, clampP(p + ivec2(0,2*s)), 0);
    c /= 16.0;
    vec4 f = texelFetch(u_fine, p, 0);
    imageStore(u_coarse, p, c);
    imageStore(u_detail, p, f - c);
}
