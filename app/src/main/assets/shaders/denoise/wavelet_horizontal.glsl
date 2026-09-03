#version 310 es
precision highp float;
precision highp int;
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0) uniform highp sampler2D u_input;
layout(rgba32f, binding = 0) uniform writeonly highp image2D u_output;
uniform ivec2 u_size;
uniform int u_step;

ivec2 clampP(ivec2 p) { return clamp(p, ivec2(0), u_size - 1); }
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    int s = u_step;
    vec4 v = texelFetch(u_input, clampP(p + ivec2(-2*s,0)), 0)
           + 4.0 * texelFetch(u_input, clampP(p + ivec2(-s,0)), 0)
           + 6.0 * texelFetch(u_input, p, 0)
           + 4.0 * texelFetch(u_input, clampP(p + ivec2(s,0)), 0)
           + texelFetch(u_input, clampP(p + ivec2(2*s,0)), 0);
    imageStore(u_output, p, v / 16.0);
}
