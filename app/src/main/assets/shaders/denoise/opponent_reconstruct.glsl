#version 310 es
precision highp float;
precision highp int;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
layout(rgba32f, binding = 0) uniform readonly highp image2D u_residue;
layout(rgba32f, binding = 1) uniform readonly highp image2D u_accum;
layout(rgba16f, binding = 2) uniform writeonly highp image2D u_rgb;
uniform ivec2 u_inner_offset;
uniform ivec2 u_output_offset;
uniform ivec2 u_output_size;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_output_size))) return;
    ivec2 q = p + u_inner_offset;
    vec3 v = imageLoad(u_residue, q).rgb + imageLoad(u_accum, q).rgb;
    float y=v.x, u=v.y, vv=v.z;
    vec3 rgb = vec3(y + u + (2.0/3.0)*vv,
                    y - (4.0/3.0)*vv,
                    y - u + (2.0/3.0)*vv);
    imageStore(u_rgb, p + u_output_offset, vec4(rgb, 1.0));
}
