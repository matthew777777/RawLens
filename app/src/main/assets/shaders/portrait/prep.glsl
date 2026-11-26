// Full-res disparity from the colour guided-filter coefficients (PortraitDepth.
// colorGuidedCoefficients): d = a . rgb + b, rgb = 3x3-smoothed display encoding
// of this pixel (matches the area-averaged thumbnail the CPU fitted against).
// Coefficients are RGBA32F (a can be large, fp16 would cancel in a.rgb + b) and
// bilinear-filtered by hand (float-linear filtering is optional in ES 3.1).
layout(local_size_x = 16, local_size_y = 16) in;
layout(rgba16f, binding = 1) readonly uniform image2D u_scene;
uniform highp sampler2D u_coef;   // RGBA32F a_r a_g a_b b, NEAREST
layout(rgba16f, binding = 0) writeonly uniform image2D u_img;
uniform ivec2 u_size;
uniform int u_csize;
shared vec3 tile[18 * 18];

vec4 coefAt(vec2 p) {
    vec2 q = clamp(p, vec2(0.0), vec2(float(u_csize - 1)));
    ivec2 i0 = min(ivec2(q), ivec2(u_csize - 2));
    vec2 f = q - vec2(i0);
    vec4 c00 = texelFetch(u_coef, i0, 0);
    vec4 c10 = texelFetch(u_coef, i0 + ivec2(1, 0), 0);
    vec4 c01 = texelFetch(u_coef, i0 + ivec2(0, 1), 0);
    vec4 c11 = texelFetch(u_coef, i0 + ivec2(1, 1), 0);
    return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
}

void main() {
    ivec2 base = ivec2(gl_WorkGroupID.xy) * 16 - 1;
    for (uint k = gl_LocalInvocationIndex; k < 324u; k += 256u) {
        ivec2 q = clamp(base + ivec2(int(k) % 18, int(k) / 18), ivec2(0), u_size - 1);
        tile[k] = portraitDisplay(imageLoad(u_scene, q).rgb);
    }
    barrier();
    ivec2 g = ivec2(gl_GlobalInvocationID.xy);
    if (g.x >= u_size.x || g.y >= u_size.y) return;
    ivec2 l = ivec2(gl_LocalInvocationID.xy) + 1;
    vec3 c = vec3(0.0);
    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) c += tile[(l.y + dy) * 18 + l.x + dx];
    vec4 ab = coefAt((vec2(g) + 0.5) * float(u_csize) / vec2(u_size) - 0.5);
    float d = clamp(dot(ab.rgb, c / 9.0) + ab.a, 0.0, 1.0);
    imageStore(u_img, g, vec4(imageLoad(u_scene, g).rgb, d));
}
