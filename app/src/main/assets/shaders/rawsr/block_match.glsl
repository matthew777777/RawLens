// SPDX-License-Identifier: GPL-3.0-or-later
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 1, local_size_y = 1) in;
uniform sampler2D u_reference;
uniform sampler2D u_moving;
uniform sampler2D u_initial_flow;
uniform ivec2 u_size;
uniform ivec2 u_tile_grid;
uniform ivec2 u_previous_grid;
uniform int u_tile_size;
uniform int u_search_radius;
uniform int u_has_initial_flow;
layout(binding = 0, rgba32f) writeonly uniform highp image2D img_flow;
const int MAX_TILE = 32;
const int MAX_RADIUS = 6;
void main() {
    ivec2 tile = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(tile, u_tile_grid))) return;
    ivec2 origin = tile * u_tile_size;
    ivec2 end = min(origin + ivec2(u_tile_size), u_size);
    ivec2 priorTile = clamp(ivec2((vec2(tile) + vec2(0.5)) *
        vec2(u_previous_grid) / vec2(u_tile_grid)), ivec2(0), u_previous_grid - 1);
    vec2 initial = u_has_initial_flow != 0 ? texelFetch(u_initial_flow, priorTile, 0).xy * 2.0 : vec2(0.0);
    // Kotlin Float.toInt and GLSL float-to-int conversion both truncate toward zero.
    ivec2 base = ivec2(initial);
    float bestError = 3.402823e38;
    ivec2 best = base;
    for (int oy = -MAX_RADIUS; oy <= MAX_RADIUS; ++oy) {
        if (abs(oy) > u_search_radius) continue;
        for (int ox = -MAX_RADIUS; ox <= MAX_RADIUS; ++ox) {
            if (abs(ox) > u_search_radius) continue;
            float error = 0.0;
            int count = 0;
            for (int y = 0; y < MAX_TILE; ++y) {
                if (origin.y + y >= end.y) break;
                for (int x = 0; x < MAX_TILE; ++x) {
                    if (origin.x + x >= end.x) break;
                    ivec2 p = origin + ivec2(x, y);
                    ivec2 q = p + base + ivec2(ox, oy);
                    if (all(greaterThanEqual(p, ivec2(0))) && all(lessThan(p, u_size)) &&
                        all(greaterThanEqual(q, ivec2(0))) && all(lessThan(q, u_size))) {
                        float d = texelFetch(u_moving, q, 0).r - texelFetch(u_reference, p, 0).r;
                        error += d * d; count++;
                    }
                }
            }
            float score = count >= 4 ? error / float(count) : 3.402823e38;
            if (score < bestError) { bestError = score; best = base + ivec2(ox, oy); }
        }
    }
    imageStore(img_flow, tile, vec4(vec2(best), bestError, bestError < 3.402823e38 ? 1.0 : 0.0));
}
