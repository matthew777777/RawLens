#version 310 es
precision highp float;
precision highp int;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
layout(rgba16f, binding = 0) uniform readonly highp image2D u_rgb;
layout(rgba16f, binding = 1) uniform writeonly highp image2D u_low;
uniform ivec2 u_size;
uniform ivec2 u_source_size;
uniform ivec2 u_source_offset;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    ivec2 source = clamp(p + u_source_offset, ivec2(0), u_source_size - 1);
    vec3 rgb = imageLoad(u_rgb, source).rgb;
    // Reversible YCoCg-R lifting transform. Alpha is unused but initialized.
    float co = rgb.r - rgb.b;
    float t = rgb.b + 0.5 * co;
    float cg = rgb.g - t;
    float y = t + 0.5 * cg;
    imageStore(u_low, p, vec4(y, co, cg, 0.0));
}
