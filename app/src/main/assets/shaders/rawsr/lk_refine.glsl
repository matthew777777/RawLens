// SPDX-License-Identifier: GPL-3.0-or-later
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 1, local_size_y = 1) in;
uniform sampler2D u_reference;
uniform sampler2D u_moving;
uniform sampler2D u_flow;
uniform ivec2 u_size;
uniform ivec2 u_tile_grid;
uniform int u_tile_size;
uniform int u_iterations;
uniform float u_min_determinant;
uniform float u_max_residual;
layout(binding = 0, rgba32f) writeonly uniform highp image2D img_refined;
const int MAX_TILE = 32;
const int MAX_ITERATIONS = 6;
float sampleMoving(vec2 p) {
    ivec2 p0 = ivec2(floor(p));
    ivec2 p1 = min(p0 + ivec2(1), u_size - 1);
    vec2 f = p - vec2(p0);
    float top = mix(texelFetch(u_moving, p0, 0).r, texelFetch(u_moving, ivec2(p1.x, p0.y), 0).r, f.x);
    float bottom = mix(texelFetch(u_moving, ivec2(p0.x, p1.y), 0).r, texelFetch(u_moving, p1, 0).r, f.x);
    return mix(top, bottom, f.y);
}
void main() {
    ivec2 tile = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(tile, u_tile_grid))) return;
    ivec2 origin = tile * u_tile_size;
    vec2 flow = texelFetch(u_flow, tile, 0).xy;
    float determinant = 0.0;
    for (int iteration = 0; iteration < MAX_ITERATIONS; ++iteration) {
        if (iteration >= u_iterations) break;
        float hxx = 0.0, hxy = 0.0, hyy = 0.0, bx = 0.0, by = 0.0;
        for (int y = 0; y < MAX_TILE; ++y) {
            if (y >= u_tile_size) break;
            for (int x = 0; x < MAX_TILE; ++x) {
                if (x >= u_tile_size) break;
                ivec2 p = origin + ivec2(x, y);
                vec2 q = vec2(p) + flow;
                if (any(lessThanEqual(p, ivec2(0))) || any(greaterThanEqual(p, u_size - 1)) ||
                    any(lessThan(q, vec2(0.0))) || any(greaterThanEqual(q, vec2(u_size - 1)))) continue;
                float gx = 0.5 * (texelFetch(u_reference, p + ivec2(1, 0), 0).r -
                    texelFetch(u_reference, p - ivec2(1, 0), 0).r);
                float gy = 0.5 * (texelFetch(u_reference, p + ivec2(0, 1), 0).r -
                    texelFetch(u_reference, p - ivec2(0, 1), 0).r);
                float e = sampleMoving(q) - texelFetch(u_reference, p, 0).r;
                hxx += gx * gx; hxy += gx * gy; hyy += gy * gy; bx += gx * e; by += gy * e;
            }
        }
        determinant = hxx * hyy - hxy * hxy;
        if (determinant <= u_min_determinant) break;
        vec2 step = vec2(hyy * bx - hxy * by, hxx * by - hxy * bx) / determinant;
        flow -= clamp(step, vec2(-1.0), vec2(1.0));
    }
    float residual = 0.0; int count = 0;
    for (int y = 0; y < MAX_TILE; ++y) {
        if (y >= u_tile_size) break;
        for (int x = 0; x < MAX_TILE; ++x) {
            if (x >= u_tile_size) break;
            ivec2 p = origin + ivec2(x, y); vec2 q = vec2(p) + flow;
            if (all(greaterThanEqual(q, vec2(0.0))) && all(lessThan(q, vec2(u_size - 1))) &&
                all(lessThan(p, u_size))) {
                residual += abs(sampleMoving(q) - texelFetch(u_reference, p, 0).r); count++;
            }
        }
    }
    residual = count > 0 ? residual / float(count) : 3.402823e38;
    float valid = determinant > u_min_determinant && residual <= u_max_residual ? 1.0 : 0.0;
    imageStore(img_refined, tile, vec4(flow, residual, valid));
}
