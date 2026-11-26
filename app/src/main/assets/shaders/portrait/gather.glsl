// One depth layer at one pyramid level: premultiply by the layer's tent weight,
// then an anti-aliased disc gather (radius <= RMAX texels) with a cat's-eye cut.
layout(local_size_x = 16, local_size_y = 16) in;
#define RMAX 3
#define T (16 + 2 * RMAX)
uniform sampler2D u_img;    // rgb + disparity in alpha, full mip chain
layout(rgba16f, binding = 0) writeonly uniform image2D u_dst;
uniform ivec2 u_size;       // this level
uniform int u_level;
uniform float u_r;          // radius in this level's texels
uniform vec3 u_tent;        // centre, lower neighbour, upper neighbour (disparity)
uniform float u_cat;
shared uvec2 tile[T * T];
shared uint anyw;
float tentWeight(float d) {
    return d < u_tent.x ? clamp((d - u_tent.y) / (u_tent.x - u_tent.y), 0.0, 1.0)
                        : clamp((u_tent.z - d) / (u_tent.z - u_tent.x), 0.0, 1.0);
}
void main() {
    if (gl_LocalInvocationIndex == 0u) anyw = 0u;
    barrier();
    ivec2 base = ivec2(gl_WorkGroupID.xy) * 16 - RMAX;
    for (uint k = gl_LocalInvocationIndex; k < uint(T * T); k += 256u) {
        ivec2 q = clamp(base + ivec2(int(k) % T, int(k) / T), ivec2(0), u_size - 1);
        vec4 v = texelFetch(u_img, q, u_level);
        float w = tentWeight(v.a);
        tile[k] = uvec2(packHalf2x16(v.rg * w), packHalf2x16(vec2(v.b * w, w)));
        if (w > 0.0) anyw = 1u;
    }
    barrier();
    ivec2 g = ivec2(gl_GlobalInvocationID.xy);
    if (g.x >= u_size.x || g.y >= u_size.y) return;
    if (anyw == 0u) { imageStore(u_dst, g, vec4(0.0)); return; }
    vec2 rel = (vec2(g) + 0.5) / vec2(u_size) - 0.5;
    vec2 sh = -rel * u_cat * u_r;
    int ri = int(ceil(u_r));
    vec4 acc = vec4(0.0);
    float wsum = 0.0;
    ivec2 l = ivec2(gl_LocalInvocationID.xy) + RMAX;
    for (int dy = -ri; dy <= ri; ++dy) {
        for (int dx = -ri; dx <= ri; ++dx) {
            vec2 o = vec2(dx, dy);
            float w = clamp(u_r + 0.5 - length(o), 0.0, 1.0) * clamp(u_r + 0.5 - length(o - sh), 0.0, 1.0);
            if (w > 0.0) {
                uvec2 t = tile[(l.y + dy) * T + l.x + dx];
                acc += w * vec4(unpackHalf2x16(t.x), unpackHalf2x16(t.y));
                wsum += w;
            }
        }
    }
    imageStore(u_dst, g, acc / max(wsum, 1e-6));
}
