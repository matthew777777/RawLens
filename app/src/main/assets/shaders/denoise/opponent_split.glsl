#version 310 es
precision highp float;
precision highp int;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
layout(rgba16f, binding = 0) uniform readonly highp image2D u_rgb;
layout(rgba32f, binding = 1) uniform writeonly highp image2D u_yuv;
layout(rgba32f, binding = 2) uniform writeonly highp image2D u_accum;
uniform ivec2 u_size;
uniform ivec2 u_source_size;
uniform ivec2 u_source_offset;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    ivec2 q = clamp(p + u_source_offset, ivec2(0), u_source_size - 1);
    vec3 rgb = imageLoad(u_rgb, q).rgb;
    // darktable denoiseprofile Y0U0V0 basis before WB/noise normalization:
    // Y0=(R+G+B)/3, U0=(R-B)/2, V0=(R-2G+B)/4.
    vec3 yuv = vec3(
        (rgb.r + rgb.g + rgb.b) / 3.0,
        0.5 * (rgb.r - rgb.b),
        0.25 * rgb.r - 0.5 * rgb.g + 0.25 * rgb.b
    );
    imageStore(u_yuv, p, vec4(yuv, 1.0));
    imageStore(u_accum, p, vec4(0.0));
}
