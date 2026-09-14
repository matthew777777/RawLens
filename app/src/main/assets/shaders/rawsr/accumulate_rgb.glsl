// SPDX-License-Identifier: GPL-3.0-or-later
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_rgb;
uniform sampler2D u_flow;
uniform sampler2D u_numerator;
uniform sampler2D u_denominator;
uniform ivec2 u_size;
uniform ivec2 u_tile_grid;
uniform int u_tile_size;
uniform int u_reference_only;
layout(binding = 0, rgba32f) writeonly uniform highp image2D img_numerator;
layout(binding = 1, r32f) writeonly uniform highp image2D img_denominator;
vec3 sampleRgb(vec2 p) {
    ivec2 p0 = ivec2(floor(p));
    ivec2 p1 = min(p0 + ivec2(1), u_size - 1);
    vec2 f = p - vec2(p0);
    vec3 top = mix(texelFetch(u_rgb, p0, 0).rgb,
        texelFetch(u_rgb, ivec2(p1.x, p0.y), 0).rgb, f.x);
    vec3 bottom = mix(texelFetch(u_rgb, ivec2(p0.x, p1.y), 0).rgb,
        texelFetch(u_rgb, p1, 0).rgb, f.x);
    return mix(top, bottom, f.y);
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    ivec2 tile = clamp((p / 2) / u_tile_size, ivec2(0), u_tile_grid - 1);
    vec4 packedFlow = texelFetch(u_flow, tile, 0);
    vec2 flow = u_reference_only != 0 ? vec2(0.0) : packedFlow.xy * 2.0;
    float valid = u_reference_only != 0 ? 1.0 : packedFlow.w;
    vec2 q = vec2(p) + flow;
    bool inside = all(greaterThanEqual(q, vec2(0.0))) && all(lessThan(q, vec2(u_size - 1)));
    vec4 numerator = texelFetch(u_numerator, p, 0);
    float denominator = texelFetch(u_denominator, p, 0).r;
    if (valid > 0.5 && inside) {
        vec3 rgb = sampleRgb(q);
        numerator.rgb += rgb; denominator += 1.0;
    }
    imageStore(img_numerator, p, numerator);
    imageStore(img_denominator, p, vec4(denominator));
}
